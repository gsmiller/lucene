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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.FacetsConfig.DimConfig;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.TopOrdAndFloatQueue;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;

/** Base class for all taxonomy-based facets that aggregate to a per-ords float[]. */
public abstract class FloatTaxonomyFacets extends TaxonomyFacets {

  // TODO: also use native hash map for sparse collection, like IntTaxonomyFacets

  /** Per-ordinal value. */
  protected volatile float[] values;

  private final Object mutex = new Object();

  /** Sole constructor. */
  protected FloatTaxonomyFacets(
      String indexFieldName, IndexReader indexReader, TaxonomyReader taxoReader, FacetsConfig config, FacetsCollector facetsCollector) throws IOException {
    super(indexFieldName, indexReader, taxoReader, config, facetsCollector);
  }

  boolean isCounted() {
    synchronized (mutex) {
      return values != null;
    }
  }

  @Override
  void count() throws IOException {
    synchronized (mutex) {
      if (isCounted()) {
        return;
      }

      values = new float[taxoReader.getSize()];
      doCount();
    }
  }

  abstract void doCount() throws IOException;

  @Override
  void countAll() throws IOException {
    synchronized (mutex) {
      if (isCounted()) {
        return;
      }

      values = new float[taxoReader.getSize()];
      doCountAll();
    }
  }

  abstract void doCountAll() throws IOException;

  /** Rolls up any single-valued hierarchical dimensions. */
  protected void rollup() throws IOException {
    // Rollup any necessary dims:
    int[] children = getChildren();
    for (Map.Entry<String, DimConfig> ent : config.getDimConfigs().entrySet()) {
      String dim = ent.getKey();
      DimConfig ft = ent.getValue();
      if (ft.hierarchical && ft.multiValued == false) {
        int dimRootOrd = taxoReader.getOrdinal(new FacetLabel(dim));
        assert dimRootOrd > 0;
        values[dimRootOrd] += rollup(children[dimRootOrd]);
      }
    }
  }

  private float rollup(int ord) throws IOException {
    int[] children = getChildren();
    int[] siblings = getSiblings();
    float sum = 0;
    while (ord != TaxonomyReader.INVALID_ORDINAL) {
      float childValue = values[ord] + rollup(children[ord]);
      values[ord] = childValue;
      sum += childValue;
      ord = siblings[ord];
    }
    return sum;
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
    return values[ord];
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

    TopOrdAndFloatQueue q = new TopOrdAndFloatQueue(Math.min(taxoReader.getSize(), topN));
    float bottomValue = 0;

    int[] children = getChildren();
    int[] siblings = getSiblings();

    int ord = children[dimOrd];
    float sumValues = 0;
    int childCount = 0;

    TopOrdAndFloatQueue.OrdAndValue reuse = null;
    while (ord != TaxonomyReader.INVALID_ORDINAL) {
      if (values[ord] > 0) {
        sumValues += values[ord];
        childCount++;
        if (values[ord] > bottomValue) {
          if (reuse == null) {
            reuse = new TopOrdAndFloatQueue.OrdAndValue();
          }
          reuse.ord = ord;
          reuse.value = values[ord];
          reuse = q.insertWithOverflow(reuse);
          if (q.size() == topN) {
            bottomValue = q.top().value;
          }
        }
      }

      ord = siblings[ord];
    }

    if (sumValues == 0) {
      return null;
    }

    if (dimConfig.multiValued) {
      if (dimConfig.requireDimCount) {
        sumValues = values[dimOrd];
      } else {
        // Our sum'd count is not correct, in general:
        sumValues = -1;
      }
    } else {
      // Our sum'd dim count is accurate, so we keep it
    }

    LabelAndValue[] labelValues = new LabelAndValue[q.size()];
    int[] ordinals = new int[labelValues.length];
    float[] values = new float[labelValues.length];

    for (int i = labelValues.length - 1; i >= 0; i--) {
      TopOrdAndFloatQueue.OrdAndValue ordAndValue = q.pop();
      ordinals[i] = ordAndValue.ord;
      values[i] = ordAndValue.value;
    }

    FacetLabel[] bulkPath = taxoReader.getBulkPath(ordinals);
    for (int i = 0; i < labelValues.length; i++) {
      labelValues[i] = new LabelAndValue(bulkPath[i].components[cp.length], values[i]);
    }

    return new FacetResult(dim, path, sumValues, labelValues, childCount);
  }
}
