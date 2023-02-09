/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.sandbox;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KeywordField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInvertState;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermStatistics;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

// NOTE: Make sure to shuffle allCountries.txt prior to benchmarking, otherwise postings-based approaches
// have a significant, artificial advantage due to the data already being sorted by country code.
// java -cp "lucene/sandbox/build/libs/lucene-sandbox-10.0.0-SNAPSHOT.jar:lucene/core/build/libs/lucene-core-10.0.0-SNAPSHOT.jar" TiSBench.java /tmp/allCountries.txt /tmp/idx -1

/** Benchmark set queries on 1M lines of Geonames. */
public class TiSBench {

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      System.err.println("Usage: NumSetBenchmark /path/to/geonames.txt /path/to/index/dir doc_limit(or -1 means index all lines)");
      System.exit(2);
    }

    String geonamesDataPath = args[0];
    String indexPath = args[1];
    int docLimit = Integer.parseInt(args[2]);

    Path path = Paths.get(indexPath);
//    IOUtils.rm(path);
    try (FSDirectory dir = FSDirectory.open(path)) {
//      System.err.println("Now run indexing");
//      IndexWriterConfig config = new IndexWriterConfig();
//      try (IndexWriter iw = new IndexWriter(dir, config);
//           LineNumberReader reader = new LineNumberReader(new InputStreamReader(Files.newInputStream(Paths.get(geonamesDataPath))))) {
//        long t0 = System.nanoTime();
//        indexDocs(iw, reader, docLimit);
//        System.out.printf(Locale.ROOT, "Indexing time: %d msec%n", (System.nanoTime() - t0) / 1_000_000);
//      }
      System.err.println("Index files: " + Arrays.toString(dir.listAll()));

      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        Random r = new Random();
        Similarity sim = new Similarity() {
          @Override
          public long computeNorm(FieldInvertState state) {
            return 1;
          }

          @Override
          public SimScorer scorer(float boost, CollectionStatistics collectionStats, TermStatistics... termStats) {
            return new SimScorer() {
              @Override
              public float score(float freq, long norm) {
                return r.nextFloat();
              }
            };
          }
        };
        searcher.setSimilarity(sim);
        int numIds = 50000;
        TopDocs td = searcher.search(new MatchAllDocsQuery(), numIds);
        ScoreDoc[] hits = td.scoreDocs;
        assert hits.length == numIds;
        // this is kind of terrible, but I just need to grab a bunch of ids:
        List<BytesRef> ids = new ArrayList<>(numIds);
        for (int i = 0; i < numIds; i++) {
          ScoreDoc hit = hits[i];
          String id = reader.storedFields().document(hit.doc).get("id");
          assert id != null;
          ids.add(new BytesRef(id));
        }
        Collections.shuffle(ids);

        System.out.println("| Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |");
        System.out.println("|---|---|---|---|");
//        doBench(reader, LARGE_NAME_TERMS, "id", ids);
//        doBench(reader, MEDIUM_NAME_TERMS, "id", ids);
//        doBench(reader, SMALL_NAME_TERMS, "id", ids);
        doBench(reader, null, "id", ids.toArray(new BytesRef[0]));
        System.out.println("|");
      }
    }
  }

  static void doBench(IndexReader reader, String[] leads, String filterField, BytesRef[] filterTerms) throws Exception {
    int iters = 300;
    // warmup
    for (int i = 0; i < iters; ++i) {
      getDocs(reader, leads, filterField, filterTerms);
    }
    // Take the min across multiple runs to decrease noise
    long minDurationNS = Long.MAX_VALUE;
    for (int i = 0; i < iters; ++i) {
      long t0 = System.nanoTime();
      getDocs(reader, leads, filterField, filterTerms);
      minDurationNS = Math.min(minDurationNS, System.nanoTime() - t0);
    }
    System.out.printf(Locale.ROOT, "| %.2f ", minDurationNS / 1_000_000.);
  }

  static void indexDocs(IndexWriter iw, LineNumberReader reader, int docLimit) throws Exception {
    Document doc = new Document();
    TextField name = new TextField("name", "", Field.Store.NO);
    doc.add(name);
    KeywordField id = new KeywordField("id", "", Field.Store.YES);
    doc.add(id);

    String line;
    while ((line = reader.readLine()) != null) {
      if (reader.getLineNumber() % 10000 == 0) {
        System.err.println("doc: " + reader.getLineNumber());
      }
      if (docLimit != -1 && reader.getLineNumber() == docLimit) {
        break;
      }
      String[] values = line.split("\t");
      id.setStringValue(values[0]);
      name.setStringValue(values[1]);
      iw.addDocument(doc);
    }
  }

  static void getDocs(IndexReader reader, String[] leads, String filterField, BytesRef[] filterTerms) throws IOException {
    IndexSearcher searcher = new IndexSearcher(reader);
    searcher.setQueryCache(null); // benchmarking

    Query filterQuery = KeywordField.newSetQuery(filterField, filterTerms);
    if (leads != null) {
      for (String lead : leads) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(new BooleanClause(new TermQuery(new Term("name", lead)), BooleanClause.Occur.MUST));
        builder.add(filterQuery, BooleanClause.Occur.MUST);
        searcher.search(builder.build(), 100);
      }
    } else {
      searcher.search(filterQuery, 100);
    }
  }

  /*
   * term counts (name field):
   * "la" -> 181425
   * "de" -> 171095
   * "saint" -> 62831
   * "canyon" -> 27503
   *
   * "hotel" -> 64349
   * "del" -> 37469
   * "les" -> 13111
   * "plaza" -> 10605
   *
   * "channel" -> 4186
   * "centre" -> 4615
   * "st" -> 6616
   * "imperial" -> 663
   */
  static final String[] LARGE_NAME_TERMS =
      {"la", "de", "saint", "canyon"};
  static final String[] MEDIUM_NAME_TERMS =
      {"hotel", "del", "les", "plaza"};
  static final String[] SMALL_NAME_TERMS =
      {"channel", "centre", "st", "imperial"};
}
