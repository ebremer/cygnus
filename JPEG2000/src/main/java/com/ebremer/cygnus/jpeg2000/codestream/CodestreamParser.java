package com.ebremer.cygnus.jpeg2000.codestream;

import com.ebremer.cygnus.jpeg2000.io.ByteSource;
import com.ebremer.cygnus.jpeg2000.io.SourceReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOException;

/**
 * Parses a JPEG 2000 codestream (T.800 Annex A): main header, tile-part
 * headers and body locations. Headers are read through a buffered reader;
 * tile-part bodies are skipped (recorded as source ranges), so parsing a
 * large streamed file reads only its header bytes.
 */
public final class CodestreamParser {

    private final SourceReader r;
    private final long end;          // exclusive, Long.MAX_VALUE when unknown
    private final boolean endKnown;
    private final ByteSource src;
    private final Codestream cs;
    private ByteArrayOutputStream ppmRaw;

    private CodestreamParser(ByteSource src, long off, long len) throws IIOException {
        this.src = src;
        this.r = new SourceReader(src);
        this.r.seek(off);
        long total;
        try {
            total = src.length();
        } catch (IOException e) {
            throw new IIOException("Cannot determine source length", e);
        }
        if (len >= 0) {
            this.end = off + len;
            this.endKnown = true;
        } else if (total >= 0) {
            this.end = total;
            this.endKnown = true;
        } else {
            this.end = Long.MAX_VALUE;
            this.endKnown = false;
        }
        this.cs = new Codestream(src);
    }

    public static Codestream parse(byte[] data) throws IIOException {
        return parse(ByteSource.of(data), 0, data.length);
    }

    public static Codestream parse(byte[] data, int off, int len) throws IIOException {
        return parse(ByteSource.of(data), off, len);
    }

    /**
     * Parses a codestream starting at {@code off}; {@code len} may be -1
     * when the extent is unknown (the parser then relies on Psot fields and
     * the EOC marker).
     */
    public static Codestream parse(ByteSource src, long off, long len) throws IIOException {
        return new CodestreamParser(src, off, len).run();
    }

    private int u8() throws IIOException {
        checkEnd(1);
        return r.u8();
    }

    private int u16() throws IIOException {
        checkEnd(2);
        return r.u16();
    }

    private long u32() throws IIOException {
        checkEnd(4);
        return r.u32();
    }

    private void checkEnd(int n) throws IIOException {
        if (r.position() + n > end) {
            throw new IIOException("Unexpected end of codestream");
        }
    }

    private int u32AsInt() throws IIOException {
        long v = u32();
        if (v > Integer.MAX_VALUE) {
            throw new IIOException("Image dimensions beyond 2^31-1 are not supported");
        }
        return (int) v;
    }

    private Codestream run() throws IIOException {
        if (u16() != Marker.SOC) {
            throw new IIOException("Not a JPEG 2000 codestream (missing SOC)");
        }
        if (u16() != Marker.SIZ) {
            throw new IIOException("SIZ marker must immediately follow SOC");
        }
        readSiz();
        cs.tiles = new Codestream.TileHeader[cs.siz.numTiles()];

        // main header
        while (true) {
            int marker = u16();
            if (marker == Marker.SOT) {
                break;
            }
            if (marker == Marker.EOC) {
                throw new IIOException("Codestream contains no tile-parts");
            }
            readMainSegment(marker);
        }
        finishPpm();

        // tile-parts
        while (true) {
            readTilePart(); // positioned just after an SOT marker
            if (r.position() >= end) {
                break;
            }
            int marker = r.u16OrEof();
            if (marker < 0 || marker == Marker.EOC) {
                break;
            }
            if (marker != Marker.SOT) {
                cs.warnings.add("Expected SOT or EOC, found " + Marker.name(marker));
                break;
            }
        }
        if (cs.mainCod == null || cs.mainQcd == null) {
            throw new IIOException("Main header lacks mandatory COD/QCD marker segment");
        }
        return cs;
    }

    private void readSiz() throws IIOException {
        int lsiz = u16();
        int rsiz = u16();
        int xsiz = u32AsInt();
        int ysiz = u32AsInt();
        int xosiz = u32AsInt();
        int yosiz = u32AsInt();
        int xtsiz = u32AsInt();
        int ytsiz = u32AsInt();
        int xtosiz = u32AsInt();
        int ytosiz = u32AsInt();
        int csiz = u16();
        if (csiz < 1 || csiz > 16384) {
            throw new IIOException("Invalid component count " + csiz);
        }
        if (lsiz != 38 + 3 * csiz) {
            throw new IIOException("Invalid SIZ length " + lsiz);
        }
        if (xsiz <= xosiz || ysiz <= yosiz || xtsiz < 1 || ytsiz < 1
                || xtosiz > xosiz || ytosiz > yosiz
                || (long) xtosiz + xtsiz <= xosiz || (long) ytosiz + ytsiz <= yosiz
                || xsiz < 1 || ysiz < 1) {
            throw new IIOException("Invalid SIZ geometry");
        }
        long numTiles = Math.ceilDiv((long) xsiz - xtosiz, xtsiz)
                * Math.ceilDiv((long) ysiz - ytosiz, ytsiz);
        if (numTiles > (1 << 24)) {
            throw new IIOException("Tile count " + numTiles + " exceeds the safety limit");
        }
        int[] prec = new int[csiz];
        boolean[] signed = new boolean[csiz];
        int[] xr = new int[csiz];
        int[] yr = new int[csiz];
        for (int c = 0; c < csiz; c++) {
            int ssiz = u8();
            signed[c] = (ssiz & 0x80) != 0;
            prec[c] = (ssiz & 0x7F) + 1;
            xr[c] = u8();
            yr[c] = u8();
            if (xr[c] < 1 || yr[c] < 1) {
                throw new IIOException("Invalid subsampling for component " + c);
            }
        }
        cs.siz = new Siz(rsiz, xsiz, ysiz, xosiz, yosiz, xtsiz, ytsiz,
                xtosiz, ytosiz, prec, signed, xr, yr);
    }

    private void readMainSegment(int marker) throws IIOException {
        if (marker >= 0xFF30 && marker <= 0xFF3F) {
            return; // reserved segment-less markers (T.800 Table A.1)
        }
        int len = u16();
        long segEnd = r.position() + len - 2;
        if (len < 2 || segEnd > end) {
            throw new IIOException("Invalid length for " + Marker.name(marker));
        }
        switch (marker) {
            case Marker.COD -> cs.mainCod = readCod();
            case Marker.COC -> {
                int[] c = new int[1];
                CodingStyle style = readCoc(c);
                cs.mainCoc.put(c[0], style);
            }
            case Marker.QCD -> cs.mainQcd = readQuant(segEnd);
            case Marker.QCC -> {
                int c = compIndex();
                cs.mainQcc.put(c, readQuant(segEnd));
            }
            case Marker.RGN -> {
                if (cs.mainRgn == null) {
                    cs.mainRgn = new int[cs.siz.numComponents];
                }
                readRgn(cs.mainRgn);
            }
            case Marker.POC -> readPoc(segEnd, cs.mainPoc);
            case Marker.PPM -> {
                if (ppmRaw == null) {
                    ppmRaw = new ByteArrayOutputStream();
                }
                u8(); // Zppm - segments are required to appear in order
                ppmRaw.writeBytes(r.readBytes((int) (segEnd - r.position())));
            }
            case Marker.CAP, Marker.CPF, Marker.TLM, Marker.PLM, Marker.CRG,
                 Marker.COM -> {
                // capabilities are implied by the code-block style (CB_HT);
                // the rest are informational and not needed for decoding
            }
            default -> cs.warnings.add("Skipping unexpected main-header marker "
                    + Marker.name(marker));
        }
        r.seek(segEnd);
    }

    private CodingStyle readCod() throws IIOException {
        CodingStyle s = new CodingStyle();
        int scod = u8();
        boolean customPrecincts = (scod & 0x01) != 0;
        s.useSop = (scod & 0x02) != 0;
        s.useEph = (scod & 0x04) != 0;
        s.progression = u8();
        s.numLayers = u16();
        s.mct = u8();
        if (s.progression > 4) {
            throw new IIOException("Invalid progression order " + s.progression);
        }
        if (s.numLayers < 1) {
            throw new IIOException("Invalid layer count");
        }
        readSPcod(s, customPrecincts);
        return s;
    }

    private CodingStyle readCoc(int[] compOut) throws IIOException {
        compOut[0] = compIndex();
        CodingStyle s = new CodingStyle();
        int scoc = u8();
        readSPcod(s, (scoc & 0x01) != 0);
        return s;
    }

    private void readSPcod(CodingStyle s, boolean customPrecincts) throws IIOException {
        s.numLevels = u8();
        if (s.numLevels > 32) {
            throw new IIOException("Invalid decomposition level count " + s.numLevels);
        }
        s.xcb = (u8() & 0x0F) + 2;
        s.ycb = (u8() & 0x0F) + 2;
        if (s.xcb > 10 || s.ycb > 10 || s.xcb + s.ycb > 12) {
            throw new IIOException("Invalid code-block size " + s.xcb + "/" + s.ycb);
        }
        s.cbStyle = u8();
        s.wavelet = u8();
        if (s.wavelet > 1) {
            throw new IIOException("Unknown wavelet transform " + s.wavelet);
        }
        if (customPrecincts) {
            s.precinctSizes = new int[s.numLevels + 1];
            for (int r = 0; r <= s.numLevels; r++) {
                s.precinctSizes[r] = u8();
            }
        } else {
            s.precinctSizes = null;
        }
    }

    private int compIndex() throws IIOException {
        return cs.siz.numComponents < 257 ? u8() : u16();
    }

    private Quant readQuant(long segEnd) throws IIOException {
        int sq = u8();
        int style = sq & 0x1F;
        int guard = (sq >> 5) & 0x07;
        int[] exps;
        int[] mants;
        switch (style) {
            case Quant.STYLE_NONE -> {
                int n = (int) (segEnd - r.position());
                if (n < 1) {
                    throw new IIOException("Quantization segment without exponents");
                }
                exps = new int[n];
                mants = new int[0];
                for (int i = 0; i < n; i++) {
                    exps[i] = u8() >> 3;
                }
            }
            case Quant.STYLE_DERIVED -> {
                int v = u16();
                exps = new int[] {v >> 11};
                mants = new int[] {v & 0x7FF};
            }
            case Quant.STYLE_EXPOUNDED -> {
                int n = (int) (segEnd - r.position()) / 2;
                if (n < 1) {
                    throw new IIOException("Quantization segment without exponents");
                }
                exps = new int[n];
                mants = new int[n];
                for (int i = 0; i < n; i++) {
                    int v = u16();
                    exps[i] = v >> 11;
                    mants[i] = v & 0x7FF;
                }
            }
            default -> throw new IIOException("Unknown quantization style " + style);
        }
        return new Quant(style, guard, exps, mants);
    }

    private void readRgn(int[] rgn) throws IIOException {
        int c = compIndex();
        int srgn = u8();
        int shift = u8();
        if (srgn != 0) {
            throw new IIOException("Unknown ROI style " + srgn);
        }
        if (c < rgn.length) {
            rgn[c] = shift;
        }
    }

    private void readPoc(long segEnd, java.util.List<Poc> out) throws IIOException {
        int compBytes = cs.siz.numComponents < 257 ? 1 : 2;
        int entry = 5 + 2 * compBytes;
        while (segEnd - r.position() >= entry) {
            int rs = u8();
            int csn = compIndex();
            int lye = u16();
            int re = u8();
            int cen = compBytes == 1 ? u8() : u16();
            if (cen == 0) {
                cen = cs.siz.numComponents; // 0 means 256/16384 per spec
            }
            int prog = u8();
            if (prog > 4) {
                throw new IIOException("Invalid progression order " + prog);
            }
            out.add(new Poc(rs, csn, lye, re, Math.min(cen, cs.siz.numComponents), prog));
        }
    }

    private void finishPpm() {
        if (ppmRaw == null) {
            return;
        }
        byte[] raw = ppmRaw.toByteArray();
        cs.ppmChunks = new java.util.ArrayList<>();
        int q = 0;
        while (q + 4 <= raw.length) {
            int n = ((raw[q] & 0xFF) << 24) | ((raw[q + 1] & 0xFF) << 16)
                    | ((raw[q + 2] & 0xFF) << 8) | (raw[q + 3] & 0xFF);
            q += 4;
            if (n < 0 || q + n > raw.length) {
                cs.warnings.add("Truncated PPM data");
                n = Math.max(0, raw.length - q);
            }
            byte[] chunk = new byte[n];
            System.arraycopy(raw, q, chunk, 0, n);
            cs.ppmChunks.add(chunk);
            q += n;
        }
    }

    /** Reads one tile-part; on entry the SOT marker code has been consumed. */
    private void readTilePart() throws IIOException {
        long sotPos = r.position() - 2;
        int lsot = u16();
        if (lsot != 10) {
            throw new IIOException("Invalid SOT length " + lsot);
        }
        int tileIdx = u16();
        long psot = u32();
        int tpsot = u8();
        u8(); // TNsot (total tile-parts; informational)
        if (tileIdx >= cs.tiles.length) {
            throw new IIOException("Tile index " + tileIdx + " out of range");
        }
        Codestream.TileHeader th = cs.tiles[tileIdx];
        if (th == null) {
            th = new Codestream.TileHeader();
            cs.tiles[tileIdx] = th;
        }

        // tile-part header markers up to SOD
        while (true) {
            int marker = u16();
            if (marker == Marker.SOD) {
                break;
            }
            if (marker >= 0xFF30 && marker <= 0xFF3F) {
                continue; // reserved segment-less markers
            }
            int len = u16();
            long segEnd = r.position() + len - 2;
            if (len < 2 || segEnd > end) {
                throw new IIOException("Invalid length for " + Marker.name(marker)
                        + " in tile-part header");
            }
            switch (marker) {
                case Marker.COD -> th.cod = readCod();
                case Marker.COC -> {
                    int[] c = new int[1];
                    CodingStyle style = readCoc(c);
                    th.coc.put(c[0], style);
                }
                case Marker.QCD -> th.qcd = readQuant(segEnd);
                case Marker.QCC -> {
                    int c = compIndex();
                    th.qcc.put(c, readQuant(segEnd));
                }
                case Marker.RGN -> {
                    if (th.rgn == null) {
                        th.rgn = new int[cs.siz.numComponents];
                    }
                    readRgn(th.rgn);
                }
                case Marker.POC -> readPoc(segEnd, th.poc);
                case Marker.PPT -> {
                    if (th.ppt == null) {
                        th.ppt = new ByteArrayOutputStream();
                    }
                    u8(); // Zppt
                    th.ppt.writeBytes(r.readBytes((int) (segEnd - r.position())));
                }
                case Marker.PLT, Marker.COM -> {
                    // informational
                }
                default -> cs.warnings.add("Skipping unexpected tile-part marker "
                        + Marker.name(marker));
            }
            r.seek(segEnd);
        }

        long bodyStart = r.position();
        long tpEnd;
        if (psot == 0) {
            // last tile-part: all data up to (excluding) the EOC marker
            tpEnd = endKnown ? end : r.scanToEof(new byte[2]);
            if (endsWithEoc(tpEnd)) {
                tpEnd -= 2;
            }
        } else {
            if (psot < 14 || sotPos + psot < bodyStart) {
                // a tile-part cannot end inside its own header; a backward
                // Psot would also make the parser loop forever
                throw new IIOException("Invalid Psot " + psot + " for tile " + tileIdx);
            }
            tpEnd = sotPos + psot;
        }
        if (tpEnd > end && endKnown) {
            cs.warnings.add("Tile-part " + tileIdx + "." + tpsot + " extends past end of data");
            tpEnd = end;
        }

        Codestream.TilePart tp = new Codestream.TilePart(tileIdx, tpsot, bodyStart,
                Math.max(bodyStart, tpEnd));
        cs.tileParts.add(tp);
        th.parts.add(tp);
        r.seek(tpEnd);
    }

    /** True if the two bytes ending at {@code pos} are the EOC marker. */
    private boolean endsWithEoc(long pos) {
        if (pos - 2 < 0) {
            return false;
        }
        byte[] two = new byte[2];
        try {
            int a = src.read(pos - 2, two, 0, 2);
            return a == 2
                    && ((two[0] & 0xFF) << 8 | (two[1] & 0xFF)) == Marker.EOC;
        } catch (IOException e) {
            return false;
        }
    }
}
