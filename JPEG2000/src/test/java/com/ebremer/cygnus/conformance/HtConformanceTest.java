package com.ebremer.cygnus.conformance;

import com.ebremer.cygnus.decoder.DecodedImage;
import com.ebremer.cygnus.decoder.Jpeg2000Decoder;
import com.ebremer.cygnus.testutil.Pnm;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * HTJ2K (ITU-T T.814) decoding validated against the OpenJPH test corpus
 * (aous72/jp2k_test_codestreams, BSD-2): several dozen HT codestreams -
 * code-blocks from 4x4 to 1024x4, tiles, all progression orders, gray/RGB/
 * YUV 4:2:0, 8- and 16-bit, 5/3 and 9/7 - each with per-component MSE and
 * peak-absolute-error limits from the corpus manifest (mse_pae.txt).
 * Reversible cases must decode bit-exactly.
 *
 * <p>Data is downloaded once into {@code test-data/htj2k} and cached;
 * tests skip when offline or when a listed file is absent upstream.</p>
 */
class HtConformanceTest {

    private static final String RAW =
            "https://raw.githubusercontent.com/aous72/jp2k_test_codestreams/main/openjph/";
    private static final Path CACHE = Path.of("test-data", "htj2k");
    private static final AtomicBoolean NETWORK_OK = new AtomicBoolean(true);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private record Case(String file, String ref, double[] mse, int[] pae) {
    }

    private static Path fetch(String name) {
        Path local = CACHE.resolve(Path.of(name).getFileName().toString());
        try {
            if (Files.exists(local) && Files.size(local) > 0) {
                return local;
            }
        } catch (IOException ignored) {
            // re-download
        }
        if (!NETWORK_OK.get()) {
            return null;
        }
        try {
            Files.createDirectories(CACHE);
            HttpRequest req = HttpRequest.newBuilder(URI.create(RAW + name))
                    .timeout(Duration.ofSeconds(120)).build();
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

    /** Parses the corpus manifest: file + reference, then one MSE/PAE line per component. */
    private static List<Case> manifest() throws IOException {
        Path mf = fetch("mse_pae.txt");
        if (mf == null) {
            return List.of();
        }
        List<Case> cases = new ArrayList<>();
        List<String> lines = Files.readAllLines(mf, StandardCharsets.US_ASCII);
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                i++;
                continue;
            }
            String[] head = line.split("\\s+");
            i++;
            List<Double> mses = new ArrayList<>();
            List<Integer> paes = new ArrayList<>();
            while (i < lines.size()) {
                String[] tok = lines.get(i).trim().split("\\s+");
                if (tok.length != 2 || !tok[0].matches("[0-9.]+")) {
                    break;
                }
                mses.add(Double.parseDouble(tok[0]));
                paes.add(Integer.parseInt(tok[1]));
                i++;
            }
            // skip DPX encoder round-trips and the OpenJPH extension file
            // (transform "irv53" is not part of T.800/T.814)
            if (head.length == 2 && head[0].contains(".") && !head[0].startsWith("dpx_")
                    && !head[0].contains("irv53_bhvhb")) {
                cases.add(new Case(head[0], head[1].replace("references/", ""),
                        mses.stream().mapToDouble(Double::doubleValue).toArray(),
                        paes.stream().mapToInt(Integer::intValue).toArray()));
            }
        }
        return cases;
    }

    @TestFactory
    List<DynamicTest> htCorpus() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (Case c : manifest()) {
            if (c.ref.endsWith(".yuv")) {
                tests.add(DynamicTest.dynamicTest("HT-" + c.file, () -> runYuv(c)));
            } else {
                tests.add(DynamicTest.dynamicTest("HT-" + c.file, () -> runPnm(c)));
            }
        }
        tests.add(DynamicTest.dynamicTest("HT-Bretagne1_ht.j2k", this::runBretagne));
        tests.add(DynamicTest.dynamicTest("HT-byte.jph", () -> runSmoke("byte.jph")));
        tests.add(DynamicTest.dynamicTest("HT-byte_causal.jhc",
                () -> runSmoke("byte_causal.jhc")));
        assumeTrue(!tests.isEmpty(), "HT corpus manifest unavailable (offline?)");
        return tests;
    }

    private static final String OPJ_RAW =
            "https://raw.githubusercontent.com/uclouvain/openjpeg-data/master/input/nonregression/";

    private static Path fetchOpj(String name) {
        Path local = CACHE.resolve(Path.of(name).getFileName().toString());
        try {
            if (Files.exists(local) && Files.size(local) > 0) {
                return local;
            }
            Files.createDirectories(CACHE);
            HttpRequest req = HttpRequest.newBuilder(URI.create(OPJ_RAW + name))
                    .timeout(Duration.ofSeconds(120)).build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                return null;
            }
            Files.write(local, resp.body());
            return local;
        } catch (Exception e) {
            return null;
        }
    }

    /** OpenJPEG's lossless HT encode of Bretagne1 must match the source PPM. */
    private void runBretagne() throws Exception {
        Path input = fetchOpj("htj2k/Bretagne1_ht.j2k");
        Path refFile = fetchOpj("Bretagne1.ppm");
        assumeTrue(input != null && refFile != null, "openjpeg HT data unavailable");
        Pnm ref = Pnm.read(refFile);
        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        DecodedImage img = dec.decode(Files.readAllBytes(input));
        assertEquals(ref.width, img.width);
        assertEquals(ref.height, img.height);
        for (int comp = 0; comp < ref.comps; comp++) {
            long peak = 0;
            for (int i = 0; i < ref.samples[comp].length; i++) {
                peak = Math.max(peak, Math.abs(
                        (long) img.samples[comp][i] - ref.samples[comp][i]));
            }
            assertTrue(peak == 0, "Bretagne1_ht comp " + comp
                    + " not lossless, peak=" + peak + " " + dec.warnings());
        }
    }

    private void runSmoke(String name) throws Exception {
        Path input = fetchOpj("htj2k/" + name);
        assumeTrue(input != null, "openjpeg HT data unavailable");
        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        DecodedImage img = dec.decode(Files.readAllBytes(input));
        assertTrue(img.width > 0 && img.height > 0, name + ": non-empty");
        assertTrue(dec.warnings().isEmpty(), name + ": warnings " + dec.warnings());
    }

    private DecodedImage decode(Case c) throws Exception {
        Path input = fetch(c.file);
        assumeTrue(input != null, "codestream unavailable: " + c.file);
        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        return dec.decode(Files.readAllBytes(input));
    }

    private void runPnm(Case c) throws Exception {
        Path refFile = fetch("references/" + c.ref);
        assumeTrue(refFile != null, "reference unavailable: " + c.ref);
        Pnm ref = Pnm.read(refFile);
        DecodedImage img = decode(c);
        assertEquals(ref.width, img.width, c.file + ": width");
        assertEquals(ref.height, img.height, c.file + ": height");
        assertTrue(img.numChannels >= ref.comps, c.file + ": channels");
        for (int comp = 0; comp < ref.comps; comp++) {
            check(c, comp, ref.samples[comp], img.samples[comp]);
        }
    }

    /** foreman 4:2:0 planar reference: Y 352x288, then U and V at 176x144. */
    private void runYuv(Case c) throws Exception {
        Path refFile = fetch("references/" + c.ref);
        assumeTrue(refFile != null, "reference unavailable: " + c.ref);
        byte[] raw = Files.readAllBytes(refFile);
        int w = 352;
        int h = 288;
        DecodedImage img = decode(c);
        assertEquals(w, img.width, c.file + ": width");
        assertEquals(h, img.height, c.file + ": height");
        int[][] planes = new int[3][];
        planes[0] = new int[w * h];
        planes[1] = new int[w * h / 4];
        planes[2] = new int[w * h / 4];
        int p = 0;
        for (int comp = 0; comp < 3; comp++) {
            for (int i = 0; i < planes[comp].length; i++) {
                planes[comp][i] = raw[p++] & 0xFF;
            }
        }
        for (int comp = 0; comp < 3; comp++) {
            assertEquals(planes[comp].length, img.samples[comp].length,
                    c.file + ": comp " + comp + " size");
            check(c, comp, planes[comp], img.samples[comp]);
        }
    }

    private void check(Case c, int comp, int[] ref, int[] got) {
        long n = ref.length;
        long peak = 0;
        double sumSq = 0;
        for (int i = 0; i < n; i++) {
            long d = Math.abs((long) got[i] - ref[i]);
            peak = Math.max(peak, d);
            sumSq += (double) d * d;
        }
        double mse = sumSq / n;
        double mseLimit = c.mse[comp];
        int paeLimit = c.pae[comp];
        if (mseLimit == 0 && paeLimit == 0) {
            // reversible: exact
            assertTrue(peak == 0, String.format(
                    "%s comp %d: expected lossless, peak=%d mse=%.4f",
                    c.file, comp, peak, mse));
        } else {
            // corpus limits are exact measurements of OpenJPH's own decoder,
            // not spec bounds; allow a small implementation margin for the
            // irreversible float path
            assertTrue(mse <= mseLimit * 1.05 + 1e-6
                            && peak <= paeLimit + Math.max(2, paeLimit / 8),
                    String.format("%s comp %d: mse=%.4f (limit %.4f), peak=%d (limit %d)",
                            c.file, comp, mse, mseLimit, peak, paeLimit));
        }
    }
}
