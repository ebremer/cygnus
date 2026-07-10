package com.ebremer.jpegxl.codestream;

import com.ebremer.jpegxl.io.Bits;
import java.io.IOException;

/** FrameHeader (18181-1 clause 9.2) plus derived group geometry. */
public final class FrameHeader {

    public static final int TYPE_REGULAR = 0;
    public static final int TYPE_LF = 1;
    public static final int TYPE_REFERENCE_ONLY = 2;
    public static final int TYPE_SKIP_PROGRESSIVE = 3;

    public static final long FLAG_NOISE = 1;
    public static final long FLAG_PATCHES = 2;
    public static final long FLAG_SPLINES = 16;
    public static final long FLAG_USE_LF_FRAME = 32;
    public static final long FLAG_SKIP_ADAPTIVE_LF_SMOOTHING = 128;

    public static final int BLEND_REPLACE = 0;
    public static final int BLEND_ADD = 1;
    public static final int BLEND_BLEND = 2;
    public static final int BLEND_MUL_ADD = 3;
    public static final int BLEND_MUL = 4;

    public int type = TYPE_REGULAR;
    public boolean isModular;
    public long flags;
    public boolean doYCbCr;
    /** Per-channel chroma upsampling shifts (normalised; 0 = full resolution). */
    public int[] jpegShiftX = new int[3];
    public int[] jpegShiftY = new int[3];
    /** Raw JPEG-style sampling factors (1 or 2), kept for jbrd reconstruction. */
    public int[] jpegSampH = {1, 1, 1};
    public int[] jpegSampV = {1, 1, 1};
    public boolean isSubsampled;
    public int upsampling = 1;
    public int[] ecUpsampling = new int[0];
    public int groupSizeShift = 8;
    public int xqmScale = 3;
    public int bqmScale = 2;
    public PassesInfo passes = new PassesInfo();
    public RestorationFilter restorationFilter = new RestorationFilter();
    public int lfLevel;
    public int x0;
    public int y0;
    /** Coded frame size (after dividing by upsampling and the LF level scale). */
    public int width;
    public int height;
    /** Frame size as it appears on the canvas (before upsampling division). */
    public int displayWidth;
    public int displayHeight;
    /** Buffer size: coded size rounded up to whole blocks for VarDCT. */
    public int paddedWidth;
    public int paddedHeight;
    /** Per-channel frame blending parameters. */
    public record BlendingInfo(int mode, int alphaChannel, boolean clamp, int source) {
        public static final BlendingInfo REPLACE = new BlendingInfo(BLEND_REPLACE, 0, false, 0);
    }

    public BlendingInfo blending = BlendingInfo.REPLACE;
    public BlendingInfo[] ecBlending = new BlendingInfo[0];
    public int blendMode = BLEND_REPLACE;
    public boolean allBlendReplace = true;
    public long duration;
    public long timecode;
    public boolean isLast = true;
    public int saveAsReference;
    public boolean saveBeforeColourTransform;
    public String name = "";
    public boolean fullFrame = true;

    // derived geometry
    public int groupDim;
    public int groupRows;
    public int groupColumns;
    public int numGroups;
    public int lfGroupRows;
    public int lfGroupColumns;
    public int numLfGroups;

    public static FrameHeader read(Bits in, ImageMetadata meta, int imageWidth, int imageHeight)
            throws IOException {
        in.zeroPadToByte();
        FrameHeader f = new FrameHeader();
        f.width = imageWidth;
        f.height = imageHeight;
        int numEC = meta.numExtraChannels();
        f.ecUpsampling = new int[numEC];
        java.util.Arrays.fill(f.ecUpsampling, 1);
        f.ecBlending = new BlendingInfo[numEC];
        java.util.Arrays.fill(f.ecBlending, BlendingInfo.REPLACE);

        if (!in.bool()) { // !all_default
            f.type = in.u(2);
            f.isModular = in.bool();
            f.flags = in.u64();
            if (!meta.xybEncoded) {
                f.doYCbCr = in.bool();
            }
            if ((f.flags & FLAG_USE_LF_FRAME) == 0) {
                if (f.doYCbCr) {
                    // JPEG-style sampling factors per channel (Cb, Y, Cr order)
                    for (int i = 0; i < 3; i++) {
                        switch (in.u(2)) {
                            case 1 -> {
                                f.jpegShiftY[i] = 1;
                                f.jpegShiftX[i] = 1;
                            }
                            case 2 -> f.jpegShiftX[i] = 1;
                            case 3 -> f.jpegShiftY[i] = 1;
                            default -> {
                            }
                        }
                        f.jpegSampH[i] = 1 + f.jpegShiftX[i];
                        f.jpegSampV[i] = 1 + f.jpegShiftY[i];
                    }
                    // normalise: the most finely sampled channel gets shift 0
                    int maxY = Math.max(f.jpegShiftY[0], Math.max(f.jpegShiftY[1], f.jpegShiftY[2]));
                    int maxX = Math.max(f.jpegShiftX[0], Math.max(f.jpegShiftX[1], f.jpegShiftX[2]));
                    for (int i = 0; i < 3; i++) {
                        f.jpegShiftY[i] = maxY - f.jpegShiftY[i];
                        f.jpegShiftX[i] = maxX - f.jpegShiftX[i];
                        f.isSubsampled |= f.jpegShiftY[i] != 0 || f.jpegShiftX[i] != 0;
                    }
                }
                f.upsampling = 1 << in.u(2);
                for (int i = 0; i < numEC; i++) {
                    // the effective factor folds in the channel's dim_shift
                    f.ecUpsampling[i] = (1 << in.u(2)) << meta.extraChannels.get(i).dimShift;
                    if (f.ecUpsampling[i] > 8) {
                        throw new IOException("extra channel upsampling too large");
                    }
                    if (f.ecUpsampling[i] < f.upsampling) {
                        throw new IOException(
                                "extra channel upsampling below colour upsampling");
                    }
                }
            }
            if (f.isModular) {
                f.groupSizeShift = 7 + in.u(2);
            } else if (meta.xybEncoded) {
                f.xqmScale = in.u(3);
                f.bqmScale = in.u(3);
            }
            if (f.type != TYPE_REFERENCE_ONLY) {
                f.passes = PassesInfo.read(in);
            }
            boolean fullFrame = true;
            if (f.type == TYPE_LF) {
                f.lfLevel = in.u(2) + 1;
            } else if (in.bool()) { // have_crop
                if (f.type != TYPE_REFERENCE_ONLY) {
                    f.x0 = Bits.unpackSigned(in.u32(0, 8, 256, 11, 2304, 14, 18688, 30));
                    f.y0 = Bits.unpackSigned(in.u32(0, 8, 256, 11, 2304, 14, 18688, 30));
                }
                f.width = in.u32(0, 8, 256, 11, 2304, 14, 18688, 30);
                f.height = in.u32(0, 8, 256, 11, 2304, 14, 18688, 30);
                fullFrame = f.x0 <= 0 && f.y0 <= 0
                        && f.width + f.x0 >= imageWidth && f.height + f.y0 >= imageHeight;
            }
            f.fullFrame = fullFrame;
            if (f.type == TYPE_REGULAR || f.type == TYPE_SKIP_PROGRESSIVE) {
                for (int i = -1; i < numEC; i++) {
                    int mode = in.u32(0, 0, 1, 0, 2, 0, 3, 2);
                    if (mode > BLEND_MUL) {
                        throw new IOException("bad blend mode " + mode);
                    }
                    int alphaChannel = 0;
                    boolean clamp = false;
                    int source = 0;
                    if (numEC > 0) {
                        if (mode == BLEND_BLEND || mode == BLEND_MUL_ADD) {
                            alphaChannel = in.u32(0, 0, 1, 0, 2, 0, 3, 3);
                            clamp = in.u(1) != 0;
                        } else if (mode == BLEND_MUL) {
                            clamp = in.u(1) != 0;
                        }
                    }
                    if (!fullFrame || mode != BLEND_REPLACE) {
                        source = in.u(2);
                    }
                    BlendingInfo info = new BlendingInfo(mode, alphaChannel, clamp, source);
                    if (i < 0) {
                        f.blending = info;
                        f.blendMode = mode;
                    } else {
                        f.ecBlending[i] = info;
                    }
                    if (mode != BLEND_REPLACE) {
                        f.allBlendReplace = false;
                    }
                }
                if (meta.haveAnimation) {
                    int sel = in.u(2);
                    f.duration = switch (sel) {
                        case 0 -> 0;
                        case 1 -> 1;
                        case 2 -> in.u(8);
                        default -> in.u(32) & 0xffffffffL;
                    };
                    if (meta.animHaveTimecodes) {
                        f.timecode = in.u(32) & 0xffffffffL;
                    }
                }
                f.isLast = in.bool();
            } else {
                f.isLast = false;
            }
            if (f.type != TYPE_LF && !f.isLast) {
                f.saveAsReference = in.u(2);
            }
            if (f.type == TYPE_REFERENCE_ONLY || (fullFrame
                    && (f.type == TYPE_REGULAR || f.type == TYPE_SKIP_PROGRESSIVE)
                    && f.blendMode == BLEND_REPLACE
                    && (f.duration == 0 || f.saveAsReference != 0)
                    && !f.isLast)) {
                f.saveBeforeColourTransform = in.bool();
            } else {
                f.saveBeforeColourTransform = f.type == TYPE_LF;
            }
            f.name = ExtraChannelInfo.readName(in);
            f.restorationFilter = RestorationFilter.read(in, f.isModular);
            ImageMetadata.readExtensions(in);
        }

        if (!f.isModular && !meta.xybEncoded) {
            // qm scales are only coded for XYB VarDCT frames; otherwise neutral
            f.xqmScale = 2;
            f.bqmScale = 2;
        }
        f.computeGeometry();
        return f;
    }

    public void computeGeometry() {
        displayWidth = width;
        displayHeight = height;
        width = Bits.ceilDiv(width, upsampling);
        height = Bits.ceilDiv(height, upsampling);
        if (lfLevel > 0) {
            // LF frames code an image 8^level times smaller
            width = Bits.ceilDiv(width, 1 << (3 * lfLevel));
            height = Bits.ceilDiv(height, 1 << (3 * lfLevel));
        }
        // padded geometry follows the RAW JPEG sampling factors: when every
        // channel is equally shifted the normalised shifts are all zero, but
        // the frame is still padded to whole MCUs (libjxl MaxHShift/MaxVShift)
        int factorY = Math.max(jpegSampV[0], Math.max(jpegSampV[1], jpegSampV[2]));
        int factorX = Math.max(jpegSampH[0], Math.max(jpegSampH[1], jpegSampH[2]));
        if (isModular) {
            paddedWidth = Bits.ceilDiv(width, factorX) * factorX;
            paddedHeight = Bits.ceilDiv(height, factorY) * factorY;
        } else {
            paddedWidth = Bits.ceilDiv(Bits.ceilDiv(width, 8), factorX) * factorX * 8;
            paddedHeight = Bits.ceilDiv(Bits.ceilDiv(height, 8), factorY) * factorY * 8;
        }
        groupDim = 1 << groupSizeShift;
        groupRows = Bits.ceilDiv(height, groupDim);
        groupColumns = Bits.ceilDiv(width, groupDim);
        numGroups = groupRows * groupColumns;
        lfGroupRows = Bits.ceilDiv(height, groupDim * 8);
        lfGroupColumns = Bits.ceilDiv(width, groupDim * 8);
        numLfGroups = lfGroupRows * lfGroupColumns;
    }

    public boolean hasFlag(long flag) {
        return (flags & flag) != 0;
    }

    public int numSections() {
        return passes.numPasses == 1 && numGroups == 1
                ? 1
                : 1 + numLfGroups + 1 + passes.numPasses * numGroups;
    }
}
