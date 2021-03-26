package org.apache.lucene.codecs;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class BitsPerValueTool {

    public static void main(String[] args) throws Exception {
        long[] docBpvHistogram = new long[32];
        Arrays.fill(docBpvHistogram, 0L);

        Path indexPath = Paths.get(args[0]);
        try (IndexReader reader = DirectoryReader.open(FSDirectory.open(indexPath))) {
            for (LeafReaderContext ctx : reader.leaves()) {
                LeafReader leafReader = ctx.reader();
                for (FieldInfo fieldInfo : leafReader.getFieldInfos()) {
                    Terms terms = leafReader.terms(fieldInfo.name);
                    if (terms == null) {
                        continue;
                    }
                    TermsEnum te = terms.iterator();
                    while (te.next() != null) {
                        PostingsEnum pe = te.postings(null, PostingsEnum.NONE);
                        while (pe.nextDoc() != Integer.MAX_VALUE) {
                            // just iterate to populate the underlying histogram
                        }
                        for (int i = 0; i < pe.getDocIdBpvHistogram().length; i++) {
                            docBpvHistogram[i] += pe.getDocIdBpvHistogram()[i];
                        }
                    }
                }
            }
        }

        System.out.println("DOC ID BPV");
        long totalBlocks = 0;
        long totalBits = 0;
        for (int i = 0; i < docBpvHistogram.length; i++) {
            totalBlocks += docBpvHistogram[i];
            if (i > 0) {
                totalBits += i * 128 * docBpvHistogram[i];
            }
        }
        for (int i = 0; i < docBpvHistogram.length; i++) {
            double pct = (double) docBpvHistogram[i] / (double) totalBlocks * 100D;
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < pct / 2D; j++) {
                sb.append('*');
            }
            String msg = String.format("%2d %-50s  [%2.2f pct] (%d of %d)", i, sb.toString(), pct, docBpvHistogram[i], totalBlocks);
            System.out.println(msg);
        }
        System.out.println("Total bytes used: " + totalBits / 8L);
    }
}
