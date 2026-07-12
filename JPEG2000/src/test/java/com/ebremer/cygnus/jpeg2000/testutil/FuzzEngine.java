package com.ebremer.cygnus.jpeg2000.testutil;

import com.ebremer.cygnus.jpeg2000.decoder.Jpeg2000Decoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Mutation fuzzer for the decoder: takes valid seed codestreams, applies
 * random byte-level mutations, and verifies that decoding either succeeds
 * or fails with a controlled {@code IOException}/{@code IllegalArgumentException}
 * within a time budget - never with a runtime error, memory exhaustion or
 * a hang. Failing inputs are written to {@code target/fuzz-failures} for
 * reproduction.
 */
public final class FuzzEngine {

    private static final ExecutorService RUNNER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fuzz-decode");
        t.setDaemon(true);
        return t;
    });

    private FuzzEngine() {
    }

    /** Applies 1..8 random mutations to a copy of {@code seed}. */
    public static byte[] mutate(byte[] seed, Random rnd) {
        byte[] d = seed.clone();
        int mutations = 1 + rnd.nextInt(8);
        for (int m = 0; m < mutations; m++) {
            switch (rnd.nextInt(6)) {
                case 0 -> { // flip random bits in one byte
                    int i = rnd.nextInt(d.length);
                    d[i] ^= (byte) (1 << rnd.nextInt(8));
                }
                case 1 -> { // set a byte to an interesting value
                    int i = rnd.nextInt(d.length);
                    byte[] vals = {0, 1, (byte) 0x7F, (byte) 0x80, (byte) 0xFF};
                    d[i] = vals[rnd.nextInt(vals.length)];
                }
                case 2 -> { // 16-bit value tweak, biased toward the header
                    if (d.length >= 2) {
                        int span = rnd.nextBoolean() ? Math.min(256, d.length) : d.length;
                        int i = rnd.nextInt(Math.max(1, span - 1));
                        int v = rnd.nextInt(65536);
                        d[i] = (byte) (v >> 8);
                        d[i + 1] = (byte) v;
                    }
                }
                case 3 -> { // truncate
                    int n = 1 + rnd.nextInt(d.length);
                    byte[] cut = new byte[n];
                    System.arraycopy(d, 0, cut, 0, n);
                    d = cut;
                }
                case 4 -> { // duplicate a slice over another position
                    if (d.length > 8) {
                        int len = 1 + rnd.nextInt(Math.min(64, d.length / 2));
                        int from = rnd.nextInt(d.length - len);
                        int to = rnd.nextInt(d.length - len);
                        System.arraycopy(d, from, d, to, len);
                    }
                }
                default -> { // burst of random bytes
                    int len = 1 + rnd.nextInt(Math.min(32, d.length));
                    int at = rnd.nextInt(d.length - len + 1);
                    for (int i = 0; i < len; i++) {
                        d[at + i] = (byte) rnd.nextInt(256);
                    }
                }
            }
        }
        return d;
    }

    /**
     * Runs {@code iterations} mutations per seed; returns the number of
     * inputs that decoded successfully (the rest failed cleanly).
     *
     * @throws AssertionError on any disallowed failure mode
     */
    public static long run(List<byte[]> seeds, long baseSeed, int iterations,
                           long timeoutMillis) throws Exception {
        long ok = 0;
        for (int s = 0; s < seeds.size(); s++) {
            byte[] seed = seeds.get(s);
            Random rnd = new Random(baseSeed + s);
            for (int it = 0; it < iterations; it++) {
                byte[] input = mutate(seed, rnd);
                Future<?> f = RUNNER.submit(() -> {
                    Jpeg2000Decoder dec = new Jpeg2000Decoder();
                    dec.setParallelism(1);
                    dec.setMemoryLimit(64L << 20);
                    try {
                        dec.decode(input);
                    } catch (javax.imageio.IIOException | IllegalArgumentException e) {
                        // controlled rejection of malformed input: fine
                        return;
                    } catch (java.io.IOException e) {
                        return;
                    }
                });
                try {
                    f.get(timeoutMillis, TimeUnit.MILLISECONDS);
                    ok++;
                } catch (TimeoutException e) {
                    f.cancel(true);
                    throw fail(seed, input, baseSeed + s, it, "timed out after "
                            + timeoutMillis + " ms");
                } catch (java.util.concurrent.ExecutionException e) {
                    throw fail(seed, input, baseSeed + s, it,
                            "threw " + e.getCause());
                }
            }
        }
        return ok;
    }

    private static AssertionError fail(byte[] seed, byte[] input, long seedVal,
                                       int iteration, String what) {
        Path dir = Path.of("target", "fuzz-failures");
        String name = "fuzz-" + seedVal + "-" + iteration;
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(name + ".input"), input);
            Files.write(dir.resolve(name + ".seed"), seed);
        } catch (Exception ignored) {
            // reporting only
        }
        return new AssertionError("Fuzz failure (rng seed " + seedVal + ", iteration "
                + iteration + "): decode " + what + "; input saved to " + dir
                + "/" + name + ".input");
    }
}
