package com.ebremer.cygnus.io;

import java.io.IOException;
import javax.imageio.IIOException;

/**
 * Buffered big-endian reader over a {@link ByteSource} with an explicit
 * position. Seeks are lazy: skipping large ranges (tile-part bodies) costs
 * nothing until the next read.
 */
public final class SourceReader {

    /** Small buffer: header segments are tiny, and large prefetches would
     * read tile-part bodies that the parser is about to seek across. */
    private static final int BUF_SIZE = 8192;

    private final ByteSource src;
    private final byte[] buf = new byte[BUF_SIZE];
    private long bufPos;      // source offset of buf[0]
    private int bufLen;       // valid bytes in buf
    private long pos;         // logical read position

    public SourceReader(ByteSource src) {
        this.src = src;
    }

    public long position() {
        return pos;
    }

    public void seek(long p) {
        pos = p;
    }

    /** Fills the buffer at the current position; false at end of source. */
    private boolean fill() throws IIOException {
        try {
            int n = src.read(pos, buf, 0, buf.length);
            if (n <= 0) {
                return false;
            }
            bufPos = pos;
            bufLen = n;
            return true;
        } catch (IOException e) {
            throw new IIOException("Read error at position " + pos, e);
        }
    }

    private boolean inBuffer() {
        return pos >= bufPos && pos < bufPos + bufLen;
    }

    public int u8() throws IIOException {
        if (!inBuffer() && !fill()) {
            throw new IIOException("Unexpected end of codestream");
        }
        return buf[(int) (pos++ - bufPos)] & 0xFF;
    }

    public int u16() throws IIOException {
        return (u8() << 8) | u8();
    }

    public long u32() throws IIOException {
        return ((long) u16() << 16) | u16();
    }

    public long u64() throws IIOException {
        return (u32() << 32) | u32();
    }

    /** Reads a 16-bit value, or -1 at a clean end of source. */
    public int u16OrEof() throws IIOException {
        if (!inBuffer() && !fill()) {
            return -1;
        }
        return u16();
    }

    /** Reads exactly {@code n} bytes from the current position. */
    public byte[] readBytes(int n) throws IIOException {
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            if (!inBuffer() && !fill()) {
                throw new IIOException("Unexpected end of codestream");
            }
            int avail = (int) (bufPos + bufLen - pos);
            int take = Math.min(avail, n - off);
            System.arraycopy(buf, (int) (pos - bufPos), out, off, take);
            off += take;
            pos += take;
        }
        return out;
    }

    /**
     * Scans from the current position to the end of the source without
     * retaining data; returns the end offset and leaves the final two bytes
     * in {@code lastTwo} (0xFF-filled if fewer were available).
     */
    public long scanToEof(byte[] lastTwo) throws IIOException {
        lastTwo[0] = (byte) 0xFF;
        lastTwo[1] = (byte) 0xFF;
        while (true) {
            if (!inBuffer() && !fill()) {
                return pos;
            }
            int avail = (int) (bufPos + bufLen - pos);
            long newPos = pos + avail;
            if (avail >= 2) {
                lastTwo[0] = buf[(int) (newPos - 2 - bufPos)];
                lastTwo[1] = buf[(int) (newPos - 1 - bufPos)];
            } else if (avail == 1) {
                lastTwo[0] = lastTwo[1];
                lastTwo[1] = buf[(int) (newPos - 1 - bufPos)];
            }
            pos = newPos;
        }
    }
}
