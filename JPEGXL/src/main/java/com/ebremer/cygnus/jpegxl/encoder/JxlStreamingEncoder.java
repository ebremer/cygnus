package com.ebremer.cygnus.jpegxl.encoder;

import com.ebremer.cygnus.jpegxl.codestream.SizeHeader;
import com.ebremer.cygnus.jpegxl.io.BitWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Streaming (chunked) lossless encoder: rows are pushed top to bottom with
 * {@link #writeRows}, each 256-row band of groups is compressed as soon as it
 * completes, and {@link #finish()} assembles the codestream. Peak memory is
 * one band of samples plus the already-compressed sections — the image's
 * total height never has to fit in memory, so images far beyond the reach of
 * {@link JxlEncoder#encode} (and beyond the 2^31 samples-per-plane array
 * limit) can be written.
 *
 * <p>Every group is a self-contained modular section: its own reversible
 * colour transform, predictor choice, learned MA tree and entropy code
 * (nothing is shared across groups, since global statistics would need the
 * whole image). Files are typically a few percent larger than
 * {@link JxlEncoder#encode}'s and decode with any conforming decoder.
 */
public final class JxlStreamingEncoder implements AutoCloseable {

    private final OutputStream out;
    private final int width;
    private final int height;
    private final int bits;
    private final boolean grey;
    private final boolean alpha;
    private final boolean alphaAssociated;
    private final int numInput;
    private final int groupColumns;

    private final int[][] band;      // one band of input rows, [channel][row-major]
    private int bandFill;            // rows buffered in the current band
    private int bandIndex;           // group-row index of the current band
    private long rowsReceived;
    private final byte[][] sections; // finished pass-group sections, group order
    private final int[][] whole;     // single-group images just buffer everything
    private boolean finished;

    /**
     * Plane layout everywhere: colour channels first (1 for greyscale, 3 for
     * RGB), then an optional alpha plane; sample values in [0, 2^bits).
     */
    public JxlStreamingEncoder(OutputStream out, int width, int height, int bits,
            boolean grey, boolean alpha, boolean alphaAssociated) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad dimensions " + width + "x" + height);
        }
        if (bits < 1 || bits > 31) {
            throw new IllegalArgumentException("bits per sample must be in 1..31");
        }
        this.out = out;
        this.width = width;
        this.height = height;
        this.bits = bits;
        this.grey = grey;
        this.alpha = alpha;
        this.alphaAssociated = alphaAssociated;
        this.numInput = (grey ? 1 : 3) + (alpha ? 1 : 0);
        this.groupColumns = JxlEncoder.ceilDiv(width, JxlEncoder.GROUP_DIM);
        int groupRows = JxlEncoder.ceilDiv(height, JxlEncoder.GROUP_DIM);
        if (groupColumns * groupRows == 1) {
            // a single-group image is a single-section frame with a global
            // stream; collect it and delegate to the whole-image encoder
            this.whole = new int[numInput][width * height];
            this.band = null;
            this.sections = null;
        } else {
            this.whole = null;
            this.band = new int[numInput][width * Math.min(JxlEncoder.GROUP_DIM, height)];
            this.sections = new byte[groupColumns * groupRows][];
        }
    }

    /**
     * Consumes the next {@code rowCount} rows. {@code rows[c]} holds that
     * channel's samples for these rows, row-major from the top; any number of
     * rows per call is fine — bands are cut internally.
     */
    public void writeRows(int[][] rows, int rowCount) throws IOException {
        if (finished) {
            throw new IllegalStateException("encoder already finished");
        }
        if (rowCount <= 0) {
            throw new IllegalArgumentException("rowCount " + rowCount);
        }
        if (rows.length != numInput) {
            throw new IllegalArgumentException("expected " + numInput + " planes, got " + rows.length);
        }
        if (rowsReceived + rowCount > height) {
            throw new IllegalArgumentException("rows exceed the image height");
        }
        for (int c = 0; c < numInput; c++) {
            if (rows[c].length < rowCount * width) {
                throw new IllegalArgumentException("plane " + c + " holds fewer than "
                        + rowCount + " rows");
            }
        }
        if (whole != null) {
            for (int c = 0; c < numInput; c++) {
                System.arraycopy(rows[c], 0, whole[c], (int) rowsReceived * width,
                        rowCount * width);
            }
            rowsReceived += rowCount;
            return;
        }
        int srcRow = 0;
        while (rowCount > 0) {
            int bandH = bandHeight();
            int take = Math.min(rowCount, bandH - bandFill);
            for (int c = 0; c < numInput; c++) {
                System.arraycopy(rows[c], srcRow * width, band[c], bandFill * width,
                        take * width);
            }
            bandFill += take;
            srcRow += take;
            rowCount -= take;
            rowsReceived += take;
            if (bandFill == bandH) {
                encodeBand(bandH);
                bandIndex++;
                bandFill = 0;
            }
        }
    }

    private int bandHeight() {
        return Math.min(JxlEncoder.GROUP_DIM, height - bandIndex * JxlEncoder.GROUP_DIM);
    }

    /**
     * Compresses every group of the completed band, in parallel — but with
     * the worker count bounded by the heap: each in-flight group holds
     * roughly 10-20 MB of transients (pixel copies, transform candidates,
     * token buffers), so unbounded fan-out would break the bounded-memory
     * contract on small heaps.
     */
    private void encodeBand(int bandH) {
        int gRow = bandIndex;
        int workers = bandWorkers();
        java.util.function.IntConsumer one = gc -> {
            int x0 = gc * JxlEncoder.GROUP_DIM;
            int w = Math.min(JxlEncoder.GROUP_DIM, width - x0);
            sections[gRow * groupColumns + gc] = encodeGroup(x0, w, bandH);
        };
        if (workers <= 1 || groupColumns == 1) {
            for (int gc = 0; gc < groupColumns; gc++) {
                one.accept(gc);
            }
            return;
        }
        if (workers >= Runtime.getRuntime().availableProcessors()) {
            java.util.stream.IntStream.range(0, groupColumns).parallel().forEach(one);
            return;
        }
        java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(workers);
        try {
            pool.submit(() ->
                    java.util.stream.IntStream.range(0, groupColumns).parallel().forEach(one))
                    .join();
        } finally {
            pool.shutdown();
        }
    }

    /** Parallelism that keeps band + per-worker transients inside the heap. */
    private int bandWorkers() {
        long perWorker = 20L << 20;
        long bandBytes = 4L * numInput * width * Math.min(JxlEncoder.GROUP_DIM, height);
        long reserve = (64L << 20) + bandBytes;
        long budget = Runtime.getRuntime().maxMemory() - reserve;
        int byMemory = (int) Math.max(1, Math.min(Integer.MAX_VALUE, budget / perWorker));
        return Math.min(Runtime.getRuntime().availableProcessors(), byMemory);
    }

    /** One self-contained pass-group section from the current band. */
    private byte[] encodeGroup(int x0, int w, int h) {
        int[][] px = new int[numInput][w * h];
        for (int c = 0; c < numInput; c++) {
            for (int y = 0; y < h; y++) {
                System.arraycopy(band[c], y * width + x0, px[c], y * w, w);
            }
        }
        int rct = -1;
        if (!grey) {
            int[][] rgb = {px[0], px[1], px[2]};
            rct = JxlEncoder.selectRct(rgb, w, h);
            JxlEncoder.applyForwardRct(rct, rgb);
            px[0] = rgb[0];
            px[1] = rgb[1];
            px[2] = rgb[2];
            if (rct == 0) {
                rct = -1; // nothing to code
            }
        }
        List<JxlEncoder.Chan> list = new ArrayList<>(numInput);
        for (int c = 0; c < numInput; c++) {
            list.add(new JxlEncoder.Chan(w, h, px[c]));
        }
        List<int[]> rect = List.of(new int[] {0, 0, w, h});
        Map<JxlEncoder.Chan, JxlEncoder.TNode> subs = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            JxlEncoder.Chan c = list.get(i);
            JxlEncoder.choosePredictor(c);
            int[] ref = i > 0 ? list.get(i - 1).px : null;
            JxlEncoder.TNode sub = JxlEncoder.learnTree(c, ref, rect, 1 << 14, 4);
            JxlEncoder.refineLeaves(c, sub, ref, rect);
            subs.put(c, sub);
        }
        JxlEncoder.TNode tree = JxlEncoder.chainNode(list, list.size() - 1, subs);
        int numCtx = JxlEncoder.assignCtx(tree);
        JxlEncoder.TokenBuf buf = new JxlEncoder.TokenBuf();
        for (int i = 0; i < list.size(); i++) {
            JxlEncoder.Chan c = list.get(i);
            int[] ref = i > 0 ? list.get(i - 1).px : null;
            JxlEncoder.tokenizeRect(c, subs.get(c), ref, 0, 0, w, h, buf);
        }

        BitWriter gw = new BitWriter();
        gw.writeBool(false); // use_global_tree = false: the group is standalone
        gw.writeBool(true);  // default weighted-predictor parameters
        if (rct >= 0) {
            gw.write(1, 2);  // nb_transforms = 1
            JxlEncoder.writeRctTransform(gw, rct);
        } else {
            gw.write(0, 2);  // nb_transforms = 0
        }
        EntropyEncoder treeEnc = new EntropyEncoder(6, false, false, true);
        JxlEncoder.emitTree(tree, null, treeEnc);
        treeEnc.writeSpec(gw);
        JxlEncoder.emitTree(tree, gw, treeEnc);
        treeEnc.finishSection(gw);
        EntropyEncoder litProbe = new EntropyEncoder(numCtx, true, true);
        JxlEncoder.countLiterals(buf, litProbe);
        litProbe.prepareCosts();
        JxlEncoder.findMatches(buf, w, litProbe);
        EntropyEncoder dataEnc = new EntropyEncoder(numCtx, true, true, true);
        JxlEncoder.emitBuffer(buf, null, dataEnc, w);
        dataEnc.writeSpec(gw);
        JxlEncoder.emitBuffer(buf, gw, dataEnc, w);
        dataEnc.finishSection(gw);
        gw.zeroPadToByte();
        return gw.toByteArray();
    }

    /**
     * Writes the codestream (signature, headers, TOC, sections). All rows must
     * have been delivered. Safe to call once; later calls are no-ops.
     */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        if (rowsReceived != height) {
            throw new IllegalStateException("only " + rowsReceived + " of " + height
                    + " rows were written");
        }
        finished = true;
        if (whole != null) {
            out.write(JxlEncoder.encode(whole, width, height, bits, grey, alpha,
                    alphaAssociated));
            out.flush();
            return;
        }
        BitWriter w = new BitWriter();
        w.write(0xff, 8);
        w.write(0x0a, 8);
        new SizeHeader(width, height).write(w);
        JxlEncoder.buildMetadata(bits, grey, alpha, alphaAssociated).write(w);
        JxlEncoder.writeFrameHeader(w, alpha);
        w.writeBool(false); // TOC not permuted
        w.zeroPadToByte();
        byte[] lfGlobal = buildLfGlobal();
        JxlEncoder.writeTocEntry(w, lfGlobal.length);
        int numLfGroups = JxlEncoder.ceilDiv(width, JxlEncoder.GROUP_DIM * 8)
                * JxlEncoder.ceilDiv(height, JxlEncoder.GROUP_DIM * 8);
        for (int i = 0; i < numLfGroups; i++) {
            JxlEncoder.writeTocEntry(w, 0); // empty LfGroup sections
        }
        JxlEncoder.writeTocEntry(w, 0);     // empty HfGlobal section
        for (byte[] s : sections) {
            JxlEncoder.writeTocEntry(w, s.length);
        }
        w.zeroPadToByte();
        w.writeBytes(lfGlobal);
        out.write(w.toByteArray());
        for (byte[] s : sections) {
            out.write(s);
        }
        out.flush();
    }

    /**
     * The LfGlobal section: no globally coded channels, no transforms, and a
     * trivial one-leaf global tree that no group refers to (each group brings
     * its own learned tree).
     */
    private static byte[] buildLfGlobal() {
        BitWriter lf = new BitWriter();
        lf.writeBool(true); // LfChannelDequantization.all_default
        lf.writeBool(true); // global tree present
        JxlEncoder.TNode leaf = new JxlEncoder.TNode();
        leaf.predictor = 5; // gradient
        EntropyEncoder treeEnc = new EntropyEncoder(6, false);
        JxlEncoder.emitTree(leaf, null, treeEnc);
        treeEnc.writeSpec(lf);
        JxlEncoder.emitTree(leaf, lf, treeEnc);
        EntropyEncoder dataEnc = new EntropyEncoder(1, true, true);
        dataEnc.writeSpec(lf); // nothing is coded against it
        lf.writeBool(true);    // use_global_tree
        lf.writeBool(true);    // default weighted-predictor parameters
        lf.write(0, 2);        // nb_transforms = 0
        lf.zeroPadToByte();
        return lf.toByteArray();
    }

    /** Finishes the stream if complete; complains if rows are missing. */
    @Override
    public void close() throws IOException {
        if (!finished && rowsReceived != height) {
            throw new IllegalStateException("closed after " + rowsReceived + " of "
                    + height + " rows");
        }
        finish();
    }
}
