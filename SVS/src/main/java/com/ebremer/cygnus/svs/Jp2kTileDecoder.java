package com.ebremer.cygnus.svs;

import com.ebremer.cygnus.jpeg2000.decoder.DecodedImage;
import com.ebremer.cygnus.jpeg2000.decoder.Jpeg2000Decoder;
import java.awt.Rectangle;
import java.awt.image.WritableRaster;
import java.io.IOException;
import javax.imageio.IIOException;
import javax.imageio.stream.ImageInputStream;

/**
 * Decodes the tiles of an Aperio JPEG 2000 level (TIFF compression 33003 or
 * 33005), which no TIFF codec covers: each tile is a bare JPEG 2000
 * codestream, decoded here with the Cygnus JPEG 2000 decoder.
 *
 * <p>Under compression 33003 the three codestream components are Y, Cb and Cr
 * — the codestream carries no colour signalling, so the TIFF tag is the only
 * thing that says so — and are converted to RGB on the way out. Chroma coded
 * at reduced resolution is replicated up by the decoder.</p>
 */
public final class Jp2kTileDecoder {

    /** Refuses to allocate for an implausible TileByteCounts entry. */
    private static final int MAX_TILE_BYTES = 1 << 28;

    /** White, the background an Aperio scanner leaves where it wrote no tile. */
    private static final int BACKGROUND = 255;

    /** Reports decoded tiles; returning false aborts the read. */
    public interface Progress {
        boolean tileDecoded(int done, int total);
    }

    private final ImageInputStream stream;
    private final SVSDirectory dir;
    private final boolean ycbcr;

    public Jp2kTileDecoder(ImageInputStream stream, SVSDirectory dir) throws IIOException {
        if (!dir.isJpeg2000()) {
            throw new IIOException("TIFF directory " + dir.index() + " is not JPEG 2000 coded");
        }
        if (!dir.isTiled()) {
            throw new IIOException("Aperio JPEG 2000 directory " + dir.index() + " is not tiled");
        }
        this.stream = stream;
        this.dir = dir;
        this.ycbcr = dir.compression() == Tiff.COMPRESSION_APERIO_JP2K_YCBCR;
    }

    /**
     * Decodes the tiles covering {@code srcRegion} and writes them, subsampled
     * by {@code subX}/{@code subY}, into {@code destRegion} of {@code dest}.
     *
     * <p>{@code srcRegion} and {@code destRegion} are the rectangles
     * {@code ImageReader.computeRegions} produces, so the source pixel behind
     * destination column {@code dx} is {@code srcRegion.x + dx * subX}.</p>
     */
    public void readRegion(Rectangle srcRegion, int subX, int subY,
                           WritableRaster dest, Rectangle destRegion,
                           int[] srcBands, int[] destBands, Progress progress)
            throws IOException {
        int tileW = dir.tileWidth();
        int tileH = dir.tileHeight();
        int firstTileX = srcRegion.x / tileW;
        int lastTileX = (srcRegion.x + srcRegion.width - 1) / tileW;
        int firstTileY = srcRegion.y / tileH;
        int lastTileY = (srcRegion.y + srcRegion.height - 1) / tileH;
        int total = (lastTileX - firstTileX + 1) * (lastTileY - firstTileY + 1);

        boolean interleaved = srcBands == null && destBands == null && dest.getNumBands() == 3;
        int[] row = interleaved ? new int[destRegion.width * 3] : null;
        int[] rgb = new int[3];
        int done = 0;

        for (int tileY = firstTileY; tileY <= lastTileY; tileY++) {
            for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                int originX = tileX * tileW;
                int originY = tileY * tileH;
                int width = Math.min(tileW, dir.width() - originX);
                int height = Math.min(tileH, dir.height() - originY);

                // Destination columns and rows whose source pixel lands in this tile.
                int fromX = Math.max(0, Math.ceilDiv(originX - srcRegion.x, subX));
                int toX = Math.min(destRegion.width - 1,
                        Math.floorDiv(originX + width - 1 - srcRegion.x, subX));
                int fromY = Math.max(0, Math.ceilDiv(originY - srcRegion.y, subY));
                int toY = Math.min(destRegion.height - 1,
                        Math.floorDiv(originY + height - 1 - srcRegion.y, subY));
                if (fromX > toX || fromY > toY) {
                    done++;
                    continue;
                }

                DecodedImage tile = decodeTile(tileX, tileY);
                for (int dy = fromY; dy <= toY; dy++) {
                    int y = srcRegion.y + dy * subY - originY;
                    if (interleaved) {
                        int at = 0;
                        for (int dx = fromX; dx <= toX; dx++) {
                            sample(tile, srcRegion.x + dx * subX - originX, y, rgb);
                            row[at++] = rgb[0];
                            row[at++] = rgb[1];
                            row[at++] = rgb[2];
                        }
                        dest.setPixels(destRegion.x + fromX, destRegion.y + dy,
                                toX - fromX + 1, 1, row);
                    } else {
                        writeBanded(tile, srcRegion, subX, dest, destRegion,
                                srcBands, destBands, rgb, dy, y, fromX, toX, originX);
                    }
                }

                done++;
                if (progress != null && !progress.tileDecoded(done, total)) {
                    return;
                }
            }
        }
    }

    /** Per-pixel path for reads that select or reorder bands. */
    private void writeBanded(DecodedImage tile, Rectangle srcRegion, int subX,
                             WritableRaster dest, Rectangle destRegion,
                             int[] srcBands, int[] destBands, int[] rgb,
                             int dy, int y, int fromX, int toX, int originX) {
        int bands = srcBands != null ? srcBands.length
                : (destBands != null ? destBands.length : 3);
        for (int dx = fromX; dx <= toX; dx++) {
            sample(tile, srcRegion.x + dx * subX - originX, y, rgb);
            for (int b = 0; b < bands; b++) {
                int from = srcBands != null ? srcBands[b] : b;
                int to = destBands != null ? destBands[b] : b;
                dest.setSample(destRegion.x + dx, destRegion.y + dy, to, rgb[from]);
            }
        }
    }

    /** The RGB triple at tile-local position (x, y); a missing tile reads as background. */
    private void sample(DecodedImage tile, int x, int y, int[] rgb) {
        if (tile == null) {
            rgb[0] = BACKGROUND;
            rgb[1] = BACKGROUND;
            rgb[2] = BACKGROUND;
            return;
        }
        if (tile.numChannels < 3) {
            int grey = eightBit(tile, 0, tile.sampleAt(0, x, y));
            rgb[0] = grey;
            rgb[1] = grey;
            rgb[2] = grey;
            return;
        }
        int c0 = eightBit(tile, 0, tile.sampleAt(0, x, y));
        int c1 = eightBit(tile, 1, tile.sampleAt(1, x, y));
        int c2 = eightBit(tile, 2, tile.sampleAt(2, x, y));
        if (ycbcr) {
            float cb = c1 - 128f;
            float cr = c2 - 128f;
            rgb[0] = clamp(Math.round(c0 + 1.402f * cr));
            rgb[1] = clamp(Math.round(c0 - 0.344136f * cb - 0.714136f * cr));
            rgb[2] = clamp(Math.round(c0 + 1.772f * cb));
        } else {
            rgb[0] = c0;
            rgb[1] = c1;
            rgb[2] = c2;
        }
    }

    /** Normalises a codestream sample to the unsigned 8-bit range the reader emits. */
    private static int eightBit(DecodedImage tile, int channel, int value) {
        int depth = tile.depth[channel];
        if (tile.signed[channel]) {
            value += 1 << (depth - 1);
        }
        if (depth > 8) {
            value >>= depth - 8;
        } else if (depth < 8) {
            value <<= 8 - depth;
        }
        return clamp(value);
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    /** Decodes one tile's codestream, or null where the scanner wrote no tile. */
    private DecodedImage decodeTile(int tileX, int tileY) throws IOException {
        int index = tileY * dir.tilesAcross() + tileX;
        long length = dir.tileByteCount(index);
        if (length <= 0) {
            return null;
        }
        if (length > MAX_TILE_BYTES) {
            throw new IIOException("Tile " + tileX + "," + tileY + " of TIFF directory "
                    + dir.index() + " claims " + length + " bytes");
        }
        long offset = dir.tileOffset(index);
        if (offset < 0) {
            throw new IIOException("Tile " + tileX + "," + tileY + " of TIFF directory "
                    + dir.index() + " is at offset " + offset);
        }
        byte[] codestream = new byte[(int) length];
        stream.seek(offset);
        stream.readFully(codestream);
        return new Jpeg2000Decoder().decode(codestream);
    }
}
