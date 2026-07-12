package com.ebremer.cygnus.jpeg2000.codestream;

import com.ebremer.cygnus.jpeg2000.io.ByteSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOException;

/**
 * Parsed JPEG 2000 codestream: main header parameters, tile-part layout and
 * per-tile header overrides, with the parameter-precedence rules of
 * T.800 A.6 resolved by the accessor methods. Tile-part bodies are not held
 * in memory; they are ranges into the source, read on demand.
 */
public final class Codestream {

    /** The codestream bytes (tile-part bodies are ranges into this source). */
    public final ByteSource source;

    public Siz siz;
    public CodingStyle mainCod;
    public final Map<Integer, CodingStyle> mainCoc = new HashMap<>();
    public Quant mainQcd;
    public final Map<Integer, Quant> mainQcc = new HashMap<>();
    public int[] mainRgn;                       // per-component ROI shift (0 = none)
    public final List<Poc> mainPoc = new ArrayList<>();
    public final List<TilePart> tileParts = new ArrayList<>();
    public TileHeader[] tiles;                  // indexed by tile number, null if absent
    public List<byte[]> ppmChunks;              // one chunk per tile-part, codestream order
    public final List<String> warnings = new ArrayList<>();

    Codestream(ByteSource source) {
        this.source = source;
    }

    /** One tile-part: which tile it belongs to and where its body bytes live. */
    public static final class TilePart {
        public final int tile;
        public final int part;
        public final long bodyStart;
        public final long bodyEnd;

        TilePart(int tile, int part, long bodyStart, long bodyEnd) {
            this.tile = tile;
            this.part = part;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
        }
    }

    /** Header state accumulated from a tile's tile-part headers. */
    public static final class TileHeader {
        public CodingStyle cod;
        public final Map<Integer, CodingStyle> coc = new HashMap<>();
        public Quant qcd;
        public final Map<Integer, Quant> qcc = new HashMap<>();
        public int[] rgn;
        public final List<Poc> poc = new ArrayList<>();
        public ByteArrayOutputStream ppt;
        public final List<TilePart> parts = new ArrayList<>();
    }

    private TileHeader tileHeader(int tile) {
        return (tiles != null && tile < tiles.length) ? tiles[tile] : null;
    }

    /**
     * Coding style for one tile-component with COC/COD precedence applied:
     * tile COC &gt; tile COD &gt; main COC &gt; main COD for component
     * parameters; tile COD &gt; main COD for tile-wide parameters.
     */
    public CodingStyle style(int tile, int comp) {
        TileHeader th = tileHeader(tile);
        CodingStyle tileWide = (th != null && th.cod != null) ? th.cod : mainCod;
        CodingStyle compSrc = null;
        if (th != null && th.coc.containsKey(comp)) {
            compSrc = th.coc.get(comp);
        } else if (th != null && th.cod != null) {
            compSrc = th.cod;
        } else if (mainCoc.containsKey(comp)) {
            compSrc = mainCoc.get(comp);
        } else {
            compSrc = mainCod;
        }
        CodingStyle merged = tileWide.clone();
        merged.numLevels = compSrc.numLevels;
        merged.xcb = compSrc.xcb;
        merged.ycb = compSrc.ycb;
        merged.cbStyle = compSrc.cbStyle;
        merged.wavelet = compSrc.wavelet;
        merged.precinctSizes = compSrc.precinctSizes == null
                ? null : compSrc.precinctSizes.clone();
        return merged;
    }

    /** Quantization for one tile-component (tile QCC &gt; tile QCD &gt; main QCC &gt; main QCD). */
    public Quant quant(int tile, int comp) {
        TileHeader th = tileHeader(tile);
        if (th != null && th.qcc.containsKey(comp)) {
            return th.qcc.get(comp);
        }
        if (th != null && th.qcd != null) {
            return th.qcd;
        }
        if (mainQcc.containsKey(comp)) {
            return mainQcc.get(comp);
        }
        return mainQcd;
    }

    /** ROI max-shift for one tile-component (0 = no ROI). */
    public int rgnShift(int tile, int comp) {
        TileHeader th = tileHeader(tile);
        if (th != null && th.rgn != null) {
            return th.rgn[comp];
        }
        return mainRgn != null ? mainRgn[comp] : 0;
    }

    /** Progression order changes applying to a tile, or empty if none. */
    public List<Poc> progressionChanges(int tile) {
        TileHeader th = tileHeader(tile);
        if (th != null && !th.poc.isEmpty()) {
            return th.poc;
        }
        return mainPoc;
    }

    /**
     * Concatenated tile-part body bytes for a tile (its packet data stream),
     * read from the source on demand.
     */
    public byte[] tileBody(int tile) throws IIOException {
        TileHeader th = tileHeader(tile);
        if (th == null) {
            return new byte[0];
        }
        long total = 0;
        for (TilePart tp : th.parts) {
            total += tp.bodyEnd - tp.bodyStart;
        }
        if (total > Integer.MAX_VALUE - 8) {
            throw new IIOException("Tile " + tile + " compressed body exceeds 2 GiB");
        }
        byte[] out = new byte[(int) total];
        int pos = 0;
        for (TilePart tp : th.parts) {
            int n = (int) (tp.bodyEnd - tp.bodyStart);
            int got = readFully(tp.bodyStart, out, pos, n);
            pos += n;
            if (got < n) {
                warnings.add("Tile " + tile + " body truncated ("
                        + got + " of " + n + " bytes)");
                break;
            }
        }
        return out;
    }

    /** Reads up to {@code len} bytes at {@code srcPos}; returns bytes read. */
    private int readFully(long srcPos, byte[] dst, int off, int len) throws IIOException {
        int done = 0;
        try {
            while (done < len) {
                int n = source.read(srcPos + done, dst, off + done, len - done);
                if (n <= 0) {
                    break;
                }
                done += n;
            }
        } catch (IOException e) {
            throw new IIOException("Read error in tile body", e);
        }
        return done;
    }

    /**
     * Packed packet-header bytes for a tile (from PPM or PPT marker segments),
     * or {@code null} if packet headers are in-line in the tile body.
     */
    public byte[] packedHeaders(int tile) {
        if (ppmChunks != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < tileParts.size() && i < ppmChunks.size(); i++) {
                if (tileParts.get(i).tile == tile) {
                    out.writeBytes(ppmChunks.get(i));
                }
            }
            return out.toByteArray();
        }
        TileHeader th = tileHeader(tile);
        if (th != null && th.ppt != null) {
            return th.ppt.toByteArray();
        }
        return null;
    }
}
