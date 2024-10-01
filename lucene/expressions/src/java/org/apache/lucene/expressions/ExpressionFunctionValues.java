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
package org.apache.lucene.expressions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DoubleValues;
import org.apache.lucene.search.DoubleValuesSource;

/** A {@link DoubleValues} which evaluates an expression */
class ExpressionFunctionValues extends DoubleValues {
  final Expression expression;
  final DoubleValuesSource[] variableSources;
  final DoubleValues scores;
  final LeafReaderContext context;
  final Map<String, DoubleValues> valuesCache;

  DoubleValues[] functionValues;

  double currentValue;
  int currentDoc = -1;
  boolean computed;

  ExpressionFunctionValues(
      Expression expression,
      DoubleValuesSource[] variableSources,
      DoubleValues scores,
      LeafReaderContext context) {
    this(expression, variableSources, scores, context, null);
  }

  ExpressionFunctionValues(
      Expression expression,
      DoubleValuesSource[] variableSources,
      DoubleValues scores,
      LeafReaderContext context,
      Map<String, DoubleValues> valuesCache) {
    if (expression == null) {
      throw new NullPointerException();
    }
    this.expression = expression;
    this.variableSources = variableSources;
    this.scores = scores;
    this.context = context;
    this.valuesCache = valuesCache;
    try {
      init();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void init() throws IOException {
    if (functionValues != null) {
      return;
    }

    functionValues = new DoubleValues[expression.variables.length];
    Map<String, DoubleValues> cache =
        valuesCache != null ? valuesCache : new HashMap<>();

    for (int i = 0; i < functionValues.length; i++) {
      String externalName = expression.variables[i];
      DoubleValues values = cache.get(externalName);
      if (values == null) {
        if (variableSources[i] instanceof CachingExpressionValueSource cvs) {
          values = cvs.getValuesWithCache(context, scores, cache);
        } else {
          values = variableSources[i].getValues(context, scores);
        }
        if (values == null) {
          throw new RuntimeException(
              "Unrecognized variable ("
                  + externalName
                  + ") referenced in expression ("
                  + expression.sourceText
                  + ").");
        }
        values = zeroWhenUnpositioned(values);
        cache.put(externalName, values);
      }
      functionValues[i] = values;
    }
  }

  @Override
  public boolean advanceExact(int doc) throws IOException {
    if (currentDoc == doc) {
      return true;
    }
    currentDoc = doc;
    computed = false;
    return true;
  }

  @Override
  public double doubleValue() throws IOException {
    if (computed == false) {
      for (DoubleValues v : functionValues) {
        v.advanceExact(currentDoc);
      }
      currentValue = expression.evaluate(functionValues);
      computed = true;
    }
    return currentValue;
  }

  /**
   * Create a wrapper around all the expression arguments to do two things:
   *
   * <ol>
   *   <li>Default to 0 for any argument that doesn't have a value for a given doc (i.e.,
   *       #advanceExact returns false)
   *   <li>Be as lazy as possible about actually advancing to the given doc until the argument value
   *       is actually needed by the expression. For a given doc, some arguments may not actually be
   *       needed, e.g., because of condition short-circuiting (<code>(true || X)</code> doesn't
   *       need to evaluate <code>X</code>) or ternary branching (<code>true ? X : Y</code> doesn't
   *       need to evaluate <code>Y</code>).
   * </ol>
   */
  private static DoubleValues zeroWhenUnpositioned(DoubleValues in) {
    return new DoubleValues() {

      int currentDoc = -1;
      double value;
      boolean computed = false;

      @Override
      public double doubleValue() throws IOException {
        if (computed == false) {
          value = in.advanceExact(currentDoc) ? in.doubleValue() : 0;
          computed = true;
        }
        return value;
      }

      @Override
      public boolean advanceExact(int doc) {
        if (currentDoc == doc) {
          return true;
        }
        currentDoc = doc;
        computed = false;
        return true;
      }
    };
  }
}
