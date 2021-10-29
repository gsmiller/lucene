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
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.LongBitSet;

/**
 * Rewrites MultiTermQueries into a filter, using DocValues for term enumeration.
 *
 * <p>This can be used to perform these queries against an unindexed docvalues field.
 *
 * @lucene.experimental
 */
public final class DocValuesRewriteMethod extends MultiTermQuery.RewriteMethod {

  public static final DocValuesRewriteMethod DOC_VALUES_REWRITE_METHOD = new DocValuesRewriteMethod();

  @Override
  public Query rewrite(IndexReader reader, MultiTermQuery query) {
    return new ConstantScoreQuery(new MultiTermQueryDocValuesWrapper(query));
  }

  static class MultiTermQueryDocValuesWrapper extends Query {

    protected final MultiTermQuery query;

    /** Wrap a {@link MultiTermQuery} as a Filter. */
    protected MultiTermQueryDocValuesWrapper(MultiTermQuery query) {
      this.query = query;
    }

    @Override
    public String toString(String field) {
      // query.toString should be ok for the filter, too, if the query boost is 1.0f
      return query.toString(field);
    }

    @Override
    public final boolean equals(final Object other) {
      return sameClassAs(other) && query.equals(((MultiTermQueryDocValuesWrapper) other).query);
    }

    @Override
    public final int hashCode() {
      return 31 * classHash() + query.hashCode();
    }

    /** Returns the field name for this query */
    public final String getField() {
      return query.getField();
    }

    @Override
    public void visit(QueryVisitor visitor) {
      if (visitor.acceptField(query.getField())) {
        visitor.getSubVisitor(BooleanClause.Occur.FILTER, query);
      }
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {

      return new ConstantScoreWeight(this, boost) {

        @Override
        public Matches matches(LeafReaderContext context, int doc) throws IOException {
          final SortedSetDocValues values = DocValues.getSortedSet(context.reader(), query.field);
          return MatchesUtils.forField(
              query.field,
              () ->
                  DisjunctionMatchesIterator.fromTermsEnum(
                      context, doc, query, query.field, getTermsEnum(query, values)));
        }

        @Override
        public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
          return new DocValuesTermInSetScoreSupplier(context, query, this, score(), scoreMode);
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
          return scorerSupplier(context).get(Long.MAX_VALUE);
        }

        @Override
        public boolean isCacheable(LeafReaderContext ctx) {
          return DocValues.isCacheable(ctx, query.field);
        }
      };
    }
  }

  @Override
  public boolean equals(Object other) {
    return other != null && getClass() == other.getClass();
  }

  @Override
  public int hashCode() {
    return 641;
  }

  private static TermsEnum getTermsEnum(MultiTermQuery query, SortedSetDocValues values) throws IOException {
    return query.getTermsEnum(
        new Terms() {

          @Override
          public TermsEnum iterator() throws IOException {
            return values.termsEnum();
          }

          @Override
          public long getSumTotalTermFreq() {
            throw new UnsupportedOperationException();
          }

          @Override
          public long getSumDocFreq() {
            throw new UnsupportedOperationException();
          }

          @Override
          public int getDocCount() {
            throw new UnsupportedOperationException();
          }

          @Override
          public long size() {
            return -1;
          }

          @Override
          public boolean hasFreqs() {
            return false;
          }

          @Override
          public boolean hasOffsets() {
            return false;
          }

          @Override
          public boolean hasPositions() {
            return false;
          }

          @Override
          public boolean hasPayloads() {
            return false;
          }
        });
  }

  private static class DocValuesTermInSetScoreSupplier extends ScorerSupplier {
    private final MultiTermQuery query;
    private final Weight weight;
    private final float score;
    private final ScoreMode scoreMode;
    private final SortedSetDocValues values;
    private final long cost;

    DocValuesTermInSetScoreSupplier(LeafReaderContext context,
                                    MultiTermQuery query,
                                    Weight weight,
                                    float score,
                                    ScoreMode scoreMode) throws IOException {
      this.query = query;
      this.weight = weight;
      this.score = score;
      this.scoreMode = scoreMode;

      // [] Load the doc values:
      values = DocValues.getSortedSet(context.reader(), query.getField());

      int queryTermsCount = query.getTermsCount();
      if (values == null) {
        cost = 0;  // field doesn't exist
      } else if (queryTermsCount == -1) {
        cost = values.cost();
      } else {
        cost = Math.min(values.cost(), queryTermsCount + (values.cost() - values.getValueCount()));
      }
    }

    @Override
    public Scorer get(long leadCost) throws IOException {
      // [] Create a TermsEnum that intersects the terms specified in the MTQ with the terms actually found in
      // the doc values:
      final TermsEnum termsEnum = getTermsEnum(query, values);

      // [] Create a bit set for the "term set" ordinals (these are the terms provided by the MTQ that are actually
      // present in the doc values field):
      final LongBitSet termSetOrdinals = new LongBitSet(values.getValueCount());
      while (termsEnum.next() != null) {
        long ord = termsEnum.ord();
        if (ord >= 0) {
          termSetOrdinals.set(ord);
        }
      }

      final SortedDocValues singleton = DocValues.unwrapSingleton(values);
      final TwoPhaseIterator iterator;
      if (singleton != null) {
        iterator = new TwoPhaseIterator(singleton) {
          @Override
          public boolean matches() throws IOException {
            long ord = singleton.ordValue();
            return termSetOrdinals.get(ord);
          }

          @Override
          public float matchCost() {
            return 3; // lookup in a bitset
          }
        };
      } else {
        iterator = new TwoPhaseIterator(values) {
          @Override
          public boolean matches() throws IOException {
            for (long ord = values.nextOrd(); ord != SortedSetDocValues.NO_MORE_ORDS; ord = values.nextOrd()) {
              if (termSetOrdinals.get(ord)) {
                return true;
              }
            }
            return false;
          }

          @Override
          public float matchCost() {
            return 3; // lookup in a bitset
          }
        };
      }

      return new ConstantScoreScorer(weight, score, scoreMode, iterator);
    }

    @Override
    public long cost() {
      return cost;
    }
  }
}
