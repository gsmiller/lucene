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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.PrefixCodedTerms;
import org.apache.lucene.index.SortedDocValues;
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
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.DocIdSetBuilder;
import org.apache.lucene.util.LongBitSet;
import org.apache.lucene.util.PriorityQueue;
import org.apache.lucene.util.automaton.Automata;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.ByteRunAutomaton;
import org.apache.lucene.util.automaton.CompiledAutomaton;
import org.apache.lucene.util.automaton.Operations;

public class TermInSetQuery extends Query {
  // TODO: tunable coefficients. need to actually tune them (or maybe these are too complex and not
  // useful)
  private static final double J = 1.0;
  private static final double K = 1.0;
  // L: postings lists under this threshold will always be "pre-processed" into a bitset
  private static final int L = 512;
  // M: max number of clauses we'll manage/check during scoring (these remain "unprocessed")
  private static final int M = Math.min(IndexSearcher.getMaxClauseCount(), 64);

  private final String field;
  private final PrefixCodedTerms termData;
  private final int termsHashCode;

  public TermInSetQuery(String field, Collection<BytesRef> terms) {
    this.field = field;

    BytesRef[] sortedTerms = terms.toArray(new BytesRef[0]);
    // already sorted if we are a SortedSet with natural order
    boolean sorted =
        terms instanceof SortedSet && ((SortedSet<BytesRef>) terms).comparator() == null;
    if (sorted == false) {
      ArrayUtil.timSort(sortedTerms);
    }

    PrefixCodedTerms.Builder termBuilder = new PrefixCodedTerms.Builder();
    BytesRefBuilder previous = null;
    for (BytesRef term : sortedTerms) {
      if (previous == null) {
        previous = new BytesRefBuilder();
      } else if (previous.get().equals(term)) {
        continue; // dedupe
      }
      termBuilder.add(field, term);
      previous.copyBytes(term);
    }
    termData = termBuilder.finish();

    // TODO: compute lazily?
    termsHashCode = termData.hashCode();
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
      throws IOException {

    return new ConstantScoreWeight(this, boost) {

      // TODO: bulkScorer?

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
        if (termData.size() <= 1) {
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
            assert termData.size() > 1;

            // If there are no doc values indexed, we have to use a postings-based approach:
            DocValuesType dvType = fi.getDocValuesType();
            if (dvType != DocValuesType.SORTED && dvType != DocValuesType.SORTED_SET) {
              Terms t = reader.terms(field);
              if (t == null) {
                return emptyScorer();
              }
              return postingsScorer(reader, t.iterator(), null, -1);
            }

            if (termData.size() > J * leadCost) {
              // If the number of terms is > the number of candidates, DV should perform better.
              // TODO: This assumes all terms are present in the segment. If the actual number of
              // found terms in the segment is significantly smaller, this can be the wrong
              // decision. Maybe we can do better? Possible bloom filter application?
              return docValuesScorer(reader);
            } else {
              Terms t = reader.terms(field);
              if (t == null) {
                // If there are no postings, we have to use doc values:
                return docValuesScorer(reader);
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
              double expectedTotalAdvances = avgTermAdvances * termData.size();
              if (expectedTotalAdvances > leadCost) { // TODO: tuning coefficient?
                return docValuesScorer(reader);
              }

              // At this point, it seems that using a postings approach may be best, so we'll
              // actually seek to all the terms and gather more accurate index statistics and make
              // one more decision. The hope is that we end up using a postings approach since all
              // this term seeking is wasted if we go DV:
              long expectedAdvances = 0;
              int foundTermCount = 0;
              int fieldDocCount = t.getDocCount();
              TermsEnum termsEnum = t.iterator();
              List<TermState> termStates = new ArrayList<>();
              TermState lastTermState = null;
              BytesRef lastTerm = null;
              PrefixCodedTerms.TermIterator termIterator = termData.iterator();
              for (BytesRef term = termIterator.next(); term != null; term = termIterator.next()) {
                if (termsEnum.seekExact(term) == false) {
                  termStates.add(null);
                  continue;
                }

                // If we find a "completely dense" postings list, we can use it directly:
                int termDocFreq = termsEnum.docFreq();
                if (fieldDocCount == termDocFreq) {
                  PostingsEnum postings = termsEnum.postings(null, PostingsEnum.NONE);
                  return scorerFor(postings);
                }

                // As expectedAdvances grows, it becomes more-and-more likely a doc-values approach
                // will be more cost-effective. Since the cost of a doc-values approach is bound
                // by leadCost, we chose to use it if expectedAdvances grows beyond a certain point:
                expectedAdvances += Math.min(termDocFreq, leadCost);
                if (expectedAdvances > (K * leadCost)) {
                  return docValuesScorer(reader);
                }

                foundTermCount++;
                lastTermState = termsEnum.termState();
                lastTerm = term;
                termStates.add(lastTermState);
              }

              // We have some new information about how many terms were actually found in the
              // segment, so make some special-case decisions based on that new information:
              if (foundTermCount == 0) {
                return emptyScorer();
              } else if (foundTermCount == 1) {
                termsEnum.seekExact(lastTerm, lastTermState);
                PostingsEnum postings = termsEnum.postings(null, PostingsEnum.NONE);
                return scorerFor(postings);
              }

              // If we reach this point, it's likely a postings-based approach will prove more
              // cost-effective:
              return postingsScorer(
                  context.reader(), termsEnum, termStates.iterator(), fieldDocCount);
            }
          }

          @Override
          public long cost() {
            // TODO: Should we lazily store this? Will #cost every be called multiple times?
            try {
              final long cost;
              Terms indexTerms = reader.terms(field);
              if (indexTerms == null) {
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
              cost = termData.size() + potentialExtraCost;

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

      private Scorer postingsScorer(
          LeafReader reader, TermsEnum termsEnum, Iterator<TermState> termStates, int fieldDocCount)
          throws IOException {
        List<DisiWrapper> unprocessed = null;
        PriorityQueue<DisiWrapper> unprocessedPq = null;
        DocIdSetBuilder processedPostings = null;
        PostingsEnum reuse = null;
        PrefixCodedTerms.TermIterator termIterator = termData.iterator();
        for (BytesRef term = termIterator.next(); term != null; term = termIterator.next()) {
          // If we have term states, use them:
          if (termStates != null) {
            TermState termState = termStates.next();
            if (termState == null) {
              continue;
            }
            termsEnum.seekExact(term, termState);
          } else {
            if (termsEnum.seekExact(term) == false) {
              continue;
            }

            // TODO: #docFreq isn't free. Maybe this isn't worth it?
            if (fieldDocCount == termsEnum.docFreq()) {
              PostingsEnum postings = termsEnum.postings(null, PostingsEnum.NONE);
              return scorerFor(postings);
            }
          }

          if (termsEnum.docFreq() < L) { // TODO: should this be a ratio to candidate size?
            if (processedPostings == null) {
              processedPostings = new DocIdSetBuilder(reader.maxDoc());
            }
            processedPostings.add(termsEnum.postings(reuse, PostingsEnum.NONE));
          } else {
            DisiWrapper w = new DisiWrapper(termsEnum.postings(null, PostingsEnum.NONE));
            if (unprocessedPq != null) {
              DisiWrapper evicted = unprocessedPq.insertWithOverflow(w);
              assert evicted != null;
              assert processedPostings != null;
              processedPostings.add(evicted.iterator);
            } else {
              if (unprocessed == null) {
                unprocessed = new ArrayList<>(M);
              }
              if (unprocessed.size() < M) {
                unprocessed.add(w);
              } else {
                assert unprocessedPq == null;
                unprocessedPq =
                    new PriorityQueue<>(M) {
                      @Override
                      protected boolean lessThan(DisiWrapper a, DisiWrapper b) {
                        return a.cost < b.cost;
                      }
                    };
                unprocessedPq.addAll(unprocessed);
                unprocessed = null;

                DisiWrapper evicted = unprocessedPq.insertWithOverflow(w);
                assert evicted != null;
                if (processedPostings == null) {
                  processedPostings = new DocIdSetBuilder(reader.maxDoc());
                }
                processedPostings.add(evicted.iterator);
              }
            }
          }
        }

        if (processedPostings == null && unprocessed == null) {
          assert unprocessedPq == null;
          return emptyScorer();
        }

        if (processedPostings != null && (unprocessed == null && unprocessedPq == null)) {
          return scorerFor(processedPostings.build().iterator());
        }

        if (processedPostings == null && unprocessed.size() == 1) {
          assert unprocessedPq == null;
          return scorerFor(unprocessed.get(0).iterator);
        }

        DisiWrapper[] postings;
        if (unprocessed != null) {
          if (processedPostings != null) {
            postings = new DisiWrapper[unprocessed.size() + 1];
            postings[postings.length - 1] = new DisiWrapper(processedPostings.build().iterator());
          } else {
            postings = new DisiWrapper[unprocessed.size()];
          }
          System.arraycopy(
              unprocessed.toArray(new DisiWrapper[0]), 0, postings, 0, unprocessed.size());
        } else {
          assert unprocessedPq != null && processedPostings != null;
          assert unprocessedPq.size() == M;
          postings = new DisiWrapper[M + 1];
          int i = 0;
          for (DisiWrapper w : unprocessedPq) {
            postings[i] = w;
            i++;
          }
          postings[i] = new DisiWrapper(processedPostings.build().iterator());
        }

        DisiPriorityQueue pq = new DisiPriorityQueue(postings.length);
        pq.addAll(postings, 0, postings.length);

        long c = 0;
        for (DisiWrapper w : postings) {
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

      private Scorer docValuesScorer(LeafReader reader) throws IOException {
        SortedSetDocValues ssdv = DocValues.getSortedSet(reader, field);

        boolean hasAtLeastOneTerm = false;
        LongBitSet ords = new LongBitSet(ssdv.getValueCount());
        PrefixCodedTerms.TermIterator termIterator = termData.iterator();
        for (BytesRef term = termIterator.next(); term != null; term = termIterator.next()) {
          long ord = ssdv.lookupTerm(term);
          if (ord >= 0) {
            ords.set(ord);
            hasAtLeastOneTerm = true;
          }
        }

        if (hasAtLeastOneTerm == false) {
          return emptyScorer();
        }

        TwoPhaseIterator it;
        SortedDocValues singleton = DocValues.unwrapSingleton(ssdv);
        if (singleton != null) {
          it =
              new TwoPhaseIterator(singleton) {
                @Override
                public boolean matches() throws IOException {
                  return ords.get(singleton.ordValue());
                }

                @Override
                public float matchCost() {
                  return 1f;
                }
              };
        } else {
          it =
              new TwoPhaseIterator(ssdv) {
                @Override
                public boolean matches() throws IOException {
                  for (int i = 0; i < ssdv.docValueCount(); i++) {
                    if (ords.get(ssdv.nextOrd())) {
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
        }

        return scorerFor(it);
      }

      @Override
      public boolean isCacheable(LeafReaderContext ctx) {
        return false;
      }
    };
  }

  @Override
  public Query rewrite(IndexSearcher indexSearcher) throws IOException {
    if (termData.size() == 0) {
      return new MatchNoDocsQuery();
    }
    if (termData.size() == 1) {
      Term term = new Term(field, termData.iterator().next());
      return new TermQuery(term);
    }
    return this;
  }

  @Override
  public void visit(QueryVisitor visitor) {
    if (visitor.acceptField(field) == false) {
      return;
    }

    if (termData.size() == 1) {
      visitor.consumeTerms(this, new Term(field, termData.iterator().next()));
    }
    if (termData.size() > 1) {
      visitor.consumeTermsMatching(this, field, this::asByteRunAutomaton);
    }
  }

  // TODO: this is extremely slow. we should not be doing this.
  private ByteRunAutomaton asByteRunAutomaton() {
    PrefixCodedTerms.TermIterator iterator = termData.iterator();
    List<Automaton> automata = new ArrayList<>();
    for (BytesRef term = iterator.next(); term != null; term = iterator.next()) {
      automata.add(Automata.makeBinary(term));
    }
    Automaton automaton =
        Operations.determinize(
            Operations.union(automata), Operations.DEFAULT_DETERMINIZE_WORK_LIMIT);
    return new CompiledAutomaton(automaton).runAutomaton;
  }

  @Override
  public String toString(String field) {
    StringBuilder sb = new StringBuilder(field + ":(");
    boolean first = true;
    PrefixCodedTerms.TermIterator termIterator = termData.iterator();
    for (BytesRef t = termIterator.next(); t != null; t = termIterator.next()) {
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
    return termsHashCode == o.termsHashCode && termData.equals(o.termData);
  }

  @Override
  public int hashCode() {
    return 31 * classHash() + termsHashCode;
  }
}
