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
package org.apache.lucene.codecs.lucene90;

import org.apache.lucene.index.Impact;
import org.apache.lucene.index.Impacts;
import org.apache.lucene.store.IndexInput;

import java.io.IOException;
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

final class Lucene90ScoreSkipReader extends Lucene90SkipReader {

  private final Impacts impacts;
  private int numLevels = 1;
  private final MutableImpactList dummyImpactsList = new MutableImpactList();

  public Lucene90ScoreSkipReader(
      IndexInput skipStream,
      int maxSkipLevels,
      boolean hasPos,
      boolean hasOffsets,
      boolean hasPayloads) {
    super(skipStream, maxSkipLevels, hasPos, hasOffsets, hasPayloads);
    impacts =
        new Impacts() {

          @Override
          public int numLevels() {
            return numLevels;
          }

          @Override
          public int getDocIdUpTo(int level) {
            return skipDoc[level];
          }

          @Override
          public List<Impact> getImpacts(int level) {
            assert level < numLevels;
            return dummyImpactsList;
          }
        };
  }

  @Override
  public int skipTo(int target) throws IOException {
    int result = super.skipTo(target);
    if (numberOfSkipLevels > 0) {
      numLevels = numberOfSkipLevels;
    } else {
      // End of postings don't have skip data anymore, so we fill with dummy data
      // like SlowImpactsEnum.
      numLevels = 1;
    }
    return result;
  }

  Impacts getImpacts() {
    return impacts;
  }

  static class MutableImpactList extends AbstractList<Impact> implements RandomAccess {
    int length = 1;
    Impact[] impacts = new Impact[] {new Impact(Integer.MAX_VALUE, 1L)};

    @Override
    public Impact get(int index) {
      return impacts[index];
    }

    @Override
    public int size() {
      return length;
    }
  }
}
