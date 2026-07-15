package com.ebremer.cygnus.jpegxl.codestream;

import com.ebremer.cygnus.jpegxl.io.BitWriter;
import com.ebremer.cygnus.jpegxl.io.Bits;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** ImageMetadata (18181-1 clause 8), read immediately after the SizeHeader. */
public final class ImageMetadata {

    public BitDepth bitDepth = BitDepth.INT8;
    public boolean modular16BitBuffers = true;
    public final List<ExtraChannelInfo> extraChannels = new ArrayList<>();
    public boolean xybEncoded = true;
    public ColourEncoding colourEncoding = new ColourEncoding();

    public int orientation = 1;
    public int intrinsicWidth;
    public int intrinsicHeight;

    /** Preview dimensions, or 0 when the image has no preview. */
    public int previewWidth;
    public int previewHeight;

    public boolean haveAnimation;
    public int animTpsNumerator;
    public int animTpsDenominator;
    public long animNumLoops;
    public boolean animHaveTimecodes;

    public float intensityTarget = 255f;
    public float minNits;
    public float linearBelow;

    /** Opsin inverse matrix parameters, only meaningful when {@link #xybEncoded}. */
    public final float[] opsinInverse = {
        11.031566901960783f, -9.866943921568629f, -0.16462299647058826f,
        -3.254147380392157f, 4.418770392156863f, -0.16462299647058826f,
        -3.6588512862745097f, 2.7129230470588235f, 1.9459282392156863f,
    };
    public final float[] opsinBias = {
        -0.0037930732552754493f, -0.0037930732552754493f, -0.0037930732552754493f,
    };
    public final float[] quantBias = {
        1.0f - 0.05465007330715401f, 1.0f - 0.07005449891748593f, 1.0f - 0.049935103337343655f,
    };
    public float quantBiasNumerator = 0.145f;

    /** Custom upsampling weights (null = use the normative defaults). */
    public float[] up2Weights;
    public float[] up4Weights;
    public float[] up8Weights;

    /** Reconstructed embedded ICC profile bytes, when present and decodable. */
    public byte[] iccProfile;

    public int numExtraChannels() {
        return extraChannels.size();
    }

    /** Index of the first alpha extra channel, or -1. */
    public int alphaChannelIndex() {
        for (int i = 0; i < extraChannels.size(); i++) {
            if (extraChannels.get(i).type == ExtraChannelInfo.TYPE_ALPHA) {
                return i;
            }
        }
        return -1;
    }

    public int colourChannelCount() {
        return colourEncoding.isGrey() ? 1 : 3;
    }

    public static ImageMetadata read(Bits in) throws IOException {
        ImageMetadata m = new ImageMetadata();
        boolean allDefault = in.bool();
        if (!allDefault) {
            boolean extraFields = in.bool();
            if (extraFields) {
                m.orientation = in.u(3) + 1;
                if (in.bool()) { // have_intrinsic_size
                    SizeHeader intr = SizeHeader.read(in);
                    m.intrinsicWidth = intr.width;
                    m.intrinsicHeight = intr.height;
                }
                if (in.bool()) { // have_preview
                    SizeHeader preview = SizeHeader.readPreview(in);
                    m.previewWidth = preview.width;
                    m.previewHeight = preview.height;
                }
                if (in.bool()) { // have_animation
                    m.haveAnimation = true;
                    m.animTpsNumerator = in.u32(100, 0, 1000, 0, 1, 10, 1, 30);
                    m.animTpsDenominator = in.u32(1, 0, 1001, 0, 1, 8, 1, 10);
                    int sel = in.u(2);
                    m.animNumLoops = switch (sel) {
                        case 0 -> 0;
                        case 1 -> in.u(3);
                        case 2 -> in.u(16);
                        default -> in.u(32) & 0xffffffffL;
                    };
                    m.animHaveTimecodes = in.bool();
                }
            }
            m.bitDepth = BitDepth.read(in);
            m.modular16BitBuffers = in.bool();
            int numEC = in.u32(0, 0, 1, 0, 2, 4, 1, 12);
            for (int i = 0; i < numEC; i++) {
                m.extraChannels.add(ExtraChannelInfo.read(in));
            }
            m.xybEncoded = in.bool();
            m.colourEncoding = ColourEncoding.read(in);
            if (extraFields) {
                if (!in.bool()) { // ToneMapping.all_default
                    m.intensityTarget = in.f16();
                    m.minNits = in.f16();
                    boolean relative = in.bool();
                    m.linearBelow = in.f16();
                    if (relative) {
                        m.linearBelow *= -1f;
                    }
                }
            }
            readExtensions(in);
        }
        if (!in.bool()) { // !default_m
            if (m.xybEncoded && !in.bool()) { // OpsinInverseMatrix not all-default
                for (int i = 0; i < 9; i++) {
                    m.opsinInverse[i] = in.f16();
                }
                for (int i = 0; i < 3; i++) {
                    m.opsinBias[i] = in.f16();
                }
                for (int i = 0; i < 3; i++) {
                    m.quantBias[i] = in.f16();
                }
                m.quantBiasNumerator = in.f16();
            }
            int cwMask = in.u(3);
            if ((cwMask & 1) != 0) {
                m.up2Weights = new float[15];
                for (int i = 0; i < 15; i++) {
                    m.up2Weights[i] = in.f16();
                }
            }
            if ((cwMask & 2) != 0) {
                m.up4Weights = new float[55];
                for (int i = 0; i < 55; i++) {
                    m.up4Weights[i] = in.f16();
                }
            }
            if ((cwMask & 4) != 0) {
                m.up8Weights = new float[210];
                for (int i = 0; i < 210; i++) {
                    m.up8Weights[i] = in.f16();
                }
            }
        }
        return m;
    }

    /** Writes this metadata; supports the shapes the encoder produces. */
    public void write(BitWriter out) {
        boolean allDefault = !bitDepth.floatingPoint && bitDepth.bitsPerSample == 8
                && modular16BitBuffers && extraChannels.isEmpty() && xybEncoded
                && colourEncoding.allDefault && orientation == 1 && !haveAnimation
                && previewWidth == 0;
        out.writeBool(allDefault);
        if (!allDefault) {
            boolean extraFields = previewWidth > 0 || haveAnimation || orientation != 1;
            out.writeBool(extraFields);
            if (extraFields) {
                out.write(orientation - 1, 3);
                out.writeBool(false); // no intrinsic size
                boolean havePreview = previewWidth > 0;
                out.writeBool(havePreview);
                if (havePreview) {
                    out.writeBool(false); // preview not div8
                    out.writeU32Auto(previewHeight, 1, 6, 65, 8, 321, 10, 1345, 12);
                    out.write(0, 3);      // explicit width
                    out.writeU32Auto(previewWidth, 1, 6, 65, 8, 321, 10, 1345, 12);
                }
                out.writeBool(haveAnimation);
                if (haveAnimation) {
                    out.writeU32Auto(animTpsNumerator, 100, 0, 1000, 0, 1, 10, 1, 30);
                    out.writeU32Auto(animTpsDenominator, 1, 0, 1001, 0, 1, 8, 1, 10);
                    writeNumLoops(out, animNumLoops);
                    out.writeBool(animHaveTimecodes);
                }
            }
            bitDepth.write(out);
            out.writeBool(modular16BitBuffers);
            switch (extraChannels.size()) {
                case 0 -> out.write(0, 2);
                case 1 -> out.write(1, 2);
                default -> {
                    out.writeU32Auto(extraChannels.size(), 0, 0, 1, 0, 2, 4, 1, 12);
                }
            }
            for (ExtraChannelInfo ec : extraChannels) {
                ec.write(out);
            }
            out.writeBool(xybEncoded);
            colourEncoding.write(out);
            if (extraFields) {
                out.writeBool(true); // ToneMapping.all_default
            }
            out.writeU64(0); // extensions
        }
        out.writeBool(true); // default_m
    }

    /** num_loops: U32(0, u(3), u(16), u(32)); 0 means loop forever. */
    private static void writeNumLoops(BitWriter out, long loops) {
        if (loops == 0) {
            out.write(0, 2);
        } else if (loops < (1 << 3)) {
            out.write(1, 2);
            out.write((int) loops, 3);
        } else if (loops < (1 << 16)) {
            out.write(2, 2);
            out.write((int) loops, 16);
        } else {
            out.write(3, 2);
            out.write((int) loops, 32);
        }
    }

    public static void readExtensions(Bits in) throws IOException {
        long extensions = in.u64();
        long totalBits = 0;
        for (int i = 0; i < 64; i++) {
            if ((extensions >> i & 1) != 0) {
                long n = in.u64();
                if (n < 0 || totalBits + n < 0) {
                    throw new IOException("bad extension length");
                }
                totalBits += n;
            }
        }
        in.skip(totalBits);
    }
}
