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
package org.apache.lucene.sandbox.facet.ordinals;

import java.io.IOException;
import org.apache.lucene.util.PriorityQueue;

/**
 * Class that consumes incoming ordinals, sorts them by provided Comparable, and returns first top N
 * ordinals only.
 */
public class TopnOrdinalIterator implements OrdinalIterator {

  private final OrdinalComparator ordinalComparator;
  private final OrdinalIterator sourceOrds;
  private final int topN;
  private int[] result;
  private int currentIndex;

  /** Constructor. */
  public TopnOrdinalIterator(
      OrdinalIterator sourceOrds, OrdinalComparator ordinalComparator, int topN) {
    if (topN <= 0) {
      throw new IllegalArgumentException("topN must be > 0 (got: " + topN + ")");
    }
    this.sourceOrds = sourceOrds;
    this.ordinalComparator = ordinalComparator;
    this.topN = topN;
  }

  private void getTopN() throws IOException {
    assert result == null;
    // TODO: current taxonomy implementations limit queue size by taxo reader size too, but it
    //  probably doesn't make sense for large enough taxonomy indexes?
    //  e.g. TopOrdAndIntQueue q = new TopComparableQueue(Math.min(taxoReader.getSize(), topN));
    // TODO: create queue lazily - skip if first nextOrd is NO_MORE_ORDS ?
    TopComparableQueue queue = new TopComparableQueue(topN);
    Container reuse = null;
    for (int ord = sourceOrds.nextOrd(); ord != NO_MORE_ORDS; ord = sourceOrds.nextOrd()) {
      if (reuse == null) {
        reuse = new Container();
      }
      reuse.ord = ord;
      reuse = queue.insertWithOverflow(reuse);
    }
    // Now we need to read from the queue as well as the queue gives the least element, not the top.
    result = new int[queue.size()];
    for (int i = result.length - 1; i >= 0; i--) {
      result[i] = queue.pop().ord;
    }
    currentIndex = 0;
  }

  @Override
  public int nextOrd() throws IOException {
    if (result == null) {
      getTopN();
    }
    assert result != null;
    if (currentIndex >= result.length) {
      return NO_MORE_ORDS;
    }
    return result[currentIndex++];
  }

  /** Keeps top N results ordered by Comparable. */
  private static class TopComparableQueue extends PriorityQueue<Container> {
    private OrdinalComparator comparisonLogic;

    /** Sole constructor. */
    public TopComparableQueue(int topN) {
      super(topN);
    }

    @Override
    protected boolean lessThan(Container a, Container b) {
      return comparisonLogic.compare(a.ord, b.ord) < 0;
    }
  }

  private static class Container {
    int ord;
  }
}
