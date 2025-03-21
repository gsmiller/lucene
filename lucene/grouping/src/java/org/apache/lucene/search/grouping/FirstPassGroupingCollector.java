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
package org.apache.lucene.search.grouping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.LeafFieldComparator;
import org.apache.lucene.search.Pruning;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.CollectionUtil;
import org.apache.lucene.util.PriorityQueue;

/**
 * FirstPassGroupingCollector is the first of two passes necessary to collect grouped hits. This
 * pass gathers the top N sorted groups. Groups are defined by a {@link GroupSelector}
 *
 * <p>See {@link org.apache.lucene.search.grouping} for more details including a full code example.
 *
 * @lucene.experimental
 */
public class FirstPassGroupingCollector<T> extends SimpleCollector {

  private final GroupSelector<T> groupSelector;

  private final FieldComparator<?>[] comparators;
  private final LeafFieldComparator[] leafComparators;
  private final int[] reversed;
  private final int topNGroups;
  private final boolean needsScores;
  private final HashMap<T, CollectedSearchGroup<T>> groupMap;
  private final int compIDXEnd;

  private PriorityQueue<CollectedSearchGroup<T>> groupPq;
  private List<SearchGroup<T>> sortedGroups;

  private int docBase;
  private int spareSlot;

  /**
   * Create the first pass collector.
   *
   * @param groupSelector a GroupSelector used to defined groups
   * @param groupSort The {@link Sort} used to sort the groups. The top sorted document within each
   *     group according to groupSort, determines how that group sorts against other groups. This
   *     must be non-null, ie, if you want to groupSort by relevance use Sort.RELEVANCE.
   * @param topNGroups How many top groups to keep.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public FirstPassGroupingCollector(
      GroupSelector<T> groupSelector, Sort groupSort, int topNGroups) {
    this.groupSelector = groupSelector;
    if (topNGroups < 1) {
      throw new IllegalArgumentException("topNGroups must be >= 1 (got " + topNGroups + ")");
    }

    // TODO: allow null groupSort to mean "by relevance",
    // and specialize it?

    this.topNGroups = topNGroups;
    this.needsScores = groupSort.needsScores();
    final SortField[] sortFields = groupSort.getSort();
    comparators = new FieldComparator[sortFields.length];
    leafComparators = new LeafFieldComparator[sortFields.length];
    compIDXEnd = comparators.length - 1;
    reversed = new int[sortFields.length];
    for (int i = 0; i < sortFields.length; i++) {
      final SortField sortField = sortFields[i];

      // use topNGroups + 1 so we have a spare slot to use for comparing (tracked by
      // this.spareSlot):
      comparators[i] = sortField.getComparator(topNGroups + 1, Pruning.NONE);
      reversed[i] = sortField.getReverse() ? -1 : 1;
    }

    spareSlot = topNGroups;
    groupMap = CollectionUtil.newHashMap(topNGroups);
  }

  @Override
  public ScoreMode scoreMode() {
    return needsScores ? ScoreMode.COMPLETE : ScoreMode.COMPLETE_NO_SCORES;
  }

  /**
   * Returns top groups, starting from offset. This may return null, if no groups were collected, or
   * if the number of unique groups collected is &lt;= offset.
   *
   * @param groupOffset The offset in the collected groups
   * @return top groups, starting from offset
   */
  public Collection<SearchGroup<T>> getTopGroups(int groupOffset) throws IOException {

    // System.out.println("FP.getTopGroups groupOffset=" + groupOffset + " fillFields=" + fillFields
    // + " groupMap.size()=" + groupMap.size());

    if (groupOffset < 0) {
      throw new IllegalArgumentException("groupOffset must be >= 0 (got " + groupOffset + ")");
    }

    int groupCount = groupMap.size();
    if (groupCount <= groupOffset) {
      return null;
    }

    if (sortedGroups == null) {
      sortedGroups = new ArrayList<>(groupCount);

      if (groupPq == null) {
        buildPriorityQueue();
      }

      assert groupPq.size() == groupCount;
      final int sortFieldCount = comparators.length;
      for (int i = 0; i < groupCount; i++) {
        CollectedSearchGroup<T> group = groupPq.pop();
        assert group != null;

        SearchGroup<T> searchGroup = new SearchGroup<>();
        searchGroup.groupValue = group.groupValue;
        searchGroup.sortValues = new Object[sortFieldCount];
        for (int sortFieldIDX = 0; sortFieldIDX < sortFieldCount; sortFieldIDX++) {
          searchGroup.sortValues[sortFieldIDX] =
              comparators[sortFieldIDX].value(group.comparatorSlot);
        }
        sortedGroups.add(searchGroup);
      }
      Collections.reverse(sortedGroups);
    }

    return sortedGroups.subList(groupOffset, sortedGroups.size());
  }

  @Override
  public void setScorer(Scorable scorer) throws IOException {
    groupSelector.setScorer(scorer);
    for (LeafFieldComparator comparator : leafComparators) {
      comparator.setScorer(scorer);
    }
  }

  private boolean isCompetitive(int doc) throws IOException {
    // If orderedGroups != null we already have collected N groups and
    // can short circuit by comparing this document to the bottom group,
    // without having to find what group this document belongs to.

    // Even if this document belongs to a group in the top N, we'll know that
    // we don't have to update that group.

    // Downside: if the number of unique groups is very low, this is
    // wasted effort as we will most likely be updating an existing group.
    if (groupPq != null) {
      for (int compIDX = 0; ; compIDX++) {
        final int c = reversed[compIDX] * leafComparators[compIDX].compareBottom(doc);
        if (c < 0) {
          // Definitely not competitive. So don't even bother to continue
          return false;
        } else if (c > 0) {
          // Definitely competitive.
          break;
        } else if (compIDX == compIDXEnd) {
          // Here c=0. If we're at the last comparator, this doc is not
          // competitive, since docs are visited in doc Id order, which means
          // this doc cannot compete with any other document in the queue.
          return false;
        }
      }
    }
    return true;
  }

  @Override
  public void collect(int doc) throws IOException {

    if (isCompetitive(doc) == false) {
      return;
    }

    // TODO: should we add option to mean "ignore docs that
    // don't have the group field" (instead of stuffing them
    // under null group)?
    groupSelector.advanceTo(doc);
    T groupValue = groupSelector.currentValue();

    final CollectedSearchGroup<T> group = groupMap.get(groupValue);

    if (group == null) {

      // First time we are seeing this group, or, we've seen
      // it before but it fell out of the top N and is now
      // coming back

      if (groupMap.size() < topNGroups) {

        // Still in startup transient: we have not
        // seen enough unique groups to start pruning them;
        // just keep collecting them

        // Add a new CollectedSearchGroup:
        CollectedSearchGroup<T> sg = new CollectedSearchGroup<>();
        sg.groupValue = groupSelector.copyValue();
        sg.comparatorSlot = groupMap.size();
        sg.topDoc = docBase + doc;
        for (LeafFieldComparator fc : leafComparators) {
          fc.copy(sg.comparatorSlot, doc);
        }
        groupMap.put(sg.groupValue, sg);

        if (groupMap.size() == topNGroups) {
          // End of startup transient: we now have max
          // number of groups; from here on we will drop
          // bottom group when we insert new one:
          buildPriorityQueue();
        }

        return;
      }

      // We already tested that the document is competitive, so replace
      // the bottom group with this new group.
      assert groupPq.size() == topNGroups;
      CollectedSearchGroup<T> bottomGroup = groupPq.top();

      groupMap.remove(bottomGroup.groupValue);

      // reuse the removed CollectedSearchGroup
      bottomGroup.groupValue = groupSelector.copyValue();
      bottomGroup.topDoc = docBase + doc;

      int lastComparatorSlot = bottomGroup.comparatorSlot;
      for (LeafFieldComparator fc : leafComparators) {
        fc.copy(lastComparatorSlot, doc);
      }

      groupMap.put(bottomGroup.groupValue, bottomGroup);

      bottomGroup = groupPq.updateTop();
      lastComparatorSlot = bottomGroup.comparatorSlot;
      for (LeafFieldComparator fc : leafComparators) {
        fc.setBottom(lastComparatorSlot);
      }

      return;
    }

    // Update existing group:
    for (int compIDX = 0; ; compIDX++) {
      leafComparators[compIDX].copy(spareSlot, doc);

      final int c =
          reversed[compIDX] * comparators[compIDX].compare(group.comparatorSlot, spareSlot);
      if (c < 0) {
        // Definitely not competitive.
        return;
      } else if (c > 0) {
        // Definitely competitive; set remaining comparators:
        for (int compIDX2 = compIDX + 1; compIDX2 < comparators.length; compIDX2++) {
          leafComparators[compIDX2].copy(spareSlot, doc);
        }
        break;
      } else if (compIDX == compIDXEnd) {
        // Here c=0. If we're at the last comparator, this doc is not
        // competitive, since docs are visited in doc Id order, which means
        // this doc cannot compete with any other document in the queue.
        return;
      }
    }

    // Remove before updating the group since lookup is done via comparators
    // TODO: optimize this

    final CollectedSearchGroup<T> prevLast;
    if (groupPq != null) {
      prevLast = groupPq.top();
      // This removal is unfortunate (linear operation). I wonder if we can do something better?
      groupPq.remove(group);
      assert groupPq.size() == topNGroups - 1;
    } else {
      prevLast = null;
    }

    group.topDoc = docBase + doc;

    // Swap slots
    final int tmp = spareSlot;
    spareSlot = group.comparatorSlot;
    group.comparatorSlot = tmp;

    // Re-add the changed group
    if (groupPq != null) {
      groupPq.add(group);
      assert groupPq.size() == topNGroups;
      final CollectedSearchGroup<?> newLast = groupPq.top();
      // If we changed the value of the last group, or changed which group was last, then update
      // bottom:
      if (group == newLast || prevLast != newLast) {
        for (LeafFieldComparator fc : leafComparators) {
          fc.setBottom(newLast.comparatorSlot);
        }
      }
    }
  }

  private void buildPriorityQueue() throws IOException {
    groupPq = new PriorityQueue<>(topNGroups) {
      @Override
      protected boolean lessThan(CollectedSearchGroup<T> a, CollectedSearchGroup<T> b) {
        for (int compIDX = 0; ; compIDX++) {
          FieldComparator<?> fc = comparators[compIDX];
          final int c = reversed[compIDX] * fc.compare(a.comparatorSlot, b.comparatorSlot);
          if (c != 0) {
            return c > 0;
          } else if (compIDX == compIDXEnd) {
            return a.topDoc > b.topDoc;
          }
        }
      }
    };

    groupPq.addAll(groupMap.values());
    assert groupPq.size() == groupMap.size();

    CollectedSearchGroup<T> top = groupPq.top();
    for (LeafFieldComparator fc : leafComparators) {
      fc.setBottom(top.comparatorSlot);
    }
  }

  @Override
  protected void doSetNextReader(LeafReaderContext readerContext) throws IOException {
    docBase = readerContext.docBase;
    for (int i = 0; i < comparators.length; i++) {
      leafComparators[i] = comparators[i].getLeafComparator(readerContext);
    }
    groupSelector.setNextReader(readerContext);
  }

  /**
   * @return the GroupSelector used for this Collector
   */
  public GroupSelector<T> getGroupSelector() {
    return groupSelector;
  }
}
