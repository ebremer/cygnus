package com.ebremer.cygnus.io;

import java.io.IOException;
import javax.imageio.stream.ImageInputStream;

/**
 * {@link ByteSource} over a seekable {@link ImageInputStream}. Not
 * thread-safe (the underlying stream has a single position).
 */
public final class ImageInputStreamSource implements ByteSource {

    private final ImageInputStream stream;

    public ImageInputStreamSource(ImageInputStream stream) {
        this.stream = stream;
    }

    @Override
    public int read(long pos, byte[] dst, int off, int len) throws IOException {
        stream.seek(pos);
        return stream.read(dst, off, len);
    }

    @Override
    public long length() throws IOException {
        return stream.length();
    }
}
