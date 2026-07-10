package com.ebremer.jpegxl.jpeg;

import com.ebremer.jpegxl.brotli.Brotli;
import com.ebremer.jpegxl.io.Bits;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The non-pixel contents of a JPEG file as carried in a {@code jbrd} box
 * (JPEG bitstream reconstruction data): marker order, tables, scan scripts
 * and the byte blobs needed to rebuild the original file bit-exactly.
 * Mirrors libjxl's {@code JPEGData} bundle.
 */
public final class JpegData {

    public static final int[] NATURAL_ORDER = {
        0, 1, 8, 16, 9, 2, 3, 10,
        17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34,
        27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36,
        29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46,
        53, 60, 61, 54, 47, 55, 62, 63,
    };

    public static final int APP_UNKNOWN = 0;
    public static final int APP_ICC = 1;
    public static final int APP_EXIF = 2;
    public static final int APP_XMP = 3;

    public static final class QuantTable {
        public final int[] values = new int[64]; // natural order
        public int precision;
        public int index;
        public boolean isLast = true;
    }

    public static final class HuffmanCode {
        public final int[] counts = new int[17];
        public final int[] values = new int[257];
        public int slotId;
        public boolean isLast = true;
    }

    public static final class ScanComponent {
        public int compIdx;
        public int dcTblIdx;
        public int acTblIdx;
    }

    public static final class ScanInfo {
        public int ss;
        public int se;
        public int ah;
        public int al;
        public int numComponents;
        public final ScanComponent[] components = {
            new ScanComponent(), new ScanComponent(), new ScanComponent(), new ScanComponent()};
        public int[] resetPoints = new int[0];
        public int[] extraZeroRunBlock = new int[0];
        public int[] extraZeroRunCount = new int[0];
    }

    public static final class Component {
        public int id;
        public int hSampFactor = 1;
        public int vSampFactor = 1;
        public int quantIdx;
        public int widthInBlocks;
        public int heightInBlocks;
        public short[] coeffs; // 64 per block, natural order, block-raster
    }

    public int width;
    public int height;
    public int restartInterval;
    public final List<byte[]> appData = new ArrayList<>();
    public final List<Integer> appMarkerType = new ArrayList<>();
    public final List<byte[]> comData = new ArrayList<>();
    public final List<QuantTable> quant = new ArrayList<>();
    public final List<HuffmanCode> huffmanCodes = new ArrayList<>();
    public final List<Component> components = new ArrayList<>();
    public final List<ScanInfo> scanInfo = new ArrayList<>();
    public byte[] markerOrder = new byte[0];
    public final List<byte[]> interMarkerData = new ArrayList<>();
    public byte[] tailData = new byte[0];
    public boolean hasZeroPaddingBit;
    public byte[] paddingBits = new byte[0];

    /**
     * Parses a {@code jbrd} box: the JPEGData bundle followed by a brotli
     * stream holding the marker payloads. ICC / Exif / XMP marker payloads
     * are reconstructed from the given side data (any may be null when the
     * file has no such markers).
     */
    public static JpegData parse(byte[] jbrd, byte[] iccProfile, byte[] exif, byte[] xmp)
            throws IOException {
        JpegData j = new JpegData();
        Bits in = new Bits(jbrd);
        boolean hasDri = j.parseBundle(in);
        in.zeroPadToByte();

        // remaining bytes hold the brotli-compressed blobs
        int consumed = in.alignedBytePosition();
        byte[] blobs = Brotli.decode(jbrd, consumed, jbrd.length - consumed, 1 << 28);
        j.fillBlobs(blobs, iccProfile, exif, xmp);
        if (!hasDri) {
            j.restartInterval = 0;
        }
        return j;
    }

    /** Reads the bundle fields; returns whether a DRI marker is present. */
    private boolean parseBundle(Bits in) throws IOException {
        boolean isGray = in.bool();
        int numComponents = isGray ? 1 : 3;

        int numApp = 0;
        int numCom = 0;
        int numScans = 0;
        int numIntermarker = 0;
        boolean hasDri = false;
        List<Byte> markers = new ArrayList<>();
        while (true) {
            int marker = 0xc0 + in.u(6);
            markers.add((byte) marker);
            if (markers.size() > 16384) {
                throw new IOException("jbrd: too many markers");
            }
            if ((marker & 0xf0) == 0xe0) {
                numApp++;
            } else if (marker == 0xfe) {
                numCom++;
            } else if (marker == 0xda) {
                numScans++;
            } else if (marker == 0xff) {
                numIntermarker++;
            } else if (marker == 0xdd) {
                hasDri = true;
            }
            if (marker == 0xd9) {
                break;
            }
        }
        markerOrder = new byte[markers.size()];
        for (int i = 0; i < markers.size(); i++) {
            markerOrder[i] = markers.get(i);
        }
        if (numScans == 0) {
            throw new IOException("jbrd: no scans");
        }

        for (int i = 0; i < numApp; i++) {
            int type = in.u32(0, 0, 1, 0, 2, 1, 4, 2);
            if (type > APP_XMP) {
                throw new IOException("jbrd: unknown app marker type " + type);
            }
            appMarkerType.add(type);
            int len = in.u(16);
            byte[] app = new byte[len + 1];
            if (app.length < 3) {
                throw new IOException("jbrd: invalid app marker size");
            }
            appData.add(app);
        }
        for (int i = 0; i < numCom; i++) {
            int len = in.u(16);
            byte[] com = new byte[len + 1];
            if (com.length < 3) {
                throw new IOException("jbrd: invalid com marker size");
            }
            comData.add(com);
        }

        int numQuant = in.u32(1, 0, 2, 0, 3, 0, 4, 0);
        if (numQuant == 4) {
            throw new IOException("jbrd: invalid number of quant tables");
        }
        for (int i = 0; i < numQuant; i++) {
            QuantTable q = new QuantTable();
            q.precision = in.u(1);
            q.index = in.u(2);
            q.isLast = in.bool();
            quant.add(q);
        }

        int componentType = in.u(2);
        if (componentType == 0) { // gray
            numComponents = 1;
        } else if (componentType != 3) {
            numComponents = 3;
        } else {
            numComponents = in.u32(1, 0, 2, 0, 3, 0, 4, 0);
            if (numComponents != 1 && numComponents != 3) {
                throw new IOException("jbrd: invalid number of components");
            }
        }
        for (int i = 0; i < numComponents; i++) {
            components.add(new Component());
        }
        switch (componentType) {
            case 0 -> components.get(0).id = 1;
            case 1 -> {
                components.get(0).id = 1;
                components.get(1).id = 2;
                components.get(2).id = 3;
            }
            case 2 -> {
                components.get(0).id = 'R';
                components.get(1).id = 'G';
                components.get(2).id = 'B';
            }
            default -> {
                for (Component c : components) {
                    c.id = in.u(8);
                }
            }
        }
        for (Component c : components) {
            c.quantIdx = in.u(2);
            if (c.quantIdx >= quant.size()) {
                throw new IOException("jbrd: invalid quant table index");
            }
        }

        int numHuff = in.u32(4, 0, 2, 3, 10, 4, 26, 6);
        for (int i = 0; i < numHuff; i++) {
            HuffmanCode hc = new HuffmanCode();
            boolean isAc = in.bool();
            int id = in.u(2);
            hc.slotId = (isAc ? 0x10 : 0) | id;
            hc.isLast = in.bool();
            int numSymbols = 0;
            for (int k = 0; k <= 16; k++) {
                hc.counts[k] = in.u32(0, 0, 1, 0, 2, 3, 0, 8);
                numSymbols += hc.counts[k];
            }
            if (numSymbols == 0) {
                huffmanCodes.add(hc); // empty DHT marker
                continue;
            }
            if (numSymbols > 257) {
                throw new IOException("jbrd: huffman code too large");
            }
            for (int k = 0; k < numSymbols; k++) {
                hc.values[k] = in.u32(0, 2, 4, 2, 8, 4, 1, 8);
            }
            if (hc.values[numSymbols - 1] != 256) {
                throw new IOException("jbrd: missing huffman sentinel");
            }
            huffmanCodes.add(hc);
        }

        for (int i = 0; i < numScans; i++) {
            ScanInfo si = new ScanInfo();
            si.numComponents = in.u32(1, 0, 2, 0, 3, 0, 4, 0);
            if (si.numComponents >= 4) {
                throw new IOException("jbrd: invalid scan component count");
            }
            si.ss = in.u(6);
            si.se = in.u(6);
            si.al = in.u(4);
            si.ah = in.u(4);
            for (int k = 0; k < si.numComponents; k++) {
                si.components[k].compIdx = in.u(2);
                if (si.components[k].compIdx >= components.size()) {
                    throw new IOException("jbrd: invalid scan component index");
                }
                si.components[k].acTblIdx = in.u(2);
                si.components[k].dcTblIdx = in.u(2);
            }
            in.u32(0, 0, 1, 0, 2, 0, 3, 3); // last_needed_pass (unused here)
            scanInfo.add(si);
        }

        if (hasDri) {
            restartInterval = in.u(16);
        }

        for (ScanInfo si : scanInfo) {
            int numReset = in.u32(0, 0, 1, 2, 4, 4, 20, 16);
            si.resetPoints = new int[numReset];
            int last = -1;
            for (int k = 0; k < numReset; k++) {
                int delta = in.u32(0, 0, 1, 3, 9, 5, 41, 28);
                int block = delta + last + 1;
                if (block >= (3 << 26)) {
                    throw new IOException("jbrd: invalid reset point");
                }
                si.resetPoints[k] = block;
                last = block;
            }
            int numZeroRuns = in.u32(0, 0, 1, 2, 4, 4, 20, 16);
            si.extraZeroRunBlock = new int[numZeroRuns];
            si.extraZeroRunCount = new int[numZeroRuns];
            last = -1;
            for (int k = 0; k < numZeroRuns; k++) {
                si.extraZeroRunCount[k] = in.u32(1, 0, 2, 2, 5, 4, 20, 8);
                int delta = in.u32(0, 0, 1, 3, 9, 5, 41, 28);
                int block = delta + last + 1;
                if (si.extraZeroRunCount[k] > 4 || block > (3 << 26)) {
                    throw new IOException("jbrd: invalid extra zero run");
                }
                si.extraZeroRunBlock[k] = block;
                last = block;
            }
        }

        int[] interSizes = new int[numIntermarker];
        for (int i = 0; i < numIntermarker; i++) {
            interSizes[i] = in.u(16);
        }
        int tailLen = in.u32(0, 0, 1, 8, 257, 16, 65793, 22);

        hasZeroPaddingBit = in.bool();
        if (hasZeroPaddingBit) {
            int nbit = in.u(24);
            paddingBits = new byte[nbit];
            for (int i = 0; i < nbit; i++) {
                paddingBits[i] = (byte) (in.bool() ? 1 : 0);
            }
        }

        for (int size : interSizes) {
            interMarkerData.add(new byte[size]);
        }
        tailData = new byte[tailLen];
        return hasDri;
    }

    private static final byte[] ICC_TAG = {'I', 'C', 'C', '_', 'P', 'R', 'O', 'F', 'I', 'L', 'E', 0};
    private static final byte[] EXIF_TAG = {'E', 'x', 'i', 'f', 0, 0};
    private static final byte[] XMP_TAG = "http://ns.adobe.com/xap/1.0/\0".getBytes(
            java.nio.charset.StandardCharsets.ISO_8859_1);

    /** Distributes the decompressed blob bytes and side data into the markers. */
    private void fillBlobs(byte[] blobs, byte[] iccProfile, byte[] exif, byte[] xmp)
            throws IOException {
        int pos = 0;
        int numIcc = 0;
        int iccPos = 0;
        for (int i = 0; i < appData.size(); i++) {
            byte[] marker = appData.get(i);
            int type = appMarkerType.get(i);
            if (type != APP_UNKNOWN) {
                int sizeMinus1 = marker.length - 1;
                marker[1] = (byte) (sizeMinus1 >> 8);
                marker[2] = (byte) sizeMinus1;
                switch (type) {
                    case APP_ICC -> {
                        if (marker.length < 17) {
                            throw new IOException("jbrd: ICC marker too small");
                        }
                        marker[0] = (byte) 0xE2;
                        System.arraycopy(ICC_TAG, 0, marker, 3, ICC_TAG.length);
                        marker[15] = (byte) ++numIcc;
                        int len = marker.length - 17;
                        if (iccProfile == null || iccPos + len > iccProfile.length) {
                            throw new IOException("jbrd: ICC profile shorter than markers");
                        }
                        System.arraycopy(iccProfile, iccPos, marker, 17, len);
                        iccPos += len;
                    }
                    case APP_EXIF -> {
                        if (marker.length < 3 + EXIF_TAG.length) {
                            throw new IOException("jbrd: Exif marker too small");
                        }
                        marker[0] = (byte) 0xE1;
                        System.arraycopy(EXIF_TAG, 0, marker, 3, EXIF_TAG.length);
                        int len = marker.length - 3 - EXIF_TAG.length;
                        // the Exif box payload starts with a 4-byte offset field
                        if (exif == null || exif.length < 4 + len) {
                            throw new IOException("jbrd: missing or short Exif box");
                        }
                        System.arraycopy(exif, 4, marker, 3 + EXIF_TAG.length, len);
                    }
                    case APP_XMP -> {
                        if (marker.length < 3 + XMP_TAG.length) {
                            throw new IOException("jbrd: XMP marker too small");
                        }
                        marker[0] = (byte) 0xE1;
                        System.arraycopy(XMP_TAG, 0, marker, 3, XMP_TAG.length);
                        int len = marker.length - 3 - XMP_TAG.length;
                        if (xmp == null || xmp.length < len) {
                            throw new IOException("jbrd: missing or short XMP box");
                        }
                        System.arraycopy(xmp, 0, marker, 3 + XMP_TAG.length, len);
                    }
                    default -> throw new IOException("jbrd: bad app marker type");
                }
            } else {
                pos = readBlob(blobs, pos, marker);
                if (((marker[1] & 0xff) << 8 | (marker[2] & 0xff)) + 1 != marker.length) {
                    throw new IOException("jbrd: app marker size mismatch");
                }
            }
        }
        for (int i = 0; i < appData.size(); i++) {
            if (appMarkerType.get(i) == APP_ICC) {
                appData.get(i)[16] = (byte) numIcc;
            }
        }
        for (byte[] marker : comData) {
            pos = readBlob(blobs, pos, marker);
            if (((marker[1] & 0xff) << 8 | (marker[2] & 0xff)) + 1 != marker.length) {
                throw new IOException("jbrd: com marker size mismatch");
            }
        }
        for (byte[] data : interMarkerData) {
            pos = readBlob(blobs, pos, data);
        }
        pos = readBlob(blobs, pos, tailData);
        if (pos != blobs.length) {
            throw new IOException("jbrd: excess blob data");
        }
    }

    private static int readBlob(byte[] src, int pos, byte[] dst) throws IOException {
        if (pos + dst.length > src.length) {
            throw new IOException("jbrd: not enough blob data");
        }
        System.arraycopy(src, pos, dst, 0, dst.length);
        return pos + dst.length;
    }
}
