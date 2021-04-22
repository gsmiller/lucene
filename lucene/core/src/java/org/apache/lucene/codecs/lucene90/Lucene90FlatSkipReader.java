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

    private int upto;
    private int blockCount;

    private int nextDoc;
    private long nextDocPointer;
    private long nextPosPointer;
    private int nextPosBufferUpto;
    private int nextPayloadByteUpto;
    private long nextPayloadPointer;

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
        skipStream.seek(skipPointer);
        nextDocPointer = docBasePointer;
        nextPosPointer = posBasePointer;
        nextPayloadPointer = payBasePointer;
        blockCount = df / ForUtil.BLOCK_SIZE;
        upto = 0;
        nextDoc = 0;
        lastDoc = 0;
    }

    public int skipTo(int target) throws IOException {
        if (nextDoc == Integer.MAX_VALUE) {
            return blockCount * ForUtil.BLOCK_SIZE - 1;
        }

        for ( ; upto < blockCount; ++upto) {
            if (target <= nextDoc) {
                break;
            }
            saveLast();
            readSkipData();
        }

        if (target > nextDoc) {
            saveLast();
            nextDoc = Integer.MAX_VALUE;
            return blockCount * ForUtil.BLOCK_SIZE - 1;
        }

        return upto * ForUtil.BLOCK_SIZE - ForUtil.BLOCK_SIZE - 1;
    }

    private void readSkipData() throws IOException {
        nextDoc = skipStream.readInt();
        nextDocPointer = skipStream.readLong();

        if (hasPos) {
            nextPosPointer = skipStream.readLong();
            nextPosBufferUpto = skipStream.readInt();

            if (hasPay) {
                nextPayloadByteUpto = skipStream.readInt();
            }

            if (hasPay || hasOffsets) {
                nextPayloadPointer = skipStream.readLong();
            }
        }
    }

    private void saveLast() {
        lastDoc = nextDoc;
        lastDocPointer = nextDocPointer;

        if (hasPos) {
            lastPosPointer = nextPosPointer;
            lastPosBufferUpto = nextPosBufferUpto;

            if (hasPay) {
                lastPayloadByteUpto = nextPayloadByteUpto;
            }

            if (hasPay || hasOffsets) {
                lastPayloadPointer = nextPayloadPointer;
            }
        }
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
