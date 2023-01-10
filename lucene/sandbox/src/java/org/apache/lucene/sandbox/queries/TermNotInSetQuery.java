package org.apache.lucene.sandbox.queries;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermState;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DisiPriorityQueue;
import org.apache.lucene.search.DisiWrapper;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.FilterWeight;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BitDocIdSet;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.DocIdSetBuilder;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.LongBitSet;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class TermNotInSetQuery extends Query {
  private static final double J = 1.0;
  private static final double K = 1.0;

  private final String field;
  private final BytesRef[] terms;

  public TermNotInSetQuery(String field, Collection<BytesRef> terms) {
    this.field = field;

    final Set<BytesRef> uniqueTerms;
    if (terms instanceof Set<BytesRef>) {
      uniqueTerms = (Set<BytesRef>) terms;
    } else {
      uniqueTerms = new HashSet<>(terms);
    }
    this.terms = new BytesRef[uniqueTerms.size()];
    Iterator<BytesRef> it = uniqueTerms.iterator();
    for (int i = 0; i < uniqueTerms.size(); i++) {
      assert it.hasNext();
      this.terms[i] = it.next();
    }
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {

    return new ConstantScoreWeight(this,  boost) {

      @Override
      public Scorer scorer(LeafReaderContext context) throws IOException {
        ScorerSupplier supplier = scorerSupplier(context);
        if (supplier == null) {
          return null;
        } else {
          return supplier.get(Long.MAX_VALUE);
        }
      }
      
      @Override
      public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {

        if (terms.length == 0) {
          return null;
        }

        FieldInfo fi = context.reader().getFieldInfos().fieldInfo(field);
        if (fi == null) {
          return null;
        }

        final long cost;
        final long queryTermsCount = terms.length;
        Terms indexTerms = context.reader().terms(field);
        long potentialExtraCost = indexTerms.getSumDocFreq();
        final long indexedTermCount = indexTerms.size();
        if (indexedTermCount != -1) {
          potentialExtraCost -= indexedTermCount;
        }
        cost = queryTermsCount + potentialExtraCost;

        Weight weight = this;
        return new ScorerSupplier() {

          @Override
          public Scorer get(long leadCost) throws IOException {

            if (terms.length == 1) {
              return docAtATimeScorer(weight, context);
            }

            if (fi.getDocValuesType() != DocValuesType.SORTED_SET && fi.getDocValuesType() != DocValuesType.SORTED) {
              if (terms.length > IndexSearcher.getMaxClauseCount()) {
                return termAtATimeScorer(weight, context);
              } else {
                return docAtATimeScorer(weight, context);
              }
            }

            if (terms.length >= J * leadCost) {
              return docValuesScorer(weight, context);
            } else {
              long totalDensity = 0;
              TermsEnum termsEnum = context.reader().terms(field).iterator();
              TermState[] termStates = new TermState[terms.length];
              for (int i = 0; i < terms.length; i++) {
                BytesRef term = terms[i];
                if (termsEnum.seekExact(term) == false) {
                  termStates[i] = null;
                } else {
                  totalDensity += termsEnum.docFreq();
                  termStates[i] = termsEnum.termState();
                }
              }

              if (totalDensity / (K * leadCost) >= 1.0) {
                return docValuesScorer(weight, context);
              } else {
                return docAtATimeScorer(weight, context, termStates, termsEnum);
              }
            }
          }

          @Override
          public long cost() {
            return cost;
          }
        };
      }

      @Override
      public Explanation explain(LeafReaderContext context, int doc) throws IOException {
        return null;
      }

      private Scorer docAtATimeScorer(Weight weight, LeafReaderContext context) throws IOException {
        DisiWrapper[] disiWrappers = new DisiWrapper[terms.length];
        int disiCount = 0;
        for (int i = 0; i < terms.length; i++) {
          PostingsEnum postings = context.reader().postings(new Term(field, terms[i]), PostingsEnum.NONE);
          if (postings != null) {
            disiWrappers[i] = new DisiWrapper(postings);
            disiCount++;
          }
        }

        return docAtATimeScorer(weight, context, disiWrappers, disiCount);
      }

      private Scorer docAtATimeScorer(Weight weight, LeafReaderContext context, TermState[] termStates, TermsEnum termsEnum) throws IOException {
        DisiWrapper[] disiWrappers = new DisiWrapper[terms.length];
        int disiCount = 0;
        for (int i = 0; i < terms.length; i++) {
          TermState termState = termStates[i];
          if (termState != null) {
            termsEnum.seekExact(terms[i], termState);
            PostingsEnum postings = termsEnum.postings(null, PostingsEnum.NONE);
            disiWrappers[i] = new DisiWrapper(postings);
            disiCount++;
          }
        }

        return docAtATimeScorer(weight, context, disiWrappers, disiCount);
      }
      
      private Scorer termAtATimeScorer(Weight weight, LeafReaderContext context) throws IOException {
        DocIdSetBuilder hits = new DocIdSetBuilder(context.reader().maxDoc());
        boolean foundAtLeastOneTerm = false;
        for (BytesRef term : terms) {
          PostingsEnum postings = context.reader().postings(new Term(field, term), PostingsEnum.NONE);
          if (postings != null) {
            foundAtLeastOneTerm = true;
            hits.add(postings);
          }
        }

        DocIdSetIterator it;
        if (foundAtLeastOneTerm == false) {
          it = DocIdSetIterator.empty();
        } else {
          it = hits.build().iterator();
        }

        return new ConstantScoreScorer(weight, score(), scoreMode, it);
      }

      private Scorer docAtATimeScorer(Weight weight, LeafReaderContext context, DisiWrapper[] disiWrappers, int disiCount) {
        final DocIdSetIterator it;
        if (disiCount == 0) {
          it = DocIdSetIterator.empty();
        } else if (disiCount == 1) {
          it = disiWrappers[0].iterator;
        } else {
          DisiPriorityQueue pq = new DisiPriorityQueue(disiCount);
          pq.addAll(disiWrappers, 0, disiCount);

          final long cost = Arrays.stream(disiWrappers).mapToLong(e -> e.cost).sum();

          it = new DocIdSetIterator() {
            private int docID = -1;

            @Override
            public int docID() {
              return docID;
            }

            @Override
            public int nextDoc() throws IOException {
              return doNext(docID + 1);
            }

            @Override
            public int advance(int target) throws IOException {
              return doNext(target);
            }

            private int doNext(int target) throws IOException {
              DisiWrapper top = pq.top();
              do {
                top.doc = top.approximation.advance(target);
                if (top.doc == target) {
                  pq.updateTop();
                  docID = target;
                  return docID;
                }
                top = pq.updateTop();
              } while (top.doc < target);

              docID = top.doc;
              return docID;
            }

            @Override
            public long cost() {
              return cost;
            }
          };
        }

        return new ConstantScoreScorer(weight, score(), scoreMode, it);
      }
      
      private Scorer docValuesScorer(Weight weight, LeafReaderContext context) throws IOException {
        SortedSetDocValues dv = DocValues.getSortedSet(context.reader(), field);

        boolean hasAtLeastOneTerm = false;
        LongBitSet ords = new LongBitSet(dv.getValueCount());
        for (BytesRef term : terms) {
          long ord = dv.lookupTerm(term);
          if (ord >= 0) {
            ords.set(ord);
            hasAtLeastOneTerm = true;
          }
        }

        if (hasAtLeastOneTerm == false) {
          return new ConstantScoreScorer(weight, score(), scoreMode, DocIdSetIterator.empty());
        }

        TwoPhaseIterator it = new TwoPhaseIterator(dv) {
          @Override
          public boolean matches() throws IOException {
            for (int i = 0; i < dv.docValueCount(); i++) {
              if (ords.get(dv.nextOrd())) {
                return true;
              }
            }
            return false;
          }

          @Override
          public float matchCost() {
            return 1f;
          }
        };

        return new ConstantScoreScorer(weight, score(), scoreMode, it);
      }
      
      @Override
      public boolean isCacheable(LeafReaderContext ctx) {
        return DocValues.isCacheable(ctx, field);
      }
    };
  }

  @Override
  public Query rewrite(IndexSearcher indexSearcher) throws IOException {
    return super.rewrite(indexSearcher);
  }

  @Override
  public void visit(QueryVisitor visitor) {

  }

  @Override
  public String toString(String field) {
    return null;
  }

  @Override
  public boolean equals(Object obj) {
    return false;
  }

  @Override
  public int hashCode() {
    return 0;
  }
}
