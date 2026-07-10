package com.ebremer.jpegxl.modular;

/**
 * Self-correcting (weighted) predictor state, 18181-1 clause H.4. All
 * predictions are kept in 1/8 units.
 *
 * <p>The stored error state deliberately uses 32-bit wrapping arithmetic:
 * libjxl keeps {@code pred_errors} as {@code uint32_t} and the true errors as
 * {@code int32_t}, so for very wide samples (32-bit float bit patterns) the
 * error terms overflow and wrap. That wrapping is normative in practice —
 * decoders that track exact 64-bit errors desync on such streams.
 */
public final class WpState {

    /** {@code DIV24[i] = floor(2^24 / (i + 1))}. */
    private static final int[] DIV24 = new int[64];

    static {
        for (int i = 0; i < 64; i++) {
            DIV24[i] = 0x1000000 / (i + 1);
        }
    }

    private final int width;
    private final int p1;
    private final int p2;
    private final int[] p3;
    private final int[] w;
    private final int[] errors; // 2 rows * width * 5 entries, 32-bit wrapping
    final long[] pred = new long[5];
    int trueErrW;
    int trueErrN;
    int trueErrNW;
    int trueErrNE;

    public WpState(WpParams params, int width) {
        this.width = width;
        this.p1 = params.p1;
        this.p2 = params.p2;
        this.p3 = params.p3;
        this.w = params.w;
        this.errors = new int[width * 2 * 5];
    }

    public void beforePredict(int x, int y, long pw, long pn, long pnw, long pne, long pnn) {
        int cur = (y & 1) != 0 ? width * 5 : 0;
        int north = (y & 1) != 0 ? 0 : width * 5;

        int e0W = 0;
        int e1W = 0;
        int e2W = 0;
        int e3W = 0;
        int e0N = 0;
        int e1N = 0;
        int e2N = 0;
        int e3N = 0;
        int e0NW;
        int e1NW;
        int e2NW;
        int e3NW;
        int e0NE;
        int e1NE;
        int e2NE;
        int e3NE;
        int e0WW = 0;
        int e1WW = 0;
        int e2WW = 0;
        int e3WW = 0;

        if (x > 0) {
            int o = cur + (x - 1) * 5;
            e0W = errors[o];
            e1W = errors[o + 1];
            e2W = errors[o + 2];
            e3W = errors[o + 3];
            trueErrW = errors[o + 4];
        } else {
            trueErrW = 0;
        }
        if (y > 0) {
            int o = north + x * 5;
            e0N = errors[o];
            e1N = errors[o + 1];
            e2N = errors[o + 2];
            e3N = errors[o + 3];
            trueErrN = errors[o + 4];
        } else {
            trueErrN = 0;
        }
        if (x > 0 && y > 0) {
            int o = north + (x - 1) * 5;
            e0NW = errors[o];
            e1NW = errors[o + 1];
            e2NW = errors[o + 2];
            e3NW = errors[o + 3];
            trueErrNW = errors[o + 4];
        } else {
            e0NW = e0N;
            e1NW = e1N;
            e2NW = e2N;
            e3NW = e3N;
            trueErrNW = trueErrN;
        }
        if (x + 1 < width && y > 0) {
            int o = north + (x + 1) * 5;
            e0NE = errors[o];
            e1NE = errors[o + 1];
            e2NE = errors[o + 2];
            e3NE = errors[o + 3];
            trueErrNE = errors[o + 4];
        } else {
            e0NE = e0N;
            e1NE = e1N;
            e2NE = e2N;
            e3NE = e3N;
            trueErrNE = trueErrN;
        }
        if (x > 1) {
            int o = cur + (x - 2) * 5;
            e0WW = errors[o];
            e1WW = errors[o + 1];
            e2WW = errors[o + 2];
            e3WW = errors[o + 3];
        }
        // at the right edge the W errors are counted twice
        int e0W2 = 0;
        int e1W2 = 0;
        int e2W2 = 0;
        int e3W2 = 0;
        if (x + 1 >= width) {
            e0W2 = e0W;
            e1W2 = e1W;
            e2W2 = e2W;
            e3W2 = e3W;
        }

        long teW = trueErrW;
        long teN = trueErrN;
        long teNW = trueErrNW;
        long teNE = trueErrNE;
        pred[0] = (pw + pne - pn) * 8;
        pred[1] = pn * 8 - (((teW + teN + teNE) * p1) >> 5);
        pred[2] = pw * 8 - (((teW + teN + teNW) * p2) >> 5);
        pred[3] = pn * 8 - ((teNW * p3[0] + teN * p3[1] + teNE * p3[2]
                + (pnn - pn) * 8 * p3[3] + (pnw - pw) * 8 * p3[4]) >> 5);

        int[] wgt = weightScratch;
        wgt[0] = weight(e0N + e0W + e0NW + e0WW + e0NE + e0W2, w[0]);
        wgt[1] = weight(e1N + e1W + e1NW + e1WW + e1NE + e1W2, w[1]);
        wgt[2] = weight(e2N + e2W + e2NW + e2WW + e2NE + e2W2, w[2]);
        wgt[3] = weight(e3N + e3W + e3NW + e3WW + e3NE + e3W2, w[3]);

        int logw = floorLog2(wgt[0] + wgt[1] + wgt[2] + wgt[3]) - 4;
        int wsum = 0;
        long sum = 0;
        for (int i = 0; i < 4; i++) {
            wgt[i] >>>= logw;
            wsum += wgt[i];
            sum += pred[i] * wgt[i];
        }
        pred[4] = (sum + (wsum >> 1) - 1) * DIV24[wsum - 1] >> 24;
        if (((trueErrN ^ trueErrW) | (trueErrN ^ trueErrNW)) <= 0) {
            long lo = Math.min(pw, Math.min(pn, pne)) * 8;
            long hi = Math.max(pw, Math.max(pn, pne)) * 8;
            pred[4] = Math.min(Math.max(lo, pred[4]), hi);
        }
    }

    private final int[] weightScratch = new int[4];

    private static int weight(int errSum, int baseWeight) {
        long unsignedSum = errSum & 0xFFFFFFFFL;
        int shift = Math.max(floorLog2Long(unsignedSum + 1) - 5, 0);
        return 4 + (int) ((baseWeight * (long) DIV24[(int) (unsignedSum >> shift)]) >>> shift);
    }

    /** The value of predictor 6 after {@link #beforePredict}. */
    public long prediction() {
        return (pred[4] + 3) >> 3;
    }

    /** Property 15: the largest-magnitude neighboring true error (signed, 32-bit). */
    public int maxError() {
        int v = trueErrW;
        if (Math.abs((long) trueErrN) > Math.abs((long) v)) {
            v = trueErrN;
        }
        if (Math.abs((long) trueErrNW) > Math.abs((long) v)) {
            v = trueErrNW;
        }
        if (Math.abs((long) trueErrNE) > Math.abs((long) v)) {
            v = trueErrNE;
        }
        return v;
    }

    public void afterPredict(int x, int y, long val) {
        int o = ((y & 1) != 0 ? width * 5 : 0) + x * 5;
        long val8 = val * 8;
        for (int i = 0; i < 4; i++) {
            errors[o + i] = (int) ((Math.abs(pred[i] - val8) + 3) >> 3);
        }
        errors[o + 4] = (int) (pred[4] - val8);
    }

    public void reset() {
        java.util.Arrays.fill(errors, 0);
        java.util.Arrays.fill(pred, 0);
        trueErrW = trueErrN = trueErrNW = trueErrNE = 0;
    }

    private static int floorLog2(int x) {
        return 31 - Integer.numberOfLeadingZeros(x);
    }

    private static int floorLog2Long(long x) {
        return 63 - Long.numberOfLeadingZeros(x);
    }

    /** WPHeader parameters. */
    public static final class WpParams {
        public final int p1;
        public final int p2;
        public final int[] p3;
        public final int[] w;

        public WpParams(int p1, int p2, int[] p3, int[] w) {
            this.p1 = p1;
            this.p2 = p2;
            this.p3 = p3;
            this.w = w;
        }

        public static final WpParams DEFAULT =
                new WpParams(16, 10, new int[] {7, 7, 7, 0, 0}, new int[] {13, 12, 12, 12});
    }
}
