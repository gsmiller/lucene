package org.apache.lucene.codecs.lucene90;

import org.apache.lucene.codecs.CompetitiveImpactAccumulator;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.store.IndexOutput;

import java.io.IOException;

final class Lucene90FlatSkipWriter {
    // TODO should be able to record diffs off the starting point at least (maybe doesn't matter since we don't vlong)
    private boolean fieldHasPositions;
    private boolean fieldHasOffsets;
    private boolean fieldHasPayloads;

    private final IndexOutput docOut;

    /** for every skip level a different buffer is used  */
    private ByteBuffersDataOutput skipBuffer;

    private boolean initialized;

    public Lucene90FlatSkipWriter(int maxSkipLevels, int blockSize, int docCount, IndexOutput docOut, IndexOutput posOut, IndexOutput payOut) {
        this.docOut = docOut;
    }

    public void setField(boolean fieldHasPositions, boolean fieldHasOffsets, boolean fieldHasPayloads) {
        this.fieldHasPositions = fieldHasPositions;
        this.fieldHasOffsets = fieldHasOffsets;
        this.fieldHasPayloads = fieldHasPayloads;
    }

    public void resetSkip() {
        initialized = false;
    }

    /**
     * Sets the values for the current skip data.
     */
    public void bufferSkip(int doc, CompetitiveImpactAccumulator competitiveFreqNorms,
                           int numDocs, long posFP, long payFP, int posBufferUpto, int payloadByteUpto) throws IOException {
        initSkip();

        skipBuffer.writeInt(doc);
        skipBuffer.writeLong(docOut.getFilePointer());

        if (fieldHasPositions) {
            skipBuffer.writeLong(posFP);
            skipBuffer.writeInt(posBufferUpto);

            if (fieldHasPayloads) {
                skipBuffer.writeInt(payloadByteUpto);
            }

            if (fieldHasOffsets || fieldHasPayloads) {
                skipBuffer.writeLong(payFP);
            }
        }
    }

    /**
     * Writes the buffered skip lists to the given output.
     *
     * @param output the IndexOutput the skip lists shall be written to
     * @return the pointer the skip list starts
     */
    public long writeSkip(IndexOutput output) throws IOException {
        long skipPointer = output.getFilePointer();
        if (skipBuffer == null) return skipPointer;
        long length = skipBuffer.size();
        if (length > 0) {
            output.writeVLong(length);
            skipBuffer.copyTo(output);
        }

        return skipPointer;
    }

    private void initSkip() {
        if (initialized == false) {
            if (skipBuffer == null) {
                skipBuffer = ByteBuffersDataOutput.newResettableInstance();
            } else {
                skipBuffer.reset();
            }
            initialized = true;
        }
    }
}
