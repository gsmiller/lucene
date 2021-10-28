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
package org.apache.lucene.search;

import java.io.IOException;
import java.util.List;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BytesRef;

public class TestIndexOrDocValuesQuery extends LuceneTestCase {

  public void testUseIndexForSelectiveTermInSetQueries() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter w = new RandomIndexWriter(random(), dir);

    for (int i = 0; i < 2000; ++i) {
      Document doc = new Document();
      if (i == 42) {
        doc.add(new StringField("f1", "bar", Store.NO));
        doc.add(new StringField("f2", "42", Store.NO));
        doc.add(new SortedDocValuesField("f2", new BytesRef("42")));
      } else if (i == 100) {
        doc.add(new StringField("f1", "foo", Store.NO));
        doc.add(new StringField("f2", "2", Store.NO));
        doc.add(new SortedDocValuesField("f2", new BytesRef("2")));
      } else {
        doc.add(new StringField("f1", "bar", Store.NO));
        doc.add(new StringField("f2", "2", Store.NO));
        doc.add(new SortedDocValuesField("f2", new BytesRef("2")));
      }
      w.addDocument(doc);
    }
    w.forceMerge(1);
    IndexReader reader = w.getReader();
    IndexSearcher searcher = newSearcher(reader);
    searcher.setQueryCache(null);

    // The term query is more selective, so the IndexOrDocValuesQuery should use doc values
    final List<BytesRef> queryTerms = List.of(new BytesRef("2"));
    final Query q1 =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("f1", "foo")), Occur.MUST)
            .add(
                new IndexOrDocValuesQuery(
                    new TermInSetQuery("f2", queryTerms),
                    SortedDocValuesField.newSlowTermInSetQuery("f2", queryTerms)),
                Occur.MUST)
            .build();

    final Weight w1 = searcher.createWeight(searcher.rewrite(q1), ScoreMode.COMPLETE, 1);
    final Scorer s1 = w1.scorer(searcher.getIndexReader().leaves().get(0));
    assertNotNull(s1.twoPhaseIterator()); // means we use doc values

    // The term query is less selective, so the IndexOrDocValuesQuery should use points
    final Query q2 =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("f1", "bar")), Occur.MUST)
            .add(
                new IndexOrDocValuesQuery(
                    new TermInSetQuery("f2", queryTerms),
                    SortedDocValuesField.newSlowTermInSetQuery("f2", queryTerms)),
                Occur.MUST)
            .build();

    final Weight w2 = searcher.createWeight(searcher.rewrite(q2), ScoreMode.COMPLETE, 1);
    final Scorer s2 = w2.scorer(searcher.getIndexReader().leaves().get(0));
    assertNull(s2.twoPhaseIterator()); // means we use index postings

    reader.close();
    w.close();
    dir.close();
  }

  public void testUseIndexForSelectiveTermInSetMultiValueQueries() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter w = new RandomIndexWriter(random(), dir);

    for (int i = 0; i < 2000; ++i) {
      Document doc = new Document();
      if (i == 42) {
        doc.add(new StringField("f1", "bar", Store.NO));
        doc.add(new StringField("f2", "42", Store.NO));
        doc.add(new SortedSetDocValuesField("f2", new BytesRef("42")));
      } else if (i == 100) {
        doc.add(new StringField("f1", "foo", Store.NO));
        doc.add(new StringField("f2", "2", Store.NO));
        doc.add(new SortedSetDocValuesField("f2", new BytesRef("2")));
      } else {
        doc.add(new StringField("f1", "bar", Store.NO));
        doc.add(new StringField("f2", "2", Store.NO));
        doc.add(new SortedSetDocValuesField("f2", new BytesRef("2")));
      }
      w.addDocument(doc);
    }
    w.forceMerge(1);
    IndexReader reader = w.getReader();
    IndexSearcher searcher = newSearcher(reader);
    searcher.setQueryCache(null);

    // The term query is more selective, so the IndexOrDocValuesQuery should use doc values
    final List<BytesRef> queryTerms = List.of(new BytesRef("2"));
    final Query q1 =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("f1", "foo")), Occur.MUST)
            .add(
                new IndexOrDocValuesQuery(
                    new TermInSetQuery("f2", queryTerms),
                    SortedSetDocValuesField.newSlowTermInSetQuery("f2", queryTerms)),
                Occur.MUST)
            .build();

    final Weight w1 = searcher.createWeight(searcher.rewrite(q1), ScoreMode.COMPLETE, 1);
    final Scorer s1 = w1.scorer(searcher.getIndexReader().leaves().get(0));
    assertNotNull(s1.twoPhaseIterator()); // means we use doc values

    // The term query is less selective, so the IndexOrDocValuesQuery should use points
    final Query q2 =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("f1", "bar")), Occur.MUST)
            .add(
                new IndexOrDocValuesQuery(
                    new TermInSetQuery("f2", queryTerms),
                    SortedSetDocValuesField.newSlowTermInSetQuery("f2", queryTerms)),
                Occur.MUST)
            .build();

    final Weight w2 = searcher.createWeight(searcher.rewrite(q2), ScoreMode.COMPLETE, 1);
    final Scorer s2 = w2.scorer(searcher.getIndexReader().leaves().get(0));
    assertNull(s2.twoPhaseIterator()); // means we use index postings

    reader.close();
    w.close();
    dir.close();
  }

  public void testUseIndexForSelectiveQueries() throws IOException {
    Directory dir = newDirectory();
    IndexWriter w =
        new IndexWriter(
            dir,
            newIndexWriterConfig()
                // relies on costs and PointValues.estimateCost so we need the default codec
                .setCodec(TestUtil.getDefaultCodec()));
    for (int i = 0; i < 2000; ++i) {
      Document doc = new Document();
      if (i == 42) {
        doc.add(new StringField("f1", "bar", Store.NO));
        doc.add(new LongPoint("f2", 42L));
        doc.add(new NumericDocValuesField("f2", 42L));
      } else if (i == 100) {
        doc.add(new StringField("f1", "foo", Store.NO));
        doc.add(new LongPoint("f2", 2L));
        doc.add(new NumericDocValuesField("f2", 2L));
      } else {
        doc.add(new StringField("f1", "bar", Store.NO));
        doc.add(new LongPoint("f2", 2L));
        doc.add(new NumericDocValuesField("f2", 2L));
      }
      w.addDocument(doc);
    }
    w.forceMerge(1);
    IndexReader reader = DirectoryReader.open(w);
    IndexSearcher searcher = newSearcher(reader);
    searcher.setQueryCache(null);

    // The term query is more selective, so the IndexOrDocValuesQuery should use doc values
    final Query q1 =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("f1", "foo")), Occur.MUST)
            .add(
                new IndexOrDocValuesQuery(
                    LongPoint.newExactQuery("f2", 2),
                    NumericDocValuesField.newSlowRangeQuery("f2", 2L, 2L)),
                Occur.MUST)
            .build();

    final Weight w1 = searcher.createWeight(searcher.rewrite(q1), ScoreMode.COMPLETE, 1);
    final Scorer s1 = w1.scorer(searcher.getIndexReader().leaves().get(0));
    assertNotNull(s1.twoPhaseIterator()); // means we use doc values

    // The term query is less selective, so the IndexOrDocValuesQuery should use points
    final Query q2 =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("f1", "bar")), Occur.MUST)
            .add(
                new IndexOrDocValuesQuery(
                    LongPoint.newExactQuery("f2", 42),
                    NumericDocValuesField.newSlowRangeQuery("f2", 42L, 42L)),
                Occur.MUST)
            .build();

    final Weight w2 = searcher.createWeight(searcher.rewrite(q2), ScoreMode.COMPLETE, 1);
    final Scorer s2 = w2.scorer(searcher.getIndexReader().leaves().get(0));
    assertNull(s2.twoPhaseIterator()); // means we use points

    reader.close();
    w.close();
    dir.close();
  }

  public void testUseIndexForSelectiveMultiValueQueries() throws IOException {
    Directory dir = newDirectory();
    IndexWriter w =
        new IndexWriter(
            dir,
            newIndexWriterConfig()
                // relies on costs and PointValues.estimateCost so we need the default codec
                .setCodec(TestUtil.getDefaultCodec()));
    for (int i = 0; i < 2000; ++i) {
      Document doc = new Document();
      if (i < 1000) {
        doc.add(new StringField("f1", "bar", Store.NO));
        for (int j = 0; j < 500; j++) {
          doc.add(new LongPoint("f2", 42L));
          doc.add(new SortedNumericDocValuesField("f2", 42L));
        }
      } else if (i == 1001) {
        doc.add(new StringField("f1", "foo", Store.NO));
        doc.add(new LongPoint("f2", 2L));
        doc.add(new SortedNumericDocValuesField("f2", 42L));
      } else {
        doc.add(new StringField("f1", "bar", Store.NO));
        for (int j = 0; j < 100; j++) {
          doc.add(new LongPoint("f2", 2L));
          doc.add(new SortedNumericDocValuesField("f2", 2L));
        }
      }
      w.addDocument(doc);
    }
    w.forceMerge(1);
    IndexReader reader = DirectoryReader.open(w);
    IndexSearcher searcher = newSearcher(reader);
    searcher.setQueryCache(null);

    // The term query is less selective, so the IndexOrDocValuesQuery should use points
    final Query q1 =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("f1", "bar")), Occur.MUST)
            .add(
                new IndexOrDocValuesQuery(
                    LongPoint.newExactQuery("f2", 2),
                    SortedNumericDocValuesField.newSlowRangeQuery("f2", 2L, 2L)),
                Occur.MUST)
            .build();

    final Weight w1 = searcher.createWeight(searcher.rewrite(q1), ScoreMode.COMPLETE, 1);
    final Scorer s1 = w1.scorer(searcher.getIndexReader().leaves().get(0));
    assertNull(s1.twoPhaseIterator()); // means we use points

    // The term query is less selective, so the IndexOrDocValuesQuery should use points
    final Query q2 =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("f1", "bar")), Occur.MUST)
            .add(
                new IndexOrDocValuesQuery(
                    LongPoint.newExactQuery("f2", 42),
                    SortedNumericDocValuesField.newSlowRangeQuery("f2", 42, 42L)),
                Occur.MUST)
            .build();

    final Weight w2 = searcher.createWeight(searcher.rewrite(q2), ScoreMode.COMPLETE, 1);
    final Scorer s2 = w2.scorer(searcher.getIndexReader().leaves().get(0));
    assertNull(s2.twoPhaseIterator()); // means we use points

    // The term query is more selective, so the IndexOrDocValuesQuery should use doc values
    final Query q3 =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("f1", "foo")), Occur.MUST)
            .add(
                new IndexOrDocValuesQuery(
                    LongPoint.newExactQuery("f2", 42),
                    SortedNumericDocValuesField.newSlowRangeQuery("f2", 42, 42L)),
                Occur.MUST)
            .build();

    final Weight w3 = searcher.createWeight(searcher.rewrite(q3), ScoreMode.COMPLETE, 1);
    final Scorer s3 = w3.scorer(searcher.getIndexReader().leaves().get(0));
    assertNotNull(s3.twoPhaseIterator()); // means we use doc values

    reader.close();
    w.close();
    dir.close();
  }

  // Weight#count is delegated to the inner weight
  public void testQueryMatchesCount() throws Exception {
    Directory dir = newDirectory();
    IndexWriter w =
        new IndexWriter(
            dir,
            newIndexWriterConfig()
                // relies on costs and PointValues.estimateCost so we need the default codec
                .setCodec(TestUtil.getDefaultCodec()));
    final int numDocs = random().nextInt(5000);
    for (int i = 0; i < numDocs; ++i) {
      Document doc = new Document();
      doc.add(new LongPoint("f2", 42L));
      doc.add(new SortedNumericDocValuesField("f2", 42L));
      w.addDocument(doc);
    }
    w.forceMerge(1);
    IndexReader reader = DirectoryReader.open(w);
    IndexSearcher searcher = newSearcher(reader);

    final IndexOrDocValuesQuery query =
        new IndexOrDocValuesQuery(
            LongPoint.newExactQuery("f2", 42),
            SortedNumericDocValuesField.newSlowRangeQuery("f2", 42, 42L));

    final int searchCount = searcher.count(query);
    final Weight weight = searcher.createWeight(query, ScoreMode.COMPLETE, 1);
    int weightCount = 0;
    for (LeafReaderContext leafReaderContext : reader.leaves()) {
      weightCount += weight.count(leafReaderContext);
    }
    assertEquals(searchCount, weightCount);

    reader.close();
    w.close();
    dir.close();
  }
}
