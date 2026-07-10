package com.ebremer.jpegxl.codestream;

import com.ebremer.jpegxl.io.BitWriter;
import com.ebremer.jpegxl.io.Bits;
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
