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

  // IDENTITY_PLUS_ONE[i] == i+1
  private static final long[] IDENTITY_PLUS_ONE = new long[ForUtil.BLOCK_SIZE];

  static {
    for (int i = 0; i < ForUtil.BLOCK_SIZE; ++i) {
      IDENTITY_PLUS_ONE[i] = i + 1;
    }
  }

  private static void prefixSumOfOnes(long[] arr, long base) {
    System.arraycopy(IDENTITY_PLUS_ONE, 0, arr, 0, ForUtil.BLOCK_SIZE);
    // This loop gets auto-vectorized
    for (int i = 0; i < ForUtil.BLOCK_SIZE; ++i) {
      arr[i] += base;
    }
  }

  private static void prefixSumOf(long val, long[] arr, long base) {
    for (int i = 0; i < ForUtil.BLOCK_SIZE; i++) {
      arr[i] = (i + 1) * val + base;
    }
  }

  static boolean allEqual(long[] l) {
    for (int i = 1; i < ForUtil.BLOCK_SIZE; ++i) {
      if (l[i] != l[0]) {
        return false;
      }
    }
    return true;
  }

  private final ForUtil forUtil;
  private final byte[] byteBuff = new byte[14];

  PForUtil(ForUtil forUtil) {
    assert ForUtil.BLOCK_SIZE <= 256 : "blocksize must fit in one byte. got " + ForUtil.BLOCK_SIZE;
    this.forUtil = forUtil;
  }

  /** Encode 128 integers from {@code longs} into {@code out}. */
  void encode(long[] longs, DataOutput out) throws IOException {
    // At most 7 exceptions
    final long[] top8 = new long[8];
    Arrays.fill(top8, -1L);
    for (int i = 0; i < ForUtil.BLOCK_SIZE; ++i) {
      if (longs[i] > top8[0]) {
        top8[0] = longs[i];
        Arrays.sort(
            top8); // For only 8 entries we just sort on every iteration instead of maintaining a PQ
      }
    }

    final int maxBitsRequired = PackedInts.bitsRequired(top8[7]);
    // We store the patch on a byte, so we can't decrease the number of bits required by more than 8
    final int patchedBitsRequired = Math.max(PackedInts.bitsRequired(top8[0]), maxBitsRequired - 8);
    int numExceptions = 0;
    final long maxUnpatchedValue = (1L << patchedBitsRequired) - 1;
    for (int i = 1; i < 8; ++i) {
      if (top8[i] > maxUnpatchedValue) {
        numExceptions++;
      }
    }
    final byte[] exceptions = new byte[numExceptions * 2];
    if (numExceptions > 0) {
      int exceptionCount = 0;
      for (int i = 0; i < ForUtil.BLOCK_SIZE; ++i) {
        if (longs[i] > maxUnpatchedValue) {
          exceptions[exceptionCount * 2] = (byte) i;
          exceptions[exceptionCount * 2 + 1] = (byte) (longs[i] >>> patchedBitsRequired);
          longs[i] &= maxUnpatchedValue;
          exceptionCount++;
        }
      }
      assert exceptionCount == numExceptions : exceptionCount + " " + numExceptions;
    }

    if (allEqual(longs) && maxBitsRequired <= 8) {
      for (int i = 0; i < numExceptions; ++i) {
        exceptions[2 * i + 1] =
            (byte) (Byte.toUnsignedLong(exceptions[2 * i + 1]) << patchedBitsRequired);
      }
      out.writeByte((byte) (numExceptions << 5));
      out.writeVLong(longs[0]);
    } else {
      final int token = (numExceptions << 5) | patchedBitsRequired;
      out.writeByte((byte) token);
      forUtil.encode(longs, patchedBitsRequired, out);
    }
    out.writeBytes(exceptions, exceptions.length);
  }

  /** Decode 128 integers into {@code ints}. */
  void decode(DataInput in, long[] longs) throws IOException {
    final int token = Byte.toUnsignedInt(in.readByte());
    final int bitsPerValue = token & 0x1f;
    final int numExceptions = token >>> 5;
    if (bitsPerValue == 0) {
      Arrays.fill(longs, 0, ForUtil.BLOCK_SIZE, in.readVLong());
    } else {
      forUtil.decode(bitsPerValue, in, longs);
    }
    for (int i = 0; i < numExceptions; ++i) {
      longs[Byte.toUnsignedInt(in.readByte())] |=
          Byte.toUnsignedLong(in.readByte()) << bitsPerValue;
    }
  }

  /** Decode deltas, compute the prefix sum and add {@code base} to all decoded longs. */
  void decodeAndPrefixSum(DataInput in, long base, long[] longs) throws IOException {
    final int token = Byte.toUnsignedInt(in.readByte());
    final int bitsPerValue = token & 0x1f;
    final int numExceptions = token >>> 5;
    if (numExceptions == 0) {
      // handle the zero-exception case very similarly to ForDeltaUtil
      if (bitsPerValue == 0) {
        long val = in.readVLong();
        if (val == 1) {
          prefixSumOfOnes(longs, base);
        } else {
          prefixSumOf(val, longs, base);
        }
      } else {
        forUtil.decodeAndPrefixSum(bitsPerValue, in, base, longs);
      }
    } else { // we have exceptions
      // pack two values per long so we can apply prefixes two-at-a-time, just like in ForUtil
      if (bitsPerValue == 0) {
        fillSameValue(in, longs);
      } else {
        forUtil.decodeTo32(bitsPerValue, in, longs);
      }
      applyExceptionsIn32Space(in, longs, numExceptions, bitsPerValue);
      prefixSum(longs, base);
    }
  }

  /** Skip 128 integers. */
  void skip(DataInput in) throws IOException {
    final int token = Byte.toUnsignedInt(in.readByte());
    final int bitsPerValue = token & 0x1f;
    final int numExceptions = token >>> 5;
    if (bitsPerValue == 0) {
      in.readVLong();
      in.skipBytes((numExceptions << 1));
    } else {
      in.skipBytes(forUtil.numBytes(bitsPerValue) + (numExceptions << 1));
    }
  }

  private void fillSameValue(DataInput in, long[] longs) throws IOException {
    long read = in.readVLong();
    long val = read << 32 | read; // pack two values into each long
    Arrays.fill(longs, 0, ForUtil.BLOCK_SIZE / 2, val);
  }

  private void applyExceptionsIn32Space(DataInput in, long[] longs, int numExceptions, int bitsPerValue) throws IOException {
    in.readBytes(byteBuff, 0, numExceptions * 2);
    for (int i = 0; i < numExceptions; ++i) {
      int exceptionPos = Byte.toUnsignedInt(byteBuff[i * 2]);
      // note that we pack two values per long, so the index is [0..63] for 128 values
      int idx = exceptionPos & 0x3f; // mod 64
      // we need to shift by 1) the bpv, and 2) 32 for positions [0..63] (and no 32 shift for [64..127])
      int shift = bitsPerValue + ((1 - (exceptionPos >>> 6)) << 5);
      long exception = Byte.toUnsignedLong(byteBuff[i * 2 + 1]) << shift;
      longs[idx] |= exception;
    }
  }

  /**
   * lifted completely from ForUtil
   */
  private static void prefixSum(long[] longs, long base) {
    longs[0] += base << 32;
    innerPrefixSum32(longs);
    expand32(longs);
    long l = longs[ForUtil.BLOCK_SIZE / 2 - 1];
    for (int i = ForUtil.BLOCK_SIZE / 2; i < ForUtil.BLOCK_SIZE; ++i) {
      longs[i] += l;
    }
  }

  /**
   * lifted completely from ForUtil
   */
  private static void expand32(long[] arr) {
    for (int i = 0; i < 64; ++i) {
      long l = arr[i];
      arr[i] = l >>> 32;
      arr[64 + i] = l & 0xFFFFFFFFL;
    }
  }

  /**
   * lifted completely from ForUtil
   */
  private static void innerPrefixSum32(long[] arr) {
    arr[1] += arr[0];
    arr[2] += arr[1];
    arr[3] += arr[2];
    arr[4] += arr[3];
    arr[5] += arr[4];
    arr[6] += arr[5];
    arr[7] += arr[6];
    arr[8] += arr[7];
    arr[9] += arr[8];
    arr[10] += arr[9];
    arr[11] += arr[10];
    arr[12] += arr[11];
    arr[13] += arr[12];
    arr[14] += arr[13];
    arr[15] += arr[14];
    arr[16] += arr[15];
    arr[17] += arr[16];
    arr[18] += arr[17];
    arr[19] += arr[18];
    arr[20] += arr[19];
    arr[21] += arr[20];
    arr[22] += arr[21];
    arr[23] += arr[22];
    arr[24] += arr[23];
    arr[25] += arr[24];
    arr[26] += arr[25];
    arr[27] += arr[26];
    arr[28] += arr[27];
    arr[29] += arr[28];
    arr[30] += arr[29];
    arr[31] += arr[30];
    arr[32] += arr[31];
    arr[33] += arr[32];
    arr[34] += arr[33];
    arr[35] += arr[34];
    arr[36] += arr[35];
    arr[37] += arr[36];
    arr[38] += arr[37];
    arr[39] += arr[38];
    arr[40] += arr[39];
    arr[41] += arr[40];
    arr[42] += arr[41];
    arr[43] += arr[42];
    arr[44] += arr[43];
    arr[45] += arr[44];
    arr[46] += arr[45];
    arr[47] += arr[46];
    arr[48] += arr[47];
    arr[49] += arr[48];
    arr[50] += arr[49];
    arr[51] += arr[50];
    arr[52] += arr[51];
    arr[53] += arr[52];
    arr[54] += arr[53];
    arr[55] += arr[54];
    arr[56] += arr[55];
    arr[57] += arr[56];
    arr[58] += arr[57];
    arr[59] += arr[58];
    arr[60] += arr[59];
    arr[61] += arr[60];
    arr[62] += arr[61];
    arr[63] += arr[62];
  }
}
