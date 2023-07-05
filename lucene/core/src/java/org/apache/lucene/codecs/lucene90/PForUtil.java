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
import org.apache.lucene.util.LongHeap;
import org.apache.lucene.util.packed.PackedInts;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/** Utility class to encode sequences of 128 small positive integers. */
final class PForUtil {

  private static final int MAX_EXCEPTIONS = 7;

  // IDENTITY_PLUS_ONE[i] == i + 1
  private static final long[] IDENTITY_PLUS_ONE = new long[ForUtil.BLOCK_SIZE];
  private static final int[] ONE = new int[ForUtil.BLOCK_SIZE];

  static {
    for (int i = 0; i < ForUtil.BLOCK_SIZE; ++i) {
      IDENTITY_PLUS_ONE[i] = i + 1;
      ONE[i] = 1;
    }
  }

  static boolean allEqual(int[] l) {
    for (int i = 1; i < ForUtil.BLOCK_SIZE; ++i) {
      if (l[i] != l[0]) {
        return false;
      }
    }
    return true;
  }

  private final ForUtil forUtil;
  // buffer for reading exception data; each exception uses two bytes (pos + high-order bits of the
  // exception)
  private final byte[] exceptionBuff = new byte[MAX_EXCEPTIONS * 2];
  private final int[] intBuff = new int[ForUtil.BLOCK_SIZE];

  PForUtil(ForUtil forUtil) {
    assert ForUtil.BLOCK_SIZE <= 256 : "blocksize must fit in one byte. got " + ForUtil.BLOCK_SIZE;
    this.forUtil = forUtil;
  }

  void encode(int[] values, DataOutput out) throws IOException {
    // Determine the top MAX_EXCEPTIONS + 1 values
    final LongHeap top = new LongHeap(MAX_EXCEPTIONS + 1);
    for (int i = 0; i <= MAX_EXCEPTIONS; ++i) {
      top.push(values[i]);
    }
    long topValue = top.top();
    for (int i = MAX_EXCEPTIONS + 1; i < ForUtil.BLOCK_SIZE; ++i) {
      if (values[i] > topValue) {
        topValue = top.updateTop(values[i]);
      }
    }

    long max = 0L;
    for (int i = 1; i <= top.size(); ++i) {
      max = Math.max(max, top.get(i));
    }

    final int maxBitsRequired = PackedInts.bitsRequired(max);
    // We store the patch on a byte, so we can't decrease the number of bits required by more than 8
    final int patchedBitsRequired =
        Math.max(PackedInts.bitsRequired(topValue), maxBitsRequired - 8);
    int numExceptions = 0;
    final long maxUnpatchedValue = (1L << patchedBitsRequired) - 1;
    for (int i = 2; i <= top.size(); ++i) {
      if (top.get(i) > maxUnpatchedValue) {
        numExceptions++;
      }
    }
    final byte[] exceptions = new byte[numExceptions * 2];
    if (numExceptions > 0) {
      int exceptionCount = 0;
      for (int i = 0; i < ForUtil.BLOCK_SIZE; ++i) {
        if (values[i] > maxUnpatchedValue) {
          exceptions[exceptionCount * 2] = (byte) i;
          exceptions[exceptionCount * 2 + 1] = (byte) (values[i] >>> patchedBitsRequired);
          values[i] &= maxUnpatchedValue;
          exceptionCount++;
        }
      }
      assert exceptionCount == numExceptions : exceptionCount + " " + numExceptions;
    }

    if (allEqual(values) && maxBitsRequired <= 8) {
      for (int i = 0; i < numExceptions; ++i) {
        exceptions[2 * i + 1] =
            (byte) (Byte.toUnsignedLong(exceptions[2 * i + 1]) << patchedBitsRequired);
      }
      out.writeByte((byte) (numExceptions << 5));
      out.writeVInt(values[0]);
    } else {
      final int token = (numExceptions << 5) | patchedBitsRequired;
      out.writeByte((byte) token);
      forUtil.encode(values, patchedBitsRequired, out);
    }
    out.writeBytes(exceptions, exceptions.length);
  }

  /** Decode 128 integers into {@code ints}. */
  void decode(DataInput in, int[] output) throws IOException {
    final int token = Byte.toUnsignedInt(in.readByte());
    final int bitsPerValue = token & 0x1f;
    final int numExceptions = token >>> 5;
    if (bitsPerValue == 0) {
      Arrays.fill(output, 0, ForUtil.BLOCK_SIZE, in.readVInt());
    } else {
      forUtil.decode(bitsPerValue, in, output);
    }
    for (int i = 0; i < numExceptions; ++i) {
      output[Byte.toUnsignedInt(in.readByte())] |=
          Byte.toUnsignedInt(in.readByte()) << bitsPerValue;
    }
  }

  void postDecode(DataInput in, int[] longs) throws IOException {
    final int token = Byte.toUnsignedInt(in.readByte());
    final int bitsPerValue = token & 0x1f;
    final int numExceptions = token >>> 5;
    if (numExceptions == 0) {
      // when there are no exceptions to apply, we can be a bit more efficient with our decoding
      if (bitsPerValue == 0) {
        // a bpv of zero indicates all delta values are the same
        long val = in.readVLong();
        if (val == 1) {
          // this will often be the common case when working with doc IDs, so we special-case it to
          // be slightly more efficient
          System.arraycopy(ONE, 0, longs, 0, ForUtil.BLOCK_SIZE);
        } else {
          Arrays.fill(longs, 0, ForUtil.BLOCK_SIZE, (int) val);
        }
      } else {
        // decode the deltas then apply the prefix sum logic
        forUtil.decode(bitsPerValue, in, longs);
      }
    } else {
      // pack two values per long so we can apply prefixes two-at-a-time
      if (bitsPerValue == 0) {
        Arrays.fill(longs, 0, ForUtil.BLOCK_SIZE, (int) in.readVLong());
      } else {
        forUtil.decode(bitsPerValue, in, longs);
      }
      for (int i = 0; i < numExceptions; ++i) {
        longs[Byte.toUnsignedInt(in.readByte())] |=
            Byte.toUnsignedLong(in.readByte()) << bitsPerValue;
      }
    }
  }

  /** Decode deltas, compute the prefix sum and add {@code base} to all decoded longs. */
  void decodeAndPrefixSum(DataInput in, int base, int[] output) throws IOException {
    final int token = Byte.toUnsignedInt(in.readByte());
    final int bitsPerValue = token & 0x1f;
    final int numExceptions = token >>> 5;
    if (numExceptions == 0) {
      // when there are no exceptions to apply, we can be a bit more efficient with our decoding
      if (bitsPerValue == 0) {
        // a bpv of zero indicates all delta values are the same
        int val = in.readVInt();
        if (val == 1) {
          // this will often be the common case when working with doc IDs, so we special-case it to
          // be slightly more efficient
          prefixSumOfOnes(output, base);
        } else {
          prefixSumOf(output, base, val);
        }
      } else {
        // decode the deltas then apply the prefix sum logic
        forUtil.decode(bitsPerValue, in, intBuff);
        prefixSum(intBuff, output, base);
      }
    } else {
      // pack two values per long so we can apply prefixes two-at-a-time
      if (bitsPerValue == 0) {
        int v = in.readVInt();
        Arrays.fill(intBuff, v);
      } else {
        forUtil.decode(bitsPerValue, in, intBuff);
      }
      applyExceptions(bitsPerValue, numExceptions, in, intBuff);
      prefixSum(intBuff, output, base);
    }
  }

  /** Skip 128 integers. */
  void skip(DataInput in) throws IOException {
    final int token = Byte.toUnsignedInt(in.readByte());
    final int bitsPerValue = token & 0x1f;
    final int numExceptions = token >>> 5;
    if (bitsPerValue == 0) {
      in.readVInt();
      in.skipBytes((numExceptions << 1));
    } else {
      in.skipBytes(forUtil.numBytes(bitsPerValue) + (numExceptions << 1));
    }
  }

  /**
   * Fill {@code longs} with the final values for the case of all deltas being 1. Note this assumes
   * there are no exceptions to apply.
   */
  private static void prefixSumOfOnes(int[] output, long base) {
    System.arraycopy(IDENTITY_PLUS_ONE, 0, output, 0, ForUtil.BLOCK_SIZE);
    // This loop gets auto-vectorized
    for (int i = 0; i < ForUtil.BLOCK_SIZE; ++i) {
      output[i] += base;
    }
  }

  /**
   * Fill {@code longs} with the final values for the case of all deltas being {@code val}. Note
   * this assumes there are no exceptions to apply.
   */
  private static void prefixSumOf(int[] output, int base, int val) {
    for (int i = 0; i < ForUtil.BLOCK_SIZE; i++) {
      output[i] = (i + 1) * val + base;
    }
  }

  private void applyExceptions(int bitsPerValue, int numExceptions, DataInput in, int[] output) throws IOException {
    in.readBytes(exceptionBuff, 0, numExceptions * 2);
    for (int i = 0; i < numExceptions; i++) {
      int exceptionPos = Byte.toUnsignedInt(exceptionBuff[i * 2]);
      int exception = Byte.toUnsignedInt(exceptionBuff[i * 2 + 1]);
      output[exceptionPos] |= exception << bitsPerValue;
    }
  }

  private static final VectorSpecies<Integer> SPECIES_128 = IntVector.SPECIES_128;

  private static void prefixSum(int[] input, int[] output, int base) {
    input[0] += base;

    IntVector vec0 = IntVector.fromArray(SPECIES_128, input, 0);
    vec0 = vec0.add(vec0.unslice(1));
    vec0 = vec0.add(vec0.unslice(2));
    vec0.intoArray(output, 0);

    int upperBound = SPECIES_128.loopBound(input.length);
    int specLen = SPECIES_128.length();
    int i = SPECIES_128.length();
    for (; i < upperBound; i += specLen) {
      IntVector vec = IntVector.fromArray(SPECIES_128, input, i);
      vec = vec.add(vec.unslice(1));
      vec = vec.add(vec.unslice(2));
      vec = vec.add(IntVector.broadcast(SPECIES_128, output[i - 1]));
      vec.intoArray(output, i);
    }
    for (; i < input.length; ++i) {
      output[i] = output[i - 1] + input[i];
    }
  }
}
