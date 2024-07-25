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
package org.apache.lucene.sandbox.facet;

import java.io.IOException;
import org.apache.lucene.sandbox.facet.ordinals.OrdinalComparator;
import org.apache.lucene.sandbox.facet.recorders.CountFacetRecorder;
import org.apache.lucene.sandbox.facet.recorders.LongAggregationsFacetRecorder;
import org.apache.lucene.util.InPlaceMergeSorter;

public class ComparableUtils {
  private ComparableUtils() {}

  /** {@link OrdinalComparator} that can be used to sort by ords (ascending). */
  public static OrdinalComparator byOrdinal() {
    return new OrdinalComparator() {
      @Override
      public int compare(int ordA, int ordB) {
        return Integer.compare(ordB, ordA);
      }
    };
  }

  /**
   * {@link OrdinalComparator} that can be used to sort ordinals by count (descending) with ord as a
   * tie-break (ascending) using provided {@link CountFacetRecorder}.
   */
  public static OrdinalComparator byCount(
      CountFacetRecorder recorder) {
    return new OrdinalComparator() {
      @Override
      public int compare(int ordA, int ordB) {
        int cmp = Integer.compare(recorder.getCount(ordA), recorder.getCount(ordB));
        if (cmp == 0) {
          cmp = Integer.compare(ordB, ordA);
        }
        return cmp;
      }
    };
  }

  /**
   * {@link OrdinalComparator} to sort ordinals by long aggregation (descending) with tie-break by
   * count (descending) with ordinal as a tie-break (ascending) using provided {@link
   * CountFacetRecorder} and {@link LongAggregationsFacetRecorder}.
   */
  public static OrdinalComparator byAggregation(
      CountFacetRecorder countRecorder,
      LongAggregationsFacetRecorder longAggregationsFacetRecorder,
      int aggregationId) {
    return new OrdinalComparator() {
      @Override
      public int compare(int ordA, int ordB) {
        long primaryRankA = longAggregationsFacetRecorder.getRecordedValue(ordA, aggregationId);
        long primaryRankB = longAggregationsFacetRecorder.getRecordedValue(ordB, aggregationId);
        int secondaryRankA = countRecorder.getCount(ordA);
        int secondaryRankB = countRecorder.getCount(ordB);
        int cmp = Long.compare(primaryRankA, primaryRankB);
        if (cmp == 0) {
          cmp = Integer.compare(secondaryRankA, secondaryRankB);
          if (cmp == 0) {
            cmp = Integer.compare(ordB, ordA);
          }
        }
        return cmp;
      }
    };
  }

  /**
   * Sort array of ordinals.
   *
   * <p>To get top-n ordinals use {@link
   * org.apache.lucene.sandbox.facet.ordinals.TopnOrdinalIterator} instead.
   *
   * @param ordinals array of ordinals to sort
   * @param ordinalComparator defines sort order
   */
  public static void sort(int[] ordinals, OrdinalComparator ordinalComparator) throws IOException {
    new InPlaceMergeSorter() {
      @Override
      protected void swap(int i, int j) {
        int tmp = ordinals[i];
        ordinals[i] = ordinals[j];
        ordinals[j] = tmp;
      }

      @Override
      protected int compare(int i, int j) {
        return ordinalComparator.compare(ordinals[j], ordinals[i]);
      }
    }.sort(0, ordinals.length);
  }
}
