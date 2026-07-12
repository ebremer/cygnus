package com.ebremer.cygnus.jpeg2000.io;

/** {@link ByteSource} over an in-memory array. */
public final class ArraySource implements ByteSource {

    private final byte[] data;

    public ArraySource(byte[] data) {
        this.data = data;
    }

    @Override
    public int read(long pos, byte[] dst, int off, int len) {
        if (pos >= data.length) {
            return -1;
        }
        int n = (int) Math.min(len, data.length - pos);
        System.arraycopy(data, (int) pos, dst, off, n);
        return n;
    }

    @Override
    public long length() {
        return data.length;
    }
}
