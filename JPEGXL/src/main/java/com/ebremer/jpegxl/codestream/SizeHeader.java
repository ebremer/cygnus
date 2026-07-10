package com.ebremer.jpegxl.codestream;

import com.ebremer.jpegxl.io.BitWriter;
import com.ebremer.jpegxl.io.Bits;
import java.io.IOException;

/** SizeHeader (18181-1 clause 7): the nominal image dimensions. */
public final class SizeHeader {

    public final int width;
    public final int height;

    public SizeHeader(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public static SizeHeader read(Bits in) throws IOException {
        boolean div8 = in.bool();
        int h = div8 ? (in.u(5) + 1) * 8 : in.u32(1, 9, 1, 13, 1, 18, 1, 30);
        int ratio = in.u(3);
        long w = ratio != 0
                ? widthFromRatio(h, ratio)
                : (div8 ? (in.u(5) + 1) * 8 : in.u32(1, 9, 1, 13, 1, 18, 1, 30));
        if (w <= 0 || w > Integer.MAX_VALUE || h <= 0) {
            throw new IOException("bad image dimensions " + w + "x" + h);
        }
        if (w * h > (1L << 40)) {
            throw new IOException("image too large: " + w + "x" + h);
        }
        return new SizeHeader((int) w, h);
    }

    static long widthFromRatio(int h, int ratio) {
        return switch (ratio) {
            case 1 -> h;
            case 2 -> (long) h * 6 / 5;
            case 3 -> (long) h * 4 / 3;
            case 4 -> (long) h * 3 / 2;
            case 5 -> (long) h * 16 / 9;
            case 6 -> (long) h * 5 / 4;
            default -> (long) h * 2;
        };
    }

    /** PreviewHeader (18181-1 clause 7): small preview dimensions. */
    public static SizeHeader readPreview(Bits in) throws IOException {
        boolean div8 = in.bool();
        int h = div8
                ? in.u32(16, 0, 32, 0, 1, 5, 33, 9) * 8
                : in.u32(1, 6, 65, 8, 321, 10, 1345, 12);
        int ratio = in.u(3);
        long w = ratio != 0
                ? widthFromRatio(h, ratio)
                : (div8
                    ? in.u32(16, 0, 32, 0, 1, 5, 33, 9) * 8L
                    : in.u32(1, 6, 65, 8, 321, 10, 1345, 12));
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) {
            throw new IOException("bad preview dimensions " + w + "x" + h);
        }
        return new SizeHeader((int) w, h);
    }

    public void write(BitWriter out) {
        boolean div8 = width == height && width % 8 == 0 && width <= 256;
        out.writeBool(div8);
        if (div8) {
            out.write(height / 8 - 1, 5);
        } else {
            out.writeU32Auto(height, 1, 9, 1, 13, 1, 18, 1, 30);
        }
        if (width == height) {
            out.write(1, 3); // ratio 1:1
        } else {
            out.write(0, 3);
            out.writeU32Auto(width, 1, 9, 1, 13, 1, 18, 1, 30);
        }
    }
}
