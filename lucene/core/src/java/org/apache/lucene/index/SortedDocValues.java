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
package org.apache.lucene.index;

import java.io.IOException;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.CompiledAutomaton;

/**
 * A per-document byte[] with presorted values. This is fundamentally an iterator over the int ord
 * values per document, with random access APIs to resolve an int ord to BytesRef.
 *
 * <p>Per-Document values in a SortedDocValues are deduplicated, dereferenced, and sorted into a
 * dictionary of unique values. A pointer to the dictionary value (ordinal) can be retrieved for
 * each document. Ordinals are dense and in increasing sorted order.
 */
public abstract class SortedDocValues extends DocValuesIterator {

  /** Sole constructor. (For invocation by subclass constructors, typically implicit.) */
  protected SortedDocValues() {}

  /**
   * Returns the ordinal for the current docID. It is illegal to call this method after {@link
   * #advanceExact(int)} returned {@code false}.
   *
   * @return ordinal for the document: this is dense, starts at 0, then increments by 1 for the next
   *     value in sorted order.
   */
  public abstract int ordValue() throws IOException;

  /**
   * Retrieves the value for the specified ordinal. The returned {@link BytesRef} may be re-used
   * across calls to {@link #lookupOrd(int)} so make sure to {@link BytesRef#deepCopyOf(BytesRef)
   * copy it} if you want to keep it around.
   *
   * @param ord ordinal to lookup (must be &gt;= 0 and &lt; {@link #getValueCount()})
   * @see #ordValue()
   */
  public abstract BytesRef lookupOrd(int ord) throws IOException;

  /**
   * Advances to the first beyond the current whose ordinal is distinct to the current ordinal and
   * returns the document number itself. Exhausts the iterator and returns {@link #NO_MORE_DOCS} if
   * theerre is no more ordinals distinct to the current one.
   *
   * <p>The behavior of this method is <b>undefined</b> when called with <code> target &le; current
   * </code>, or after the iterator has exhausted. Both cases may result in unpredicted behavior.
   *
   * <p>When <code> target &gt; current</code> it behaves as if written:
   *
   * <pre class="prettyprint">
   * int advanceOrd() {
   *   final long ord = ordValue();
   *   int doc = docID();
   *   while (doc != DocIdSetIterator.NO_MORE_DOCS &amp;&amp; ordValue() == ord) {
   *       doc = nextDoc();
   *    }
   *   return doc;
   * }
   * </pre>
   *
   * Some implementations are considerably more efficient than that.
   */
  public int advanceOrd() throws IOException {
    int doc = docID();
    if (doc == DocIdSetIterator.NO_MORE_DOCS) {
      return doc;
    }
    final long ord = ordValue();
    do {
      doc = nextDoc();
    } while (doc != DocIdSetIterator.NO_MORE_DOCS && ordValue() == ord);
    assert doc == docID();
    return doc;
  }

  /**
   * Returns the number of unique values.
   *
   * @return number of unique values in this SortedDocValues. This is also equivalent to one plus
   *     the maximum ordinal.
   */
  public abstract int getValueCount();

  /**
   * If {@code key} exists, returns its ordinal, else returns {@code -insertionPoint-1}, like {@code
   * Arrays.binarySearch}.
   *
   * @param key Key to look up
   */
  public int lookupTerm(BytesRef key) throws IOException {
    int low = 0;
    int high = getValueCount() - 1;

    while (low <= high) {
      int mid = (low + high) >>> 1;
      final BytesRef term = lookupOrd(mid);
      int cmp = term.compareTo(key);

      if (cmp < 0) {
        low = mid + 1;
      } else if (cmp > 0) {
        high = mid - 1;
      } else {
        return mid; // key found
      }
    }

    return -(low + 1); // key not found.
  }

  /**
   * Returns a {@link TermsEnum} over the values. The enum supports {@link TermsEnum#ord()} and
   * {@link TermsEnum#seekExact(long)}.
   */
  public TermsEnum termsEnum() throws IOException {
    return new SortedDocValuesTermsEnum(this);
  }

  /**
   * Returns a {@link TermsEnum} over the values, filtered by a {@link CompiledAutomaton} The enum
   * supports {@link TermsEnum#ord()}.
   */
  public TermsEnum intersect(CompiledAutomaton automaton) throws IOException {
    TermsEnum in = termsEnum();
    switch (automaton.type) {
      case NONE:
        return TermsEnum.EMPTY;
      case ALL:
        return in;
      case SINGLE:
        return new SingleTermsEnum(in, automaton.term);
      case NORMAL:
        return new AutomatonTermsEnum(in, automaton);
      default:
        // unreachable
        throw new RuntimeException("unhandled case");
    }
  }
}
