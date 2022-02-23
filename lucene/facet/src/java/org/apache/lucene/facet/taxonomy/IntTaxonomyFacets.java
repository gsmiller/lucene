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
package org.apache.lucene.facet.taxonomy;

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.cursors.IntIntCursor;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsCollector.MatchingDocs;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.FacetsConfig.DimConfig;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.TopOrdAndIntQueue;
import org.apache.lucene.index.IndexReader;

import java.io.IOException;
import java.util.Map;

/** Base class for all taxonomy-based facets that aggregate to a per-ords int[]. */
abstract class IntTaxonomyFacets extends TaxonomyFacets {

  /** Dense ordinal values. */
  int[] values;

  /** Sparse ordinal values. */
  IntIntHashMap sparseValues;

  /** Sole constructor. */
  IntTaxonomyFacets(
      String indexFieldName, IndexReader indexReader, TaxonomyReader taxoReader, FacetsConfig config, FacetsCollector fc)
      throws IOException {
    super(indexFieldName, indexReader, taxoReader, config, fc);
  }

  /** Increment the count for this ordinal by {@code amount}.. */
  void increment(int ordinal, int amount) {
    if (sparseValues != null) {
      sparseValues.addTo(ordinal, amount);
    } else {
      values[ordinal] += amount;
    }
  }

  /** Get the count for this ordinal. */
  int getValue(int ordinal) {
    if (sparseValues != null) {
      return sparseValues.get(ordinal);
    } else {
      return values[ordinal];
    }
  }

  synchronized void count() throws IOException {
    // Confirm we actually need to do the counting and no other thread beat us to it:
    if (isCounted) {
      return;
    }

    if (useHashTable(facetsCollector, taxoReader)) {
      sparseValues = new IntIntHashMap();
    } else {
      values = new int[taxoReader.getSize()];
    }

    doCount();
    isCounted = true;
  }

  synchronized void countAll() throws IOException {
    // Confirm we actually need to do the counting and no other thread beat us to it:
    if (isCounted) {
      return;
    }

    if (useHashTable(facetsCollector, taxoReader)) {
      sparseValues = new IntIntHashMap();
    } else {
      values = new int[taxoReader.getSize()];
    }

    doCountAll();
    isCounted = true;
  }

  abstract void doCount() throws IOException;

  abstract void doCountAll() throws IOException;

  /** Rolls up any single-valued hierarchical dimensions. */
  void rollup() throws IOException {
    // Rollup any necessary dims:
    int[] children = null;
    for (Map.Entry<String, DimConfig> ent : config.getDimConfigs().entrySet()) {
      String dim = ent.getKey();
      DimConfig ft = ent.getValue();
      if (ft.hierarchical && ft.multiValued == false) {
        int dimRootOrd = taxoReader.getOrdinal(new FacetLabel(dim));
        // It can be -1 if this field was declared in the
        // config but never indexed:
        if (dimRootOrd > 0) {
          if (children == null) {
            // lazy init
            children = getChildren();
          }
          increment(dimRootOrd, rollup(children[dimRootOrd]));
        }
      }
    }
  }

  private int rollup(int ord) throws IOException {
    int[] children = getChildren();
    int[] siblings = getSiblings();
    int sum = 0;
    while (ord != TaxonomyReader.INVALID_ORDINAL) {
      increment(ord, rollup(children[ord]));
      sum += getValue(ord);
      ord = siblings[ord];
    }
    return sum;
  }

  /** Return true if a sparse hash table should be used for counting, instead of a dense int[]. */
  private boolean useHashTable(FacetsCollector fc, TaxonomyReader taxoReader) {
    if (taxoReader.getSize() < 1024) {
      // small number of unique values: use an array
      return false;
    }

    if (fc == null) {
      // counting all docs: use an array
      return false;
    }

    int maxDoc = 0;
    int sumTotalHits = 0;
    for (MatchingDocs docs : fc.getMatchingDocs()) {
      sumTotalHits += docs.totalHits;
      maxDoc += docs.context.reader().maxDoc();
    }

    // if our result set is < 10% of the index, we collect sparsely (use hash map):
    return sumTotalHits < maxDoc / 10;
  }

  @Override
  public Number getSpecificValue(String dim, String... path) throws IOException {
    DimConfig dimConfig = verifyDim(dim);
    if (path.length == 0) {
      if (dimConfig.hierarchical && dimConfig.multiValued == false) {
        // ok: rolled up at search time
      } else if (dimConfig.requireDimCount && dimConfig.multiValued) {
        // ok: we indexed all ords at index time
      } else {
        throw new IllegalArgumentException(
            "cannot return dimension-level value alone; use getTopChildren instead");
      }
    }

    doFullCount();

    int ord = taxoReader.getOrdinal(new FacetLabel(dim, path));
    if (ord < 0) {
      return -1;
    }
    return getValue(ord);
  }

  @Override
  public FacetResult getTopChildren(int topN, String dim, String... path) throws IOException {
    if (topN <= 0) {
      throw new IllegalArgumentException("topN must be > 0 (got: " + topN + ")");
    }
    DimConfig dimConfig = verifyDim(dim);
    FacetLabel cp = new FacetLabel(dim, path);
    int dimOrd = taxoReader.getOrdinal(cp);
    if (dimOrd == -1) {
      return null;
    }

    doFullCount();

    TopOrdAndIntQueue q = new TopOrdAndIntQueue(Math.min(taxoReader.getSize(), topN));

    int bottomValue = 0;

    int totValue = 0;
    int childCount = 0;

    TopOrdAndIntQueue.OrdAndValue reuse = null;

    // TODO: would be faster if we had a "get the following children" API?  then we
    // can make a single pass over the hashmap

    if (sparseValues != null) {
      for (IntIntCursor c : sparseValues) {
        int count = c.value;
        int ord = c.key;
        if (parents[ord] == dimOrd && count > 0) {
          totValue += count;
          childCount++;
          if (count > bottomValue) {
            if (reuse == null) {
              reuse = new TopOrdAndIntQueue.OrdAndValue();
            }
            reuse.ord = ord;
            reuse.value = count;
            reuse = q.insertWithOverflow(reuse);
            if (q.size() == topN) {
              bottomValue = q.top().value;
            }
          }
        }
      }
    } else {
      int[] children = getChildren();
      int[] siblings = getSiblings();
      int ord = children[dimOrd];
      while (ord != TaxonomyReader.INVALID_ORDINAL) {
        int value = values[ord];
        if (value > 0) {
          totValue += value;
          childCount++;
          if (value > bottomValue) {
            if (reuse == null) {
              reuse = new TopOrdAndIntQueue.OrdAndValue();
            }
            reuse.ord = ord;
            reuse.value = value;
            reuse = q.insertWithOverflow(reuse);
            if (q.size() == topN) {
              bottomValue = q.top().value;
            }
          }
        }

        ord = siblings[ord];
      }
    }

    if (totValue == 0) {
      return null;
    }

    if (dimConfig.multiValued) {
      if (dimConfig.requireDimCount) {
        totValue = getValue(dimOrd);
      } else {
        // Our sum'd value is not correct, in general:
        totValue = -1;
      }
    } else {
      // Our sum'd dim value is accurate, so we keep it
    }

    LabelAndValue[] labelValues = new LabelAndValue[q.size()];
    int[] ordinals = new int[labelValues.length];
    int[] values = new int[labelValues.length];

    for (int i = labelValues.length - 1; i >= 0; i--) {
      TopOrdAndIntQueue.OrdAndValue ordAndValue = q.pop();
      ordinals[i] = ordAndValue.ord;
      values[i] = ordAndValue.value;
    }

    FacetLabel[] bulkPath = taxoReader.getBulkPath(ordinals);
    for (int i = 0; i < labelValues.length; i++) {
      labelValues[i] = new LabelAndValue(bulkPath[i].components[cp.length], values[i]);
    }

    return new FacetResult(dim, path, totValue, labelValues, childCount);
  }
}
