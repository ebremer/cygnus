package com.ebremer.cygnus.jpeg2000.imageio;

import javax.imageio.ImageWriteParam;

/**
 * Write parameters for the Cygnus JPEG 2000 writer.
 *
 * <p>Two compression types are offered: {@code "Lossless"} (the default;
 * reversible 5/3 wavelet with RCT, exact reconstruction) and {@code "Lossy"}
 * (irreversible 9/7 wavelet with ICT and scalar quantization). For the lossy
 * type, {@link #setCompressionQuality} scales the quantization step sizes:
 * 1.0 gives near-lossless quality and the largest files, smaller values give
 * coarser quantization and stronger compression.</p>
 *
 * <p>Tiling is supported via {@link #setTiling} (tile offsets must be zero).
 * The additional JPEG 2000 knobs are the number of wavelet decomposition
 * levels, the code-block size, optional SOP/EPH resynchronization markers,
 * and whether to write a raw codestream instead of a JP2 file.</p>
 */
public class CygnusImageWriteParam extends ImageWriteParam {

    /** Compression type name for the reversible path. */
    public static final String LOSSLESS = "Lossless";
    /** Compression type name for the irreversible path. */
    public static final String LOSSY = "Lossy";

    private int numDecompositionLevels = -1;
    private int codeBlockWidth = 64;
    private int codeBlockHeight = 64;
    private boolean sopMarkers;
    private boolean ephMarkers;
    private boolean writeCodeStreamOnly;

    public CygnusImageWriteParam() {
        this.canWriteCompressed = true;
        this.canWriteTiles = true;
        this.canOffsetTiles = false;
        this.canWriteProgressive = false;
        this.compressionTypes = new String[] {LOSSLESS, LOSSY};
        this.compressionMode = MODE_EXPLICIT;
        this.compressionType = LOSSLESS;
        this.compressionQuality = 1.0f;
    }

    /**
     * Number of wavelet decomposition levels, 0..32, or -1 (the default) to
     * pick automatically from the tile size (at most 5).
     */
    public void setNumDecompositionLevels(int levels) {
        if (levels < -1 || levels > 32) {
            throw new IllegalArgumentException("Decomposition levels out of range: "
                    + levels);
        }
        this.numDecompositionLevels = levels;
    }

    public int getNumDecompositionLevels() {
        return numDecompositionLevels;
    }

    /**
     * Code-block size; each dimension a power of two in 4..1024 and the
     * area at most 4096 (T.800 code-block limits). Default 64x64.
     */
    public void setCodeBlockSize(int width, int height) {
        if (Integer.bitCount(width) != 1 || Integer.bitCount(height) != 1
                || width < 4 || height < 4 || width * height > 4096) {
            throw new IllegalArgumentException("Invalid code-block size "
                    + width + "x" + height);
        }
        this.codeBlockWidth = width;
        this.codeBlockHeight = height;
    }

    public int getCodeBlockWidth() {
        return codeBlockWidth;
    }

    public int getCodeBlockHeight() {
        return codeBlockHeight;
    }

    /** Emit an SOP marker segment before every packet (default false). */
    public void setSopMarkers(boolean sop) {
        this.sopMarkers = sop;
    }

    public boolean getSopMarkers() {
        return sopMarkers;
    }

    /** Emit an EPH marker after every packet header (default false). */
    public void setEphMarkers(boolean eph) {
        this.ephMarkers = eph;
    }

    public boolean getEphMarkers() {
        return ephMarkers;
    }

    /**
     * True writes a raw JPEG 2000 codestream (.j2k/.j2c); false (default)
     * wraps it in a JP2 container carrying colour and channel metadata.
     */
    public void setWriteCodeStreamOnly(boolean codeStreamOnly) {
        this.writeCodeStreamOnly = codeStreamOnly;
    }

    public boolean getWriteCodeStreamOnly() {
        return writeCodeStreamOnly;
    }
}
