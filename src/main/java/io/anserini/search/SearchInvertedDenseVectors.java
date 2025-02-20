/*
 * Anserini: A Lucene toolkit for reproducible information retrieval research
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.anserini.search;

import io.anserini.analysis.fw.FakeWordsEncoderAnalyzer;
import io.anserini.index.Constants;
import io.anserini.rerank.ScoredDocuments;
import io.anserini.search.query.InvertedDenseVectorQueryGenerator;
import io.anserini.search.topicreader.TopicReader;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.FSDirectory;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionHandlerFilter;
import org.kohsuke.args4j.ParserProperties;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.anserini.index.IndexInvertedDenseVectors.FW;

/**
 * Main entry point for inverted dense vector search.
 */
public final class SearchInvertedDenseVectors implements Closeable {
  private static final Logger LOG = LogManager.getLogger(SearchInvertedDenseVectors.class);

  public static class Args {
    @Option(name = "-index", metaVar = "[path]", required = true, usage = "Path to Lucene index")
    public String index;

    @Option(name = "-topics", metaVar = "[file]", handler = StringArrayOptionHandler.class, usage = "topics file")
    public String[] topics;

    @Option(name = "-output", metaVar = "[file]", usage = "output file")
    public String output;

    @Option(name = "-topicreader", usage = "TopicReader to use.")
    public String topicReader;

    @Option(name = "-topicfield", usage = "Which field of the query should be used, default \"title\"." +
        " For TREC ad hoc topics, description or narrative can be used.")
    public String topicfield = "title";

    @Option(name = "-encoding", metaVar = "[word]", required = true, usage = "encoding must be one of {fw, lexlsh}")
    public String encoding;

    @Option(name = "-lexlsh.n", metaVar = "[int]", usage = "ngrams")
    public int ngrams = 2;

    @Option(name = "-lexlsh.d", metaVar = "[int]", usage = "decimals")
    public int decimals = 1;

    @Option(name = "-lexlsh.hsize", metaVar = "[int]", usage = "hash set size")
    public int hashSetSize = 1;

    @Option(name = "-lexlsh.h", metaVar = "[int]", usage = "hash count")
    public int hashCount = 1;

    @Option(name = "-lexlsh.b", metaVar = "[int]", usage = "bucket count")
    public int bucketCount = 300;

    @Option(name = "-fw.q", metaVar = "[int]", usage = "quantization factor")
    public int q = FakeWordsEncoderAnalyzer.DEFAULT_Q;

    @Option(name = "-threads", metaVar = "[int]", usage = "Number of threads to use for running different parameter configurations.")
    public int threads = 1;

    @Option(name = "-parallelism", metaVar = "[int]", usage = "Number of threads to use for each individual parameter configuration.")
    public int parallelism = 8;

    @Option(name = "-threadsPerQuery", metaVar = "[int]", usage = "Number of threads used to execute each query.")
    public int threadsPerQuery = 1;

    @Option(name = "-removeQuery", usage = "Remove docids that have the query id when writing final run output.")
    public Boolean removeQuery = false;

    // Note that this option is set to false by default because duplicate documents usually indicate some underlying
    // indexing issues, and we don't want to just eat errors silently.
    @Option(name = "-removedups", usage = "Remove duplicate docids when writing final run output.")
    public Boolean removedups = false;

    @Option(name = "-skipexists", usage = "When enabled, will skip if the run file exists")
    public Boolean skipexists = false;

    @Option(name = "-hits", metaVar = "[number]", required = false, usage = "max number of hits to return")
    public int hits = 1000;

    @Option(name = "-inmem", usage = "Boolean switch to read index in memory")
    public Boolean inmem = false;

    @Option(name = "-runtag", metaVar = "[tag]", usage = "runtag")
    public String runtag = null;

    @Option(name = "-format", metaVar = "[output format]", usage = "Output format, default \"trec\", alternative \"msmarco\".")
    public String format = "trec";

    // ---------------------------------------------
    // Simple built-in support for passage retrieval
    // ---------------------------------------------

    // A simple approach to passage retrieval is to pre-segment documents in the corpus into passages and index those
    // passages. At retrieval time, we retain only the max scoring passage from each document; this is often called MaxP,
    // from Dai and Callan (SIGIR 2019) in the context of BERT, although the general approach dates back to Callan
    // (SIGIR 1994), Hearst and Plaunt (SIGIR 1993), and lots of other papers from the 1990s and even earlier.
    //
    // One common convention is to label the passages of a docid as "docid.00000", "docid.00001", "docid.00002", ...
    // We use this convention in CORD-19. Alternatively, in document expansion for the MS MARCO document corpus, we use
    // '#' as the delimiter.
    //
    // The options below control various aspects of this behavior.

    @Option(name = "-selectMaxPassage", usage = "Select and retain only the max scoring segment from each document.")
    public Boolean selectMaxPassage = false;

    @Option(name = "-selectMaxPassage.delimiter", metaVar = "[regexp]",
        usage = "The delimiter (as a regular regression) for splitting the segment id from the doc id.")
    public String selectMaxPassage_delimiter = "\\.";

    @Option(name = "-selectMaxPassage.hits", metaVar = "[int]",
        usage = "Maximum number of hits to return per topic after segment id removal. " +
            "Note that this is different from '-hits', which specifies the number of hits including the segment id. ")
    public int selectMaxPassage_hits = Integer.MAX_VALUE;
  }

  private final Args args;
  private final IndexReader reader;

  private InvertedDenseVectorQueryGenerator generator;

  private final class SearcherThread<K> extends Thread {

    final private IndexReader reader;
    final private IndexSearcher searcher;
    final private SortedMap<K, Map<String, String>> topics;
    final private String outputPath;
    final private String runTag;

    private SearcherThread(IndexReader reader, SortedMap<K, Map<String, String>> topics, String outputPath, String runTag,
                           ExecutorService executorService, Similarity similarity) {
      this.reader = reader;
      this.topics = topics;
      this.runTag = runTag;
      this.outputPath = outputPath;
      this.searcher = executorService != null ? new IndexSearcher(this.reader, executorService) : new IndexSearcher(this.reader);
      if (similarity != null) {
        searcher.setSimilarity(similarity);
      }
      setName(outputPath);
    }

    @Override
    public void run() {
      try {
        // A short descriptor of the ranking setup.
        final String desc = String.format("ranker: kNN");
        // ThreadPool for parallelizing the execution of individual queries:
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(args.parallelism);
        // Data structure for holding the per-query results, with the qid as the key and the results (the lines that
        // will go into the final run file) as the value.
        ConcurrentSkipListMap<K, String> results = new ConcurrentSkipListMap<>();
        AtomicInteger cnt = new AtomicInteger();

        final long start = System.nanoTime();
        for (Map.Entry<K, Map<String, String>> entry : topics.entrySet()) {
          K qid = entry.getKey();

          // This is the per-query execution, in parallel.
          executor.execute(() -> {
            // This is for holding the results.
            StringBuilder out = new StringBuilder();
            String queryString = entry.getValue().get(args.topicfield);
            ScoredDocuments docs;
            try {
              docs = search(this.searcher, queryString);
            } catch (IOException e) {
              throw new CompletionException(e);
            }

            // For removing duplicate docids.
            Set<String> docids = new HashSet<>();

            int rank = 1;
            for (int i = 0; i < docs.documents.length; i++) {
              String docid = docs.documents[i].get(Constants.ID);

              if (args.selectMaxPassage) {
                docid = docid.split(args.selectMaxPassage_delimiter)[0];
              }

              if (docids.contains(docid)) {
                continue;
              }

              // Remove docids that are identical to the query id if flag is set.
              if (args.removeQuery && docid.equals(qid)) {
                continue;
              }

              if ("msmarco".equals(args.format)) {
                // MS MARCO output format:
                out.append(String.format(Locale.US, "%s\t%s\t%d\n", qid, docid, rank));
              } else {
                // Standard TREC format:
                // + the first column is the topic number.
                // + the second column is currently unused and should always be "Q0".
                // + the third column is the official document identifier of the retrieved document.
                // + the fourth column is the rank the document is retrieved.
                // + the fifth column shows the score (integer or floating point) that generated the ranking.
                // + the sixth column is called the "run tag" and should be a unique identifier for your
                out.append(String.format(Locale.US, "%s Q0 %s %d %f %s\n",
                                         qid, docid, rank, docs.scores[i], runTag));
              }

              // Note that this option is set to false by default because duplicate documents usually indicate some
              // underlying indexing issues, and we don't want to just eat errors silently.
              //
              // However, we we're performing passage retrieval, i.e., with "selectMaxSegment", we *do* want to remove
              // duplicates.
              if (args.removedups || args.selectMaxPassage) {
                docids.add(docid);
              }

              rank++;

              if (args.selectMaxPassage && rank > args.selectMaxPassage_hits) {
                break;
              }
            }

            results.put(qid, out.toString());
            int n = cnt.incrementAndGet();
            if (n % 100 == 0) {
              LOG.info(String.format("%s: %d queries processed", desc, n));
            }
          });
        }

        executor.shutdown();

        try {
          // Wait for existing tasks to terminate.
          while (!executor.awaitTermination(1, TimeUnit.MINUTES)) ;
        } catch (InterruptedException ie) {
          // (Re-)Cancel if current thread also interrupted.
          executor.shutdownNow();
          // Preserve interrupt status.
          Thread.currentThread().interrupt();
        }
        final long durationMillis = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start, TimeUnit.NANOSECONDS);

        LOG.info(desc + ": " + topics.size() + " queries processed in " +
                     DurationFormatUtils.formatDuration(durationMillis, "HH:mm:ss") +
                     String.format(" = ~%.2f q/s", topics.size() / (durationMillis / 1000.0)));

        // Now we write the results to a run file.
        PrintWriter out = new PrintWriter(Files.newBufferedWriter(Paths.get(outputPath), StandardCharsets.UTF_8));

        // This is the default case: just dump out the qids by their natural order.
        for (K qid : results.keySet()) {
          out.print(results.get(qid));
        }
        out.flush();
        out.close();
      } catch (Exception e) {
        LOG.error(Thread.currentThread().getName() + ": Unexpected Exception: ", e);
      }
    }
  }

  public SearchInvertedDenseVectors(Args args) throws IOException {
    this.args = args;
    Path indexPath = Paths.get(args.index);

    if (!Files.exists(indexPath) || !Files.isDirectory(indexPath) || !Files.isReadable(indexPath)) {
      throw new IllegalArgumentException(String.format("Index path '%s' does not exist or is not a directory.", args.index));
    }

    LOG.info("============ Initializing Searcher ============");
    LOG.info("Index: " + indexPath);
    this.reader = DirectoryReader.open(FSDirectory.open(indexPath));
    LOG.info("Vector Search:");
    LOG.info("Number of threads for running different parameter configurations: " + args.threads);
  }

  @Override
  public void close() throws IOException {
    reader.close();
  }

  @SuppressWarnings("unchecked")
  public <K> void runTopics() {
    generator = new InvertedDenseVectorQueryGenerator(args, true);
    TopicReader<K> tr;
    SortedMap<K, Map<String, String>> topics = new TreeMap<>();
    for (String singleTopicsFile : args.topics) {
      Path topicsFilePath = Paths.get(singleTopicsFile);
      if (!Files.exists(topicsFilePath) || !Files.isRegularFile(topicsFilePath) || !Files.isReadable(topicsFilePath)) {
        throw new IllegalArgumentException("Topics file : " + topicsFilePath + " does not exist or is not a (readable) file.");
      }
      try {
        tr = (TopicReader<K>) Class.forName("io.anserini.search.topicreader." + args.topicReader + "TopicReader")
            .getConstructor(Path.class).newInstance(topicsFilePath);
        topics.putAll(tr.read());
      } catch (Exception e) {
        e.printStackTrace();
        throw new IllegalArgumentException("Unable to load topic reader: " + args.topicReader);
      }
    }

    final String runTag = args.runtag == null ? "Anserini" : args.runtag;
    LOG.info("runtag: " + runTag);

    final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(args.threads);

    LOG.info("============ Launching Search Threads ============");

    ExecutorService queryExecutor = null;
    if (args.threadsPerQuery > 1) {
      queryExecutor = Executors.newFixedThreadPool(args.threadsPerQuery);
    }

    Similarity similarity = null;
    if (args.encoding.equalsIgnoreCase(FW)) {
      similarity = new ClassicSimilarity();
    }
    String outputPath = args.output;
    if (args.skipexists && new File(outputPath).exists()) {
      LOG.info("Run already exists, skipping: " + outputPath);
    } else {
      executor.execute(new SearcherThread<>(reader, topics, outputPath, runTag, queryExecutor, similarity));
      executor.shutdown();
      if (queryExecutor != null) {
        queryExecutor.shutdown();
      }
    }

    try {
      // Wait for existing tasks to terminate
      while (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
      }
      if (queryExecutor != null) {
        while (!queryExecutor.awaitTermination(1, TimeUnit.MINUTES)) {
        }
      }
    } catch (InterruptedException ie) {
      // (Re-)Cancel if current thread also interrupted
      executor.shutdownNow();
      // Preserve interrupt status
      Thread.currentThread().interrupt();
    }
  }

  public ScoredDocuments search(IndexSearcher searcher, String queryString) throws IOException {
    // If fieldsMap isn't null, then it means that the -fields option is specified. In this case, we search across
    // multiple fields with the associated boosts.
    Query query = generator.buildQuery(queryString);

    TopScoreDocCollector results = TopScoreDocCollector.create(args.hits, Integer.MAX_VALUE);
    searcher.search(query, results);

    return ScoredDocuments.fromTopDocs(results.topDocs(), searcher);
  }

  public static void main(String[] args) throws Exception {
    Args searchArgs = new Args();
    CmdLineParser parser = new CmdLineParser(searchArgs, ParserProperties.defaults().withUsageWidth(100));

    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.err.println("Example: SearchCollection" + parser.printExample(OptionHandlerFilter.REQUIRED));
      return;
    }

    final long start = System.nanoTime();
    SearchInvertedDenseVectors searcher;

    // We're at top-level already inside a main; makes no sense to propagate exceptions further, so reformat the
    // exception messages and display on console.
    try {
      searcher = new SearchInvertedDenseVectors(searchArgs);
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      return;
    }
    if (searchArgs.topicReader != null && searchArgs.topics != null) {
      searcher.runTopics();
    }

    searcher.close();
    final long durationMillis = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    LOG.info("Total run time: " + DurationFormatUtils.formatDuration(durationMillis, "HH:mm:ss"));
  }

}