package com.ebremer.cygnus.jp2;

import java.io.ByteArrayOutputStream;

/**
 * Builds the JP2 container boxes (ISO/IEC 15444-1 Annex I) that precede the
 * contiguous codestream box: the signature box, the file type box and the
 * JP2 header superbox (image header, optional per-component bit depths,
 * colour specification and channel definitions). The counterpart of
 * {@link Jp2Parser}.
 */
public final class Jp2Writer {

    private static final int BOX_JP = 0x6A502020;    // "jP\40\40"
    private static final int BOX_FTYP = 0x66747970;
    private static final int BOX_JP2H = 0x6A703268;
    private static final int BOX_IHDR = 0x69686472;
    private static final int BOX_BPCC = 0x62706363;
    private static final int BOX_COLR = 0x636F6C72;
    private static final int BOX_CDEF = 0x63646566;
    /** "jp2\40" brand / compatibility code. */
    private static final int BRAND_JP2 = 0x6A703220;
    /** Box type of the contiguous codestream box, for callers writing it. */
    public static final int BOX_JP2C = 0x6A703263;

    private Jp2Writer() {
    }

    /**
     * The boxes of a JP2 file up to (not including) the contiguous
     * codestream box. The caller appends a jp2c box (whose length field may
     * be zero, meaning "to end of file") holding the codestream.
     *
     * @param depths     bit depth per codestream component
     * @param signed     signedness per component
     * @param enumCs     enumerated colourspace ({@link Jp2Info#CS_SRGB},
     *                   {@link Jp2Info#CS_GREY}, {@link Jp2Info#CS_SYCC});
     *                   ignored when {@code iccProfile} is given
     * @param iccProfile restricted ICC profile for a method-2 colour box,
     *                   or null for the enumerated method
     * @param alphaChannel index of the opacity channel, or -1 for none; when
     *                   present, a channel definition box maps the remaining
     *                   channels to colours in order
     * @param alphaPremultiplied true if the opacity channel is premultiplied
     */
    public static byte[] headerBoxes(int width, int height,
                                     int[] depths, boolean[] signed,
                                     int enumCs, byte[] iccProfile,
                                     int alphaChannel, boolean alphaPremultiplied) {
        int nc = depths.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // signature box
        w32(out, 12);
        w32(out, BOX_JP);
        w32(out, 0x0D0A870A);

        // file type box
        w32(out, 20);
        w32(out, BOX_FTYP);
        w32(out, BRAND_JP2);
        w32(out, 0);          // minor version
        w32(out, BRAND_JP2);  // compatibility list

        // jp2h superbox payload
        ByteArrayOutputStream hdr = new ByteArrayOutputStream();
        boolean uniform = true;
        for (int c = 1; c < nc; c++) {
            uniform &= depths[c] == depths[0] && signed[c] == signed[0];
        }

        w32(hdr, 22);
        w32(hdr, BOX_IHDR);
        w32(hdr, height);
        w32(hdr, width);
        w16(hdr, nc);
        hdr.write(uniform ? bpcByte(depths[0], signed[0]) : 255);
        hdr.write(7);   // compression type: wavelet
        hdr.write(0);   // UnkC: colourspace is specified
        hdr.write(0);   // IPR: no intellectual property box

        if (!uniform) {
            w32(hdr, 8 + nc);
            w32(hdr, BOX_BPCC);
            for (int c = 0; c < nc; c++) {
                hdr.write(bpcByte(depths[c], signed[c]));
            }
        }

        if (iccProfile != null) {
            w32(hdr, 8 + 3 + iccProfile.length);
            w32(hdr, BOX_COLR);
            hdr.write(Jp2Info.METH_RESTRICTED_ICC);
            hdr.write(0);   // precedence
            hdr.write(0);   // approximation
            hdr.writeBytes(iccProfile);
        } else {
            w32(hdr, 8 + 7);
            w32(hdr, BOX_COLR);
            hdr.write(Jp2Info.METH_ENUMERATED);
            hdr.write(0);
            hdr.write(0);
            w32(hdr, enumCs);
        }

        if (alphaChannel >= 0) {
            w32(hdr, 8 + 2 + 6 * nc);
            w32(hdr, BOX_CDEF);
            w16(hdr, nc);
            int colour = 1;
            for (int c = 0; c < nc; c++) {
                w16(hdr, c);
                if (c == alphaChannel) {
                    w16(hdr, alphaPremultiplied ? 2 : 1);
                    w16(hdr, 0);            // applies to the whole image
                } else {
                    w16(hdr, 0);            // colour channel
                    w16(hdr, colour++);     // 1-based colour index
                }
            }
        }

        byte[] hdrBytes = hdr.toByteArray();
        w32(out, 8 + hdrBytes.length);
        w32(out, BOX_JP2H);
        out.writeBytes(hdrBytes);
        return out.toByteArray();
    }

    private static int bpcByte(int depth, boolean signed) {
        return (depth - 1) | (signed ? 0x80 : 0);
    }

    private static void w16(ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void w32(ByteArrayOutputStream o, int v) {
        w16(o, v >>> 16);
        w16(o, v);
    }
}
