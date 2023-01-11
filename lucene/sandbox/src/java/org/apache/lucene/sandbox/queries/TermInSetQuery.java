package org.apache.lucene.sandbox.queries;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReader;
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
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.DocIdSetBuilder;
import org.apache.lucene.util.LongBitSet;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

public class TermInSetQuery extends Query {
  private static final double J = 1.0;
  private static final double K = 1.0;

  private final String field;
  private final BytesRef[] terms;
  private final int termsHashCode;

  public TermInSetQuery(String field, Collection<BytesRef> terms) {
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
    termsHashCode = Arrays.hashCode(this.terms);
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

        if (terms.length <= 1) {
          throw new IllegalStateException("Must call IndexSearcher#rewrite");
        }

        FieldInfo fi = context.reader().getFieldInfos().fieldInfo(field);
        if (fi == null) {
          return null;
        }

        final long cost;
        Terms indexTerms = context.reader().terms(field);
        long potentialExtraCost = indexTerms.getSumDocFreq();
        final long indexedTermCount = indexTerms.size();
        if (indexedTermCount != -1) {
          potentialExtraCost -= indexedTermCount;
        }
        cost = terms.length + potentialExtraCost;

        Weight weight = this;
        return new ScorerSupplier() {

          @Override
          public Scorer get(long leadCost) throws IOException {

            assert terms.length > 1;

            // If there are no doc values indexed, we have to use a postings-based approach:
            if (fi.getDocValuesType() != DocValuesType.SORTED_SET && fi.getDocValuesType() != DocValuesType.SORTED) {
              if (terms.length > IndexSearcher.getMaxClauseCount()) {
                // TODO: There are times when we probably want to combine term/doc at a time:
                return termAtATimeScorer(weight, context);
              } else {
                return docAtATimeScorer(weight, context);
              }
            }

            if (terms.length >= J * leadCost) {
              // If there are a lot of terms relative to candidate docs, using DV is best:
              return docValuesScorer(weight, context);
            } else {
              Terms t = context.reader().terms(field);
              if (t == null) {
                return null;
              }

              // Compute the "total density" of all term postings:
              long totalDensity = 0;
              TermsEnum termsEnum = t.iterator();
              int fieldDocCount = t.getDocCount();
              TermState[] termStates = new TermState[terms.length];
              for (int i = 0; i < terms.length; i++) {
                BytesRef term = terms[i];
                if (termsEnum.seekExact(term) == false) {
                  termStates[i] = null;
                } else {
                  // If we find a "completely dense" postings list, we can use it directly:
                  if (fieldDocCount == termsEnum.docFreq()) {
                    PostingsEnum postings = termsEnum.postings(null, PostingsEnum.NONE);
                    return docAtATimeScorer(weight, 1, new DisiWrapper(postings));
                  }
                  totalDensity += termsEnum.docFreq();
                  termStates[i] = termsEnum.termState();
                }
              }

              // Heuristic that determines whether-or-not to use DV or postings. Based on ratio
              // of total density to candidate size:
              if (totalDensity >= (K * leadCost)) {
                return docValuesScorer(weight, context);
              } else {
                return docAtATimeScorer(weight, termsEnum, termStates);
              }
            }
          }

          @Override
          public long cost() {
            return cost;
          }
        };
      }

      private Scorer docAtATimeScorer(Weight weight, LeafReaderContext context) throws IOException {
        Terms t = context.reader().terms(field);
        TermsEnum termsEnum = t.iterator();
        int fieldDocCount = t.getDocCount();

        DisiWrapper[] disiWrappers = new DisiWrapper[terms.length];
        int disiCount = 0;
        for (BytesRef term : terms) {
          if (termsEnum.seekExact(term)) {
            PostingsEnum postings = termsEnum.postings(null, PostingsEnum.NONE);
            DisiWrapper disiWrapper = new DisiWrapper(postings);
            if (fieldDocCount == termsEnum.docFreq()) {
              return docAtATimeScorer(weight, 1, disiWrapper);
            }
            disiWrappers[disiCount] = disiWrapper;
            disiCount++;
          }
        }

        return docAtATimeScorer(weight, disiCount, disiWrappers);
      }

      private Scorer docAtATimeScorer(Weight weight, TermsEnum termsEnum, TermState... termStates) throws IOException {
        DisiWrapper[] disiWrappers = new DisiWrapper[terms.length];
        int disiCount = 0;
        for (int i = 0; i < terms.length; i++) {
          TermState termState = termStates[i];
          if (termState != null) {
            termsEnum.seekExact(terms[i], termState);
            PostingsEnum postings = termsEnum.postings(null, PostingsEnum.NONE);
            disiWrappers[disiCount] = new DisiWrapper(postings);
            disiCount++;
          }
        }

        return docAtATimeScorer(weight, disiCount, disiWrappers);
      }
      
      private Scorer termAtATimeScorer(Weight weight, LeafReaderContext context) throws IOException {
        LeafReader reader = context.reader();

        Terms t = reader.terms(field);
        TermsEnum termsEnum = t.iterator();
        int fieldDocCount = t.getDocCount();

        DocIdSetIterator it = null;
        DocIdSetBuilder hits = new DocIdSetBuilder(reader.maxDoc());
        boolean foundAtLeastOneTerm = false;
        PostingsEnum postings = null;
        for (BytesRef term : terms) {
          if (termsEnum.seekExact(term)) {
            foundAtLeastOneTerm = true;
            postings = termsEnum.postings(postings, PostingsEnum.NONE);
            if (fieldDocCount == termsEnum.docFreq()) {
              it = postings;
              break;
            }
            hits.add(postings);
          }
        }

        if (it == null) {
          if (foundAtLeastOneTerm == false) {
            it = DocIdSetIterator.empty();
          } else {
            it = hits.build().iterator();
          }
        }

        return new ConstantScoreScorer(weight, score(), scoreMode, it);
      }

      private Scorer docAtATimeScorer(Weight weight, int disiCount, DisiWrapper... disiWrappers) {
        final DocIdSetIterator it;
        if (disiCount == 0) {
          it = DocIdSetIterator.empty();
        } else if (disiCount == 1) {
          it = disiWrappers[0].iterator;
        } else {
          DisiPriorityQueue pq = new DisiPriorityQueue(disiCount);
          pq.addAll(disiWrappers, 0, disiCount);

          long c = 0;
          for (int i = 0; i < disiCount; i++) {
            c += disiWrappers[i].cost;
          }
          final long cost = c;

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
        return false;
      }
    };
  }

  @Override
  public Query rewrite(IndexSearcher indexSearcher) throws IOException {
    if (terms.length == 0) {
      return new MatchNoDocsQuery();
    }
    if (terms.length == 1) {
      return new TermQuery(new Term(field, terms[0]));
    }
    return this;
  }

  @Override
  public void visit(QueryVisitor visitor) {
    if (visitor.acceptField(field) == false) {
      return;
    }

    final Term[] allTerms = new Term[terms.length];
    for (int i = 0; i < terms.length; i++) {
      allTerms[i] = new Term(field, terms[i]);
    }
    visitor.consumeTerms(this, allTerms);
  }

  @Override
  public String toString(String field) {
    StringBuilder sb = new StringBuilder(field + ":(");
    boolean first = true;
    for (BytesRef t : terms) {
      if (first == false) {
        sb.append(' ');
      }
      sb.append(Term.toString(t));
      first = false;
    }
    sb.append(')');
    return sb.toString();
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (sameClassAs(other) == false) {
      return false;
    }
    TermInSetQuery o = (TermInSetQuery) other;
    return field.equals(o.field) && termsHashCode == o.termsHashCode && Arrays.equals(terms, o.terms);
  }

  @Override
  public int hashCode() {
    return Objects.hash(field, termsHashCode);
  }
}
