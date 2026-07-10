package com.ebremer.jpegxl.codestream;

import com.ebremer.jpegxl.io.BitWriter;
import com.ebremer.jpegxl.io.Bits;
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
    public static final int TYPE_NON_OPTIONAL = 15;
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

    public void write(BitWriter out) {
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
        if (type == TYPE_ALPHA) {
            out.writeBool(alphaAssociated);
        } else if (type != TYPE_DEPTH && type != TYPE_SELECTION_MASK && type != TYPE_BLACK
                && type != TYPE_THERMAL && type != TYPE_NON_OPTIONAL && type != TYPE_OPTIONAL) {
            throw new IllegalStateException("cannot encode extra channel type " + type);
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
