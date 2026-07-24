package com.ebremer.cygnus.nifti;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The extension chain that follows a NIfTI header.
 *
 * <p>Immediately after the header — at 352 for NIfTI-1, 544 for NIfTI-2 —
 * comes a four-byte marker. If its first byte is zero, or if the file ends
 * before it, there are no extensions. If it is nonzero, extensions follow, each
 * one an {@code esize}, an {@code ecode} and {@code esize - 8} bytes of
 * payload, with {@code esize} a positive multiple of 16.</p>
 *
 * <p>Where the chain ends is not in the chain. In a single file it ends at
 * {@code vox_offset}, where the voxels begin; in a {@code .hdr} of a pair it
 * runs to the end of the file. Both are the caller's {@code limit}.</p>
 *
 * <h2>Alignment falls out</h2>
 *
 * <p>352 and 544 are both multiples of 16, and every {@code esize} is a
 * multiple of 16, so a {@code vox_offset} computed as header + marker +
 * extensions is 16-aligned however many extensions there are. That is the
 * property the specification asks for and it needs no rounding step.</p>
 */
public final class NiftiExtensions {

    /** Bytes of marker between the header and the first extension. */
    public static final int MARKER_LENGTH = 4;

    private NiftiExtensions() {
    }

    /**
     * Reads the marker at {@code off} and the chain that follows it, stopping
     * at {@code limit}.
     *
     * <p>Slack is tolerated, a lie is not. Fewer than 16 bytes left over after
     * the last complete extension is padding some writer left and the read
     * simply ends; an {@code esize} that is not a positive multiple of 16, or
     * that claims more bytes than are left, is a malformed file and an
     * {@link IOException}. Nothing is allocated from an {@code esize} before it
     * has been held against the bytes actually there.</p>
     *
     * @param bytes the header and what follows it
     * @param off   where the four-byte marker starts: 352 or 544
     * @param limit where the chain ends: {@code vox_offset}, or the file length
     * @param order the file's byte order
     */
    public static List<NiftiExtension> decode(byte[] bytes, int off, int limit, ByteOrder order)
            throws IOException {
        if (off < 0 || limit < off) {
            throw new IOException("extension range " + off + ".." + limit + " is not a range");
        }
        int end = Math.min(limit, bytes.length);
        // A .hdr of exactly 348 bytes has no marker at all, which is legal:
        // no room for one means no extensions, not a malformed file.
        if (end - off < MARKER_LENGTH || bytes[off] == 0) {
            return List.of();
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(order);
        List<NiftiExtension> out = new ArrayList<>(4);
        int p = off + MARKER_LENGTH;
        while (end - p >= NiftiExtension.MIN_ESIZE) {
            int esize = b.getInt(p);
            int ecode = b.getInt(p + 4);
            if (esize < NiftiExtension.MIN_ESIZE
                    || esize % NiftiExtension.ALIGNMENT != 0) {
                throw new IOException("extension " + out.size() + " at offset " + p
                        + " declares esize " + esize
                        + "; it must be a positive multiple of "
                        + NiftiExtension.ALIGNMENT);
            }
            if (esize > end - p) {
                throw new IOException("extension " + out.size() + " at offset " + p
                        + " declares esize " + esize + " but only " + (end - p)
                        + " bytes remain before " + limit);
            }
            out.add(new NiftiExtension(ecode,
                    Arrays.copyOfRange(bytes, p + NiftiExtension.PROLOGUE, p + esize)));
            p += esize;
        }
        return List.copyOf(out);
    }

    /**
     * The marker and the chain, ready to be written after a header. An empty
     * list gives the four zero bytes that say "no extensions", which every
     * single-file image carries whether it has extensions or not.
     */
    public static byte[] encode(List<NiftiExtension> extensions, ByteOrder order)
            throws IOException {
        long total = encodedLength(extensions);
        if (total > Integer.MAX_VALUE) {
            throw new IOException("extensions total " + total
                    + " bytes, more than one array can hold");
        }
        byte[] out = new byte[(int) total];
        if (extensions.isEmpty()) {
            return out;
        }
        out[0] = 1;
        ByteBuffer b = ByteBuffer.wrap(out).order(order);
        int p = MARKER_LENGTH;
        for (NiftiExtension e : extensions) {
            int esize = e.esize();
            b.putInt(p, esize);
            b.putInt(p + 4, e.ecode());
            b.put(p + NiftiExtension.PROLOGUE, e.data(), 0, e.data().length);
            p += esize;     // whatever padding remains is already zero
        }
        return out;
    }

    /** How many bytes {@link #encode} produces: the marker plus every {@code esize}. */
    public static long encodedLength(List<NiftiExtension> extensions) {
        long total = MARKER_LENGTH;
        for (NiftiExtension e : extensions) {
            total += e.esize();
        }
        return total;
    }

    /**
     * Where the voxels go in a single file carrying these extensions: the
     * header, the marker, and the chain. Always a multiple of 16, and never
     * less than {@link NiftiVersion#minVoxOffset}.
     */
    public static long voxOffsetFor(NiftiVersion version, List<NiftiExtension> extensions) {
        return version.headerSize + encodedLength(extensions);
    }
}
