package com.ebremer.cygnus.svs;

import com.twelvemonkeys.imageio.metadata.Directory;
import com.twelvemonkeys.imageio.metadata.Entry;
import javax.imageio.IIOException;

/**
 * One TIFF directory of an SVS file, reduced to the fields the reader needs:
 * geometry, tiling, compression and the Aperio description.
 *
 * <p>Directories are parsed up front so that structural queries and the JPEG
 * 2000 tile path never depend on the delegate TIFF reader.</p>
 */
public final class SVSDirectory {

    private final int index;
    private final int width;
    private final int height;
    private final int tileWidth;
    private final int tileHeight;
    private final int compression;
    private final int photometric;
    private final int samplesPerPixel;
    private final long[] tileOffsets;
    private final long[] tileByteCounts;
    private final AperioImageDescription description;

    private SVSDirectory(int index, int width, int height, int tileWidth, int tileHeight,
                         int compression, int photometric, int samplesPerPixel,
                         long[] tileOffsets, long[] tileByteCounts,
                         AperioImageDescription description) {
        this.index = index;
        this.width = width;
        this.height = height;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.compression = compression;
        this.photometric = photometric;
        this.samplesPerPixel = samplesPerPixel;
        this.tileOffsets = tileOffsets;
        this.tileByteCounts = tileByteCounts;
        this.description = description;
    }

    static SVSDirectory from(int index, Directory ifd) throws IIOException {
        int width = requireInt(ifd, Tiff.TAG_IMAGE_WIDTH, "ImageWidth", index);
        int height = requireInt(ifd, Tiff.TAG_IMAGE_LENGTH, "ImageLength", index);
        return new SVSDirectory(
                index, width, height,
                optionalInt(ifd, Tiff.TAG_TILE_WIDTH, 0),
                optionalInt(ifd, Tiff.TAG_TILE_LENGTH, 0),
                optionalInt(ifd, Tiff.TAG_COMPRESSION, Tiff.COMPRESSION_NONE),
                optionalInt(ifd, Tiff.TAG_PHOTOMETRIC_INTERPRETATION, -1),
                optionalInt(ifd, Tiff.TAG_SAMPLES_PER_PIXEL, 1),
                longs(ifd, Tiff.TAG_TILE_OFFSETS),
                longs(ifd, Tiff.TAG_TILE_BYTE_COUNTS),
                AperioImageDescription.parse(text(ifd, Tiff.TAG_IMAGE_DESCRIPTION)));
    }

    /** Index of this directory in the TIFF, which is what the delegate reader indexes by. */
    public int index() {
        return index;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Tile width, or 0 when the directory is stored in strips. */
    public int tileWidth() {
        return tileWidth;
    }

    /** Tile height, or 0 when the directory is stored in strips. */
    public int tileHeight() {
        return tileHeight;
    }

    public int compression() {
        return compression;
    }

    public int photometricInterpretation() {
        return photometric;
    }

    public int samplesPerPixel() {
        return samplesPerPixel;
    }

    public AperioImageDescription description() {
        return description;
    }

    /**
     * Tiled directories are the pyramid; the label, macro and thumbnail are
     * stored in strips.
     */
    public boolean isTiled() {
        return tileWidth > 0 && tileHeight > 0;
    }

    /** Whether the tiles are JPEG 2000 codestreams rather than a TIFF-native codec. */
    public boolean isJpeg2000() {
        return Tiff.isAperioJpeg2000(compression);
    }

    public int tilesAcross() {
        return isTiled() ? Math.ceilDiv(width, tileWidth) : 1;
    }

    public int tilesDown() {
        return isTiled() ? Math.ceilDiv(height, tileHeight) : 1;
    }

    /** Byte offset of a tile in the file, in row-major tile order. */
    public long tileOffset(int tileIndex) throws IIOException {
        return element(tileOffsets, tileIndex, "TileOffsets");
    }

    /** Compressed length of a tile; 0 marks a tile the scanner never wrote. */
    public long tileByteCount(int tileIndex) throws IIOException {
        return element(tileByteCounts, tileIndex, "TileByteCounts");
    }

    private long element(long[] values, int tileIndex, String tag) throws IIOException {
        if (values == null) {
            throw new IIOException("TIFF directory " + index + " has no " + tag);
        }
        if (tileIndex < 0 || tileIndex >= values.length) {
            throw new IIOException("Tile " + tileIndex + " outside the " + values.length
                    + " entries of " + tag + " in TIFF directory " + index);
        }
        return values[tileIndex];
    }

    @Override
    public String toString() {
        return "SVSDirectory[" + index + " " + width + "x" + height
                + (isTiled() ? " tiles " + tileWidth + "x" + tileHeight : " stripped")
                + " " + Tiff.compressionName(compression) + "]";
    }

    // ---- entry coercion ----
    //
    // TwelveMonkeys hands back a boxed Number for single-valued entries and a
    // short[]/int[]/long[] for arrays, depending on the TIFF field type.

    private static int requireInt(Directory ifd, int tag, String name, int index)
            throws IIOException {
        Entry entry = ifd.getEntryById(tag);
        if (entry == null) {
            throw new IIOException("TIFF directory " + index + " has no " + name);
        }
        return ((Number) entry.getValue()).intValue();
    }

    private static int optionalInt(Directory ifd, int tag, int defaultValue) {
        Entry entry = ifd.getEntryById(tag);
        if (entry == null || !(entry.getValue() instanceof Number value)) {
            return defaultValue;
        }
        return value.intValue();
    }

    private static String text(Directory ifd, int tag) {
        Entry entry = ifd.getEntryById(tag);
        Object value = entry == null ? null : entry.getValue();
        return value instanceof String s ? s : null;
    }

    private static long[] longs(Directory ifd, int tag) {
        Entry entry = ifd.getEntryById(tag);
        if (entry == null) {
            return null;
        }
        Object value = entry.getValue();
        if (value instanceof long[] longs) {
            return longs;
        }
        if (value instanceof int[] ints) {
            long[] out = new long[ints.length];
            for (int i = 0; i < ints.length; i++) {
                out[i] = ints[i] & 0xFFFFFFFFL;
            }
            return out;
        }
        if (value instanceof short[] shorts) {
            long[] out = new long[shorts.length];
            for (int i = 0; i < shorts.length; i++) {
                out[i] = shorts[i] & 0xFFFFL;
            }
            return out;
        }
        if (value instanceof Number number) {
            return new long[] {number.longValue()};
        }
        return null;
    }
}
