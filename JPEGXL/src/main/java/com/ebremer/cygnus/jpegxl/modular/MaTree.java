package com.ebremer.cygnus.jpegxl.modular;

import com.ebremer.cygnus.jpegxl.entropy.EntropyDecoder;
import com.ebremer.cygnus.jpegxl.io.Bits;
import java.io.IOException;

/**
 * Meta-adaptive tree (18181-1 clause H.4.2). Nodes are stored in level order;
 * branch nodes store the property index, split value, and absolute child
 * indices, while leaves carry (context, predictor, offset, multiplier).
 */
public final class MaTree {

    /** Branch property index, or -1 for leaves. */
    final int[] property;
    final int[] splitValue;
    final int[] left;
    final int[] right;
    final int[] context;
    final int[] predictor;
    final int[] offset;
    final int[] multiplier;
    private final int numLeaves;

    private MaTree(int[] property, int[] splitValue, int[] left, int[] right,
            int[] context, int[] predictor, int[] offset, int[] multiplier, int numLeaves) {
        this.property = property;
        this.splitValue = splitValue;
        this.left = left;
        this.right = right;
        this.context = context;
        this.predictor = predictor;
        this.offset = offset;
        this.multiplier = multiplier;
        this.numLeaves = numLeaves;
    }

    public int leafCount() {
        return numLeaves;
    }

    public int size() {
        return property.length;
    }

    /** True when any reachable leaf uses the weighted predictor or property 15. */
    public boolean usesWeightedPredictor() {
        for (int i = 0; i < property.length; i++) {
            if (property[i] >= 0 ? property[i] == 15 : predictor[i] == 6) {
                return true;
            }
        }
        return false;
    }

    /** True when the tree is a single leaf with the given predictor and no properties. */
    public boolean onlyUsesSimpleProperties(int maxProperty) {
        for (int p : property) {
            if (p > maxProperty) {
                return false;
            }
        }
        return true;
    }

    /** The result of reading a tree: the tree plus the entropy code for the pixel data. */
    public record WithCode(MaTree tree, EntropyDecoder code) {
    }

    public static WithCode read(Bits in, int maxSize) throws IOException {
        EntropyDecoder treeCode = EntropyDecoder.read(in, 6, true);
        int cap = 8;
        int[] property = new int[cap];
        int[] splitValue = new int[cap];
        int[] left = new int[cap];
        int[] right = new int[cap];
        int[] context = new int[cap];
        int[] predictor = new int[cap];
        int[] offset = new int[cap];
        int[] multiplier = new int[cap];

        int treeIdx = 0;
        int nodesLeft = 1;
        int ctxId = 0;
        int depth = 0;
        int nodesUptoThisDepth = 1;
        while (nodesLeft-- > 0) {
            if (treeIdx == nodesUptoThisDepth) {
                depth++;
                if (depth > 2048) {
                    throw new IOException("MA tree too deep");
                }
                nodesUptoThisDepth += nodesLeft + 1;
            }
            if (treeIdx == cap) {
                cap *= 2;
                property = java.util.Arrays.copyOf(property, cap);
                splitValue = java.util.Arrays.copyOf(splitValue, cap);
                left = java.util.Arrays.copyOf(left, cap);
                right = java.util.Arrays.copyOf(right, cap);
                context = java.util.Arrays.copyOf(context, cap);
                predictor = java.util.Arrays.copyOf(predictor, cap);
                offset = java.util.Arrays.copyOf(offset, cap);
                multiplier = java.util.Arrays.copyOf(multiplier, cap);
            }
            int prop = treeCode.readSymbol(in, 1);
            int cur = treeIdx++;
            if (prop > 0) {
                property[cur] = prop - 1;
                splitValue[cur] = Bits.unpackSigned(treeCode.readSymbol(in, 0));
                left[cur] = cur + (++nodesLeft);
                right[cur] = cur + (++nodesLeft);
            } else {
                property[cur] = -1;
                context[cur] = ctxId++;
                predictor[cur] = treeCode.readSymbol(in, 2);
                if (predictor[cur] >= 14) {
                    throw new IOException("bad predictor " + predictor[cur]);
                }
                offset[cur] = Bits.unpackSigned(treeCode.readSymbol(in, 3));
                int shift = treeCode.readSymbol(in, 4);
                if (shift >= 31) {
                    throw new IOException("bad multiplier shift");
                }
                int bits = treeCode.readSymbol(in, 5);
                if (((bits + 1) >> (31 - shift)) != 0) {
                    throw new IOException("multiplier overflow");
                }
                multiplier[cur] = (bits + 1) << shift;
            }
            if (treeIdx + nodesLeft > maxSize) {
                throw new IOException("MA tree too large");
            }
        }
        treeCode.finish(in);

        if (Boolean.getBoolean("jxl.debug")) {
            java.util.Set<Integer> props = new java.util.TreeSet<>();
            java.util.Set<Integer> preds = new java.util.TreeSet<>();
            for (int i = 0; i < treeIdx; i++) {
                if (property[i] >= 0) {
                    props.add(property[i]);
                } else {
                    preds.add(predictor[i]);
                }
            }
            System.err.println("[jxl] MA tree: nodes=" + treeIdx + " leaves=" + ctxId
                    + " properties=" + props + " predictors=" + preds);
            if (treeIdx <= 32) {
                for (int i = 0; i < treeIdx; i++) {
                    if (property[i] >= 0) {
                        System.err.printf("[jxl]   node=%d prop=%d split=%d L=%d R=%d%n",
                                i, property[i], splitValue[i], left[i], right[i]);
                    } else {
                        System.err.printf("[jxl]   leaf node=%d ctx=%d pred=%d offset=%d mult=%d%n",
                                i, context[i], predictor[i], offset[i], multiplier[i]);
                    }
                }
            }
        }
        MaTree tree = new MaTree(
                java.util.Arrays.copyOf(property, treeIdx),
                java.util.Arrays.copyOf(splitValue, treeIdx),
                java.util.Arrays.copyOf(left, treeIdx),
                java.util.Arrays.copyOf(right, treeIdx),
                java.util.Arrays.copyOf(context, treeIdx),
                java.util.Arrays.copyOf(predictor, treeIdx),
                java.util.Arrays.copyOf(offset, treeIdx),
                java.util.Arrays.copyOf(multiplier, treeIdx),
                ctxId);
        EntropyDecoder dataCode = EntropyDecoder.read(in, ctxId, true);
        return new WithCode(tree, dataCode);
    }
}
