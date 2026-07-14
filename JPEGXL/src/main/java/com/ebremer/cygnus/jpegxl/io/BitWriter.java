package com.ebremer.cygnus.jpegxl.io;

import java.util.Arrays;

/** LSB-first bit writer, the encoding counterpart of {@link Bits}. */
public final class BitWriter {

    private byte[] buf = new byte[1 << 12];
    private int bytePos;
    private long acc;
    private int nbits;

    public void write(int value, int n) {
        if (n == 0) {
            return;
        }
        if (n < 0 || n > 32) {
            throw new IllegalArgumentException("bad bit count " + n);
        }
        long v = value & ((n == 32) ? 0xffffffffL : ((1L << n) - 1));
        acc |= v << nbits;
        nbits += n;
        while (nbits >= 8) {
            ensure(1);
            buf[bytePos++] = (byte) acc;
            acc >>>= 8;
            nbits -= 8;
        }
    }

    /** U32 with an explicit selector; the caller picks the branch. */
    public void writeU32(int sel, int value, int o0, int n0, int o1, int n1, int o2, int n2, int o3, int n3) {
        int[] o = {o0, o1, o2, o3};
        int[] n = {n0, n1, n2, n3};
        write(sel, 2);
        write(value - o[sel], n[sel]);
    }

    /** U32 choosing the smallest branch that can represent the value. */
    public void writeU32Auto(int value, int o0, int n0, int o1, int n1, int o2, int n2, int o3, int n3) {
        int[] o = {o0, o1, o2, o3};
        int[] n = {n0, n1, n2, n3};
        for (int sel = 0; sel < 4; sel++) {
            long lo = o[sel];
            long hi = o[sel] + (1L << n[sel]) - 1;
            if (value >= lo && value <= hi) {
                write(sel, 2);
                write(value - o[sel], n[sel]);
                return;
            }
        }
        throw new IllegalArgumentException("value " + value + " not representable");
    }

    public void writeU64(long value) {
        if (value == 0) {
            write(0, 2);
        } else if (value >= 1 && value <= 16) {
            write(1, 2);
            write((int) (value - 1), 4);
        } else if (value >= 17 && value <= 272) {
            write(2, 2);
            write((int) (value - 17), 8);
        } else {
            write(3, 2);
            write((int) (value & 0xfff), 12);
            value >>>= 12;
            int shift = 12;
            while (value != 0) {
                write(1, 1);
                int chunk = shift < 56 ? 8 : 64 - shift;
                write((int) (value & ((1L << chunk) - 1)), chunk);
                value >>>= chunk;
                shift += 8;
            }
            if (shift < 64) {
                write(0, 1);
            }
        }
    }

    /**
     * F16(): binary16, read back by {@code Bits.f16}.
     *
     * <p>Rounded to the nearest half, because half is all the format has: these
     * fields <em>are</em> f16, so a spot colour of 0.9 is stored as 0.89990234
     * by anyone who writes one, and refusing it would only mean refusing almost
     * every colour anybody names. What is refused is a value half cannot reach at
     * all — beyond 65504, or already infinite or NaN — since the reader rejects
     * that encoding outright and the file would not be readable.
     */
    public void writeF16(float value) {
        short half = Float.floatToFloat16(value);
        int bits = half & 0xffff;
        if (((bits >> 10) & 0x1f) == 31) {
            throw new IllegalArgumentException("F16 cannot hold " + value);
        }
        write(bits, 16);
    }

    /** What {@link #writeF16} would store for {@code value}. */
    public static float roundF16(float value) {
        return Float.float16ToFloat(Float.floatToFloat16(value));
    }

    public void writeEnum(int value) {
        if (value == 0) {
            write(0, 2);
        } else if (value == 1) {
            write(1, 2);
        } else if (value >= 2 && value < 18) {
            write(2, 2);
            write(value - 2, 4);
        } else {
            write(3, 2);
            write(value - 18, 6);
        }
    }

    public void writeBool(boolean b) {
        write(b ? 1 : 0, 1);
    }

    public void zeroPadToByte() {
        if (nbits > 0) {
            ensure(1);
            buf[bytePos++] = (byte) acc;
            acc = 0;
            nbits = 0;
        }
    }

    /**
     * Appends another writer's bits, whatever bit offset either sits at. Lets a
     * caller build two candidate encodings, measure them, and splice in the
     * shorter.
     */
    public void writeBits(BitWriter other) {
        for (int i = 0; i < other.bytePos; i++) {
            write(other.buf[i] & 0xff, 8);
        }
        if (other.nbits > 0) {
            write((int) (other.acc & ((1L << other.nbits) - 1)), other.nbits);
        }
    }

    /** Appends whole bytes; the writer must be byte-aligned. */
    public void writeBytes(byte[] bytes) {
        if (nbits != 0) {
            throw new IllegalStateException("not byte-aligned");
        }
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, bytePos, bytes.length);
        bytePos += bytes.length;
    }

    public int bitLength() {
        return bytePos * 8 + nbits;
    }

    public byte[] toByteArray() {
        if (nbits != 0) {
            throw new IllegalStateException("not byte-aligned; call zeroPadToByte() first");
        }
        return Arrays.copyOf(buf, bytePos);
    }

    private void ensure(int extra) {
        if (bytePos + extra > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, bytePos + extra));
        }
    }
}
