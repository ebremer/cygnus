package com.ebremer.cygnus.jpegxl.encoder;

import com.ebremer.cygnus.jpegxl.codestream.BitDepth;
import com.ebremer.cygnus.jpegxl.codestream.ImageMetadata;
import com.ebremer.cygnus.jpegxl.codestream.SizeHeader;
import com.ebremer.cygnus.jpegxl.io.BitWriter;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Streaming (chunked) lossy VarDCT encoder, driven by {@link JxlStreamingEncoder}.
 * Rows arrive top to bottom; each 256-row band is transformed, quantised and
 * entropy-coded as soon as the rows it needs have arrived, and nothing but the
 * band, its halo and the already-compressed sections is ever held.
 *
 * <p>Two things in the whole-image encoder look at every pixel before deciding
 * anything, and both had to be given a streaming answer:
 *
 * <p><b>Adaptive quantisation.</b> Masking asks whether a block is busy or
 * smooth <em>relative to the image</em>, which means a mean over every cell.
 * Here the mean is accumulated as bands arrive, so band <i>b</i> is masked
 * against the mean of bands 0..<i>b</i> — exact for a one-band image,
 * converging quickly after that. The multiplier it picks is coded per block,
 * so a drifting reference costs consistency at the very top of a tall image,
 * never correctness. (On slide images the drift is slight anyway: most of the
 * frame is background, so the running mean starts near where it ends.)
 *
 * <p><b>The HF entropy code.</b> A VarDCT frame carries one coefficient code
 * for the whole image, so a strict one-pass encoder would have to freeze it on
 * the first band and then code everything else with it — ruinous on a slide,
 * whose first band is blank glass and whose alphabet would be frozen at three
 * symbols. Instead this uses {@code num_hf_presets}: the frame may carry
 * several coefficient codes, and each group says which one it used. Bands claim
 * a code from a small pool, sharing one when their statistics agree and taking
 * a fresh one when they do not, so blank bands and dense bands are not made to
 * share an alphabet. A code is settled the moment a band claims it, which is
 * what lets the band's bits be written immediately.
 */
final class VarDctStreamer {

    /**
     * Coefficient codes a frame may carry here. Eight is the ceiling of the
     * simple cluster map (its index width is two bits), and enough for the
     * handful of genuinely distinct block statistics an image tends to have.
     */
    private static final int MAX_BOOKS = 8;

    /** What a fresh code costs in the HfGlobal section: its prefix-code header. */
    private static final double BOOK_HEADER_BITS = 512;

    /** How much cheaper a fresh code must be before it is worth claiming. */
    private static final double BOOK_GAIN = 1.02;

    /**
     * How much of the error a finer quantiser must actually remove before rate
     * control keeps it; below this it is paying bits for nothing and stops.
     *
     * <p>This was once load-bearing, for the wrong reason. The encoder's gaborish
     * inversion did not converge, and the error it left behind did not shrink with
     * the quantiser — so on noisy content rate control would climb forever against
     * a wall. This guard noticed the wall. Now that the inversion converges there
     * is no wall, and on every image measured the guard never fires at all. It
     * stays because the near-lossless end has a real floor of its own — eight-bit
     * output rounding — and stopping there is still the right thing to do.
     */
    private static final double FLOOR_GAIN = 0.93;

    private final OutputStream out;
    private final int width;
    private final int height;
    private final BitDepth depth;
    private final int bits;
    private final boolean grey;
    private final boolean alpha;
    private final boolean alphaAssociated;
    private final float distance;
    private final int numInput;
    private final int alphaIndex;

    private final int groupColumns;
    private final int numBands;      // = group rows
    private final int numGroups;
    private final int lfColumns;
    private final int numLfGroups;
    private final int paddedHeight;

    private final VarDctEncoder enc;
    private final EntropyEncoder hfEnc;
    private final int books;
    private final boolean bookPerBand;
    private int booksUsed;

    // rows held: image rows [holdY0, holdY0 + holdRows), enough for the band
    // being assembled plus the gaborish halo on each side
    private final int[][] hold;
    private final int holdCapacity;
    private int holdY0;
    private int holdRows;
    private long rowsReceived;
    private int bandIndex;

    // the activity reference, accumulated across the bands seen so far
    private double actSum;
    private long actCells;

    // rate control: the last measured multiplier and the content it was for
    private final boolean rateControl;
    private int calibratedMul;
    private double calibratedActivity;

    private VarDctEncoder.Cells cells;   // the LF group row being filled
    private final byte[][] passBytes;
    private final byte[][] lfGroupBytes;
    private boolean finished;

    VarDctStreamer(OutputStream out, int width, int height, BitDepth depth, boolean grey,
            boolean alpha, boolean alphaAssociated, float distance, boolean rateControl) {
        this.out = out;
        this.rateControl = rateControl;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.bits = depth.bitsPerSample;
        this.grey = grey;
        this.alpha = alpha;
        this.alphaAssociated = alphaAssociated;
        this.distance = distance;
        this.numInput = (grey ? 1 : 3) + (alpha ? 1 : 0);
        this.alphaIndex = grey ? 1 : 3;
        this.paddedHeight = (height + 7) & ~7;

        this.groupColumns = VarDctEncoder.ceilDiv(width, VarDctEncoder.GROUP_DIM);
        this.numBands = VarDctEncoder.ceilDiv(height, VarDctEncoder.GROUP_DIM);
        this.numGroups = groupColumns * numBands;
        int lfDim = VarDctEncoder.GROUP_DIM * 8;
        this.lfColumns = VarDctEncoder.ceilDiv(width, lfDim);
        this.numLfGroups = lfColumns * VarDctEncoder.ceilDiv(height, lfDim);

        this.enc = new VarDctEncoder(width, height, depth, grey, alpha, distance);
        this.books = Math.min(numBands, MAX_BOOKS);
        this.bookPerBand = numBands <= MAX_BOOKS;
        int[] map = new int[books * VarDctEncoder.CONTEXTS_PER_PRESET];
        for (int i = 0; i < map.length; i++) {
            map[i] = i / VarDctEncoder.CONTEXTS_PER_PRESET;
        }
        this.hfEnc = new EntropyEncoder(map, books);

        this.holdCapacity = VarDctEncoder.GROUP_DIM + 2 * VarDctEncoder.GABORISH_HALO;
        this.hold = new int[numInput][width * Math.min(holdCapacity, height)];
        this.passBytes = new byte[numGroups][];
        this.lfGroupBytes = new byte[numLfGroups][];
    }

    // ------------------------------------------------------------ row intake

    void writeRows(int[][] rows, int rowCount) throws IOException {
        int src = 0;
        while (rowCount > 0) {
            int capacity = Math.min(holdCapacity, height);
            int space = capacity - holdRows;
            if (space == 0) {
                throw new IllegalStateException("band buffer full with no band to encode");
            }
            int take = Math.min(space, rowCount);
            for (int c = 0; c < numInput; c++) {
                System.arraycopy(rows[c], src * width, hold[c], holdRows * width, take * width);
            }
            holdRows += take;
            rowsReceived += take;
            src += take;
            rowCount -= take;
            drainBands();
        }
    }

    /** Encodes every band whose rows — its own and its halo — have all arrived. */
    private void drainBands() throws IOException {
        while (bandIndex < numBands && rowsReceived >= sourceEnd(bandIndex)) {
            encodeBand(bandIndex);
            bandIndex++;
            slide(sourceStart(bandIndex));
        }
    }

    /** The first image row a band reads: its own, less the halo. */
    private int sourceStart(int band) {
        if (band >= numBands) {
            return height;
        }
        return Math.max(0, band * VarDctEncoder.GROUP_DIM - VarDctEncoder.GABORISH_HALO);
    }

    /** One past the last image row a band reads. */
    private int sourceEnd(int band) {
        int top = band * VarDctEncoder.GROUP_DIM;
        int rows = bandRows(band);
        return Math.min(height, top + rows + VarDctEncoder.GABORISH_HALO);
    }

    /** Padded rows in a band; the last one carries the image's bottom padding. */
    private int bandRows(int band) {
        int top = band * VarDctEncoder.GROUP_DIM;
        return Math.min(top + VarDctEncoder.GROUP_DIM, paddedHeight) - top;
    }

    /** Drops rows no later band will read. */
    private void slide(int keepFrom) {
        int drop = Math.min(keepFrom - holdY0, holdRows);
        if (drop <= 0) {
            return;
        }
        int keep = holdRows - drop;
        for (int c = 0; c < numInput; c++) {
            System.arraycopy(hold[c], drop * width, hold[c], 0, keep * width);
        }
        holdY0 += drop;
        holdRows = keep;
    }

    // ----------------------------------------------------------------- band

    private void encodeBand(int band) throws IOException {
        int top = band * VarDctEncoder.GROUP_DIM;
        int rows = bandRows(band);
        enc.loadWindow(hold, holdY0, holdRows, top, rows, VarDctEncoder.GABORISH_HALO);

        // masking reference: every cell seen so far, this band included
        enc.measureWindow();
        actSum += enc.activitySum();
        actCells += enc.activityCells();
        double mean = actSum / actCells;
        if (rateControl) {
            enc.setHfMul(calibrate(top, mean));
        }
        enc.quantiseWindow(mean);

        // the LF group this band feeds is 2048 rows tall: eight bands
        int lfRow = band / 8;
        if (cells == null) {
            int row0 = lfRow * VarDctEncoder.GROUP_DIM;
            cells = new VarDctEncoder.Cells(enc.w8, VarDctEncoder.ceilDiv(enc.w8, 8), row0,
                    Math.min(VarDctEncoder.GROUP_DIM, enc.h8Total - row0));
        }
        enc.storeCells(cells);

        // A band's coefficients are tokenized twice: once to see what code they
        // want, then again to write them with it. Keeping them between the two
        // would cost a band's worth of tokens, which is the memory this class
        // exists not to spend; the transform work is already done either way.
        // Groups are independent in both passes, so both run wide.
        long[][] perGroup = new long[groupColumns][];
        groups().forEach(gc -> {
            long[] h = new long[EntropyEncoder.ALPHABET];
            int[] scratch = new int[1];
            VarDctEncoder.Tokens tokens = new VarDctEncoder.Tokens();
            enc.tokenizeGroup(band, gc, tokens);
            for (int i = 0; i < tokens.n; i++) {
                EntropyEncoder.tally(h, tokens.val[i], scratch);
            }
            perGroup[gc] = h;
        });
        long[] histogram = new long[EntropyEncoder.ALPHABET];
        for (long[] h : perGroup) {
            for (int t = 0; t < histogram.length; t++) {
                histogram[t] += h[t];
            }
        }

        int book = claimBook(histogram);
        int presetBits = books > 1 ? 32 - Integer.numberOfLeadingZeros(books - 1) : 0;
        int contextBase = book * VarDctEncoder.CONTEXTS_PER_PRESET;

        groups().forEach(gc -> {
            BitWriter gw = new BitWriter();
            gw.write(book, presetBits);
            VarDctEncoder.Tokens tokens = new VarDctEncoder.Tokens();
            enc.tokenizeGroup(band, gc, tokens);
            int[] scratch = new int[1];
            for (int i = 0; i < tokens.n; i++) {
                hfEnc.writeShared(gw, contextBase + tokens.ctx[i], tokens.val[i], scratch);
            }
            if (alpha) {
                int x0 = gc * VarDctEncoder.GROUP_DIM;
                enc.writeGroupAlpha(gw, hold[alphaIndex], holdY0, x0, top,
                        Math.min(VarDctEncoder.GROUP_DIM, width - x0),
                        Math.min(VarDctEncoder.GROUP_DIM, height - top));
            }
            gw.zeroPadToByte();
            passBytes[band * groupColumns + gc] = gw.toByteArray();
        });

        if (band % 8 == 7 || band == numBands - 1) {
            for (int lc = 0; lc < lfColumns; lc++) {
                int gg = lfRow * lfColumns + lc;
                BitWriter w = new BitWriter();
                enc.writeLfGroupBits(w, cells, gg, lfColumns);
                w.zeroPadToByte();
                lfGroupBytes[gg] = w.toByteArray();
            }
            cells = null;
        }
    }

    /** The band's group columns, spread across cores once there are enough to bother. */
    private java.util.stream.IntStream groups() {
        java.util.stream.IntStream all = java.util.stream.IntStream.range(0, groupColumns);
        return groupColumns > 1 ? all.parallel() : all;
    }

    // ----------------------------------------------------------- rate control

    /**
     * Sets the band's HF multiplier by measuring what the current one actually
     * costs: one group of the band is encoded and decoded for real, and the
     * multiplier is moved until the error it produces matches what the
     * requested distance is supposed to deliver.
     *
     * <p>The whole-image loop does this by encoding and decoding the entire
     * image up to three times, which a streaming encoder cannot do and, on a
     * slide, should not want to: most of the frame is background, so an error
     * averaged over all of it is an average over blank glass, and driving
     * <em>that</em> to the target would coarsen the tissue until the mean came
     * up. Measuring per band, on the busiest group in the band, both fits in a
     * band's memory and puts the measurement where the picture is.
     *
     * <p>The crop is quantised with the band's own masking reference, so the
     * multiplier it settles on is the one the band will actually use. Bands
     * whose content has not moved since the last measurement reuse its answer,
     * which is what keeps this to a few percent of the encode on wide images.
     */
    private int calibrate(int top, double mean) throws IOException {
        int busiest = 0;
        double peak = -1;
        for (int gc = 0; gc < groupColumns; gc++) {
            double a = enc.groupActivity(gc);
            if (a > peak) {
                peak = a;
                busiest = gc;
            }
        }
        if (calibratedMul > 0 && peak > calibratedActivity * 0.8
                && peak < calibratedActivity * 1.25) {
            return calibratedMul;   // same kind of content; the last answer stands
        }

        int x0 = busiest * VarDctEncoder.GROUP_DIM;
        int cw = Math.min(VarDctEncoder.GROUP_DIM, width - x0);
        int ch = Math.min(VarDctEncoder.GROUP_DIM, height - top);
        int[][] crop = new int[grey ? 1 : 3][cw * ch];
        for (int c = 0; c < crop.length; c++) {
            for (int y = 0; y < ch; y++) {
                System.arraycopy(hold[c], (top - holdY0 + y) * width + x0,
                        crop[c], y * cw, cw);
            }
        }

        double target = VarDctEncoder.targetError(distance);
        int base = VarDctEncoder.baseHfMul(distance);

        // Start each measurement from what the distance itself asks for, never
        // from where the previous band ended up. A band is only measured afresh
        // because its content changed, so the answer for the old content is the
        // worst place to begin: a blank band settles at the coarse end of the
        // range, and a dense band starting from there spends its whole budget of
        // rounds climbing back, finishing coarser than it would have been with no
        // rate control at all.
        int mul = base;

        // Only a multiplier that was actually measured may be kept. The last one
        // the loop reaches for is a guess it never got to check, and near the
        // target — where the correction is small and rounds to a step either way —
        // that guess is as likely to be worse than where it started as better.
        int best = mul;
        double bestMiss = Double.MAX_VALUE;
        int prevMul = 0;
        double prevErr = 0;
        for (int round = 0; round < 4; round++) {
            VarDctEncoder probe = new VarDctEncoder(cw, ch, depth, grey, false, distance);
            probe.setHfMul(mul);
            probe.loadWindow(crop, 0, ch, 0, (ch + 7) & ~7, 0);
            probe.measureWindow();
            probe.quantiseWindow(mean);
            byte[] jxl = probe.standalone(null, false);
            double err = VarDctEncoder.measureError(crop, cw, ch, depth, grey, jxl);

            // A finer quantiser stops paying at some point: the error a band can
            // reach is floored by what the decoder's own smoothing does to it,
            // not by the coefficients, and past that floor a finer step buys bits
            // and nothing else. When a step of the multiplier barely moves the
            // error, take the cheaper one and stop.
            if (prevMul > 0 && err > prevErr * FLOOR_GAIN) {
                best = prevMul;
                break;
            }
            double miss = Math.abs(Math.log(Math.max(err, 1e-3) / target));
            if (miss < bestMiss) {
                bestMiss = miss;
                best = mul;
            }
            if (err > target * 0.85 && err < target * 1.18) {
                break;  // close enough
            }
            int next = VarDctEncoder.nextHfMul(mul, err, target, base);
            if (next == mul) {
                break;  // clamped, or already there
            }
            prevMul = mul;
            prevErr = err;
            mul = next;
        }
        calibratedMul = best;
        calibratedActivity = peak;
        return best;
    }

    // ------------------------------------------------------------- codebooks

    /**
     * Picks the coefficient code a band's tokens will be written with. With a
     * code to spare for every band, each takes its own and is coded exactly.
     * Otherwise bands share: a band settles for an existing code when that code
     * carries it within {@link #BOOK_GAIN} of what a bespoke one would, and
     * claims a fresh code when it would not — so a run of near-identical bands
     * uses one slot between them and leaves the rest for content that differs.
     */
    private int claimBook(long[] histogram) {
        if (bookPerBand) {
            int book = booksUsed++;
            hfEnc.freezeCluster(book, histogram);
            return book;
        }
        double ideal = idealBits(histogram);
        int best = -1;
        double bestCost = Double.MAX_VALUE;
        for (int k = 0; k < booksUsed; k++) {
            double cost = hfEnc.costWith(k, histogram);
            if (cost < bestCost) {
                bestCost = cost;
                best = k;
            }
        }
        if (booksUsed < books
                && (best < 0 || bestCost > ideal * BOOK_GAIN + BOOK_HEADER_BITS)) {
            int book = booksUsed++;
            hfEnc.freezeCluster(book, smoothed(histogram));
            return book;
        }
        return best;
    }

    /** Bits an ideal code would spend on these counts: their entropy. */
    private static double idealBits(long[] histogram) {
        long total = 0;
        for (long c : histogram) {
            total += c;
        }
        if (total == 0) {
            return 0;
        }
        double bits = 0;
        for (long c : histogram) {
            if (c > 0) {
                bits += c * (Math.log((double) total / c) / Math.log(2));
            }
        }
        return bits;
    }

    /**
     * A shared code has to be able to carry symbols the band that built it
     * never produced, or a later band would find its alphabet cut short. One
     * count on every symbol the value coder can emit buys that: it leaves the
     * common symbols where they were and gives the rest a long but usable code.
     */
    private static long[] smoothed(long[] histogram) {
        long[] h = histogram.clone();
        for (int t = 0; t <= EntropyEncoder.MAX_TOKEN; t++) {
            h[t]++;
        }
        return h;
    }

    // --------------------------------------------------------------- finish

    void finish() throws IOException {
        if (finished) {
            return;
        }
        if (rowsReceived != height) {
            throw new IllegalStateException("only " + rowsReceived + " of " + height
                    + " rows were written");
        }
        finished = true;

        // codes nobody claimed still have to be spelled out: give them the
        // smoothing floor, which is a valid code for anything
        for (int k = booksUsed; k < books; k++) {
            hfEnc.freezeCluster(k, smoothed(new long[EntropyEncoder.ALPHABET]));
        }

        BitWriter lfg = new BitWriter();
        enc.writeLfGlobalBits(lfg, false, null);
        lfg.zeroPadToByte();
        byte[] lfGlobalBytes = lfg.toByteArray();

        BitWriter hfg = new BitWriter();
        enc.writeHfGlobalBits(hfg, hfEnc, numGroups, books);
        hfg.zeroPadToByte();
        byte[] hfGlobalBytes = hfg.toByteArray();

        BitWriter w = new BitWriter();
        w.write(0xff, 8);
        w.write(0x0a, 8);
        new SizeHeader(width, height).write(w);
        ImageMetadata meta = JxlEncoder.buildMetadata(depth, grey, alpha, alphaAssociated);
        meta.xybEncoded = true;
        meta.write(w);
        VarDctEncoder.writeFrameHeader(w, alpha);
        w.writeBool(false); // TOC not permuted
        w.zeroPadToByte();
        VarDctEncoder.writeTocEntry(w, lfGlobalBytes.length);
        for (byte[] b : lfGroupBytes) {
            VarDctEncoder.writeTocEntry(w, b.length);
        }
        VarDctEncoder.writeTocEntry(w, hfGlobalBytes.length);
        for (byte[] b : passBytes) {
            VarDctEncoder.writeTocEntry(w, b.length);
        }
        w.zeroPadToByte();
        w.writeBytes(lfGlobalBytes);
        for (byte[] b : lfGroupBytes) {
            w.writeBytes(b);
        }
        w.writeBytes(hfGlobalBytes);
        out.write(w.toByteArray());
        for (byte[] b : passBytes) {
            out.write(b);
        }
        out.flush();
    }
}
