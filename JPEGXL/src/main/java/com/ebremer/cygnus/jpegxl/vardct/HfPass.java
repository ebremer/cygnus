package com.ebremer.cygnus.jpegxl.vardct;

import com.ebremer.cygnus.jpegxl.entropy.EntropyDecoder;
import com.ebremer.cygnus.jpegxl.io.Bits;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Per-pass HF data: coefficient orders (possibly permuted) and the entropy
 * code spec for the AC coefficients.
 */
public final class HfPass {

    /** Cached natural orders, one per order id; entries are (y << 16) | x. */
    private static final int[][] NATURAL_ORDER = new int[13][];

    /** order[orderId][channel][k] = (y << 16) | x within the varblock. */
    public final int[][][] order = new int[13][3][];
    public final EntropyDecoder contextStream;
    public final int numContexts;

    public HfPass(Bits in, int numHfPresets, int numBlockClusters) throws IOException {
        int usedOrders = in.u32(0x5F, 0, 0x13, 0, 0, 0, 0, 13);
        EntropyDecoder stream = usedOrders != 0 ? EntropyDecoder.read(in, 8, true) : null;
        for (int b = 0; b < 13; b++) {
            int[] natural = naturalOrder(b);
            for (int c = 0; c < 3; c++) {
                if ((usedOrders & (1 << b)) != 0) {
                    int[] perm = readFullPermutation(in, stream, natural.length, natural.length / 64);
                    int[] ord = new int[natural.length];
                    for (int i = 0; i < ord.length; i++) {
                        ord[i] = natural[perm[i]];
                    }
                    order[b][c] = ord;
                } else {
                    order[b][c] = natural;
                }
            }
        }
        if (stream != null) {
            stream.finish(in);
        }
        this.numContexts = 495 * numHfPresets * numBlockClusters;
        this.contextStream = EntropyDecoder.read(in, numContexts, true);
    }

    /** Reads a Lehmer-coded permutation as a full index array. */
    public static int[] readFullPermutation(Bits in, EntropyDecoder stream, int size, int skip)
            throws IOException {
        int end = stream.readSymbol(in, Math.min(7, ceilLog1p(size)));
        if (end > size - skip) {
            throw new IOException("bad permutation length");
        }
        int[] lehmer = new int[size];
        for (int i = skip; i < end + skip; i++) {
            lehmer[i] = stream.readSymbol(in,
                    Math.min(7, ceilLog1p(i > skip ? lehmer[i - 1] : 0)));
            if (lehmer[i] >= size - i) {
                throw new IOException("bad permutation value");
            }
        }
        int[] temp = new int[size];
        for (int i = 0; i < size; i++) {
            temp[i] = i;
        }
        int[] permutation = new int[size];
        int remaining = size;
        for (int i = 0; i < size; i++) {
            int index = lehmer[i];
            permutation[i] = temp[index];
            System.arraycopy(temp, index + 1, temp, index, remaining - index - 1);
            remaining--;
        }
        return permutation;
    }

    public static synchronized int[] naturalOrder(int orderId) {
        if (NATURAL_ORDER[orderId] != null) {
            return NATURAL_ORDER[orderId];
        }
        TransformType tt = TransformType.byOrderId(orderId);
        int h = tt.pixelHeight;
        int w = tt.pixelWidth;
        int selH = tt.blockHeight;
        int selW = tt.blockWidth;
        int maxDim = Math.max(selH, selW);
        Integer[] positions = new Integer[h * w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                positions[y * w + x] = (y << 16) | x;
            }
        }
        Arrays.sort(positions, new Comparator<Integer>() {
            @Override
            public int compare(Integer pa, Integer pb) {
                int ay = pa >> 16;
                int ax = pa & 0xffff;
                int by = pb >> 16;
                int bx = pb & 0xffff;
                boolean aLLF = ay < selH && ax < selW;
                boolean bLLF = by < selH && bx < selW;
                if (aLLF && !bLLF) {
                    return -1;
                }
                if (bLLF && !aLLF) {
                    return 1;
                }
                if (aLLF && bLLF) {
                    return ay != by ? ay - by : ax - bx;
                }
                int aSY = ay * maxDim / selH;
                int aSX = ax * maxDim / selW;
                int bSY = by * maxDim / selH;
                int bSX = bx * maxDim / selW;
                int aKey1 = aSY + aSX;
                int bKey1 = bSY + bSX;
                if (aKey1 != bKey1) {
                    return aKey1 - bKey1;
                }
                int aKey2 = aSX - aSY;
                int bKey2 = bSX - bSY;
                if ((aKey1 & 1) == 1) {
                    aKey2 = -aKey2;
                }
                if ((bKey1 & 1) == 1) {
                    bKey2 = -bKey2;
                }
                return aKey2 - bKey2;
            }
        });
        int[] order = new int[h * w];
        for (int i = 0; i < order.length; i++) {
            order[i] = positions[i];
        }
        NATURAL_ORDER[orderId] = order;
        return order;
    }

    private static int ceilLog1p(long x) {
        return 64 - Long.numberOfLeadingZeros(x);
    }
}
