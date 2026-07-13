package com.ebremer.cygnus.ndpi;

import com.twelvemonkeys.imageio.stream.ByteArrayImageInputStream;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;

/**
 * Reads a level of an NDPI slide, whose pixels are one JPEG far larger than a
 * JPEG is allowed to be.
 *
 * <p>Hamamatsu makes that readable by placing a restart marker every
 * {@code restartInterval} MCUs and recording, in {@link Ndpi#TAG_MCU_STARTS},
 * the byte offset of each one. A restart interval is therefore a tile —
 * {@code restartInterval} MCUs wide and one MCU tall — and, because a restart
 * marker resets the entropy coder and the DC predictors, its bytes decode on
 * their own.</p>
 *
 * <p>So a tile is read by building a small, entirely ordinary JPEG out of it:
 * the level's own header with the SOF's dimensions rewritten to the tile's,
 * followed by the tile's entropy data, followed by an EOI. Any JPEG decoder
 * will read that. (OpenSlide instead patches the SOF to libjpeg's maximum and
 * decodes a prefix of a nominally 65500-pixel-wide image, which a Java decoder
 * would try to allocate.)</p>
 */
public final class NdpiTileDecoder {

    /** Refuses to allocate for an implausible strip or tile length. */
    private static final int MAX_TILE_BYTES = 1 << 28;

    /** Reports decoded tiles; returning false aborts the read. */
    public interface Progress {
        boolean tileDecoded(int done, int total);
    }

    /** How a directory stores its pixels. */
    private enum Kind {
        /** One JPEG, cut into tiles by restart markers. Every pyramid level of a slide. */
        JPEG,
        /** Raw 8-bit samples. The map image, and only ever a small one. */
        RAW,
        /** A codec this reader does not have — in practice JPEG XR. */
        UNREADABLE,
    }

    private final ImageInputStream stream;
    private final NdpiDirectory directory;
    private final ImageReader jpeg;
    private final Kind kind;
    private final int compression;
    private final JpegHeader header;      // null unless JPEG
    private final long stripOffset;
    private final long stripEnd;
    private final int bands;
    private final boolean whiteIsZero;
    private final int width;
    private final int height;
    private final int tileWidth;
    private final int tileHeight;
    private final int tilesAcross;
    private final int tilesDown;

    private long[] mcuStarts;
    private List<ImageTypeSpecifier> imageTypes;

    public NdpiTileDecoder(ImageInputStream stream, NdpiDirectory directory, ImageReader jpeg)
            throws IOException {
        this.stream = stream;
        this.directory = directory;
        this.jpeg = jpeg;

        this.compression = directory.compression();
        this.kind = switch (compression) {
            case Ndpi.COMPRESSION_JPEG -> Kind.JPEG;
            case Ndpi.COMPRESSION_NONE -> Kind.RAW;
            default -> Kind.UNREADABLE;
        };
        this.whiteIsZero =
                directory.integer(Ndpi.TAG_PHOTOMETRIC_INTERPRETATION).orElse(1) == 0;

        if (kind == Kind.UNREADABLE) {
            // Say what we can about it anyway: the geometry is all in the tags, so a caller
            // can still see the slide's shape, and only asking for pixels fails.
            this.header = null;
            this.stripOffset = -1;
            this.stripEnd = -1;
            this.bands = directory.samplesPerPixel();
            this.width = directory.width();
            this.height = directory.height();
            this.tileWidth = width;
            this.tileHeight = height;
            this.tilesAcross = 1;
            this.tilesDown = 1;
            return;
        }

        this.stripOffset = directory.stripOffset();
        this.stripEnd = stripOffset + directory.stripByteCount();

        if (kind == Kind.RAW) {
            this.header = null;
            this.bands = directory.samplesPerPixel();
            this.width = directory.width();
            this.height = directory.height();
            this.tileWidth = width;
            this.tileHeight = height;
            this.tilesAcross = 1;
            this.tilesDown = 1;
            long needed = (long) width * height * bands;
            if (needed > stripEnd - stripOffset) {
                throw new IIOException("NDPI directory " + directory.index() + " is "
                        + width + "x" + height + "x" + bands + " uncompressed, which needs "
                        + needed + " bytes, but its strip holds " + (stripEnd - stripOffset));
            }
            return;
        }

        this.header = JpegHeader.read(stream, stripOffset, stripEnd);
        this.bands = header.components();

        int restartInterval = header.restartInterval();
        if (restartInterval == 0) {
            // No restart markers: an ordinary JPEG, and so necessarily one a JPEG can
            // describe. Its own dimensions are the reliable ones — the TIFF's disagree
            // at the deepest levels.
            if (header.width() <= 0 || header.height() <= 0
                    || header.width() > Ndpi.JPEG_MAX_DIMENSION
                    || header.height() > Ndpi.JPEG_MAX_DIMENSION) {
                throw new IIOException("NDPI directory " + directory.index() + " declares "
                        + header.width() + "x" + header.height() + ", which no JPEG can hold, "
                        + "and has no restart markers to read it in pieces");
            }
            this.width = header.width();
            this.height = header.height();
            this.tileWidth = width;
            this.tileHeight = height;
            this.tilesAcross = 1;
            this.tilesDown = 1;
            return;
        }

        // The level is too large for its SOF to describe, so the TIFF tags are the truth
        // and the restart intervals are the tiles.
        this.width = directory.width();
        this.height = directory.height();
        int mcuWidth = header.mcuWidth();
        int mcusAcross = Math.ceilDiv(width, mcuWidth);
        if (restartInterval > mcusAcross) {
            throw new IIOException("Restart interval " + restartInterval + " exceeds the "
                    + mcusAcross + " MCUs of a row in NDPI directory " + directory.index());
        }
        if (mcusAcross % restartInterval != 0) {
            throw new IIOException("Restart interval " + restartInterval + " does not divide the "
                    + mcusAcross + " MCUs of a row in NDPI directory " + directory.index()
                    + ", so the restart markers do not line up with the tile grid");
        }
        this.tileWidth = restartInterval * mcuWidth;
        this.tileHeight = header.mcuHeight();
        this.tilesAcross = mcusAcross / restartInterval;
        this.tilesDown = Math.ceilDiv(height, tileHeight);
    }

    /** Fails with the codec's name, rather than a tag number, when we cannot read it. */
    private void requireReadable() throws IIOException {
        if (kind == Kind.UNREADABLE) {
            throw new IIOException("NDPI directory " + directory.index() + " is "
                    + Ndpi.compressionName(compression) + ", which this reader does not decode. "
                    + "Hamamatsu's newer scanners write levels as tiled JPEG XR rather than as "
                    + "one over-large JPEG; OpenSlide does not read those either.");
        }
    }

    /** Whether the pixels can be decoded at all; the geometry is readable regardless. */
    public boolean isReadable() {
        return kind != Kind.UNREADABLE;
    }

    /** The codec the directory's pixels are stored with. */
    public String compressionName() {
        return Ndpi.compressionName(compression);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int tileWidth() {
        return tileWidth;
    }

    public int tileHeight() {
        return tileHeight;
    }

    public int tilesAcross() {
        return tilesAcross;
    }

    public int tilesDown() {
        return tilesDown;
    }

    /** Whether the level is broken into restart intervals rather than being one JPEG. */
    public boolean isTiled() {
        return header != null && header.restartInterval() > 0;
    }

    /** Colour components: 3 for a scanned slide, 1 for a fluorescence channel or the map. */
    public int bands() {
        return bands;
    }

    /** The types the pixels can be decoded to. */
    public List<ImageTypeSpecifier> imageTypes() throws IOException {
        requireReadable();
        if (imageTypes == null) {
            imageTypes = kind == Kind.RAW ? List.of(rawImageType()) : jpegImageTypes();
        }
        return imageTypes;
    }

    private ImageTypeSpecifier rawImageType() throws IIOException {
        if (bands >= 3) {
            return ImageTypeSpecifier.createInterleaved(
                    ColorSpace.getInstance(ColorSpace.CS_sRGB), new int[] {0, 1, 2},
                    DataBuffer.TYPE_BYTE, false, false);
        }
        if (bands == 1) {
            return ImageTypeSpecifier.createGrayscale(8, DataBuffer.TYPE_BYTE, false);
        }
        throw new IIOException("NDPI directory " + directory.index() + " is uncompressed with "
                + bands + " samples per pixel");
    }

    /** What the JPEG decoder itself offers, asked of a tile so the answer is its own. */
    private List<ImageTypeSpecifier> jpegImageTypes() throws IOException {
        jpeg.setInput(new ByteArrayImageInputStream(tileJpeg(0)));
        try {
            List<ImageTypeSpecifier> types = new ArrayList<>();
            jpeg.getImageTypes(0).forEachRemaining(types::add);
            if (types.isEmpty()) {
                throw new IIOException("The JPEG decoder offers no type for NDPI directory "
                        + directory.index());
            }
            return List.copyOf(types);
        } finally {
            jpeg.setInput(null);
        }
    }

    /**
     * Decodes the tiles covering {@code srcRegion} straight into
     * {@code destRegion} of {@code dest}, subsampled by {@code subX}/{@code subY}.
     *
     * <p>{@code srcRegion} and {@code destRegion} are what
     * {@code ImageReader.computeRegions} produces, so the source pixel behind
     * destination column {@code dx} is {@code srcRegion.x + dx * subX}.</p>
     */
    public void readRegion(Rectangle srcRegion, int subX, int subY,
                           BufferedImage dest, Rectangle destRegion,
                           int[] srcBands, int[] destBands, Progress progress)
            throws IOException {
        requireReadable();
        if (kind == Kind.RAW) {
            readRaw(srcRegion, subX, subY, dest, destRegion, srcBands, destBands);
            if (progress != null) {
                progress.tileDecoded(1, 1);
            }
            return;
        }

        int firstTileX = srcRegion.x / tileWidth;
        int lastTileX = (srcRegion.x + srcRegion.width - 1) / tileWidth;
        int firstTileY = srcRegion.y / tileHeight;
        int lastTileY = (srcRegion.y + srcRegion.height - 1) / tileHeight;
        int total = (lastTileX - firstTileX + 1) * (lastTileY - firstTileY + 1);
        int done = 0;

        for (int tileY = firstTileY; tileY <= lastTileY; tileY++) {
            for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                int originX = tileX * tileWidth;
                int originY = tileY * tileHeight;
                // The bottom and right tiles are padded out to a whole tile in the file;
                // only the part inside the image is real.
                int lastX = Math.min(originX + tileWidth, width) - 1;
                int lastY = Math.min(originY + tileHeight, height) - 1;

                // Destination columns and rows whose source pixel lands in this tile.
                int fromX = Math.max(0, Math.ceilDiv(originX - srcRegion.x, subX));
                int toX = Math.min(destRegion.width - 1,
                        Math.floorDiv(lastX - srcRegion.x, subX));
                int fromY = Math.max(0, Math.ceilDiv(originY - srcRegion.y, subY));
                int toY = Math.min(destRegion.height - 1,
                        Math.floorDiv(lastY - srcRegion.y, subY));
                if (fromX > toX || fromY > toY) {
                    done++;
                    continue;
                }

                // Let the JPEG decoder do the cropping, the subsampling and the writing:
                // it can skip the inverse DCT outside the region, which we cannot.
                ImageReadParam param = jpeg.getDefaultReadParam();
                param.setSourceRegion(new Rectangle(
                        srcRegion.x + fromX * subX - originX,
                        srcRegion.y + fromY * subY - originY,
                        (toX - fromX) * subX + 1,
                        (toY - fromY) * subY + 1));
                param.setSourceSubsampling(subX, subY, 0, 0);
                param.setDestination(dest);
                param.setDestinationOffset(
                        new Point(destRegion.x + fromX, destRegion.y + fromY));
                if (srcBands != null) {
                    param.setSourceBands(srcBands);
                }
                if (destBands != null) {
                    param.setDestinationBands(destBands);
                }

                jpeg.setInput(new ByteArrayImageInputStream(
                        tileJpeg(tileY * tilesAcross + tileX)));
                try {
                    jpeg.read(0, param);
                } finally {
                    jpeg.setInput(null);
                }

                done++;
                if (progress != null && !progress.tileDecoded(done, total)) {
                    return;
                }
            }
        }
    }

    /** Raw 8-bit samples, row by row. Only the map image is stored this way. */
    private void readRaw(Rectangle srcRegion, int subX, int subY,
                         BufferedImage dest, Rectangle destRegion,
                         int[] srcBands, int[] destBands) throws IOException {
        WritableRaster raster = dest.getRaster();
        long rowLength = (long) width * bands;
        byte[] row = new byte[(int) rowLength];
        int[] pixel = new int[bands];
        boolean interleaved = srcBands == null && destBands == null
                && raster.getNumBands() == bands;
        int[] out = interleaved ? new int[destRegion.width * bands] : null;

        for (int dy = 0; dy < destRegion.height; dy++) {
            long y = srcRegion.y + (long) dy * subY;
            stream.seek(stripOffset + y * rowLength);
            stream.readFully(row);

            int at = 0;
            for (int dx = 0; dx < destRegion.width; dx++) {
                int x = srcRegion.x + dx * subX;
                for (int b = 0; b < bands; b++) {
                    int sample = row[x * bands + b] & 0xFF;
                    pixel[b] = whiteIsZero ? 255 - sample : sample;
                }
                if (interleaved) {
                    for (int b = 0; b < bands; b++) {
                        out[at++] = pixel[b];
                    }
                } else {
                    int count = srcBands != null ? srcBands.length
                            : (destBands != null ? destBands.length : bands);
                    for (int b = 0; b < count; b++) {
                        int from = srcBands != null ? srcBands[b] : b;
                        int to = destBands != null ? destBands[b] : b;
                        raster.setSample(destRegion.x + dx, destRegion.y + dy, to, pixel[from]);
                    }
                }
            }
            if (interleaved) {
                raster.setPixels(destRegion.x, destRegion.y + dy, destRegion.width, 1, out);
            }
        }
    }

    /**
     * An ordinary JPEG holding one tile: the level's header with the tile's
     * dimensions, the tile's entropy data, and an EOI.
     */
    public byte[] tileJpeg(int tileIndex) throws IOException {
        if (!isTiled()) {
            return read(stripOffset, stripEnd);   // already an ordinary JPEG
        }
        long[] starts = mcuStarts();
        if (tileIndex < 0 || tileIndex >= starts.length) {
            throw new IIOException("Tile " + tileIndex + " outside the " + starts.length
                    + " restart intervals of NDPI directory " + directory.index());
        }
        if (tileIndex > 0 && !isRestartMarkerBefore(starts[tileIndex])) {
            // The recorded table lied. Find the restart markers ourselves.
            starts = mcuStarts = scanRestartMarkers();
        }

        long start = starts[tileIndex];
        long end = tileIndex + 1 < starts.length ? starts[tileIndex + 1] : stripEnd;
        if (end <= start) {
            throw new IIOException("Tile " + tileIndex + " of NDPI directory "
                    + directory.index() + " is empty");
        }

        byte[] head = header.withSize(tileWidth, tileHeight);
        byte[] data = read(start, end);
        byte[] tile = Arrays.copyOf(head, head.length + data.length);
        System.arraycopy(data, 0, tile, head.length, data.length);

        // The tile's bytes run up to the next restart marker, or to the JPEG's own EOI for
        // the last one. Either way the last two bytes are a marker; make it an EOI.
        if (tile[tile.length - 2] == (byte) 0xFF) {
            tile[tile.length - 1] = (byte) 0xD9;
            return tile;
        }
        byte[] terminated = Arrays.copyOf(tile, tile.length + 2);
        terminated[terminated.length - 2] = (byte) 0xFF;
        terminated[terminated.length - 1] = (byte) 0xD9;
        return terminated;
    }

    // ---- where the tiles are ----

    private long[] mcuStarts() throws IOException {
        if (mcuStarts == null) {
            mcuStarts = readMcuStarts();
        }
        return mcuStarts;
    }

    /**
     * The recorded restart-marker offsets, or a scan of the entropy data when
     * the scanner left none, or left ones that do not hold up.
     */
    private long[] readMcuStarts() throws IOException {
        int count = tilesAcross * tilesDown;
        long[] low = directory.integers(Ndpi.TAG_MCU_STARTS);
        if (low == null || low.length != count) {
            return scanRestartMarkers();
        }
        long[] high = directory.integers(Ndpi.TAG_MCU_STARTS_HIGH);
        boolean wide = high != null && high.length == count;

        long[] starts = new long[count];
        for (int i = 0; i < count; i++) {
            // Recorded relative to the strip, and split in half for slides beyond 4 GiB.
            long offset = wide ? low[i] | (high[i] << 32) : low[i];
            starts[i] = stripOffset + offset;
        }
        if (starts[0] != stripOffset + header.length()) {
            return scanRestartMarkers();
        }
        return starts;
    }

    private boolean isRestartMarkerBefore(long start) throws IOException {
        if (start - 2 < stripOffset || start > stripEnd) {
            return false;
        }
        stream.seek(start - 2);
        int lead = stream.readUnsignedByte();
        int code = stream.readUnsignedByte();
        return lead == 0xFF && code >= 0xD0 && code <= 0xD7;
    }

    /** Walks the entropy data for restart markers, skipping stuffed bytes. */
    private long[] scanRestartMarkers() throws IOException {
        int count = tilesAcross * tilesDown;
        long[] starts = new long[count];
        starts[0] = stripOffset + header.length();

        int found = 1;
        long position = starts[0];
        stream.seek(position);
        byte[] buffer = new byte[1 << 16];
        boolean afterFF = false;

        while (position < stripEnd && found < count) {
            int wanted = (int) Math.min(buffer.length, stripEnd - position);
            int read = stream.read(buffer, 0, wanted);
            if (read <= 0) {
                break;
            }
            for (int i = 0; i < read; i++) {
                int b = buffer[i] & 0xFF;
                if (!afterFF) {
                    afterFF = b == 0xFF;
                } else if (b >= 0xD0 && b <= 0xD7) {
                    starts[found++] = position + i + 1;
                    afterFF = false;
                    if (found == count) {
                        break;
                    }
                } else {
                    // 0xFF00 is a stuffed data byte and 0xFFFF is padding before a marker.
                    afterFF = b == 0xFF;
                }
            }
            position += read;
        }

        if (found != count) {
            throw new IIOException("NDPI directory " + directory.index() + " has " + found
                    + " restart markers, but its " + tilesAcross + "x" + tilesDown
                    + " tile grid needs " + count);
        }
        return starts;
    }

    private byte[] read(long from, long to) throws IOException {
        long length = to - from;
        if (length <= 0 || length > MAX_TILE_BYTES) {
            throw new IIOException("NDPI directory " + directory.index()
                    + " claims a " + length + " byte run of JPEG data");
        }
        byte[] data = new byte[(int) length];
        stream.seek(from);
        stream.readFully(data);
        return data;
    }
}
