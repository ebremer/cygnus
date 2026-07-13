package com.ebremer.cygnus.ndpi;

import com.twelvemonkeys.imageio.stream.ByteArrayImageInputStream;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
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

    private final ImageInputStream stream;
    private final NdpiDirectory directory;
    private final ImageReader jpeg;
    private final JpegHeader header;
    private final long stripOffset;
    private final long stripEnd;
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
        int compression = directory.compression();
        if (compression != Ndpi.COMPRESSION_JPEG) {
            throw new IIOException("NDPI directory " + directory.index()
                    + " is not JPEG (compression " + compression + ")");
        }
        this.stream = stream;
        this.directory = directory;
        this.jpeg = jpeg;
        this.stripOffset = directory.stripOffset();
        this.stripEnd = stripOffset + directory.stripByteCount();
        this.header = JpegHeader.read(stream, stripOffset, stripEnd);

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
        return header.restartInterval() > 0;
    }

    /** Colour components the level's JPEG holds: 3 for a scanned slide, 1 for fluorescence. */
    public int bands() {
        return header.components();
    }

    /** The types the JPEG decoder offers for this level's pixels. */
    public List<ImageTypeSpecifier> imageTypes() throws IOException {
        if (imageTypes == null) {
            jpeg.setInput(new ByteArrayImageInputStream(tileJpeg(0)));
            try {
                List<ImageTypeSpecifier> types = new ArrayList<>();
                jpeg.getImageTypes(0).forEachRemaining(types::add);
                if (types.isEmpty()) {
                    throw new IIOException("The JPEG decoder offers no type for NDPI directory "
                            + directory.index());
                }
                imageTypes = List.copyOf(types);
            } finally {
                jpeg.setInput(null);
            }
        }
        return imageTypes;
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
