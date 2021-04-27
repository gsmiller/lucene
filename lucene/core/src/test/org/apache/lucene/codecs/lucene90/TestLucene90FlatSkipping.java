package org.apache.lucene.codecs.lucene90;

import com.carrotsearch.randomizedtesting.generators.RandomNumbers;
import org.apache.lucene.codecs.CompetitiveImpactAccumulator;
import org.apache.lucene.index.Impact;
import org.apache.lucene.index.Impacts;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.packed.PackedInts;
import org.junit.Assert;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

public class TestLucene90FlatSkipping extends LuceneTestCase {

    public void testOldVsNew() throws IOException {
        Random random = random();
        CompetitiveImpactAccumulator accum = new CompetitiveImpactAccumulator();
        byte[] buff = new byte[1000];
        random.nextBytes(buff);

        final Directory d = new ByteBuffersDirectory();
        IndexOutput outOld = d.createOutput("test_old.bin", IOContext.DEFAULT);
        IndexOutput outNew = d.createOutput("test_new.bin", IOContext.DEFAULT);

        Lucene90SkipWriter oldWriter =
                new Lucene90SkipWriter(10, ForUtil.BLOCK_SIZE, Integer.MAX_VALUE, outOld, null, null);
        Lucene90FlatSkipWriter newWriter =
                new Lucene90FlatSkipWriter(1, ForUtil.BLOCK_SIZE, Integer.MAX_VALUE, outNew, null, null);

        int postingsCount = RandomNumbers.randomIntBetween(random, 100, 1000);
        List<Long> postingStartPositionsOld = new ArrayList<>();
        List<Long> postingStartPositionsNew = new ArrayList<>();
        List<Integer> postingDocCounts = new ArrayList<>();
        List<List<Long>> expectedBlockPositionsOld = new ArrayList<>();
        List<List<Long>> expectedBlockPositionsNew = new ArrayList<>();
        List<List<Integer>> expectedLastReadDocIds = new ArrayList<>();
        List<List<Integer>> expectedNextSkipDocIds = new ArrayList<>();
        List<Long> skipStartPositionsOld = new ArrayList<>();
        List<Long> skipStartPositionsNew = new ArrayList<>();
        List<List<Collection<Impact>>> expectedImpacts = new ArrayList<>();
        for (int i = 0; i < postingsCount; i++) {
            int numBlocks = RandomNumbers.randomIntBetween(random, 1, 512);
            if (random.nextInt(100) < 20) {
                numBlocks = 0;
            }
            int numExtra = random.nextInt(ForUtil.BLOCK_SIZE);
            if (numBlocks > 0 && random.nextInt(100) < 20) {
                numExtra = 0;
            }
            int docCount = numBlocks * ForUtil.BLOCK_SIZE + numExtra;
            postingDocCounts.add(docCount);
            int maxBitsPerValue = (int) Math.floor(31D - (Math.log(docCount)));
            int maxDocDelta = (int) PackedInts.maxValue(maxBitsPerValue - 1);
            maxDocDelta = 10; // TODO shouldn't need this

            oldWriter.setField(false, false, false);
            newWriter.setField(false, false, false);
            oldWriter.resetSkip();
            newWriter.resetSkip();

            postingStartPositionsOld.add(outOld.getFilePointer());
            postingStartPositionsNew.add(outNew.getFilePointer());
            List<Long> blockPositionsOld = new ArrayList<>();
            List<Long> blockPositionsNew = new ArrayList<>();
            List<Integer> lastReadDocIds = new ArrayList<>();
            List<Integer> nextDocIds = new ArrayList<>();
            List<Collection<Impact>> impacts = new ArrayList<>();
            int docId = 0;
            lastReadDocIds.add(0);  // TODO why not -1?
            blockPositionsOld.add(outOld.getFilePointer());
            blockPositionsNew.add(outNew.getFilePointer());
            for (int j = 0; j < docCount; j++) {
                docId += random.nextInt(maxDocDelta);
                int freq = RandomNumbers.randomIntBetween(random, 1, 1000);
                long norm = RandomNumbers.randomLongBetween(random, 1, 100);
                accum.add(freq, norm);
                if (j % ForUtil.BLOCK_SIZE == 0 && j != 0) {
                    int blockBytes = random.nextInt(buff.length + 1);
                    outOld.writeBytes(buff, blockBytes);
                    outNew.writeBytes(buff, blockBytes);
                    blockPositionsOld.add(outOld.getFilePointer());
                    blockPositionsNew.add(outNew.getFilePointer());
                    lastReadDocIds.add(docId);
                    nextDocIds.add(docId);

                    oldWriter.bufferSkip(docId, accum, j, 0, 0, 0, 0);
                    newWriter.bufferSkip(docId, accum, j, 0, 0, 0, 0);
                    impacts.add(accum.getCompetitiveFreqNormPairs());
                    accum.clear();
                }
            }
            nextDocIds.add(Integer.MAX_VALUE);
            expectedBlockPositionsOld.add(blockPositionsOld);
            expectedBlockPositionsNew.add(blockPositionsNew);
            expectedLastReadDocIds.add(lastReadDocIds);
            expectedNextSkipDocIds.add(nextDocIds);
            skipStartPositionsOld.add(oldWriter.writeSkip(outOld));
            skipStartPositionsNew.add(newWriter.writeSkip(outNew));
            expectedImpacts.add(impacts);
        }
        outOld.close();
        outNew.close();

        IndexInput inOld = d.openInput("test_old.bin", IOContext.READONCE);
        IndexInput inNew = d.openInput("test_new.bin", IOContext.READONCE);

        Lucene90ScoreSkipReader oldReader =
                new Lucene90ScoreSkipReader(inOld, 10, false, false, false);
        Lucene90FlatScoreSkipReader newReader =
                new Lucene90FlatScoreSkipReader(inNew, 1, false, false, false);
        for (int i = 0; i < postingsCount; i++) {
            long basePosOld = postingStartPositionsOld.get(i);
            long basePosNew = postingStartPositionsNew.get(i);
            long skipPosOld = skipStartPositionsOld.get(i);
            long skipPosNew = skipStartPositionsNew.get(i);
            int docCount = postingDocCounts.get(i);
            List<Long> blockPositionsOld = expectedBlockPositionsOld.get(i);
            List<Long> blockPositionsNew = expectedBlockPositionsNew.get(i);
            List<Integer> lastReadDocIds = expectedLastReadDocIds.get(i);
            List<Integer> nextDocIds = expectedNextSkipDocIds.get(i);
            Assert.assertEquals(blockPositionsOld.size(), blockPositionsNew.size());
            int blockCount = blockPositionsOld.size();

            oldReader.init(skipPosOld, basePosOld, 0, 0, docCount);
            newReader.init(skipPosNew, basePosNew, 0, 0, docCount);

            for (int j = 0; j < blockCount; j++) {
                int tmp = random.nextInt(100);
                if (tmp < 10) {
                    j = Math.min(blockCount - 1, j + 50);
                } else if (tmp < 20) {
                    j = Math.min(blockCount - 1, j + 10);
                } else if (tmp < 50) {
                    continue;
                }

                int docLow = lastReadDocIds.get(j) + 1;
                int docHigh = nextDocIds.get(j);
                int skipTo = RandomNumbers.randomIntBetween(random, docLow, docHigh);

                int oldUpto = oldReader.skipTo(skipTo);
                int newUpto = newReader.skipTo(skipTo);
                Assert.assertEquals(oldUpto, newUpto);
                Assert.assertEquals(j * ForUtil.BLOCK_SIZE - 1, newUpto);

                int oldDoc = oldReader.getDoc();
                int newDoc = newReader.getDoc();
                Assert.assertEquals(oldDoc, newDoc);
                Assert.assertEquals((int) lastReadDocIds.get(j), newDoc);

                long oldDocPtr = oldReader.getDocPointer();
                long newDocPtr = newReader.getDocPointer();
                Assert.assertEquals(oldDocPtr - basePosOld, newDocPtr - basePosNew);
                Assert.assertEquals((long) blockPositionsOld.get(j), oldDocPtr);
                Assert.assertEquals((long) blockPositionsNew.get(j), newDocPtr);

                int oldNextDoc = oldReader.getNextSkipDoc();
                int newNextDoc = newReader.getNextSkipDoc();
                Assert.assertEquals(oldNextDoc, newNextDoc);
                Assert.assertEquals((int) nextDocIds.get(j), newNextDoc);

                Impacts oldImpacts = oldReader.getImpacts();
                Impacts newImpacts = newReader.getImpacts();
                Assert.assertEquals(oldImpacts.getDocIdUpTo(0), newImpacts.getDocIdUpTo(0));
                Assert.assertEquals(oldImpacts.getImpacts(0), newImpacts.getImpacts(0));
                for (Impact impact : newImpacts.getImpacts(0)) {
                    Assert.assertEquals(Integer.MAX_VALUE, impact.freq);
                    Assert.assertEquals(1, impact.norm);
                }
            }
        }
        oldReader.close();
        newReader.close();

        d.close();
    }

    public void testWriteThenRead() throws IOException {
        int df = 10 * 128 + 10;
        CompetitiveImpactAccumulator dummyAccum = new CompetitiveImpactAccumulator();

        final Directory d = new ByteBuffersDirectory();
        IndexOutput out = d.createOutput("test.bin", IOContext.DEFAULT);

        Lucene90FlatSkipWriter writer = new Lucene90FlatSkipWriter(1, 128, df , out, null, null);
        writer.setField(false, false, false);
        writer.resetSkip();
        byte[] dummyBytes = new byte[10];
        for (int i = 0; i < 10; i++) {
            int docid = (i + 1) * 1000;
            out.writeBytes(dummyBytes, 10);
            writer.bufferSkip(docid, dummyAccum, 128 * i, 0, 0, 0, 0);
        }
        long skipPtr = writer.writeSkip(out);
        out.close();

        IndexInput in = d.openInput("test.bin", IOContext.READONCE);
        Lucene90FlatSkipReader reader = new Lucene90FlatSkipReader(in, 1, false, false, false);
        reader.init(skipPtr, 0, 0, 0, df);

        int upto = reader.skipTo(100);
        System.out.printf("curr doc upto: %d%n", upto);
        System.out.printf("curr doc ID: %d%n", reader.getDoc());
        System.out.printf("curr doc pointer: %d%n", reader.getDocPointer());
        System.out.printf("next skip doc ID: %d%n", reader.getNextSkipDoc());
        System.out.println();

        upto = reader.skipTo(10001);
        System.out.printf("curr doc upto: %d%n", upto);
        System.out.printf("curr doc ID: %d%n", reader.getDoc());
        System.out.printf("curr doc pointer: %d%n", reader.getDocPointer());
        System.out.printf("next skip doc ID: %d%n", reader.getNextSkipDoc());

        reader.close();

        d.close();
    }
}
