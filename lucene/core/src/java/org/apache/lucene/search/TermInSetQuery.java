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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import org.apache.lucene.index.FilteredTermsEnum;
import org.apache.lucene.index.PrefixCodedTerms;
import org.apache.lucene.index.PrefixCodedTerms.TermIterator;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.AttributeSource;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.RamUsageEstimator;
import org.apache.lucene.util.automaton.Automata;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.ByteRunAutomaton;
import org.apache.lucene.util.automaton.CompiledAutomaton;
import org.apache.lucene.util.automaton.Operations;

/**
 * Specialization for a disjunction over many terms that behaves like a {@link ConstantScoreQuery}
 * over a {@link BooleanQuery} containing only {@link
 * org.apache.lucene.search.BooleanClause.Occur#SHOULD} clauses.
 *
 * <p>For instance in the following example, both {@code q1} and {@code q2} would yield the same
 * scores:
 *
 * <pre class="prettyprint">
 * Query q1 = new TermInSetQuery("field", new BytesRef("foo"), new BytesRef("bar"));
 *
 * BooleanQuery bq = new BooleanQuery();
 * bq.add(new TermQuery(new Term("field", "foo")), Occur.SHOULD);
 * bq.add(new TermQuery(new Term("field", "bar")), Occur.SHOULD);
 * Query q2 = new ConstantScoreQuery(bq);
 * </pre>
 *
 * <p>When there are few terms, this query executes like a regular disjunction. However, when there
 * are many terms, instead of merging iterators on the fly, it will populate a bit set with matching
 * docs and return a {@link Scorer} over this bit set.
 *
 * <p>NOTE: This query produces scores that are equal to its boost
 */
public class TermInSetQuery extends MultiTermQuery implements Accountable {

  private static final long BASE_RAM_BYTES_USED =
      RamUsageEstimator.shallowSizeOfInstance(TermInSetQuery.class);
  // Same threshold as MultiTermQueryConstantScoreWrapper
  static final int BOOLEAN_REWRITE_TERM_COUNT_THRESHOLD = 16;

  private final String field;
  private final PrefixCodedTerms termData;
  private final int termCount;
  private final int termDataHashCode; // cached hashcode of termData

  /** Creates a new {@link TermInSetQuery} from the given collection of terms. */
  public TermInSetQuery(String field, Collection<BytesRef> terms) {
    super(field);
    int termCount = 0;
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
      termCount++;
    }
    this.field = field;
    termData = builder.finish();
    this.termCount = termCount;
    termDataHashCode = termData.hashCode();
  }

  /** Creates a new {@link TermInSetQuery} from the given array of terms. */
  public TermInSetQuery(String field, BytesRef... terms) {
    this(field, Arrays.asList(terms));
  }

  @Override
  public int getTermsCount() throws IOException {
    return termCount;
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

  // TODO: this is _really_ inefficient, we should not do this...
  private ByteRunAutomaton asByteRunAutomaton() {
    TermIterator iterator = termData.iterator();
    List<Automaton> automata = new ArrayList<>();
    for (BytesRef term = iterator.next(); term != null; term = iterator.next()) {
      automata.add(Automata.makeBinary(term));
    }
    return new CompiledAutomaton(Operations.union(automata)).runAutomaton;
  }

  @Override
  public boolean equals(Object other) {
    return sameClassAs(other) && equalsTo(getClass().cast(other));
  }

  private boolean equalsTo(TermInSetQuery other) {
    // no need to check 'field' explicitly since it is encoded in 'termData'
    // termData might be heavy to compare so check the hash code first
    return termDataHashCode == other.termDataHashCode && termData.equals(other.termData);
  }

  @Override
  public int hashCode() {
    return 31 * classHash() + termDataHashCode;
  }

  /** Returns the terms wrapped in a PrefixCodedTerms. */
  public PrefixCodedTerms getTermData() {
    return termData;
  }

  @Override
  public String toString(String defaultField) {
    StringBuilder builder = new StringBuilder();
    builder.append(field);
    builder.append(":(");

    TermIterator iterator = termData.iterator();
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
  public long ramBytesUsed() {
    return BASE_RAM_BYTES_USED + termData.ramBytesUsed();
  }

  @Override
  public Collection<Accountable> getChildResources() {
    return Collections.emptyList();
  }

  @Override
  protected TermsEnum getTermsEnum(Terms terms, AttributeSource atts) throws IOException {
    return new SetEnum(terms.iterator());
  }

  // like a baby AutomatonTermsEnum, ping-pong intersects the terms dict against our
  // PrefixCodedTerms
  class SetEnum extends FilteredTermsEnum {
    final TermIterator iterator;
    BytesRef seekTerm;

    SetEnum(TermsEnum termsEnum) {
      super(termsEnum);
      iterator = termData.iterator();
      seekTerm = iterator.next();
    }

    @Override
    protected AcceptStatus accept(BytesRef term) throws IOException {
      // next() our iterator until it is >= the incoming term
      // if it matches exactly, its a hit, otherwise its a miss
      int cmp = 0;
      while (seekTerm != null && (cmp = seekTerm.compareTo(term)) < 0) {
        seekTerm = iterator.next();
      }
      if (seekTerm == null) {
        return AcceptStatus.END;
      } else if (cmp == 0) {
        return AcceptStatus.YES_AND_SEEK;
      } else {
        return AcceptStatus.NO_AND_SEEK;
      }
    }

    @Override
    protected BytesRef nextSeekTerm(BytesRef currentTerm) throws IOException {
      // next() our iterator until it is > the currentTerm, must always make progress.
      if (currentTerm == null) {
        return seekTerm;
      }
      while (seekTerm != null && seekTerm.compareTo(currentTerm) <= 0) {
        seekTerm = iterator.next();
      }
      return seekTerm;
    }
  }
}
