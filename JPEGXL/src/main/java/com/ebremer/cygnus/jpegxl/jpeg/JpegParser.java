package com.ebremer.cygnus.jpegxl.jpeg;

import java.io.IOException;

/**
 * Parses a JPEG file into a {@link JpegData} bundle: marker order, tables,
 * scan scripts, padding bits and the fully decoded quantised DCT
 * coefficients — everything {@link JpegWriter} needs to reproduce the file
 * byte for byte. Baseline and progressive Huffman JPEGs with sampling
 * factors 1 and 2 are supported; arithmetic, lossless, hierarchical and
 * 12-bit files are rejected.
 */
public final class JpegParser {

    private final byte[] in;
    private int pos;
    private final JpegData jpg = new JpegData();
    private final java.util.List<Byte> markerOrder = new java.util.ArrayList<>();
    private final java.util.List<Integer> paddingBits = new java.util.ArrayList<>();
    private boolean anyZeroPad;
    private boolean isProgressive;
    private boolean seenDri;
    private boolean seenSof;

    // Huffman decode tables per slot: value/length by 16-bit peek
    private final int[][] dcLookup = new int[4][];
    private final int[][] acLookup = new int[4][];

    private JpegParser(byte[] jpeg) {
        this.in = jpeg;
    }

    /** Parses {@code jpeg}; the returned bundle has all coefficients filled. */
    public static JpegData parse(byte[] jpeg) throws IOException {
        return new JpegParser(jpeg).run();
    }

    private JpegData run() throws IOException {
        if (in.length < 4 || (in[0] & 0xff) != 0xFF || (in[1] & 0xff) != 0xD8) {
            throw new IOException("not a JPEG file (missing SOI)");
        }
        pos = 2;
        while (true) {
            if (pos + 2 > in.length) {
                throw new IOException("truncated JPEG (missing EOI)");
            }
            if ((in[pos] & 0xff) != 0xFF) {
                throw new IOException("garbage between JPEG markers is not supported");
            }
            int marker = in[pos + 1] & 0xff;
            if (marker == 0xFF) {
                throw new IOException("fill bytes between JPEG markers are not supported");
            }
            pos += 2;
            markerOrder.add((byte) marker);
            switch (marker) {
                case 0xC0, 0xC1, 0xC2 -> readSof(marker);
                case 0xC4 -> readDht();
                case 0xDB -> readDqt();
                case 0xDA -> readScan();
                case 0xDD -> readDri();
                case 0xD9 -> {
                    jpg.tailData = java.util.Arrays.copyOfRange(in, pos, in.length);
                    finish();
                    return jpg;
                }
                case 0xE0, 0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7,
                     0xE8, 0xE9, 0xEA, 0xEB, 0xEC, 0xED, 0xEE, 0xEF -> readApp(marker);
                case 0xFE -> readCom();
                case 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF ->
                        throw new IOException(String.format(
                                "unsupported JPEG type (SOF marker 0x%02X): only baseline"
                                + " and progressive Huffman coding are supported", marker));
                case 0xCC -> throw new IOException("arithmetic coding is not supported");
                case 0xDC -> throw new IOException("DNL markers are not supported");
                default -> throw new IOException(
                        String.format("unsupported JPEG marker 0x%02X", marker));
            }
        }
    }

    private void finish() throws IOException {
        if (jpg.scanInfo.isEmpty()) {
            throw new IOException("JPEG has no scans");
        }
        jpg.markerOrder = new byte[markerOrder.size()];
        for (int i = 0; i < markerOrder.size(); i++) {
            jpg.markerOrder[i] = markerOrder.get(i);
        }
        if (anyZeroPad) {
            jpg.hasZeroPaddingBit = true;
            jpg.paddingBits = new byte[paddingBits.size()];
            for (int i = 0; i < paddingBits.size(); i++) {
                jpg.paddingBits[i] = (byte) (int) paddingBits.get(i);
            }
        }
    }

    // ------------------------------------------------------------- segments

    private int segmentLength() throws IOException {
        if (pos + 2 > in.length) {
            throw new IOException("truncated JPEG segment");
        }
        int len = ((in[pos] & 0xff) << 8) | (in[pos + 1] & 0xff);
        if (len < 2 || pos + len > in.length) {
            throw new IOException("bad JPEG segment length");
        }
        return len;
    }

    private void readApp(int marker) throws IOException {
        int len = segmentLength();
        // stored as the marker byte plus the whole segment; every APP marker
        // is carried verbatim (type "unknown"), so ICC/Exif/XMP round-trip
        // byte-exactly without a codestream copy
        byte[] data = new byte[1 + len];
        data[0] = (byte) marker;
        System.arraycopy(in, pos, data, 1, len);
        jpg.appData.add(data);
        jpg.appMarkerType.add(JpegData.APP_UNKNOWN);
        pos += len;
    }

    private void readCom() throws IOException {
        int len = segmentLength();
        byte[] data = new byte[1 + len];
        data[0] = (byte) 0xFE;
        System.arraycopy(in, pos, data, 1, len);
        jpg.comData.add(data);
        pos += len;
    }

    private void readDri() throws IOException {
        int len = segmentLength();
        if (len != 4) {
            throw new IOException("bad DRI segment");
        }
        int interval = ((in[pos + 2] & 0xff) << 8) | (in[pos + 3] & 0xff);
        if (seenDri && interval != jpg.restartInterval) {
            throw new IOException("multiple DRI markers with different intervals");
        }
        jpg.restartInterval = interval;
        seenDri = true;
        pos += len;
    }

    private void readSof(int marker) throws IOException {
        if (seenSof) {
            throw new IOException("multiple SOF markers");
        }
        seenSof = true;
        isProgressive = marker == 0xC2;
        int len = segmentLength();
        int p = pos + 2;
        int precision = in[p] & 0xff;
        if (precision != 8) {
            throw new IOException("only 8-bit JPEGs are supported");
        }
        jpg.height = ((in[p + 1] & 0xff) << 8) | (in[p + 2] & 0xff);
        jpg.width = ((in[p + 3] & 0xff) << 8) | (in[p + 4] & 0xff);
        if (jpg.width == 0 || jpg.height == 0) {
            throw new IOException("bad JPEG dimensions");
        }
        int n = in[p + 5] & 0xff;
        if (n != 1 && n != 3) {
            throw new IOException(n + "-component JPEGs are not supported");
        }
        if (len != 8 + 3 * n) {
            throw new IOException("bad SOF segment length");
        }
        p += 6;
        for (int i = 0; i < n; i++) {
            JpegData.Component c = new JpegData.Component();
            c.id = in[p] & 0xff;
            c.hSampFactor = (in[p + 1] & 0xff) >> 4;
            c.vSampFactor = in[p + 1] & 0x0f;
            c.quantIdx = in[p + 2] & 0xff;
            if (c.hSampFactor < 1 || c.hSampFactor > 2
                    || c.vSampFactor < 1 || c.vSampFactor > 2) {
                throw new IOException("sampling factors beyond 2 are not supported");
            }
            if (c.quantIdx > 3) {
                throw new IOException("bad quant table selector");
            }
            jpg.components.add(c);
            p += 3;
        }
        int maxH = 1;
        int maxV = 1;
        for (JpegData.Component c : jpg.components) {
            maxH = Math.max(maxH, c.hSampFactor);
            maxV = Math.max(maxV, c.vSampFactor);
        }
        int mcusAcross = ceilDiv(jpg.width, 8 * maxH);
        int mcusDown = ceilDiv(jpg.height, 8 * maxV);
        for (JpegData.Component c : jpg.components) {
            c.widthInBlocks = mcusAcross * c.hSampFactor;
            c.heightInBlocks = mcusDown * c.vSampFactor;
            c.coeffs = new short[c.widthInBlocks * c.heightInBlocks * 64];
        }
        pos += len;
    }

    private void readDqt() throws IOException {
        int len = segmentLength();
        int end = pos + len;
        int p = pos + 2;
        JpegData.QuantTable last = null;
        while (p < end) {
            JpegData.QuantTable t = new JpegData.QuantTable();
            t.precision = (in[p] & 0xff) >> 4;
            t.index = in[p] & 0x0f;
            if (t.precision > 1 || t.index > 3) {
                throw new IOException("bad DQT table header");
            }
            p++;
            for (int i = 0; i < 64; i++) {
                int v;
                if (t.precision != 0) {
                    v = ((in[p] & 0xff) << 8) | (in[p + 1] & 0xff);
                    p += 2;
                } else {
                    v = in[p] & 0xff;
                    p++;
                }
                if (v == 0) {
                    throw new IOException("zero quantiser value");
                }
                t.values[JpegData.NATURAL_ORDER[i]] = v;
            }
            if (last != null) {
                last.isLast = false;
            }
            jpg.quant.add(t);
            last = t;
            if (p > end) {
                throw new IOException("truncated DQT segment");
            }
        }
        pos += len;
    }

    private void readDht() throws IOException {
        int len = segmentLength();
        int end = pos + len;
        int p = pos + 2;
        if (p == end) {
            // an empty DHT marker is representable and round-trips
            jpg.huffmanCodes.add(new JpegData.HuffmanCode());
            pos += len;
            return;
        }
        JpegData.HuffmanCode last = null;
        while (p < end) {
            JpegData.HuffmanCode hc = new JpegData.HuffmanCode();
            hc.slotId = in[p] & 0xff;
            boolean isAc = (hc.slotId & 0x10) != 0;
            int id = hc.slotId & 0x0f;
            if ((hc.slotId & ~0x13) != 0 || id > 3) {
                throw new IOException("bad DHT slot " + hc.slotId);
            }
            p++;
            int total = 0;
            int maxLength = 0;
            for (int l = 1; l <= 16; l++) {
                hc.counts[l] = in[p++] & 0xff;
                total += hc.counts[l];
                if (hc.counts[l] != 0) {
                    maxLength = l;
                }
            }
            if (total == 0 || total > 256 || p + total > end) {
                throw new IOException("bad DHT counts");
            }
            for (int i = 0; i < total; i++) {
                hc.values[i] = in[p++] & 0xff;
            }
            // the bundle stores the code with the libjxl sentinel appended at
            // the longest length
            hc.counts[maxLength]++;
            hc.values[total] = 256;
            buildLookup(hc, isAc, id);
            if (last != null) {
                last.isLast = false;
            }
            jpg.huffmanCodes.add(hc);
            last = hc;
        }
        if (p != end) {
            throw new IOException("truncated DHT segment");
        }
        pos += len;
    }

    /** 16-bit-peek decode table: entry = (symbol << 8) | codeLength. */
    private void buildLookup(JpegData.HuffmanCode hc, boolean isAc, int id) throws IOException {
        int[] lookup = new int[1 << 16];
        int code = 0;
        int k = 0;
        for (int l = 1; l <= 16; l++) {
            for (int i = 0; i < hc.counts[l]; i++) {
                int symbol = hc.values[k++];
                if (symbol == 256) {
                    continue; // the sentinel never gets a code (it is last)
                }
                if (code >= (1 << l)) {
                    throw new IOException("overfull Huffman code");
                }
                int base = code << (16 - l);
                for (int fill = 0; fill < (1 << (16 - l)); fill++) {
                    lookup[base + fill] = (symbol << 8) | l;
                }
                code++;
            }
            code <<= 1;
        }
        if (isAc) {
            acLookup[id] = lookup;
        } else {
            dcLookup[id] = lookup;
        }
    }

    // ------------------------------------------------------------ scan decode

    // entropy-coded bit reader (MSB first, 0xFF00 unstuffing)
    private long bitBuffer;
    private int bitCount;

    private void fillBits() throws IOException {
        while (bitCount <= 56) {
            if (pos >= in.length) {
                throw new IOException("truncated entropy-coded data");
            }
            int b = in[pos] & 0xff;
            if (b == 0xFF) {
                int next = pos + 1 < in.length ? in[pos + 1] & 0xff : -1;
                if (next != 0) {
                    // a marker: feed ones so any dangling reads fail loudly
                    // via the padding check instead of running into it
                    break;
                }
                pos += 2;
            } else {
                pos++;
            }
            bitBuffer |= (long) b << (56 - bitCount);
            bitCount += 8;
        }
    }

    private int readBit() throws IOException {
        if (bitCount == 0) {
            fillBits();
            if (bitCount == 0) {
                throw new IOException("entropy data ends inside a scan");
            }
        }
        int bit = (int) (bitBuffer >>> 63);
        bitBuffer <<= 1;
        bitCount--;
        return bit;
    }

    private int readBits(int n) throws IOException {
        int v = 0;
        for (int i = 0; i < n; i++) {
            v = (v << 1) | readBit();
        }
        return v;
    }

    private int readSymbol(int[] lookup) throws IOException {
        if (lookup == null) {
            throw new IOException("scan uses an undefined Huffman table");
        }
        if (bitCount < 16) {
            fillBits();
        }
        int peek;
        if (bitCount >= 16) {
            peek = (int) (bitBuffer >>> 48);
        } else {
            // near the scan end: pad the peek with ones (never a valid long code)
            peek = (int) ((bitBuffer >>> 48) | ((1 << (16 - bitCount)) - 1));
        }
        int entry = lookup[peek];
        int length = entry & 0xff;
        if (length == 0 || length > bitCount) {
            throw new IOException("bad Huffman code in scan");
        }
        bitBuffer <<= length;
        bitCount -= length;
        return entry >> 8;
    }

    /** JPEG "extend": {@code v} in {@code n} bits to its signed value. */
    private static int extend(int v, int n) {
        return n == 0 ? 0 : (v < (1 << (n - 1)) ? v - (1 << n) + 1 : v);
    }

    /** Byte-aligns at a scan boundary, recording the discarded padding bits. */
    private void capturePadding() throws IOException {
        int n = bitCount & 7;
        for (int i = 0; i < n; i++) {
            int bit = readBit();
            paddingBits.add(bit);
            if (bit == 0) {
                anyZeroPad = true;
            }
        }
        if (bitCount != 0) {
            throw new IOException("scan bit reader out of sync");
        }
    }

    private void readScan() throws IOException {
        int len = segmentLength();
        int p = pos + 2;
        JpegData.ScanInfo scan = new JpegData.ScanInfo();
        int n = in[p] & 0xff;
        if (n < 1 || n > 4 || len != 6 + 2 * n) {
            throw new IOException("bad SOS segment");
        }
        if (n >= 4) {
            throw new IOException("scans with 4 components are not supported");
        }
        scan.numComponents = n;
        p++;
        for (int i = 0; i < n; i++) {
            int id = in[p] & 0xff;
            int compIdx = -1;
            for (int k = 0; k < jpg.components.size(); k++) {
                if (jpg.components.get(k).id == id) {
                    compIdx = k;
                }
            }
            if (compIdx < 0) {
                throw new IOException("scan references an unknown component");
            }
            scan.components[i].compIdx = compIdx;
            scan.components[i].dcTblIdx = (in[p + 1] & 0xff) >> 4;
            scan.components[i].acTblIdx = in[p + 1] & 0x0f;
            if (scan.components[i].dcTblIdx > 3 || scan.components[i].acTblIdx > 3) {
                throw new IOException("bad Huffman table selector");
            }
            p += 2;
        }
        scan.ss = in[p] & 0xff;
        scan.se = in[p + 1] & 0xff;
        scan.ah = (in[p + 2] & 0xff) >> 4;
        scan.al = in[p + 2] & 0x0f;
        pos += len;
        jpg.scanInfo.add(scan);

        decodeScanData(scan);
    }

    private void decodeScanData(JpegData.ScanInfo scan) throws IOException {
        int n = scan.numComponents;
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
        if (isProgressive && ss > 0 && n > 1) {
            throw new IOException("progressive AC scans must be non-interleaved");
        }

        if (isProgressive) {
            if (scan.ss == 0 && scan.se != 0) {
                throw new IOException("progressive DC scans must have Se = 0");
            }
            if (scan.ss > scan.se || scan.se > 63) {
                throw new IOException("bad spectral selection");
            }
        }
        int restartInterval = seenDri ? jpg.restartInterval : 0;
        int[] lastDc = new int[4];
        bitBuffer = 0;
        bitCount = 0;
        eobRun = 0;
        justEndedRun = 0;
        java.util.List<Integer> resetPoints = new java.util.ArrayList<>();
        java.util.List<Integer> zeroRunBlocks = new java.util.ArrayList<>();
        java.util.List<Integer> zeroRunCounts = new java.util.ArrayList<>();

        int restartsToGo = restartInterval;
        int nextRestartMarker = 0;
        int blockScanIndex = 0;

        for (int mcuY = 0; mcuY < mcuRows; mcuY++) {
            for (int mcuX = 0; mcuX < mcusPerRow; mcuX++) {
                if (restartInterval > 0 && restartsToGo == 0) {
                    if (eobRun > 0) {
                        throw new IOException("EOB run extends across a restart");
                    }
                    justEndedRun = 0; // a restart flushes implicitly
                    capturePadding();
                    if (pos + 2 > in.length || (in[pos] & 0xff) != 0xFF
                            || (in[pos + 1] & 0xff) != 0xD0 + nextRestartMarker) {
                        throw new IOException("missing restart marker RST" + nextRestartMarker);
                    }
                    pos += 2;
                    nextRestartMarker = (nextRestartMarker + 1) & 7;
                    restartsToGo = restartInterval;
                    java.util.Arrays.fill(lastDc, 0);
                }
                for (int i = 0; i < n; i++) {
                    JpegData.ScanComponent si = scan.components[i];
                    JpegData.Component c = jpg.components.get(si.compIdx);
                    int blocksY = interleaved ? c.vSampFactor : 1;
                    int blocksX = interleaved ? c.hSampFactor : 1;
                    for (int iy = 0; iy < blocksY; iy++) {
                        for (int ix = 0; ix < blocksX; ix++) {
                            int blockY = mcuY * blocksY + iy;
                            int blockX = mcuX * blocksX + ix;
                            int off = (blockY * c.widthInBlocks + blockX) << 6;
                            int zeroRuns;
                            try {
                                switch (mode) {
                                    case 0 -> zeroRuns = decodeBlockSequential(c.coeffs, off,
                                            dcLookup[si.dcTblIdx], acLookup[si.acTblIdx],
                                            lastDc, si.compIdx);
                                    case 1 -> zeroRuns = decodeBlockProgressive(c.coeffs, off,
                                            dcLookup[si.dcTblIdx], acLookup[si.acTblIdx],
                                            ss, se, al, lastDc, si.compIdx,
                                            blockScanIndex, resetPoints);
                                    default -> zeroRuns = decodeRefinement(c.coeffs, off,
                                            acLookup[si.acTblIdx], ss, se, al,
                                            blockScanIndex, resetPoints);
                                }
                            } catch (IOException e) {
                                throw new IOException("scan " + (jpg.scanInfo.size() - 1)
                                        + " (Ss=" + scan.ss + " Se=" + scan.se
                                        + " Ah=" + scan.ah + " Al=" + scan.al
                                        + ") block " + blockScanIndex + ": "
                                        + e.getMessage(), e);
                            }
                            if (zeroRuns > 0) {
                                zeroRunBlocks.add(blockScanIndex);
                                zeroRunCounts.add(zeroRuns);
                            }
                            blockScanIndex++;
                        }
                    }
                }
                restartsToGo--;
            }
        }
        if (eobRun > 0) {
            throw new IOException("EOB run extends past the scan");
        }
        capturePadding();

        scan.resetPoints = toArray(resetPoints);
        scan.extraZeroRunBlock = toArray(zeroRunBlocks);
        scan.extraZeroRunCount = toArray(zeroRunCounts);
    }

    private static int[] toArray(java.util.List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    // progressive end-of-band run state
    private int eobRun;            // blocks still covered by the current run
    private int currentRunLength;  // total length of the current run
    /**
     * Length of the run that ended at the previous block, or 0 when the
     * previous block was not a run end. A new EOB symbol while this is a
     * plain run length means the original encoder flushed early — a reset
     * point — unless that run hit the 0x7FFF cap, which flushes by itself.
     */
    private int justEndedRun;

    private void noteRunConsumed() {
        eobRun--;
        justEndedRun = eobRun == 0 ? currentRunLength : 0;
    }

    private void startEobRun(int r, int blockScanIndex, java.util.List<Integer> resetPoints)
            throws IOException {
        int run = 1 << r;
        if (r > 0) {
            run += readBits(r);
        }
        if (justEndedRun > 0 && justEndedRun != 0x7FFF) {
            resetPoints.add(blockScanIndex);
        }
        currentRunLength = run;
        eobRun = run;
    }

    /** Decodes one baseline block; returns the count of extra zero runs. */
    private int decodeBlockSequential(short[] coeffs, int off, int[] dcTbl, int[] acTbl,
            int[] lastDc, int compIdx) throws IOException {
        int s = readSymbol(dcTbl);
        int diff = extend(readBits(s), s);
        lastDc[compIdx] += diff;
        coeffs[off] = (short) lastDc[compIdx];

        int k = 1;
        int pendingZrl = 0;
        while (k <= 63) {
            int symbol = readSymbol(acTbl);
            int r = symbol >> 4;
            int size = symbol & 0x0f;
            if (size == 0) {
                if (r == 15) { // ZRL
                    pendingZrl++;
                    if (k + 16 * pendingZrl >= 64) {
                        // the runs skip past the block end, replacing the EOB
                        return pendingZrl;
                    }
                    continue;
                }
                if (r != 0) {
                    throw new IOException("bad AC symbol in sequential scan");
                }
                return pendingZrl; // EOB; any pending ZRLs were extra
            }
            k += 16 * pendingZrl + r;
            pendingZrl = 0;
            if (k > 63) {
                throw new IOException("AC coefficient index out of range");
            }
            coeffs[off + JpegData.NATURAL_ORDER[k]] = (short) extend(readBits(size), size);
            k++;
        }
        return 0;
    }

    /** Decodes one first-pass progressive block; returns extra zero runs. */
    private int decodeBlockProgressive(short[] coeffs, int off, int[] dcTbl, int[] acTbl,
            int ss, int se, int al, int[] lastDc, int compIdx, int blockScanIndex,
            java.util.List<Integer> resetPoints) throws IOException {
        if (ss == 0) {
            int s = readSymbol(dcTbl);
            int diff = extend(readBits(s), s);
            lastDc[compIdx] += diff;
            coeffs[off] = (short) (lastDc[compIdx] << al);
            return 0; // DC scans have neither runs nor extra ZRLs
        }
        if (eobRun > 0) {
            noteRunConsumed();
            return 0;
        }
        int k = ss;
        int pendingZrl = 0;
        boolean hadCoefficient = false;
        while (k <= se) {
            int symbol = readSymbol(acTbl);
            int r = symbol >> 4;
            int size = symbol & 0x0f;
            if (size == 0) {
                if (r == 15) {
                    pendingZrl++;
                    if (k + 16 * pendingZrl > se) {
                        justEndedRun = 0;
                        return pendingZrl;
                    }
                    continue;
                }
                // EOBn covers this block too
                if (hadCoefficient) {
                    justEndedRun = 0;
                }
                startEobRun(r, blockScanIndex, resetPoints);
                noteRunConsumed();
                return pendingZrl;
            }
            k += 16 * pendingZrl + r;
            pendingZrl = 0;
            if (k > se) {
                throw new IOException("AC coefficient index out of range");
            }
            int v = extend(readBits(size), size);
            coeffs[off + JpegData.NATURAL_ORDER[k]] = (short) (v << al);
            k++;
            hadCoefficient = true;
            justEndedRun = 0;
        }
        return 0;
    }

    /** Decodes one refinement block; refinement scans never have extra runs. */
    private int decodeRefinement(short[] coeffs, int off, int[] acTbl,
            int ss, int se, int al, int blockScanIndex,
            java.util.List<Integer> resetPoints) throws IOException {
        if (ss == 0) {
            int bit = readBit();
            if (bit != 0) {
                coeffs[off] |= (short) (1 << al);
            }
            return 0;
        }
        if (eobRun > 0) {
            // inside an EOB run: only correction bits for existing coefficients
            refineExisting(coeffs, off, ss, se, al);
            noteRunConsumed();
            return 0;
        }
        int k = ss;
        while (k <= se) {
            int symbol = readSymbol(acTbl);
            int r = symbol >> 4;
            int size = symbol & 0x0f;
            int newBit = 0;
            if (size == 0) {
                if (r != 15) { // EOBn
                    startEobRun(r, blockScanIndex, resetPoints);
                    refineExisting(coeffs, off, k, se, al);
                    noteRunConsumed();
                    return 0;
                }
                // ZRL: skip 16 zero-history positions, refining along the way
            } else {
                if (size != 1) {
                    throw new IOException("bad refinement symbol");
                }
                newBit = readBit() != 0 ? 1 : -1;
            }
            while (k <= se) {
                int idx = off + JpegData.NATURAL_ORDER[k];
                if (coeffs[idx] != 0) {
                    if (readBit() != 0 && (Math.abs(coeffs[idx]) & (1 << al)) == 0) {
                        coeffs[idx] += (short) (coeffs[idx] >= 0 ? (1 << al) : -(1 << al));
                    }
                } else {
                    if (r == 0) {
                        if (newBit != 0) {
                            coeffs[idx] = (short) (newBit > 0 ? (1 << al) : -(1 << al));
                        }
                        k++;
                        break;
                    }
                    r--;
                }
                k++;
            }
            justEndedRun = 0;
        }
        return 0;
    }

    /** Correction bits for already-nonzero coefficients in [k, se]. */
    private void refineExisting(short[] coeffs, int off, int k, int se, int al)
            throws IOException {
        for (; k <= se; k++) {
            int idx = off + JpegData.NATURAL_ORDER[k];
            if (coeffs[idx] != 0) {
                if (readBit() != 0 && (Math.abs(coeffs[idx]) & (1 << al)) == 0) {
                    coeffs[idx] += (short) (coeffs[idx] >= 0 ? (1 << al) : -(1 << al));
                }
            }
        }
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
