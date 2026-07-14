package com.ebremer.cygnus.jpegxl.codestream;

import com.ebremer.cygnus.jpegxl.io.BitWriter;
import com.ebremer.cygnus.jpegxl.io.Bits;
import java.io.IOException;

/** BitDepth bundle: integer or floating point sample encoding. */
public final class BitDepth {

    public final boolean floatingPoint;
    public final int bitsPerSample;
    public final int expBits;

    public BitDepth(boolean floatingPoint, int bitsPerSample, int expBits) {
        this.floatingPoint = floatingPoint;
        this.bitsPerSample = bitsPerSample;
        this.expBits = expBits;
    }

    public static final BitDepth INT8 = new BitDepth(false, 8, 0);
    public static final BitDepth INT16 = new BitDepth(false, 16, 0);

    public static BitDepth of(int bits) {
        return new BitDepth(false, bits, 0);
    }

    /** IEEE binary32: the only float depth that can hold any float exactly. */
    public static BitDepth float32() {
        return new BitDepth(true, 32, 8);
    }

    /** IEEE binary16 (half). */
    public static BitDepth float16() {
        return new BitDepth(true, 16, 5);
    }

    /**
     * A custom float layout: one sign bit, {@code expBits} of exponent, the rest
     * mantissa. The format allows 2 to 8 exponent bits and 2 to 23 of mantissa.
     */
    public static BitDepth ofFloat(int bitsPerSample, int expBits) {
        int mantissa = bitsPerSample - expBits - 1;
        if (mantissa < 2 || mantissa > 23 || expBits < 2 || expBits > 8) {
            throw new IllegalArgumentException("no such float layout: " + bitsPerSample
                    + " bits with " + expBits + " of exponent leaves " + mantissa
                    + " of mantissa (2 to 23 needed, and 2 to 8 exponent bits)");
        }
        return new BitDepth(true, bitsPerSample, expBits);
    }

    public static BitDepth read(Bits in) throws IOException {
        if (in.bool()) {
            int bpp = in.u32(32, 0, 16, 0, 24, 0, 1, 6);
            int exp = in.u(4) + 1;
            int mantissa = bpp - exp - 1;
            if (mantissa < 2 || mantissa > 23 || exp < 2 || exp > 8) {
                throw new IOException("bad float sample format bpp=" + bpp + " exp=" + exp);
            }
            return new BitDepth(true, bpp, exp);
        }
        int bpp = in.u32(8, 0, 10, 0, 12, 0, 1, 6);
        if (bpp < 1 || bpp > 31) {
            throw new IOException("bad bits per sample " + bpp);
        }
        return new BitDepth(false, bpp, 0);
    }

    /**
     * Reinterprets a modular sample as a floating-point value: the integer
     * holds the bits of a custom float with this depth's exponent size.
     */
    public float sampleToFloat(int v) {
        if (bitsPerSample == 32 && expBits == 8) {
            return Float.intBitsToFloat(v);
        }
        int mantBits = bitsPerSample - expBits - 1;
        int sign = (v >>> (bitsPerSample - 1)) & 1;
        int biasedExp = (v >>> mantBits) & ((1 << expBits) - 1);
        int mant = v & ((1 << mantBits) - 1);
        int bias = (1 << (expBits - 1)) - 1;
        float value;
        if (biasedExp == (1 << expBits) - 1) {
            value = mant == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        } else if (biasedExp == 0) {
            value = (float) Math.scalb((double) mant, 1 - bias - mantBits);
        } else {
            value = (float) Math.scalb(1.0 + (double) mant / (1 << mantBits), biasedExp - bias);
        }
        return sign != 0 ? -value : value;
    }

    /**
     * The modular sample whose bits {@link #sampleToFloat} reads back as
     * {@code f} — the encoder's side of the same reinterpretation.
     *
     * <p>Throws when this depth cannot hold the value exactly. Nothing here
     * rounds: a lossless encoder handed a sample it cannot represent has been
     * given the wrong depth, and that is worth saying rather than quietly
     * approximating. Only {@link #float32()} can take any float at all, since
     * only it is what a {@code float} already is; anything narrower is a
     * promise by the caller that the samples fit.
     */
    public int floatToSample(float f) {
        if (!floatingPoint) {
            throw new IllegalStateException("not a floating-point depth");
        }
        if (bitsPerSample == 32 && expBits == 8) {
            return Float.floatToRawIntBits(f);   // it is already this
        }
        int v = pack(f);
        if (Float.floatToRawIntBits(sampleToFloat(v)) != Float.floatToRawIntBits(f)) {
            throw new IllegalArgumentException(f + " does not fit exactly in a " + bitsPerSample
                    + "-bit float with " + expBits + " exponent bits");
        }
        return v;
    }

    /**
     * Lays a float out in this depth's field widths. The result is only right
     * if the value fits; {@link #floatToSample} is what checks that, by reading
     * it back.
     */
    private int pack(float f) {
        int mantBits = bitsPerSample - expBits - 1;
        int bias = (1 << (expBits - 1)) - 1;
        int maxExp = (1 << expBits) - 1;
        int raw = Float.floatToRawIntBits(f);
        int sign = raw >>> 31;
        int rawExp = (raw >>> 23) & 0xff;
        int rawMant = raw & 0x7fffff;

        if (rawExp == 0xff) {  // infinity, or a NaN whose payload will not survive
            return lay(sign, maxExp, rawMant == 0 ? 0 : 1 << (mantBits - 1));
        }
        if (rawExp == 0 && rawMant == 0) {
            return lay(sign, 0, 0);   // zero, keeping its sign
        }

        // normalise to mant * 2^(exp - 23), with mant in [2^23, 2^24)
        int mant;
        int exp;
        if (rawExp == 0) {   // subnormal as a float32; may still be normal here
            int shift = Integer.numberOfLeadingZeros(rawMant) - 8;
            mant = rawMant << shift;
            exp = -126 - shift;
        } else {
            mant = rawMant | 0x800000;
            exp = rawExp - 127;
        }

        int biased = exp + bias;
        if (biased >= maxExp) {
            return lay(sign, maxExp, 0);   // out of range: infinity, and not exact
        }
        if (biased > 0) {
            return lay(sign, biased, (mant & 0x7fffff) >>> (23 - mantBits));
        }
        // subnormal at this depth: the value is mant * 2^(1 - bias - mantBits)
        int shift = 24 - mantBits - biased;
        return lay(sign, 0, shift < 32 ? mant >>> shift : 0);
    }

    private int lay(int sign, int biasedExp, int mantissa) {
        int mantBits = bitsPerSample - expBits - 1;
        return (sign << (bitsPerSample - 1)) | (biasedExp << mantBits) | mantissa;
    }

    public void write(BitWriter out) {
        out.writeBool(floatingPoint);
        if (floatingPoint) {
            out.writeU32Auto(bitsPerSample, 32, 0, 16, 0, 24, 0, 1, 6);
            out.write(expBits - 1, 4);
        } else {
            switch (bitsPerSample) {
                case 8 -> out.write(0, 2);
                case 10 -> out.write(1, 2);
                case 12 -> out.write(2, 2);
                default -> {
                    out.write(3, 2);
                    out.write(bitsPerSample - 1, 6);
                }
            }
        }
    }
}
