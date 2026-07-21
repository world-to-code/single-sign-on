package com.example.sso.metadata.internal.application;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;

/**
 * Swallows a UTF-8 byte-order mark if the stream opens with one.
 *
 * <p>Spreadsheet exports routinely lead with a BOM. Left in place it becomes part of the FIRST header name,
 * so that column silently maps to nothing while every other column works — which reads as "the export is
 * broken" rather than "the file has three invisible bytes at the front".
 */
final class BomAwareStream extends FilterInputStream {

    private static final int[] UTF8_BOM = {0xEF, 0xBB, 0xBF};

    BomAwareStream(InputStream in) throws IOException {
        super(skipBom(new PushbackInputStream(in, UTF8_BOM.length)));
    }

    private static InputStream skipBom(PushbackInputStream in) throws IOException {
        byte[] first = new byte[UTF8_BOM.length];
        int read = in.read(first);
        if (read < 0) {
            return in;
        }
        boolean bom = read == UTF8_BOM.length;
        for (int i = 0; bom && i < UTF8_BOM.length; i++) {
            bom = (first[i] & 0xFF) == UTF8_BOM[i];
        }
        if (!bom) {
            in.unread(first, 0, read);
        }
        return in;
    }
}
