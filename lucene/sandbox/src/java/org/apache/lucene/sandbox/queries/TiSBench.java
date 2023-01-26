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
package org.apache.lucene.sandbox.queries;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.sandbox.search.DocValuesTermsQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// java -cp "lucene/sandbox/build/libs/lucene-sandbox-10.0.0-SNAPSHOT.jar:lucene/core/build/libs/lucene-core-10.0.0-SNAPSHOT.jar" TiSBench.java /tmp/allCountries.txt /tmp/idx -1

/** Benchmark set queries on 1M lines of Geonames. */
public class TiSBench {

  public static void main(String args[]) throws Exception {
    if (args.length != 3) {
      System.err.println("Usage: NumSetBenchmark /path/to/geonames.txt /path/to/index/dir doc_limit(or -1 means index all lines)");
      System.exit(2);
    }

    String geonamesDataPath = args[0];
    String indexPath = args[1];
    int docLimit = Integer.parseInt(args[2]);

    IOUtils.rm(Paths.get(indexPath));
    try (FSDirectory dir = FSDirectory.open(Paths.get(indexPath))) {

      System.err.println("Now run indexing");
      IndexWriterConfig config = new IndexWriterConfig();
      try (IndexWriter iw = new IndexWriter(dir, config);
           LineNumberReader reader = new LineNumberReader(new InputStreamReader(Files.newInputStream(Paths.get(geonamesDataPath))))) {
        long t0 = System.nanoTime();
        indexDocs(iw, reader, docLimit);
        System.out.println(String.format(Locale.ROOT, "Indexing time: %d msec", (System.nanoTime() - t0) / 1_000_000));
      }

      System.err.println("Index files: " + Arrays.toString(dir.listAll()));

      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        System.out.print("BIG_BIG: ");
        doBench(reader, BIG_BIG, "cc");
        System.out.print("MEDIUM_BIG: ");
        doBench(reader, MEDIUM_BIG, "cc");
        System.out.print("SMALL_BIG: ");
        doBench(reader, SMALL_BIG, "cc");
        System.out.println();
        System.out.print("BIG_MEDIUM: ");
        doBench(reader, BIG_MEDIUM, "cc");
        System.out.print("MEDIUM_MEDIUM: ");
        doBench(reader, MEDIUM_MEDIUM, "cc");
        System.out.print("SMALL_MEDIUM: ");
        doBench(reader, SMALL_MEDIUM, "cc");
        System.out.println();
        System.out.print("BIG_PK: ");
        doBench(reader, BIG_PK, "id");
        System.out.print("MEDIUM_PK: ");
        doBench(reader, MEDIUM_PK, "id");
        System.out.print("SMALL_PK: ");
        doBench(reader, SMALL_PK, "id");
        System.out.println();
        System.out.print("BIG_BIG COMBINED: ");
        doBench(reader, BIG_BIG, "combined");
        System.out.print("MEDIUM_BIG COMBINED: ");
        doBench(reader, MEDIUM_BIG, "combined");
        System.out.print("SMALL_BIG COMBINED: ");
        doBench(reader, SMALL_BIG, "combined");
      }
    }
    System.out.println("dummy=" + DUMMY);
  }

  static void doBench(IndexReader reader, String[] queries, String filterField) throws Exception {
    int iters = 300;
    // warmup
    for (int i = 0; i < iters; ++i) {
      getDocs(reader, queries, filterField, true);
      getDocs(reader, queries, filterField,  false);
    }
    // Take the min across multiple runs to decrease noise
    long minDurationNS = Long.MAX_VALUE;
    for (int i = 0; i < iters; ++i) {
      long t0 = System.nanoTime();
      getDocs(reader, queries, filterField,  true);
      minDurationNS = Math.min(minDurationNS, System.nanoTime() - t0);
    }
    System.out.print(String.format(Locale.ROOT, "count=%.5f msec", minDurationNS / 1_000_000.));
    minDurationNS = Long.MAX_VALUE;
    for (int i = 0; i < iters; ++i) {
      long t0 = System.nanoTime();
      getDocs(reader, queries, filterField,  false);
      minDurationNS = Math.min(minDurationNS, System.nanoTime() - t0);
    }
    System.out.println(String.format(Locale.ROOT, " search=%.5f msec", minDurationNS / 1_000_000.));
  }

  static void indexDocs(IndexWriter iw, LineNumberReader reader, int docLimit) throws Exception {
    Document doc = new Document();
    TextField name = new TextField("name", "", Field.Store.NO);
    doc.add(name);
    StringField id = new StringField("id", "", Field.Store.NO);
    doc.add(id);
    StringField cc = new StringField("cc", "", Field.Store.NO);
    doc.add(cc);
    StringField combinedId = new StringField("combined", "", Field.Store.NO);
    doc.add(combinedId);
    StringField combinedCC = new StringField("combined", "", Field.Store.NO);
    doc.add(combinedCC);
    SortedDocValuesField idDv = new SortedDocValuesField("id", new BytesRef());
    doc.add(idDv);
    SortedDocValuesField ccDv = new SortedDocValuesField("cc", new BytesRef());
    doc.add(ccDv);
    SortedSetDocValuesField combinedIdDv = new SortedSetDocValuesField("combined", new BytesRef());
    doc.add(combinedIdDv);
    SortedSetDocValuesField combinedCCDv = new SortedSetDocValuesField("combined", new BytesRef());
    doc.add(combinedCCDv);

    String line = null;
    while ((line = reader.readLine()) != null) {
      if (reader.getLineNumber() % 10000 == 0) {
        System.err.println("doc: " + reader.getLineNumber());
      }
      if (docLimit != -1 && reader.getLineNumber() == docLimit) {
        break;
      }
      String values[] = line.split("\t");
      id.setStringValue(values[0]);
      name.setStringValue(values[1]);
      cc.setStringValue(values[8]);
      combinedId.setStringValue(values[0]);
      combinedCC.setStringValue(values[8]);
      idDv.setBytesValue(new BytesRef(values[0]));
      ccDv.setBytesValue(new BytesRef(values[8]));
      combinedIdDv.setBytesValue(new BytesRef(values[0]));
      combinedCCDv.setBytesValue(new BytesRef(values[8]));
      iw.addDocument(doc);
    }
  }

  static int DUMMY;

  // for histogram:
  // cut -f 8 allCountries.txt | sort | uniq -c | sort -nr | more
  // "smaller numbers are more common"

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
   * "parc" -> 2287
   * "by" -> 6794
   *
   * "channel" -> 4186
   * "centre" -> 4615
   * "st" -> 6616
   * "imperial" -> 663
   * "silent" -> 99
   * "sant" -> 863
   * "andorra" -> 49
   */

  /*
   * term counts (country_code field):
   * 2240232 US
   * 874153 CN
   * 649062 IN
   * 607434 NO
   * 455292 MX
   * 447465 ID
   * 366929 RU
   * 314332 CA
   * 255167 TH
   * 243458 IR
   * 225948 PK
   * 214006 AU
   * 199201 DE
   * 169307 FR
   * 153182 MA
   * 140885 NP
   * 132473 KR
   * 129968 BR
   * 119854 IT
   * 105131 PE
   *
   *  1 YU
   *  2 AN
   *  2 CS
   *  41 PN
   *  47 BV
   *  60 CC
   *  71 SM
   *  81 NF
   *  87 CX
   *  97 HM
   *  99 MC
   *  109 NR
   *  115 VA
   *  118 TK
   *  119 IO
   *  132 BL
   *  168 IM
   *  174 MO
   *  199 SX
   *  229 MF
   */
  static final String[] BIG_BIG = new String[] {
      "la|AD,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",    // 100471 hits
      "de|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",    // 85069 hits
      "saint|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",   // 59566 hits
      "canyon|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",   // 26734 hits
  };

  static final String[] MEDIUM_BIG = new String[] {
      "hotel|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE", // 33077 hits
      "del|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",   // 16614 hits
      "les|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",   // 9180 hits
      "plaza|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",   // 7961 hits
      "parc|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",   // 1873 hits
      "by|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",   // 5580 hits
  };

  static final String[] SMALL_BIG = new String[] {
      "channel|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",   // 3132 hits
      "centre|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",   // 2336 hits
      "st|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",   // 4174 hits
      "imperial|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",   // 449 hits
      "silent|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",   // 90 hits
      "sant|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",   // 120 hits
      "andorra|US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE",   // 13 hits
  };

  static final String[] BIG_MEDIUM = new String[] {
      "la|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF",    // 14 hits
      "de|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF",    // 33 hits
      "saint|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MFL",   // 6 hits
      "canyon|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF",   // 0 hits
  };

  static final String[] MEDIUM_MEDIUM = new String[] {
      "hotel|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF", // 8 hits
      "del|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF",   // 17 hits
      "les|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF",   // 5 hits
      "plaza|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF",   // 0 hits
      "parc|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF",   // 0 hits
      "by|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF",   // 0 hits
  };

  static final String[] SMALL_MEDIUM = new String[] {
      "channel|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF",   // 4 hits
      "centre|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF",   // 1 hits
      "st|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF",   // 3 hits
      "imperial|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF",   // 0 hits
      "silent|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF",   // 0 hits
      "sant|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF",   // 0 hits
      "andorra|YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF",   // 0 hits
  };

  static final String[] BIG_PK = new String[] {
      "la|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815",
      "de|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815",
      "saint|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815",
      "canyon|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815"
  };

  static final String[] MEDIUM_PK = new String[] {
      "hotel|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815",
      "del|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815",
      "les|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815",
      "plaza|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815",
      "parc|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815",
      "by|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815"
  };

  static final String[] SMALL_PK = new String[] {
      "channel|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815",
      "centre|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815",
      "st|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815",
      "imperial|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815",
      "silent|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815",
      "sant|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815",
      "andorra|2986043,2994701,3007683,3017832,3017833,3023203,3029315,3034945,3038814,3038815"
  };

  static void getDocs(IndexReader reader, String[] queries, String filterField, boolean doCount) throws IOException {
    IndexSearcher searcher = new IndexSearcher(reader);
    searcher.setQueryCache(null); // benchmarking

    for (String textQuery : queries) {
      String parts[] = textQuery.split("\\|");
      String filterParts[] = parts[1].split(",");
      List<BytesRef> filterTerms = Arrays.stream(filterParts).map(BytesRef::new).toList();
      BooleanQuery.Builder builder = new BooleanQuery.Builder();
      builder.add(new BooleanClause(new TermQuery(new Term("name", parts[0])), BooleanClause.Occur.MUST));
      Query q1 = new TermInSetQuery(filterField, filterTerms);
      Query q2 = new DocValuesTermsQuery(filterField, filterTerms);
      Query q = new IndexOrDocValuesQuery(q1, q2);
//      Query q = new TermInSetQuery(filterField, filterTerms);
//      Query q = new DocValuesTermsQuery(filterField, filterTerms);
      builder.add(new BooleanClause(q, BooleanClause.Occur.MUST));
      if (doCount) {
        int hits = searcher.count(builder.build());
        DUMMY += hits;
      } else {
        int hits = (int) searcher.search(builder.build(), 10).totalHits.value;
        DUMMY += hits;
      }
    }
  }
}
