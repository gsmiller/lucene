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
        long[] pforUtilBpvHistogram = new long[32];
        long[] pforUtilExceptionsHistogram = new long[8];
        Arrays.fill(pforUtilBpvHistogram, 0L);
        Arrays.fill(pforUtilExceptionsHistogram, 0L);

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
                            for (int i = 0; i < pe.freq(); i++) {
                                pe.nextPosition();  // force all the position/offset/payload data to load if present
                            }
                        }
                        accumulate(pe.getPForUtilBpvHistogram(), pforUtilBpvHistogram);
                        accumulate(pe.getPForUtilExceptionsHistogram(), pforUtilExceptionsHistogram);
                    }
                }
            }
        }

        System.out.println("PFOR BPV Histogram");
        printHistogram(pforUtilBpvHistogram);
        printBytesUsed(pforUtilBpvHistogram, pforUtilExceptionsHistogram);
        System.out.println("PFOR Exceptions Used Histogram");
        printHistogram(pforUtilExceptionsHistogram);
    }

    private static void accumulate(long[] source, long[] dest) {
        assert dest.length >= source.length;
        for (int i = 0; i < source.length; i++) {
            dest[i] += source[i];
        }
    }

    private static void printHistogram(long[] histogram) {
        long total = 0;
        for (int i = 0; i < histogram.length; i++) {
            total += histogram[i];
        }
        for (int i = 0; i < histogram.length; i++) {
            double pct = (double) histogram[i] / (double) total * 100D;
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < pct / 2D; j++) {
                sb.append('*');
            }
            String msg = String.format("%2d %-50s  [%2.2f %%] (%d of %d)", i, sb.toString(), pct, histogram[i], total);
            System.out.println(msg);
        }
    }

    private static void printBytesUsed(long[] bpvHistogram, long[] exceptionsHistogram) {
        long totalBits = 0;
        for (int i = 0; i < bpvHistogram.length; i++) {
            if (i == 0) {
                totalBits += bpvHistogram[i] * 8; // only use 1 byte for 0 bpv
            } else {
                totalBits += i * 128 * bpvHistogram[i];
            }
        }
        for (int i = 0; i < exceptionsHistogram.length; i++) {
            totalBits += i * 8 * exceptionsHistogram[i]; // 8 bits per exception entry
        }
        System.out.println("Total bytes used: " + totalBits / 8L);
    }
}
