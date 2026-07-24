package com.ebremer.cygnus.nifti.io;

import com.ebremer.cygnus.nifti.NiftiDataType;
import com.ebremer.cygnus.nifti.NiftiHeader;
import java.io.IOException;

/**
 * Bounds checks for the header numbers that become allocations and offsets.
 *
 * <p>A NIfTI header is 348 or 540 bytes and every one of them is believed
 * until checked. {@code dim[1..7]} multiply into a voxel count, the voxel
 * count multiplies by {@code bitpix} into a byte count, and
 * {@code vox_offset} says where to start reading — so a few bytes of a
 * hostile or damaged header can ask for an array that does not fit in memory
 * or an offset past the end of the disk. Every such value is held against a
 * limit <em>before</em> anything is sized from it, and a violation is an
 * {@link IOException} — the file is malformed — never an
 * {@link OutOfMemoryError}, a {@link NegativeArraySizeException} or an
 * {@link ArrayIndexOutOfBoundsException}.</p>
 *
 * <h2>The volume and a read are bounded differently</h2>
 *
 * <p>A NIfTI-2 volume is <em>allowed</em> to hold more than 2^31 voxels —
 * that is what the version is for — so the total is not held to what a Java
 * array can index. What bounds the total is the file: a header claiming 2^62
 * voxels is rejected because those bytes are not there, which is a check
 * against reality rather than against a number someone chose.</p>
 *
 * <p>What is held to a chosen limit is a single read. That is the one that
 * turns into a single array, and {@value #MAX_VOXELS_PROPERTY} is what a
 * deployment reading untrusted files lowers.</p>
 */
public final class Bounds {

    /** System property naming the ceiling on the voxels one read may ask for. */
    public static final String MAX_VOXELS_PROPERTY = "nifti.maxVoxels";

    /** System property naming the ceiling on the bytes of extensions read into memory. */
    public static final String MAX_EXTENSION_BYTES_PROPERTY = "nifti.maxExtensionBytes";

    /** The default for {@value #MAX_EXTENSION_BYTES_PROPERTY}: 64 MiB. */
    public static final long DEFAULT_MAX_EXTENSION_BYTES = 64L << 20;

    private Bounds() {
    }

    /**
     * The ceiling on the extension region held in memory: the
     * {@value #MAX_EXTENSION_BYTES_PROPERTY} system property, clamped to
     * {@code [0, Integer.MAX_VALUE]}.
     *
     * <p>Unlike the voxel array, extensions are read whole and up front —
     * a caller asking for a header expects its extensions with it. The
     * region's size comes from {@code vox_offset}, which a file is free to
     * put anywhere inside itself, so a 4 GB file may legitimately claim 4 GB
     * of extensions. The largest anything real carries is a CIFTI XML
     * document in the low megabytes.</p>
     */
    public static long maxExtensionBytes() {
        Long v = Long.getLong(MAX_EXTENSION_BYTES_PROPERTY);
        if (v == null) {
            return DEFAULT_MAX_EXTENSION_BYTES;
        }
        return Math.clamp(v, 0, Integer.MAX_VALUE);
    }

    /**
     * How many bytes of extension chain lie between the end of a header and
     * {@code limit} — {@code vox_offset} in a single file, the file's length
     * in a {@code .hdr} — checked against {@link #maxExtensionBytes}.
     */
    public static int extensionRegion(int headerSize, long limit) throws IOException {
        if (limit <= headerSize) {
            return 0;
        }
        long bytes = limit - headerSize;
        long cap = maxExtensionBytes();
        if (bytes > cap) {
            throw new IOException("the header claims " + bytes
                    + " bytes of extensions before the voxels, past the " + cap
                    + "-byte ceiling (-D" + MAX_EXTENSION_BYTES_PROPERTY + " raises it)");
        }
        return (int) bytes;
    }

    /**
     * The ceiling on how many voxels a single read may ask for: the
     * {@value #MAX_VOXELS_PROPERTY} system property, clamped to
     * {@code [1, Integer.MAX_VALUE]} and defaulting to the top of that range,
     * which is as much as one Java array holds. Read live, so a host can lower
     * it without reloading the class.
     */
    public static long maxVoxels() {
        Long v = Long.getLong(MAX_VOXELS_PROPERTY);
        if (v == null || v > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, v);
    }

    /**
     * Checks {@code dim[0..7]} and returns the number of voxels the header
     * describes.
     *
     * <p>{@code dim[0]} must be 1 to 7 — it is how many of the others count,
     * and a reader that trusts anything else indexes past the array. Each
     * counted extent must be non-negative; a zero is the omission real files
     * write and counts as one (see {@link NiftiHeader#effectiveDim}), a
     * negative is not something a length can be. The product is accumulated in
     * {@code long} and rejected if it overflows.</p>
     */
    public static long voxelCount(NiftiHeader header) throws IOException {
        long nd = header.dim[0];
        if (nd < 1 || nd > 7) {
            throw new IOException("dim[0] is " + nd
                    + "; a NIfTI image has 1 to 7 dimensions");
        }
        long voxels = 1;
        for (int i = 1; i <= nd; i++) {
            long extent = header.dim[i];
            if (extent < 0) {
                throw new IOException("dim[" + i + "] is " + extent
                        + "; an extent cannot be negative");
            }
            long counted = extent == 0 ? 1 : extent;
            try {
                voxels = Math.multiplyExact(voxels, counted);
            } catch (ArithmeticException overflow) {
                throw new IOException("dim[1.." + nd + "] multiply past "
                        + Long.MAX_VALUE + " voxels, which no file holds");
            }
        }
        return voxels;
    }

    /**
     * How many bytes the whole voxel array occupies, in {@code long}, checked
     * for overflow. Not held to any ceiling of its own — see the class note —
     * but {@link #checkVolumeFits} holds it against the file.
     */
    public static long volumeBytes(NiftiHeader header, NiftiDataType type) throws IOException {
        long voxels = voxelCount(header);
        try {
            return Math.multiplyExact(voxels, type.bytesPerVoxel());
        } catch (ArithmeticException overflow) {
            throw new IOException(voxels + " voxels of " + type + " are more than "
                    + Long.MAX_VALUE + " bytes, which no file holds");
        }
    }

    /**
     * Checks that {@code vox_offset} and the voxel array are inside a file of
     * {@code available} bytes.
     *
     * <p>This is the check that makes the others unnecessary to guess at: a
     * header can claim any number of voxels it likes, and if the bytes are not
     * there it is wrong however plausible the number looked.</p>
     *
     * @param header    the header, whose {@code vox_offset} and dimensions are checked
     * @param type      the resolved voxel type
     * @param available bytes in the file the voxels live in — the image itself
     *                  for a single file, the {@code .img} for a pair
     */
    public static void checkVolumeFits(NiftiHeader header, NiftiDataType type, long available)
            throws IOException {
        long offset = header.voxOffset;
        if (offset < 0) {
            throw new IOException("vox_offset is " + offset + "; it cannot be negative");
        }
        if (header.singleFile && offset < header.version.minVoxOffset) {
            throw new IOException("vox_offset is " + offset + ", inside the "
                    + header.version.headerSize + "-byte header; a "
                    + header.version + " single file starts its voxels at "
                    + header.version.minVoxOffset + " at the earliest");
        }
        long bytes = volumeBytes(header, type);
        long end;
        try {
            end = Math.addExact(offset, bytes);
        } catch (ArithmeticException overflow) {
            throw new IOException("vox_offset " + offset + " plus " + bytes
                    + " bytes of voxels overflows a signed 64-bit offset");
        }
        if (end > available) {
            throw new IOException("the header describes " + bytes + " bytes of voxels at offset "
                    + offset + ", which needs a file of " + end + " bytes; this one is "
                    + available);
        }
    }

    /**
     * Checks that a single read of {@code voxels} voxels can be held in one
     * array, and returns it as an {@code int} ready to size one.
     *
     * @param what what is being read, for the message
     */
    public static int readableVoxels(long voxels, NiftiDataType type, String what)
            throws IOException {
        if (voxels < 0) {
            throw new IOException(what + " is " + voxels + " voxels");
        }
        long cap = maxVoxels();
        if (voxels > cap) {
            throw new IOException(what + " is " + voxels + " voxels, past the "
                    + cap + "-voxel ceiling on a single read (-D"
                    + MAX_VOXELS_PROPERTY + " raises it)");
        }
        long components = voxels * type.components;
        if (components > Integer.MAX_VALUE) {
            throw new IOException(what + " is " + voxels + " voxels of " + type + ", which is "
                    + components + " components and more than one Java array holds");
        }
        return (int) voxels;
    }

    /**
     * Checks that {@code count} items starting at {@code start} stay inside
     * {@code limit}, computing the end in {@code long}.
     */
    public static void checkRange(long start, long count, long limit, String what)
            throws IOException {
        if (start < 0 || count < 0) {
            throw new IOException(what + ": " + count + " at " + start + " is not a range");
        }
        long end;
        try {
            end = Math.addExact(start, count);
        } catch (ArithmeticException overflow) {
            throw new IOException(what + ": " + start + " plus " + count + " overflows");
        }
        if (end > limit) {
            throw new IOException(what + ": " + count + " at " + start
                    + " runs to " + end + ", past " + limit);
        }
    }

    /**
     * A value about to size an array: non-negative and no more than
     * {@link Integer#MAX_VALUE}.
     */
    public static int arrayLength(long value, String what) throws IOException {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IOException(what + " is " + value
                    + ", which is not a length one Java array can have");
        }
        return (int) value;
    }
}
