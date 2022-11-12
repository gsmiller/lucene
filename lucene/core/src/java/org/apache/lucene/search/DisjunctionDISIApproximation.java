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
import java.util.Collection;

/**
 * A {@link DocIdSetIterator} which is a disjunction of the approximations of the provided
 * iterators.
 *
 * @lucene.internal
 */
public class DisjunctionDISIApproximation extends DocIdSetIterator {
  private final DisiPriorityQueue head;
  private final DisiWrapper[] tail;
  private final long cost;

  private int docID;
  private int tailSize;

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
    this.tail = new DisiWrapper[subScorers.length];
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

  public DisiWrapper[] tail() {
    return tail;
  }

  public int tailSize() {
    return tailSize;
  }

  public void popTail() {
    DisiWrapper w = tail[tailSize - 1];
    head.add(w);
    tailSize--;
  }

  public void advanceTail() throws IOException {
    for (int i = 0; i < tailSize; i++) {
      DisiWrapper w = tail[i];
      assert w.doc < docID;
      w.doc = w.approximation.advance(docID);
      head.add(w);
    }
    tailSize = 0;
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
      tail[tailSize] = top;
      tailSize++;
      head.pop();
      top = head.top();
    }

    if (top != null && top.doc == target) {
      docID = target;
      return docID;
    }

    for (int i = tailSize - 1; i >= 0; i--) {
      DisiWrapper w = tail[i];
      w.doc = w.approximation.advance(target);
      popTail();
      if (w.doc == target) {
        docID = target;
        return docID;
      }
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
