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
  private final DisiPriorityQueue queue;
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

  public DisjunctionDISIApproximation(DisiWrapper[] wrappers) {
    queue = new DisiPriorityQueue(wrappers.length);
    queue.addAll(wrappers, 0, wrappers.length);
    long cost = 0;
    for (DisiWrapper w : queue) {
      cost += w.cost;
    }
    this.cost = cost;
    this.docID = queue.top().approximation.docID();
  }

  public DisiPriorityQueue queue() {
    return queue;
  }

  public void advanceTail() throws IOException {
    DisiWrapper top = queue.top();
    while (top.doc < docID) {
      top.doc = top.approximation.advance(docID);
      top = queue.updateTop();
    }
    assert queue.top().doc >= docID;
  }

  public DisiWrapper topList() throws IOException {
    advanceTail();
    return queue.topList();
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

    DisiWrapper top = queue.top();
    while (top.doc < target) {
      top.doc = top.approximation.advance(target);
      if (top.doc == target) {
        docID = target;
        queue.updateTop();

        return docID;
      } else {
        top = queue.updateTop();
      }
    }
    docID = queue.top().doc;

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
