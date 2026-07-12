package com.ebremer.cygnus.jpeg2000.t1;

/**
 * Bit reader for raw (arithmetic-bypass) codeword segments (T.800 D.6):
 * MSB-first with the 0xFF bit-stuffing rule, and - unlike packet headers -
 * an all-ones fill past the end of the segment, matching the MQ coder's
 * 0xFF fill. Encoders may truncate trailing bytes of a terminated raw
 * segment when the decoder's 1-fill reproduces them.
 */
final class RawBitReader {

    private final byte[] data;
    private final int end;
    private int pos;
    private int buf;
    private int bitsLeft;
    private boolean lastWasFF;

    RawBitReader(byte[] data, int start, int end) {
        this.data = data;
        this.pos = start;
        this.end = Math.min(end, data.length);
    }

    int readBit() {
        if (bitsLeft == 0) {
            buf = pos < end ? data[pos] & 0xFF : 0xFF; // 1-fill past the end
            pos++;
            bitsLeft = lastWasFF ? 7 : 8;
            lastWasFF = buf == 0xFF;
        }
        bitsLeft--;
        return (buf >> bitsLeft) & 1;
    }
}
