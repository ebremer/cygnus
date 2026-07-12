package com.ebremer.cygnus.jpeg2000.t2;

/**
 * MSB-first bit reader for packet headers with the bit-stuffing rule of
 * T.800 B.10.1: after a byte equal to 0xFF, the next byte carries only
 * seven bits (its most significant bit is a stuffed 0).
 */
public final class PacketBitReader {

    private final byte[] data;
    private int pos;
    private final int end;
    private int buf;        // current byte
    private int bitsLeft;   // unread bits in buf
    private boolean lastWasFF;

    public PacketBitReader(byte[] data, int start, int end) {
        this.data = data;
        this.pos = start;
        this.end = Math.min(end, data.length);
        this.buf = 0;
        this.bitsLeft = 0;
        this.lastWasFF = false;
    }

    /** Reads one bit; returns 0 past the end of the data. */
    public int readBit() {
        if (bitsLeft == 0) {
            if (pos >= end) {
                return 0;
            }
            buf = data[pos++] & 0xFF;
            bitsLeft = lastWasFF ? 7 : 8;
            lastWasFF = buf == 0xFF;
        }
        bitsLeft--;
        return (buf >> bitsLeft) & 1;
    }

    /** Reads {@code n} bits MSB first. */
    public int readBits(int n) {
        int v = 0;
        for (int i = 0; i < n; i++) {
            v = (v << 1) | readBit();
        }
        return v;
    }

    /**
     * Finishes the packet header: discards remaining bits of the current byte
     * and, if the header ended with a 0xFF byte, the following stuffed byte
     * (T.800 B.10.1: a header never ends with 0xFF followed by data).
     * Returns the position of the first byte after the header.
     */
    public int align() {
        bitsLeft = 0;
        if (lastWasFF) {
            // the single stuffed bit of the next byte belongs to this header
            pos++;
            lastWasFF = false;
        }
        return pos;
    }

    /**
     * If the next two bytes equal the given marker code, skips them.
     * Must be called on a byte boundary (after {@link #align()}).
     */
    public boolean trySkipMarker(int code) {
        if (pos + 2 <= end
                && ((data[pos] & 0xFF) << 8 | (data[pos + 1] & 0xFF)) == code) {
            pos += 2;
            return true;
        }
        return false;
    }

    public int position() {
        return pos;
    }

    public boolean exhausted() {
        return pos >= end && bitsLeft == 0;
    }
}
