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

import java.io.IOException;
import java.util.Arrays;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.util.packed.PackedInts;

/** Utility class to encode sequences of 128 small positive integers. */
final class PForUtil {

  static boolean allEqual(long[] l) {
    for (int i = 1; i < ForUtil.BLOCK_SIZE; ++i) {
      if (l[i] != l[0]) {
        return false;
      }
    }
    return true;
  }

  private final ForUtil forUtil;

  PForUtil(ForUtil forUtil) {
    assert ForUtil.BLOCK_SIZE <= 256 : "blocksize must fit in one byte. got " + ForUtil.BLOCK_SIZE;
    this.forUtil = forUtil;
  }

  /** Encode 128 integers from {@code longs} into {@code out}. */
  void encode(long[] longs, DataOutput out) throws IOException {
    long max = -1L;
    for (int i = 0; i < ForUtil.BLOCK_SIZE; ++i) {
      max = Math.max(max, longs[i]);
    }

    final int maxBitsRequired = PackedInts.bitsRequired(max);
    if (allEqual(longs)) {
      out.writeByte((byte) 0);
      out.writeVLong(longs[0]);
    } else {
      out.writeByte((byte) maxBitsRequired);
      forUtil.encode(longs, maxBitsRequired, out);
    }
  }

  /** Decode 128 integers into {@code ints}. */
  void decode(DataInput in, long[] longs) throws IOException {
    final int bitsPerValue = Byte.toUnsignedInt(in.readByte());
    if (bitsPerValue == 0) {
      Arrays.fill(longs, 0, ForUtil.BLOCK_SIZE, in.readVLong());
    } else {
      forUtil.decode(bitsPerValue, in, longs);
    }
  }

  /** Skip 128 integers. */
  void skip(DataInput in) throws IOException {
    final int bitsPerValue = Byte.toUnsignedInt(in.readByte());
    if (bitsPerValue == 0) {
      in.readVLong();
    } else {
      in.skipBytes(forUtil.numBytes(bitsPerValue));
    }
  }
}
