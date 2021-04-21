package org.apache.lucene.codecs.lucene90;

import org.apache.lucene.store.IndexInput;

import java.io.IOException;

final class Lucene90FlatSkipReader {
    private final IndexInput skipStream;

    private final boolean hasPos;
    private final boolean hasPay;
    private final boolean hasOffsets;

    private int upto;
    private int blockCount;

    private int nextDoc;
    private int lastDoc;
    private long docPointer;
    private long posPointer;
    private int posBufferUpto;
    private int payloadByteUpto;
    private long payPointer;

    public Lucene90FlatSkipReader(
            IndexInput skipStream, int maxSkipLevels,
            boolean hasPos, boolean hasOffsets, boolean hasPayloads) {
        this.skipStream = skipStream;
        this.hasPos = hasPos;
        this.hasPay = hasPayloads;
        this.hasOffsets = hasOffsets;
    }

    public void init(long skipPointer, long docBasePointer, long posBasePointer, long payBasePointer, int df) throws IOException {
        skipStream.seek(skipPointer);
        docPointer = docBasePointer;
        posPointer = posBasePointer;
        payPointer = payBasePointer;
        blockCount = df / ForUtil.BLOCK_SIZE;
        upto = 0;
    }

    public int skipTo(int target) throws IOException {
        for ( ; upto < blockCount; ++upto) {
            if (target <= nextDoc) {
                break;
            }
            readSkipData();
        }

        return (upto * ForUtil.BLOCK_SIZE) - ForUtil.BLOCK_SIZE;
    }

    private void readSkipData() throws IOException {
        lastDoc = nextDoc;

        nextDoc = skipStream.readInt();
        docPointer = skipStream.readLong();

        if (hasPos) {
            posPointer = skipStream.readLong();
            posBufferUpto = skipStream.readInt();

            if (hasPay) {
                payloadByteUpto = skipStream.readInt();
            }

            if (hasPay || hasOffsets) {
                payPointer = skipStream.readLong();
            }
        }
    }

    public int getDoc() {
        return lastDoc;
    }

    public long getDocPointer() {
        return docPointer;
    }

    public long getPosPointer() {
        return posPointer;
    }

    public int getPosBufferUpto() {
        return posBufferUpto;
    }

    public long getPayPointer() {
        return payPointer;
    }

    public int getPayloadByteUpto() {
        return payloadByteUpto;
    }

    public int getNextSkipDoc() {
        return nextDoc;
    }
}
