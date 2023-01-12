package org.apache.lucene.sandbox.queries;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;

public class TestTermInSetQuery extends LuceneTestCase {
  public void testDuel() throws IOException {
    final int iters = atLeast(10);
    final String field = "f";
    for (int iter = 0; iter < iters; ++iter) {
      final List<BytesRef> allTerms = new ArrayList<>();
      final int numTerms = TestUtil.nextInt(random(), 1, 1 << TestUtil.nextInt(random(), 1, 20));
      for (int i = 0; i < numTerms; ++i) {
        final String value = TestUtil.randomAnalysisString(random(), 10, true);
        allTerms.add(newBytesRef(value));
      }
      Directory dir = newDirectory();
      RandomIndexWriter iw = new RandomIndexWriter(random(), dir);
      final int numDocs = atLeast(100);
      for (int i = 0; i < numDocs; ++i) {
        Document doc = new Document();
        final BytesRef term = allTerms.get(random().nextInt(allTerms.size()));
        doc.add(new StringField(field, term, Field.Store.NO));
        doc.add(new SortedDocValuesField(field, term));
        if (random().nextDouble() < 0.2) {
          doc.add(new StringField("small", new BytesRef("a"), Field.Store.NO));
        }
        iw.addDocument(doc);
      }
      if (numTerms > 1 && random().nextBoolean()) {
        iw.deleteDocuments(new TermQuery(new Term(field, allTerms.get(0))));
      }
      iw.commit();
      final IndexReader reader = iw.getReader();
      final IndexSearcher searcher = newSearcher(reader);
      iw.close();

      if (reader.numDocs() == 0) {
        // may occasionally happen if all documents got the same term
        IOUtils.close(reader, dir);
        continue;
      }

      for (int i = 0; i < 100; ++i) {
        final float boost = random().nextFloat() * 10;
        final int numQueryTerms =
            TestUtil.nextInt(random(), 1, 1 << TestUtil.nextInt(random(), 1, 18));
        List<BytesRef> queryTerms = new ArrayList<>();
        for (int j = 0; j < numQueryTerms; ++j) {
          queryTerms.add(allTerms.get(random().nextInt(allTerms.size())));
        }

        Query q1 = new org.apache.lucene.search.TermInSetQuery(field, queryTerms);
        Query q2 = new TermInSetQuery(field, queryTerms);

        if (random().nextBoolean()) {
          Query restrictive = new TermQuery(new Term("small", "a"));
          BooleanQuery.Builder bq = new BooleanQuery.Builder();
          bq.add(restrictive, BooleanClause.Occur.MUST);
          bq.add(q1, BooleanClause.Occur.MUST);
          q1 = bq.build();
          bq = new BooleanQuery.Builder();
          bq.add(restrictive, BooleanClause.Occur.MUST);
          bq.add(q2, BooleanClause.Occur.MUST);
          q2 = bq.build();
        }

        assertSameMatches(searcher, new BoostQuery(q1, boost), new BoostQuery(q2, boost), true);
      }

      reader.close();
      dir.close();
    }
  }

  private void assertSameMatches(IndexSearcher searcher, Query q1, Query q2, boolean scores)
      throws IOException {
    final int maxDoc = searcher.getIndexReader().maxDoc();
    final TopDocs td1 = searcher.search(q1, maxDoc, scores ? Sort.RELEVANCE : Sort.INDEXORDER);
    final TopDocs td2 = searcher.search(q2, maxDoc, scores ? Sort.RELEVANCE : Sort.INDEXORDER);
    assertEquals(td1.totalHits.value, td2.totalHits.value);
    for (int i = 0; i < td1.scoreDocs.length; ++i) {
      assertEquals(td1.scoreDocs[i].doc, td2.scoreDocs[i].doc);
      if (scores) {
        assertEquals(td1.scoreDocs[i].score, td2.scoreDocs[i].score, 10e-7);
      }
    }
  }
}
