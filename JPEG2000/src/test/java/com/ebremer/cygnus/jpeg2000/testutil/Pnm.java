package com.ebremer.cygnus.jpeg2000.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reader for binary PGM (P5) and PPM (P6) images, 8- or 16-bit. */
public final class Pnm {

    public final int width, height, comps, maxVal;
    /** Per-component planes, row-major. */
    public final int[][] samples;

    private Pnm(int width, int height, int comps, int maxVal, int[][] samples) {
        this.width = width;
        this.height = height;
        this.comps = comps;
        this.maxVal = maxVal;
        this.samples = samples;
    }

    public static Pnm read(Path file) throws IOException {
        byte[] d = Files.readAllBytes(file);
        int[] pos = {0};
        String magic = token(d, pos);
        int comps = switch (magic) {
            case "P5" -> 1;
            case "P6" -> 3;
            default -> throw new IOException("Unsupported PNM type " + magic);
        };
        int width = Integer.parseInt(token(d, pos));
        int height = Integer.parseInt(token(d, pos));
        int maxVal = Integer.parseInt(token(d, pos));
        pos[0]++; // single whitespace after maxval
        int bytes = maxVal > 255 ? 2 : 1;
        int[][] out = new int[comps][width * height];
        int p = pos[0];
        for (int i = 0; i < width * height; i++) {
            for (int c = 0; c < comps; c++) {
                int v = d[p++] & 0xFF;
                if (bytes == 2) {
                    v = (v << 8) | (d[p++] & 0xFF); // big-endian per PNM spec
                }
                out[c][i] = v;
            }
        }
        return new Pnm(width, height, comps, maxVal, out);
    }

    private static String token(byte[] d, int[] pos) {
        int p = pos[0];
        while (p < d.length && (Character.isWhitespace(d[p]) || d[p] == '#')) {
            if (d[p] == '#') {
                while (p < d.length && d[p] != '\n') {
                    p++;
                }
            }
            p++;
        }
        int start = p;
        while (p < d.length && !Character.isWhitespace(d[p])) {
            p++;
        }
        pos[0] = p;
        return new String(d, start, p - start, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
