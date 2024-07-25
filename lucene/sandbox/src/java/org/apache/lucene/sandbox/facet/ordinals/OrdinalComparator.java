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
package org.apache.lucene.sandbox.facet.ordinals;

public interface OrdinalComparator {

  // NOTE: We could also just use java's Comparator<Integer> as another option. The advantage
  // of this is that we can work with primitives and avoid int auto-boxing. (We might also be
  // able to leverage the existing IntComparator in Lucene, but I think it's a bit special-purpose)
  int compare(int ordA, int ordB);
}
