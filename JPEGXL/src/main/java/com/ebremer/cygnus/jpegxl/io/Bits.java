package com.ebremer.cygnus.jpegxl.io;

import java.io.IOException;

/**
 * LSB-first bit reader over a byte range, as used by the JPEG XL codestream
 * (ISO/IEC 18181-1). Bits are consumed from each byte starting at the least
 * significant bit, like DEFLATE and Brotli.
 */
public final class Bits {

    private final byte[] data;
    private final int end;   // exclusive byte limit
    private final long base; // codestream offset of data[0]
    private int pos;         // next byte to load into the accumulator
    private long acc;        // pending bits, LSB = next bit
    private int nbits;       // number of valid bits in acc

    public Bits(byte[] data) {
        this(data, 0, data.length, 0);
    }

    public Bits(byte[] data, int start, int end) {
        this(data, start, end, 0);
    }

    /** @param base the codestream offset that {@code data[0]} corresponds to */
    public Bits(byte[] data, int start, int end, long base) {
        if (start < 0 || end > data.length || start > end) {
            throw new IllegalArgumentException("bad range " + start + ".." + end);
        }
        this.data = data;
        this.pos = start;
        this.end = end;
        this.base = base;
    }

    private void refill() {
        while (nbits <= 56 && pos < end) {
            acc |= (data[pos++] & 0xffL) << nbits;
            nbits += 8;
        }
    }

    private static final boolean LENIENT = Boolean.getBoolean("jxl.lenient");

    /** Reads {@code n} raw bits (0..32). Fails when past the end of the range. */
    public int u(int n) throws IOException {
        if (n == 0) {
            return 0;
        }
        if (nbits < n) {
            refill();
            if (nbits < n) {
                if (LENIENT) {
                    int ret = (int) (acc & ((1L << n) - 1));
                    acc = 0;
                    nbits = 0;
                    return ret;
                }
                throw new IOException("unexpected end of JPEG XL bitstream");
            }
        }
        int ret = (int) (acc & ((1L << n) - 1));
        acc >>>= n;
        nbits -= n;
        return ret;
    }

    /**
     * Peeks up to {@code n} bits, zero-padding past the end of the range.
     * Used by prefix-code decoding where a final code may be shorter than the
     * lookahead window.
     */
    public int peekZeroPad(int n) {
        if (nbits < n) {
            refill();
        }
        return (int) (acc & ((1L << n) - 1));
    }

    /**
     * Consumes {@code n} bits previously peeked. Consuming more bits than are
     * physically present means the (virtual) zero padding was consumed, which
     * is an error.
     */
    public void consume(int n) throws IOException {
        if (n > nbits) {
            throw new IOException("unexpected end of JPEG XL bitstream (prefix code)");
        }
        acc >>>= n;
        nbits -= n;
    }

    public boolean bool() throws IOException {
        return u(1) != 0;
    }

    /** U32(): a 2-bit selector followed by one of four (offset, bit count) encodings. */
    public int u32(int o0, int n0, int o1, int n1, int o2, int n2, int o3, int n3) throws IOException {
        int sel = u(2);
        return switch (sel) {
            case 0 -> u(n0) + o0;
            case 1 -> u(n1) + o1;
            case 2 -> u(n2) + o2;
            default -> u(n3) + o3;
        };
    }

    /** U64(): variable length up to 64 bits. */
    public long u64() throws IOException {
        int sel = u(2);
        long ret = u(sel * 4);
        if (sel < 3) {
            ret += 17 >> (8 - sel * 4);
        } else {
            for (int shift = 12; shift < 64 && u(1) != 0; shift += 8) {
                ret |= (long) u(shift < 56 ? 8 : 64 - shift) << shift;
            }
        }
        return ret;
    }

    /** Enum(): U32(Val(0), Val(1), BitsOffset(4, 2), BitsOffset(6, 18)), capped at 63. */
    public int enumValue() throws IOException {
        int v = u32(0, 0, 1, 0, 2, 4, 18, 6);
        if (v >= 64) {
            throw new IOException("invalid enum value " + v);
        }
        return v;
    }

    /**
     * F16(): binary16 without infinities or NaNs.
     *
     * <p>A subnormal has no implicit leading one and its exponent is that of the
     * smallest normal, not one less — so the scale is {@code 2^-24} either way,
     * which is what clamping the exponent to at least 1 says. Reading it as
     * {@code 2^-25} halves every subnormal, and this used to.
     */
    public float f16() throws IOException {
        int bits = u(16);
        int biasedExp = (bits >> 10) & 0x1f;
        if (biasedExp == 31) {
            throw new IOException("non-finite F16 value in header");
        }
        int mant = (bits & 0x3ff) | (biasedExp > 0 ? 0x400 : 0);
        float v = (float) Math.scalb((double) mant, Math.max(biasedExp, 1) - 25);
        return (bits >> 15) != 0 ? -v : v;
    }

    /** U8(): as used by ANS distribution decoding. */
    public int u8dist() throws IOException {
        if (u(1) == 0) {
            return 0;
        }
        int n = u(3);
        return u(n) + (1 << n);
    }

    /** Reads a value in [0, max] with ceil(log2(max+1)) bits. */
    public int atMost(int max) throws IOException {
        int v = max > 0 ? u(ceilLog2(max + 1)) : 0;
        if (v > max) {
            throw new IOException("out-of-range value " + v + " > " + max);
        }
        return v;
    }

    /** Skips to the next byte boundary, verifying the padding bits are zero. */
    public void zeroPadToByte() throws IOException {
        int n = nbits & 7;
        if ((acc & ((1L << n) - 1)) != 0) {
            throw new IOException("non-zero padding bits");
        }
        acc >>>= n;
        nbits -= n;
    }

    /** Skips {@code n} bits without checking their contents. */
    public void skip(long n) throws IOException {
        while (n > 0) {
            int chunk = (int) Math.min(n, 32);
            u(chunk);
            n -= chunk;
        }
    }

    /** Absolute byte offset of the current (byte-aligned) position within {@code data}. */
    public int alignedBytePosition() {
        if ((nbits & 7) != 0) {
            throw new IllegalStateException("not byte-aligned");
        }
        return pos - (nbits >> 3);
    }

    /** Codestream offset of the current (byte-aligned) position. */
    public long absolutePosition() {
        return base + alignedBytePosition();
    }

    /** True when every bit up to the end of the range has been consumed. */
    public boolean atEnd() {
        return nbits == 0 && pos >= end;
    }

    /** Verifies the remaining bits of the range are zero padding and the range is exhausted. */
    public void expectEndOfSection() throws IOException {
        if (Boolean.getBoolean("jxl.lenient")) {
            return;
        }
        zeroPadToByte();
        if (!atEnd()) {
            throw new IOException("excess data at end of section (" + ((end - pos) + nbits / 8) + " bytes)");
        }
    }

    public static int unpackSigned(int x) {
        // treats x as unsigned, so 32-bit sample patterns survive intact
        return (x & 1) != 0 ? -(x >>> 1) - 1 : x >>> 1;
    }

    public static long unpackSigned64(long x) {
        return (x & 1) != 0 ? -(x / 2 + 1) : x / 2;
    }

    public static int ceilLog2(int x) {
        return x > 1 ? 32 - Integer.numberOfLeadingZeros(x - 1) : 0;
    }

    public static int floorLog2(int x) {
        return 31 - Integer.numberOfLeadingZeros(x);
    }

    public static int ceilDiv(int x, int y) {
        return (x + y - 1) / y;
    }
}
