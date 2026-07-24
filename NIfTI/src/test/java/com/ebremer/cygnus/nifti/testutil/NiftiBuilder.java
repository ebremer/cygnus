package com.ebremer.cygnus.nifti.testutil;

import com.ebremer.cygnus.nifti.NiftiExtension;
import com.ebremer.cygnus.nifti.NiftiExtensions;
import com.ebremer.cygnus.nifti.NiftiHeader;
import com.ebremer.cygnus.nifti.NiftiHeaderCodec;
import com.ebremer.cygnus.nifti.NiftiVersion;
import com.ebremer.cygnus.nifti.io.VoxelCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Synthesizes NIfTI files for the tests, byte by byte, without going through
 * the module's own writer — so a test that reads one is testing the reader
 * rather than agreeing with the writer about a shared mistake.
 */
public final class NiftiBuilder {

    private NiftiBuilder() {
    }

    /** A single-file image: header, extension chain, padding to {@code vox_offset}, voxels. */
    public static byte[] singleFile(NiftiHeader header, List<NiftiExtension> extensions,
                                    byte[] voxels) throws IOException {
        NiftiHeader h = header.copy();
        h.singleFile = true;
        h.voxOffset = NiftiExtensions.voxOffsetFor(h.version, extensions);
        return assemble(h, extensions, voxels, h.voxOffset);
    }

    /**
     * A single-file image whose {@code vox_offset} is {@code voxOffset}
     * whatever the extensions say, for the tests that need the two to
     * disagree or to leave a gap.
     */
    public static byte[] singleFileAt(NiftiHeader header, List<NiftiExtension> extensions,
                                      byte[] voxels, long voxOffset) throws IOException {
        NiftiHeader h = header.copy();
        h.singleFile = true;
        h.voxOffset = voxOffset;
        return assemble(h, extensions, voxels, voxOffset);
    }

    private static byte[] assemble(NiftiHeader h, List<NiftiExtension> extensions,
                                   byte[] voxels, long voxOffset) throws IOException {
        byte[] head = NiftiHeaderCodec.encode(h);
        byte[] chain = NiftiExtensions.encode(extensions, h.byteOrder);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(head);
        out.write(chain);
        for (long i = head.length + chain.length; i < voxOffset; i++) {
            out.write(0);
        }
        out.write(voxels);
        return out.toByteArray();
    }

    /** A pair: {@code [0]} is the {@code .hdr} bytes, {@code [1]} the {@code .img} bytes. */
    public static byte[][] pair(NiftiHeader header, List<NiftiExtension> extensions,
                                byte[] voxels) throws IOException {
        NiftiHeader h = header.copy();
        h.singleFile = false;
        h.voxOffset = 0;
        ByteArrayOutputStream hdr = new ByteArrayOutputStream();
        hdr.write(NiftiHeaderCodec.encode(h));
        hdr.write(NiftiExtensions.encode(extensions, h.byteOrder));
        return new byte[][] {hdr.toByteArray(), voxels};
    }

    /**
     * An ANALYZE 7.5 header: 348 bytes with the fields ANALYZE shares with
     * NIfTI-1 filled in and no magic, which is what makes it ANALYZE.
     */
    public static byte[] analyzeHeader(ByteOrder order, int datatype, int bitpix,
                                       short[] dim, float[] pixdim) {
        ByteBuffer b = ByteBuffer.allocate(348).order(order);
        b.putInt(0, 348);
        b.putInt(32, 16384);            // extents, as ANALYZE writes it
        b.put(38, (byte) 'r');          // regular
        for (int i = 0; i < 8 && i < dim.length; i++) {
            b.putShort(40 + 2 * i, dim[i]);
        }
        b.putShort(70, (short) datatype);
        b.putShort(72, (short) bitpix);
        for (int i = 0; i < 8 && i < pixdim.length; i++) {
            b.putFloat(76 + 4 * i, pixdim[i]);
        }
        b.putFloat(108, 0);             // vox_offset: the .img starts at its own beginning
        return b.array();
    }

    /** Voxel bytes whose value is a function of the voxel's position, so a wrong stride shows. */
    public static byte[] ramp(com.ebremer.cygnus.nifti.NiftiDataType type, ByteOrder order,
                              int voxels) throws IOException {
        Object samples = VoxelCodec.allocate(type, voxels);
        int components = voxels * type.components;
        for (int i = 0; i < components; i++) {
            VoxelCodec.fromDouble(samples, i, i % 100 + 1, type);
        }
        return VoxelCodec.encode(samples, type, order, voxels);
    }

    /**
     * The value a coordinate-encoding volume holds at {@code coords}: one
     * decimal digit per dimension, {@code i} in the units place. A voxel at
     * (3, 1, 4) holds 413, so a read that came back off by a stride says so on
     * sight rather than merely being unequal.
     */
    public static double coordinateValue(long... coords) {
        double value = 0;
        double place = 1;
        for (long c : coords) {
            value += c * place;
            place *= 10;
        }
        return value;
    }

    /**
     * A whole voxel array of {@link #coordinateValue}s, in NIfTI storage order
     * — {@code i} fastest. Single-component types only; the point is one
     * number per voxel.
     */
    public static byte[] coordinateVoxels(com.ebremer.cygnus.nifti.NiftiDataType type,
                                          ByteOrder order, long... shape) throws IOException {
        if (type.components != 1) {
            throw new IllegalArgumentException(type + " has more than one component per voxel");
        }
        int voxels = 1;
        for (long s : shape) {
            voxels = Math.multiplyExact(voxels, Math.toIntExact(s));
        }
        Object samples = VoxelCodec.allocate(type, voxels);
        long[] coords = new long[shape.length];
        for (int n = 0; n < voxels; n++) {
            VoxelCodec.fromDouble(samples, n, coordinateValue(coords), type);
            for (int d = 0; d < shape.length; d++) {
                if (++coords[d] < shape[d]) {
                    break;
                }
                coords[d] = 0;
            }
        }
        return VoxelCodec.encode(samples, type, order, voxels);
    }

    /** {@code data} gzipped, as {@code .nii.gz} holds it. */
    public static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    /** Writes {@code data} to {@code path} and returns the path. */
    public static Path write(Path path, byte[] data) throws IOException {
        Files.write(path, data);
        return path;
    }
}
