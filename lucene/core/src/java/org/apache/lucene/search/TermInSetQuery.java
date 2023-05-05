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
import org.apache.lucene.index.ImpactsEnum;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.PrefixCodedTerms;
import org.apache.lucene.index.PrefixCodedTerms.TermIterator;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermState;
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
 * Specialization for a disjunction over many terms that, by default, behaves like a {@link
 * ConstantScoreQuery} over a {@link BooleanQuery} containing only {@link
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
 * <p>Unless a custom {@link MultiTermQuery.RewriteMethod} is provided, this query executes like a
 * regular disjunction where there are few terms. However, when there are many terms, instead of
 * merging iterators on the fly, it will populate a bit set with matching docs for the least-costly
 * terms and maintain a size-limited set of more costly iterators that are merged on the fly. For
 * more details, see {@link MultiTermQuery#CONSTANT_SCORE_BLENDED_REWRITE}.
 *
 * <p>Users may also provide a custom {@link MultiTermQuery.RewriteMethod} to define different
 * execution behavior, such as relying on doc values (see: {@link
 * MultiTermQuery#DOC_VALUES_REWRITE}), or if scores are required (see: {@link
 * MultiTermQuery#SCORING_BOOLEAN_REWRITE}). See {@link MultiTermQuery} documentation for more
 * rewrite options.
 *
 * <p>NOTE: This query produces scores that are equal to its boost
 */
public class TermInSetQuery extends MultiTermQuery implements Accountable {

  private static final long BASE_RAM_BYTES_USED =
      RamUsageEstimator.shallowSizeOfInstance(TermInSetQuery.class);

  private final String field;
  private final PrefixCodedTerms termData;
  private final int termDataHashCode; // cached hashcode of termData

  public TermInSetQuery(String field, Collection<BytesRef> terms) {
    this(field, packTerms(field, terms));
  }

  public TermInSetQuery(String field, BytesRef... terms) {
    this(field, packTerms(field, Arrays.asList(terms)));
  }

  /** Creates a new {@link TermInSetQuery} from the given collection of terms. */
  public TermInSetQuery(RewriteMethod rewriteMethod, String field, Collection<BytesRef> terms) {
    super(field, rewriteMethod);
    this.field = field;
    this.termData = packTerms(field, terms);
    termDataHashCode = termData.hashCode();
  }

  /** Creates a new {@link TermInSetQuery} from the given array of terms. */
  public TermInSetQuery(RewriteMethod rewriteMethod, String field, BytesRef... terms) {
    this(rewriteMethod, field, Arrays.asList(terms));
  }

  private TermInSetQuery(String field, PrefixCodedTerms termData) {
    super(field, MultiTermQuery.CONSTANT_SCORE_BLENDED_REWRITE);
    this.field = field;
    this.termData = termData;
    termDataHashCode = termData.hashCode();
  }

  private static PrefixCodedTerms packTerms(String field, Collection<BytesRef> terms) {
    BytesRef[] sortedTerms = terms.toArray(new BytesRef[0]);
    // already sorted if we are a SortedSet with natural order
    boolean sorted =
        terms instanceof SortedSet && ((SortedSet<BytesRef>) terms).comparator() == null;
    if (sorted == false) {
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

    return builder.finish();
  }

  @Override
  public long getTermsCount() throws IOException {
    return termData.size();
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
    TermIterator iterator = termData.iterator();
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
    return new IntersectedTermsEnum(terms.iterator());
  }

  private class IntersectedTermsEnum extends TermsEnum {
    private final TermsEnum indexTermsEnum;
    private final TermIterator queryTermsIterator;

    IntersectedTermsEnum(TermsEnum indexTermsEnum) {
      this.indexTermsEnum = indexTermsEnum;
      this.queryTermsIterator = termData.iterator();
    }

    @Override
    public AttributeSource attributes() {
      return indexTermsEnum.attributes();
    }

    @Override
    public BytesRef term() throws IOException {
      return indexTermsEnum.term();
    }

    @Override
    public long ord() throws IOException {
      return indexTermsEnum.ord();
    }

    @Override
    public int docFreq() throws IOException {
      return indexTermsEnum.docFreq();
    }

    @Override
    public long totalTermFreq() throws IOException {
      return indexTermsEnum.totalTermFreq();
    }

    @Override
    public PostingsEnum postings(PostingsEnum reuse, int flags) throws IOException {
      return indexTermsEnum.postings(reuse, flags);
    }

    @Override
    public ImpactsEnum impacts(int flags) throws IOException {
      return indexTermsEnum.impacts(flags);
    }

    @Override
    public TermState termState() throws IOException {
      return indexTermsEnum.termState();
    }

    @Override
    public BytesRef next() throws IOException {
      BytesRef nextTerm;
      while ((nextTerm = queryTermsIterator.next()) != null) {
        if (indexTermsEnum.seekExact(nextTerm)) {
          break;
        }
      }
      return nextTerm;
    }

    @Override
    public boolean seekExact(BytesRef text) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void seekExact(long ord) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void seekExact(BytesRef term, TermState state) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public SeekStatus seekCeil(BytesRef text) throws IOException {
      throw new UnsupportedOperationException();
    }
  }
}
