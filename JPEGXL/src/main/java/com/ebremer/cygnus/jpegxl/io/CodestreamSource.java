package com.ebremer.cygnus.jpegxl.io;

import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import javax.imageio.stream.ImageInputStream;

/**
 * Random-access view of a bare JPEG XL codestream. Implementations map
 * codestream offsets to the underlying storage, which may be an in-memory
 * array or (possibly fragmented) box payloads inside an ISOBMFF container
 * read from an {@link ImageInputStream}.
 */
public interface CodestreamSource {

    /** Total codestream length in bytes. */
    long size() throws IOException;

    /**
     * Reads exactly {@code len} bytes starting at codestream offset
     * {@code offset}. Implementations must be safe to call from multiple
     * threads.
     */
    void readFully(long offset, byte[] dst, int dstOff, int len) throws IOException;

    default byte[] readRange(long offset, int len) throws IOException {
        byte[] out = new byte[len];
        readFully(offset, out, 0, len);
        return out;
    }

    /**
     * The codestream level a container declared in its {@code jxll} box (5 or
     * 10), or <b>0</b> when nothing declared one — a bare codestream, which is
     * decoded whatever it holds rather than measured against a baseline it never
     * claimed. The level travels with the source so the single decode path can
     * hold a file to its promise whatever entry point (bytes, stream, region)
     * reached it. Left as a plain int to keep this interface off the codestream
     * package.
     */
    default int declaredLevel() {
        return 0;
    }

    /** A source over an in-memory codestream. */
    final class ArraySource implements CodestreamSource {
        private final byte[] data;
        private final int level;

        public ArraySource(byte[] data) {
            this(data, 0);
        }

        public ArraySource(byte[] data, int declaredLevel) {
            this.data = data;
            this.level = declaredLevel;
        }

        @Override
        public int declaredLevel() {
            return level;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public void readFully(long offset, byte[] dst, int dstOff, int len) throws IOException {
            if (offset < 0 || offset + len > data.length) {
                throw new EOFException("read past end of codestream");
            }
            System.arraycopy(data, (int) offset, dst, dstOff, len);
        }
    }

    /** A contiguous run of codestream bytes inside the underlying file. */
    record Segment(long csOffset, long fileOffset, long length) {
    }

    /**
     * A source over an {@link ImageInputStream}, reading codestream bytes on
     * demand from the byte ranges listed in {@code segments}. Reads seek, so
     * the stream must not be repositioned concurrently by other users.
     */
    final class StreamSource implements CodestreamSource {
        private final ImageInputStream in;
        private final List<Segment> segments;
        private final long size;
        private final int level;

        public StreamSource(ImageInputStream in, List<Segment> segments) {
            this(in, segments, 0);
        }

        public StreamSource(ImageInputStream in, List<Segment> segments, int declaredLevel) {
            this.in = in;
            this.segments = segments;
            this.level = declaredLevel;
            Segment last = segments.get(segments.size() - 1);
            this.size = last.csOffset() + last.length();
        }

        @Override
        public int declaredLevel() {
            return level;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public void readFully(long offset, byte[] dst, int dstOff, int len) throws IOException {
            synchronized (in) {
                long remainingOffset = offset;
                int remaining = len;
                int out = dstOff;
                for (Segment s : segments) {
                    if (remaining == 0) {
                        break;
                    }
                    if (remainingOffset >= s.csOffset() + s.length()) {
                        continue;
                    }
                    if (remainingOffset < s.csOffset()) {
                        throw new EOFException("codestream gap at " + remainingOffset);
                    }
                    long within = remainingOffset - s.csOffset();
                    int chunk = (int) Math.min(remaining, s.length() - within);
                    in.seek(s.fileOffset() + within);
                    in.readFully(dst, out, chunk);
                    out += chunk;
                    remainingOffset += chunk;
                    remaining -= chunk;
                }
                if (remaining != 0) {
                    throw new EOFException("read past end of codestream");
                }
            }
        }
    }
}
