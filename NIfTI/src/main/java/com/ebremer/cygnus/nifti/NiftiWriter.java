package com.ebremer.cygnus.nifti;

import com.ebremer.cygnus.nifti.io.Bounds;
import com.ebremer.cygnus.nifti.io.VoxelCodec;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a NIfTI image, in either version and any of the four layouts.
 *
 * <p>Voxels can be handed over all at once or a piece at a time. The
 * streaming form is the primitive and the one-shot form calls it, so the two
 * produce the same bytes by construction rather than by agreement — a volume
 * assembled slice by slice is byte-identical to the same volume written
 * whole.</p>
 *
 * <h2>What is derived, and what is refused</h2>
 *
 * <p>{@code sizeof_hdr}, the magic and {@code vox_offset} are computed from
 * what is actually being written, never taken from the header, so they cannot
 * disagree with the file they describe. {@code bitpix} is checked against
 * {@code datatype} rather than either being trusted. A header that
 * contradicts itself — a {@code bitpix} that does not match its type, an
 * extent that is negative, a {@code dim[0]} outside 1 to 7 — is refused
 * rather than quietly repaired, because repairing it means guessing which
 * half the writer meant.</p>
 *
 * <p>The one thing a caller may set is a {@code vox_offset} larger than the
 * extensions need: that is padding, it is honoured, and the gap is zeroed. A
 * zero means "not set" and the offset is derived; anything smaller than the
 * extensions require is a contradiction and is refused.</p>
 *
 * <h2>Choosing the version</h2>
 *
 * <p>By default a header asking for NIfTI-1 gets NIfTI-1 if all of it fits —
 * every {@code dim} inside 16 bits, {@code vox_offset} and the reals inside a
 * {@code float} — and NIfTI-2 if any of it does not. The fit is decided by
 * attempting the NIfTI-1 encode, so the test and the encoder cannot drift
 * apart. {@link Options#strict()} turns the upgrade off and makes the
 * overflow an error instead, for a caller who needs a file some older tool
 * can read.</p>
 */
public final class NiftiWriter implements Closeable {

    /** How to write: which layout, and whether to compress. */
    public record Options(boolean strictVersion, Layout layout, Compression compression) {

        /** Whether the voxels go in the header's file or beside it. */
        public enum Layout {
            /** {@code .nii} means one file, {@code .hdr}/{@code .img} means a pair. */
            FROM_NAME,
            /** One file, whatever it is called. */
            SINGLE_FILE,
            /** A {@code .hdr} and an {@code .img}, whatever the path is called. */
            PAIR
        }

        /** Whether to gzip. */
        public enum Compression {
            /** Compress if the name ends in {@code .gz}. */
            FROM_NAME,
            /** Compress. */
            GZIP,
            /** Do not compress. */
            NONE
        }

        /** Layout and compression from the name, and NIfTI-1 upgraded to NIfTI-2 if it must be. */
        public static Options defaults() {
            return new Options(false, Layout.FROM_NAME, Compression.FROM_NAME);
        }

        /** As these, but a header that does not fit its version is an error rather than an upgrade. */
        public Options strict() {
            return new Options(true, layout, compression);
        }

        /** As these, but with the given layout. */
        public Options layout(Layout newLayout) {
            return new Options(strictVersion, newLayout, compression);
        }

        /** As these, but with the given compression. */
        public Options compression(Compression newCompression) {
            return new Options(strictVersion, layout, newCompression);
        }
    }

    private final NiftiHeader header;
    private final NiftiDataType type;
    private final long expectedVoxels;
    private final OutputStream voxelStream;
    private long writtenVoxels;
    private boolean closed;

    private NiftiWriter(NiftiHeader header, NiftiDataType type, long expectedVoxels,
                        OutputStream voxelStream) {
        this.header = header;
        this.type = type;
        this.expectedVoxels = expectedVoxels;
        this.voxelStream = voxelStream;
    }

    // =====================================================================
    // One-shot.
    // =====================================================================

    /** Writes a whole image, with the layout taken from {@code path}'s name. */
    public static NiftiHeader write(Path path, NiftiHeader header,
                                    List<NiftiExtension> extensions, Object voxels)
            throws IOException {
        return write(path, header, extensions, voxels, Options.defaults());
    }

    /** Writes a whole image. */
    public static NiftiHeader write(Path path, NiftiHeader header,
                                    List<NiftiExtension> extensions, Object voxels,
                                    Options options) throws IOException {
        try (NiftiWriter writer = create(path, header, extensions, options)) {
            writer.write(voxels);
            return writer.header();
        }
    }

    /** Writes a whole image whose voxels are already encoded in the header's byte order. */
    public static NiftiHeader writeRaw(Path path, NiftiHeader header,
                                       List<NiftiExtension> extensions, byte[] voxelBytes,
                                       Options options) throws IOException {
        try (NiftiWriter writer = create(path, header, extensions, options)) {
            writer.writeRaw(voxelBytes, 0, voxelBytes.length);
            return writer.header();
        }
    }

    /** A single-file image as bytes, uncompressed. */
    public static byte[] toBytes(NiftiHeader header, List<NiftiExtension> extensions,
                                 Object voxels) throws IOException {
        NiftiHeader resolved = resolve(header, extensions, true, false);
        NiftiDataType type = VoxelCodec.resolve(resolved);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writePrologue(out, resolved, extensions);
        try (NiftiWriter writer = new NiftiWriter(resolved, type,
                Bounds.voxelCount(resolved), out)) {
            writer.write(voxels);
        }
        return out.toByteArray();
    }

    /** A pair as bytes: {@code [0]} is the {@code .hdr}, {@code [1]} the {@code .img}. */
    public static byte[][] toPairBytes(NiftiHeader header, List<NiftiExtension> extensions,
                                       Object voxels) throws IOException {
        NiftiHeader resolved = resolve(header, extensions, false, false);
        NiftiDataType type = VoxelCodec.resolve(resolved);
        ByteArrayOutputStream headerOut = new ByteArrayOutputStream();
        writePrologue(headerOut, resolved, extensions);
        ByteArrayOutputStream voxelOut = new ByteArrayOutputStream();
        try (NiftiWriter writer = new NiftiWriter(resolved, type,
                Bounds.voxelCount(resolved), voxelOut)) {
            writer.write(voxels);
        }
        return new byte[][] {headerOut.toByteArray(), voxelOut.toByteArray()};
    }

    // =====================================================================
    // Streaming.
    // =====================================================================

    /**
     * Opens {@code path} for writing, emits the header and extensions, and
     * returns a writer that takes the voxels.
     *
     * <p>Closing it checks that as many voxels arrived as the header
     * describes: a volume written short would otherwise be a file whose
     * header lies about it, which is exactly the kind of file this module
     * spends its time defending against.</p>
     */
    public static NiftiWriter create(Path path, NiftiHeader header,
                                     List<NiftiExtension> extensions, Options options)
            throws IOException {
        boolean single = switch (options.layout()) {
            case SINGLE_FILE -> true;
            case PAIR -> false;
            case FROM_NAME -> !NiftiFiles.isHeaderName(path) && !NiftiFiles.isImageName(path);
        };
        boolean gzip = switch (options.compression()) {
            case GZIP -> true;
            case NONE -> false;
            case FROM_NAME -> NiftiFiles.hasGzipSuffix(path);
        };

        NiftiHeader resolved = resolve(header, extensions, single, options.strictVersion());
        NiftiDataType type = VoxelCodec.resolve(resolved);
        long voxels = Bounds.voxelCount(resolved);

        if (single) {
            OutputStream out = open(path, gzip);
            try {
                writePrologue(out, resolved, extensions);
            } catch (IOException | RuntimeException e) {
                out.close();
                throw e;
            }
            return new NiftiWriter(resolved, type, voxels, out);
        }

        Path[] names = NiftiFiles.pairNamesFor(path, gzip);
        try (OutputStream headerOut = open(names[0], gzip)) {
            writePrologue(headerOut, resolved, extensions);
        }
        OutputStream voxelOut = open(names[1], gzip);
        return new NiftiWriter(resolved, type, voxels, voxelOut);
    }

    private static OutputStream open(Path path, boolean gzip) throws IOException {
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(path), 1 << 16);
        return gzip ? new GZIPOutputStream(out, 1 << 16) : out;
    }

    /** The header as it was written: the version chosen, and the derived fields filled in. */
    public NiftiHeader header() {
        return header;
    }

    /** Appends every voxel in {@code samples}, an array of this image's natural type. */
    public void write(Object samples) throws IOException {
        int components = java.lang.reflect.Array.getLength(samples);
        if (components % type.components != 0) {
            throw new IOException(components + " components is not a whole number of "
                    + type + " voxels");
        }
        write(samples, 0, components / type.components);
    }

    /** Appends {@code voxels} voxels from {@code samples}, starting at component {@code offset}. */
    public void write(Object samples, int offset, int voxels) throws IOException {
        checkOpen();
        byte[] encoded = new byte[Math.multiplyExact(voxels, type.bytesPerVoxel())];
        VoxelCodec.encode(samples, offset, type, header.byteOrder, encoded, 0, voxels);
        appendRaw(encoded, 0, encoded.length, voxels);
    }

    /**
     * Appends voxels already encoded in this image's type and byte order —
     * the way to copy voxels between files without decoding them, and the only
     * way to write the types this module does not decode.
     */
    public void writeRaw(byte[] bytes, int offset, int length) throws IOException {
        checkOpen();
        int bytesPerVoxel = type.bytesPerVoxel();
        if (length % bytesPerVoxel != 0) {
            throw new IOException(length + " bytes is not a whole number of " + type
                    + " voxels (" + bytesPerVoxel + " bytes each)");
        }
        appendRaw(bytes, offset, length, length / bytesPerVoxel);
    }

    private void appendRaw(byte[] bytes, int offset, int length, long voxels)
            throws IOException {
        if (writtenVoxels + voxels > expectedVoxels) {
            throw new IOException("the header describes " + expectedVoxels
                    + " voxels and " + (writtenVoxels + voxels) + " have been offered");
        }
        voxelStream.write(bytes, offset, length);
        writtenVoxels += voxels;
    }

    /** How many voxels have been written so far. */
    public long writtenVoxels() {
        return writtenVoxels;
    }

    /** How many the header says there are. */
    public long expectedVoxels() {
        return expectedVoxels;
    }

    private void checkOpen() throws IOException {
        if (closed) {
            throw new IOException("this writer is closed");
        }
    }

    /**
     * Finishes the file, and fails if fewer voxels arrived than the header
     * describes. The stream is closed either way — a short file on disk is
     * better than a leaked handle, and the exception says which it is.
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        voxelStream.close();
        if (writtenVoxels != expectedVoxels) {
            throw new IOException("the header describes " + expectedVoxels
                    + " voxels and only " + writtenVoxels + " were written");
        }
    }

    // =====================================================================
    // Resolving the header that will actually be written.
    // =====================================================================

    /**
     * The version this header can be written as: NIfTI-1 if every field fits
     * it, NIfTI-2 otherwise.
     *
     * <p>The fit is decided by attempting the NIfTI-1 encode rather than by a
     * second list of the limits, so the answer and the encoder cannot drift
     * apart. A header already asking for NIfTI-2 stays there.</p>
     */
    public static NiftiVersion smallestVersion(NiftiHeader header) {
        if (header.version != NiftiVersion.NIFTI1) {
            return header.version;
        }
        NiftiHeader trial = header.copy();
        trial.version = NiftiVersion.NIFTI1;
        try {
            NiftiHeaderCodec.encode(trial);
            return NiftiVersion.NIFTI1;
        } catch (IOException doesNotFit) {
            return NiftiVersion.NIFTI2;
        }
    }

    /**
     * A copy of {@code header} with the version chosen, the layout set,
     * {@code bitpix} checked and {@code vox_offset} derived — the header that
     * will be written, which {@link #header()} hands back.
     */
    static NiftiHeader resolve(NiftiHeader header, List<NiftiExtension> extensions,
                               boolean singleFile, boolean strictVersion) throws IOException {
        NiftiHeader out = header.copy();
        if (!out.version.writable()) {
            throw new IOException(out.version + " is read-only; write NIfTI-1 or NIfTI-2 instead");
        }
        out.singleFile = singleFile;

        NiftiDataType type = VoxelCodec.resolve(out);
        out.bitpix = type.bitpix;
        Bounds.voxelCount(out);

        long required = singleFile
                ? NiftiExtensions.voxOffsetFor(out.version, extensions) : 0;
        if (out.voxOffset == 0) {
            out.voxOffset = required;
        } else if (out.voxOffset < required) {
            throw new IOException("vox_offset is " + out.voxOffset
                    + " but the header and its extensions need " + required);
        }

        if (!strictVersion) {
            NiftiVersion chosen = smallestVersion(out);
            if (chosen != out.version) {
                out.version = chosen;
                // the new header is longer, so the offset the old one implied
                // no longer clears it
                long grown = singleFile
                        ? NiftiExtensions.voxOffsetFor(chosen, extensions) : 0;
                if (out.voxOffset < grown) {
                    out.voxOffset = grown;
                }
            }
        }
        // the encode below is what refuses a header that cannot be written at
        // all, with the offending field named
        NiftiHeaderCodec.encode(out);
        return out;
    }

    private static void writePrologue(OutputStream out, NiftiHeader header,
                                      List<NiftiExtension> extensions) throws IOException {
        byte[] head = NiftiHeaderCodec.encode(header);
        byte[] chain = NiftiExtensions.encode(extensions, header.byteOrder);
        out.write(head);
        out.write(chain);
        if (header.singleFile) {
            long padding = header.voxOffset - head.length - chain.length;
            byte[] zeros = new byte[(int) Math.min(padding, 1 << 16)];
            while (padding > 0) {
                int block = (int) Math.min(padding, zeros.length);
                out.write(zeros, 0, block);
                padding -= block;
            }
        }
    }
}
