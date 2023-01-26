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
import org.apache.lucene.search.BulkScorer;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DisiPriorityQueue;
import org.apache.lucene.search.DisiWrapper;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.Bits;
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

/** TODO: javadoc */
public class TermInSetQuery extends Query {
  // TODO: tunable coefficients. need to actually tune them (or maybe these are too complex and not
  // useful)
  private static final double J = 1.0;
  private static final double K = 1.0;
  // number of terms we'll "pre-seek" to validate; limits heap if there are many terms
  private static final int PRE_SEEK_TERM_LIMIT = 16;
  // postings lists under this threshold will always be "pre-processed" into a bitset
  private static final int POSTINGS_PRE_PROCESS_THRESHOLD = 512;
  // max number of clauses we'll manage/check during scoring (these remain "unprocessed")
  private static final int MAX_UNPROCESSED_POSTINGS =
      Math.min(IndexSearcher.getMaxClauseCount(), 16);

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
    termsHashCode = termData.hashCode();
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
      throws IOException {

    return new ConstantScoreWeight(this, boost) {

      @Override
      public BulkScorer bulkScorer(LeafReaderContext context) throws IOException {
        final LeafReader reader = context.reader();
        final Terms t = reader.terms(field);
        if (t == null) {
          return super.bulkScorer(context);
        }

        // For top-level bulk scoring, it should be better to do term-at-a-time scoring with
        // postings:
        final TermsEnum termsEnum = t.iterator();
        int fieldDocCount = t.getDocCount();
        PostingsEnum singleton = null;
        PostingsEnum reuse = null;
        DocIdSetBuilder builder = null;
        PrefixCodedTerms.TermIterator termIterator = termData.iterator();
        for (BytesRef term = termIterator.next(); term != null; term = termIterator.next()) {
          if (termsEnum.seekExact(term) == false) {
            continue;
          }

          // If we find a "completely dense" postings list, we can use it directly:
          int termDocFreq = termsEnum.docFreq();
          if (fieldDocCount == termDocFreq) {
            singleton = termsEnum.postings(reuse, PostingsEnum.NONE);
            break;
          }

          reuse = termsEnum.postings(reuse, PostingsEnum.NONE);
          if (builder == null) {
            builder = new DocIdSetBuilder(reader.maxDoc());
          }
          builder.add(reuse);
        }

        final DocIdSetIterator it;
        if (singleton != null) {
          it = singleton;
        } else if (builder == null) {
          // No terms found:
          return null;
        } else {
          it = builder.build().iterator();
        }
        final long cost = it.cost();

        return new BulkScorer() {

          @Override
          public int score(LeafCollector collector, Bits acceptDocs, int min, int max)
              throws IOException {
            final DummyScorable dummy = new DummyScorable(boost);
            collector.setScorer(dummy);

            int doc = it.docID();
            if (doc < min) {
              doc = it.advance(min);
            }

            while (doc < max) {
              if (acceptDocs == null || acceptDocs.get(doc)) {
                dummy.docID = doc;
                collector.collect(doc);
              }
              doc = it.nextDoc();
            }

            return doc;
          }

          @Override
          public long cost() {
            return cost;
          }
        };
      }

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

        final LeafReader reader = context.reader();
        final FieldInfo fi = reader.getFieldInfos().fieldInfo(field);
        if (fi == null) {
          return null;
        }

        final Terms t = reader.terms(field);
        final SortedSetDocValues dv;
        if (fi.getDocValuesType() == DocValuesType.SORTED
            || fi.getDocValuesType() == DocValuesType.SORTED_SET) {
          dv = DocValues.getSortedSet(reader, field);
        } else {
          dv = null;
        }

        // If the field doesn't exist in the segment, return null:
        if (t == null && dv == null) {
          return null;
        }

        // Estimate cost:
        final long cost;
        if (t == null) {
          cost = dv.cost();
        } else {
          long potentialExtraCost = t.getSumDocFreq();
          final long indexedTermCount = t.size();
          if (indexedTermCount != -1) {
            potentialExtraCost -= indexedTermCount;
          }
          cost = termData.size() + potentialExtraCost;
        }

        return new ScorerSupplier() {

          @Override
          public Scorer get(long leadCost) throws IOException {
            assert termData.size() > 1;

            // If there are no doc values indexed, we have to use a postings-based approach:
            if (dv == null) {
              return postingsScorer(reader, t.getDocCount(), t.iterator(), null);
            }

            // Number of possible candidates that need filtering:
            long candidateSize = Math.min(leadCost, dv.cost());

            if (termData.size() > J * candidateSize) {
              // If the number of terms is > the number of candidates, DV should perform better.
              // TODO: This assumes all terms are present in the segment. If the actual number of
              // found terms in the segment is significantly smaller, this can be the wrong
              // decision. Maybe we can do better? Possible bloom filter application?
              return docValuesScorer(dv);
            } else {
              if (t == null) {
                // If there are no postings, we have to use doc values:
                return docValuesScorer(dv);
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
//              if (termData.size() > PRE_SEEK_TERM_LIMIT) {
//                double avgTermAdvances =
//                    Math.min((double) candidateSize, (double) t.getSumDocFreq() / t.size());
//                double expectedTotalAdvances = avgTermAdvances * termData.size();
//                if (expectedTotalAdvances > K * candidateSize) {
//                  return docValuesScorer(dv);
//                }
//              }

              // At this point, it seems that using a postings approach may be best, so we'll
              // actually seek to all the terms and gather more accurate index statistics and make
              // one more decision. The hope is that we end up using a postings approach since all
              // this term seeking is wasted if we go DV:
              long expectedAdvances = 0;
              int visitedTermCount = 0;
              int foundTermCount = 0;
              int fieldDocCount = t.getDocCount();
              TermsEnum termsEnum = t.iterator();
              // Note: We can safely cast termData.size() to an int here since all the terms
              // originally came into the ctor in a Collection:
              List<TermState> termStates =
                  new ArrayList<>(Math.min(PRE_SEEK_TERM_LIMIT, Math.toIntExact(termData.size())));
              TermState lastTermState = null;
              BytesRefBuilder lastTerm = null;
              PrefixCodedTerms.TermIterator termIterator = termData.iterator();
              for (BytesRef term = termIterator.next();
                  term != null && visitedTermCount < PRE_SEEK_TERM_LIMIT;
                  term = termIterator.next(), visitedTermCount++) {
                if (termsEnum.seekExact(term) == false) {
                  // Keep track that the term wasn't found:
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
                // by candidateSize, we chose to use it if expectedAdvances grows beyond a certain
                // point:
                expectedAdvances += Math.min(termDocFreq, candidateSize);
                if (expectedAdvances > K * candidateSize) {
                  return docValuesScorer(dv);
                }

                foundTermCount++;
                lastTermState = termsEnum.termState();
                if (lastTerm == null) {
                  lastTerm = new BytesRefBuilder();
                }
                lastTerm.copyBytes(term);
                termStates.add(lastTermState);
              }

              if (visitedTermCount == termData.size()) {
                // If we visited all the terms, we do one more check for some special-cases:
                if (foundTermCount == 0) {
                  return emptyScorer();
                } else if (foundTermCount == 1) {
                  termsEnum.seekExact(lastTerm.get(), lastTermState);
                  PostingsEnum postings = termsEnum.postings(null, PostingsEnum.NONE);
                  return scorerFor(postings);
                }
              }

              // If we reach this point, it's likely a postings-based approach will prove more
              // cost-effective:
              return postingsScorer(
                  context.reader(), fieldDocCount, termsEnum, termStates.iterator());
            }
          }

          @Override
          public long cost() {
            return cost;
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
          LeafReader reader, int fieldDocCount, TermsEnum termsEnum, Iterator<TermState> termStates)
          throws IOException {
        List<DisiWrapper> unprocessed = null;
        PriorityQueue<DisiWrapper> unprocessedPq = null;
        DocIdSetBuilder processedPostings = null;
        PostingsEnum reuse = null;
        PrefixCodedTerms.TermIterator termIterator = termData.iterator();
        for (BytesRef term = termIterator.next(); term != null; term = termIterator.next()) {
          // Use any term states we have:
          if (termStates != null && termStates.hasNext()) {
            TermState termState = termStates.next();
            if (termState == null) {
              // Term wasn't found in the segment; move on:
              continue;
            }
            termsEnum.seekExact(term, termState);
          } else {
            if (termsEnum.seekExact(term) == false) {
              continue;
            }

            // If we find a "completely dense" postings list, we can use it directly:
            if (fieldDocCount == termsEnum.docFreq()) {
              reuse = termsEnum.postings(reuse, PostingsEnum.NONE);
              return scorerFor(reuse);
            }
          }

          if (termsEnum.docFreq() < POSTINGS_PRE_PROCESS_THRESHOLD) {
            // For small postings lists, we pre-process them directly into a bitset since they
            // are unlikely to benefit from skipping:
            if (processedPostings == null) {
              processedPostings = new DocIdSetBuilder(reader.maxDoc());
            }
            reuse = termsEnum.postings(reuse, PostingsEnum.NONE);
            processedPostings.add(reuse);
          } else {
            DisiWrapper w = new DisiWrapper(termsEnum.postings(null, PostingsEnum.NONE));
            if (unprocessedPq != null) {
              // We've hit our unprocessed postings limit, so use our PQ:
              DisiWrapper evicted = unprocessedPq.insertWithOverflow(w);
              processedPostings.add(evicted.iterator);
            } else {
              if (unprocessed == null) {
                unprocessed = new ArrayList<>(MAX_UNPROCESSED_POSTINGS);
              }
              if (unprocessed.size() < MAX_UNPROCESSED_POSTINGS) {
                unprocessed.add(w);
              } else {
                // Hit our unprocessed postings limit; switch to PQ:
                unprocessedPq =
                    new PriorityQueue<>(MAX_UNPROCESSED_POSTINGS) {
                      @Override
                      protected boolean lessThan(DisiWrapper a, DisiWrapper b) {
                        return a.cost < b.cost;
                      }
                    };
                unprocessedPq.addAll(unprocessed);
                unprocessed = null;

                DisiWrapper evicted = unprocessedPq.insertWithOverflow(w);
                if (processedPostings == null) {
                  processedPostings = new DocIdSetBuilder(reader.maxDoc());
                }
                processedPostings.add(evicted.iterator);
              }
            }
          }
        }

        // No terms found:
        if (processedPostings == null && unprocessed == null) {
          assert unprocessedPq == null;
          return emptyScorer();
        }

        // Single term found and _not_ pre-built into the bitset:
        if (processedPostings == null && unprocessed.size() == 1) {
          assert unprocessedPq == null;
          return scorerFor(unprocessed.get(0).iterator);
        }

        // All postings built into the bitset:
        if (processedPostings != null && unprocessed == null && unprocessedPq == null) {
          return scorerFor(processedPostings.build().iterator());
        }

        DisiPriorityQueue pq;
        final long cost;
        if (unprocessed != null) {
          assert unprocessedPq == null;
          if (processedPostings != null) {
            unprocessed.add(new DisiWrapper(processedPostings.build().iterator()));
          }
          pq = new DisiPriorityQueue(unprocessed.size());
          pq.addAll(unprocessed.toArray(new DisiWrapper[0]), 0, unprocessed.size());
          cost = unprocessed.stream().mapToLong(w -> w.cost).sum();
        } else {
          assert unprocessedPq != null && processedPostings != null;
          assert unprocessedPq.size() == MAX_UNPROCESSED_POSTINGS;
          DisiWrapper[] postings = new DisiWrapper[MAX_UNPROCESSED_POSTINGS + 1];
          int i = 0;
          long c = 0;
          for (DisiWrapper w : unprocessedPq) {
            c += w.cost;
            postings[i] = w;
            i++;
          }
          DisiWrapper w = new DisiWrapper(processedPostings.build().iterator());
          cost = c + w.cost;
          postings[i] = w;
          pq = new DisiPriorityQueue(postings.length);
          pq.addAll(postings, 0, postings.length);
        }

        // This iterator is slightly different from a standard disjunction iterator in that it
        // short-circuits whenever it finds a target doc (instead of ensuring all sub-iterators
        // are positioned on the same doc). This can be done since we're not using it to score,
        // only filter, and there are no second-phase checks:
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

      private Scorer docValuesScorer(SortedSetDocValues dv) throws IOException {
        boolean hasAtLeastOneTerm = false;
        LongBitSet ords = new LongBitSet(dv.getValueCount());
        PrefixCodedTerms.TermIterator termIterator = termData.iterator();
        for (BytesRef term = termIterator.next(); term != null; term = termIterator.next()) {
          long ord = dv.lookupTerm(term);
          if (ord >= 0) {
            ords.set(ord);
            hasAtLeastOneTerm = true;
          }
        }

        if (hasAtLeastOneTerm == false) {
          return emptyScorer();
        }

        TwoPhaseIterator it;
        SortedDocValues singleton = DocValues.unwrapSingleton(dv);
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
        }

        return scorerFor(it);
      }

      @Override
      public boolean isCacheable(LeafReaderContext ctx) {
        // TODO:
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

  private static final class DummyScorable extends Scorable {
    private final float score;
    int docID = -1;

    DummyScorable(float score) {
      this.score = score;
    }

    @Override
    public float score() throws IOException {
      return score;
    }

    @Override
    public int docID() {
      return docID;
    }
  }
}
