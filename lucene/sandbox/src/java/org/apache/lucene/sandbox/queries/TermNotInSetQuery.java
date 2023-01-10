package org.apache.lucene.sandbox.queries;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermState;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DisiPriorityQueue;
import org.apache.lucene.search.DisiWrapper;
import org.apache.lucene.search.DocIdSetIterator;
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
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.LongBitSet;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class TermNotInSetQuery extends Query {
  private static final double K = 1.0;

  private final Query baseQuery;
  private final String field;
  private final BytesRef[] terms;

  public TermNotInSetQuery(String field, Collection<BytesRef> terms) {
    this(new MatchAllDocsQuery(), field, terms);
  }

  public TermNotInSetQuery(Query baseQuery, String field, Collection<BytesRef> terms) {
    this.baseQuery = baseQuery;
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
    Weight baseWeight = baseQuery.createWeight(searcher, scoreMode, boost);

    return new FilterWeight(baseWeight) {

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
        ScorerSupplier baseScoreSupplier = baseWeight.scorerSupplier(context);
        if (baseScoreSupplier == null) {
          return null;
        }

        FieldInfo fi = context.reader().getFieldInfos().fieldInfo(field);
        if (fi == null) {
          return baseScoreSupplier;
        }

        Weight weight = this;
        return new ScorerSupplier() {
          @Override
          public Scorer get(long leadCost) throws IOException {
            Scorer baseScorer = baseScoreSupplier.get(leadCost);
            
            if (fi.getDocValuesType() != DocValuesType.SORTED_SET && fi.getDocValuesType() != DocValuesType.SORTED) {
              if (terms.length > IndexSearcher.getMaxClauseCount()) {
                return termAtATimeScorer(baseScorer, weight, context);
              } else {
                return docAtATimeScorer(baseScorer, weight, context);
              }
            } else {
              final long numCandidates = Math.min(baseScorer.iterator().cost(), leadCost);
              if (terms.length >= numCandidates) {
                return docValuesScorer(baseScorer, weight, context);
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

                if (totalDensity / (K * numCandidates) >= 1.0) {
                  return docValuesScorer(baseScorer, weight, context);
                } else {
                  return docAtATimeScorer(baseScorer, weight, termStates, termsEnum);
                }
              }
            }
          }

          @Override
          public long cost() {
            return baseScoreSupplier.cost();
          }
        };
      }

      private Scorer docAtATimeScorer(Scorer baseScorer, Weight weight, LeafReaderContext context) throws IOException {
        DisiWrapper[] disiWrappers = new DisiWrapper[terms.length];
        int disiCount = 0;
        for (int i = 0; i < terms.length; i++) {
          PostingsEnum postings = context.reader().postings(new Term(field, terms[i]), PostingsEnum.NONE);
          if (postings != null) {
            disiWrappers[i] = new DisiWrapper(postings);
            disiCount++;
          }
        }

        return docAtATimeScorer(baseScorer, weight, disiWrappers, disiCount);
      }

      private Scorer docAtATimeScorer(Scorer baseScorer, Weight weight, TermState[] termStates, TermsEnum termsEnum) throws IOException {
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

        return docAtATimeScorer(baseScorer, weight, disiWrappers, disiCount);
      }
      
      private Scorer termAtATimeScorer(Scorer baseScorer, Weight weight, LeafReaderContext context) throws IOException {
        BitSet excludedDocs = new FixedBitSet(context.reader().maxDoc());
        boolean foundAtLeastOneTerm = false;
        for (BytesRef term : terms) {
          PostingsEnum postings = context.reader().postings(new Term(field, term), PostingsEnum.NONE);
          if (postings != null) {
            foundAtLeastOneTerm = true;
            for (int doc = postings.nextDoc(); doc < DocIdSetIterator.NO_MORE_DOCS; doc = postings.nextDoc()) {
              excludedDocs.set(doc);
            }
          }
        }

        if (foundAtLeastOneTerm == false) {
          return baseScorer;
        }

        return new FilterScorer(weight, baseScorer) {
          @Override
          public TwoPhaseIterator twoPhaseIterator() {
            TwoPhaseIterator baseTwoPhase = baseScorer.twoPhaseIterator();
            final DocIdSetIterator baseApproximation;
            if (baseTwoPhase == null) {
              baseApproximation = baseScorer.iterator();
            } else {
              baseApproximation = baseTwoPhase.approximation();
            }

            return new TwoPhaseIterator(baseApproximation) {
              @Override
              public boolean matches() throws IOException {
                if (excludedDocs.get(docID())) {
                  return false;
                }

                return baseTwoPhase == null || baseTwoPhase.matches();
              }

              @Override
              public float matchCost() {
                return 0;
              }
            };
          }
        };
      }

      private static Scorer docAtATimeScorer(Scorer baseScorer, Weight weight, DisiWrapper[] disiWrappers, int disiCount) {
        if (disiCount == 0) {
          return baseScorer;
        }

        DisiPriorityQueue pq = new DisiPriorityQueue(disiCount);
        pq.addAll(disiWrappers, 0, disiCount);
        
        return new FilterScorer(weight, baseScorer) {
          @Override
          public TwoPhaseIterator twoPhaseIterator() {
            TwoPhaseIterator baseTwoPhase = baseScorer.twoPhaseIterator();
            final DocIdSetIterator baseApproximation;
            if (baseTwoPhase == null) {
              baseApproximation = baseScorer.iterator();
            } else {
              baseApproximation = baseTwoPhase.approximation();
            }

            return new TwoPhaseIterator(baseApproximation) {
              @Override
              public boolean matches() throws IOException {
                int doc = docID();
                DisiWrapper top = pq.top();
                while (top.doc < doc) {
                  top.doc = top.iterator.advance(doc);
                  if (top.doc == doc) {
                    pq.updateTop();
                    return false;
                  }
                  top = pq.updateTop();
                }
                return top.doc != doc && (baseTwoPhase == null || baseTwoPhase.matches());
              }

              @Override
              public float matchCost() {
                return pq.size();
              }
            };
          }
        };
      }
      
      private Scorer docValuesScorer(Scorer baseScorer, Weight weight, LeafReaderContext context) throws IOException {
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
          return baseScorer;
        }

        return new FilterScorer(weight, baseScorer) {
          @Override
          public TwoPhaseIterator twoPhaseIterator() {
            TwoPhaseIterator baseTwoPhase = baseScorer.twoPhaseIterator();
            final DocIdSetIterator baseApproximation;
            if (baseTwoPhase == null) {
              baseApproximation = baseScorer.iterator();
            } else {
              baseApproximation = baseTwoPhase.approximation();
            }

            return new TwoPhaseIterator(baseApproximation) {
              @Override
              public boolean matches() throws IOException {
                if (dv.advanceExact(docID()) == false) {
                  return baseTwoPhase == null || baseTwoPhase.matches();
                }
                for (int i = 0; i < dv.docValueCount(); i++) {
                  if (ords.get(dv.nextOrd())) {
                    return false;
                  }
                }
                return baseTwoPhase == null || baseTwoPhase.matches();
              }

              @Override
              public float matchCost() {
                return 1f;
              }
            };
          }
        };
      }
      
      @Override
      public boolean isCacheable(LeafReaderContext ctx) {
        return false;
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

  private static abstract class FilterScorer extends Scorer {
    private final Scorer in;

    FilterScorer(Weight weight, Scorer in) {
      super(weight);
      this.in = in;
    }

    @Override
    public abstract TwoPhaseIterator twoPhaseIterator();

    @Override
    public DocIdSetIterator iterator() {
      return TwoPhaseIterator.asDocIdSetIterator(twoPhaseIterator());
    }

    @Override
    public float getMaxScore(int upTo) throws IOException {
      return in.getMaxScore(upTo);
    }

    @Override
    public float score() throws IOException {
      return in.score();
    }

    @Override
    public int docID() {
      return in.docID();
    }

    @Override
    public int advanceShallow(int target) throws IOException {
      return in.advanceShallow(target);
    }

    @Override
    public void setMinCompetitiveScore(float minScore) throws IOException {
      in.setMinCompetitiveScore(minScore);
    }

    @Override
    public Collection<ChildScorable> getChildren() throws IOException {
      return in.getChildren();
    }
  }
}
