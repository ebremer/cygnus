package com.ebremer.cygnus.jp2;

import com.ebremer.cygnus.io.ByteSource;
import com.ebremer.cygnus.io.SourceReader;
import java.io.IOException;
import javax.imageio.IIOException;

/**
 * Minimal JP2 box structure walker (ISO/IEC 15444-1 Annex I): locates the
 * contiguous codestream and collects the header boxes needed for decoding.
 * The top-level walk streams over the source (boxes other than the small
 * header superbox are skipped by seeking, and the codestream payload is
 * recorded as a range, not read).
 */
public final class Jp2Parser {

    private static final int BOX_JP = 0x6A502020;    // "jP\40\40" signature
    private static final int BOX_JP2H = 0x6A703268;
    private static final int BOX_IHDR = 0x69686472;
    private static final int BOX_BPCC = 0x62706363;
    private static final int BOX_COLR = 0x636F6C72;
    private static final int BOX_PCLR = 0x70636C72;
    private static final int BOX_CMAP = 0x636D6170;
    private static final int BOX_CDEF = 0x63646566;
    private static final int BOX_RES = 0x72657320;   // "res " superbox
    private static final int BOX_RESC = 0x72657363;  // capture resolution
    private static final int BOX_RESD = 0x72657364;  // display resolution
    private static final int BOX_JP2C = 0x6A703263;

    /** Sanity bound for header boxes read into memory. */
    private static final int MAX_HEADER_BOX = 1 << 26;

    private final byte[] d;
    private final Jp2Info info;

    private Jp2Parser(byte[] headerPayload, Jp2Info info) {
        this.d = headerPayload;
        this.info = info;
    }

    /** True if the data starts with the JP2 signature box. */
    public static boolean isJp2(byte[] data) {
        return data.length >= 12
                && u32(data, 0) == 12 && u32(data, 4) == BOX_JP
                && u32(data, 8) == 0x0D0A870A;
    }

    /** True if the data starts with a raw JPEG 2000 codestream (SOC + SIZ). */
    public static boolean isRawCodestream(byte[] data) {
        return data.length >= 4
                && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0x4F
                && (data[2] & 0xFF) == 0xFF && (data[3] & 0xFF) == 0x51;
    }

    public static Jp2Info parse(byte[] data) throws IIOException {
        return parse(ByteSource.of(data));
    }

    /** Walks the top-level boxes of a JP2 source. */
    public static Jp2Info parse(ByteSource src) throws IIOException {
        long total;
        try {
            total = src.length();
        } catch (IOException e) {
            throw new IIOException("Cannot determine source length", e);
        }
        Jp2Info info = new Jp2Info();
        SourceReader r = new SourceReader(src);
        long pos = 0;
        while (true) {
            if (total >= 0 && pos + 8 > total) {
                break;
            }
            r.seek(pos);
            long lbox = r.u16OrEof();
            if (lbox < 0) {
                break;
            }
            lbox = (lbox << 16) | r.u16();
            int tbox = (int) r.u32();
            long payload = pos + 8;
            long boxEnd;
            if (lbox == 0) {
                boxEnd = total >= 0 ? total : -1; // to end of source
            } else if (lbox == 1) {
                long xl = r.u64();
                payload += 8;
                boxEnd = pos + xl;
            } else {
                boxEnd = pos + lbox;
            }
            if (boxEnd >= 0 && (boxEnd < payload || (total >= 0 && boxEnd > total))) {
                boxEnd = total >= 0 ? total : -1; // tolerate a broken final box
            }
            switch (tbox) {
                case BOX_JP2H -> {
                    long n = (boxEnd < 0 ? Long.MAX_VALUE : boxEnd) - payload;
                    if (n < 0 || n > MAX_HEADER_BOX) {
                        throw new IIOException("Unreasonable jp2h box size");
                    }
                    r.seek(payload);
                    byte[] hdr = r.readBytes((int) n);
                    new Jp2Parser(hdr, info).walkHeader(0, hdr.length);
                }
                case BOX_JP2C -> {
                    if (info.codestreamOffset < 0) {
                        info.codestreamOffset = payload;
                        info.codestreamLength = boxEnd < 0 ? -1 : boxEnd - payload;
                    }
                }
                default -> {
                    // jP, ftyp, res, uuid, xml, ... are not needed for decoding
                }
            }
            if (boxEnd < 0) {
                break; // unbounded final box
            }
            pos = boxEnd;
        }
        if (info.codestreamOffset < 0) {
            throw new IIOException("JP2 file contains no codestream box");
        }
        return info;
    }

    private static int u32(byte[] d, int p) {
        return ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    private int u16(int p) {
        return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
    }

    /** Walks the boxes nested in the (in-memory) jp2h superbox payload. */
    private void walkHeader(int pos, int end) throws IIOException {
        while (pos + 8 <= end) {
            long lbox = u32(d, pos) & 0xFFFFFFFFL;
            int tbox = u32(d, pos + 4);
            int payload = pos + 8;
            long boxEnd;
            if (lbox == 0) {
                boxEnd = end;
            } else if (lbox == 1) {
                if (payload + 8 > end) {
                    break;
                }
                long xl = ((long) u32(d, payload) << 32) | (u32(d, payload + 4) & 0xFFFFFFFFL);
                boxEnd = pos + xl;
                payload += 8;
            } else {
                boxEnd = pos + lbox;
            }
            if (boxEnd > end || boxEnd < payload) {
                boxEnd = end; // tolerate a broken final box length
            }
            int pEnd = (int) boxEnd;
            switch (tbox) {
                case BOX_IHDR -> readIhdr(payload, pEnd);
                case BOX_BPCC -> {
                    info.bpcc = new int[Math.min(info.numComponents, pEnd - payload)];
                    for (int i = 0; i < info.bpcc.length; i++) {
                        info.bpcc[i] = d[payload + i] & 0xFF;
                    }
                }
                case BOX_COLR -> readColr(payload, pEnd);
                case BOX_PCLR -> readPclr(payload, pEnd);
                case BOX_CMAP -> readCmap(payload, pEnd);
                case BOX_CDEF -> readCdef(payload, pEnd);
                case BOX_RES -> walkHeader(payload, pEnd); // superbox: resc/resd
                case BOX_RESC, BOX_RESD -> readRes(tbox, payload, pEnd);
                default -> {
                    // other informational boxes
                }
            }
            pos = pEnd;
        }
    }

    private void readIhdr(int p, int end) throws IIOException {
        if (end - p < 14) {
            throw new IIOException("Malformed ihdr box");
        }
        info.height = u32(d, p);
        info.width = u32(d, p + 4);
        info.numComponents = u16(p + 8);
        info.bpc = d[p + 10] & 0xFF;
    }

    private void readColr(int p, int end) {
        if (end - p < 3) {
            return; // malformed colour box: ignore, colour falls back
        }
        int meth = d[p] & 0xFF;
        // keep only the first colr box we can use (approximation precedence ignored)
        if (info.colourMethod != 0) {
            return;
        }
        if (meth == Jp2Info.METH_ENUMERATED && p + 7 <= end) {
            info.colourMethod = meth;
            info.enumCs = u32(d, p + 3);
        } else if (meth == Jp2Info.METH_RESTRICTED_ICC) {
            info.colourMethod = meth;
            info.iccProfile = new byte[end - (p + 3)];
            System.arraycopy(d, p + 3, info.iccProfile, 0, info.iccProfile.length);
        }
    }

    private void readPclr(int p, int end) throws IIOException {
        if (end - p < 3) {
            throw new IIOException("Invalid palette box");
        }
        int ne = u16(p);
        int npc = d[p + 2] & 0xFF;
        if (ne < 1 || ne > 1024 || npc < 1 || end - p < 3 + npc) {
            throw new IIOException("Invalid palette box");
        }
        info.paletteDepth = new int[npc];
        info.paletteSigned = new boolean[npc];
        int q = p + 3;
        for (int i = 0; i < npc; i++) {
            int b = d[q++] & 0xFF;
            info.paletteSigned[i] = (b & 0x80) != 0;
            info.paletteDepth[i] = (b & 0x7F) + 1;
        }
        info.palette = new int[npc][ne];
        for (int e = 0; e < ne; e++) {
            for (int i = 0; i < npc; i++) {
                int bytes = (info.paletteDepth[i] + 7) / 8;
                int v = 0;
                for (int k = 0; k < bytes && q < end; k++) {
                    v = (v << 8) | (d[q++] & 0xFF);
                }
                info.palette[i][e] = v;
            }
        }
    }

    private void readCmap(int p, int end) {
        int n = (end - p) / 4;
        info.cmapComponent = new int[n];
        info.cmapType = new int[n];
        info.cmapColumn = new int[n];
        for (int i = 0; i < n; i++) {
            info.cmapComponent[i] = u16(p + 4 * i);
            info.cmapType[i] = d[p + 4 * i + 2] & 0xFF;
            info.cmapColumn[i] = d[p + 4 * i + 3] & 0xFF;
        }
    }

    /** Capture/display resolution (I.5.3.7): grid points per meter. */
    private void readRes(int tbox, int p, int end) {
        if (end - p < 10) {
            return;
        }
        int vn = u16(p);
        int vd = u16(p + 2);
        int hn = u16(p + 4);
        int hd = u16(p + 6);
        int ve = d[p + 8];  // signed exponents
        int he = d[p + 9];
        if (vd == 0 || hd == 0) {
            return;
        }
        double vr = (double) vn / vd * Math.pow(10, ve);
        double hr = (double) hn / hd * Math.pow(10, he);
        if (tbox == BOX_RESC) {
            info.captureResX = hr;
            info.captureResY = vr;
        } else {
            info.displayResX = hr;
            info.displayResY = vr;
        }
    }

    private void readCdef(int p, int end) {
        if (end - p < 2) {
            return;
        }
        int n = u16(p);
        if (p + 2 + 6 * n > end) {
            return;
        }
        info.cdefChannel = new int[n];
        info.cdefType = new int[n];
        info.cdefAssoc = new int[n];
        for (int i = 0; i < n; i++) {
            int q = p + 2 + 6 * i;
            info.cdefChannel[i] = u16(q);
            info.cdefType[i] = u16(q + 2);
            info.cdefAssoc[i] = u16(q + 4);
        }
    }
}
