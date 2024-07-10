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
package org.apache.lucene.sandbox.facet.recorders;

import java.io.IOException;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.sandbox.facet.cutters.LeafFacetCutter;
import org.apache.lucene.sandbox.facet.misc.FacetRollup;
import org.apache.lucene.sandbox.facet.ordinals.OrdinalIterator;

/**
 * Record data for each facet of each doc.
 *
 * <p>TODO: In the next iteration we can add an extra layer between FacetRecorder and
 * LeafFacetRecorder, e.g. SliceFacetRecorder. The new layer will be created per {@link
 * org.apache.lucene.search.Collector}, which means that collecting of multiple leafs (segments)
 * within a slice is sequential and can be done to a single non-sync map to improve performance and
 * reduce memory consumption. We already tried that, but didn't see any performance improvement.
 * Given that it also makes lazy leaf recorder init in {@link
 * org.apache.lucene.sandbox.facet.FacetFieldCollector} trickier, it was decided to rollback the
 * initial attempt and try again later, in the next iteration.
 */
public interface FacetRecorder {
  /** Get leaf recorder. */
  LeafFacetRecorder getLeafRecorder(LeafReaderContext context) throws IOException;

  /** Return next collected ordinal, or {@link LeafFacetCutter#NO_MORE_ORDS} */
  OrdinalIterator recordedOrds();

  /** True if there are no records */
  boolean isEmpty();

  /**
   * Reduce leaf recorder results into this recorder. If facetRollup is not null, it also rolls up
   * values.
   *
   * @throws UnsupportedOperationException if facetRollup is not null but this type of record can't
   *     be rolled up.
   */
  void reduce(FacetRollup facetRollup) throws IOException;
}
