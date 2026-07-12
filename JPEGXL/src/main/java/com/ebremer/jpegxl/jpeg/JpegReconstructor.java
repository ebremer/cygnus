package com.ebremer.jpegxl.jpeg;

import com.ebremer.jpegxl.codestream.FrameHeader;
import com.ebremer.jpegxl.container.Container;
import com.ebremer.jpegxl.decoder.JxlDecoder;
import com.ebremer.jpegxl.decoder.JxlImage;
import com.ebremer.jpegxl.vardct.VarDctState;
import java.io.IOException;

/**
 * Byte-exact reconstruction of the original JPEG file from a JPEG XL
 * container holding a {@code jbrd} box (produced by
 * {@code cjxl --lossless_jpeg=1}). The JPEG's quantised DCT coefficients
 * are taken from the recompressed VarDCT frame; everything else (markers,
 * Huffman tables, scan script, padding) comes from the reconstruction data.
 */
public final class JpegReconstructor {

    private JpegReconstructor() {
    }

    /** True when the file carries JPEG bitstream reconstruction data. */
    public static boolean hasJpegData(byte[] file) throws IOException {
        return Container.findBox(file, "jbrd") != null;
    }

    /** Rebuilds the original JPEG bytes, or throws when no jbrd box exists. */
    public static byte[] reconstruct(byte[] file) throws IOException {
        byte[] jbrd = Container.findBox(file, "jbrd");
        if (jbrd == null) {
            throw new IOException("no JPEG reconstruction data (jbrd box) in this file");
        }
        byte[] exif = Container.findBox(file, "Exif");
        byte[] xmp = Container.findBox(file, "xml ");

        VarDctState[] captured = new VarDctState[1];
        FrameHeader[] capturedFh = new FrameHeader[1];
        JxlImage image;
        JxlDecoder.JPEG_CAPTURE.set((vd, fh) -> {
            if (captured[0] == null) {
                captured[0] = vd;
                capturedFh[0] = fh;
            }
        });
        try {
            image = JxlDecoder.decode(file);
        } finally {
            JxlDecoder.JPEG_CAPTURE.remove();
        }
        if (captured[0] == null) {
            throw new IOException("jbrd: the codestream has no VarDCT frame");
        }

        JpegData jpg = JpegData.parse(jbrd, image.metadata.iccProfile, exif, xmp);
        // JPEG dimensions are pre-orientation: use the frame's coded size,
        // not the oriented image size (they differ for orientations 5-8)
        jpg.width = capturedFh[0].width;
        jpg.height = capturedFh[0].height;
        fillComponents(jpg, captured[0], capturedFh[0]);
        return new JpegWriter(jpg).write();
    }

    /**
     * Copies the raw quantised coefficients of the recompressed frame into
     * JPEG per-component block layout, and materialises the quant tables
     * from the raw-coded dequant matrices.
     */
    private static void fillComponents(JpegData jpg, VarDctState vd, FrameHeader fh)
            throws IOException {
        int[][] rawQuant = vd.dequant.rawQuant[0]; // DCT8 parameter set
        if (rawQuant == null) {
            throw new IOException("jbrd: frame does not use raw quant tables");
        }

        int numComp = jpg.components.size();
        int maxH = 1;
        int maxV = 1;
        int[] jxlChannel = new int[numComp];
        for (int i = 0; i < numComp; i++) {
            // YCbCr components map to JXL channels as (0,1,2) -> (1,0,2);
            // RGB stays in place, grayscale uses the luma channel
            jxlChannel[i] = numComp == 1 ? 1
                    : fh.doYCbCr ? (i < 2 ? 1 - i : i) : i;
        }
        for (int i = 0; i < numComp; i++) {
            int c = jxlChannel[i];
            JpegData.Component comp = jpg.components.get(i);
            comp.hSampFactor = fh.jpegSampH[c];
            comp.vSampFactor = fh.jpegSampV[c];
            maxH = Math.max(maxH, comp.hSampFactor);
            maxV = Math.max(maxV, comp.vSampFactor);
        }

        // quant table values: each component's table from its JXL channel
        // matrix; raw matrices are stored transposed relative to JPEG order
        for (int i = 0; i < numComp; i++) {
            JpegData.Component comp = jpg.components.get(i);
            JpegData.QuantTable table = jpg.quant.get(comp.quantIdx);
            int[] m = rawQuant[jxlChannel[i]];
            for (int k = 0; k < 64; k++) {
                table.values[k] = m[(k & 7) * 8 + (k >> 3)];
            }
        }
        // unused tables must replicate their predecessor (bundle guarantee)
        for (int t = 1; t < jpg.quant.size(); t++) {
            boolean used = false;
            for (JpegData.Component comp : jpg.components) {
                used |= comp.quantIdx == t;
            }
            if (!used) {
                System.arraycopy(jpg.quant.get(t - 1).values, 0,
                        jpg.quant.get(t).values, 0, 64);
            }
        }

        // chroma-from-luma correction constants (only in 4:4:4 frames):
        // scaledQ[k] = 2048 * Qluma[k] / Qchroma[k] in JPEG natural order
        boolean cfl = !fh.isSubsampled && numComp == 3;
        int lumaComp = 0;
        for (int i = 0; i < numComp; i++) {
            if (jxlChannel[i] == 1) {
                lumaComp = i;
            }
        }
        int[][] scaledQ = new int[numComp][];
        if (cfl) {
            int[] qy = jpg.quant.get(jpg.components.get(lumaComp).quantIdx).values;
            for (int i = 0; i < numComp; i++) {
                if (jxlChannel[i] == 1) {
                    continue;
                }
                int[] qc = jpg.quant.get(jpg.components.get(i).quantIdx).values;
                int[] s = new int[64];
                for (int k = 0; k < 64; k++) {
                    if (qy[k] <= 0 || qc[k] <= 0 || qy[k] >= 65536 || qc[k] >= 65536) {
                        throw new IOException("jbrd: invalid quant value");
                    }
                    s[k] = (2048 * qy[k]) / qc[k];
                }
                scaledQ[i] = s;
            }
        }
        int dcOff = 0; // RGB (no colour transform) stores DC offset by 1024/Q

        for (int i = 0; i < numComp; i++) {
            int c = jxlChannel[i];
            JpegData.Component comp = jpg.components.get(i);
            int sx = fh.jpegShiftX[c];
            int sy = fh.jpegShiftY[c];
            int wib = (fh.paddedWidth >> sx) >> 3;
            int hib = (fh.paddedHeight >> sy) >> 3;
            comp.widthInBlocks = wib;
            comp.heightInBlocks = hib;
            short[] coeffs = new short[wib * hib * 64];
            comp.coeffs = coeffs;
            if (!fh.doYCbCr && numComp == 3) {
                dcOff = 1024 / jpg.quant.get(comp.quantIdx).values[0];
            }
            int[] sq = scaledQ[i];

            int cStride = 256 >> sx;
            for (int by = 0; by < hib; by++) {
                int byF = by << sy; // full-resolution block row
                for (int bx = 0; bx < wib; bx++) {
                    int bxF = bx << sx;
                    int off = (by * wib + bx) * 64;

                    // DC from the LF image of the covering LF group
                    VarDctState.LfGroupData gg =
                            vd.lfGroups[(byF >> 8) * fh.lfGroupColumns + (bxF >> 8)];
                    if (gg.lfQuantRaw == null) {
                        throw new IOException("jbrd: LF frame reference is unsupported");
                    }
                    if (gg.lfExtraPrecision != 0) {
                        throw new IOException("jbrd: nonzero LF extra precision");
                    }
                    int cy = (byF & 255) >> sy;
                    int cx = (bxF & 255) >> sx;
                    coeffs[off] = (short)
                            (gg.lfQuantRaw[c][cy * gg.cWidth8[c] + cx] - dcOff);

                    // AC from the raw group coefficients, in natural order
                    int group = (byF >> 5) * fh.groupColumns + (bxF >> 5);
                    int[][] raw = vd.rawCoefficients(group);
                    if (raw == null) {
                        throw new IOException("jbrd: missing coefficient group " + group);
                    }
                    int pY = ((byF & 31) >> sy) << 3;
                    int pX = ((bxF & 31) >> sx) << 3;
                    int[] plane = raw[c];

                    if (sq == null) {
                        for (int y = 0; y < 8; y++) {
                            for (int x = 0; x < 8; x++) {
                                if (x == 0 && y == 0) {
                                    continue;
                                }
                                coeffs[off + y * 8 + x] =
                                        (short) plane[(pY + y) * cStride + pX + x];
                            }
                        }
                        continue;
                    }

                    // chroma-from-luma: the JPEG coefficient adds a scaled
                    // share of the luma block (integer arithmetic, decoder-exact)
                    int tileY = (byF & 255) >> 3;
                    int tileX = (bxF & 255) >> 3;
                    int factor = (c == 0 ? gg.xFromY : gg.bFromY)
                            [tileY * gg.tileStride + tileX];
                    int scale = factor * 2048 / 84;
                    int[] luma = raw[1];
                    for (int y = 0; y < 8; y++) {
                        for (int x = 0; x < 8; x++) {
                            if (x == 0 && y == 0) {
                                continue;
                            }
                            int k = y * 8 + x;
                            int qy = luma[(pY + y) * cStride + pX + x];
                            int coeffScale = (sq[k] * scale + 1024) >> 11;
                            int cflAdd = (qy * coeffScale + 1024) >> 11;
                            coeffs[off + k] = (short)
                                    (plane[(pY + y) * cStride + pX + x] + cflAdd);
                        }
                    }
                }
            }
        }
    }
}
