package com.ebremer.cygnus.nifti.io;

import com.ebremer.cygnus.nifti.NiftiFiles;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;

/**
 * Random access to a NIfTI file's bytes.
 *
 * <p>The point of the abstraction is gzip. A {@code .nii} is seekable and a
 * slice can be read straight out of the middle of it; a {@code .nii.gz} is
 * not, and there is no way to reach byte 40 million of a deflate stream
 * except by inflating the 40 million before it. Since {@code .nii.gz} is what
 * FSL writes by default and therefore what most real files are, sitting on it
 * is not an option — so a compressed input is inflated once, up front, and
 * served from the result.</p>
 *
 * <p>Where the result goes depends on how big it is: memory below
 * {@value #MEMORY_LIMIT_PROPERTY} bytes, a temporary file above it, deleted
 * when the source is closed. An fMRI run of a few hundred megabytes should not
 * have to fit in the heap to be readable, and a structural scan of thirty
 * should not pay for a file.</p>
 *
 * <p>Reading only the header of a compressed file does not inflate the rest:
 * {@link #peek} stops after the bytes it was asked for.</p>
 */
public interface VoxelSource extends Closeable {

    /** System property naming how many inflated bytes are held in memory before a temporary file is used. */
    String MEMORY_LIMIT_PROPERTY = "nifti.gzipMemoryLimit";

    /** The default for {@value #MEMORY_LIMIT_PROPERTY}: 128 MiB. */
    long DEFAULT_MEMORY_LIMIT = 128L << 20;

    /** How many bytes there are. */
    long length() throws IOException;

    /**
     * Reads exactly {@code len} bytes starting at {@code offset}.
     *
     * @throws EOFException if the source ends first — which is the shape a
     *                      truncated file takes, and is why callers do not
     *                      need to check the length before every read
     */
    void readFully(long offset, byte[] dst, int dstOff, int len) throws IOException;

    /** Reads exactly {@code len} bytes at {@code offset} into a fresh array. */
    default byte[] read(long offset, int len) throws IOException {
        byte[] out = new byte[len];
        readFully(offset, out, 0, len);
        return out;
    }

    /**
     * The first {@code count} bytes of {@code path}, inflating if the file is
     * gzipped, and reading no further than it has to.
     *
     * <p>This is how a header is read without inflating a whole volume: a
     * {@code .nii.gz} yields its 540-byte header after a few kilobytes of
     * input. Fewer than {@code count} bytes come back if the file is shorter,
     * so the caller can tell a truncated file from a short read.</p>
     */
    static byte[] peek(Path path, int count) throws IOException {
        try (InputStream raw = new java.io.BufferedInputStream(
                Files.newInputStream(path), 1 << 16)) {
            raw.mark(2);
            byte[] magic = raw.readNBytes(2);
            raw.reset();
            InputStream in = NiftiFiles.looksGzipped(magic)
                    ? new GZIPInputStream(raw, 1 << 16) : raw;
            return in.readNBytes(count);
        }
    }

    /** Every byte of {@code data}, held by reference. */
    static VoxelSource ofBytes(byte[] data) {
        return new MemorySource(data);
    }

    /** An open file, closed when this source is. */
    static VoxelSource ofChannel(FileChannel channel) {
        return new ChannelSource(channel, null);
    }

    /**
     * Opens {@code path} for random access, inflating it first if it is
     * gzipped — which is decided by its first two bytes, not by its name, so a
     * {@code .nii} that is really compressed still reads.
     */
    static VoxelSource open(Path path) throws IOException {
        byte[] magic = Files.newInputStream(path).readNBytes(2);
        if (!NiftiFiles.looksGzipped(magic)) {
            return ofChannel(FileChannel.open(path, StandardOpenOption.READ));
        }
        try (InputStream in = new GZIPInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(path), 1 << 16),
                1 << 16)) {
            return inflate(in, memoryLimit());
        }
    }

    /**
     * Drains {@code in} into memory, or into a temporary file once it passes
     * {@code memoryLimit}. The spill copies what has accumulated and then
     * streams the rest, so the peak is the limit rather than the whole thing.
     */
    static VoxelSource inflate(InputStream in, long memoryLimit) throws IOException {
        ByteArrayOutputStream buffered = new ByteArrayOutputStream(1 << 16);
        byte[] chunk = new byte[1 << 16];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) >= 0) {
            if (total + n > memoryLimit) {
                return spill(buffered, chunk, n, in);
            }
            buffered.write(chunk, 0, n);
            total += n;
        }
        return ofBytes(buffered.toByteArray());
    }

    private static VoxelSource spill(ByteArrayOutputStream buffered, byte[] chunk,
                                     int chunkLength, InputStream rest) throws IOException {
        Path temp = Files.createTempFile("cygnus-nifti-", ".raw");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                buffered.writeTo(out);
                out.write(chunk, 0, chunkLength);
                rest.transferTo(out);
            }
            return new ChannelSource(FileChannel.open(temp, StandardOpenOption.READ), temp);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /**
     * How many inflated bytes are held in memory before spilling: the
     * {@value #MEMORY_LIMIT_PROPERTY} system property, clamped to
     * {@code [0, Integer.MAX_VALUE]} because that is as large as one array
     * gets. Read live, so a host can change it without reloading the class.
     */
    static long memoryLimit() {
        Long v = Long.getLong(MEMORY_LIMIT_PROPERTY);
        if (v == null) {
            return DEFAULT_MEMORY_LIMIT;
        }
        return Math.clamp(v, 0, Integer.MAX_VALUE);
    }

    /** Bytes already in the heap. */
    final class MemorySource implements VoxelSource {

        private final byte[] data;

        MemorySource(byte[] data) {
            this.data = data;
        }

        @Override
        public long length() {
            return data.length;
        }

        @Override
        public void readFully(long offset, byte[] dst, int dstOff, int len) throws IOException {
            checkRange(offset, len, dst, dstOff);
            if (offset + len > data.length) {
                throw new EOFException("wanted bytes " + offset + ".." + (offset + len)
                        + " but the image is " + data.length + " bytes");
            }
            System.arraycopy(data, (int) offset, dst, dstOff, len);
        }

        @Override
        public void close() {
        }
    }

    /** A file, which may be the original or a temporary one holding inflated bytes. */
    final class ChannelSource implements VoxelSource {

        private final FileChannel channel;
        private final Path deleteOnClose;

        ChannelSource(FileChannel channel, Path deleteOnClose) {
            this.channel = channel;
            this.deleteOnClose = deleteOnClose;
        }

        @Override
        public long length() throws IOException {
            return channel.size();
        }

        @Override
        public void readFully(long offset, byte[] dst, int dstOff, int len) throws IOException {
            checkRange(offset, len, dst, dstOff);
            ByteBuffer buf = ByteBuffer.wrap(dst, dstOff, len);
            long pos = offset;
            while (buf.hasRemaining()) {
                int n = channel.read(buf, pos);
                if (n < 0) {
                    throw new EOFException("wanted bytes " + offset + ".." + (offset + len)
                            + " but the image is " + channel.size() + " bytes");
                }
                pos += n;
            }
        }

        @Override
        public void close() throws IOException {
            try {
                channel.close();
            } finally {
                if (deleteOnClose != null) {
                    Files.deleteIfExists(deleteOnClose);
                }
            }
        }
    }

    private static void checkRange(long offset, int len, byte[] dst, int dstOff)
            throws IOException {
        if (offset < 0 || len < 0 || dstOff < 0 || len > dst.length - dstOff) {
            throw new IOException("read of " + len + " bytes at " + offset
                    + " into a " + dst.length + "-byte array at " + dstOff
                    + " is not a range");
        }
    }
}
