package com.ebremer.cygnus.jpeg2000.decoder;

import com.ebremer.cygnus.jpeg2000.codestream.Codestream;
import com.ebremer.cygnus.jpeg2000.codestream.CodestreamParser;
import com.ebremer.cygnus.jpeg2000.codestream.CodingStyle;
import com.ebremer.cygnus.jpeg2000.codestream.Quant;
import com.ebremer.cygnus.jpeg2000.codestream.Siz;
import com.ebremer.cygnus.jpeg2000.io.ByteSource;
import com.ebremer.cygnus.jpeg2000.io.ImageInputStreamSource;
import com.ebremer.cygnus.jpeg2000.jp2.Jp2Info;
import com.ebremer.cygnus.jpeg2000.jp2.Jp2Parser;
import com.ebremer.cygnus.jpeg2000.t1.BlockDecoder;
import com.ebremer.cygnus.jpeg2000.t2.Band;
import com.ebremer.cygnus.jpeg2000.t2.CodeBlock;
import com.ebremer.cygnus.jpeg2000.t2.PacketBitReader;
import com.ebremer.cygnus.jpeg2000.t2.PacketDecoder;
import com.ebremer.cygnus.jpeg2000.t2.PacketIterator;
import com.ebremer.cygnus.jpeg2000.t2.Precinct;
import com.ebremer.cygnus.jpeg2000.t2.Resolution;
import com.ebremer.cygnus.jpeg2000.t2.Tile;
import com.ebremer.cygnus.jpeg2000.t2.TileComponent;
import com.ebremer.cygnus.jpeg2000.wavelet.InverseDWT;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOException;

/**
 * Top-level JPEG 2000 Part 1 decoder: accepts a JP2 file or a raw codestream
 * and produces a {@link DecodedImage} with all stages applied - Tier-2,
 * Tier-1, dequantization, inverse DWT, inverse component transform, DC level
 * shift, palette expansion and channel definitions.
 *
 * <p>Decoding is selective at tile granularity: {@link #decode(Rectangle)}
 * entropy-decodes only the tiles intersecting the requested region.
 * {@link #shape()} answers structural queries (size, channels, colour,
 * tiling) from the headers alone, without decoding any samples.</p>
 */
public final class Jpeg2000Decoder {

    /** Return false from {@link #tileDecoded} to abort decoding. */
    public interface ProgressListener {
        boolean tileDecoded(int tilesDone, int tileTotal);
    }

    private final List<String> warnings = new ArrayList<>();
    private ProgressListener listener;
    private Codestream cs;
    private Jp2Info jp2;
    private boolean applyMct = true;
    private int parallelism = Runtime.getRuntime().availableProcessors();
    private long memoryLimit = defaultMemoryLimit();

    private static long defaultMemoryLimit() {
        long max = Runtime.getRuntime().maxMemory();
        if (max == Long.MAX_VALUE) {
            return 8L << 30;
        }
        return Math.max(64L << 20, max / 2);
    }

    public List<String> warnings() {
        return warnings;
    }

    public void setProgressListener(ProgressListener listener) {
        this.listener = listener;
    }

    /**
     * Enables or disables the inverse multiple component transform
     * (RCT/ICT). Disabling yields the raw codestream components as coded
     * (used e.g. by minimal/Class-0 decoding). Default: enabled.
     */
    public void setInverseComponentTransform(boolean enabled) {
        this.applyMct = enabled;
    }

    /**
     * Maximum worker threads used while decoding (entropy decoding of
     * code-blocks and per-component wavelet synthesis fan out onto the
     * common ForkJoin pool). Default: the number of available processors;
     * 1 decodes on the calling thread only. Decoded output is identical
     * at any setting.
     */
    public void setParallelism(int threads) {
        this.parallelism = Math.max(1, threads);
    }

    /** The parsed codestream headers (available after {@code open}). */
    public Codestream codestream() {
        ensureOpen();
        return cs;
    }

    /** JP2 container metadata, or null for a raw codestream. */
    public Jp2Info containerInfo() {
        ensureOpen();
        return jp2;
    }

    /**
     * Rough upper bound, in bytes, on the working memory a single decode
     * call may allocate (sample planes and per-tile coefficient buffers).
     * Requests beyond it fail with an IIOException instead of exhausting
     * the heap - untrusted files can declare enormous dimensions. Default:
     * half the JVM's maximum heap (at least 64 MiB).
     */
    public void setMemoryLimit(long bytes) {
        this.memoryLimit = Math.max(1L << 20, bytes);
    }

    private void checkBudget(long bytes, String what) throws IIOException {
        if (bytes > memoryLimit) {
            throw new IIOException(what + " needs about " + (bytes >> 20)
                    + " MiB, over the decoder memory limit of "
                    + (memoryLimit >> 20) + " MiB (see setMemoryLimit)");
        }
    }

    /** Runs independent decode tasks, honoring {@link #setParallelism}. */
    private void runTasks(List<Runnable> tasks) {
        int n = Math.min(parallelism, tasks.size());
        if (n <= 1) {
            for (Runnable t : tasks) {
                t.run();
            }
            return;
        }
        java.util.concurrent.atomic.AtomicInteger next =
                new java.util.concurrent.atomic.AtomicInteger();
        Runnable worker = () -> {
            int i;
            while ((i = next.getAndIncrement()) < tasks.size()) {
                tasks.get(i).run();
            }
        };
        List<java.util.concurrent.ForkJoinTask<?>> spawned = new ArrayList<>(n - 1);
        for (int k = 1; k < n; k++) {
            spawned.add(java.util.concurrent.ForkJoinPool.commonPool().submit(worker));
        }
        worker.run();
        for (java.util.concurrent.ForkJoinTask<?> f : spawned) {
            f.join();
        }
    }

    /** Parses container and codestream headers; no samples are decoded yet. */
    public void open(byte[] data) throws IIOException {
        open(ByteSource.of(data));
    }

    /**
     * Opens a seekable stream without buffering it: headers are parsed now
     * (reading only header bytes); tile-part bodies are fetched from the
     * stream on demand during decoding. The stream must support backward
     * seeking and must stay open for the lifetime of this decoder.
     */
    public void open(javax.imageio.stream.ImageInputStream stream) throws IIOException {
        open(new ImageInputStreamSource(stream));
    }

    /** Opens any random-access byte source (headers parsed, bodies deferred). */
    public void open(ByteSource src) throws IIOException {
        warnings.clear();
        byte[] head = new byte[12];
        int got = 0;
        try {
            while (got < head.length) {
                int n = src.read(got, head, got, head.length - got);
                if (n <= 0) {
                    break;
                }
                got += n;
            }
        } catch (IOException e) {
            throw new IIOException("Cannot read stream header", e);
        }
        byte[] sniff = got == head.length ? head : java.util.Arrays.copyOf(head, got);
        Jp2Info info = null;
        long off = 0;
        long len;
        try {
            len = src.length();
        } catch (IOException e) {
            len = -1;
        }
        if (Jp2Parser.isJp2(sniff)) {
            info = Jp2Parser.parse(src);
            off = info.codestreamOffset;
            len = info.codestreamLength;
        } else if (!Jp2Parser.isRawCodestream(sniff)) {
            throw new IIOException("Neither a JP2 file nor a JPEG 2000 codestream");
        }
        this.cs = CodestreamParser.parse(src, off, len);
        this.jp2 = info;
        warnings.addAll(cs.warnings);
    }

    private void ensureOpen() {
        if (cs == null) {
            throw new IllegalStateException("Call open(data) first");
        }
    }

    /** Convenience: {@link #open} followed by a full decode. */
    public DecodedImage decode(byte[] data) throws IIOException {
        open(data);
        return decode();
    }

    /** Decodes the full image region at full resolution. */
    public DecodedImage decode() throws IIOException {
        return decode(0);
    }

    /**
     * Decodes the full image at a reduced resolution: the {@code reduction}
     * highest DWT levels are discarded, yielding an image of roughly
     * 1/2^reduction scale (exact dimensions follow the reference-grid
     * rounding rules, see {@link DecodedImage#imageWidth}).
     */
    public DecodedImage decode(int reduction) throws IIOException {
        ensureOpen();
        return decode(fullRect(reduction), reduction);
    }

    /** Structure of the image from the headers only ({@code samples} null). */
    public DecodedImage shape() throws IIOException {
        return shape(0);
    }

    /** Image structure at a reduced resolution, from the headers only. */
    public DecodedImage shape(int reduction) throws IIOException {
        ensureOpen();
        checkReduction(reduction);
        Rectangle full = fullRect(reduction);
        return buildChannels(cs.siz, null, jp2, full,
                compRanges(cs.siz, full, reduction), reduction);
    }

    /** Decodes the tiles covering {@code region} at full resolution. */
    public DecodedImage decode(Rectangle region) throws IIOException {
        return decode(region, 0);
    }

    /**
     * Decodes the tiles covering {@code region} at a reduced resolution.
     * The region is given in the coordinates of the reduced image, i.e.
     * (0,0) is the top-left pixel of the 1/2^reduction-scale image.
     */
    public DecodedImage decode(Rectangle region, int reduction) throws IIOException {
        ensureOpen();
        checkReduction(reduction);
        Rectangle r = region.intersection(fullRect(reduction));
        if (r.isEmpty()) {
            throw new IIOException("Requested region " + region + " is outside the image");
        }
        int[][] ranges = compRanges(cs.siz, r, reduction);
        int[][] comps = decodeComponents(cs, ranges, reduction);
        if (comps == null) {
            throw new IIOException("Decoding aborted");
        }
        return buildChannels(cs.siz, comps, jp2, r, ranges, reduction);
    }

    /**
     * The largest usable resolution reduction: the minimum number of
     * decomposition levels declared by any coding style in the codestream.
     */
    public int maxReduction() {
        ensureOpen();
        int min = cs.mainCod.numLevels;
        for (CodingStyle s : cs.mainCoc.values()) {
            min = Math.min(min, s.numLevels);
        }
        for (Codestream.TileHeader th : cs.tiles) {
            if (th == null) {
                continue;
            }
            if (th.cod != null) {
                min = Math.min(min, th.cod.numLevels);
            }
            for (CodingStyle s : th.coc.values()) {
                min = Math.min(min, s.numLevels);
            }
        }
        return min;
    }

    private void checkReduction(int reduction) throws IIOException {
        if (reduction < 0) {
            throw new IllegalArgumentException("Negative resolution reduction");
        }
        if (reduction > maxReduction()) {
            throw new IIOException("Resolution reduction " + reduction
                    + " exceeds the decomposition level count (max "
                    + maxReduction() + ")");
        }
    }

    /** Whole image region in reduced-grid coordinates. */
    private Rectangle fullRect(int reduction) {
        Siz siz = cs.siz;
        long sc = 1L << reduction;
        int w = (int) (Math.ceilDiv(siz.xsiz, sc) - Math.ceilDiv(siz.xosiz, sc));
        int h = (int) (Math.ceilDiv(siz.ysiz, sc) - Math.ceilDiv(siz.yosiz, sc));
        return new Rectangle(0, 0, w, h);
    }

    // ---- component windows ----

    /**
     * Per component: the window {cx0, cy0, cx1, cy1} on the component's
     * reduced grid (subsampled by xrsiz*2^reduction from the reference
     * grid) of samples whose coverage intersects the region. On the reduced
     * grid a component sample covers xrsiz reduced-grid units, so the
     * region mapping uses the plain subsampling factors.
     */
    private static int[][] compRanges(Siz siz, Rectangle region, int reduction) {
        int nc = siz.numComponents;
        int[][] ranges = new int[nc][4];
        long sc = 1L << reduction;
        int gx0 = (int) Math.ceilDiv(siz.xosiz, sc) + region.x;
        int gy0 = (int) Math.ceilDiv(siz.yosiz, sc) + region.y;
        int gx1 = gx0 + region.width;
        int gy1 = gy0 + region.height;
        for (int c = 0; c < nc; c++) {
            int dx = siz.xrsiz[c];
            int dy = siz.yrsiz[c];
            long fx = (long) dx << reduction;
            long fy = (long) dy << reduction;
            int startX = (int) Math.ceilDiv(siz.xosiz, fx);
            int endX = (int) Math.ceilDiv(siz.xsiz, fx);
            int startY = (int) Math.ceilDiv(siz.yosiz, fy);
            int endY = (int) Math.ceilDiv(siz.ysiz, fy);
            int cx0 = Math.clamp(Math.floorDiv(gx0, dx), startX, endX);
            int cx1 = Math.clamp(Math.floorDiv(gx1 - 1, dx) + 1, cx0, endX);
            int cy0 = Math.clamp(Math.floorDiv(gy0, dy), startY, endY);
            int cy1 = Math.clamp(Math.floorDiv(gy1 - 1, dy) + 1, cy0, endY);
            ranges[c] = new int[] {cx0, cy0, cx1, cy1};
        }
        return ranges;
    }

    // ---- codestream decoding ----

    /** Decodes the tiles intersecting the component windows. */
    private int[][] decodeComponents(Codestream cs, int[][] ranges, int reduction)
            throws IIOException {
        Siz siz = cs.siz;
        int nc = siz.numComponents;
        long planeBytes = 0;
        for (int c = 0; c < nc; c++) {
            long area = (long) (ranges[c][2] - ranges[c][0]) * (ranges[c][3] - ranges[c][1]);
            if (area > Integer.MAX_VALUE - 8) {
                throw new IIOException("Region too large to decode into one array");
            }
            planeBytes += 4 * area;
        }
        checkBudget(planeBytes, "Decoding this region");
        int[][] out = new int[nc][];
        for (int c = 0; c < nc; c++) {
            long area = (long) (ranges[c][2] - ranges[c][0]) * (ranges[c][3] - ranges[c][1]);
            out[c] = new int[(int) area];
        }

        List<Integer> needed = new ArrayList<>();
        for (int t = 0; t < siz.numTiles(); t++) {
            if (tileIntersects(siz, t, ranges, reduction)) {
                needed.add(t);
            }
        }
        int done = 0;
        for (int t : needed) {
            if (cs.tiles[t] == null) {
                warnings.add("Tile " + t + " missing from codestream");
            } else {
                decodeTile(cs, t, out, ranges, reduction);
            }
            done++;
            if (listener != null && !listener.tileDecoded(done, needed.size())) {
                return null;
            }
        }
        return out;
    }

    /** True if any component window overlaps the tile-component of tile {@code t}. */
    private static boolean tileIntersects(Siz siz, int t, int[][] ranges, int reduction) {
        int[] tb = siz.tileBounds(t);
        for (int c = 0; c < siz.numComponents; c++) {
            long fx = (long) siz.xrsiz[c] << reduction;
            long fy = (long) siz.yrsiz[c] << reduction;
            int tcx0 = (int) Math.ceilDiv(tb[0], fx);
            int tcy0 = (int) Math.ceilDiv(tb[1], fy);
            int tcx1 = (int) Math.ceilDiv(tb[2], fx);
            int tcy1 = (int) Math.ceilDiv(tb[3], fy);
            if (Math.max(tcx0, ranges[c][0]) < Math.min(tcx1, ranges[c][2])
                    && Math.max(tcy0, ranges[c][1]) < Math.min(tcy1, ranges[c][3])) {
                return true;
            }
        }
        return false;
    }

    private void decodeTile(Codestream cs, int t, int[][] out, int[][] ranges,
                            int reduction) throws IIOException {
        Siz siz = cs.siz;
        // budget check before building any tile structures: coefficient
        // buffers, code-block/precinct objects and synthesis planes all
        // scale with the tile-component areas (~16 bytes per sample)
        {
            int[] tb = siz.tileBounds(t);
            long tileBytes = 0;
            for (int c = 0; c < siz.numComponents; c++) {
                long tw = Math.ceilDiv((long) tb[2], siz.xrsiz[c])
                        - Math.ceilDiv((long) tb[0], siz.xrsiz[c]);
                long th = Math.ceilDiv((long) tb[3], siz.yrsiz[c])
                        - Math.ceilDiv((long) tb[1], siz.yrsiz[c]);
                tileBytes += 16 * tw * th;
            }
            checkBudget(tileBytes, "Decoding tile " + t);
        }
        Tile tile;
        CodingStyle tileStyle;
        try {
            tile = Tile.build(cs, t);
            tileStyle = cs.style(t, 0);
        } catch (com.ebremer.cygnus.jpeg2000.t2.DecodeLimitException e) {
            throw new IIOException(e.getMessage());
        }
        int nc = tile.comps.length;

        // highest resolution level kept, per component
        int[] maxRes = new int[nc];
        for (int c = 0; c < nc; c++) {
            maxRes[c] = tile.comps[c].style.numLevels - reduction;
            if (maxRes[c] < 0) {
                throw new IIOException("Reduction " + reduction + " exceeds the "
                        + tile.comps[c].style.numLevels + " decomposition levels of tile "
                        + t + " component " + c);
            }
        }

        // Tier-2: distribute packet bytes onto code-blocks; packets of
        // discarded resolutions are parsed (the stream is sequential) but
        // their codeword bytes are not retained
        byte[] body = cs.tileBody(t);
        byte[] packed = cs.packedHeaders(t);
        PacketBitReader hdr = packed != null
                ? new PacketBitReader(packed, 0, packed.length) : null;
        List<PacketIterator.Packet> seq;
        try {
            seq = PacketIterator.sequence(
                    tile, tileStyle, cs.progressionChanges(t), siz.xrsiz, siz.yrsiz);
        } catch (com.ebremer.cygnus.jpeg2000.t2.DecodeLimitException e) {
            throw new IIOException(e.getMessage());
        }
        int cursor = 0;
        for (PacketIterator.Packet pk : seq) {
            if (cursor < 0 || (hdr == null && cursor >= body.length)) {
                break; // truncated codestream: keep what has been decoded
            }
            TileComponent tc = tile.comps[pk.comp()];
            Resolution res = tc.resolutions[pk.res()];
            if (pk.precinct() >= res.precincts.length) {
                continue;
            }
            cursor = PacketDecoder.decode(res, pk.precinct(), pk.layer(), body, cursor,
                    hdr, tileStyle.useSop, tileStyle.useEph, tc.style.cbStyle,
                    pk.res() <= maxRes[pk.comp()]);
        }

        // Tier-1: entropy-decode the code-blocks of the kept resolutions;
        // blocks are independent and write disjoint band regions, so they
        // fan out onto worker threads
        List<Runnable> blockTasks = new ArrayList<>();
        List<String> warn = parallelism > 1
                ? java.util.Collections.synchronizedList(warnings) : warnings;
        for (int c = 0; c < nc; c++) {
            TileComponent tc = tile.comps[c];
            int cbSty = tc.style.cbStyle;
            for (int r = 0; r <= maxRes[c]; r++) {
                Resolution res = tc.resolutions[r];
                for (Precinct prec : res.precincts) {
                    for (int b = 0; b < prec.blocks.length; b++) {
                        Band band = res.bands[b];
                        if (prec.blocks[b].length > 0) {
                            band.coeffs(); // allocate before concurrent writes
                        }
                        for (CodeBlock cb : prec.blocks[b]) {
                            blockTasks.add(() ->
                                    BlockDecoder.decode(cb, band, cbSty, warn));
                        }
                    }
                }
            }
        }
        runTasks(blockTasks);

        // inverse DWT per component, up to the kept resolution
        Object[] planes = new Object[nc];
        boolean[] intPath = new boolean[nc];
        List<Runnable> dwtTasks = new ArrayList<>(nc);
        for (int c = 0; c < nc; c++) {
            final int fc = c;
            TileComponent tc = tile.comps[c];
            intPath[c] = tc.style.reversible() && tc.quant.style == Quant.STYLE_NONE;
            boolean intp = intPath[c];
            int mr = maxRes[c];
            dwtTasks.add(() -> planes[fc] = intp
                    ? InverseDWT.reconstructInt(tc, mr)
                    : InverseDWT.reconstructFloat(tc, mr));
        }
        runTasks(dwtTasks);

        // reduced tile-component bounds
        int[] rx0 = new int[nc], ry0 = new int[nc], rx1 = new int[nc], ry1 = new int[nc];
        for (int c = 0; c < nc; c++) {
            Resolution top = tile.comps[c].resolutions[maxRes[c]];
            rx0[c] = top.x0;
            ry0[c] = top.y0;
            rx1[c] = top.x1;
            ry1[c] = top.y1;
        }

        // inverse multiple component transform (Annex G)
        if (tileStyle.mct == 1 && applyMct && nc >= 3) {
            boolean sameDims = rx1[0] - rx0[0] == rx1[1] - rx0[1]
                    && rx1[0] - rx0[0] == rx1[2] - rx0[2]
                    && ry1[0] - ry0[0] == ry1[1] - ry0[1]
                    && ry1[0] - ry0[0] == ry1[2] - ry0[2];
            if (!sameDims) {
                warnings.add("MCT set but components 0-2 differ in size; skipping");
            } else if (intPath[0] && intPath[1] && intPath[2]) {
                inverseRct((int[]) planes[0], (int[]) planes[1], (int[]) planes[2]);
            } else if (!intPath[0] && !intPath[1] && !intPath[2]) {
                inverseIct((float[]) planes[0], (float[]) planes[1], (float[]) planes[2]);
            } else {
                warnings.add("MCT set but components 0-2 mix transforms; skipping");
            }
        }

        // DC level shift, clamp, copy the window intersection into the output
        List<Runnable> copyTasks = new ArrayList<>(nc);
        for (int c = 0; c < nc; c++) {
            final int fc = c;
            copyTasks.add(() -> {
                int prec = siz.precision[fc];
                boolean sgn = siz.signed[fc];
                int shift = sgn ? 0 : 1 << (prec - 1);
                int lo = sgn ? -(1 << (prec - 1)) : 0;
                int hi = sgn ? (1 << (prec - 1)) - 1 : (1 << prec) - 1;
                int cx0 = ranges[fc][0], cy0 = ranges[fc][1];
                int cx1 = ranges[fc][2], cy1 = ranges[fc][3];
                int cw = cx1 - cx0;
                int xStart = Math.max(rx0[fc], cx0);
                int xEnd = Math.min(rx1[fc], cx1);
                int yStart = Math.max(ry0[fc], cy0);
                int yEnd = Math.min(ry1[fc], cy1);
                int tw = rx1[fc] - rx0[fc];
                if (intPath[fc]) {
                    int[] p = (int[]) planes[fc];
                    for (int y = yStart; y < yEnd; y++) {
                        int src = (y - ry0[fc]) * tw - rx0[fc];
                        int dst = (y - cy0) * cw - cx0;
                        for (int x = xStart; x < xEnd; x++) {
                            int v = p[src + x] + shift;
                            out[fc][dst + x] = v < lo ? lo : Math.min(v, hi);
                        }
                    }
                } else {
                    float[] p = (float[]) planes[fc];
                    for (int y = yStart; y < yEnd; y++) {
                        int src = (y - ry0[fc]) * tw - rx0[fc];
                        int dst = (y - cy0) * cw - cx0;
                        for (int x = xStart; x < xEnd; x++) {
                            int v = Math.round(p[src + x]) + shift;
                            out[fc][dst + x] = v < lo ? lo : Math.min(v, hi);
                        }
                    }
                }
            });
        }
        runTasks(copyTasks);
    }

    /** Inverse reversible component transform (G-6/G-7). */
    private static void inverseRct(int[] y0, int[] y1, int[] y2) {
        for (int i = 0; i < y0.length; i++) {
            int g = y0[i] - ((y2[i] + y1[i]) >> 2);
            int r = y2[i] + g;
            int b = y1[i] + g;
            y0[i] = r;
            y1[i] = g;
            y2[i] = b;
        }
    }

    /** Inverse irreversible component transform (G-9). */
    private static void inverseIct(float[] y0, float[] y1, float[] y2) {
        for (int i = 0; i < y0.length; i++) {
            float y = y0[i];
            float cb = y1[i];
            float cr = y2[i];
            y0[i] = y + 1.402f * cr;
            y1[i] = y - 0.344136f * cb - 0.714136f * cr;
            y2[i] = y + 1.772f * cb;
        }
    }

    // ---- channel construction (palette, cmap, cdef, colourspace) ----

    private DecodedImage buildChannels(Siz siz, int[][] comps, Jp2Info jp2,
                                       Rectangle region, int[][] ranges, int reduction)
            throws IIOException {
        DecodedImage img;
        if (jp2 != null && jp2.hasPalette()) {
            int n = jp2.cmapComponent.length;
            img = new DecodedImage(region.width, region.height, n);
            for (int ch = 0; ch < n; ch++) {
                int src = jp2.cmapComponent[ch];
                if (src >= siz.numComponents) {
                    throw new IIOException("cmap references missing component " + src);
                }
                setChannelGeometry(img, ch, siz, src, ranges);
                if (jp2.cmapType[ch] == 1) {
                    int col = jp2.cmapColumn[ch];
                    if (jp2.palette == null || col >= jp2.palette.length) {
                        throw new IIOException("cmap references missing palette column");
                    }
                    if (comps != null) {
                        int[] lut = jp2.palette[col];
                        int[] source = comps[src];
                        int[] mapped = new int[source.length];
                        for (int i = 0; i < source.length; i++) {
                            int idx = Math.max(0, Math.min(lut.length - 1, source[i]));
                            mapped[i] = lut[idx];
                        }
                        img.samples[ch] = mapped;
                    }
                    img.depth[ch] = jp2.paletteDepth[col];
                    img.signed[ch] = jp2.paletteSigned[col];
                } else {
                    if (comps != null) {
                        img.samples[ch] = comps[src];
                    }
                    img.depth[ch] = siz.precision[src];
                    img.signed[ch] = siz.signed[src];
                }
            }
        } else {
            int n = siz.numComponents;
            img = new DecodedImage(region.width, region.height, n);
            for (int c = 0; c < n; c++) {
                setChannelGeometry(img, c, siz, c, ranges);
                if (comps != null) {
                    img.samples[c] = comps[c];
                }
                img.depth[c] = siz.precision[c];
                img.signed[c] = siz.signed[c];
            }
        }

        // channel definitions: find alpha and colour ordering
        int numColour = img.numChannels;
        if (jp2 != null && jp2.cdefChannel != null) {
            List<int[]> colour = new ArrayList<>(); // {assoc, channel}
            for (int i = 0; i < jp2.cdefChannel.length; i++) {
                int ch = jp2.cdefChannel[i];
                if (ch >= img.numChannels) {
                    continue;
                }
                switch (jp2.cdefType[i]) {
                    case 1 -> img.alphaChannel = ch;
                    case 2 -> {
                        img.alphaChannel = ch;
                        img.alphaPremultiplied = true;
                    }
                    default -> colour.add(new int[] {jp2.cdefAssoc[i], ch});
                }
            }
            if (!colour.isEmpty()) {
                colour.sort((a, b) -> Integer.compare(a[0], b[0]));
                img.colourChannels = new int[colour.size()];
                for (int i = 0; i < colour.size(); i++) {
                    img.colourChannels[i] = colour.get(i)[1];
                }
                numColour = colour.size();
            }
        }
        if (img.colourChannels == null) {
            numColour = img.numChannels;
            img.colourChannels = new int[numColour];
            for (int i = 0; i < numColour; i++) {
                img.colourChannels[i] = i;
            }
        }

        // colourspace
        if (jp2 != null && jp2.colourMethod == Jp2Info.METH_ENUMERATED) {
            img.colourSpace = switch (jp2.enumCs) {
                case Jp2Info.CS_SRGB -> DecodedImage.ColourSpace.SRGB;
                case Jp2Info.CS_GREY -> DecodedImage.ColourSpace.GREY;
                case Jp2Info.CS_SYCC -> DecodedImage.ColourSpace.SYCC;
                default -> DecodedImage.ColourSpace.UNKNOWN;
            };
        } else if (jp2 != null && jp2.colourMethod == Jp2Info.METH_RESTRICTED_ICC) {
            img.colourSpace = DecodedImage.ColourSpace.ICC;
            img.iccProfile = jp2.iccProfile;
        } else {
            img.colourSpace = numColour >= 3
                    ? DecodedImage.ColourSpace.SRGB
                    : DecodedImage.ColourSpace.GREY;
        }

        long sc = 1L << reduction;
        img.reduction = reduction;
        img.regionX = region.x;
        img.regionY = region.y;
        img.gridX0 = (int) Math.ceilDiv(siz.xosiz, sc) + region.x;
        img.gridY0 = (int) Math.ceilDiv(siz.yosiz, sc) + region.y;
        img.imageWidth = (int) (Math.ceilDiv(siz.xsiz, sc) - Math.ceilDiv(siz.xosiz, sc));
        img.imageHeight = (int) (Math.ceilDiv(siz.ysiz, sc) - Math.ceilDiv(siz.yosiz, sc));
        img.tileWidth = siz.xtsiz;
        img.tileHeight = siz.ytsiz;
        img.tileGridXOff = siz.xtosiz - siz.xosiz;
        img.tileGridYOff = siz.ytosiz - siz.yosiz;
        img.numXTiles = siz.numXTiles();
        img.numYTiles = siz.numYTiles();
        return img;
    }

    private static void setChannelGeometry(DecodedImage img, int ch, Siz siz,
                                           int srcComp, int[][] ranges) {
        int[] r = ranges[srcComp];
        img.chanWidth[ch] = r[2] - r[0];
        img.chanHeight[ch] = r[3] - r[1];
        img.chanX0[ch] = r[0];
        img.chanY0[ch] = r[1];
        img.dx[ch] = siz.xrsiz[srcComp];
        img.dy[ch] = siz.yrsiz[srcComp];
    }
}
