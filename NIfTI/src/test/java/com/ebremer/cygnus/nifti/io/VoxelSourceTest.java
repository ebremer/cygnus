package com.ebremer.cygnus.nifti.io;

import com.ebremer.cygnus.nifti.testutil.NiftiBuilder;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Random access over a plain file, a gzipped one, and bytes already in hand. */
class VoxelSourceTest {

    /** Bytes with enough structure that a misplaced read is obvious. */
    private static byte[] pattern(int length) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (i * 31 + (i >> 8));
        }
        return b;
    }

    /** The same reads against every kind of source. */
    private static void assertReadsCorrectly(VoxelSource source, byte[] expected)
            throws IOException {
        assertEquals(expected.length, source.length());
        assertArrayEquals(expected, source.read(0, expected.length), "whole");
        assertArrayEquals(Arrays.copyOfRange(expected, 100, 200),
                source.read(100, 100), "a slab from the middle");
        assertArrayEquals(Arrays.copyOfRange(expected, expected.length - 7, expected.length),
                source.read(expected.length - 7, 7), "the last bytes");
        assertArrayEquals(new byte[0], source.read(expected.length, 0), "an empty read at the end");

        // reading into the middle of a caller's array
        byte[] dst = new byte[64];
        Arrays.fill(dst, (byte) 0x5A);
        source.readFully(300, dst, 16, 32);
        assertArrayEquals(Arrays.copyOfRange(expected, 300, 332),
                Arrays.copyOfRange(dst, 16, 48), "read into an offset in the destination");
        assertEquals((byte) 0x5A, dst[15], "nothing written before the offset");
        assertEquals((byte) 0x5A, dst[48], "nothing written after the length");
    }

    @Test
    void bytesInMemoryReadCorrectly() throws IOException {
        byte[] data = pattern(1000);
        try (VoxelSource s = VoxelSource.ofBytes(data)) {
            assertReadsCorrectly(s, data);
        }
    }

    @Test
    void aPlainFileReadsCorrectly(@TempDir Path dir) throws IOException {
        byte[] data = pattern(1000);
        Path file = NiftiBuilder.write(dir.resolve("plain.nii"), data);
        try (VoxelSource s = VoxelSource.open(file)) {
            assertReadsCorrectly(s, data);
        }
    }

    @Test
    void aGzippedFileReadsCorrectly(@TempDir Path dir) throws IOException {
        byte[] data = pattern(100_000);
        Path file = NiftiBuilder.write(dir.resolve("compressed.nii.gz"),
                NiftiBuilder.gzip(data));
        assertTrue(Files.size(file) < data.length, "the fixture really is compressed");
        try (VoxelSource s = VoxelSource.open(file)) {
            assertReadsCorrectly(s, data);
        }
    }

    @Test
    void compressionIsDecidedByContentNotByName(@TempDir Path dir) throws IOException {
        // a .nii that is really gzipped, which happens when a file is renamed
        byte[] data = pattern(5000);
        Path lying = NiftiBuilder.write(dir.resolve("actually-compressed.nii"),
                NiftiBuilder.gzip(data));
        try (VoxelSource s = VoxelSource.open(lying)) {
            assertReadsCorrectly(s, data);
        }

        // and a .nii.gz that is not compressed at all
        Path alsoLying = NiftiBuilder.write(dir.resolve("not-really.nii.gz"), data);
        try (VoxelSource s = VoxelSource.open(alsoLying)) {
            assertReadsCorrectly(s, data);
        }
    }

    // ---- the memory / temporary-file threshold ----

    @Test
    void inflatingBelowTheLimitStaysInMemory() throws IOException {
        byte[] data = pattern(10_000);
        try (VoxelSource s = VoxelSource.inflate(
                new ByteArrayInputStream(data), 1 << 20)) {
            assertTrue(s instanceof VoxelSource.MemorySource,
                    "below the limit there is no reason for a file");
            assertReadsCorrectly(s, data);
        }
    }

    @Test
    void inflatingAboveTheLimitSpillsToAFileAndReadsTheSame() throws IOException {
        byte[] data = pattern(300_000);
        try (VoxelSource s = VoxelSource.inflate(
                new ByteArrayInputStream(data), 1024)) {
            assertTrue(s instanceof VoxelSource.ChannelSource,
                    "past the limit it belongs in a file");
            assertReadsCorrectly(s, data);
        }
    }

    @Test
    void bothSidesOfTheThresholdGiveIdenticalBytes() throws IOException {
        Random rnd = new Random(11);
        byte[] data = new byte[70_000];
        rnd.nextBytes(data);
        try (VoxelSource memory = VoxelSource.inflate(new ByteArrayInputStream(data), 1 << 30);
             VoxelSource spilled = VoxelSource.inflate(new ByteArrayInputStream(data), 4096)) {
            assertEquals(memory.length(), spilled.length());
            assertArrayEquals(memory.read(0, data.length), spilled.read(0, data.length));
            assertArrayEquals(data, spilled.read(0, data.length));
        }
    }

    @Test
    void theSpillFileIsGoneOnceTheSourceIsClosed() throws IOException {
        long before = tempFileCount();
        VoxelSource s = VoxelSource.inflate(new ByteArrayInputStream(pattern(50_000)), 512);
        assertTrue(s instanceof VoxelSource.ChannelSource);
        assertEquals(before + 1, tempFileCount(), "a spill file exists while the source is open");
        s.close();
        assertEquals(before, tempFileCount(), "and is gone once it is closed");
    }

    @Test
    void aFailedSpillLeavesNoFileBehind() throws IOException {
        long before = tempFileCount();
        // fails only after the spill has started, so the temporary file exists
        // by the time the read throws
        java.io.InputStream failing = new java.io.InputStream() {
            private int served;

            @Override
            public int read() {
                return 0;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (served > 8192) {
                    throw new IOException("the disk went away");
                }
                served += len;
                Arrays.fill(b, off, off + len, (byte) 7);
                return len;
            }
        };
        IOException e = assertThrows(IOException.class,
                () -> VoxelSource.inflate(failing, 1024));
        assertEquals("the disk went away", e.getMessage());
        assertEquals(before, tempFileCount(), "a failed spill cleans up after itself");
    }

    private static long tempFileCount() throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> files = Files.list(tmp)) {
            return files.filter(p -> p.getFileName().toString().startsWith("cygnus-nifti-"))
                    .count();
        }
    }

    @Test
    void theMemoryLimitIsReadLive() {
        String saved = System.getProperty(VoxelSource.MEMORY_LIMIT_PROPERTY);
        try {
            System.clearProperty(VoxelSource.MEMORY_LIMIT_PROPERTY);
            assertEquals(VoxelSource.DEFAULT_MEMORY_LIMIT, VoxelSource.memoryLimit());

            System.setProperty(VoxelSource.MEMORY_LIMIT_PROPERTY, "4096");
            assertEquals(4096, VoxelSource.memoryLimit());

            System.setProperty(VoxelSource.MEMORY_LIMIT_PROPERTY, "-1");
            assertEquals(0, VoxelSource.memoryLimit(), "clamped: a negative limit means never");

            System.setProperty(VoxelSource.MEMORY_LIMIT_PROPERTY, "99999999999");
            assertEquals(Integer.MAX_VALUE, VoxelSource.memoryLimit(),
                    "clamped: one array is as big as memory gets");
        } finally {
            if (saved == null) {
                System.clearProperty(VoxelSource.MEMORY_LIMIT_PROPERTY);
            } else {
                System.setProperty(VoxelSource.MEMORY_LIMIT_PROPERTY, saved);
            }
        }
    }

    // ---- peeking at a header without inflating a volume ----

    @Test
    void peekReadsOnlyWhatItWasAskedFor(@TempDir Path dir) throws IOException {
        byte[] data = pattern(2_000_000);
        Path plain = NiftiBuilder.write(dir.resolve("big.nii"), data);
        Path gzipped = NiftiBuilder.write(dir.resolve("big.nii.gz"), NiftiBuilder.gzip(data));

        assertArrayEquals(Arrays.copyOf(data, 540), VoxelSource.peek(plain, 540));
        assertArrayEquals(Arrays.copyOf(data, 540), VoxelSource.peek(gzipped, 540),
                "a compressed header comes back without inflating the volume");
    }

    @Test
    void peekOnAShortFileReturnsWhatIsThere(@TempDir Path dir) throws IOException {
        byte[] data = pattern(100);
        Path file = NiftiBuilder.write(dir.resolve("stub.nii"), data);
        byte[] got = VoxelSource.peek(file, 540);
        assertEquals(100, got.length, "a short read is how a truncated file shows itself");
        assertArrayEquals(data, got);

        Path gz = NiftiBuilder.write(dir.resolve("stub.nii.gz"), NiftiBuilder.gzip(data));
        assertEquals(100, VoxelSource.peek(gz, 540).length);
    }

    // ---- reads that cannot be served ----

    @Test
    void readingPastTheEndIsAnEofNotAWrongAnswer(@TempDir Path dir) throws IOException {
        byte[] data = pattern(500);
        Path file = NiftiBuilder.write(dir.resolve("short.nii"), data);
        for (VoxelSource s : new VoxelSource[] {
                VoxelSource.ofBytes(data), VoxelSource.open(file)}) {
            try (s) {
                assertThrows(EOFException.class, () -> s.read(400, 200),
                        s.getClass().getSimpleName() + ": a read running off the end");
                assertThrows(EOFException.class, () -> s.read(500, 1));
                assertThrows(EOFException.class, () -> s.read(1_000_000, 4));
            }
        }
    }

    @Test
    void anImpossibleRangeIsRejectedBeforeAnythingIsRead() throws IOException {
        try (VoxelSource s = VoxelSource.ofBytes(pattern(500))) {
            byte[] dst = new byte[10];
            assertThrows(IOException.class, () -> s.readFully(-1, dst, 0, 4));
            assertThrows(IOException.class, () -> s.readFully(0, dst, 0, -4));
            assertThrows(IOException.class, () -> s.readFully(0, dst, -1, 4));
            assertThrows(IOException.class, () -> s.readFully(0, dst, 8, 4),
                    "four bytes will not fit at offset eight of a ten-byte array");
        }
    }

    @Test
    void anEmptyFileHasNoBytesAndSaysSo(@TempDir Path dir) throws IOException {
        Path empty = NiftiBuilder.write(dir.resolve("empty.nii"), new byte[0]);
        try (VoxelSource s = VoxelSource.open(empty)) {
            assertEquals(0, s.length());
            assertThrows(EOFException.class, () -> s.read(0, 1));
        }
    }
}
