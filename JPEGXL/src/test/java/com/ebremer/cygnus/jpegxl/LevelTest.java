package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.jpegxl.codestream.BitDepth;
import com.ebremer.cygnus.jpegxl.codestream.CodestreamLevel;
import com.ebremer.cygnus.jpegxl.codestream.ColourEncoding;
import com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo;
import com.ebremer.cygnus.jpegxl.codestream.ImageMetadata;
import com.ebremer.cygnus.jpegxl.container.Container;
import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.encoder.JxlEncoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Codestream level: the {@code jxll} declaration, its computation, and its enforcement. */
class LevelTest {

    private static int[][] gradient(int w, int h, int channels, int max) {
        int[][] p = new int[channels][w * h];
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < w * h; i++) {
                p[c][i] = (i * 7 + c * 101) % (max + 1);
            }
        }
        return p;
    }

    private static ImageMetadata rgb8() {
        ImageMetadata m = new ImageMetadata();
        m.bitDepth = BitDepth.of(8);
        m.colourEncoding = new ColourEncoding();
        return m;
    }

    // ---- the level model, checked at boundaries real encoding cannot reach

    @Test
    void baselineImageIsLevel5() {
        assertEquals(5, CodestreamLevel.required(rgb8(), 4096, 4096));
    }

    @Test
    void dimensionBoundary() {
        ImageMetadata m = rgb8();
        // a strip one sample under the level-5 limit is level 5; one over needs 10
        assertEquals(5, CodestreamLevel.required(m, 1 << 18, 1));
        assertEquals(10, CodestreamLevel.required(m, (1 << 18) + 1, 1));
        // and the check is symmetric in the two axes
        assertEquals(10, CodestreamLevel.required(m, 1, (1 << 18) + 1));
    }

    @Test
    void pixelCountBoundary() {
        ImageMetadata m = rgb8();
        // 2^14 x 2^14 = 2^28 exactly, the level-5 ceiling; one row more spills over
        assertEquals(5, CodestreamLevel.required(m, 1 << 14, 1 << 14));
        assertEquals(10, CodestreamLevel.required(m, 1 << 14, (1 << 14) + 1));
    }

    @Test
    void beyondLevel10IsInvalid() {
        // over the level-10 dimension ceiling: no level can carry it
        assertEquals(CodestreamLevel.INVALID,
                CodestreamLevel.required(rgb8(), (1L << 30) + 1, 1));
    }

    @Test
    void sixteenBitIsStillLevel5() {
        // the spec puts the level-5 ceiling at 16 bits; libjxl's encoder is more
        // conservative (it declares 10 above 12), but that is its buffer choice,
        // not the level's limit, and a decoder uses the real one
        ImageMetadata m = rgb8();
        m.bitDepth = BitDepth.of(16);
        assertEquals(5, CodestreamLevel.required(m, 512, 512));
        m.bitDepth = BitDepth.of(17);
        assertEquals(10, CodestreamLevel.required(m, 512, 512));
    }

    @Test
    void tooManyExtraChannelsNeedsLevel10() {
        ImageMetadata m = rgb8();
        for (int i = 0; i < 4; i++) {
            m.extraChannels.add(ExtraChannelInfo.of(ExtraChannelInfo.TYPE_OPTIONAL,
                    BitDepth.of(8), "e" + i));
        }
        assertEquals(5, CodestreamLevel.required(m, 64, 64));
        m.extraChannels.add(ExtraChannelInfo.of(ExtraChannelInfo.TYPE_OPTIONAL,
                BitDepth.of(8), "fifth"));
        assertEquals(10, CodestreamLevel.required(m, 64, 64));
    }

    @Test
    void cmykBlackChannelNeedsLevel10() {
        ImageMetadata m = rgb8();
        m.extraChannels.add(ExtraChannelInfo.of(ExtraChannelInfo.TYPE_BLACK, BitDepth.of(8), "K"));
        assertEquals(10, CodestreamLevel.required(m, 64, 64));
    }

    @Test
    void deepExtraChannelNeedsLevel10() {
        ImageMetadata m = rgb8();
        m.extraChannels.add(ExtraChannelInfo.of(ExtraChannelInfo.TYPE_DEPTH,
                BitDepth.of(32), "depth"));
        assertEquals(10, CodestreamLevel.required(m, 64, 64));
    }

    // ---- the jxll box: written only above the baseline, read back exactly

    @Test
    void baselineContainerHasNoLevelBox() throws Exception {
        byte[] cs = JxlEncoder.encode(gradient(64, 48, 3, 255), 64, 48, 8, false, false, false);
        assertEquals(5, Container.requiredLevel(cs));
        byte[] file = Container.wrap(cs);
        assertEquals(5, Container.declaredLevel(file));
        assertFalse(hasBox(file, "jxll"), "a level-5 file should carry no jxll box");
    }

    @Test
    void level10ContainerCarriesLevelBox() throws Exception {
        float[][] f = new float[3][64 * 48];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < f[c].length; i++) {
                f[c][i] = (float) Math.sin(i * 0.1 + c);
            }
        }
        byte[] cs = JxlEncoder.encodeFloat(f, 64, 48, BitDepth.float32(), false, false, false);
        assertEquals(10, Container.requiredLevel(cs));
        byte[] file = Container.wrap(cs);
        assertTrue(hasBox(file, "jxll"), "a level-10 file must declare it");
        assertEquals(10, Container.declaredLevel(file));

        JxlImage img = JxlDecoder.decode(file);
        assertEquals(10, img.codestreamLevel);
    }

    // ---- enforcement: a broken promise is rejected, over-declaration is not

    @Test
    void declaringLessThanTheContentNeedsIsRejected() throws Exception {
        // a level-10 container (float32) whose jxll byte is forged down to 5
        float[][] f = new float[3][32 * 32];
        byte[] cs = JxlEncoder.encodeFloat(f, 32, 32, BitDepth.float32(), false, false, false);
        byte[] file = Container.wrap(cs);
        assertEquals(10, Container.declaredLevel(file));
        forgeLevel(file, 5);
        assertEquals(5, Container.declaredLevel(file));

        IOException e = assertThrows(IOException.class, () -> JxlDecoder.decode(file));
        assertTrue(e.getMessage().contains("level 5"), e.getMessage());
    }

    @Test
    void overDeclaringIsAllowed() throws Exception {
        // level-5 content (plain 8-bit) in a container forced to declare level 10:
        // legal, because level 10 is a superset — decode, do not reject
        byte[] cs = JxlEncoder.encode(gradient(40, 40, 3, 255), 40, 40, 8, false, false, false);
        byte[] file = Container.wrap(cs);       // no jxll box (level 5)
        byte[] forced = withLevelBox(file, 10); // splice one in, saying 10
        assertEquals(10, Container.declaredLevel(forced));
        JxlImage img = JxlDecoder.decode(forced);
        assertEquals(10, img.codestreamLevel);
    }

    @Test
    void bareCodestreamIsNeverEnforced() throws Exception {
        // a bare stream declares nothing, so level-10 content in one is decoded
        // rather than measured against the level-5 default
        float[][] f = new float[3][48 * 40];
        byte[] cs = JxlEncoder.encodeFloat(f, 48, 40, BitDepth.float32(), false, false, false);
        assertFalse(Container.isContainer(cs));
        assertEquals(0, Container.declaredLevel(cs)); // declares nothing: unconstrained
        JxlImage img = JxlDecoder.decode(cs);         // must not throw
        assertEquals(48, img.width);
        assertEquals(5, img.codestreamLevel);         // reported as the default 5
    }

    @Test
    void sixteenBitContainerIsLevel5AndReadable() throws Exception {
        byte[] cs = JxlEncoder.encode(gradient(100, 80, 3, 65535), 100, 80, 16, false, false, false);
        byte[] file = Container.wrap(cs);
        assertFalse(hasBox(file, "jxll"), "16-bit is level 5 per the spec");
        JxlImage img = JxlDecoder.decode(file);
        assertEquals(5, img.codestreamLevel);
        assertEquals(100, img.width);
    }

    // ---- against the reference: real files declare a level, and we agree

    @Test
    void conformanceContainersSatisfyTheirDeclaration() throws Exception {
        Path root = Path.of("test-data", "conformance");
        assumeTrue(Files.isDirectory(root), "conformance corpus not present");
        int containers = 0;
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : (Iterable<Path>) dirs.sorted()::iterator) {
                Path in = dir.resolve("input.jxl");
                if (!Files.exists(in)) {
                    continue;
                }
                byte[] file = Files.readAllBytes(in);
                if (!Container.isContainer(file)) {
                    continue;
                }
                containers++;
                int declared = Container.declaredLevel(file);
                int required = Container.requiredLevel(Container.extractCodestream(file));
                // the reference declares at least what the content needs
                assertTrue(required <= declared, dir.getFileName()
                        + ": declared " + declared + " but content needs " + required);
                // and enforcement lets every one of them through
                assertEquals(declared, JxlDecoder.decode(file).codestreamLevel,
                        dir.getFileName().toString());
            }
        }
        assumeTrue(containers > 0, "no container vectors found");
    }

    // ---- helpers

    private static boolean hasBox(byte[] f, String type) {
        for (int i = 0; i + 8 <= f.length; i++) {
            if (f[i + 4] == type.charAt(0) && f[i + 5] == type.charAt(1)
                    && f[i + 6] == type.charAt(2) && f[i + 7] == type.charAt(3)) {
                return true;
            }
        }
        return false;
    }

    /** Overwrites the level byte inside an existing jxll box. */
    private static void forgeLevel(byte[] f, int level) {
        for (int i = 0; i + 9 <= f.length; i++) {
            if (f[i + 4] == 'j' && f[i + 5] == 'x' && f[i + 6] == 'l' && f[i + 7] == 'l') {
                f[i + 8] = (byte) level;
                return;
            }
        }
        throw new IllegalStateException("no jxll box to forge");
    }

    /** Splices a jxll box right after the ftyp box of a container that lacks one. */
    private static byte[] withLevelBox(byte[] file, int level) {
        int ftypEnd = Container.SIGNATURE_BOX.length + Container.FTYP_BOX.length;
        byte[] box = {0, 0, 0, 9, 'j', 'x', 'l', 'l', (byte) level};
        byte[] out = new byte[file.length + box.length];
        System.arraycopy(file, 0, out, 0, ftypEnd);
        System.arraycopy(box, 0, out, ftypEnd, box.length);
        System.arraycopy(file, ftypEnd, out, ftypEnd + box.length, file.length - ftypEnd);
        return out;
    }
}
