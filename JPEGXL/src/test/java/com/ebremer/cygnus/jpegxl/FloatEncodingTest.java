package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.jpegxl.codestream.BitDepth;
import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.encoder.JxlEncoder;
import java.io.IOException;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Floating-point sample encoding. A float is a sign, an exponent and a mantissa
 * laid end to end, and that is how the format carries one: the modular coder
 * codes the bit pattern as an integer and the decoder reads the bits back as a
 * float. So the encoder's whole job is to lay the float out the way
 * {@link BitDepth#sampleToFloat} will read it — and to be exact about it,
 * negative zero and NaN included.
 */
class FloatEncodingTest {

    /** Every float layout the format allows, up to where exhaustive testing is cheap. */
    static Stream<Arguments> layouts() {
        Stream.Builder<Arguments> b = Stream.builder();
        for (int bpp = 8; bpp <= 16; bpp++) {
            for (int exp = 2; exp <= 8; exp++) {
                int mant = bpp - exp - 1;
                if (mant >= 2 && mant <= 23) {
                    b.add(Arguments.of(bpp, exp));
                }
            }
        }
        return b.build();
    }

    /**
     * Every value a narrow layout can hold, packed and read back. This is the
     * whole correctness argument for the encoder: the decoder's side of the
     * reinterpretation is checked against the official reference by the
     * conformance corpus (which carries a float image, {@code lossless_pfm}),
     * and this checks that packing is its exact inverse. What the two of them
     * close over is every float the format can carry.
     */
    @ParameterizedTest(name = "{0} bits, {1} exponent")
    @MethodSource("layouts")
    void packingIsTheExactInverseOfUnpacking(int bpp, int expBits) {
        BitDepth d = BitDepth.ofFloat(bpp, expBits);
        int subnormals = 0;
        int infinities = 0;
        for (int v = 0; v < (1 << bpp); v++) {
            float f = d.sampleToFloat(v);
            if (Float.isNaN(f)) {
                continue;   // a narrow layout cannot carry a NaN's payload
            }
            if (Float.isInfinite(f)) {
                infinities++;
            }
            int mantBits = bpp - expBits - 1;
            if (((v >>> mantBits) & ((1 << expBits) - 1)) == 0 && (v & ((1 << mantBits) - 1)) != 0) {
                subnormals++;
            }
            assertEquals(Float.floatToRawIntBits(f),
                    Float.floatToRawIntBits(d.sampleToFloat(d.floatToSample(f))),
                    "sample " + v + " (" + f + ") did not survive");
        }
        assertTrue(subnormals > 0, "the subnormals should have been exercised");
        assertEquals(2, infinities, "both infinities should have been exercised");
    }

    /** float32 is what a Java float already is, so it holds anything, bit for bit. */
    @Test
    void float32HoldsEveryFloatIncludingTheStrangeOnes() {
        BitDepth d = BitDepth.float32();
        for (float f : new float[] {
            0f, -0f, 1f, -1f, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MIN_NORMAL,
            Float.MAX_VALUE, -Float.MAX_VALUE, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NaN, Float.intBitsToFloat(0x7f800001),
            Float.intBitsToFloat(0xffc00000), 1e-40f, 3.14159265f}) {
            assertEquals(Float.floatToRawIntBits(f),
                    Float.floatToRawIntBits(d.sampleToFloat(d.floatToSample(f))), "" + f);
        }
    }

    /**
     * A narrower layout refuses what it cannot hold. Nothing rounds: a lossless
     * encoder handed a sample it cannot represent has been given the wrong depth,
     * and that is worth saying.
     */
    @Test
    void aNarrowLayoutRefusesWhatItCannotHold() {
        BitDepth half = BitDepth.float16();
        for (float ok : new float[] {0f, -0f, 1f, 0.5f, 65504f, Float.POSITIVE_INFINITY}) {
            half.floatToSample(ok);   // must not throw
        }
        for (float no : new float[] {65536f, 1e-8f, 3.14159265f, Float.MAX_VALUE,
            Float.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> half.floatToSample(no),
                    no + " should not fit in a half");
        }
    }

    static Stream<Arguments> shapes() {
        return Stream.of(
                Arguments.of(64, 64, 1, false),
                Arguments.of(64, 64, 3, false),
                Arguments.of(300, 200, 3, false),
                Arguments.of(300, 200, 4, true),      // alpha, also float
                Arguments.of(512, 512, 3, false),     // several groups
                Arguments.of(1, 50, 3, false),
                Arguments.of(17, 33, 3, false));
    }

    /**
     * The awkward samples, laid out as an image and put through the codestream.
     * Negative zero and NaN are the ones that catch a lazy comparison, so this
     * compares bit patterns, not values.
     */
    @ParameterizedTest(name = "{0}x{1}, {2} planes")
    @MethodSource("shapes")
    void float32RoundTripsBitExactly(int w, int h, int planes, boolean alpha) throws IOException {
        float[][] px = awkward(w, h, planes);
        byte[] jxl = JxlEncoder.encodeFloat(px, w, h, planes == 1, alpha, false);
        JxlImage image = JxlDecoder.decode(jxl);
        assertEquals(w, image.width);
        assertEquals(h, image.height);
        for (int c = 0; c < planes; c++) {
            float[] out = image.frames.get(0).floatChannels[c];
            assertBitEqual(px[c], out, "plane " + c);
        }
    }

    /** Half-precision data, which is what a half layout is for. */
    @Test
    void float16RoundTripsBitExactly() throws IOException {
        int w = 200;
        int h = 150;
        float[][] px = onTheHalfGrid(w, h, 3);
        byte[] jxl = JxlEncoder.encodeFloat(px, w, h, BitDepth.float16(), false, false, false);
        JxlImage image = JxlDecoder.decode(jxl);
        for (int c = 0; c < 3; c++) {
            assertBitEqual(px[c], image.frames.get(0).floatChannels[c], "plane " + c);
        }
    }

    /**
     * An image of few distinct values takes the palette path, which folds the
     * samples into a lookup and codes indices. The samples there are bit
     * patterns, and the palette must hand them back unchanged.
     */
    @Test
    void aFloat16ImageOfFewValuesStillRoundTrips() throws IOException {
        int w = 200;
        int h = 150;
        Random r = new Random(3);
        float[] palette = new float[12];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = BitDepth.float16().sampleToFloat(r.nextInt(1 << 16) & 0x7bff);
        }
        float[][] px = new float[3][w * h];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                px[c][i] = palette[(i / 7 + c) % palette.length];
            }
        }
        byte[] jxl = JxlEncoder.encodeFloat(px, w, h, BitDepth.float16(), false, false, false);
        JxlImage image = JxlDecoder.decode(jxl);
        for (int c = 0; c < 3; c++) {
            assertBitEqual(px[c], image.frames.get(0).floatChannels[c], "plane " + c);
        }
    }

    /**
     * The samples of libjxl's own float image, re-encoded by us. It is the one
     * float image we have that somebody else made, and it must survive.
     */
    @Test
    void libjxlsOwnFloatSamplesSurviveOurEncoder() throws Exception {
        java.nio.file.Path input =
                java.nio.file.Path.of("test-data", "conformance", "lossless_pfm", "input.jxl");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(input),
                "conformance corpus not fetched");
        JxlImage theirs = JxlDecoder.decode(java.nio.file.Files.readAllBytes(input));
        int w = theirs.width;
        int h = theirs.height;
        float[][] px = {theirs.frames.get(0).floatChannels[0],
            theirs.frames.get(0).floatChannels[1], theirs.frames.get(0).floatChannels[2]};
        byte[] jxl = JxlEncoder.encodeFloat(px, w, h, false, false, false);
        JxlImage ours = JxlDecoder.decode(jxl);
        for (int c = 0; c < 3; c++) {
            assertBitEqual(px[c], ours.frames.get(0).floatChannels[c], "plane " + c);
        }
    }

    @Test
    void rejectsWrongUsage() {
        float[][] px = new float[3][100];
        assertThrows(IllegalArgumentException.class,
                () -> JxlEncoder.encodeFloat(px, 10, 10, BitDepth.of(8), false, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> JxlEncoder.encodeFloat(px, 10, 10, true, false, false));   // 3 planes, grey
        assertThrows(IllegalArgumentException.class, () -> BitDepth.ofFloat(16, 14));
        assertThrows(IllegalStateException.class, () -> BitDepth.of(8).floatToSample(1f));
    }

    // ------------------------------------------------------------------ helpers

    private static void assertBitEqual(float[] want, float[] got, String what) {
        assertTrue(got != null, what + ": decoded as integers, not floats");
        assertEquals(want.length, got.length, what);
        for (int i = 0; i < want.length; i++) {
            assertEquals(Float.floatToRawIntBits(want[i]), Float.floatToRawIntBits(got[i]),
                    what + " sample " + i);
        }
    }

    /** Zeros of both signs, subnormals, infinities, NaNs, and a spread of magnitudes. */
    private static float[][] awkward(int w, int h, int planes) {
        float[] special = {
            0f, -0f, 1f, -1f, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MIN_NORMAL,
            Float.MAX_VALUE, -Float.MAX_VALUE, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NaN, Float.intBitsToFloat(0x7f800001),
            Float.intBitsToFloat(0xffc00000), 1e-40f, -1e-40f, 3.14159265f, -2.71828f,
        };
        Random r = new Random(9);
        float[][] px = new float[planes][w * h];
        for (int c = 0; c < planes; c++) {
            for (int i = 0; i < w * h; i++) {
                px[c][i] = i < special.length ? special[i]
                        : r.nextInt(3) == 0 ? Float.intBitsToFloat(r.nextInt())
                        : (float) (r.nextGaussian() * Math.pow(10, r.nextInt(20) - 10));
            }
        }
        return px;
    }

    /** A smooth field rounded onto the half grid, so a half layout can hold it. */
    private static float[][] onTheHalfGrid(int w, int h, int planes) {
        BitDepth half = BitDepth.float16();
        float[][] px = new float[planes][w * h];
        Random r = new Random(4);
        for (int c = 0; c < planes; c++) {
            for (int i = 0; i < w * h; i++) {
                // every half bit pattern that is not a NaN is a value a half can hold
                int v = r.nextInt(1 << 16);
                float f = half.sampleToFloat(v);
                px[c][i] = Float.isNaN(f) ? 0f : f;
            }
        }
        return px;
    }
}
