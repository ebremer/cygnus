package com.ebremer.cygnus.jpegxl.io;

import java.io.IOException;

/**
 * Thrown during a region-restricted decode when the stream uses a feature
 * whose reconstruction has non-local dependencies (a frame-global squeeze
 * transform, delta-palette entries, or patches copied from a region-limited
 * canvas snapshot). The decoder catches it and transparently falls back to
 * decoding every group, still returning only the requested region.
 */
public final class RegionUnsupportedException extends IOException {

    public RegionUnsupportedException(String message) {
        super(message);
    }
}
