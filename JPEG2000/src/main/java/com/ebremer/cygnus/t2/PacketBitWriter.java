package com.ebremer.cygnus.t2;

import java.io.ByteArrayOutputStream;

/**
 * MSB-first bit writer with the packet-header bit-stuffing rule of
 * T.800 B.10.1: after emitting a 0xFF byte the following byte carries only
 * seven payload bits (its MSB is a stuffed 0). Mirrors
 * {@link PacketBitReader}.
 */
public final class PacketBitWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int buf;
    private int nbits;
    private boolean lastWasFF;

    public void bit(int b) {
        int cap = lastWasFF ? 7 : 8;
        buf = (buf << 1) | (b & 1);
        nbits++;
        if (nbits == cap) {
            out.write(buf);
            lastWasFF = cap == 8 && buf == 0xFF;
            buf = 0;
            nbits = 0;
        }
    }

    public void bits(int value, int count) {
        for (int i = count - 1; i >= 0; i--) {
            bit((value >> i) & 1);
        }
    }

    /**
     * Byte-aligns and finishes the header. If the final byte is 0xFF, a
     * stuffed 0x00 byte follows (a header may not end with 0xFF).
     */
    public byte[] finish() {
        if (nbits > 0) {
            int cap = lastWasFF ? 7 : 8;
            buf <<= cap - nbits;
            out.write(buf);
            lastWasFF = cap == 8 && buf == 0xFF;
            buf = 0;
            nbits = 0;
        }
        if (lastWasFF) {
            out.write(0);
            lastWasFF = false;
        }
        return out.toByteArray();
    }
}
