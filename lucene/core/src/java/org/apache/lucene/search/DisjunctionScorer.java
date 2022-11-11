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
import java.util.Collection;
import java.util.List;
import org.apache.lucene.util.PriorityQueue;

/** Base class for Scorers that score disjunctions. */
abstract class DisjunctionScorer extends Scorer {

  private final boolean needsScores;

  private final DisjunctionDISIApproximation disiApproximation;
  private final DocIdSetIterator approximation;
  private final BlockMaxDISI blockMaxApprox;
  private final TwoPhase twoPhase;

  protected DisjunctionScorer(Weight weight, List<Scorer> subScorers, ScoreMode scoreMode)
      throws IOException {
    super(weight);
    if (subScorers.size() <= 1) {
      throw new IllegalArgumentException("There must be at least 2 subScorers");
    }
    this.disiApproximation = new DisjunctionDISIApproximation(subScorers);
    this.needsScores = scoreMode != ScoreMode.COMPLETE_NO_SCORES;
    if (scoreMode == ScoreMode.TOP_SCORES) {
      for (Scorer scorer : subScorers) {
        scorer.advanceShallow(0);
      }
      this.blockMaxApprox =
          new BlockMaxDISI(disiApproximation, this);
      this.approximation = blockMaxApprox;
    } else {
      this.approximation = disiApproximation;
      this.blockMaxApprox = null;
    }

    boolean hasApproximation = false;
    float sumMatchCost = 0;
    long sumApproxCost = 0;
    // Compute matchCost as the average over the matchCost of the subScorers.
    // This is weighted by the cost, which is an expected number of matching documents.
    DisiWrapper w = disiApproximation.topList();
    while (w != null) {
      long costWeight = (w.cost <= 1) ? 1 : w.cost;
      sumApproxCost += costWeight;
      if (w.twoPhaseView != null) {
        hasApproximation = true;
        sumMatchCost += w.matchCost * costWeight;
      }
      w = w.next;
    }

    if (hasApproximation == false) { // no sub scorer supports approximations
      twoPhase = null;
    } else {
      final float matchCost = sumMatchCost / sumApproxCost;
      twoPhase = new TwoPhase(approximation, matchCost, subScorers.size());
    }
  }

  @Override
  public DocIdSetIterator iterator() {
    if (twoPhase != null) {
      return TwoPhaseIterator.asDocIdSetIterator(twoPhase);
    } else {
      return approximation;
    }
  }

  @Override
  public TwoPhaseIterator twoPhaseIterator() {
    return twoPhase;
  }

  private class TwoPhase extends TwoPhaseIterator {

    private final float matchCost;
    // list of verified matches on the current doc
    DisiWrapper verifiedMatches;
    // priority queue of approximations on the current doc that have not been verified yet
    final PriorityQueue<DisiWrapper> unverifiedMatches;

    private TwoPhase(DocIdSetIterator approximation, float matchCost, int numScorers) {
      super(approximation);
      this.matchCost = matchCost;
      unverifiedMatches =
          new PriorityQueue<>(numScorers) {
            @Override
            protected boolean lessThan(DisiWrapper a, DisiWrapper b) {
              return a.matchCost < b.matchCost;
            }
          };
    }

    DisiWrapper getSubMatches() throws IOException {
      // iteration order does not matter
      for (DisiWrapper w : unverifiedMatches) {
        if (w.twoPhaseView.matches()) {
          w.next = verifiedMatches;
          verifiedMatches = w;
        }
      }
      unverifiedMatches.clear();
      return verifiedMatches;
    }

    @Override
    public boolean matches() throws IOException {
      verifiedMatches = null;
      unverifiedMatches.clear();

      int doc = docID();
      DisiPriorityQueue head = disiApproximation.head();
      PriorityQueue<DisiWrapper> tail = disiApproximation.tail();

      if (needsScores == false) {
        for (DisiWrapper w = head.topList(); w != null; ) {
          DisiWrapper next = w.next;

          if (w.twoPhaseView == null) {
            w.next = null;
            verifiedMatches = w;

            return true;
          } else {
            unverifiedMatches.add(w);
          }

          w = next;
        }

        DisiWrapper tailTop = tail.pop();
        while (tailTop != null) {
          tailTop.doc = tailTop.approximation.advance(doc);
          head.add(tailTop);
          if (tailTop.doc == doc) {
            if (tailTop.twoPhaseView == null) {
              tailTop.next = null;
              verifiedMatches = tailTop;
              tail.pop();

              return true;
            } else {
              unverifiedMatches.add(tailTop);
            }
          }

          tailTop = tail.pop();
        }

        while (unverifiedMatches.size() > 0) {
          DisiWrapper w = unverifiedMatches.pop();
          if (w.twoPhaseView.matches()) {
            w.next = null;
            verifiedMatches = w;
            return true;
          }
        }

        return false;
      } else {
        DisiWrapper tailTop = tail.pop();
        while (tailTop != null) {
          tailTop.doc = tailTop.approximation.advance(doc);
          head.add(tailTop);
          tailTop = tail.pop();
        }

        for (DisiWrapper w = head.topList(); w != null; ) {
          DisiWrapper next = w.next;

          if (w.twoPhaseView == null) {
            // implicitly verified, move it to verifiedMatches
            w.next = verifiedMatches;
            verifiedMatches = w;
          } else {
            unverifiedMatches.add(w);
          }
          w = next;
        }

        if (verifiedMatches != null) {
          return true;
        }

        // verify subs that have an two-phase iterator
        // least-costly ones first
        while (unverifiedMatches.size() > 0) {
          DisiWrapper w = unverifiedMatches.pop();
          if (w.twoPhaseView.matches()) {
            w.next = null;
            verifiedMatches = w;
            return true;
          }
        }

        return false;
      }
    }

    @Override
    public float matchCost() {
      return matchCost;
    }
  }

  @Override
  public final int docID() {
    return approximation.docID();
  }

  BlockMaxDISI getBlockMaxApprox() {
    return blockMaxApprox;
  }

  DisiWrapper getSubMatches() throws IOException {
    if (twoPhase == null) {
      return disiApproximation.topList();
    } else {
      return twoPhase.getSubMatches();
    }
  }

  @Override
  public final float score() throws IOException {
    return score(getSubMatches());
  }

  /** Compute the score for the given linked list of scorers. */
  protected abstract float score(DisiWrapper topList) throws IOException;

  @Override
  public final Collection<ChildScorable> getChildren() throws IOException {
    ArrayList<ChildScorable> children = new ArrayList<>();
    for (DisiWrapper scorer = getSubMatches(); scorer != null; scorer = scorer.next) {
      children.add(new ChildScorable(scorer.scorer, "SHOULD"));
    }
    return children;
  }

//  protected void positionSubIterators() throws IOException {
//    int doc = approximation.docID();
//    DisiWrapper top = subScorers.top();
//    while (top.doc < doc) {
//      top.doc = top.approximation.advance(doc);
//      top = subScorers.updateTop();
//    }
//  }
}
