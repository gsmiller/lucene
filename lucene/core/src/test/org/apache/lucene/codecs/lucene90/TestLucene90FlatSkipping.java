package org.apache.lucene.codecs.lucene90;

import org.apache.lucene.codecs.CompetitiveImpactAccumulator;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.LuceneTestCase;

import java.io.IOException;

public class TestLucene90FlatSkipping extends LuceneTestCase {

    public void testWriteThenRead() throws Exception {
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
