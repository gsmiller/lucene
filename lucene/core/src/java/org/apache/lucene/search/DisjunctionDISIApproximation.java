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

import org.apache.lucene.util.PriorityQueue;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * A {@link DocIdSetIterator} which is a disjunction of the approximations of the provided
 * iterators.
 *
 * @lucene.internal
 */
public class DisjunctionDISIApproximation extends DocIdSetIterator {
  private final DisiPriorityQueue head;
  private final PriorityQueue<DisiWrapper> tail;
  private final long cost;

  private int docID;

  public DisjunctionDISIApproximation(Collection<Scorer> subScorers) {
    this(wrapScorers(subScorers));
  }

  private static DisiWrapper[] wrapScorers(Collection<Scorer> subScorers) {
    DisiWrapper[] disiWrappers = new DisiWrapper[subScorers.size()];
    int i = 0;
    for (Scorer s : subScorers) {
      DisiWrapper w = new DisiWrapper(s);
      disiWrappers[i++] = w;
    }

    return disiWrappers;
  }

  public DisjunctionDISIApproximation(DisiWrapper[] subScorers) {
    this.head = new DisiPriorityQueue(subScorers.length);
    head.addAll(subScorers, 0, subScorers.length);
    this.tail = new PriorityQueue<>(subScorers.length) {
      @Override
      protected boolean lessThan(DisiWrapper a, DisiWrapper b) {
        return a.cost < b.cost;
      }
    };
    long cost = 0;
    for (DisiWrapper w : head) {
      cost += w.cost;
    }
    this.cost = cost;
    this.docID = head.top().approximation.docID();
  }

  public DisiPriorityQueue head() {
    return head;
  }

  public PriorityQueue<DisiWrapper> tail() {
    return tail;
  }

  public void advanceTail() throws IOException {
    DisiWrapper tailTop = tail.pop();
    while (tailTop != null) {
      assert tailTop.doc < docID;
      tailTop.doc = tailTop.approximation.advance(docID);
      head.add(tailTop);
      tailTop = tail.pop();
    }
    assert tail.size() == 0;
  }

  public DisiWrapper topList() throws IOException {
    advanceTail();
    return head.topList();
  }

  @Override
  public long cost() {
    return cost;
  }

  @Override
  public int docID() {
    return docID;
  }

  private int doNext(int target) throws IOException {
    if (target == DocIdSetIterator.NO_MORE_DOCS) {
      docID = DocIdSetIterator.NO_MORE_DOCS;
      return docID;
    }

    DisiWrapper top = head.top();
    while (top != null && top.doc < target) {
      tail.add(top);
      head.pop();
      top = head.top();
    }

    if (head.size() > 0 && top.doc == target) {
      docID = target;
      return docID;
    }

    top = tail.pop();
    while (top != null) {
      top.doc = top.approximation.advance(target);
      boolean match = top.doc == target;
      head.add(top);
      if (match) {
        docID = target;
        return docID;
      }
      top = tail.pop();
    }

    docID = head.top().doc;
    return docID;
  }

  @Override
  public int nextDoc() throws IOException {
    if (docID == DocIdSetIterator.NO_MORE_DOCS) {
      return docID;
    }
    return doNext(docID + 1);
  }

  @Override
  public int advance(int target) throws IOException {
    return doNext(target);
  }
}
