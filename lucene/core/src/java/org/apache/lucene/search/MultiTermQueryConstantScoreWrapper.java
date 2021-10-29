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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermState;
import org.apache.lucene.index.TermStates;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.DocIdSetBuilder;
import org.apache.lucene.util.RamUsageEstimator;

/**
 * This class also provides the functionality behind {@link MultiTermQuery#CONSTANT_SCORE_REWRITE}.
 * It tries to rewrite per-segment as a boolean query that returns a constant score and otherwise
 * fills a bit set with matches and builds a Scorer on top of this bit set.
 */
final class MultiTermQueryConstantScoreWrapper<Q extends MultiTermQuery> extends Query
    implements Accountable {

  // mtq that matches 16 terms or less will be executed as a regular disjunction
  private static final int BOOLEAN_REWRITE_TERM_COUNT_THRESHOLD = 16;

  @Override
  public long ramBytesUsed() {
    if (query instanceof Accountable) {
      return RamUsageEstimator.NUM_BYTES_OBJECT_HEADER
          + RamUsageEstimator.NUM_BYTES_OBJECT_REF
          + ((Accountable) query).ramBytesUsed();
    }
    return RamUsageEstimator.NUM_BYTES_OBJECT_HEADER
        + RamUsageEstimator.NUM_BYTES_OBJECT_REF
        + RamUsageEstimator.QUERY_DEFAULT_RAM_BYTES_USED;
  }

  private static class TermAndState {
    final BytesRef term;
    final TermState state;
    final int docFreq;
    final long totalTermFreq;

    TermAndState(BytesRef term, TermState state, int docFreq, long totalTermFreq) {
      this.term = term;
      this.state = state;
      this.docFreq = docFreq;
      this.totalTermFreq = totalTermFreq;
    }
  }

  private static class WeightOrDocIdSet {
    final Weight weight;
    final DocIdSet set;

    WeightOrDocIdSet(Weight weight) {
      this.weight = Objects.requireNonNull(weight);
      this.set = null;
    }

    WeightOrDocIdSet(DocIdSet bitset) {
      this.set = bitset;
      this.weight = null;
    }
  }

  final Q query;

  /** Wrap a {@link MultiTermQuery} as a Filter. */
  MultiTermQueryConstantScoreWrapper(Q query) {
    this.query = query;
  }

  @Override
  public String toString(String field) {
    // query.toString should be ok for the filter, too, if the query boost is 1.0f
    return query.toString(field);
  }

  @Override
  public boolean equals(final Object other) {
    return sameClassAs(other)
        && query.equals(((MultiTermQueryConstantScoreWrapper<?>) other).query);
  }

  @Override
  public int hashCode() {
    return 31 * classHash() + query.hashCode();
  }

  /** Returns the encapsulated query */
  Q getQuery() {
    return query;
  }

  /** Returns the field name for this query */
  String getField() {
    return query.getField();
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
      throws IOException {
    return new ConstantScoreWeight(this, boost) {

      @Override
      public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
        return new MultiTermQueryScoreSupplier(searcher, context, query, this, score(), scoreMode);
      }

      @Override
      public Scorer scorer(LeafReaderContext context) throws IOException {
        return scorerSupplier(context).get(Long.MAX_VALUE);
      }

      @Override
      public BulkScorer bulkScorer(LeafReaderContext context) throws IOException {
        final Terms indexTerms = context.reader().terms(query.getField());
        final WeightOrDocIdSet weightOrBitSet = rewrite(searcher, context, score(), scoreMode, query, indexTerms);
        if (weightOrBitSet.weight != null) {
          return weightOrBitSet.weight.bulkScorer(context);
        } else {
          final Scorer scorer = scorerForDocIdSet(this, score(), scoreMode, weightOrBitSet.set);
          if (scorer == null) {
            return null;
          }
          return new DefaultBulkScorer(scorer);
        }
      }

      @Override
      public Matches matches(LeafReaderContext context, int doc) throws IOException {
        final Terms indexTerms = context.reader().terms(query.field);
        if (indexTerms == null) {
          return null;
        }
        return MatchesUtils.forField(
            query.field,
            () ->
                DisjunctionMatchesIterator.fromTermsEnum(
                    context, doc, query, query.field, query.getTermsEnum(indexTerms)));
      }

      @Override
      public boolean isCacheable(LeafReaderContext ctx) {
        return true;
      }
    };
  }

  @Override
  public void visit(QueryVisitor visitor) {
    if (visitor.acceptField(getField())) {
      query.visit(visitor.getSubVisitor(Occur.FILTER, this));
    }
  }

  /**
   * On the given leaf context, try to either rewrite to a disjunction if there are few terms,
   * or build a bitset containing matching docs.
   */
  private static WeightOrDocIdSet rewrite(IndexSearcher searcher,
                                          LeafReaderContext context,
                                          float score,
                                          ScoreMode scoreMode,
                                          MultiTermQuery query,
                                          Terms indexTerms) throws IOException {
    // [] If the field doesn't exist, we're done:
    if (indexTerms == null) {
      return new WeightOrDocIdSet((DocIdSet) null);
    }

    // [] Create a TermsEnum that intersects the query terms with the index terms:
    final TermsEnum termsEnum = query.getTermsEnum(indexTerms);
    assert termsEnum != null;

    PostingsEnum docs = null;

    // [] Collect terms up to BOOLEAN_REWRITE_TERM_COUNT_THRESHOLD:
    final List<TermAndState> collectedTerms = new ArrayList<>();
    if (collectTerms(termsEnum, collectedTerms)) {
      // [] If there were fewer than BOOLEAN_REWRITE_TERM_COUNT_THRESHOLD terms, build a BQ:
      BooleanQuery.Builder bq = new BooleanQuery.Builder();
      for (TermAndState t : collectedTerms) {
        final TermStates termStates = new TermStates(searcher.getTopReaderContext());
        termStates.register(t.state, context.ord, t.docFreq, t.totalTermFreq);
        bq.add(new TermQuery(new Term(query.field, t.term), termStates), Occur.SHOULD);
      }
      Query q = new ConstantScoreQuery(bq.build());
      final Weight weight = searcher.rewrite(q).createWeight(searcher, scoreMode, score);
      return new WeightOrDocIdSet(weight);
    }

    // [] More than BOOLEAN_REWRITE_TERM_COUNT_THRESHOLD terms, so we'll build a bit set of the matching docs. First,
    // go back through the terms already collected and add their docs:
    DocIdSetBuilder builder = new DocIdSetBuilder(context.reader().maxDoc(), indexTerms);
    if (collectedTerms.isEmpty() == false) {
      TermsEnum termsEnum2 = indexTerms.iterator();
      for (TermAndState t : collectedTerms) {
        termsEnum2.seekExact(t.term, t.state);
        docs = termsEnum2.postings(docs, PostingsEnum.NONE);
        builder.add(docs);
      }
    }

    // [] Iterate the remaining terms and finish filling the bit set:
    do {
      docs = termsEnum.postings(docs, PostingsEnum.NONE);
      builder.add(docs);
    } while (termsEnum.next() != null);

    return new WeightOrDocIdSet(builder.build());
  }

  /**
   * Try to collect terms from the given terms enum and return true iff all terms could be
   * collected. If {@code false} is returned, the enum is left positioned on the next term.
   */
  private static boolean collectTerms(TermsEnum termsEnum, List<TermAndState> terms) throws IOException {
    final int threshold = Math.min(BOOLEAN_REWRITE_TERM_COUNT_THRESHOLD, IndexSearcher.getMaxClauseCount());
    for (int i = 0; i < threshold; i++) {
      BytesRef term = termsEnum.next();
      if (term == null) {
        return true;
      }
      TermState state = termsEnum.termState();
      terms.add(
          new TermAndState(
              BytesRef.deepCopyOf(term),
              state,
              termsEnum.docFreq(),
              termsEnum.totalTermFreq()));
    }
    return termsEnum.next() == null;
  }

  private static Scorer scorerForDocIdSet(Weight weight, float score, ScoreMode scoreMode, DocIdSet set) throws IOException {
    if (set == null) {
      return null;
    }
    final DocIdSetIterator disi = set.iterator();
    if (disi == null) {
      return null;
    }
    return new ConstantScoreScorer(weight, score, scoreMode, disi);
  }

  private static class MultiTermQueryScoreSupplier extends ScorerSupplier {
    private final IndexSearcher searcher;
    private final LeafReaderContext context;
    private final MultiTermQuery query;
    private final Weight weight;
    private final float score;
    private final ScoreMode scoreMode;
    private final Terms indexTerms;
    private final long cost;

    MultiTermQueryScoreSupplier(IndexSearcher searcher,
                                LeafReaderContext context,
                                MultiTermQuery query,
                                Weight weight,
                                float score,
                                ScoreMode scoreMode) throws IOException {
      this.searcher = searcher;
      this.context = context;
      this.query = query;
      this.weight = weight;
      this.score = score;
      this.scoreMode = scoreMode;

      // [] Load the indexed terms for the field:
      indexTerms = context.reader().terms(query.getField());

      // [] Estimate the cost. If the MTQ can provide its term count, we can do a better job estimating.
      // Cost estimation reasoning is:
      // 1. If we don't know how many query terms there are, we just assume a high ceiling where every doc with a value
      //    for the field is a hit.
      // 2. If we know how many query terms there are...
      //    2a. Assume every query term matches at least one document (queryTermsCount).
      //    2b. Determine the total number of docs beyond the first one for each term since those are already accounted
      //        for by queryTermsCount. That count provides a ceiling on the number of extra docs that could match.
      //    2c. Limit cost by the total doc count for the field since it's a natural upper-bound on the cost.
      // See: LUCENE-10207
      int queryTermsCount = query.getTermsCount();
      if (indexTerms == null) {
        cost = 0;  // field doesn't exist
      } else if (queryTermsCount == -1) {
        cost = indexTerms.getDocCount();
      } else {
        cost = Math.min(indexTerms.getDocCount(), queryTermsCount + (indexTerms.getSumDocFreq() - indexTerms.size()));
      }
    }

    @Override
    public Scorer get(long leadCost) throws IOException {
      WeightOrDocIdSet weightOrBitSet = rewrite(searcher, context, score, scoreMode, query, indexTerms);
      if (weightOrBitSet.weight != null) {
        return weightOrBitSet.weight.scorer(context);
      } else {
        return scorerForDocIdSet(weight, score, scoreMode, weightOrBitSet.set);
      }
    }

    @Override
    public long cost() {
      return cost;
    }
  }
}
