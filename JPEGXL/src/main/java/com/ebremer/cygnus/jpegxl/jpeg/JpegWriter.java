package com.ebremer.cygnus.jpegxl.jpeg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Byte-exact JPEG serializer for reconstructed {@link JpegData}: emits the
 * markers in their recorded order and entropy-codes the coefficients with
 * the original Huffman tables, restart markers, recorded padding bits,
 * extra zero runs and EOB-run reset points (baseline and progressive).
 */
final class JpegWriter {

    private final JpegData jpg;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);

    // entropy-coded bit writer state (MSB first, with 0xFF stuffing)
    private long putBuffer;
    private int putBits = 64;

    // per-slot Huffman tables
    private final int[][] dcDepth = new int[4][];
    private final int[][] dcCode = new int[4][];
    private final int[][] acDepth = new int[4][];
    private final int[][] acCode = new int[4][];

    // progressive coding state
    private int eobRun;
    private int[] eobAcDepth;
    private int[] eobAcCode;
    private final java.util.ArrayList<Integer> refinementBits = new java.util.ArrayList<>();

    private int dhtIndex;
    private int dqtIndex;
    private int appIndex;
    private int comIndex;
    private int interIndex;
    private int scanIndex;
    private int padBitPos;
    private boolean isProgressive;
    private boolean seenDri;

    JpegWriter(JpegData jpg) {
        this.jpg = jpg;
    }

    byte[] write() throws IOException {
        if (jpg.markerOrder.length == 0) {
            throw new IOException("jbrd: no markers");
        }
        out.write(0xFF);
        out.write(0xD8); // SOI
        for (byte m : jpg.markerOrder) {
            serializeSection(m & 0xff);
        }
        if (jpg.hasZeroPaddingBit && padBitPos != jpg.paddingBits.length) {
            throw new IOException("jbrd: unused padding bits");
        }
        return out.toByteArray();
    }

    private void serializeSection(int marker) throws IOException {
        switch (marker) {
            case 0xC0, 0xC1, 0xC2, 0xC9, 0xCA -> encodeSOF(marker);
            case 0xC4 -> encodeDHT();
            case 0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7 -> {
                out.write(0xFF);
                out.write(marker);
            }
            case 0xD9 -> {
                out.write(0xFF);
                out.write(0xD9);
                out.writeBytes(jpg.tailData);
            }
            case 0xDA -> encodeScan();
            case 0xDB -> encodeDQT();
            case 0xDD -> {
                seenDri = true;
                out.write(0xFF);
                out.write(0xDD);
                out.write(0);
                out.write(4);
                out.write(jpg.restartInterval >> 8);
                out.write(jpg.restartInterval & 0xFF);
            }
            case 0xE0, 0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7,
                 0xE8, 0xE9, 0xEA, 0xEB, 0xEC, 0xED, 0xEE, 0xEF -> {
                if (appIndex >= jpg.appData.size()) {
                    throw new IOException("jbrd: missing app data");
                }
                out.write(0xFF);
                out.writeBytes(jpg.appData.get(appIndex++));
            }
            case 0xFE -> {
                if (comIndex >= jpg.comData.size()) {
                    throw new IOException("jbrd: missing com data");
                }
                out.write(0xFF);
                out.writeBytes(jpg.comData.get(comIndex++));
            }
            case 0xFF -> {
                if (interIndex >= jpg.interMarkerData.size()) {
                    throw new IOException("jbrd: missing inter-marker data");
                }
                out.writeBytes(jpg.interMarkerData.get(interIndex++));
            }
            default -> throw new IOException(
                    String.format("jbrd: cannot serialize marker 0x%02X", marker));
        }
    }

    private void encodeSOF(int marker) throws IOException {
        if (marker <= 0xC2) {
            isProgressive = marker == 0xC2;
        }
        int n = jpg.components.size();
        int len = 8 + 3 * n;
        out.write(0xFF);
        out.write(marker);
        out.write(len >> 8);
        out.write(len & 0xFF);
        out.write(8); // precision
        out.write(jpg.height >> 8);
        out.write(jpg.height & 0xFF);
        out.write(jpg.width >> 8);
        out.write(jpg.width & 0xFF);
        out.write(n);
        for (JpegData.Component c : jpg.components) {
            out.write(c.id);
            out.write((c.hSampFactor << 4) | c.vSampFactor);
            out.write(jpg.quant.get(c.quantIdx).index);
        }
    }

    private void encodeDQT() throws IOException {
        int len = 2;
        for (int i = dqtIndex; i < jpg.quant.size(); i++) {
            JpegData.QuantTable t = jpg.quant.get(i);
            len += 1 + (t.precision != 0 ? 2 : 1) * 64;
            if (t.isLast) {
                break;
            }
        }
        out.write(0xFF);
        out.write(0xDB);
        out.write(len >> 8);
        out.write(len & 0xFF);
        while (true) {
            if (dqtIndex >= jpg.quant.size()) {
                throw new IOException("jbrd: missing quant table");
            }
            JpegData.QuantTable t = jpg.quant.get(dqtIndex++);
            out.write((t.precision << 4) + t.index);
            for (int i = 0; i < 64; i++) {
                int v = t.values[JpegData.NATURAL_ORDER[i]];
                if (t.precision != 0) {
                    out.write(v >> 8);
                }
                out.write(v & 0xFF);
            }
            if (t.isLast) {
                break;
            }
        }
    }

    private void encodeDHT() throws IOException {
        int len = 2;
        for (int i = dhtIndex; i < jpg.huffmanCodes.size(); i++) {
            JpegData.HuffmanCode hc = jpg.huffmanCodes.get(i);
            for (int count : hc.counts) {
                len += count;
            }
            if (len == 2) {
                break; // empty DHT marker
            }
            len += 16;
            if (hc.isLast) {
                break;
            }
        }
        out.write(0xFF);
        out.write(0xC4);
        out.write(len >> 8);
        out.write(len & 0xFF);
        while (true) {
            if (dhtIndex >= jpg.huffmanCodes.size()) {
                throw new IOException("jbrd: missing huffman code");
            }
            JpegData.HuffmanCode hc = jpg.huffmanCodes.get(dhtIndex++);
            int total = 0;
            int maxLength = 0;
            for (int i = 0; i < hc.counts.length; i++) {
                if (hc.counts[i] != 0) {
                    maxLength = i;
                }
                total += hc.counts[i];
            }
            if (total == 0) {
                break; // empty DHT marker
            }
            int[] depth = new int[257];
            int[] code = new int[257];
            buildHuffmanTable(hc, depth, code);
            int slot = hc.slotId;
            if ((slot & 0x10) != 0) {
                acDepth[slot - 0x10] = depth;
                acCode[slot - 0x10] = code;
            } else {
                dcDepth[slot] = depth;
                dcCode[slot] = code;
            }
            total--;
            out.write(hc.slotId);
            for (int i = 1; i <= 16; i++) {
                out.write(i == maxLength ? hc.counts[i] - 1 : hc.counts[i]);
            }
            for (int i = 0; i < total; i++) {
                out.write(hc.values[i]);
            }
            if (hc.isLast) {
                break;
            }
        }
    }

    /** Canonical JPEG Huffman code from length counts (sentinel included). */
    private static void buildHuffmanTable(JpegData.HuffmanCode hc, int[] depth, int[] code)
            throws IOException {
        int[] huffSize = new int[258];
        int p = 0;
        for (int l = 1; l <= 16; l++) {
            int i = hc.counts[l];
            if (p + i > 257) {
                throw new IOException("jbrd: bad huffman counts");
            }
            while (i-- > 0) {
                huffSize[p++] = l;
            }
        }
        if (p == 0) {
            return;
        }
        int lastP = p - 1;
        huffSize[lastP] = 0;
        int[] huffCode = new int[257];
        int c = 0;
        int si = huffSize[0];
        p = 0;
        while (huffSize[p] != 0) {
            while (huffSize[p] == si) {
                huffCode[p++] = c;
                c++;
            }
            c <<= 1;
            si++;
        }
        for (p = 0; p < lastP; p++) {
            depth[hc.values[p]] = huffSize[p];
            code[hc.values[p]] = huffCode[p];
        }
    }

    // ------------------------------------------------------------- bit level

    private void writeBits(int nbits, long bits) {
        putBits -= nbits;
        if (putBits < 0) {
            // discharge the 64-bit buffer
            putBuffer |= bits >>> -putBits;
            long b = putBuffer;
            for (int s = 56; s >= 0; s -= 8) {
                int by = (int) (b >>> s) & 0xFF;
                out.write(by);
                if (by == 0xFF) {
                    out.write(0);
                }
            }
            putBits += 64;
            putBuffer = putBits == 64 ? 0 : bits << putBits;
        } else {
            putBuffer |= bits << putBits;
        }
    }

    private void jumpToByteBoundary() throws IOException {
        int nBits = putBits & 7;
        int padPattern;
        if (!jpg.hasZeroPaddingBit) {
            padPattern = (1 << nBits) - 1;
        } else {
            padPattern = 0;
            int dangling = 0;
            for (int i = 0; i < nBits; i++) {
                if (padBitPos >= jpg.paddingBits.length) {
                    throw new IOException("jbrd: out of padding bits");
                }
                int bit = jpg.paddingBits[padBitPos++];
                dangling |= bit;
                padPattern = (padPattern << 1) | bit;
            }
            if ((dangling & ~1) != 0) {
                throw new IOException("jbrd: invalid padding bit");
            }
        }
        while (putBits <= 56) {
            int c = (int) (putBuffer >>> 56) & 0xFF;
            out.write(c);
            if (c == 0xFF) {
                out.write(0);
            }
            putBuffer <<= 8;
            putBits += 8;
        }
        if (putBits < 64) {
            int padMask = 0xFF >>> (64 - putBits);
            int c = ((int) (putBuffer >>> 56) & ~padMask) | padPattern;
            out.write(c);
            if (c == 0xFF) {
                out.write(0);
            }
        }
        putBuffer = 0;
        putBits = 64;
    }

    // -------------------------------------------------------------- scans

    private void flushEobRun() {
        if (eobRun > 0) {
            int nbits = 31 - Integer.numberOfLeadingZeros(eobRun);
            int symbol = nbits << 4;
            writeBits(eobAcDepth[symbol], eobAcCode[symbol]);
            if (nbits > 0) {
                writeBits(nbits, eobRun & ((1 << nbits) - 1));
            }
            eobRun = 0;
        }
        for (int bit : refinementBits) {
            writeBits(1, bit);
        }
        refinementBits.clear();
    }

    private void bufferEndOfBand(int[] acDep, int[] acCod, int[] newBits, int newBitsCount) {
        if (eobRun == 0) {
            eobAcDepth = acDep;
            eobAcCode = acCod;
        }
        eobRun++;
        for (int i = 0; i < newBitsCount; i++) {
            refinementBits.add(newBits[i]);
        }
        if (eobRun == 0x7FFF) {
            flushEobRun();
        }
    }

    private void encodeScan() throws IOException {
        JpegData.ScanInfo scan = jpg.scanInfo.get(scanIndex);

        // SOS marker
        int n = scan.numComponents;
        int len = 6 + 2 * n;
        out.write(0xFF);
        out.write(0xDA);
        out.write(len >> 8);
        out.write(len & 0xFF);
        out.write(n);
        for (int i = 0; i < n; i++) {
            JpegData.ScanComponent si = scan.components[i];
            out.write(jpg.components.get(si.compIdx).id);
            out.write((si.dcTblIdx << 4) + si.acTblIdx);
        }
        out.write(scan.ss);
        out.write(scan.se);
        out.write((scan.ah << 4) | scan.al);

        int restartInterval = seenDri ? jpg.restartInterval : 0;
        int[] lastDcCoeff = new int[4];
        eobRun = 0;
        refinementBits.clear();

        boolean interleaved = n > 1;
        JpegData.Component base = jpg.components.get(scan.components[0].compIdx);
        int hGroup = interleaved ? 1 : base.hSampFactor;
        int vGroup = interleaved ? 1 : base.vSampFactor;
        int maxH = 1;
        int maxV = 1;
        for (JpegData.Component c : jpg.components) {
            maxH = Math.max(maxH, c.hSampFactor);
            maxV = Math.max(maxV, c.vSampFactor);
        }
        int mcusPerRow = (jpg.width * hGroup + 8 * maxH - 1) / (8 * maxH);
        int mcuRows = (jpg.height * vGroup + 8 * maxV - 1) / (8 * maxV);

        int al = isProgressive ? scan.al : 0;
        int ss = isProgressive ? scan.ss : 0;
        int se = isProgressive ? scan.se : 63;
        int mode = !isProgressive || (scan.ah == 0 && al == 0 && ss == 0 && se == 63)
                ? 0 : scan.ah == 0 ? 1 : 2;

        int restartsToGo = restartInterval;
        int nextRestartMarker = 0;
        int blockScanIndex = 0;
        int resetPointPos = 0;
        int zeroRunPos = 0;

        for (int mcuY = 0; mcuY < mcuRows; mcuY++) {
            for (int mcuX = 0; mcuX < mcusPerRow; mcuX++) {
                if (restartInterval > 0 && restartsToGo == 0) {
                    flushEobRun();
                    jumpToByteBoundary();
                    out.write(0xFF);
                    out.write(0xD0 + nextRestartMarker);
                    nextRestartMarker = (nextRestartMarker + 1) & 7;
                    restartsToGo = restartInterval;
                    java.util.Arrays.fill(lastDcCoeff, 0);
                }
                for (int i = 0; i < n; i++) {
                    JpegData.ScanComponent si = scan.components[i];
                    JpegData.Component c = jpg.components.get(si.compIdx);
                    int[] dcDep = dcDepth[si.dcTblIdx];
                    int[] dcCod = dcCode[si.dcTblIdx];
                    int[] acDep = acDepth[si.acTblIdx];
                    int[] acCod = acCode[si.acTblIdx];
                    boolean wantDc = ss == 0;
                    boolean wantAc = ss != 0 || se != 0;
                    if (wantDc && mode != 2 && dcDep == null) {
                        throw new IOException("jbrd: DC table used before defined");
                    }
                    if (wantAc && acDep == null) {
                        throw new IOException("jbrd: AC table used before defined");
                    }
                    int blocksY = interleaved ? c.vSampFactor : 1;
                    int blocksX = interleaved ? c.hSampFactor : 1;
                    for (int iy = 0; iy < blocksY; iy++) {
                        for (int ix = 0; ix < blocksX; ix++) {
                            int blockY = mcuY * blocksY + iy;
                            int blockX = mcuX * blocksX + ix;
                            int blockIdx = blockY * c.widthInBlocks + blockX;
                            if (resetPointPos < scan.resetPoints.length
                                    && blockScanIndex == scan.resetPoints[resetPointPos]) {
                                flushEobRun();
                                resetPointPos++;
                            }
                            int numZeroRuns = 0;
                            if (zeroRunPos < scan.extraZeroRunBlock.length
                                    && blockScanIndex == scan.extraZeroRunBlock[zeroRunPos]) {
                                numZeroRuns = scan.extraZeroRunCount[zeroRunPos++];
                            }
                            int off = blockIdx << 6;
                            switch (mode) {
                                case 0 -> encodeBlockSequential(c.coeffs, off, dcDep, dcCod,
                                        acDep, acCod, numZeroRuns, lastDcCoeff, si.compIdx);
                                case 1 -> encodeBlockProgressive(c.coeffs, off, dcDep, dcCod,
                                        acDep, acCod, ss, se, al, numZeroRuns, lastDcCoeff,
                                        si.compIdx);
                                default -> encodeRefinement(c.coeffs, off, acDep, acCod,
                                        ss, se, al);
                            }
                            blockScanIndex++;
                        }
                    }
                }
                restartsToGo--;
            }
        }
        flushEobRun();
        jumpToByteBoundary();
        scanIndex++;
    }

    private void encodeBlockSequential(short[] coeffs, int off, int[] dcDep, int[] dcCod,
            int[] acDep, int[] acCod, int numZeroRuns, int[] lastDc, int compIdx) {
        int temp2 = coeffs[off];
        int temp = temp2 - lastDc[compIdx];
        lastDc[compIdx] = temp2;
        temp2 = temp >> 31;
        temp += temp2;
        temp2 ^= temp;

        int dcNbits = temp2 == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(temp2);
        writeBits(dcDep[dcNbits], dcCod[dcNbits]);
        if (dcNbits > 0) {
            writeBits(dcNbits, temp & ((1 << dcNbits) - 1));
        }
        int r = 0;
        for (int i = 1; i < 64; i++) {
            int t = coeffs[off + JpegData.NATURAL_ORDER[i]];
            if (t == 0) {
                r++;
                continue;
            }
            int t2 = t >> 31;
            t += t2;
            t2 ^= t;
            while (r > 15) {
                writeBits(acDep[0xF0], acCod[0xF0]);
                r -= 16;
            }
            int acNbits = 32 - Integer.numberOfLeadingZeros(t2 & 0xFFFF);
            int symbol = (r << 4) + acNbits;
            writeBits(acDep[symbol] + acNbits,
                    ((long) acCod[symbol] << acNbits) | (t & ((1 << acNbits) - 1)));
            r = 0;
        }
        for (int i = 0; i < numZeroRuns; i++) {
            writeBits(acDep[0xF0], acCod[0xF0]);
            r -= 16;
        }
        if (r > 0) {
            writeBits(acDep[0], acCod[0]);
        }
    }

    private void encodeBlockProgressive(short[] coeffs, int off, int[] dcDep, int[] dcCod,
            int[] acDep, int[] acCod, int ss, int se, int al, int numZeroRuns,
            int[] lastDc, int compIdx) throws IOException {
        boolean eobRunAllowed = ss > 0;
        if (ss == 0) {
            int temp2 = coeffs[off] >> al;
            int temp = temp2 - lastDc[compIdx];
            lastDc[compIdx] = temp2;
            temp2 = temp;
            if (temp < 0) {
                temp = -temp;
                temp2--;
            }
            int nbits = temp == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(temp);
            writeBits(dcDep[nbits], dcCod[nbits]);
            if (nbits > 0) {
                writeBits(nbits, temp2 & ((1 << nbits) - 1));
            }
            ss++;
        }
        if (ss > se) {
            return;
        }
        int r = 0;
        for (int k = ss; k <= se; k++) {
            int temp = coeffs[off + JpegData.NATURAL_ORDER[k]];
            int temp2;
            if (temp == 0) {
                r++;
                continue;
            }
            if (temp < 0) {
                temp = -temp;
                temp >>= al;
                temp2 = ~temp;
            } else {
                temp >>= al;
                temp2 = temp;
            }
            if (temp == 0) {
                r++;
                continue;
            }
            flushEobRun();
            while (r > 15) {
                writeBits(acDep[0xF0], acCod[0xF0]);
                r -= 16;
            }
            int nbits = 32 - Integer.numberOfLeadingZeros(temp);
            int symbol = (r << 4) + nbits;
            writeBits(acDep[symbol], acCod[symbol]);
            writeBits(nbits, temp2 & ((1 << nbits) - 1));
            r = 0;
        }
        if (numZeroRuns > 0) {
            flushEobRun();
            for (int i = 0; i < numZeroRuns; i++) {
                writeBits(acDep[0xF0], acCod[0xF0]);
                r -= 16;
            }
        }
        if (r > 0) {
            bufferEndOfBand(acDep, acCod, null, 0);
            if (!eobRunAllowed) {
                flushEobRun();
            }
        }
    }

    private void encodeRefinement(short[] coeffs, int off, int[] acDep, int[] acCod,
            int ss, int se, int al) {
        boolean eobRunAllowed = ss > 0;
        if (ss == 0) {
            writeBits(1, (coeffs[off] >> al) & 1);
            ss++;
        }
        if (ss > se) {
            return;
        }
        int[] absValues = new int[64];
        int eob = 0;
        for (int k = ss; k <= se; k++) {
            int av = Math.abs(coeffs[off + JpegData.NATURAL_ORDER[k]]);
            absValues[k] = av >> al;
            if (absValues[k] == 1) {
                eob = k;
            }
        }
        int r = 0;
        int[] refBits = new int[64];
        int refCount = 0;
        for (int k = ss; k <= se; k++) {
            if (absValues[k] == 0) {
                r++;
                continue;
            }
            while (r > 15 && k <= eob) {
                flushEobRun();
                writeBits(acDep[0xF0], acCod[0xF0]);
                r -= 16;
                for (int i = 0; i < refCount; i++) {
                    writeBits(1, refBits[i]);
                }
                refCount = 0;
            }
            if (absValues[k] > 1) {
                refBits[refCount++] = absValues[k] & 1;
                continue;
            }
            flushEobRun();
            int symbol = (r << 4) + 1;
            int newNonZeroBit = coeffs[off + JpegData.NATURAL_ORDER[k]] < 0 ? 0 : 1;
            writeBits(acDep[symbol], acCod[symbol]);
            writeBits(1, newNonZeroBit);
            for (int i = 0; i < refCount; i++) {
                writeBits(1, refBits[i]);
            }
            refCount = 0;
            r = 0;
        }
        if (r > 0 || refCount > 0) {
            bufferEndOfBand(acDep, acCod, refBits, refCount);
            if (!eobRunAllowed) {
                flushEobRun();
            }
        }
    }
}
