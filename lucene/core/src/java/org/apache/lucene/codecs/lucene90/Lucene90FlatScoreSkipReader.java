package org.apache.lucene.codecs.lucene90;

import org.apache.lucene.index.Impact;
import org.apache.lucene.index.Impacts;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.ArrayUtil;

import java.io.IOException;
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

final class Lucene90FlatScoreSkipReader extends Lucene90FlatSkipReader {

    private int lastImpactsSize;

    private final Impacts impacts;
    private byte[] impactsData = new byte[0];
    private final ByteArrayDataInput impactsBadi = new ByteArrayDataInput();
    private final MutableImpactList impactsList;

    public Lucene90FlatScoreSkipReader(IndexInput skipStream, int maxSkipLevels, boolean hasPos, boolean hasOffsets, boolean hasPayloads) {
        super(skipStream, maxSkipLevels, hasPos, hasOffsets, hasPayloads);
        impactsList = new MutableImpactList();
        impacts =
                new Impacts() {

                    @Override
                    public int numLevels() {
                        return 1;
                    }

                    @Override
                    public int getDocIdUpTo(int level) {
                        assert level == 0;
                        return nextDoc;
                    }

                    @Override
                    public List<Impact> getImpacts(int level) {
                        assert level == 0;
                        if (lastImpactsSize > 0) {
                            impactsBadi.reset(impactsData, 0, lastImpactsSize);
                            processImpacts(impactsBadi, impactsList);
                            lastImpactsSize = 0;
                        }
                        return impactsList;
                    }
                };
    }

    @Override
    public void init(long skipPointer, long docBasePointer, long posBasePointer, long payBasePointer, int df) throws IOException {
        super.init(skipPointer, docBasePointer, posBasePointer, payBasePointer, df);
        lastImpactsSize = 0;
    }

    Impacts getImpacts() {
        return impacts;
    }

    @Override
    protected void loadRawImpacts(int entryIdx) throws IOException {
        skipStream.seek(skipBase + entryIdx * entrySize + entrySize - 12);

        long impactsPointer = skipBase + skipLength + skipStream.readLong();
        lastImpactsSize = skipStream.readInt();
        assert lastImpactsSize > 0;
        skipStream.seek(impactsPointer);

        if (impactsData.length < lastImpactsSize) {
            impactsData = new byte[ArrayUtil.oversize(lastImpactsSize, Byte.BYTES)];
        }
        skipStream.readBytes(impactsData, 0, lastImpactsSize);
    }

    @Override
    protected void setImpactsLastBlock() {
        lastImpactsSize = 0;
        impactsList.length = 1;
        impactsList.impacts[0].freq = Integer.MAX_VALUE;
        impactsList.impacts[0].norm = 1L;
    }

    private static void processImpacts(ByteArrayDataInput in, MutableImpactList reuse) {
        int maxNumImpacts = in.length(); // at most one impact per byte
        if (reuse.impacts.length < maxNumImpacts) {
            int oldLength = reuse.impacts.length;
            reuse.impacts = ArrayUtil.grow(reuse.impacts, maxNumImpacts);
            for (int i = oldLength; i < reuse.impacts.length; ++i) {
                reuse.impacts[i] = new Impact(Integer.MAX_VALUE, 1L);
            }
        }

        int freq = 0;
        long norm = 0;
        int length = 0;
        while (in.getPosition() < in.length()) {
            int freqDelta = in.readVInt();
            if ((freqDelta & 0x01) != 0) {
                freq += 1 + (freqDelta >>> 1);
                try {
                    norm += 1 + in.readZLong();
                } catch (IOException e) {
                    throw new RuntimeException(e); // cannot happen on a BADI
                }
            } else {
                freq += 1 + (freqDelta >>> 1);
                norm++;
            }
            Impact impact = reuse.impacts[length];
            impact.freq = freq;
            impact.norm = norm;
            length++;
        }
        reuse.length = length;
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
