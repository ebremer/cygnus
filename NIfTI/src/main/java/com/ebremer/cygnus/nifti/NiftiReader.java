package com.ebremer.cygnus.nifti;

import com.ebremer.cygnus.nifti.io.Bounds;
import com.ebremer.cygnus.nifti.io.VoxelCodec;
import com.ebremer.cygnus.nifti.io.VoxelSource;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Reads a NIfTI image: its header and extensions, its geometry, and any part
 * of its voxel array.
 *
 * <p>Opening one reads the header and stops. Voxels are read when asked for,
 * and only the ones asked for: {@link #read(long[], long[])} takes a
 * hyperslab and touches nothing outside it, so a single slice of a 4-D run
 * costs a slice, not a run. Every offset is computed in {@code long}, because
 * a NIfTI-2 volume can hold more voxels than an {@code int} can count — that
 * is what the version exists for — even though any one read has to fit in a
 * Java array.</p>
 *
 * <h2>What "open" covers</h2>
 *
 * <p>{@link #open(Path)} takes any of the four layouts and works out which it
 * is from the file rather than from its name: {@code .nii}, {@code .nii.gz},
 * a {@code .hdr}/{@code .img} pair either compressed or not, and ANALYZE 7.5.
 * Handed the {@code .img} of a pair it reads the {@code .hdr} beside it,
 * since raw voxels say nothing about themselves.</p>
 *
 * <p>{@link #readHeader(Path)} is the cheap path when only the header is
 * wanted: a compressed file gives up its 540 bytes after a few kilobytes of
 * input rather than being inflated whole.</p>
 *
 * <h2>Storage order</h2>
 *
 * <p>Voxels are stored with {@code i} varying fastest: voxel
 * <i>(i, j, k, t, ...)</i> is at
 * {@code i + j*dim[1] + k*dim[1]*dim[2] + ...}. Every array this class hands
 * back is in that same order, and for a multi-component type the components
 * of a voxel are together (see {@link VoxelCodec}).</p>
 *
 * <p>Instances are not safe for use by several threads at once.</p>
 */
public final class NiftiReader implements Closeable {

    /** Bytes read per pass when a run is longer than one buffer. */
    private static final int CHUNK_BYTES = 1 << 20;

    private final NiftiHeader header;
    private final List<NiftiExtension> extensions;
    private final NiftiDataType type;
    private final VoxelSource voxels;
    private final long voxelBase;
    private final long[] shape;
    private final long[] strides;
    private final int rank;
    private boolean closed;

    private NiftiReader(NiftiHeader header, List<NiftiExtension> extensions,
                        NiftiDataType type, VoxelSource voxels, long voxelBase) {
        this.header = header;
        this.extensions = extensions;
        this.type = type;
        this.voxels = voxels;
        this.voxelBase = voxelBase;
        this.rank = header.numDimensions();
        this.shape = new long[rank];
        this.strides = new long[rank];
        long stride = 1;
        for (int d = 0; d < rank; d++) {
            shape[d] = header.effectiveDim(d + 1);
            strides[d] = stride;
            stride *= shape[d];
        }
    }

    // =====================================================================
    // Opening.
    // =====================================================================

    /**
     * Opens the image at {@code path}, whichever of the four layouts it is.
     *
     * <p>Given the {@code .img} of a pair this opens the {@code .hdr} beside
     * it instead, because a file of raw voxels says nothing about itself.</p>
     */
    public static NiftiReader open(Path path) throws IOException {
        Path headerPath = NiftiFiles.isImageName(path)
                ? requireReadable(NiftiFiles.headerFileFor(path), path)
                : path;

        byte[] peeked = VoxelSource.peek(headerPath, NiftiHeaderCodec.NIFTI2_HEADER_SIZE);
        NiftiHeaderCodec.Layout layout = NiftiHeaderCodec.detect(peeked);
        NiftiHeader header = NiftiHeaderCodec.decode(peeked, layout);
        NiftiDataType type = VoxelCodec.resolve(header);

        if (layout.singleFile()) {
            VoxelSource source = VoxelSource.open(headerPath);
            try {
                Bounds.checkVolumeFits(header, type, source.length());
                List<NiftiExtension> extensions = readExtensions(source, layout.headerSize(),
                        header.voxOffset, header.byteOrder);
                return new NiftiReader(header, extensions, type, source, header.voxOffset);
            } catch (IOException | RuntimeException e) {
                source.close();
                throw e;
            }
        }

        List<NiftiExtension> extensions;
        try (VoxelSource headerSource = VoxelSource.open(headerPath)) {
            extensions = readExtensions(headerSource, layout.headerSize(),
                    headerSource.length(), header.byteOrder);
        }
        Path imagePath = imagePathFor(headerPath);
        VoxelSource source = VoxelSource.open(imagePath);
        try {
            Bounds.checkVolumeFits(header, type, source.length());
            return new NiftiReader(header, extensions, type, source, header.voxOffset);
        } catch (IOException | RuntimeException e) {
            source.close();
            throw e;
        }
    }

    /** Opens a single-file image already in memory. */
    public static NiftiReader open(byte[] image) throws IOException {
        NiftiHeaderCodec.Layout layout = NiftiHeaderCodec.detect(image);
        NiftiHeader header = NiftiHeaderCodec.decode(image, layout);
        if (!layout.singleFile()) {
            throw new IOException("these bytes are the header of a "
                    + layout.version() + " pair, whose voxels are in a separate file;"
                    + " use open(header, image)");
        }
        NiftiDataType type = VoxelCodec.resolve(header);
        VoxelSource source = VoxelSource.ofBytes(image);
        Bounds.checkVolumeFits(header, type, source.length());
        List<NiftiExtension> extensions = readExtensions(source, layout.headerSize(),
                header.voxOffset, header.byteOrder);
        return new NiftiReader(header, extensions, type, source, header.voxOffset);
    }

    /** Opens a pair already in memory: the {@code .hdr} bytes and the {@code .img} bytes. */
    public static NiftiReader open(byte[] headerBytes, byte[] imageBytes) throws IOException {
        NiftiHeaderCodec.Layout layout = NiftiHeaderCodec.detect(headerBytes);
        NiftiHeader header = NiftiHeaderCodec.decode(headerBytes, layout);
        NiftiDataType type = VoxelCodec.resolve(header);
        VoxelSource source = VoxelSource.ofBytes(imageBytes);
        Bounds.checkVolumeFits(header, type, source.length());
        List<NiftiExtension> extensions = readExtensions(
                VoxelSource.ofBytes(headerBytes), layout.headerSize(),
                headerBytes.length, header.byteOrder);
        return new NiftiReader(header, extensions, type, source, header.voxOffset);
    }

    /**
     * The header of {@code path} and nothing else, without inflating a
     * compressed volume or opening the other half of a pair.
     */
    public static NiftiHeader readHeader(Path path) throws IOException {
        Path headerPath = NiftiFiles.isImageName(path)
                ? requireReadable(NiftiFiles.headerFileFor(path), path)
                : path;
        return NiftiHeaderCodec.decode(
                VoxelSource.peek(headerPath, NiftiHeaderCodec.NIFTI2_HEADER_SIZE));
    }

    private static Path requireReadable(Path candidate, Path asked) throws IOException {
        if (!Files.isReadable(candidate)) {
            throw new IOException(asked.getFileName()
                    + " holds only raw voxels; its header should be "
                    + candidate.getFileName() + ", which is not there");
        }
        return candidate;
    }

    private static Path imagePathFor(Path headerPath) throws IOException {
        if (NiftiFiles.isHeaderName(headerPath)) {
            return NiftiFiles.findImageFile(headerPath);
        }
        // the magic says pair but the name is not a .hdr; derive the pair's names
        Path[] names = NiftiFiles.pairNamesFor(headerPath, NiftiFiles.hasGzipSuffix(headerPath));
        if (Files.isReadable(names[1])) {
            return names[1];
        }
        throw new IOException(headerPath.getFileName()
                + " says its voxels are in a separate file, and "
                + names[1].getFileName() + " is not there");
    }

    private static List<NiftiExtension> readExtensions(VoxelSource source, int headerSize,
                                                       long limit, java.nio.ByteOrder order)
            throws IOException {
        long end = Math.min(limit, source.length());
        int region = Bounds.extensionRegion(headerSize, end);
        if (region < NiftiExtensions.MARKER_LENGTH) {
            return List.of();
        }
        byte[] bytes = new byte[headerSize + region];
        source.readFully(headerSize, bytes, headerSize, region);
        return NiftiExtensions.decode(bytes, headerSize, bytes.length, order);
    }

    // =====================================================================
    // What the header says.
    // =====================================================================

    /** The header, as read. Mutating it does not change what this reader does. */
    public NiftiHeader header() {
        return header;
    }

    /** The extensions, in the order they appeared. */
    public List<NiftiExtension> extensions() {
        return extensions;
    }

    /** The voxel type. */
    public NiftiDataType dataType() {
        return type;
    }

    /** The voxel-to-world mapping, by the usual precedence. See {@link NiftiAffine#of}. */
    public NiftiAffine affine() {
        return NiftiAffine.of(header);
    }

    /** Which transform {@link #affine()} came from. */
    public NiftiAffine.Source affineSource() {
        return NiftiAffine.sourceOf(header);
    }

    /** The extents of dimensions 1 to {@code dim[0]}, with the zeroes real files write counted as one. */
    public long[] shape() {
        return shape.clone();
    }

    /** How many dimensions are in use: {@code dim[0]}. */
    public int rank() {
        return rank;
    }

    /** How many voxels the image holds. May exceed what one array can index. */
    public long voxelCount() {
        return header.voxelCount();
    }

    /** How many 2-D {@code dim[1]}x{@code dim[2]} slices: the product of dimensions 3 and up. */
    public long sliceCount() {
        return header.sliceCount();
    }

    /** How many 3-D volumes: the product of dimensions 4 and up. */
    public long volumeCount() {
        long n = 1;
        for (int d = 3; d < rank; d++) {
            n *= shape[d];
        }
        return n;
    }

    // =====================================================================
    // Reading voxels.
    // =====================================================================

    /**
     * A hyperslab, as an array of this image's natural type (see
     * {@link VoxelCodec#allocate}). Both arrays are as long as
     * {@link #rank()}, in dimension order 1..{@code dim[0]}.
     *
     * <p>Only the bytes inside the slab are read. Runs that are contiguous in
     * storage order are read in one go, so a whole volume, a whole slice or a
     * whole image costs one traversal rather than one per row.</p>
     */
    public Object read(long[] origin, long[] extent) throws IOException {
        Plan plan = plan(origin, extent);
        Object dst = VoxelCodec.allocate(type, plan.voxels);
        if (plan.voxels == 0) {
            return dst;
        }
        int bytesPerVoxel = type.bytesPerVoxel();
        int chunkVoxels = Math.max(1, CHUNK_BYTES / bytesPerVoxel);
        byte[] buffer = new byte[(int) Math.min(plan.runVoxels, chunkVoxels) * bytesPerVoxel];
        int[] cursor = {0};
        traverse(plan, (startVoxel, runVoxels) -> {
            long remaining = runVoxels;
            long at = startVoxel;
            while (remaining > 0) {
                int chunk = (int) Math.min(remaining, buffer.length / bytesPerVoxel);
                voxels.readFully(voxelBase + at * bytesPerVoxel, buffer, 0, chunk * bytesPerVoxel);
                VoxelCodec.decode(buffer, 0, type, header.byteOrder, dst, cursor[0], chunk);
                cursor[0] += chunk * type.components;
                at += chunk;
                remaining -= chunk;
            }
        });
        return dst;
    }

    /**
     * A hyperslab as raw bytes, in the file's byte order and without being
     * decoded — the way to reach the types this module does not decode
     * (see {@link NiftiDataType}), and to copy voxels between files untouched.
     */
    public byte[] readRawBytes(long[] origin, long[] extent) throws IOException {
        Plan plan = plan(origin, extent);
        int bytesPerVoxel = type.bytesPerVoxel();
        byte[] dst = new byte[Bounds.arrayLength(
                plan.voxels * (long) bytesPerVoxel, "the requested slab in bytes")];
        int[] cursor = {0};
        traverse(plan, (startVoxel, runVoxels) -> {
            int length = Bounds.arrayLength(runVoxels * (long) bytesPerVoxel, "a run");
            voxels.readFully(voxelBase + startVoxel * bytesPerVoxel, dst, cursor[0], length);
            cursor[0] += length;
        });
        return dst;
    }

    /**
     * A hyperslab widened to {@code double} and put through
     * {@code scl_slope}/{@code scl_inter} — the values the file is a record
     * of, rather than the numbers it stores. See
     * {@link NiftiHeader#scalingApplies()}.
     */
    public double[] readScaled(long[] origin, long[] extent) throws IOException {
        Plan plan = plan(origin, extent);
        Object raw = read(origin, extent);
        return VoxelCodec.toScaledDoubles(raw,
                Bounds.arrayLength(plan.voxels * (long) type.components, "the requested slab"),
                type, header);
    }

    /** The whole voxel array. Fails rather than truncating if it is larger than one array. */
    public Object readAll() throws IOException {
        return read(new long[rank], shape.clone());
    }

    /**
     * The {@code sliceIndex}-th 2-D {@code dim[1]}x{@code dim[2]} plane,
     * counting in storage order — k fastest, then t, then the rest. This is
     * the numbering the ImageIO plug-in exposes as image indices.
     */
    public Object readSlice(long sliceIndex) throws IOException {
        return read(sliceOrigin(sliceIndex), sliceExtent());
    }

    /** The {@code sliceIndex}-th plane as raw bytes. */
    public byte[] readSliceRawBytes(long sliceIndex) throws IOException {
        return readRawBytes(sliceOrigin(sliceIndex), sliceExtent());
    }

    /** The {@code sliceIndex}-th plane, scaled. */
    public double[] readSliceScaled(long sliceIndex) throws IOException {
        return readScaled(sliceOrigin(sliceIndex), sliceExtent());
    }

    /**
     * The {@code volumeIndex}-th 3-D volume: dimensions 1 to 3 whole, at the
     * {@code volumeIndex}-th combination of dimensions 4 and up.
     */
    public Object readVolume(long volumeIndex) throws IOException {
        return read(volumeOrigin(volumeIndex), volumeExtent());
    }

    /**
     * Where the {@code sliceIndex}-th plane starts, as an origin for
     * {@link #read}: {@code {0, 0, k, t, ...}}.
     */
    public long[] sliceOrigin(long sliceIndex) {
        return originOf(sliceIndex, 2, sliceCount(), "slice");
    }

    /** The extent of one plane: {@code {dim[1], dim[2], 1, 1, ...}}. */
    public long[] sliceExtent() {
        return extentOf(2);
    }

    /** Where the {@code volumeIndex}-th volume starts. */
    public long[] volumeOrigin(long volumeIndex) {
        return originOf(volumeIndex, 3, volumeCount(), "volume");
    }

    /** The extent of one volume: {@code {dim[1], dim[2], dim[3], 1, ...}}. */
    public long[] volumeExtent() {
        return extentOf(3);
    }

    /**
     * The flattened index of the plane at {@code higher} — the inverse of
     * {@link #sliceOrigin}. {@code higher} holds the indices of dimensions 3
     * and up, k first.
     */
    public long sliceIndexOf(long... higher) {
        long index = 0;
        long stride = 1;
        for (int d = 2; d < rank; d++) {
            long at = d - 2 < higher.length ? higher[d - 2] : 0;
            index += at * stride;
            stride *= shape[d];
        }
        return index;
    }

    private long[] originOf(long index, int firstFree, long count, String what) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(what + " " + index + " of " + count);
        }
        long[] origin = new long[rank];
        long left = index;
        for (int d = firstFree; d < rank; d++) {
            origin[d] = left % shape[d];
            left /= shape[d];
        }
        return origin;
    }

    private long[] extentOf(int firstFree) {
        long[] extent = new long[rank];
        Arrays.fill(extent, 1);
        for (int d = 0; d < firstFree && d < rank; d++) {
            extent[d] = shape[d];
        }
        return extent;
    }

    // =====================================================================
    // Traversal.
    // =====================================================================

    /**
     * A checked slab request: how many voxels, how long a contiguous run is,
     * and which dimension the traversal has to step through.
     */
    private record Plan(long[] origin, long[] extent, int voxels,
                        long runVoxels, long runStart, int firstStepped) {
    }

    private interface Run {
        void read(long startVoxel, long runVoxels) throws IOException;
    }

    private Plan plan(long[] origin, long[] extent) throws IOException {
        if (closed) {
            throw new IOException("this reader is closed");
        }
        if (origin.length != rank || extent.length != rank) {
            throw new IOException("this image has " + rank + " dimensions; the slab was given "
                    + origin.length + " origins and " + extent.length + " extents");
        }
        long voxels = 1;
        for (int d = 0; d < rank; d++) {
            Bounds.checkRange(origin[d], extent[d], shape[d],
                    "dimension " + (d + 1) + " of the slab");
            voxels = Math.multiplyExact(voxels, extent[d]);
        }
        int count = Bounds.readableVoxels(voxels, type, "the requested slab");

        // Dimensions that are taken whole, from the start, are contiguous with
        // the one above them; the run absorbs them, and the partial dimension
        // above the last of them too, since its stride is exactly their size.
        int whole = 0;
        while (whole < rank && origin[whole] == 0 && extent[whole] == shape[whole]) {
            whole++;
        }
        long runVoxels = 1;
        for (int d = 0; d <= whole && d < rank; d++) {
            runVoxels *= extent[d];
        }
        long runStart = whole < rank ? origin[whole] * strides[whole] : 0;
        return new Plan(origin, extent, count, runVoxels, runStart, whole + 1);
    }

    /** Walks the slab, handing {@code run} each contiguous stretch in storage order. */
    private void traverse(Plan plan, Run run) throws IOException {
        if (plan.voxels == 0) {
            return;
        }
        long runs = plan.voxels / plan.runVoxels;
        long[] cursor = new long[rank];
        for (long r = 0; r < runs; r++) {
            long at = plan.runStart;
            for (int d = plan.firstStepped; d < rank; d++) {
                at += (plan.origin[d] + cursor[d]) * strides[d];
            }
            run.read(at, plan.runVoxels);
            for (int d = plan.firstStepped; d < rank; d++) {
                if (++cursor[d] < plan.extent[d]) {
                    break;
                }
                cursor[d] = 0;
            }
        }
    }

    // =====================================================================

    /** Closes the file, and deletes any temporary one an inflate left behind. */
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            voxels.close();
        }
    }

    @Override
    public String toString() {
        return "NiftiReader[" + header + "]";
    }
}
