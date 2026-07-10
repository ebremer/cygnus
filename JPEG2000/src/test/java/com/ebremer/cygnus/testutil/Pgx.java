package com.ebremer.cygnus.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reader for the PGX raw-image format used by the ISO/IEC 15444-4
 * conformance reference images: an ASCII header
 * {@code PG <ML|LM> [+|-] <depth> <width> <height>} followed by raw
 * samples of ceil(depth/8) bytes each (1, 2 or 4).
 */
public final class Pgx {

    public final int width, height, depth;
    public final boolean signed;
    public final int[] samples;

    private Pgx(int width, int height, int depth, boolean signed, int[] samples) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.signed = signed;
        this.samples = samples;
    }

    public static Pgx read(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        // header: first line, ASCII
        int hdrEnd = 0;
        while (hdrEnd < data.length && data[hdrEnd] != '\n' && data[hdrEnd] != '\r') {
            hdrEnd++;
        }
        String[] tok = new String(data, 0, hdrEnd, java.nio.charset.StandardCharsets.US_ASCII)
                .trim().split("\\s+");
        if (tok.length < 4 || !tok[0].equals("PG")) {
            throw new IOException("Not a PGX file: " + file);
        }
        boolean bigEndian = tok[1].equals("ML");
        int i = 2;
        boolean signed = false;
        String depthTok;
        if (tok[i].equals("+") || tok[i].equals("-")) {
            signed = tok[i].equals("-");
            i++;
            depthTok = tok[i++];
        } else if (tok[i].startsWith("+") || tok[i].startsWith("-")) {
            signed = tok[i].startsWith("-");
            depthTok = tok[i++].substring(1);
        } else {
            depthTok = tok[i++];
        }
        int depth = Integer.parseInt(depthTok);
        int width = Integer.parseInt(tok[i++]);
        int height = Integer.parseInt(tok[i]);

        int bytes = depth <= 8 ? 1 : (depth <= 16 ? 2 : 4);
        int pos = hdrEnd;
        // skip the single terminating newline (possibly \r\n)
        if (pos < data.length && data[pos] == '\r') {
            pos++;
        }
        if (pos < data.length && data[pos] == '\n') {
            pos++;
        }
        int[] samples = new int[width * height];
        for (int s = 0; s < samples.length; s++) {
            int v = 0;
            if (bigEndian) {
                for (int b = 0; b < bytes; b++) {
                    v = (v << 8) | (data[pos++] & 0xFF);
                }
            } else {
                for (int b = 0; b < bytes; b++) {
                    v |= (data[pos++] & 0xFF) << (8 * b);
                }
            }
            if (signed) {
                int shift = 32 - 8 * bytes;
                v = (v << shift) >> shift; // sign-extend from the stored width
            }
            samples[s] = v;
        }
        return new Pgx(width, height, depth, signed, samples);
    }
}
