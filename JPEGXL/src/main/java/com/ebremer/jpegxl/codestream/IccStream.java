package com.ebremer.jpegxl.codestream;

import com.ebremer.jpegxl.entropy.EntropyDecoder;
import com.ebremer.jpegxl.io.Bits;
import java.io.IOException;

/**
 * The entropy-coded ICC profile stream (18181-1 annex B): entropy decoding of
 * the predicted byte stream, and reconstruction of the actual ICC profile
 * from it (header prediction, tag list expansion, and the data commands).
 */
public final class IccStream {

    private IccStream() {
    }

    private static final String[] TAG_LIST = {
        "cprt", "wtpt", "bkpt", "rXYZ", "gXYZ", "bXYZ",
        "kXYZ", "rTRC", "gTRC", "bTRC", "kTRC", "chad",
        "desc", "chrm", "dmnd", "dmdd", "lumi",
    };

    /** Rebuilds the ICC profile bytes from the entropy-decoded stream. */
    public static byte[] reconstruct(byte[] encoded) throws IOException {
        int[] cmdPos = {0};
        int outputSize = varint(encoded, cmdPos);
        int commandSize = varint(encoded, cmdPos);
        int dataStart = cmdPos[0] + commandSize;
        int commandEnd = dataStart;
        int[] dataPos = {dataStart};
        if (dataStart > encoded.length || outputSize < 0 || outputSize > (1 << 28)) {
            throw new IOException("bad ICC stream layout");
        }
        byte[] out = new byte[outputSize];
        int pos = 0;

        // header: 128 predicted bytes
        int headerSize = Math.min(128, outputSize);
        for (int i = 0; i < headerSize; i++) {
            int e = dataByte(encoded, dataPos);
            int p = headerPrediction(out, i, outputSize);
            out[pos++] = (byte) ((p + e) & 0xFF);
        }
        if (pos == outputSize) {
            return out;
        }

        // tag list
        int tagCount = varint(encoded, cmdPos) - 1;
        if (tagCount >= 0) {
            for (int i = 24; i >= 0; i -= 8) {
                out[pos++] = (byte) (tagCount >>> i);
            }
            int prevTagStart = 128 + tagCount * 12;
            int prevTagSize = 0;
            while (cmdPos[0] < commandEnd) {
                int command = encoded[cmdPos[0]++] & 0xFF;
                int tagCode = command & 0x3F;
                if (tagCode == 0) {
                    break;
                }
                String tag;
                if (tagCode == 1) {
                    byte[] t = new byte[4];
                    for (int i = 0; i < 4; i++) {
                        t[i] = (byte) dataByte(encoded, dataPos);
                    }
                    tag = new String(t, java.nio.charset.StandardCharsets.US_ASCII);
                } else if (tagCode == 2) {
                    tag = "rTRC";
                } else if (tagCode == 3) {
                    tag = "rXYZ";
                } else if (tagCode >= 4 && tagCode <= 21) {
                    tag = TAG_LIST[tagCode - 4];
                } else {
                    throw new IOException("bad ICC tag code " + tagCode);
                }
                int tagStart = (command & 0x40) != 0 ? varint(encoded, cmdPos)
                        : prevTagStart + prevTagSize;
                int tagSize = (command & 0x80) != 0 ? varint(encoded, cmdPos)
                        : switch (tag) {
                            case "rXYZ", "gXYZ", "bXYZ", "kXYZ", "wtpt", "bkpt", "lumi" -> 20;
                            default -> prevTagSize;
                        };
                prevTagStart = tagStart;
                prevTagSize = tagSize;
                String[] tags = tagCode == 2 ? new String[] {"rTRC", "gTRC", "bTRC"}
                        : tagCode == 3 ? new String[] {"rXYZ", "gXYZ", "bXYZ"}
                        : new String[] {tag};
                for (String w : tags) {
                    byte[] t = w.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    for (int i = 0; i < 4; i++) {
                        out[pos++] = t[i];
                    }
                    for (int i = 24; i >= 0; i -= 8) {
                        out[pos++] = (byte) (tagStart >>> i);
                    }
                    for (int i = 24; i >= 0; i -= 8) {
                        out[pos++] = (byte) (tagSize >>> i);
                    }
                    if (tagCode == 3) {
                        tagStart += tagSize;
                    }
                }
            }
        }

        // data commands
        while (cmdPos[0] < commandEnd) {
            int command = encoded[cmdPos[0]++] & 0xFF;
            if (command == 1) {
                int num = varint(encoded, cmdPos);
                for (int i = 0; i < num; i++) {
                    out[pos++] = (byte) dataByte(encoded, dataPos);
                }
            } else if (command == 2 || command == 3) {
                int num = varint(encoded, cmdPos);
                byte[] b = new byte[num];
                for (int i = 0; i < num; i++) {
                    b[i] = (byte) dataByte(encoded, dataPos);
                }
                b = shuffle(b, command == 2 ? 2 : 4);
                System.arraycopy(b, 0, out, pos, num);
                pos += num;
            } else if (command == 4) {
                int flags = encoded[cmdPos[0]++] & 0xFF;
                int width = (flags & 3) + 1;
                int order = (flags & 12) >>> 2;
                if (width == 3 || order == 3) {
                    throw new IOException("bad ICC predict command");
                }
                int stride = (flags & 0x10) != 0 ? varint(encoded, cmdPos) : width;
                if (stride * 4 >= pos || stride < width) {
                    throw new IOException("bad ICC predict stride");
                }
                int num = varint(encoded, cmdPos);
                byte[] b = new byte[num];
                for (int i = 0; i < num; i++) {
                    b[i] = (byte) dataByte(encoded, dataPos);
                }
                if (width == 2 || width == 4) {
                    b = shuffle(b, width);
                }
                for (int i = 0; i < num; i += width) {
                    int n = order + 1;
                    int[] prev = new int[n];
                    for (int j = 0; j < n; j++) {
                        for (int k = 0; k < width; k++) {
                            prev[j] <<= 8;
                            prev[j] |= out[pos - stride * (j + 1) + k] & 0xFF;
                        }
                    }
                    int p = order == 0 ? prev[0]
                            : order == 1 ? 2 * prev[0] - prev[1]
                            : 3 * prev[0] - 3 * prev[1] + prev[2];
                    for (int j = 0; j < width && i + j < num; j++) {
                        out[pos++] = (byte) ((b[i + j] + (p >>> (8 * (width - 1 - j)))) & 0xFF);
                    }
                }
            } else if (command == 10) {
                out[pos++] = 'X';
                out[pos++] = 'Y';
                out[pos++] = 'Z';
                out[pos++] = ' ';
                pos += 4;
                for (int i = 0; i < 12; i++) {
                    out[pos++] = (byte) dataByte(encoded, dataPos);
                }
            } else if (command >= 16 && command < 24) {
                String[] s = {"XYZ ", "desc", "text", "mluc", "para", "curv", "sf32", "gbd "};
                for (char ch : s[command - 16].toCharArray()) {
                    out[pos++] = (byte) ch;
                }
                pos += 4;
            } else {
                throw new IOException("bad ICC data command " + command);
            }
        }
        return out;
    }

    private static int headerPrediction(byte[] out, int i, int outputSize) {
        if (i <= 3) {
            return outputSize >>> (8 * (3 - i));
        }
        if (i == 8) {
            return 4;
        }
        if (i >= 12 && i <= 23) {
            return "mntrRGB XYZ ".charAt(i - 12);
        }
        if (i >= 36 && i <= 39) {
            return "acsp".charAt(i - 36);
        }
        if (i >= 41 && i <= 43) {
            int v = out[40] & 0xFF;
            if (v == 'A') {
                return "APPL".charAt(i - 40);
            }
            if (v == 'M') {
                return "MSFT".charAt(i - 40);
            }
            if (v == 'S') {
                int v2 = out[41] & 0xFF;
                if (i >= 42 && v2 == 'G') {
                    return "SGI ".charAt(i - 40);
                }
                if (i >= 42 && v2 == 'U') {
                    return "SUNW".charAt(i - 40);
                }
            }
            return 0;
        }
        return switch (i) {
            case 70 -> 246;
            case 71 -> 214;
            case 73 -> 1;
            case 78 -> 211;
            case 79 -> 45;
            default -> i >= 80 && i < 84 ? out[i - 76] & 0xFF : 0;
        };
    }

    private static byte[] shuffle(byte[] buffer, int width) {
        int height = (buffer.length + width - 1) / width;
        byte[] result = new byte[buffer.length];
        for (int i = 0; i < buffer.length; i++) {
            result[(i % height) * width + (i / height)] = buffer[i];
        }
        return result;
    }

    private static int varint(byte[] data, int[] pos) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 63; shift += 7) {
            if (pos[0] >= data.length) {
                throw new IOException("truncated ICC varint");
            }
            int b = data[pos[0]++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if (b <= 127) {
                break;
            }
        }
        if (value > Integer.MAX_VALUE) {
            throw new IOException("ICC varint overflow");
        }
        return (int) value;
    }

    private static int dataByte(byte[] data, int[] pos) throws IOException {
        if (pos[0] >= data.length) {
            throw new IOException("truncated ICC data stream");
        }
        return data[pos[0]++] & 0xFF;
    }

    /** Decodes and discards the encoded ICC stream, returning its raw (still predicted) bytes. */
    public static byte[] readEncoded(Bits in) throws IOException {
        long encSize = in.u64();
        if (encSize < 0 || encSize > (1 << 28)) {
            throw new IOException("unreasonable ICC stream size " + encSize);
        }
        EntropyDecoder code = EntropyDecoder.read(in, 41, true);
        byte[] data = new byte[(int) encSize];
        int prev = 0;
        int pprev = 0;
        for (long index = 0; index < encSize; index++) {
            int ctx = 0;
            if (index > 128) {
                if (prev < 16) {
                    ctx = prev < 2 ? prev + 3 : 5;
                } else if (prev > 240) {
                    ctx = 6 + (prev == 255 ? 1 : 0);
                } else if (97 <= (prev | 32) && (prev | 32) <= 122) {
                    ctx = 1;
                } else if (prev == 44 || prev == 46 || (48 <= prev && prev < 58)) {
                    ctx = 2;
                } else {
                    ctx = 8;
                }
                if (pprev < 16) {
                    ctx += 2 * 8;
                } else if (pprev > 240) {
                    ctx += 3 * 8;
                } else if (97 <= (pprev | 32) && (pprev | 32) <= 122) {
                    ctx += 0;
                } else if (pprev == 44 || pprev == 46 || (48 <= pprev && pprev < 58)) {
                    ctx += 1 * 8;
                } else {
                    ctx += 4 * 8;
                }
            }
            int b = code.readSymbol(in, ctx);
            pprev = prev;
            prev = b;
            data[(int) index] = (byte) b;
        }
        code.finish(in);
        return data;
    }
}
