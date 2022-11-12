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

/**
 * A {@link DocIdSetIterator} which is a disjunction of the approximations of the provided
 * iterators.
 *
 * @lucene.internal
 */
public class DisjunctionDISIApproximation extends DocIdSetIterator {
  final DisiPriorityQueue subIterators;
  final long cost;

  int docID;

  public DisjunctionDISIApproximation(DisiPriorityQueue subIterators) {
    this.subIterators = subIterators;
    long cost = 0;
    for (DisiWrapper w : subIterators) {
      cost += w.cost;
    }
    this.cost = cost;
    this.docID = subIterators.top().approximation.docID();
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

    DisiWrapper top = subIterators.top();
    do {
      top.doc = top.approximation.advance(target);
      if (top.doc == target) {
        subIterators.updateTop();
        docID = target;
        return docID;
      }
      top = subIterators.updateTop();
    } while (top.doc < target);
    docID = top.doc;

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

  public void advanceAll() throws IOException {
    DisiWrapper top = subIterators.top();
    while (top.doc < docID) {
      top.doc = top.approximation.advance(docID);
      top = subIterators.updateTop();
    }
    assert top.doc == docID;
  }
}
