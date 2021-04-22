package org.apache.lucene.codecs.lucene90;

import org.apache.lucene.codecs.CompetitiveImpactAccumulator;
import org.apache.lucene.index.Impact;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.store.IndexOutput;

import java.io.IOException;
import java.util.Collection;

final class Lucene90FlatSkipWriter {
    // TODO should be able to record diffs off the starting point at least (maybe doesn't matter since we don't vlong)
    private boolean fieldHasPositions;
    private boolean fieldHasOffsets;
    private boolean fieldHasPayloads;

    private final IndexOutput docOut;

    private final ByteBuffersDataOutput skipBuffer = ByteBuffersDataOutput.newResettableInstance();
    private final ByteBuffersDataOutput freqNormOut = ByteBuffersDataOutput.newResettableInstance();

    public Lucene90FlatSkipWriter(int maxSkipLevels, int blockSize, int docCount, IndexOutput docOut, IndexOutput posOut, IndexOutput payOut) {
        this.docOut = docOut;
    }

    public void setField(boolean fieldHasPositions, boolean fieldHasOffsets, boolean fieldHasPayloads) {
        this.fieldHasPositions = fieldHasPositions;
        this.fieldHasOffsets = fieldHasOffsets;
        this.fieldHasPayloads = fieldHasPayloads;
    }

    public void resetSkip() {
        skipBuffer.reset();
    }

    /**
     * Sets the values for the current skip data.
     */
    public void bufferSkip(int doc, CompetitiveImpactAccumulator competitiveFreqNorms,
                           int numDocs, long posFP, long payFP, int posBufferUpto, int payloadByteUpto) throws IOException {
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

//        assert competitiveFreqNorms.getCompetitiveFreqNormPairs().size() > 0;
//        writeImpacts(competitiveFreqNorms, freqNormOut);
//        skipBuffer.writeVInt(Math.toIntExact(freqNormOut.size()));
//        freqNormOut.copyTo(skipBuffer);
//        freqNormOut.reset();
//        competitiveFreqNorms.clear();
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
            skipBuffer.copyTo(output);
        }

        return skipPointer;
    }

    private static void writeImpacts(CompetitiveImpactAccumulator acc, DataOutput out) throws IOException {
        Collection<Impact> impacts = acc.getCompetitiveFreqNormPairs();
        Impact previous = new Impact(0, 0);
        for (Impact impact : impacts) {
            assert impact.freq > previous.freq;
            assert Long.compareUnsigned(impact.norm, previous.norm) > 0;
            int freqDelta = impact.freq - previous.freq - 1;
            long normDelta = impact.norm - previous.norm - 1;
            if (normDelta == 0) {
                // most of time, norm only increases by 1, so we can fold everything in a single byte
                out.writeVInt(freqDelta << 1);
            } else {
                out.writeVInt((freqDelta << 1) | 1);
                out.writeZLong(normDelta);
            }
            previous = impact;
        }
    }
}
