package org.apache.lucene.document;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PrefixCodedTerms;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.LongBitSet;

import java.io.IOException;
import java.util.Collection;
import java.util.SortedSet;

abstract class DocValuesTermInSetQuery extends Query {
  private final String field;
  private final PrefixCodedTerms termData;
  private final int termCount;
  private final int termDataHash;

  DocValuesTermInSetQuery(String field, Collection<BytesRef> terms) {
    this.field = field;

    BytesRef[] sortedTerms = terms.toArray(new BytesRef[0]);
    // already sorted if we are a SortedSet with natural order
    boolean sorted =
        terms instanceof SortedSet && ((SortedSet<BytesRef>) terms).comparator() == null;
    if (!sorted) {
      ArrayUtil.timSort(sortedTerms);
    }
    PrefixCodedTerms.Builder builder = new PrefixCodedTerms.Builder();
    BytesRefBuilder previous = null;
    for (BytesRef term : sortedTerms) {
      if (previous == null) {
        previous = new BytesRefBuilder();
      } else if (previous.get().equals(term)) {
        continue; // deduplicate
      }
      builder.add(field, term);
      previous.copyBytes(term);
    }
    termData = builder.finish();
    termDataHash = termData.hashCode();
    termCount = terms.size();
  }

  protected abstract SortedSetDocValues getValues(LeafReader reader, String field) throws IOException;

  @Override
  public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
    return new ConstantScoreWeight(this, boost) {

      @Override
      public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
        SortedSetDocValues values = getValues(context.reader(), field);
        return new DocValuesTermInSetScoreSupplier(this, score(), scoreMode, termData, values);
      }

      @Override
      public Scorer scorer(LeafReaderContext context) throws IOException {
        return scorerSupplier(context).get(Long.MAX_VALUE);
      }

      @Override
      public boolean isCacheable(LeafReaderContext ctx) {
        return DocValues.isCacheable(ctx, field);
      }
    };
  }

  @Override
  public Query rewrite(IndexReader reader) throws IOException {
    return super.rewrite(reader);
  }

  @Override
  public void visit(QueryVisitor visitor) {
    if (visitor.acceptField(field)) {
      Term[] terms = new Term[termCount];
      PrefixCodedTerms.TermIterator iterator = termData.iterator();
      for (int i = 0; i < termCount; i++) {
        terms[i] = new Term(field, iterator.next());
      }
      // nocommit: do we need to deal with consumeTermsMatching?
      visitor.consumeTerms(this, terms);
    }
  }

  @Override
  public String toString(String field) {
    StringBuilder builder = new StringBuilder();
    builder.append(field);
    builder.append(":(");

    PrefixCodedTerms.TermIterator iterator = termData.iterator();
    boolean first = true;
    for (BytesRef term = iterator.next(); term != null; term = iterator.next()) {
      if (!first) {
        builder.append(' ');
      }
      first = false;
      builder.append(Term.toString(term));
    }
    builder.append(')');

    return builder.toString();
  }

  @Override
  public boolean equals(Object obj) {
    if (sameClassAs(obj) == false) {
      return false;
    }

    DocValuesTermInSetQuery other = (DocValuesTermInSetQuery) obj;
    return termDataHash == other.termDataHash && termData.equals(other.termData);
  }

  @Override
  public int hashCode() {
    return 31 * classHash() + termDataHash;
  }

  private static class DocValuesTermInSetScoreSupplier extends ScorerSupplier {
    private final Weight weight;
    private final float score;
    private final ScoreMode scoreMode;
    private final PrefixCodedTerms termData;
    private final SortedSetDocValues values;

    DocValuesTermInSetScoreSupplier(Weight weight, float score, ScoreMode scoreMode, PrefixCodedTerms termData, SortedSetDocValues values) {
      this.weight = weight;
      this.score = score;
      this.scoreMode = scoreMode;
      this.termData = termData;
      this.values = values;
    }

    @Override
    public Scorer get(long leadCost) throws IOException {
      LongBitSet termOrdinals = new LongBitSet(values.getValueCount());
      PrefixCodedTerms.TermIterator termIterator = termData.iterator();
      for (BytesRef term = termIterator.next(); term != null; term = termIterator.next()) {
        long ord = values.lookupTerm(term);
        if (ord >= 0) {
          termOrdinals.set(ord);
        }
      }

      SortedDocValues singleton = DocValues.unwrapSingleton(values);
      TwoPhaseIterator iterator;
      if (singleton != null) {
        iterator = new TwoPhaseIterator(singleton) {
          @Override
          public boolean matches() throws IOException {
            long ord = singleton.ordValue();
            return termOrdinals.get(ord);
          }

          @Override
          public float matchCost() {
            return 2;
          }
        };
      } else {
        iterator = new TwoPhaseIterator(values) {
          @Override
          public boolean matches() throws IOException {
            for (long ord = values.nextOrd(); ord != SortedSetDocValues.NO_MORE_ORDS; ord = values.nextOrd()) {
              if (termOrdinals.get(ord)) {
                return true;
              }
            }
            return false;
          }

          @Override
          public float matchCost() {
            return 2;
          }
        };
      }

      return new ConstantScoreScorer(weight, score, scoreMode, iterator);
    }

    @Override
    public long cost() {
      return values.cost();
    }
  }
}
