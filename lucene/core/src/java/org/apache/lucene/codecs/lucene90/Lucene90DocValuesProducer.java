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

import static org.apache.lucene.codecs.lucene90.Lucene90DocValuesFormat.NUMERIC_BLOCK_MASK;
import static org.apache.lucene.codecs.lucene90.Lucene90DocValuesFormat.NUMERIC_BLOCK_SHIFT;
import static org.apache.lucene.codecs.lucene90.Lucene90DocValuesFormat.NUMERIC_BLOCK_SIZE;
import static org.apache.lucene.codecs.lucene90.Lucene90DocValuesFormat.TERMS_DICT_BLOCK_LZ4_SHIFT;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.index.BaseTermsEnum;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.ImpactsEnum;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.index.TermsEnum.SeekStatus;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.RandomAccessInput;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.LongValues;
import org.apache.lucene.util.compress.LZ4;
import org.apache.lucene.util.packed.DirectMonotonicReader;

/** reader for {@link Lucene90DocValuesFormat} */
final class Lucene90DocValuesProducer extends DocValuesProducer {
  private final Map<String, NumericEntry> numerics = new HashMap<>();
  private final Map<String, BinaryEntry> binaries = new HashMap<>();
  private final Map<String, SortedEntry> sorted = new HashMap<>();
  private final Map<String, SortedSetEntry> sortedSets = new HashMap<>();
  private final Map<String, SortedNumericEntry> sortedNumerics = new HashMap<>();
  private final IndexInput data;
  private final int maxDoc;
  private int version = -1;

  /** expert: instantiates a new reader */
  Lucene90DocValuesProducer(
      SegmentReadState state,
      String dataCodec,
      String dataExtension,
      String metaCodec,
      String metaExtension)
      throws IOException {
    String metaName =
        IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, metaExtension);
    this.maxDoc = state.segmentInfo.maxDoc();

    // read in the entries from the metadata file.
    try (ChecksumIndexInput in = state.directory.openChecksumInput(metaName, state.context)) {
      Throwable priorE = null;

      try {
        version =
            CodecUtil.checkIndexHeader(
                in,
                metaCodec,
                Lucene90DocValuesFormat.VERSION_START,
                Lucene90DocValuesFormat.VERSION_CURRENT,
                state.segmentInfo.getId(),
                state.segmentSuffix);

        readFields(in, state.fieldInfos);

      } catch (Throwable exception) {
        priorE = exception;
      } finally {
        CodecUtil.checkFooter(in, priorE);
      }
    }

    String dataName =
        IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, dataExtension);
    this.data = state.directory.openInput(dataName, state.context);
    boolean success = false;
    try {
      final int version2 =
          CodecUtil.checkIndexHeader(
              data,
              dataCodec,
              Lucene90DocValuesFormat.VERSION_START,
              Lucene90DocValuesFormat.VERSION_CURRENT,
              state.segmentInfo.getId(),
              state.segmentSuffix);
      if (version != version2) {
        throw new CorruptIndexException(
            "Format versions mismatch: meta=" + version + ", data=" + version2, data);
      }

      // NOTE: data file is too costly to verify checksum against all the bytes on open,
      // but for now we at least verify proper structure of the checksum footer: which looks
      // for FOOTER_MAGIC + algorithmID. This is cheap and can detect some forms of corruption
      // such as file truncation.
      CodecUtil.retrieveChecksum(data);

      success = true;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(this.data);
      }
    }
  }

  private void readFields(IndexInput meta, FieldInfos infos) throws IOException {
    for (int fieldNumber = meta.readInt(); fieldNumber != -1; fieldNumber = meta.readInt()) {
      FieldInfo info = infos.fieldInfo(fieldNumber);
      if (info == null) {
        throw new CorruptIndexException("Invalid field number: " + fieldNumber, meta);
      }
      byte type = meta.readByte();
      if (type == Lucene90DocValuesFormat.NUMERIC) {
        numerics.put(info.name, readNumeric(meta));
      } else if (type == Lucene90DocValuesFormat.BINARY) {
        binaries.put(info.name, readBinary(meta));
      } else if (type == Lucene90DocValuesFormat.SORTED) {
        sorted.put(info.name, readSorted(meta));
      } else if (type == Lucene90DocValuesFormat.SORTED_SET) {
        sortedSets.put(info.name, readSortedSet(meta));
      } else if (type == Lucene90DocValuesFormat.SORTED_NUMERIC) {
        sortedNumerics.put(info.name, readSortedNumeric(meta));
      } else {
        throw new CorruptIndexException("invalid type: " + type, meta);
      }
    }
  }

  private NumericEntry readNumeric(IndexInput meta) throws IOException {
    NumericEntry entry = new NumericEntry();
    readNumeric(meta, entry);
    return entry;
  }

  private void readNumeric(IndexInput meta, NumericEntry entry) throws IOException {
    entry.docsWithFieldOffset = meta.readLong();
    entry.docsWithFieldLength = meta.readLong();
    entry.jumpTableEntryCount = meta.readShort();
    entry.denseRankPower = meta.readByte();
    entry.numValues = meta.readLong();
    if (entry.numValues > 0) {
      final int indexBlockShift = meta.readInt();
      entry.indexMeta =
          DirectMonotonicReader.loadMeta(
              meta, 1 + ((entry.numValues - 1) >>> NUMERIC_BLOCK_SHIFT), indexBlockShift);
      entry.indexOffset = meta.readLong();
      entry.indexLength = meta.readLong();
      entry.valuesOffset = meta.readLong();
      entry.valuesLength = meta.readLong();
    }
  }

  private BinaryEntry readBinary(IndexInput meta) throws IOException {
    final BinaryEntry entry = new BinaryEntry();
    entry.dataOffset = meta.readLong();
    entry.dataLength = meta.readLong();
    entry.docsWithFieldOffset = meta.readLong();
    entry.docsWithFieldLength = meta.readLong();
    entry.jumpTableEntryCount = meta.readShort();
    entry.denseRankPower = meta.readByte();
    entry.numDocsWithField = meta.readInt();
    entry.minLength = meta.readInt();
    entry.maxLength = meta.readInt();
    if (entry.minLength < entry.maxLength) {
      entry.addressesOffset = meta.readLong();

      // Old count of uncompressed addresses
      long numAddresses = entry.numDocsWithField + 1L;

      final int blockShift = meta.readVInt();
      entry.addressesMeta = DirectMonotonicReader.loadMeta(meta, numAddresses, blockShift);
      entry.addressesLength = meta.readLong();
    }
    return entry;
  }

  private SortedEntry readSorted(IndexInput meta) throws IOException {
    SortedEntry entry = new SortedEntry();
    entry.ordsEntry = new NumericEntry();
    readNumeric(meta, entry.ordsEntry);
    entry.termsDictEntry = new TermsDictEntry();
    readTermDict(meta, entry.termsDictEntry);
    return entry;
  }

  private SortedSetEntry readSortedSet(IndexInput meta) throws IOException {
    SortedSetEntry entry = new SortedSetEntry();
    byte multiValued = meta.readByte();
    switch (multiValued) {
      case 0: // singlevalued
        entry.singleValueEntry = readSorted(meta);
        return entry;
      case 1: // multivalued
        break;
      default:
        throw new CorruptIndexException("Invalid multiValued flag: " + multiValued, meta);
    }
    entry.ordsEntry = new SortedNumericEntry();
    readSortedNumeric(meta, entry.ordsEntry);
    entry.termsDictEntry = new TermsDictEntry();
    readTermDict(meta, entry.termsDictEntry);
    return entry;
  }

  private static void readTermDict(IndexInput meta, TermsDictEntry entry) throws IOException {
    entry.termsDictSize = meta.readVLong();
    final int blockShift = meta.readInt();
    final long addressesSize =
        (entry.termsDictSize + (1L << TERMS_DICT_BLOCK_LZ4_SHIFT) - 1)
            >>> TERMS_DICT_BLOCK_LZ4_SHIFT;
    entry.termsAddressesMeta = DirectMonotonicReader.loadMeta(meta, addressesSize, blockShift);
    entry.maxTermLength = meta.readInt();
    entry.maxBlockLength = meta.readInt();
    entry.termsDataOffset = meta.readLong();
    entry.termsDataLength = meta.readLong();
    entry.termsAddressesOffset = meta.readLong();
    entry.termsAddressesLength = meta.readLong();
    entry.termsDictIndexShift = meta.readInt();
    final long indexSize =
        (entry.termsDictSize + (1L << entry.termsDictIndexShift) - 1) >>> entry.termsDictIndexShift;
    entry.termsIndexAddressesMeta = DirectMonotonicReader.loadMeta(meta, 1 + indexSize, blockShift);
    entry.termsIndexOffset = meta.readLong();
    entry.termsIndexLength = meta.readLong();
    entry.termsIndexAddressesOffset = meta.readLong();
    entry.termsIndexAddressesLength = meta.readLong();
  }

  private SortedNumericEntry readSortedNumeric(IndexInput meta) throws IOException {
    SortedNumericEntry entry = new SortedNumericEntry();
    readSortedNumeric(meta, entry);
    return entry;
  }

  private SortedNumericEntry readSortedNumeric(IndexInput meta, SortedNumericEntry entry)
      throws IOException {
    readNumeric(meta, entry);
    entry.numDocsWithField = meta.readInt();
    if (entry.numDocsWithField != entry.numValues) {
      entry.addressesOffset = meta.readLong();
      final int blockShift = meta.readVInt();
      entry.addressesMeta =
          DirectMonotonicReader.loadMeta(meta, entry.numDocsWithField + 1, blockShift);
      entry.addressesLength = meta.readLong();
    }
    return entry;
  }

  @Override
  public void close() throws IOException {
    data.close();
  }

  private static class NumericEntry {
    long docsWithFieldOffset;
    long docsWithFieldLength;
    short jumpTableEntryCount;
    byte denseRankPower;
    long numValues;
    long indexOffset;
    long indexLength;
    DirectMonotonicReader.Meta indexMeta;
    long valuesOffset;
    long valuesLength;
  }

  private static class BinaryEntry {
    long dataOffset;
    long dataLength;
    long docsWithFieldOffset;
    long docsWithFieldLength;
    short jumpTableEntryCount;
    byte denseRankPower;
    int numDocsWithField;
    int minLength;
    int maxLength;
    long addressesOffset;
    long addressesLength;
    DirectMonotonicReader.Meta addressesMeta;
  }

  private static class TermsDictEntry {
    long termsDictSize;
    DirectMonotonicReader.Meta termsAddressesMeta;
    int maxTermLength;
    long termsDataOffset;
    long termsDataLength;
    long termsAddressesOffset;
    long termsAddressesLength;
    int termsDictIndexShift;
    DirectMonotonicReader.Meta termsIndexAddressesMeta;
    long termsIndexOffset;
    long termsIndexLength;
    long termsIndexAddressesOffset;
    long termsIndexAddressesLength;

    int maxBlockLength;
  }

  private static class SortedEntry {
    NumericEntry ordsEntry;
    TermsDictEntry termsDictEntry;
  }

  private static class SortedSetEntry {
    SortedEntry singleValueEntry;
    SortedNumericEntry ordsEntry;
    TermsDictEntry termsDictEntry;
  }

  private static class SortedNumericEntry extends NumericEntry {
    int numDocsWithField;
    DirectMonotonicReader.Meta addressesMeta;
    long addressesOffset;
    long addressesLength;
  }

  @Override
  public NumericDocValues getNumeric(FieldInfo field) throws IOException {
    NumericEntry entry = numerics.get(field.name);
    return getNumeric(entry);
  }

  private abstract static class DenseNumericDocValues extends NumericDocValues {

    final int maxDoc;
    int doc = -1;

    DenseNumericDocValues(int maxDoc) {
      this.maxDoc = maxDoc;
    }

    @Override
    public int docID() {
      return doc;
    }

    @Override
    public int nextDoc() throws IOException {
      return advance(doc + 1);
    }

    @Override
    public int advance(int target) throws IOException {
      if (target >= maxDoc) {
        return doc = NO_MORE_DOCS;
      }
      return doc = target;
    }

    @Override
    public boolean advanceExact(int target) {
      doc = target;
      return true;
    }

    @Override
    public long cost() {
      return maxDoc;
    }
  }

  private abstract static class SparseNumericDocValues extends NumericDocValues {

    final IndexedDISI disi;

    SparseNumericDocValues(IndexedDISI disi) {
      this.disi = disi;
    }

    @Override
    public int advance(int target) throws IOException {
      return disi.advance(target);
    }

    @Override
    public boolean advanceExact(int target) throws IOException {
      return disi.advanceExact(target);
    }

    @Override
    public int nextDoc() throws IOException {
      return disi.nextDoc();
    }

    @Override
    public int docID() {
      return disi.docID();
    }

    @Override
    public long cost() {
      return disi.cost();
    }
  }

  private abstract static class NumericValues {
    abstract long advance(long index) throws IOException;
  }

  private NumericValues getValues(NumericEntry entry) throws IOException {
    assert entry.numValues > 0;
    final RandomAccessInput indexSlice =
        data.randomAccessSlice(entry.indexOffset, entry.indexLength);
    final DirectMonotonicReader indexReader =
        DirectMonotonicReader.getInstance(entry.indexMeta, indexSlice);

    final IndexInput valuesData = data.slice("values", entry.valuesOffset, entry.valuesLength);
    return new NumericValues() {

      private final DocValuesEncoder decoder = new DocValuesEncoder();
      private long currentBlockIndex = -1;
      private final long[] currentBlock = new long[NUMERIC_BLOCK_SIZE];

      private DocValuesEncoder.PartiallyDecodedMeta resultsMeta;

      @Override
      long advance(long index) throws IOException {
        final long blockIndex = index >>> NUMERIC_BLOCK_SHIFT;
        final int blockInIndex = (int) (index & NUMERIC_BLOCK_MASK);
        if (blockIndex != currentBlockIndex) {
          assert blockIndex > currentBlockIndex;
          if (blockIndex - 1 > currentBlockIndex) {
            valuesData.seek(indexReader.get(blockIndex));
          }
          currentBlockIndex = blockIndex;
          resultsMeta = decoder.decode(valuesData, currentBlock);
        }

        long result = currentBlock[blockInIndex];
        if (resultsMeta != null) {
          result *= resultsMeta.mul;
          result += resultsMeta.off;
        }

        return result;
      }
    };
  }

  private NumericDocValues getNumeric(NumericEntry entry) throws IOException {
    if (entry.docsWithFieldOffset == -2) {
      // empty
      return DocValues.emptyNumeric();
    }
    final NumericValues values = getValues(entry);
    if (entry.docsWithFieldOffset == -1) {
      // dense
      return new DenseNumericDocValues(maxDoc) {
        @Override
        public long longValue() throws IOException {
          return values.advance(doc);
        }
      };
    } else {
      final IndexedDISI disi =
          new IndexedDISI(
              data,
              entry.docsWithFieldOffset,
              entry.docsWithFieldLength,
              entry.jumpTableEntryCount,
              entry.denseRankPower,
              entry.numValues);
      return new SparseNumericDocValues(disi) {
        @Override
        public long longValue() throws IOException {
          return values.advance(disi.index());
        }
      };
    }
  }

  private abstract static class DenseBinaryDocValues extends BinaryDocValues {

    final int maxDoc;
    int doc = -1;

    DenseBinaryDocValues(int maxDoc) {
      this.maxDoc = maxDoc;
    }

    @Override
    public int nextDoc() throws IOException {
      return advance(doc + 1);
    }

    @Override
    public int docID() {
      return doc;
    }

    @Override
    public long cost() {
      return maxDoc;
    }

    @Override
    public int advance(int target) throws IOException {
      if (target >= maxDoc) {
        return doc = NO_MORE_DOCS;
      }
      return doc = target;
    }

    @Override
    public boolean advanceExact(int target) throws IOException {
      doc = target;
      return true;
    }
  }

  private abstract static class SparseBinaryDocValues extends BinaryDocValues {

    final IndexedDISI disi;

    SparseBinaryDocValues(IndexedDISI disi) {
      this.disi = disi;
    }

    @Override
    public int nextDoc() throws IOException {
      return disi.nextDoc();
    }

    @Override
    public int docID() {
      return disi.docID();
    }

    @Override
    public long cost() {
      return disi.cost();
    }

    @Override
    public int advance(int target) throws IOException {
      return disi.advance(target);
    }

    @Override
    public boolean advanceExact(int target) throws IOException {
      return disi.advanceExact(target);
    }
  }

  @Override
  public BinaryDocValues getBinary(FieldInfo field) throws IOException {
    BinaryEntry entry = binaries.get(field.name);

    if (entry.docsWithFieldOffset == -2) {
      return DocValues.emptyBinary();
    }

    final IndexInput bytesSlice = data.slice("fixed-binary", entry.dataOffset, entry.dataLength);

    if (entry.docsWithFieldOffset == -1) {
      // dense
      if (entry.minLength == entry.maxLength) {
        // fixed length
        final int length = entry.maxLength;
        return new DenseBinaryDocValues(maxDoc) {
          final BytesRef bytes = new BytesRef(new byte[length], 0, length);

          @Override
          public BytesRef binaryValue() throws IOException {
            bytesSlice.seek((long) doc * length);
            bytesSlice.readBytes(bytes.bytes, 0, length);
            return bytes;
          }
        };
      } else {
        // variable length
        final RandomAccessInput addressesData =
            this.data.randomAccessSlice(entry.addressesOffset, entry.addressesLength);
        final LongValues addresses =
            DirectMonotonicReader.getInstance(entry.addressesMeta, addressesData);
        return new DenseBinaryDocValues(maxDoc) {
          final BytesRef bytes = new BytesRef(new byte[entry.maxLength], 0, entry.maxLength);

          @Override
          public BytesRef binaryValue() throws IOException {
            long startOffset = addresses.get(doc);
            bytes.length = (int) (addresses.get(doc + 1L) - startOffset);
            bytesSlice.seek(startOffset);
            bytesSlice.readBytes(bytes.bytes, 0, bytes.length);
            return bytes;
          }
        };
      }
    } else {
      // sparse
      final IndexedDISI disi =
          new IndexedDISI(
              data,
              entry.docsWithFieldOffset,
              entry.docsWithFieldLength,
              entry.jumpTableEntryCount,
              entry.denseRankPower,
              entry.numDocsWithField);
      if (entry.minLength == entry.maxLength) {
        // fixed length
        final int length = entry.maxLength;
        return new SparseBinaryDocValues(disi) {
          final BytesRef bytes = new BytesRef(new byte[length], 0, length);

          @Override
          public BytesRef binaryValue() throws IOException {
            bytesSlice.seek((long) disi.index() * length);
            bytesSlice.readBytes(bytes.bytes, 0, length);
            return bytes;
          }
        };
      } else {
        // variable length
        final RandomAccessInput addressesData =
            this.data.randomAccessSlice(entry.addressesOffset, entry.addressesLength);
        final LongValues addresses =
            DirectMonotonicReader.getInstance(entry.addressesMeta, addressesData);
        return new SparseBinaryDocValues(disi) {
          final BytesRef bytes = new BytesRef(new byte[entry.maxLength], 0, entry.maxLength);

          @Override
          public BytesRef binaryValue() throws IOException {
            final int index = disi.index();
            long startOffset = addresses.get(index);
            bytes.length = (int) (addresses.get(index + 1L) - startOffset);
            bytesSlice.seek(startOffset);
            bytesSlice.readBytes(bytes.bytes, 0, bytes.length);
            return bytes;
          }
        };
      }
    }
  }

  @Override
  public SortedDocValues getSorted(FieldInfo field) throws IOException {
    SortedEntry entry = sorted.get(field.name);
    return getSorted(entry);
  }

  private SortedDocValues getSorted(SortedEntry entry) throws IOException {
    final NumericDocValues ords = getNumeric(entry.ordsEntry);
    return new BaseSortedDocValues(entry, data) {

      @Override
      public int ordValue() throws IOException {
        return (int) ords.longValue();
      }

      @Override
      public boolean advanceExact(int target) throws IOException {
        return ords.advanceExact(target);
      }

      @Override
      public int docID() {
        return ords.docID();
      }

      @Override
      public int nextDoc() throws IOException {
        return ords.nextDoc();
      }

      @Override
      public int advance(int target) throws IOException {
        return ords.advance(target);
      }

      @Override
      public long cost() {
        return ords.cost();
      }
    };
  }

  private abstract static class BaseSortedDocValues extends SortedDocValues {

    final SortedEntry entry;
    final IndexInput data;
    final TermsEnum termsEnum;

    BaseSortedDocValues(SortedEntry entry, IndexInput data) throws IOException {
      this.entry = entry;
      this.data = data;
      this.termsEnum = termsEnum();
    }

    @Override
    public int getValueCount() {
      return Math.toIntExact(entry.termsDictEntry.termsDictSize);
    }

    @Override
    public BytesRef lookupOrd(int ord) throws IOException {
      termsEnum.seekExact(ord);
      return termsEnum.term();
    }

    @Override
    public int lookupTerm(BytesRef key) throws IOException {
      SeekStatus status = termsEnum.seekCeil(key);
      switch (status) {
        case FOUND:
          return Math.toIntExact(termsEnum.ord());
        case NOT_FOUND:
        case END:
        default:
          return Math.toIntExact(-1L - termsEnum.ord());
      }
    }

    @Override
    public TermsEnum termsEnum() throws IOException {
      return new TermsDict(entry.termsDictEntry, data);
    }
  }

  private abstract static class BaseSortedSetDocValues extends SortedSetDocValues {

    final SortedSetEntry entry;
    final IndexInput data;
    final TermsEnum termsEnum;

    BaseSortedSetDocValues(SortedSetEntry entry, IndexInput data) throws IOException {
      this.entry = entry;
      this.data = data;
      this.termsEnum = termsEnum();
    }

    @Override
    public long getValueCount() {
      return entry.termsDictEntry.termsDictSize;
    }

    @Override
    public BytesRef lookupOrd(long ord) throws IOException {
      termsEnum.seekExact(ord);
      return termsEnum.term();
    }

    @Override
    public long lookupTerm(BytesRef key) throws IOException {
      SeekStatus status = termsEnum.seekCeil(key);
      switch (status) {
        case FOUND:
          return termsEnum.ord();
        case NOT_FOUND:
        case END:
        default:
          return -1L - termsEnum.ord();
      }
    }

    @Override
    public TermsEnum termsEnum() throws IOException {
      return new TermsDict(entry.termsDictEntry, data);
    }
  }

  private static class TermsDict extends BaseTermsEnum {
    static final int LZ4_DECOMPRESSOR_PADDING = 7;

    final TermsDictEntry entry;
    final LongValues blockAddresses;
    final IndexInput bytes;
    final long blockMask;
    final LongValues indexAddresses;
    final IndexInput indexBytes;
    final BytesRef term;
    long ord = -1;

    BytesRef blockBuffer = null;
    ByteArrayDataInput blockInput = null;
    long currentCompressedBlockStart = -1;
    long currentCompressedBlockEnd = -1;

    TermsDict(TermsDictEntry entry, IndexInput data) throws IOException {
      this.entry = entry;
      RandomAccessInput addressesSlice =
          data.randomAccessSlice(entry.termsAddressesOffset, entry.termsAddressesLength);
      blockAddresses = DirectMonotonicReader.getInstance(entry.termsAddressesMeta, addressesSlice);
      bytes = data.slice("terms", entry.termsDataOffset, entry.termsDataLength);
      blockMask = (1L << TERMS_DICT_BLOCK_LZ4_SHIFT) - 1;
      RandomAccessInput indexAddressesSlice =
          data.randomAccessSlice(entry.termsIndexAddressesOffset, entry.termsIndexAddressesLength);
      indexAddresses =
          DirectMonotonicReader.getInstance(entry.termsIndexAddressesMeta, indexAddressesSlice);
      indexBytes = data.slice("terms-index", entry.termsIndexOffset, entry.termsIndexLength);
      term = new BytesRef(entry.maxTermLength);

      // add 7 padding bytes can help decompression run faster.
      int bufferSize = entry.maxBlockLength + LZ4_DECOMPRESSOR_PADDING;
      blockBuffer = new BytesRef(new byte[bufferSize], 0, bufferSize);
    }

    @Override
    public BytesRef next() throws IOException {
      if (++ord >= entry.termsDictSize) {
        return null;
      }

      if ((ord & blockMask) == 0L) {
        decompressBlock();
      } else {
        DataInput input = blockInput;
        final int token = Byte.toUnsignedInt(input.readByte());
        int prefixLength = token & 0x0F;
        int suffixLength = 1 + (token >>> 4);
        if (prefixLength == 15) {
          prefixLength += input.readVInt();
        }
        if (suffixLength == 16) {
          suffixLength += input.readVInt();
        }
        term.length = prefixLength + suffixLength;
        input.readBytes(term.bytes, prefixLength, suffixLength);
      }
      return term;
    }

    @Override
    public void seekExact(long ord) throws IOException {
      if (ord < 0 || ord >= entry.termsDictSize) {
        throw new IndexOutOfBoundsException();
      }
      final long blockIndex = ord >>> TERMS_DICT_BLOCK_LZ4_SHIFT;
      final long blockAddress = blockAddresses.get(blockIndex);
      bytes.seek(blockAddress);
      this.ord = (blockIndex << TERMS_DICT_BLOCK_LZ4_SHIFT) - 1;
      do {
        next();
      } while (this.ord < ord);
    }

    private BytesRef getTermFromIndex(long index) throws IOException {
      assert index >= 0 && index <= (entry.termsDictSize - 1) >>> entry.termsDictIndexShift;
      final long start = indexAddresses.get(index);
      term.length = (int) (indexAddresses.get(index + 1) - start);
      indexBytes.seek(start);
      indexBytes.readBytes(term.bytes, 0, term.length);
      return term;
    }

    private long seekTermsIndex(BytesRef text) throws IOException {
      long lo = 0L;
      long hi = (entry.termsDictSize - 1) >>> entry.termsDictIndexShift;
      while (lo <= hi) {
        final long mid = (lo + hi) >>> 1;
        getTermFromIndex(mid);
        final int cmp = term.compareTo(text);
        if (cmp <= 0) {
          lo = mid + 1;
        } else {
          hi = mid - 1;
        }
      }

      assert hi < 0 || getTermFromIndex(hi).compareTo(text) <= 0;
      assert hi == ((entry.termsDictSize - 1) >>> entry.termsDictIndexShift)
          || getTermFromIndex(hi + 1).compareTo(text) > 0;

      return hi;
    }

    private BytesRef getFirstTermFromBlock(long block) throws IOException {
      assert block >= 0 && block <= (entry.termsDictSize - 1) >>> TERMS_DICT_BLOCK_LZ4_SHIFT;
      final long blockAddress = blockAddresses.get(block);
      bytes.seek(blockAddress);
      term.length = bytes.readVInt();
      bytes.readBytes(term.bytes, 0, term.length);
      return term;
    }

    private long seekBlock(BytesRef text) throws IOException {
      long index = seekTermsIndex(text);
      if (index == -1L) {
        return -1L;
      }

      long ordLo = index << entry.termsDictIndexShift;
      long ordHi = Math.min(entry.termsDictSize, ordLo + (1L << entry.termsDictIndexShift)) - 1L;

      long blockLo = ordLo >>> TERMS_DICT_BLOCK_LZ4_SHIFT;
      long blockHi = ordHi >>> TERMS_DICT_BLOCK_LZ4_SHIFT;

      while (blockLo <= blockHi) {
        final long blockMid = (blockLo + blockHi) >>> 1;
        getFirstTermFromBlock(blockMid);
        final int cmp = term.compareTo(text);
        if (cmp <= 0) {
          blockLo = blockMid + 1;
        } else {
          blockHi = blockMid - 1;
        }
      }

      assert blockHi < 0 || getFirstTermFromBlock(blockHi).compareTo(text) <= 0;
      assert blockHi == ((entry.termsDictSize - 1) >>> TERMS_DICT_BLOCK_LZ4_SHIFT)
          || getFirstTermFromBlock(blockHi + 1).compareTo(text) > 0;

      return blockHi;
    }

    @Override
    public SeekStatus seekCeil(BytesRef text) throws IOException {
      final long block = seekBlock(text);
      if (block == -1) {
        // before the first term
        seekExact(0L);
        return SeekStatus.NOT_FOUND;
      }
      final long blockAddress = blockAddresses.get(block);
      this.ord = block << TERMS_DICT_BLOCK_LZ4_SHIFT;
      bytes.seek(blockAddress);
      decompressBlock();

      while (true) {
        int cmp = term.compareTo(text);
        if (cmp == 0) {
          return SeekStatus.FOUND;
        } else if (cmp > 0) {
          return SeekStatus.NOT_FOUND;
        }
        if (next() == null) {
          return SeekStatus.END;
        }
      }
    }

    private void decompressBlock() throws IOException {
      // The first term is kept uncompressed, so no need to decompress block if only
      // look up the first term when doing seek block.
      term.length = bytes.readVInt();
      bytes.readBytes(term.bytes, 0, term.length);
      long offset = bytes.getFilePointer();
      if (offset < entry.termsDataLength - 1) {
        // Avoid decompress again if we are reading a same block.
        if (currentCompressedBlockStart != offset) {
          int decompressLength = bytes.readVInt();
          // Decompress the remaining of current block
          LZ4.decompress(bytes, decompressLength, blockBuffer.bytes, 0);
          currentCompressedBlockStart = offset;
          currentCompressedBlockEnd = bytes.getFilePointer();
        } else {
          // Skip decompression but need to re-seek to block end.
          bytes.seek(currentCompressedBlockEnd);
        }

        // Reset the buffer.
        blockInput = new ByteArrayDataInput(blockBuffer.bytes, 0, blockBuffer.length);
      }
    }

    @Override
    public BytesRef term() throws IOException {
      return term;
    }

    @Override
    public long ord() throws IOException {
      return ord;
    }

    @Override
    public long totalTermFreq() throws IOException {
      return -1L;
    }

    @Override
    public PostingsEnum postings(PostingsEnum reuse, int flags) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public ImpactsEnum impacts(int flags) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public int docFreq() throws IOException {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  public SortedNumericDocValues getSortedNumeric(FieldInfo field) throws IOException {
    SortedNumericEntry entry = sortedNumerics.get(field.name);
    return getSortedNumeric(entry);
  }

  private SortedNumericDocValues getSortedNumeric(SortedNumericEntry entry) throws IOException {
    if (entry.numValues == entry.numDocsWithField) {
      return DocValues.singleton(getNumeric(entry));
    }

    final RandomAccessInput addressesInput =
        data.randomAccessSlice(entry.addressesOffset, entry.addressesLength);
    final LongValues addresses =
        DirectMonotonicReader.getInstance(entry.addressesMeta, addressesInput);

    final NumericValues values = getValues(entry);

    if (entry.docsWithFieldOffset == -1) {
      // dense
      return new SortedNumericDocValues() {

        int doc = -1;
        long start, end;
        int count;

        @Override
        public int nextDoc() throws IOException {
          return advance(doc + 1);
        }

        @Override
        public int docID() {
          return doc;
        }

        @Override
        public long cost() {
          return maxDoc;
        }

        @Override
        public int advance(int target) throws IOException {
          if (target >= maxDoc) {
            return doc = NO_MORE_DOCS;
          }
          start = addresses.get(target);
          end = addresses.get(target + 1L);
          count = (int) (end - start);
          return doc = target;
        }

        @Override
        public boolean advanceExact(int target) throws IOException {
          start = addresses.get(target);
          end = addresses.get(target + 1L);
          count = (int) (end - start);
          doc = target;
          return true;
        }

        @Override
        public long nextValue() throws IOException {
          return values.advance(start++);
        }

        @Override
        public int docValueCount() {
          return count;
        }
      };
    } else {
      // sparse
      final IndexedDISI disi =
          new IndexedDISI(
              data,
              entry.docsWithFieldOffset,
              entry.docsWithFieldLength,
              entry.jumpTableEntryCount,
              entry.denseRankPower,
              entry.numDocsWithField);
      return new SortedNumericDocValues() {

        boolean set;
        long start, end;
        int count;

        @Override
        public int nextDoc() throws IOException {
          set = false;
          return disi.nextDoc();
        }

        @Override
        public int docID() {
          return disi.docID();
        }

        @Override
        public long cost() {
          return disi.cost();
        }

        @Override
        public int advance(int target) throws IOException {
          set = false;
          return disi.advance(target);
        }

        @Override
        public boolean advanceExact(int target) throws IOException {
          set = false;
          return disi.advanceExact(target);
        }

        @Override
        public long nextValue() throws IOException {
          set();
          return values.advance(start++);
        }

        @Override
        public int docValueCount() {
          set();
          return count;
        }

        private void set() {
          if (set == false) {
            final int index = disi.index();
            start = addresses.get(index);
            end = addresses.get(index + 1L);
            count = (int) (end - start);
            set = true;
          }
        }
      };
    }
  }

  @Override
  public SortedSetDocValues getSortedSet(FieldInfo field) throws IOException {
    SortedSetEntry entry = sortedSets.get(field.name);
    if (entry.singleValueEntry != null) {
      return DocValues.singleton(getSorted(entry.singleValueEntry));
    }

    final SortedNumericDocValues ords = getSortedNumeric(entry.ordsEntry);
    return new BaseSortedSetDocValues(entry, data) {

      int i = 0;
      int count = 0;

      @Override
      public long nextOrd() throws IOException {
        if (i++ == count) {
          return NO_MORE_ORDS;
        }
        return ords.nextValue();
      }

      @Override
      public boolean advanceExact(int target) throws IOException {
        if (ords.advanceExact(target)) {
          count = ords.docValueCount();
          i = 0;
          return true;
        } else {
          return false;
        }
      }

      @Override
      public int docID() {
        return ords.docID();
      }

      @Override
      public int nextDoc() throws IOException {
        int doc = ords.nextDoc();
        count = ords.docValueCount();
        i = 0;
        return doc;
      }

      @Override
      public int advance(int target) throws IOException {
        int doc = ords.advance(target);
        count = ords.docValueCount();
        i = 0;
        return doc;
      }

      @Override
      public long cost() {
        return ords.cost();
      }
    };
  }

  @Override
  public void checkIntegrity() throws IOException {
    CodecUtil.checksumEntireFile(data);
  }
}
