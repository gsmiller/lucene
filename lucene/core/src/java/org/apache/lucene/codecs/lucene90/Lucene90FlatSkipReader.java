package org.apache.lucene.codecs.lucene90;

import org.apache.lucene.index.Impact;
import org.apache.lucene.index.Impacts;
import org.apache.lucene.store.IndexInput;

import java.io.Closeable;
import java.io.IOException;
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

final class Lucene90FlatSkipReader implements Closeable {
    private final IndexInput skipStream;

    private final boolean hasPos;
    private final boolean hasPay;
    private final boolean hasOffsets;

    private int nextEntryIdx;
    private long skipBase;
    private int skipEntryCount;
    private long entrySize;
    private int docCount;

    private int nextDoc;
    private int lastDoc;
    private long lastDocPointer;
    private long lastPosPointer;
    private int lastPosBufferUpto;
    private int lastPayloadByteUpto;
    private long lastPayloadPointer;

    private final Impacts impacts;
    private final MutableImpactList dummyImpactsList;

    public Lucene90FlatSkipReader(
            IndexInput skipStream, int maxSkipLevels,
            boolean hasPos, boolean hasOffsets, boolean hasPayloads) {
        this.skipStream = skipStream;
        this.hasPos = hasPos;
        this.hasPay = hasPayloads;
        this.hasOffsets = hasOffsets;
        entrySize = entrySize();

        dummyImpactsList = new MutableImpactList();
        impacts =
                new Impacts() {

                    @Override
                    public int numLevels() {
                        return 1;
                    }

                    @Override
                    public int getDocIdUpTo(int level) {
                        return nextDoc;
                    }

                    @Override
                    public List<Impact> getImpacts(int level) {
                        return dummyImpactsList;
                    }
                };
    }

    public void init(long skipPointer, long docBasePointer, long posBasePointer, long payBasePointer, int df) throws IOException {
        skipBase = skipPointer;
        skipEntryCount = df / ForUtil.BLOCK_SIZE - 1;
        if (df % ForUtil.BLOCK_SIZE > 0) {
            skipEntryCount++;
        }
        docCount = df;
        nextEntryIdx = 0;
        nextDoc = -1;
        lastDoc = 0;
        lastDocPointer = docBasePointer;
        lastPosPointer = posBasePointer;
        lastPayloadPointer = payBasePointer;
    }

    public int skipTo(int target) throws IOException {
        if (nextDoc == -1) {
            if (skipEntryCount == 0) {
                nextDoc = Integer.MAX_VALUE;
            } else {
                nextDoc = readSkipDocOnly(nextEntryIdx);
            }
        }

        if (skipEntryCount == 0) {
            return -1;
        }

        if (nextDoc == Integer.MAX_VALUE) {
            return skipEntryCount * ForUtil.BLOCK_SIZE - 1;
        }

        if (target > nextDoc) {
            nextEntryIdx = seekToBlock(target);
            nextDoc = readSkipDocOnly(nextEntryIdx);
            if (target > nextDoc) {
                loadSkipData(nextEntryIdx);
                nextDoc = Integer.MAX_VALUE;
                return skipEntryCount * ForUtil.BLOCK_SIZE - 1;
            } else {
                loadSkipData(nextEntryIdx - 1);
            }
        }

        return nextEntryIdx * ForUtil.BLOCK_SIZE - 1;
    }

    private int seekToBlock(int target) throws IOException {
        int low = nextEntryIdx, high = skipEntryCount - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            int test = readSkipDocOnly(mid);
            if (target > test) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return high;
    }

    private int readSkipDocOnly(int entryIdx) throws IOException {
        skipStream.seek(skipBase + entryIdx * entrySize);
        return skipStream.readInt();
    }

    private void loadSkipData(int entryIdx) throws IOException {
        skipStream.seek(skipBase + entryIdx * entrySize);

        lastDoc = skipStream.readInt();
        lastDocPointer = skipStream.readLong();

        if (hasPos) {
            lastPosPointer = skipStream.readLong();
            lastPosBufferUpto = skipStream.readInt();

            if (hasPay) {
                lastPayloadByteUpto = skipStream.readInt();
            }

            if (hasPay || hasOffsets) {
                lastPayloadPointer = skipStream.readLong();
            }
        }
    }

    private long entrySize() {
        long size = 12;
        if (hasPos) {
            size += 12;
            if (hasPay) {
                size += 4;
            }
            if (hasPay || hasOffsets) {
                size += 8;
            }
        }

        return size;
    }

    public int getDoc() {
        return lastDoc;
    }

    public long getDocPointer() {
        return lastDocPointer;
    }

    public long getPosPointer() {
        return lastPosPointer;
    }

    public int getPosBufferUpto() {
        return lastPosBufferUpto;
    }

    public long getPayPointer() {
        return lastPayloadPointer;
    }

    public int getPayloadByteUpto() {
        return lastPayloadByteUpto;
    }

    public int getNextSkipDoc() {
        return nextDoc;
    }

    @Override
    public void close() throws IOException {
        if (skipStream != null) {
            skipStream.close();
        }
    }

    Impacts getImpacts() {
        return impacts;
    }

    private static class MutableImpactList extends AbstractList<Impact> implements RandomAccess {
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
