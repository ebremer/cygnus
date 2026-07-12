package com.ebremer.cygnus.jpeg2000.io;

import java.io.IOException;

/**
 * Random-access byte supplier for decoding: either an in-memory array or a
 * seekable stream. Positions are absolute {@code long} offsets, so sources
 * larger than 2 GiB are supported.
 */
public interface ByteSource {

    /**
     * Reads up to {@code len} bytes starting at absolute position
     * {@code pos}.
     *
     * @return the number of bytes read, or -1 at end of source
     */
    int read(long pos, byte[] dst, int off, int len) throws IOException;

    /** Total length in bytes, or -1 when not known in advance. */
    long length() throws IOException;

    static ByteSource of(byte[] data) {
        return new ArraySource(data);
    }
}
