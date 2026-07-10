package com.ebremer.cygnus.jp2;

/**
 * Metadata gathered from the JP2 container boxes (ISO/IEC 15444-1 Annex I)
 * that affects decoding: image header, colour specification, palette,
 * component mapping and channel definitions.
 */
public final class Jp2Info {

    // colour specification methods
    public static final int METH_ENUMERATED = 1;
    public static final int METH_RESTRICTED_ICC = 2;

    // enumerated colourspaces
    public static final int CS_SRGB = 16;
    public static final int CS_GREY = 17;
    public static final int CS_SYCC = 18;

    /** Offset of the (first) contiguous codestream box payload in the source. */
    public long codestreamOffset = -1;
    /** Payload length, or -1 when the box extends to the end of an unbounded source. */
    public long codestreamLength = -1;

    // ihdr
    public int height, width, numComponents;
    public int bpc;                  // 255 = components vary, see bpcc
    public int[] bpcc;               // per-component (Ssiz-coded) depths, or null

    // colr
    public int colourMethod;         // 0 = no colr box seen
    public int enumCs = -1;
    public byte[] iccProfile;

    // pclr
    public int[][] palette;          // [column][entry] raw values, or null
    public int[] paletteDepth;       // bit depth per column
    public boolean[] paletteSigned;

    // cmap: one entry per output channel
    public int[] cmapComponent;      // source codestream component
    public int[] cmapType;           // 0 = direct, 1 = palette
    public int[] cmapColumn;         // palette column when type == 1

    // cdef: channel -> (type, association)
    public int[] cdefChannel;
    public int[] cdefType;           // 0 colour, 1 alpha, 2 premultiplied alpha
    public int[] cdefAssoc;          // 0 whole image, k = colour k (1-based), 0xFFFF none

    // res box (grid points per meter; 0 = absent)
    public double captureResX, captureResY;
    public double displayResX, displayResY;

    public boolean hasPalette() {
        return palette != null && cmapComponent != null;
    }
}
