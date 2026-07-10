package com.ebremer.jpegxl.brotli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The brotli decoder against the reference test vectors from google/brotli. */
class BrotliTest {

    @ParameterizedTest
    @ValueSource(strings = {"x", "10x10y", "zeros", "quickfox_repeated", "alice29.txt"})
    void decodesReferenceVector(String name) throws IOException {
        byte[] compressed = resource(name + ".compressed");
        byte[] expected = resource(name);
        byte[] actual = Brotli.decode(compressed, 1 << 24);
        assertArrayEquals(expected, actual, name);
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = BrotliTest.class.getResourceAsStream("/brotli/" + name)) {
            if (in == null) {
                throw new IOException("missing test resource " + name);
            }
            return in.readAllBytes();
        }
    }
}
