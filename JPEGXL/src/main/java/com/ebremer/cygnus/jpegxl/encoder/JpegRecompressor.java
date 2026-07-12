package com.ebremer.cygnus.jpegxl.encoder;

import com.ebremer.cygnus.jpegxl.codestream.ImageMetadata;
import com.ebremer.cygnus.jpegxl.codestream.SizeHeader;
import com.ebremer.cygnus.jpegxl.container.Container;
import com.ebremer.cygnus.jpegxl.io.BitWriter;
import com.ebremer.cygnus.jpegxl.jpeg.JpegData;
import com.ebremer.cygnus.jpegxl.jpeg.JpegParser;
import com.ebremer.cygnus.jpegxl.vardct.HfPass;
import com.ebremer.cygnus.jpegxl.vardct.VarDctState;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Losslessly recompresses a JPEG file into a JPEG XL container that carries
 * JPEG bitstream reconstruction data (a {@code jbrd} box): the quantised DCT
 * coefficients become a VarDCT frame with the JPEG's own quantisation tables
 * (raw-coded), and everything else — marker order, Huffman tables, scan
 * scripts, padding bits — goes into the reconstruction bundle, so
 * {@link com.ebremer.cygnus.jpegxl.jpeg.JpegReconstructor} (or {@code djxl}) can
 * rebuild the original file byte for byte. The frame also decodes to pixels
 * like any other JPEG XL image.
 */
public final class JpegRecompressor {

    private static final int[] C_MAP = {1, 0, 2};
    private static final int NUM_BLOCK_CLUSTERS = 15;
    private static final int[] DEFAULT_CTX_MAP = {
        0, 1, 2, 2, 3, 3, 4, 5, 6, 6, 6, 6, 6,
        7, 8, 9, 9, 10, 11, 12, 13, 14, 14, 14, 14, 14,
        7, 8, 9, 9, 10, 11, 12, 13, 14, 14, 14, 14, 14,
    };

    private final JpegData jpg;
    private final boolean doYCbCr;
    private final boolean grey;
    /** JXL channel -> JPEG component index, or -1 (an all-zero channel). */
    private final int[] compOf = {-1, -1, -1};
    private final int[] sampH = {1, 1, 1};
    private final int[] sampV = {1, 1, 1};
    private final int[] shiftX = new int[3];
    private final int[] shiftY = new int[3];
    private final int width;
    private final int height;
    private final int paddedWidth;
    private final int paddedHeight;

    /** Recompresses a JPEG file into a JPEG XL container with a jbrd box. */
    public static byte[] encode(byte[] jpegBytes) throws IOException {
        return new JpegRecompressor(JpegParser.parse(jpegBytes)).run();
    }

    private JpegRecompressor(JpegData jpg) throws IOException {
        this.jpg = jpg;
        this.width = jpg.width;
        this.height = jpg.height;
        int n = jpg.components.size();
        this.grey = n == 1;
        boolean rgb = false;
        if (n == 3) {
            rgb = jpg.components.get(0).id == 'R' && jpg.components.get(1).id == 'G'
                    && jpg.components.get(2).id == 'B';
        }
        this.doYCbCr = !rgb;
        if (grey) {
            compOf[1] = 0;
        } else if (rgb) {
            compOf[0] = 0;
            compOf[1] = 1;
            compOf[2] = 2;
        } else {
            compOf[1] = 0; // Y
            compOf[0] = 1; // Cb
            compOf[2] = 2; // Cr
        }
        for (int c = 0; c < 3; c++) {
            if (compOf[c] >= 0) {
                sampH[c] = jpg.components.get(compOf[c]).hSampFactor;
                sampV[c] = jpg.components.get(compOf[c]).vSampFactor;
            }
        }
        if (!doYCbCr && (sampH[0] != 1 || sampH[1] != 1 || sampH[2] != 1
                || sampV[0] != 1 || sampV[1] != 1 || sampV[2] != 1)) {
            throw new IOException("subsampled RGB JPEGs are not supported");
        }
        int maxH = Math.max(sampH[0], Math.max(sampH[1], sampH[2]));
        int maxV = Math.max(sampV[0], Math.max(sampV[1], sampV[2]));
        for (int c = 0; c < 3; c++) {
            shiftX[c] = maxH - sampH[c] > 0 ? 1 : 0;
            shiftY[c] = maxV - sampV[c] > 0 ? 1 : 0;
        }
        this.paddedWidth = ceilDiv(ceilDiv(width, 8), maxH) * maxH * 8;
        this.paddedHeight = ceilDiv(ceilDiv(height, 8), maxV) * maxV * 8;
    }

    /** RGB frames store DC offset by 1024/Q, per component (YCbCr stores none). */
    private int dcOffset(int channel) {
        return doYCbCr ? 0 : 1024 / qt0(channel);
    }

    private byte[] run() throws IOException {
        BitWriter cs = new BitWriter();
        cs.write(0xff, 8);
        cs.write(0x0a, 8);
        new SizeHeader(width, height).write(cs);
        buildMetadata().write(cs);
        writeFrame(cs);
        byte[] codestream = cs.toByteArray();
        byte[] jbrd = buildJbrd();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Container.SIGNATURE_BOX);
        out.writeBytes(Container.FTYP_BOX);
        writeBox(out, "jbrd", jbrd);
        writeBox(out, "jxlc", codestream);
        return out.toByteArray();
    }

    private static void writeBox(ByteArrayOutputStream out, String type, byte[] payload) {
        long size = 8L + payload.length;
        out.write((int) (size >>> 24));
        out.write((int) (size >>> 16));
        out.write((int) (size >>> 8));
        out.write((int) size);
        for (int i = 0; i < 4; i++) {
            out.write(type.charAt(i));
        }
        out.writeBytes(payload);
    }

    private ImageMetadata buildMetadata() {
        ImageMetadata meta = new ImageMetadata();
        meta.xybEncoded = false;
        meta.bitDepth = com.ebremer.cygnus.jpegxl.codestream.BitDepth.of(8);
        meta.modular16BitBuffers = true;
        com.ebremer.cygnus.jpegxl.codestream.ColourEncoding colour =
                new com.ebremer.cygnus.jpegxl.codestream.ColourEncoding();
        if (grey) {
            colour.allDefault = false;
            colour.colourSpace = com.ebremer.cygnus.jpegxl.codestream.ColourEncoding.CS_GREY;
        }
        meta.colourEncoding = colour;
        return meta;
    }

    // -------------------------------------------------------------- the frame

    private int qt0(int channel) {
        int comp = compOf[channel] >= 0 ? compOf[channel] : compOf[1];
        return jpg.quant.get(jpg.components.get(comp).quantIdx).values[0];
    }

    private void writeFrame(BitWriter out) throws IOException {
        int w8 = paddedWidth >> 3;
        int h8 = paddedHeight >> 3;
        int groupColumns = ceilDiv(width, 256);
        int groupRows = ceilDiv(height, 256);
        int numGroups = groupColumns * groupRows;
        int lfCols = ceilDiv(width, 2048);
        int lfRows = ceilDiv(height, 2048);
        int numLfGroups = lfCols * lfRows;
        boolean single = numGroups == 1;

        // ---- per-group coefficient tokens
        EntropyEncoder hfEnc = new EntropyEncoder(495 * NUM_BLOCK_CLUSTERS, false);
        int[][] groupCtx = new int[numGroups][];
        int[][] groupVal = new int[numGroups][];
        for (int g = 0; g < numGroups; g++) {
            java.util.ArrayList<int[]> tokens = new java.util.ArrayList<>();
            tokenizeGroup(g, groupColumns, w8, h8, tokens);
            int[] tc = new int[tokens.size()];
            int[] tv = new int[tokens.size()];
            for (int i = 0; i < tokens.size(); i++) {
                tc[i] = tokens.get(i)[0];
                tv[i] = tokens.get(i)[1];
            }
            groupCtx[g] = tc;
            groupVal[g] = tv;
            for (int i = 0; i < tc.length; i++) {
                hfEnc.count(tc[i], tv[i]);
            }
        }

        if (single) {
            BitWriter one = new BitWriter();
            writeLfGlobalBits(one);
            writeLfGroupBits(one, 0, lfCols, w8, h8);
            writeHfGlobalBits(one, hfEnc, numGroups, numLfGroups);
            for (int i = 0; i < groupCtx[0].length; i++) {
                hfEnc.write(one, groupCtx[0][i], groupVal[0][i]);
            }
            one.zeroPadToByte();
            byte[] payload = one.toByteArray();
            writeFrameHeader(out);
            out.writeBool(false); // TOC not permuted
            out.zeroPadToByte();
            writeTocEntry(out, payload.length);
            out.zeroPadToByte();
            out.writeBytes(payload);
            return;
        }

        BitWriter lfg = new BitWriter();
        writeLfGlobalBits(lfg);
        lfg.zeroPadToByte();
        byte[] lfGlobalBytes = lfg.toByteArray();
        byte[][] lfGroupBytes = new byte[numLfGroups][];
        for (int gg = 0; gg < numLfGroups; gg++) {
            BitWriter w = new BitWriter();
            writeLfGroupBits(w, gg, lfCols, w8, h8);
            w.zeroPadToByte();
            lfGroupBytes[gg] = w.toByteArray();
        }
        BitWriter hfg = new BitWriter();
        writeHfGlobalBits(hfg, hfEnc, numGroups, numLfGroups);
        hfg.zeroPadToByte();
        byte[] hfGlobalBytes = hfg.toByteArray();
        byte[][] passBytes = new byte[numGroups][];
        for (int g = 0; g < numGroups; g++) {
            BitWriter gw = new BitWriter();
            for (int i = 0; i < groupCtx[g].length; i++) {
                hfEnc.write(gw, groupCtx[g][i], groupVal[g][i]);
            }
            gw.zeroPadToByte();
            passBytes[g] = gw.toByteArray();
        }

        writeFrameHeader(out);
        out.writeBool(false); // TOC not permuted
        out.zeroPadToByte();
        writeTocEntry(out, lfGlobalBytes.length);
        for (byte[] b : lfGroupBytes) {
            writeTocEntry(out, b.length);
        }
        writeTocEntry(out, hfGlobalBytes.length);
        for (byte[] b : passBytes) {
            writeTocEntry(out, b.length);
        }
        out.zeroPadToByte();
        out.writeBytes(lfGlobalBytes);
        for (byte[] b : lfGroupBytes) {
            out.writeBytes(b);
        }
        out.writeBytes(hfGlobalBytes);
        for (byte[] b : passBytes) {
            out.writeBytes(b);
        }
    }

    private void writeFrameHeader(BitWriter out) {
        out.zeroPadToByte();
        out.writeBool(false);        // !all_default
        out.write(0, 2);             // frame_type: regular
        out.writeBool(false);        // encoding: VarDCT
        out.writeU64(128);           // flags: skip adaptive LF smoothing
        out.writeBool(doYCbCr);
        if (doYCbCr) {
            for (int c = 0; c < 3; c++) { // channel order Cb, Y, Cr
                int code;
                if (sampH[c] == 2 && sampV[c] == 2) {
                    code = 1;
                } else if (sampH[c] == 2) {
                    code = 2;
                } else if (sampV[c] == 2) {
                    code = 3;
                } else {
                    code = 0;
                }
                out.write(code, 2);
            }
        }
        out.write(0, 2);             // log upsampling
        out.write(0, 2);             // num_passes = 1
        out.writeBool(false);        // have_crop
        out.write(0, 2);             // blend mode: replace
        out.writeBool(true);         // is_last
        out.write(0, 2);             // name length
        out.writeBool(false);        // RestorationFilter not all_default
        out.writeBool(false);        // gaborish off
        out.write(0, 2);             // epf iterations = 0
        out.writeU64(0);             // restoration filter extensions
        out.writeU64(0);             // frame header extensions
    }

    private void writeLfGlobalBits(BitWriter w) {
        w.writeBool(false);          // LfChannelDequantization not all_default:
        for (int c = 0; c < 3; c++) {
            // scaledDequant must come out as qt0/2040 (see globalScale below)
            w.write(halfBits(qt0(c)), 16);
        }
        // globalScale 65280 with quantLf 16: 65536/(65280*16) * (qt0*1/128)
        // = qt0/2040, matching the raw HF tables' qt/2040 exactly
        w.write(3, 2);
        w.write(65280 - 8193, 16);
        w.write(0, 2);               // quantLf selector 0 -> 16
        w.writeBool(true);           // HFBlockContext all_default
        w.writeBool(false);          // LfChannelCorrelation not all_default
        w.write(0, 2);               // colour factor selector 0 -> 84
        w.write(0, 16);              // base correlation X = 0
        w.write(0, 16);              // base correlation B = 0
        w.write(128, 8);             // x factor LF
        w.write(128, 8);             // b factor LF
        w.writeBool(false);          // no global MA tree
    }

    private void writeLfGroupBits(BitWriter w, int gg, int lfCols, int w8, int h8) {
        int row = gg / lfCols;
        int col = gg % lfCols;
        int bw = Math.min(256, w8 - col * 256);
        int bh = Math.min(256, h8 - row * 256);

        w.write(0, 2); // extra precision = 0
        // DC coefficients as a modular image in stream order (Y, X, B), each
        // channel at its own subsampled size
        int[][] lf = new int[3][];
        int[] ws = new int[3];
        int[] hs = new int[3];
        for (int i = 0; i < 3; i++) {
            int c = C_MAP[i];
            int cw = bw >> shiftX[c];
            int ch = bh >> shiftY[c];
            ws[i] = cw;
            hs[i] = ch;
            int[] plane = new int[cw * ch];
            int comp = compOf[c];
            if (comp >= 0) {
                JpegData.Component jc = jpg.components.get(comp);
                int dcOff = dcOffset(c);
                int blockRow0 = row * (256 >> shiftY[c]);
                int blockCol0 = col * (256 >> shiftX[c]);
                for (int y = 0; y < ch; y++) {
                    for (int x = 0; x < cw; x++) {
                        int idx = ((blockRow0 + y) * jc.widthInBlocks + blockCol0 + x) << 6;
                        plane[y * cw + x] = jc.coeffs[idx] + dcOff;
                    }
                }
            }
            lf[i] = plane;
        }
        ModularSub.write(w, lf, ws, hs);

        // HF metadata: every full-resolution cell is a DCT8 block
        int nbBlocks = bw * bh;
        int n = ceilLog2(bw * bh);
        w.write(nbBlocks - 1, n);
        int tileW = ceilDiv(bw, 8);
        int tileH = ceilDiv(bh, 8);
        int[][] metaPx = {
            new int[tileW * tileH],  // x-from-y factors: zero (no chroma-from-luma)
            new int[tileW * tileH],  // b-from-y factors
            new int[2 * nbBlocks],   // block info: all type 0, multiplier 1
            new int[bw * bh],        // sharpness
        };
        ModularSub.write(w, metaPx,
                new int[] {tileW, tileW, nbBlocks, bw},
                new int[] {tileH, tileH, 2, bh});
    }

    private void writeHfGlobalBits(BitWriter w, EntropyEncoder hfEnc, int numGroups,
            int numLfGroups) {
        w.writeBool(false); // dequant matrices not all default
        for (int i = 0; i < 17; i++) {
            if (i != 0) {
                w.write(0, 3); // library default for every non-DCT8 set
                continue;
            }
            w.write(7, 3);     // RAW mode: the JPEG quant tables verbatim
            w.write(halfBits(1f / 2048), 16); // denominator: qt/2048 * 65536/65280 = qt/2040
            int[][] m = new int[3][64];
            for (int c = 0; c < 3; c++) {
                int comp = compOf[c] >= 0 ? compOf[c] : compOf[1];
                int[] values = jpg.quant.get(jpg.components.get(comp).quantIdx).values;
                for (int j = 0; j < 64; j++) {
                    // raw matrices are stored transposed relative to JPEG order
                    m[c][j] = values[(j & 7) * 8 + (j >> 3)];
                }
            }
            ModularSub.write(w, m, new int[] {8, 8, 8}, new int[] {8, 8, 8});
        }
        int bits = numGroups > 1 ? 32 - Integer.numberOfLeadingZeros(numGroups - 1) : 0;
        w.write(0, bits);  // num_hf_presets = 1
        w.write(2, 2);     // used_orders selector 2 -> 0 (natural orders)
        hfEnc.writeSpec(w);
    }

    private void tokenizeGroup(int g, int groupColumns, int w8, int h8,
            java.util.List<int[]> tokens) {
        int gRow = g / groupColumns;
        int gCol = g % groupColumns;
        int by0 = gRow << 5;
        int bx0 = gCol << 5;
        int byN = Math.min(by0 + 32, h8);
        int bxN = Math.min(bx0 + 32, w8);
        int[][] nonZeroes = new int[3][32 * 32];
        int[] order = HfPass.naturalOrder(0);
        for (int by = by0; by < byN; by++) {
            for (int bx = bx0; bx < bxN; bx++) {
                int groupY = by - by0;
                int groupX = bx - bx0;
                for (int ci = 0; ci < 3; ci++) {
                    int c = C_MAP[ci];
                    int sGroupY = groupY >> shiftY[c];
                    int sGroupX = groupX >> shiftX[c];
                    if (groupY != sGroupY << shiftY[c] || groupX != sGroupX << shiftX[c]) {
                        continue; // not on this channel's subsampled grid
                    }
                    int comp = compOf[c];
                    short[] coeffs = null;
                    int off = 0;
                    if (comp >= 0) {
                        JpegData.Component jc = jpg.components.get(comp);
                        off = ((by >> shiftY[c]) * jc.widthInBlocks + (bx >> shiftX[c])) << 6;
                        coeffs = jc.coeffs;
                    }
                    int nonZero = 0;
                    if (coeffs != null) {
                        for (int k = 1; k < 64; k++) {
                            if (coeffs[off + k] != 0) {
                                nonZero++;
                            }
                        }
                    }
                    int predicted = VarDctState.predictNonZeroes(nonZeroes[c], sGroupY, sGroupX);
                    int blockCtx = DEFAULT_CTX_MAP[(c < 2 ? 1 - c : c) * 13];
                    int nonZeroCtx = VarDctState.nonZeroContext(predicted, blockCtx,
                            NUM_BLOCK_CLUSTERS);
                    tokens.add(new int[] {nonZeroCtx, nonZero});
                    nonZeroes[c][sGroupY * 32 + sGroupX] = nonZero;
                    if (nonZero == 0) {
                        continue;
                    }
                    int histCtx = 458 * blockCtx + 37 * NUM_BLOCK_CLUSTERS;
                    int remaining = nonZero;
                    int prevToken = -1;
                    for (int j = 1; j < 64 && remaining > 0; j++) {
                        int o = order[j];
                        int oy = o >> 16;
                        int ox = o & 0xffff;
                        // the decoder stores this token at grid (oy, ox), whose
                        // JPEG natural position is the transpose
                        int v = coeffs[off + ox * 8 + oy];
                        int prev = j == 1
                                ? (nonZero > 64 / 16 ? 0 : 1)
                                : (prevToken != 0 ? 1 : 0);
                        int ctx = histCtx
                                + VarDctState.coefficientContext(j, remaining, 1, prev);
                        int packed = packSigned(v);
                        tokens.add(new int[] {ctx, packed});
                        prevToken = packed;
                        if (v != 0) {
                            remaining--;
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- jbrd box

    private byte[] buildJbrd() throws IOException {
        BitWriter w = new BitWriter();
        w.writeBool(grey);

        for (byte m : jpg.markerOrder) {
            w.write((m & 0xff) - 0xc0, 6);
        }

        for (int i = 0; i < jpg.appData.size(); i++) {
            writeU32(w, jpg.appMarkerType.get(i), 0, 0, 1, 0, 2, 1, 4, 2);
            w.write(jpg.appData.get(i).length - 1, 16);
        }
        for (byte[] com : jpg.comData) {
            w.write(com.length - 1, 16);
        }

        writeU32(w, jpg.quant.size(), 1, 0, 2, 0, 3, 0, 4, 0);
        for (JpegData.QuantTable q : jpg.quant) {
            w.write(q.precision, 1);
            w.write(q.index, 2);
            w.writeBool(q.isLast);
        }

        int componentType = componentType();
        w.write(componentType, 2);
        if (componentType == 3) {
            writeU32(w, jpg.components.size(), 1, 0, 2, 0, 3, 0, 4, 0);
            for (JpegData.Component c : jpg.components) {
                w.write(c.id, 8);
            }
        }
        for (JpegData.Component c : jpg.components) {
            w.write(c.quantIdx, 2);
        }

        writeU32(w, jpg.huffmanCodes.size(), 4, 0, 2, 3, 10, 4, 26, 6);
        for (JpegData.HuffmanCode hc : jpg.huffmanCodes) {
            w.writeBool((hc.slotId & 0x10) != 0);
            w.write(hc.slotId & 3, 2);
            w.writeBool(hc.isLast);
            int numSymbols = 0;
            for (int k = 0; k <= 16; k++) {
                writeU32(w, hc.counts[k], 0, 0, 1, 0, 2, 3, 0, 8);
                numSymbols += hc.counts[k];
            }
            for (int k = 0; k < numSymbols; k++) {
                writeU32(w, hc.values[k], 0, 2, 4, 2, 8, 4, 1, 8);
            }
        }

        for (JpegData.ScanInfo si : jpg.scanInfo) {
            writeU32(w, si.numComponents, 1, 0, 2, 0, 3, 0, 4, 0);
            w.write(si.ss, 6);
            w.write(si.se, 6);
            w.write(si.al, 4);
            w.write(si.ah, 4);
            for (int k = 0; k < si.numComponents; k++) {
                w.write(si.components[k].compIdx, 2);
                w.write(si.components[k].acTblIdx, 2);
                w.write(si.components[k].dcTblIdx, 2);
            }
            writeU32(w, 0, 0, 0, 1, 0, 2, 0, 3, 3); // last_needed_pass
        }

        boolean hasDri = false;
        for (byte m : jpg.markerOrder) {
            hasDri |= (m & 0xff) == 0xdd;
        }
        if (hasDri) {
            w.write(jpg.restartInterval, 16);
        }

        for (JpegData.ScanInfo si : jpg.scanInfo) {
            writeU32(w, si.resetPoints.length, 0, 0, 1, 2, 4, 4, 20, 16);
            int last = -1;
            for (int block : si.resetPoints) {
                writeU32(w, block - last - 1, 0, 0, 1, 3, 9, 5, 41, 28);
                last = block;
            }
            writeU32(w, si.extraZeroRunBlock.length, 0, 0, 1, 2, 4, 4, 20, 16);
            last = -1;
            for (int k = 0; k < si.extraZeroRunBlock.length; k++) {
                writeU32(w, si.extraZeroRunCount[k], 1, 0, 2, 2, 5, 4, 20, 8);
                writeU32(w, si.extraZeroRunBlock[k] - last - 1, 0, 0, 1, 3, 9, 5, 41, 28);
                last = si.extraZeroRunBlock[k];
            }
        }

        for (byte[] inter : jpg.interMarkerData) {
            w.write(inter.length, 16);
        }
        writeU32(w, jpg.tailData.length, 0, 0, 1, 8, 257, 16, 65793, 22);

        w.writeBool(jpg.hasZeroPaddingBit);
        if (jpg.hasZeroPaddingBit) {
            w.write(jpg.paddingBits.length, 24);
            for (byte b : jpg.paddingBits) {
                w.writeBool(b != 0);
            }
        }
        w.zeroPadToByte();

        // the blobs: unknown app markers, COM markers, inter-marker data and
        // the tail, brotli-compressed (as uncompressed metablocks)
        ByteArrayOutputStream blobs = new ByteArrayOutputStream();
        for (int i = 0; i < jpg.appData.size(); i++) {
            if (jpg.appMarkerType.get(i) == JpegData.APP_UNKNOWN) {
                blobs.writeBytes(jpg.appData.get(i));
            }
        }
        for (byte[] com : jpg.comData) {
            blobs.writeBytes(com);
        }
        for (byte[] inter : jpg.interMarkerData) {
            blobs.writeBytes(inter);
        }
        blobs.writeBytes(jpg.tailData);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(w.toByteArray());
        out.writeBytes(brotliRaw(blobs.toByteArray()));
        return out.toByteArray();
    }

    private int componentType() throws IOException {
        if (jpg.components.size() == 1) {
            return jpg.components.get(0).id == 1 ? 0 : 3;
        }
        int id0 = jpg.components.get(0).id;
        int id1 = jpg.components.get(1).id;
        int id2 = jpg.components.get(2).id;
        if (id0 == 1 && id1 == 2 && id2 == 3) {
            return 1;
        }
        if (id0 == 'R' && id1 == 'G' && id2 == 'B') {
            return 2;
        }
        return 3;
    }

    /** RFC 7932 stream of uncompressed metablocks (bit-packed LSB first). */
    static byte[] brotliRaw(byte[] data) {
        BitWriter w = new BitWriter();
        w.write(0, 1); // WBITS = 16
        int pos = 0;
        while (pos < data.length) {
            int chunk = Math.min(data.length - pos, 1 << 16);
            w.write(0, 1);          // ISLAST = 0
            w.write(0, 2);          // MNIBBLES code 0 -> 4 nibbles
            w.write(chunk - 1, 16); // MLEN - 1
            w.write(1, 1);          // ISUNCOMPRESSED
            w.zeroPadToByte();
            for (int i = 0; i < chunk; i++) {
                w.write(data[pos + i] & 0xff, 8);
            }
            pos += chunk;
        }
        w.write(1, 1); // ISLAST
        w.write(1, 1); // ISLASTEMPTY
        w.zeroPadToByte();
        return w.toByteArray();
    }

    // --------------------------------------------------------------- helpers

    /** Writes {@code value} in the U32 encoding given by 4 (offset, bits) pairs. */
    private static void writeU32(BitWriter w, int value,
            int o0, int b0, int o1, int b1, int o2, int b2, int o3, int b3) {
        int[] offset = {o0, o1, o2, o3};
        int[] bits = {b0, b1, b2, b3};
        for (int s = 0; s < 4; s++) {
            long max = offset[s] + (1L << bits[s]) - 1;
            if (value >= offset[s] && value <= max) {
                w.write(s, 2);
                w.write(value - offset[s], bits[s]);
                return;
            }
        }
        throw new IllegalArgumentException("value " + value + " fits no U32 selector");
    }

    /**
     * IEEE 754 half-precision bits of {@code v}, rounded and clamped to the
     * finite range. Only the pixel-path dequantisation depends on these
     * values; the byte-exact reconstruction reads the raw integer tables.
     */
    private static int halfBits(float v) {
        int bits = Float.floatToFloat16(Math.min(v, 65504f)) & 0xffff;
        if (((bits >> 10) & 0x1f) == 31) {
            throw new IllegalArgumentException("half-float overflow: " + v);
        }
        return bits;
    }

    private static void writeTocEntry(BitWriter out, int size) {
        if (size < 1024) {
            out.write(0, 2);
            out.write(size, 10);
        } else if (size < 17408) {
            out.write(1, 2);
            out.write(size - 1024, 14);
        } else if (size < 4211712) {
            out.write(2, 2);
            out.write(size - 17408, 22);
        } else {
            out.write(3, 2);
            out.write(size - 4211712, 30);
        }
    }

    private static int packSigned(int v) {
        return v >= 0 ? 2 * v : -2 * v - 1;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static int ceilLog2(int x) {
        return x > 1 ? 32 - Integer.numberOfLeadingZeros(x - 1) : 0;
    }
}
