package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlFrame;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.jpeg.JpegReconstructor;
import com.ebremer.cygnus.jpegxl.testutil.Npy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The complete official conformance corpus (github.com/libjxl/conformance),
 * validated against the corpus's own reference data — no external decoder
 * needed. Pass criteria mirror scripts/conformance.py: per-channel RMSE and
 * peak absolute error within each testcase's declared limits, byte-exact
 * embedded ICC profiles, and byte-exact reconstructed JPEGs for jbrd cases.
 *
 * <p>The corpus lives in {@code test-data/conformance/<case>/} (gitignored):
 * clone the repo and download the referenced objects from
 * {@code https://storage.googleapis.com/jxl-conformance/objects/<sha>}, or
 * point {@code JXL_CONFORMANCE_DIR} at an existing checkout with objects.
 */
class OfficialConformanceTest {

    private static Path corpusDir() {
        String env = System.getenv("JXL_CONFORMANCE_DIR");
        return env != null ? Path.of(env) : Path.of("test-data", "conformance");
    }

    static Stream<String> cases() throws IOException {
        Path dir = corpusDir();
        if (!Files.isDirectory(dir)) {
            return Stream.empty();
        }
        try (var list = Files.list(dir)) {
            return list.filter(d -> Files.exists(d.resolve("test.json"))
                            && Files.exists(d.resolve("input.jxl"))
                            && Files.exists(d.resolve("reference_image.npy")))
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @org.junit.jupiter.api.Test
    void corpusPresent() throws IOException {
        assumeTrue(Files.isDirectory(corpusDir()), "conformance corpus not downloaded");
        assertTrue(cases().count() >= 39, "corpus incomplete: " + cases().count() + " cases");
    }

    /**
     * Cases with a documented deviation from their declared thresholds.
     * Currently empty — every corpus case passes.
     */
    private static final java.util.Set<String> KNOWN_DEVIATIONS = java.util.Set.of();

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void conformance(String name) throws IOException {
        assumeTrue(!KNOWN_DEVIATIONS.contains(name),
                name + " has a small documented deviation (see KNOWN_DEVIATIONS)");
        // the references keep raw channels: spot colours stay un-composited —
        // and the property comes off again even when an assertion throws
        System.setProperty("jxl.skipSpot", "true");
        try {
            checkCase(name);
        } finally {
            System.clearProperty("jxl.skipSpot");
        }
    }

    private void checkCase(String name) throws IOException {
        Path dir = corpusDir().resolve(name);
        String json = Files.readString(dir.resolve("test.json"));
        List<double[]> limits = frameLimits(json); // {rms, peak} per frame
        byte[] input = Files.readAllBytes(dir.resolve("input.jxl"));

        Npy ref = Npy.read(dir.resolve("reference_image.npy"));
        assertEquals(4, ref.shape.length, "reference rank");
        int refFrames = ref.shape[0];
        int h = ref.shape[1];
        int w = ref.shape[2];
        int ch = ref.shape[3];

        JxlImage image = JxlDecoder.decodeToFloats(input);
        assertEquals(w, image.width, "width");
        assertEquals(h, image.height, "height");
        assertEquals(refFrames, image.frames.size(), "frame count");
        assertEquals(refFrames, limits.size(), "frames in test.json");

        for (int f = 0; f < refFrames; f++) {
            JxlFrame frame = image.frames.get(f);
            assertEquals(ch, frame.floatChannels.length,
                    "channel count (ref " + ch + ")");
            double rmsLimit = limits.get(f)[0];
            double peakLimit = limits.get(f)[1];
            double worstRms = 0;
            double peak = 0;
            int peakC = 0;
            long peakAt = 0;
            for (int c = 0; c < ch; c++) {
                float[] ours = frame.floatChannels[c];
                double sumSq = 0;
                long base = ((long) f * h) * w * ch + c;
                for (int y = 0; y < h; y++) {
                    long row = base + (long) y * w * ch;
                    int orow = y * w;
                    for (int x = 0; x < w; x++) {
                        double e = Math.abs(ref.data[(int) (row + (long) x * ch)] - ours[orow + x]);
                        sumSq += e * e;
                        if (e > peak) {
                            peak = e;
                            peakC = c;
                            peakAt = orow + x;
                        }
                    }
                }
                worstRms = Math.max(worstRms, Math.sqrt(sumSq / ((double) w * h)));
            }
            System.out.printf("%-28s frame %d: rms %.3g (limit %.3g), peak %.3g (limit %.3g)%n",
                    name, f, worstRms, rmsLimit, peak, peakLimit);
            assertTrue(worstRms <= rmsLimit, "frame " + f + " RMSE " + worstRms
                    + " > " + rmsLimit);
            assertTrue(peak <= peakLimit, "frame " + f + " peak error " + peak
                    + " > " + peakLimit + " at channel " + peakC + " index " + peakAt);
        }

        // embedded ICC profiles must reconstruct byte-exactly; enumerated
        // colourspaces have no embedded stream to compare
        Path originalIcc = dir.resolve(originalIccName(json));
        if (image.metadata.iccProfile != null && Files.exists(originalIcc)) {
            assertArrayEquals(Files.readAllBytes(originalIcc), image.metadata.iccProfile,
                    "embedded ICC profile");
        }

        // jbrd cases must rebuild the original JPEG byte for byte
        Matcher jbrd = Pattern.compile("\"reconstructed_jpeg\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (jbrd.find()) {
            byte[] expected = Files.readAllBytes(dir.resolve(jbrd.group(1)));
            assertTrue(JpegReconstructor.hasJpegData(input), "jbrd box missing");
            assertArrayEquals(expected, JpegReconstructor.reconstruct(input),
                    "reconstructed JPEG");
        }
    }

    private static List<double[]> frameLimits(String json) {
        List<double[]> limits = new ArrayList<>();
        Matcher frames = Pattern.compile(
                "\\{[^{}]*\"rms_error\"\\s*:\\s*([0-9.eE+-]+)[^{}]*\"peak_error\"\\s*:\\s*([0-9.eE+-]+)[^{}]*\\}")
                .matcher(json);
        while (frames.find()) {
            limits.add(new double[] {
                Double.parseDouble(frames.group(1)),
                Double.parseDouble(frames.group(2)),
            });
        }
        return limits;
    }

    private static String originalIccName(String json) {
        Matcher m = Pattern.compile("\"original_icc\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : "original.icc";
    }
}
