package org.apache.lucene.sandbox.queries;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
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
import org.apache.lucene.util.PriorityQueue;

public class TermInSetQuery extends Query {
  // TODO: tune
  private static final double J = 1.0;
  private static final double K = 1.0;
  private static final int L = 512;
  private static final int M = Math.min(IndexSearcher.getMaxClauseCount(), 64);

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
  public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
      throws IOException {

    return new ConstantScoreWeight(this, boost) {

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

        // If the field doesn't exist in the segment, return null:
        LeafReader reader = context.reader();
        FieldInfo fi = reader.getFieldInfos().fieldInfo(field);
        if (fi == null) {
          return null;
        }

        return new ScorerSupplier() {

          @Override
          public Scorer get(long leadCost) throws IOException {
            assert terms.length > 1;

            // If there are no doc values indexed, we have to use a postings-based approach:
            DocValuesType dvType = fi.getDocValuesType();
            if (dvType == DocValuesType.NONE) {
              return postingsScorer(reader);
            }

            if (terms.length > J * leadCost) {
              // TODO: The actual term count could be lower if there are terms not in the segment.
              // If the number of terms is > the number of candidates, a DV should perform better:
              return docValuesScorer(dvType, reader);
            } else {
              Terms t = reader.terms(field);
              if (t == null) {
                return docValuesScorer(dvType, reader);
              }

              // Assuming documents are randomly distributed (note: they may not be), as soon as
              // a given term's postings list size is >= the number of candidate documents, we
              // would expect a that term's postings to require advancing for every candidate doc
              // (in the average case). This cost is multiplied out by the number of terms, so we
              // can estimate the total amount of posting list advances we'll need to do by
              // taking the sum of the postings sizes across all of our terms. Unfortunately, it's
              // costly to get the actual postings sizes (we need to seek to each term), so we
              // do a gross estimate up-front by computing the average postings size. Further
              // complicating matters, we don't know how many of the terms are actually in the
              // segment (we assume they all are), and of course, our candidate size is also an
              // estimate:
              double avgTermAdvances = Math.min(leadCost, (double) t.getSumDocFreq() / t.size());
              double expectedTotalAdvances = avgTermAdvances * terms.length;
              if (expectedTotalAdvances > leadCost) { // TODO: tuning coefficient?
                return docValuesScorer(dvType, reader);
              }

              // TODO: #docFreq isn't free. Maybe this isn't worth it? Maybe we should just go
              // directly to using postings at this point?

              // At this point, it seems that using a postings approach may be best, so we'll
              // actually seek to all the terms and gather more accurate index statistics and make
              // one more decision. The hope is that we end up using a postings approach since all
              // this term seeking is wasted if we go DV.

              // Compute the "total density" of all term postings. This is wasted term-seeking work
              // if we end up using DVs. The good news is that the wasted work is proportional to
              // the number of terms, and if there are lots of terms, we hopefully don't drop into
              // this condition:
              long totalDensity = 0;
              int foundTermCount = 0;
              int fieldDocCount = t.getDocCount();
              TermsEnum termsEnum = t.iterator();
              TermState[] termStates = new TermState[terms.length];
              TermState lastTermState = null;
              BytesRef lastTerm = null;
              for (int i = 0; i < terms.length; i++) {
                BytesRef term = terms[i];
                if (termsEnum.seekExact(term) == false) {
                  continue;
                }

                // If we find a "completely dense" postings list, we can use it directly:
                int termDocFreq = termsEnum.docFreq();
                if (fieldDocCount == termDocFreq) {
                  PostingsEnum postings = termsEnum.postings(null, PostingsEnum.NONE);
                  return scorerFor(postings);
                }

                foundTermCount++;
                totalDensity += Math.min(termDocFreq, leadCost);
                lastTermState = termsEnum.termState();
                lastTerm = term;
                termStates[i] = lastTermState;
              }

              // We have some new information about how many terms were actually found in the
              // segment,
              // so make some special-case decisions based on that new information:
              if (foundTermCount == 0) {
                return emptyScorer();
              } else if (foundTermCount == 1) {
                termsEnum.seekExact(lastTerm, lastTermState);
                PostingsEnum postings = termsEnum.postings(null, PostingsEnum.NONE);
                return scorerFor(postings);
              }

              // TODO: Should we re-check a term count heuristic here now that we know how many
              // terms are actually in the segment?
              // Heuristic that determines whether-or-not to use DV or postings. Based on ratio
              // of total density to candidate size:
              if (totalDensity > (K * leadCost)) {
                return docValuesScorer(dvType, reader);
              } else {
                return postingsScorer(context.reader(), termsEnum, termStates);
              }
            }
          }

          @Override
          public long cost() {
            // TODO: Should we lazily store this? Will #cost every be called multiple times?
            try {
              final long cost;
              Terms indexTerms = reader.terms(field);
              if (indexTerms == null) {
                // TODO: This needs to work with any DV field type!
                SortedSetDocValues dv = reader.getSortedSetDocValues(field);
                if (dv == null) {
                  return 0;
                } else {
                  return dv.cost();
                }
              }

              long potentialExtraCost = indexTerms.getSumDocFreq();
              final long indexedTermCount = indexTerms.size();
              if (indexedTermCount != -1) {
                potentialExtraCost -= indexedTermCount;
              }
              cost = terms.length + potentialExtraCost;

              return cost;
            } catch (IOException ioe) {
              return Long.MAX_VALUE;
            }
          }
        };
      }

      private Scorer emptyScorer() {
        return new ConstantScoreScorer(this, score(), scoreMode, DocIdSetIterator.empty());
      }

      private Scorer scorerFor(DocIdSetIterator it) {
        return new ConstantScoreScorer(this, score(), scoreMode, it);
      }

      private Scorer scorerFor(TwoPhaseIterator it) {
        return new ConstantScoreScorer(this, score(), scoreMode, it);
      }

      private Scorer postingsScorer(LeafReader reader) throws IOException {
        Terms t = reader.terms(field);
        if (t == null) {
          return emptyScorer();
        }

        int fieldDocCount = t.getDocCount();
        double avgPostingsLength = (double) t.getSumDocFreq() / t.size();
        boolean checkForFullyDense = avgPostingsLength > (fieldDocCount * 0.75);

        TermsEnum termsEnum = t.iterator();
        TermState[] termStates = new TermState[terms.length];
        for (int i = 0; i < terms.length; i++) {
          BytesRef term = terms[i];
          if (termsEnum.seekExact(term) == false) {
            continue;
          }

          // TODO: #docFreq isn't free. Maybe this isn't worth it?
          if (checkForFullyDense && fieldDocCount == termsEnum.docFreq()) {
            PostingsEnum postings = termsEnum.postings(null, PostingsEnum.NONE);
            return scorerFor(postings);
          }

          termStates[i] = termsEnum.termState();
        }

        // TODO: how much cost are we adding by not loading the postings inline and instead saving
        // term states?
        return postingsScorer(reader, termsEnum, termStates);
      }

      private Scorer postingsScorer(LeafReader reader, TermsEnum termsEnum, TermState... termStates)
          throws IOException {
        int foundTermCount = 0;
        int postingsCount = 0;
        boolean hasProcessedPostings = false;
        DisiWrapper[] postings = new DisiWrapper[terms.length];
        DocIdSetBuilder processedPostings = new DocIdSetBuilder(reader.maxDoc());
        PostingsEnum reuse = null;
        for (int i = 0; i < terms.length; i++) {
          TermState termState = termStates[i];
          if (termState == null) {
            continue;
          }

          foundTermCount++;
          termsEnum.seekExact(terms[i], termState);
          if (termsEnum.docFreq() < L) { // TODO: should this be a ratio to candidate size?
            processedPostings.add(termsEnum.postings(reuse, PostingsEnum.NONE));
            hasProcessedPostings = true;
          } else {
            PostingsEnum p = termsEnum.postings(null, PostingsEnum.NONE);
            postings[postingsCount] = new DisiWrapper(p);
            postingsCount++;
          }
        }
        assert postingsCount <= foundTermCount;

        if (foundTermCount == 0) {
          return emptyScorer();
        }

        if (postingsCount == 0) {
          assert hasProcessedPostings;
          return scorerFor(processedPostings.build().iterator());
        }

        if (foundTermCount == 1) {
          assert postingsCount == 1 && hasProcessedPostings == false;
          return scorerFor(postings[0].iterator);
        }

        if (postingsCount > M) {
          PriorityQueue<DisiWrapper> pq =
              new PriorityQueue<>(M) {
                @Override
                protected boolean lessThan(DisiWrapper a, DisiWrapper b) {
                  return a.cost < b.cost;
                }
              };
          for (DisiWrapper w : postings) {
            DisiWrapper evicted = pq.insertWithOverflow(w);
            if (evicted != null) {
              processedPostings.add(evicted.iterator);
              hasProcessedPostings = true;
            }
          }
          postingsCount = 0;
          for (DisiWrapper w : postings) {
            postings[postingsCount] = w;
            postingsCount++;
          }
        }

        if (hasProcessedPostings) {
          postings[postingsCount] = new DisiWrapper(processedPostings.build().iterator());
          postingsCount++;
        }

        DisiPriorityQueue pq = new DisiPriorityQueue(postingsCount);
        long c = 0;
        for (int i = 0; i < postingsCount; i++) {
          DisiWrapper w = postings[i];
          pq.add(w);
          c += w.cost;
        }
        final long cost = c;

        DocIdSetIterator it =
            new DocIdSetIterator() {
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

        return scorerFor(it);
      }

      private Scorer docValuesScorer(DocValuesType dvType, LeafReader reader) throws IOException {
        switch (dvType) {
          case SORTED:
          case SORTED_SET:
            SortedSetDocValues ssdv = DocValues.getSortedSet(reader, field);
            SortedDocValues sdv = DocValues.unwrapSingleton(ssdv);
            if (sdv != null) {
              return docValueScorer(sdv);
            } else {
              return docValueScorer(ssdv);
            }
          case BINARY:
            return docValueScorer(DocValues.getBinary(reader, field));
          case NUMERIC:
          case SORTED_NUMERIC:
            SortedNumericDocValues sndv = DocValues.getSortedNumeric(reader, field);
            NumericDocValues ndv = DocValues.unwrapSingleton(sndv);
            if (ndv != null) {
              return docValueScorer(ndv);
            } else {
              return docValueScorer(sndv);
            }
          default:
            throw new IllegalStateException(
                "docValuesScorer should never be used when there are no indexed doc values");
        }
      }

      private Scorer docValueScorer(SortedDocValues dv) throws IOException {
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
          return emptyScorer();
        }

        TwoPhaseIterator it =
            new TwoPhaseIterator(dv) {
              @Override
              public boolean matches() throws IOException {
                return ords.get(dv.ordValue());
              }

              @Override
              public float matchCost() {
                return 1f;
              }
            };

        return scorerFor(it);
      }

      private Scorer docValueScorer(SortedSetDocValues dv) throws IOException {
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
          return emptyScorer();
        }

        TwoPhaseIterator it =
            new TwoPhaseIterator(dv) {
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

        return scorerFor(it);
      }

      private Scorer docValueScorer(BinaryDocValues dv) {
        Set<BytesRef> termSet = new HashSet<>(terms.length);
        termSet.addAll(Arrays.asList(terms));

        TwoPhaseIterator it =
            new TwoPhaseIterator(dv) {
              @Override
              public boolean matches() throws IOException {
                return termSet.contains(dv.binaryValue());
              }

              @Override
              public float matchCost() {
                return 1f;
              }
            };

        return scorerFor(it);
      }

      private Scorer docValueScorer(NumericDocValues dv) {
        LongBitSet vals = numericTermSet();

        TwoPhaseIterator it =
            new TwoPhaseIterator(dv) {
              @Override
              public boolean matches() throws IOException {
                return vals.get(dv.longValue());
              }

              @Override
              public float matchCost() {
                return 1f;
              }
            };

        return scorerFor(it);
      }

      private Scorer docValueScorer(SortedNumericDocValues dv) {
        LongBitSet vals = numericTermSet();

        TwoPhaseIterator it =
            new TwoPhaseIterator(dv) {
              @Override
              public boolean matches() throws IOException {
                for (int i = 0; i < dv.docValueCount(); i++) {
                  if (vals.get(dv.nextValue())) {
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

        return scorerFor(it);
      }

      private LongBitSet numericTermSet() {
        long max = Long.MIN_VALUE;
        long[] valsArray = new long[terms.length];
        int valCount = 0;
        for (BytesRef term : terms) {
          try {
            long val = Long.parseLong(Term.toString(term));
            max = Math.max(max, val);
            valsArray[valCount] = val;
            valCount++;
          } catch (NumberFormatException e) {
            // skip
          }
        }
        LongBitSet vals = new LongBitSet(max);
        for (int i = 0; i < valCount; i++) {
          vals.set(valsArray[i]);
        }

        return vals;
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
    return field.equals(o.field)
        && termsHashCode == o.termsHashCode
        && Arrays.equals(terms, o.terms);
  }

  @Override
  public int hashCode() {
    return Objects.hash(field, termsHashCode);
  }
}
