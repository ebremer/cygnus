package com.ebremer.cygnus.jpegxl.codestream;

import com.ebremer.cygnus.jpegxl.io.BitWriter;
import com.ebremer.cygnus.jpegxl.io.Bits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** ExtraChannelInfo bundle: alpha, depth, spot colour, and friends. */
public final class ExtraChannelInfo {

    public static final int TYPE_ALPHA = 0;
    public static final int TYPE_DEPTH = 1;
    public static final int TYPE_SPOT_COLOUR = 2;
    public static final int TYPE_SELECTION_MASK = 3;
    public static final int TYPE_BLACK = 4;
    public static final int TYPE_CFA = 5;
    public static final int TYPE_THERMAL = 6;

    /**
     * A channel whose meaning is <em>required</em> and not one of the above — so
     * a decoder that meets one cannot claim to have rendered the image, and must
     * refuse it. libjxl does. That makes this the one type an encoder must never
     * write: the file it produces is unreadable by construction, which is why
     * {@link #write} refuses it and points here. To carry something the format
     * has no name for, use {@link #TYPE_OPTIONAL}, which says the same thing
     * except that a reader who does not understand it may carry on.
     */
    public static final int TYPE_NON_OPTIONAL = 15;

    /** Anything else: named, carried, and ignorable by a reader who does not know it. */
    public static final int TYPE_OPTIONAL = 16;

    public int type = TYPE_ALPHA;
    public BitDepth bitDepth = BitDepth.INT8;
    public int dimShift;
    public String name = "";
    public boolean alphaAssociated;
    public final float[] spotColour = new float[4];
    public int cfaChannel = 1;

    public static ExtraChannelInfo read(Bits in) throws IOException {
        ExtraChannelInfo ec = new ExtraChannelInfo();
        if (in.bool()) { // d_alpha: default 8-bit unassociated alpha
            return ec;
        }
        ec.type = in.enumValue();
        ec.bitDepth = BitDepth.read(in);
        ec.dimShift = in.u32(0, 0, 3, 0, 4, 0, 1, 3);
        ec.name = readName(in);
        switch (ec.type) {
            case TYPE_ALPHA -> ec.alphaAssociated = in.bool();
            case TYPE_SPOT_COLOUR -> {
                for (int i = 0; i < 4; i++) {
                    ec.spotColour[i] = in.f16();
                }
            }
            case TYPE_CFA -> ec.cfaChannel = in.u32(1, 0, 0, 2, 3, 4, 19, 8);
            case TYPE_DEPTH, TYPE_SELECTION_MASK, TYPE_BLACK, TYPE_THERMAL,
                 TYPE_NON_OPTIONAL, TYPE_OPTIONAL -> {
            }
            default -> throw new IOException("unknown extra channel type " + ec.type);
        }
        return ec;
    }

    /** An alpha channel; {@code associated} means the colour is premultiplied. */
    public static ExtraChannelInfo alpha(BitDepth bitDepth, boolean associated) {
        ExtraChannelInfo ec = of(TYPE_ALPHA, bitDepth, "");
        ec.alphaAssociated = associated;
        return ec;
    }

    /**
     * A channel of one of the plain types — depth, selection mask, black,
     * thermal, non-optional, optional — which carry no parameters of their own.
     */
    public static ExtraChannelInfo of(int type, BitDepth bitDepth, String name) {
        ExtraChannelInfo ec = new ExtraChannelInfo();
        ec.type = type;
        ec.bitDepth = bitDepth;
        ec.name = name == null ? "" : name;
        return ec;
    }

    /**
     * A spot colour: the channel is a coverage map, and the decoder mixes
     * {@code (r, g, b)} onto the image wherever it is set, scaled by
     * {@code strength} — an ink, not an image.
     *
     * <p>The four values are snapped to the half-precision floats the header
     * carries, so what this returns is what the decoder will see: read them back
     * from {@link #spotColour} rather than assuming the exact values given.
     */
    public static ExtraChannelInfo spot(BitDepth bitDepth, String name,
            float r, float g, float b, float strength) {
        ExtraChannelInfo ec = of(TYPE_SPOT_COLOUR, bitDepth, name);
        ec.spotColour[0] = BitWriter.roundF16(r);
        ec.spotColour[1] = BitWriter.roundF16(g);
        ec.spotColour[2] = BitWriter.roundF16(b);
        ec.spotColour[3] = BitWriter.roundF16(strength);
        return ec;
    }

    /** One plane of a colour-filter-array sensor; {@code channel} is its index in the pattern. */
    public static ExtraChannelInfo cfa(BitDepth bitDepth, String name, int channel) {
        ExtraChannelInfo ec = of(TYPE_CFA, bitDepth, name);
        ec.cfaChannel = channel;
        return ec;
    }

    /** Image samples per sample of this channel along each axis: 1, 2, 4 or 8. */
    public int step() {
        return 1 << dimShift;
    }

    /**
     * The shape this channel's plane must have for an image of the given size.
     * A channel with a {@code dimShift} is stored smaller and stretched back out
     * by the decoder, so a caller supplying its samples needs to know by how much.
     */
    public int planeWidth(int imageWidth) {
        return (imageWidth + step() - 1) / step();
    }

    public int planeHeight(int imageHeight) {
        return (imageHeight + step() - 1) / step();
    }

    public void write(BitWriter out) {
        if (type == TYPE_NON_OPTIONAL) {
            throw new IllegalStateException("extra channel type 15 means \"required, and you do"
                    + " not know what it is\", so every decoder must refuse the file — use"
                    + " TYPE_OPTIONAL (16) to carry a channel the format has no name for");
        }
        boolean isDefault = type == TYPE_ALPHA && !bitDepth.floatingPoint
                && bitDepth.bitsPerSample == 8 && dimShift == 0 && name.isEmpty() && !alphaAssociated;
        out.writeBool(isDefault);
        if (isDefault) {
            return;
        }
        out.writeEnum(type);
        bitDepth.write(out);
        out.writeU32Auto(dimShift, 0, 0, 3, 0, 4, 0, 1, 3);
        writeName(out, name);
        switch (type) {
            case TYPE_ALPHA -> out.writeBool(alphaAssociated);
            case TYPE_SPOT_COLOUR -> {
                for (int i = 0; i < 4; i++) {
                    out.writeF16(spotColour[i]);
                }
            }
            case TYPE_CFA -> out.writeU32Auto(cfaChannel, 1, 0, 0, 2, 3, 4, 19, 8);
            case TYPE_DEPTH, TYPE_SELECTION_MASK, TYPE_BLACK, TYPE_THERMAL,
                 TYPE_NON_OPTIONAL, TYPE_OPTIONAL -> {
            }
            default -> throw new IllegalStateException("cannot encode extra channel type " + type);
        }
    }

    static String readName(Bits in) throws IOException {
        int len = in.u32(0, 0, 0, 4, 16, 5, 48, 10);
        byte[] buf = new byte[len];
        for (int i = 0; i < len; i++) {
            buf[i] = (byte) in.u(8);
        }
        return new String(buf, StandardCharsets.UTF_8);
    }

    static void writeName(BitWriter out, String name) {
        byte[] buf = name.getBytes(StandardCharsets.UTF_8);
        out.writeU32Auto(buf.length, 0, 0, 0, 4, 16, 5, 48, 10);
        for (byte b : buf) {
            out.write(b & 0xff, 8);
        }
    }
}
