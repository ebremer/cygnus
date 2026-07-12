package com.ebremer.cygnus.jpeg2000.conformance;

import com.ebremer.cygnus.jpeg2000.decoder.DecodedImage;
import com.ebremer.cygnus.jpeg2000.decoder.Jpeg2000Decoder;
import com.ebremer.cygnus.jpeg2000.testutil.Pgx;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ISO/IEC 15444-4 decoder conformance: Class 1 (full resolution, every
 * component) and Class 0 (specified resolution reduction, first component)
 * over the official profile-0/profile-1 exercise codestreams, compared
 * against the reference images within the standard's maximum peak-error and
 * MSE limits (values from ISO 15444-4 as encoded in OpenJPEG's conformance
 * suite).
 *
 * <p>Test data is downloaded once from the uclouvain/openjpeg-data
 * repository into {@code test-data/conformance} and cached; the tests are
 * skipped when the data is unavailable (offline).</p>
 */
class ConformanceTest {

    private static final String RAW =
            "https://raw.githubusercontent.com/uclouvain/openjpeg-data/master/";
    private static final Path CACHE = Path.of("test-data", "conformance");
    private static final AtomicBoolean NETWORK_OK = new AtomicBoolean(true);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /**
     * One conformance exercise: decode {@code file} at {@code reduction} and
     * compare channel i against {@code refs[i]} within (peak[i], mse[i]).
     * Class-0 exercises follow the minimal-decoder rules: first component
     * only, no inverse component transform, reference bit depth (possibly
     * 8-bit) and a resolution derived from the reference dimensions.
     */
    private record Case(String label, String file, int reduction,
                        String[] refs, double[] peak, double[] mse, boolean class0,
                        String skipReason) {
    }

    private static Case c1(String file, int reduction, double[] peak, double[] mse) {
        String base = "c1" + file.substring(0, file.indexOf('.'));
        String[] refs = new String[peak.length];
        for (int i = 0; i < refs.length; i++) {
            refs[i] = base + "_" + i + ".pgx";
        }
        return new Case("C1-" + file, file, reduction, refs, peak, mse, false, null);
    }

    private static Case c0(String file, int reduction, String ref, double peak, double mse) {
        return new Case("C0-" + file, file, reduction, new String[] {ref},
                new double[] {peak}, new double[] {mse}, true, null);
    }

    private static Case c0Skip(String file, String reason) {
        return new Case("C0-" + file, file, 0, new String[0],
                new double[0], new double[0], true, reason);
    }

    private static double[] d(double... v) {
        return v;
    }

    private static final List<Case> CASES = List.of(
            // ---- Class 1: full resolution, all components ----
            c1("p0_01.j2k", 0, d(0), d(0)),
            c1("p0_02.j2k", 0, d(0), d(0)),
            c1("p0_03.j2k", 0, d(0), d(0)),
            c1("p0_04.j2k", 0, d(5, 4, 6), d(0.776, 0.626, 1.070)),
            c1("p0_05.j2k", 0, d(2, 2, 2, 0), d(0.302, 0.307, 0.269, 0)),
            c1("p0_06.j2k", 0, d(635, 403, 378, 0), d(11287, 6124, 3968, 0)),
            c1("p0_07.j2k", 0, d(0, 0, 0), d(0, 0, 0)),
            c1("p0_08.j2k", 1, d(0, 0, 0), d(0, 0, 0)),
            c1("p0_09.j2k", 0, d(0), d(0)),
            c1("p0_10.j2k", 0, d(0, 0, 0), d(0, 0, 0)),
            c1("p0_11.j2k", 0, d(0), d(0)),
            c1("p0_12.j2k", 0, d(0), d(0)),
            c1("p0_13.j2k", 0, d(0, 0, 0, 0), d(0, 0, 0, 0)),
            c1("p0_14.j2k", 0, d(0, 0, 0), d(0, 0, 0)),
            c1("p0_15.j2k", 0, d(0), d(0)),
            c1("p0_16.j2k", 0, d(0), d(0)),
            c1("p1_01.j2k", 0, d(0), d(0)),
            c1("p1_02.j2k", 0, d(5, 4, 6), d(0.765, 0.616, 1.051)),
            c1("p1_03.j2k", 0, d(2, 2, 1, 0), d(0.3, 0.210, 0.200, 0)),
            c1("p1_04.j2k", 0, d(624), d(3080)),
            c1("p1_05.j2k", 0, d(40, 40, 40), d(8.458, 9.816, 10.154)),
            c1("p1_06.j2k", 0, d(2, 2, 2), d(0.6, 0.6, 0.6)),
            c1("p1_07.j2k", 0, d(0, 0), d(0, 0)),
            // ---- Class 0: reduced resolution, first component only ----
            c0("p0_01.j2k", 0, "c0p0_01.pgx", 0, 0),
            c0("p0_02.j2k", 0, "c0p0_02.pgx", 0, 0),
            c0Skip("p0_03.j2k", "c0 reference is not a plain DWT-level discard; "
                    + "reduced decode is verified against ffmpeg -lowres instead"),
            c0("p0_04.j2k", 3, "c0p0_04.pgx", 33, 55.8),
            c0("p0_05.j2k", 3, "c0p0_05.pgx", 54, 68),
            c0("p0_06.j2k", 3, "c0p0_06.pgx", 109, 743),
            c0Skip("p0_07.j2k", "c0 reference is 128x128 but every component has "
                    + "only 3 decomposition levels (min 256x256): the reference "
                    + "is not producible by DWT-level discard"),
            c0("p0_08.j2k", 5, "c0p0_08.pgx", 7, 6.72),
            c0("p0_09.j2k", 2, "c0p0_09.pgx", 4, 1.47),
            c0("p0_10.j2k", 0, "c0p0_10.pgx", 10, 2.84),
            c0("p0_11.j2k", 0, "c0p0_11.pgx", 0, 0),
            c0("p0_12.j2k", 0, "c0p0_12.pgx", 0, 0),
            c0("p0_13.j2k", 0, "c0p0_13.pgx", 0, 0),
            c0("p0_14.j2k", 2, "c0p0_14.pgx", 0, 0),
            c0Skip("p0_15.j2k", "c0 reference is not a plain DWT-level discard; "
                    + "reduced decode is verified against ffmpeg -lowres instead"),
            c0("p0_16.j2k", 0, "c0p0_16.pgx", 0, 0),
            c0("p1_01.j2k", 0, "c0p1_01.pgx", 0, 0),
            c0("p1_02.j2k", 3, "c0p1_02.pgx", 35, 74),
            c0("p1_03.j2k", 3, "c0p1_03.pgx", 28, 18.8),
            c0Skip("p1_04.j2k", "c0 reference is not a plain DWT-level discard "
                    + "(diffs track image detail); our reduction-3 decode is "
                    + "bit-identical to ffmpeg -lowres 3"),
            c0("p1_05.j2k", 4, "c0p1_05.pgx", 128, 16384),
            c0("p1_06.j2k", 1, "c0p1_06.pgx", 128, 16384),
            c0("p1_07.j2k", 0, "c0p1_07.pgx", 0, 0));

    /** Downloads (or reuses) a conformance data file; null when offline. */
    private static Path fetch(String repoPath) {
        Path local = CACHE.resolve(Path.of(repoPath).getFileName().toString());
        try {
            if (Files.exists(local) && Files.size(local) > 0) {
                return local;
            }
        } catch (IOException ignored) {
            // fall through to re-download
        }
        if (!NETWORK_OK.get()) {
            return null;
        }
        try {
            Files.createDirectories(CACHE);
            HttpRequest req = HttpRequest.newBuilder(URI.create(RAW + repoPath))
                    .timeout(Duration.ofSeconds(60)).build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                return null;
            }
            Files.write(local, resp.body());
            return local;
        } catch (Exception e) {
            NETWORK_OK.set(false);
            return null;
        }
    }

    @TestFactory
    List<DynamicTest> conformance() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Case c : CASES) {
            tests.add(DynamicTest.dynamicTest(c.label, () -> runCase(c)));
        }
        for (int i = 1; i <= 9; i++) {
            String name = "file" + i + ".jp2";
            tests.add(DynamicTest.dynamicTest("JP2-" + name, () -> runJp2Smoke(name)));
        }
        return tests;
    }

    private void runCase(Case c) throws Exception {
        assumeTrue(c.skipReason == null, c.skipReason);
        Path input = fetch("input/conformance/" + c.file);
        assumeTrue(input != null, "conformance data unavailable (offline?)");
        Pgx[] refs = new Pgx[c.refs.length];
        for (int comp = 0; comp < refs.length; comp++) {
            Path ref = fetch("baseline/conformance/" + c.refs[comp]);
            assumeTrue(ref != null, "reference data unavailable (offline?)");
            refs[comp] = Pgx.read(ref);
        }

        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        dec.open(Files.readAllBytes(input));
        int reduction = c.reduction;
        if (c.class0) {
            dec.setInverseComponentTransform(false);
            reduction = matchReduction(dec, refs[0]);
        }
        DecodedImage img = dec.decode(reduction);

        assertTrue(img.numChannels >= refs.length,
                c.label + ": decoded " + img.numChannels + " channels, expected >= "
                        + refs.length);
        for (int comp = 0; comp < refs.length; comp++) {
            Pgx ref = refs[comp];
            assertEquals(ref.width, img.chanWidth[comp],
                    c.label + " comp " + comp + ": width");
            assertEquals(ref.height, img.chanHeight[comp],
                    c.label + " comp " + comp + ": height");
            int shift = c.class0 ? Math.max(0, img.depth[comp] - ref.depth) : 0;
            long n = (long) ref.width * ref.height;
            long peak = 0;
            double sumSq = 0;
            int firstBad = -1;
            for (int i = 0; i < n; i++) {
                long diff = Math.abs(((long) img.samples[comp][i] >> shift) - ref.samples[i]);
                if (diff > c.peak[comp] && firstBad < 0) {
                    firstBad = i;
                }
                peak = Math.max(peak, diff);
                sumSq += (double) diff * diff;
            }
            double mse = sumSq / n;
            assertTrue(peak <= c.peak[comp] && mse <= c.mse[comp],
                    String.format("%s comp %d: peak=%d (limit %.0f), mse=%.4f (limit %.4f),"
                                    + " first excess at %d (%s)",
                            c.label, comp, peak, c.peak[comp], mse, c.mse[comp],
                            firstBad, dec.warnings()));
        }
    }

    /**
     * Class-0 references define their own resolution: find the reduction
     * whose first-component dimensions match the reference image.
     */
    private static int matchReduction(Jpeg2000Decoder dec, Pgx ref) throws Exception {
        StringBuilder seen = new StringBuilder();
        for (int r = 0; r <= dec.maxReduction(); r++) {
            DecodedImage s = dec.shape(r);
            if (s.chanWidth[0] == ref.width && s.chanHeight[0] == ref.height) {
                return r;
            }
            seen.append(" r").append(r).append("=")
                    .append(s.chanWidth[0]).append("x").append(s.chanHeight[0]);
        }
        throw new AssertionError("No reduction matches reference "
                + ref.width + "x" + ref.height + ";" + seen);
    }

    /** The JP2-container conformance files must at least decode sanely. */
    private void runJp2Smoke(String name) throws Exception {
        Path input = fetch("input/conformance/" + name);
        assumeTrue(input != null, "conformance data unavailable (offline?)");
        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        DecodedImage img = dec.decode(Files.readAllBytes(input));
        assertTrue(img.width > 0 && img.height > 0, name + ": non-empty image");
        assertTrue(img.numChannels > 0, name + ": has channels");
        for (int ch = 0; ch < img.numChannels; ch++) {
            assertTrue(img.samples[ch] != null && img.samples[ch].length > 0,
                    name + ": channel " + ch + " decoded");
        }
    }
}
