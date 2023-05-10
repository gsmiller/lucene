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

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermInSetQueryLegacy;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;

// NOTE: Make sure to shuffle allCountries.txt prior to benchmarking, otherwise postings-based
// approaches
// have a significant, artificial advantage due to the data already being sorted by country code.
// java -cp
// "lucene/sandbox/build/libs/lucene-sandbox-10.0.0-SNAPSHOT.jar:lucene/core/build/libs/lucene-core-10.0.0-SNAPSHOT.jar" TiSBench.java /tmp/allCountries.txt /tmp/idx -1

/** Benchmark set queries on 1M lines of Geonames. */
public class TiSBench {

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      System.err.println(
          "Usage: NumSetBenchmark /path/to/geonames.txt /path/to/index/dir doc_limit(or -1 means index all lines)");
      System.exit(2);
    }

    String geonamesDataPath = args[0];
    String indexPath = args[1];
    int docLimit = Integer.parseInt(args[2]);

    Path path = Paths.get(indexPath);
    IOUtils.rm(path);
    try (FSDirectory dir = FSDirectory.open(path)) {
      System.err.println("Now run indexing");
      IndexWriterConfig config = new IndexWriterConfig();
      try (IndexWriter iw = new IndexWriter(dir, config);
          LineNumberReader reader =
              new LineNumberReader(
                  new InputStreamReader(Files.newInputStream(Paths.get(geonamesDataPath))))) {
        long t0 = System.nanoTime();
        indexDocs(iw, reader, docLimit);
        System.out.printf(
            Locale.ROOT, "Indexing time: %d msec%n", (System.nanoTime() - t0) / 1_000_000);
      }
      System.err.println("Index files: " + Arrays.toString(dir.listAll()));

      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        System.out.println("| Task | Postings MTQ | Postings Automata | DV MTQ | DV Automata |");
        System.out.println("|---|---|---|---|---|");
        benchTask(reader, "All Country Code Filter Terms", "cc", ALL_CC_TERMS);
        benchTask(
            reader,
            "Medium Cardinality + High Cost Country Code Filter Terms",
            "cc",
            MEDIUM_CARDINALITY_HIGH_COST_CC_TERMS);
        benchTask(
            reader,
            "Low Cardinality + High Cost Country Code Filter Terms",
            "cc",
            LOW_CARDINALITY_HIGH_COST_CC_TERMS);
        benchTask(
            reader,
            "Medium Cardinality + Low Cost Country Code Filter Terms",
            "cc",
            MEDIUM_CARDINALITY_LOW_COST_CC_TERMS);
        benchTask(
            reader,
            "Low Cardinality + Low Cost Country Code Filter Terms",
            "cc",
            LOW_CARDINALITY_LOW_COST_CC_TERMS);
        benchTask(reader, "High Cardinality PK Filter Terms", "id", HIGH_CARDINALITY_PK_TERMS);
        benchTask(reader, "Medium Cardinality PK Filter Terms", "id", MEDIUM_CARDINALITY_PK_TERMS);
        benchTask(reader, "Low Cardinality PK Filter Terms", "id", LOW_CARDINALITY_PK_TERMS);
      }
    }
  }

  enum Approach {
    POSTINGS_MTQ,
    POSTINGS_AUTOMATA,
    DV_MTQ,
    DV_AUTOMATA
  }

  private static void benchTask(
      DirectoryReader reader, String task, String filterField, String filterTerms)
      throws Exception {
    System.out.print("| " + task + " ");
    doBench(reader, filterField, filterTerms, Approach.POSTINGS_MTQ);
    doBench(reader, filterField, filterTerms, Approach.POSTINGS_AUTOMATA);
    doBench(reader, filterField, filterTerms, Approach.DV_MTQ);
    doBench(reader, filterField, filterTerms, Approach.DV_AUTOMATA);
    System.out.println("|");
  }

  static void doBench(IndexReader reader, String filterField, String filterTerms, Approach approach)
      throws Exception {
    int iters = 300;
    // warmup
    for (int i = 0; i < iters; ++i) {
      getDocs(reader, filterField, filterTerms, approach);
    }
    // Take the min across multiple runs to decrease noise
    long minDurationNS = Long.MAX_VALUE;
    for (int i = 0; i < iters; ++i) {
      long t0 = System.nanoTime();
      getDocs(reader, filterField, filterTerms, approach);
      minDurationNS = Math.min(minDurationNS, System.nanoTime() - t0);
    }
    System.out.printf(Locale.ROOT, "| %.2f ", minDurationNS / 1_000_000.);
  }

  static void indexDocs(IndexWriter iw, LineNumberReader reader, int docLimit) throws Exception {
    Document doc = new Document();
    TextField name = new TextField("name", "", Field.Store.NO);
    doc.add(name);
    StringField id = new StringField("id", "", Field.Store.NO);
    doc.add(id);
    StringField cc = new StringField("cc", "", Field.Store.NO);
    doc.add(cc);
    SortedDocValuesField idDv = new SortedDocValuesField("id", new BytesRef());
    SortedDocValuesField ccDv = new SortedDocValuesField("cc", new BytesRef());
    doc.add(idDv);
    doc.add(ccDv);

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
      cc.setStringValue(values[8]);
      idDv.setBytesValue(new BytesRef(values[0]));
      ccDv.setBytesValue(new BytesRef(values[8]));
      iw.addDocument(doc);
    }
  }

  static int DUMMY;

  static void getDocs(IndexReader reader, String filterField, String filterTerms, Approach approach)
      throws IOException {
    IndexSearcher searcher = new IndexSearcher(reader);
    searcher.setQueryCache(null); // benchmarking

    List<BytesRef> terms = Arrays.stream(filterTerms.split(",")).map(BytesRef::new).toList();
    Query filterQuery;
    switch (approach) {
      case POSTINGS_MTQ -> filterQuery = new TermInSetQueryLegacy(filterField, terms);
      case POSTINGS_AUTOMATA -> filterQuery = new TermInSetQuery(filterField, terms);
      case DV_MTQ -> filterQuery =
          new TermInSetQueryLegacy(MultiTermQuery.DOC_VALUES_REWRITE, filterField, terms);
      case DV_AUTOMATA -> filterQuery =
          new TermInSetQuery(MultiTermQuery.DOC_VALUES_REWRITE, filterField, terms);
      default -> throw new IllegalStateException("no");
    }

    DUMMY += searcher.count(filterQuery);
  }

  // for country code histogram:
  // cut -f 9 allCountries.txt | sort | uniq -c | sort -nr | more

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

  static final String ALL_CC_TERMS =
      "AD,AE,AF,AG,AI,AL,AM,AN,AO,AQ,AR,AS,AT,AU,AW,AX,AZ,BA,BB,BD,BE,BF,BG,BH,BI,BJ,BL,"
          + "BM,BN,BO,BQ,BR,BS,BT,BV,BW,BY,BZ,CA,CC,CD,CF,CG,CH,CI,CK,CL,CM,CN,CO,CR,CS,CU,CV,CW,CX,"
          + "CY,CZ,DE,DJ,DK,DM,DO,DZ,EC,EE,EG,EH,ER,ES,ET,FI,FJ,FK,FM,FO,FR,GA,GB,GD,GE,GF,GG,GH,GI,"
          + "GL,GM,GN,GP,GQ,GR,GS,GT,GU,GW,GY,HK,HM,HN,HR,HT,HU,ID,IE,IL,IM,IN,IO,IQ,IR,IS,IT,JE,JM,"
          + "JO,JP,KE,KG,KH,KI,KM,KN,KP,KR,KW,KY,KZ,LA,LB,LC,LI,LK,LR,LS,LT,LU,LV,LY,MA,MC,MD,ME,MF,"
          + "MG,MH,MK,ML,MM,MN,MO,MP,MQ,MR,MS,MT,MU,MV,MW,MX,MY,MZ,NA,NC,NE,NF,NG,NI,NL,NO,NP,NR,NU,"
          + "NZ,OM,PA,PE,PF,PG,PH,PK,PL,PM,PN,PR,PS,PT,PW,PY,QA,RE,RO,RS,RU,RW,SA,SB,SC,SD,SE,SG,SH,"
          + "SI,SJ,SK,SL,SM,SN,SO,SR,SS,ST,SV,SX,SY,SZ,TC,TD,TF,TG,TH,TJ,TK,TL,TM,TN,TO,TR,TT,TV,TW,"
          + "TZ,UA,UG,UM,US,UY,UZ,VA,VC,VE,VG,VI,VN,VU,WF,WS,XK,YE,YT,YU,ZA,ZM,ZW"; // 254 terms

  static final String MEDIUM_CARDINALITY_HIGH_COST_CC_TERMS =
      "US,CN,IN,NO,MX,ID,RU,CA,TH,IR,PK,AU,DE,FR,MA,NP,KR,BR,IT,PE"; // 20 terms

  static final String MEDIUM_CARDINALITY_LOW_COST_CC_TERMS =
      "YU,AN,CS,PN,BV,CC,SM,NF,CX,HM,MC,NR,VA,TK,IO,BL,IM,MO,SX,MF"; // 20 terms

  static final String LOW_CARDINALITY_HIGH_COST_CC_TERMS =
      "US,CN,IN,NO,MX,ID,RU,CA,TH,IR"; // 10 terms

  static final String LOW_CARDINALITY_LOW_COST_CC_TERMS =
      "YU,AN,CS,PN,BV,CC,SM,NF,CX,HM"; // 10 terms

  static final String HIGH_CARDINALITY_PK_TERMS =
      "1663540,2309214,7336256,3397112,9513415,11576397,8142991,3422630,9799485,12273722,"
          + "6464500,890305,11318450,2358764,11519878,10857753,3779957,11966610,5361768,8347911,"
          + "8943710,4159316,4228910,1331777,9070983,8700931,9235499,258667,337675,6923429,9994198,"
          + "4482060,8315692,9635313,8661214,10349521,621067,10861607,11510719,6468194,7873166,"
          + "7409515,938984,8709824,3461699,3189267,8136013,4666348,3698467,3007476,2153319,2923288,"
          + "5240516,11210520,2804151,7778496,3070227,1570855,9039465,9512103,3650456,8023215,"
          + "11311977,6345314,5875474,9567748,11516937,783018,11913262,2569062,78149,7139629,"
          + "4141874,475523,5010749,10152317,10006849,6145629,1091838,3315773,9747290,5762470,"
          + "1806886,8577795,7006406,3328372,12101512,2652906,12305562,1770923,3496377,10917733,"
          + "8747969,1468612,3905880,9752549,10822172,1711066,3904876,11133893,2217059,4139169,"
          + "9390649,8776784,247242,8162245,8567872,3654166,4815097,1897880,8043115,9583080,"
          + "11657242,10672376,1248390,5294322,1220669,11156881,4973532,7357060,3288667,10376723,"
          + "9612873,4512812,9739618,1060799,11612918,5096654,10856754,4128108,7881289,3617839,"
          + "11967348,10628952,11601639,12501908,7889121,719132,11940896,2656431,5287777,12052708,"
          + "10052198,8954423,7973523,10241750,3902980,2345759,1060684,5151885,5031707,5064246,"
          + "1759418,1243144,8293377,3556445,4889915,6628645,8410481,12265119,157571,10994181,"
          + "9724746,2564805,9320847,8517668,5389511,11043256,11682175,3012310,1774358,7842550,"
          + "7823038,10498099,1217119,6957721,2491270,2627944,11104007,4453693,12300354,9221972,"
          + "10576242,2515005,8711847,2065798,936101,8192003,505607,7230369,2890121,3621783,"
          + "10839791,676175,5355478,7240238,10413941,6386021,677944,1341911,5350499,8988612,"
          + "12429291,1256201,7336969,5314192,5740475,2175936,11327514,5928139,10464531,10128936,"
          + "6761564,11599456,3361592,5366360,2206657,6908258,680169,4175180,11323464,4032399,"
          + "9789375,12399499,11973865,10508455,11358762,5249271,11603533,7217764,3036898,3061255,"
          + "8607301,3970628,6943730,3524214,9654521,11251492,3086811,4427610,10430386,1801368,"
          + "8862857,4856724,10761478,2820655,10988324,11852828,826917,5893282,10822239,4101603,"
          + "10655128,8797027,8751637,2308119,11306084,5832392,1499858,9272094,7015325,11004908,"
          + "1048868,1287236,4681239,8040884,11856153,8573630,3863198,246909,4251378,6328540,"
          + "2682562,4751666,3011780,12368000,11012489,988428,6874100,3904669,1207597,1049954,"
          + "3225030,11693123,8030043,7279239,11158228,1136935,3463696,2876195,9138136,12172210,"
          + "919871,1240184,4027419,10338419,8785965,8068281,9968712,5126873,2080616,9404049,"
          + "9686581,9979049,5863075,2435064,4617559,1080804,8862238,9480114,7516479,5386425,"
          + "6307146,7172065,531828,5062603,8922995,5008927,788991,11968904,4469664,794850,3432795,"
          + "5982334,1947625,7375688,2187910,11604025,11245217,1420661,5095861,6265208,8570553,"
          + "1488738,12477962,3640594,4504508,1291997,2453878,1648336,5043031,7685041,1555763,"
          + "6895288,9118472,2122306,5054601,6043208,8506567,605201,5214023,12357661,7061182,"
          + "2841591,6401081,5127814,896698,2483190,5374097,6874665,579276,12411607,10207653,"
          + "8444842,3205900,4265077,10033312,9432703,5491847,6450149,10574364,9058890,7263481,"
          + "8340598,4592189,1181258,8157502,9189079,454055,10478084,5333567,8168908,11235007,"
          + "8624730,7453433,1408612,10175485,3287099,5513405,3125837,6628833,9295571,11663847,"
          + "10792833,4141251,2608336,974894,6547477,11296830,4219296,12135330,11221005,4378330,"
          + "7194771,2896814,614194,2271159,4168689,6174794,2713514,12323934,11657672,8859690,"
          + "2084194,7266813,465653,7889662,7619771,151642,147219,5349991,876238,58718,2501425,"
          + "5841588,11087914,4935071,1039818,4228738,10011140,12202944,8068171,5920209,4658662,"
          + "2847764,4012498,10805917,11419718,10936320,12236916,6004234,4557967,6117859,6385578,"
          + "7789487,10351511,2584244,2756647,4690284,3732568,6520656,912200,10301114,6328292,"
          + "4053886,11047529,4076953,2657485,5221977,9176281,5492326,11959229,3621671,10112728,"
          + "6461240,1171663,5978869,8266335,5362483,7779084,7882414,11509838,12374050,3646105,"
          + "8900493,1539096,757767,8777938,9567645,4471247,1152165,9085040,6481713,9994331,5881194,"
          + "11180136,9017702,1897,2880753,3699097,4505827,6181958,2905972,2306790,3826537,3827005,"
          + "3558623,11974885,11389969,11821878"; // 500 terms

  static final String MEDIUM_CARDINALITY_PK_TERMS =
      "1663540,2309214,7336256,3397112,9513415,11576397,8142991,3422630,9799485,12273722,"
          + "6464500,890305,11318450,2358764,11519878,10857753,3779957,11966610,5361768,8347911"; // 20 terms

  static final String LOW_CARDINALITY_PK_TERMS =
      "1663540,2309214,7336256,3397112,9513415,11576397,8142991,3422630,9799485,12273722"; // 10
  // terms
}
