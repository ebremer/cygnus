package com.ebremer.cygnus.jpegxl.container;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * ISO BMFF container handling (ISO/IEC 18181-2). A JPEG XL file is either a
 * bare codestream starting with FF 0A, or a box container whose codestream is
 * carried in one {@code jxlc} box or a series of {@code jxlp} boxes.
 */
public final class Container {

    public static final byte[] SIGNATURE_BOX = {
        0x00, 0x00, 0x00, 0x0c, 'J', 'X', 'L', ' ', 0x0d, 0x0a, (byte) 0x87, 0x0a,
    };
    public static final byte[] FTYP_BOX = {
        0x00, 0x00, 0x00, 0x14, 'f', 't', 'y', 'p', 'j', 'x', 'l', ' ',
        0x00, 0x00, 0x00, 0x00, 'j', 'x', 'l', ' ',
    };

    private Container() {
    }

    public static boolean isBareCodestream(byte[] data) {
        return data.length >= 2 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0x0a;
    }

    public static boolean isContainer(byte[] data) {
        if (data.length < SIGNATURE_BOX.length) {
            return false;
        }
        for (int i = 0; i < SIGNATURE_BOX.length; i++) {
            if (data[i] != SIGNATURE_BOX[i]) {
                return false;
            }
        }
        return true;
    }

    /** Extracts the raw codestream, concatenating jxlp fragments if needed. */
    public static byte[] extractCodestream(byte[] data) throws IOException {
        if (isBareCodestream(data)) {
            return data;
        }
        if (!isContainer(data)) {
            throw new IOException("not a JPEG XL file (bad signature)");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long off = SIGNATURE_BOX.length;
        boolean first = true;
        while (off + 8 <= data.length) {
            long size = u32be(data, (int) off);
            int type = (int) u32be(data, (int) off + 4);
            long payload = off + 8;
            if (size == 1) {
                if (payload + 8 > data.length) {
                    throw new IOException("truncated box header");
                }
                size = (u32be(data, (int) payload) << 32) | u32be(data, (int) payload + 4);
                if (size < 16) {
                    throw new IOException("bad extended box size");
                }
                payload += 8;
                size -= 16;
            } else if (size == 0) {
                size = data.length - payload; // extends to end of file
            } else {
                if (size < 8) {
                    throw new IOException("bad box size");
                }
                size -= 8;
            }
            if (payload + size > data.length) {
                throw new IOException("truncated box");
            }
            if (first) {
                if (type != 0x66747970 /* ftyp */) {
                    throw new IOException("missing ftyp box");
                }
                first = false;
            } else if (type == 0x6a786c63 /* jxlc */) {
                out.write(data, (int) payload, (int) size);
            } else if (type == 0x6a786c70 /* jxlp */) {
                if (size < 4) {
                    throw new IOException("truncated jxlp box");
                }
                out.write(data, (int) payload + 4, (int) (size - 4));
            }
            off = payload + size;
        }
        byte[] codestream = out.toByteArray();
        if (codestream.length == 0) {
            throw new IOException("container has no codestream boxes");
        }
        return codestream;
    }

    /**
     * Scans the box structure of a file read from an {@link javax.imageio.stream.ImageInputStream}
     * and returns the codestream byte ranges without reading their contents.
     * Handles bare codestreams (one segment spanning the whole stream).
     */
    public static java.util.List<com.ebremer.cygnus.jpegxl.io.CodestreamSource.Segment> scanSegments(
            javax.imageio.stream.ImageInputStream in) throws IOException {
        java.util.List<com.ebremer.cygnus.jpegxl.io.CodestreamSource.Segment> segments =
                new java.util.ArrayList<>();
        in.seek(0);
        long length = in.length();
        if (length < 0) {
            // caching streams may not know their length; probe to EOF once
            byte[] probe = new byte[1 << 16];
            long total = 0;
            int r;
            while ((r = in.read(probe)) > 0) {
                total += r;
            }
            length = total;
            in.seek(0);
        }
        byte[] head = new byte[SIGNATURE_BOX.length];
        int got = 0;
        while (got < head.length) {
            int r = in.read(head, got, head.length - got);
            if (r < 0) {
                break;
            }
            got += r;
        }
        if (got >= 2 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0x0a) {
            segments.add(new com.ebremer.cygnus.jpegxl.io.CodestreamSource.Segment(0, 0, length));
            return segments;
        }
        if (got < head.length || !java.util.Arrays.equals(head, SIGNATURE_BOX)) {
            throw new IOException("not a JPEG XL file (bad signature)");
        }
        long off = SIGNATURE_BOX.length;
        long csOffset = 0;
        boolean first = true;
        while (off + 8 <= length) {
            in.seek(off);
            long size;
            int type;
            try {
                size = in.readUnsignedInt();
                type = in.readInt();
            } catch (java.io.EOFException e) {
                break;
            }
            long payload = off + 8;
            if (size == 1) {
                size = in.readLong();
                if (size < 16) {
                    throw new IOException("bad extended box size");
                }
                payload += 8;
                size -= 16;
            } else if (size == 0) {
                size = length - payload;
            } else {
                if (size < 8) {
                    throw new IOException("bad box size");
                }
                size -= 8;
            }
            if (payload + size > length) {
                throw new IOException("truncated box");
            }
            if (first) {
                if (type != 0x66747970 /* ftyp */) {
                    throw new IOException("missing ftyp box");
                }
                first = false;
            } else if (type == 0x6a786c63 /* jxlc */) {
                segments.add(new com.ebremer.cygnus.jpegxl.io.CodestreamSource.Segment(
                        csOffset, payload, size));
                csOffset += size;
            } else if (type == 0x6a786c70 /* jxlp */) {
                if (size < 4) {
                    throw new IOException("truncated jxlp box");
                }
                segments.add(new com.ebremer.cygnus.jpegxl.io.CodestreamSource.Segment(
                        csOffset, payload + 4, size - 4));
                csOffset += size - 4;
            }
            off = payload + size;
        }
        if (segments.isEmpty()) {
            throw new IOException("container has no codestream boxes");
        }
        return segments;
    }

    /**
     * Returns the concatenated payload of every box with the given 4-byte
     * type ({@code "jbrd"}, {@code "Exif"}, {@code "xml "}, ...), or
     * {@code null} when the file is a bare codestream or has no such box.
     * {@code brob} (brotli-compressed) boxes wrapping the requested type are
     * decompressed transparently.
     */
    public static byte[] findBox(byte[] data, String boxType) throws IOException {
        if (!isContainer(data)) {
            return null;
        }
        int wanted = (boxType.charAt(0) << 24) | (boxType.charAt(1) << 16)
                | (boxType.charAt(2) << 8) | boxType.charAt(3);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean found = false;
        long off = SIGNATURE_BOX.length;
        boolean first = true;
        while (off + 8 <= data.length) {
            long size = u32be(data, (int) off);
            int type = (int) u32be(data, (int) off + 4);
            long payload = off + 8;
            if (size == 1) {
                if (payload + 8 > data.length) {
                    throw new IOException("truncated box header");
                }
                size = (u32be(data, (int) payload) << 32) | u32be(data, (int) payload + 4);
                if (size < 16) {
                    throw new IOException("bad extended box size");
                }
                payload += 8;
                size -= 16;
            } else if (size == 0) {
                size = data.length - payload;
            } else {
                if (size < 8) {
                    throw new IOException("bad box size");
                }
                size -= 8;
            }
            if (payload + size > data.length) {
                throw new IOException("truncated box");
            }
            if (first) {
                first = false;
            } else if (type == wanted) {
                out.write(data, (int) payload, (int) size);
                found = true;
            } else if (type == 0x62726f62 /* brob */ && size > 4) {
                long inner = u32be(data, (int) payload);
                if (inner == (wanted & 0xFFFFFFFFL)) {
                    byte[] plain = com.ebremer.cygnus.jpegxl.brotli.Brotli.decode(
                            data, (int) payload + 4, (int) (size - 4), 1 << 28);
                    out.write(plain, 0, plain.length);
                    found = true;
                }
            }
            off = payload + size;
        }
        return found ? out.toByteArray() : null;
    }

    /** Wraps a bare codestream into a minimal box container (jxlc). */
    public static byte[] wrap(byte[] codestream) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE_BOX);
        out.writeBytes(FTYP_BOX);
        long size = codestream.length + 8L;
        out.write((int) (size >>> 24));
        out.write((int) (size >>> 16));
        out.write((int) (size >>> 8));
        out.write((int) size);
        out.writeBytes(new byte[] {'j', 'x', 'l', 'c'});
        out.writeBytes(codestream);
        return out.toByteArray();
    }

    private static long u32be(byte[] b, int off) {
        return ((b[off] & 0xffL) << 24) | ((b[off + 1] & 0xffL) << 16)
                | ((b[off + 2] & 0xffL) << 8) | (b[off + 3] & 0xffL);
    }
}
