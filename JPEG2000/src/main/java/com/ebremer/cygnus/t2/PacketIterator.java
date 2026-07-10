package com.ebremer.cygnus.t2;

import com.ebremer.cygnus.codestream.CodingStyle;
import com.ebremer.cygnus.codestream.Poc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Produces the packet sequence of one tile (T.800 B.12): the five
 * progression orders, per-component resolution counts, and progression
 * order changes (POC). Each packet is emitted exactly once; overlapping
 * POC ranges are de-duplicated as required by the packet-once rule.
 */
public final class PacketIterator {

    /** One packet identity: component, resolution, precinct, layer. */
    public record Packet(int comp, int res, int precinct, int layer) {
    }

    private final Tile tile;
    private final int numComps;
    private final int maxResolutions;
    private final int numLayers;
    private final int[] xrsiz, yrsiz;
    private final List<Packet> out = new ArrayList<>();
    private final Set<Packet> emitted = new HashSet<>();
    /** Per (comp, res): reference-grid x/y positions that start a precinct. */
    private final Map<Long, Map<Long, Integer>> trigX = new HashMap<>();
    private final Map<Long, Map<Long, Integer>> trigY = new HashMap<>();

    private PacketIterator(Tile tile, int numLayers, int[] xrsiz, int[] yrsiz) {
        this.tile = tile;
        this.numComps = tile.comps.length;
        this.numLayers = numLayers;
        this.xrsiz = xrsiz;
        this.yrsiz = yrsiz;
        int maxRes = 0;
        for (TileComponent tc : tile.comps) {
            maxRes = Math.max(maxRes, tc.resolutions.length);
        }
        this.maxResolutions = maxRes;
        for (int c = 0; c < numComps; c++) {
            for (int r = 0; r < tile.comps[c].resolutions.length; r++) {
                buildTriggers(c, r);
            }
        }
    }

    /**
     * Computes the packet order for a tile.
     *
     * @param pocs progression changes applying to this tile (may be empty)
     */
    public static List<Packet> sequence(Tile tile, CodingStyle tileStyle,
                                        List<Poc> pocs, int[] xrsiz, int[] yrsiz) {
        PacketIterator it = new PacketIterator(tile, tileStyle.numLayers, xrsiz, yrsiz);
        if (pocs.isEmpty()) {
            it.runSegment(new Poc(0, 0, tileStyle.numLayers, it.maxResolutions,
                    it.numComps, tileStyle.progression));
        } else {
            for (Poc poc : pocs) {
                it.runSegment(poc);
            }
        }
        return it.out;
    }

    // ---- precinct position triggers (B.12.1.3 - B.12.1.5) ----

    private static long key(int c, int r) {
        return ((long) c << 8) | r;
    }

    private void buildTriggers(int c, int r) {
        Resolution res = tile.comps[c].resolutions[r];
        Map<Long, Integer> mx = new HashMap<>();
        Map<Long, Integer> my = new HashMap<>();
        if (res.numPrecWide > 0 && res.numPrecHigh > 0) {
            int nd = tile.comps[c].style.numLevels - r;
            long wScale = (long) xrsiz[c] << nd;
            long hScale = (long) yrsiz[c] << nd;
            int gridX0 = res.x0 >> res.ppx;
            for (int i = 0; i < res.numPrecWide; i++) {
                long sx = ((long) (gridX0 + i)) << res.ppx;
                long trig = sx < res.x0 ? tile.x0 : sx * wScale;
                mx.putIfAbsent(trig, i);
            }
            int gridY0 = res.y0 >> res.ppy;
            for (int j = 0; j < res.numPrecHigh; j++) {
                long sy = ((long) (gridY0 + j)) << res.ppy;
                long trig = sy < res.y0 ? tile.y0 : sy * hScale;
                my.putIfAbsent(trig, j);
            }
        }
        trigX.put(key(c, r), mx);
        trigY.put(key(c, r), my);
    }

    /** Precinct index triggered at reference-grid position (x, y), or -1. */
    private int precinctAt(int c, int r, long x, long y) {
        Integer i = trigX.get(key(c, r)).get(x);
        Integer j = trigY.get(key(c, r)).get(y);
        if (i == null || j == null) {
            return -1;
        }
        return j * tile.comps[c].resolutions[r].numPrecWide + i;
    }

    private int numPrecincts(int c, int r) {
        Resolution res = tile.comps[c].resolutions[r];
        return res.numPrecWide * res.numPrecHigh;
    }

    private int numRes(int c) {
        return tile.comps[c].resolutions.length;
    }

    // ---- progression segments ----

    private void runSegment(Poc poc) {
        int rs = Math.max(0, poc.rspoc());
        int re = Math.min(maxResolutions, poc.repoc());
        int cst = Math.max(0, poc.cspoc());
        int ce = Math.min(numComps, poc.cepoc());
        int le = Math.min(numLayers, poc.lyepoc());
        switch (poc.progression()) {
            case CodingStyle.LRCP -> {
                for (int l = 0; l < le; l++) {
                    for (int r = rs; r < re; r++) {
                        for (int c = cst; c < ce; c++) {
                            emitAllPrecincts(c, r, l);
                        }
                    }
                }
            }
            case CodingStyle.RLCP -> {
                for (int r = rs; r < re; r++) {
                    for (int l = 0; l < le; l++) {
                        for (int c = cst; c < ce; c++) {
                            emitAllPrecincts(c, r, l);
                        }
                    }
                }
            }
            case CodingStyle.RPCL -> {
                for (int r = rs; r < re; r++) {
                    int rr = r;
                    forPositions(cst, ce, r, r + 1, (y, x) -> {
                        for (int c = cst; c < ce; c++) {
                            if (rr < numRes(c)) {
                                emitAtPosition(c, rr, x, y, le);
                            }
                        }
                    });
                }
            }
            case CodingStyle.PCRL -> forPositions(cst, ce, rs, re, (y, x) -> {
                for (int c = cst; c < ce; c++) {
                    for (int r = rs; r < Math.min(re, numRes(c)); r++) {
                        emitAtPosition(c, r, x, y, le);
                    }
                }
            });
            case CodingStyle.CPRL -> {
                for (int c = cst; c < ce; c++) {
                    int cc = c;
                    forPositions(c, c + 1, rs, re, (y, x) -> {
                        for (int r = rs; r < Math.min(re, numRes(cc)); r++) {
                            emitAtPosition(cc, r, x, y, le);
                        }
                    });
                }
            }
            default -> throw new IllegalArgumentException(
                    "Unknown progression order " + poc.progression());
        }
    }

    private interface PositionVisitor {
        void visit(long y, long x);
    }

    /**
     * Visits, in raster order, every reference-grid position that starts a
     * precinct for any (component, resolution) in the given ranges. This is
     * equivalent to the spec's exhaustive scan over the tile area, since
     * positions between triggers never match any precinct.
     */
    private void forPositions(int cs, int ce, int rs, int re, PositionVisitor visitor) {
        TreeSet<Long> ys = new TreeSet<>();
        TreeSet<Long> xs = new TreeSet<>();
        for (int c = cs; c < Math.min(ce, numComps); c++) {
            for (int r = rs; r < Math.min(re, numRes(c)); r++) {
                ys.addAll(trigY.get(key(c, r)).keySet());
                xs.addAll(trigX.get(key(c, r)).keySet());
            }
        }
        for (long y : ys) {
            for (long x : xs) {
                visitor.visit(y, x);
            }
        }
    }

    private void emitAtPosition(int c, int r, long x, long y, int layerEnd) {
        int p = precinctAt(c, r, x, y);
        if (p < 0) {
            return;
        }
        for (int l = 0; l < layerEnd; l++) {
            emit(c, r, p, l);
        }
    }

    private void emitAllPrecincts(int c, int r, int l) {
        if (r >= numRes(c)) {
            return;
        }
        int np = numPrecincts(c, r);
        for (int p = 0; p < np; p++) {
            emit(c, r, p, l);
        }
    }

    /** Safety cap: no legitimate tile needs more packets than this. */
    private static final int MAX_PACKETS_PER_TILE = 1 << 22;

    private void emit(int c, int r, int p, int l) {
        Packet packet = new Packet(c, r, p, l);
        if (emitted.add(packet)) {
            if (out.size() >= MAX_PACKETS_PER_TILE) {
                throw new DecodeLimitException(
                        "Tile packet count exceeds the safety limit");
            }
            out.add(packet);
        }
    }
}
