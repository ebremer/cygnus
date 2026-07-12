package com.ebremer.cygnus.jpegxl.entropy;

import com.ebremer.cygnus.jpegxl.io.Bits;
import java.io.IOException;

/**
 * Hybrid integer configuration: tokens below {@code 1 << splitExp} are literal
 * values, larger tokens carry the value's magnitude class plus a few explicit
 * MSB/LSB bits, with the middle bits read raw from the bitstream.
 */
public final class HybridUintConfig {

    public final int splitExp;
    public final int msbInToken;
    public final int lsbInToken;
    public final int maxToken;

    public HybridUintConfig(int splitExp, int msbInToken, int lsbInToken) {
        this.splitExp = splitExp;
        this.msbInToken = msbInToken;
        this.lsbInToken = lsbInToken;
        // values are 32-bit bit patterns at most (e.g. float samples)
        this.maxToken = (1 << splitExp) + ((32 - splitExp) << (lsbInToken + msbInToken)) - 1;
    }

    public static HybridUintConfig read(Bits in, int logAlphaSize) throws IOException {
        int splitExp = in.atMost(logAlphaSize);
        int msb = 0;
        int lsb = 0;
        if (splitExp != logAlphaSize) {
            msb = in.atMost(splitExp);
            lsb = in.atMost(splitExp - msb);
        }
        return new HybridUintConfig(splitExp, msb, lsb);
    }

    /** Expands a token into the coded value, reading the middle bits from {@code in}. */
    public int decode(Bits in, int token) throws IOException {
        int split = 1 << splitExp;
        if (token < split) {
            return token;
        }
        if (token > maxToken) {
            throw new IOException("hybrid integer overflow (token " + token + ")");
        }
        int bitsInToken = msbInToken + lsbInToken;
        int midBits = splitExp - bitsInToken + ((token - split) >> bitsInToken);
        if (midBits > 32) {
            throw new IOException("hybrid integer too wide");
        }
        int mid = midBits == 32 ? (int) (in.u(16) | ((long) in.u(16) << 16)) : in.u(midBits);
        int top = 1 << msbInToken;
        int lo = token & ((1 << lsbInToken) - 1);
        int hi = (token >> lsbInToken) & (top - 1);
        return ((top | hi) << (midBits + lsbInToken)) | ((mid << lsbInToken) | lo);
    }

    @Override
    public String toString() {
        return "(" + splitExp + "," + msbInToken + "," + lsbInToken + ")";
    }

    /**
     * Splits a value (treated as unsigned 32-bit) into token and raw middle
     * bits for encoding. Returns {@code token | (long) midBitCount << 32};
     * the raw bits themselves are stored in {@code midOut[0]}.
     */
    public long encode(int value, int[] midOut) {
        long u = value & 0xFFFFFFFFL;
        int split = 1 << splitExp;
        if (u < split) {
            midOut[0] = (int) 0;
            return u;
        }
        int n = 63 - Long.numberOfLeadingZeros(u); // MSB position
        int midBits = n - msbInToken - lsbInToken;
        int lo = (int) (u & ((1 << lsbInToken) - 1));
        int hi = (int) ((u >>> (midBits + lsbInToken)) & ((1 << msbInToken) - 1));
        int mid = (int) ((u >>> lsbInToken) & ((1L << midBits) - 1));
        int token = split + ((((midBits - (splitExp - msbInToken - lsbInToken)) << msbInToken)
                | hi) << lsbInToken | lo);
        midOut[0] = mid;
        return token | ((long) midBits << 32);
    }
}
