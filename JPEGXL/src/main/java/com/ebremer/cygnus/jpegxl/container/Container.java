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

    /**
     * The level a container declares: its {@code jxll} box value, or
     * {@link com.ebremer.cygnus.jpegxl.codestream.CodestreamLevel#DEFAULT} (5)
     * when it omits the box, since a level-5 file is written without one. A bare
     * codestream is in no container and declares nothing, so it returns 0 — the
     * sentinel that leaves it unconstrained: it is decoded whatever it holds
     * rather than held to a baseline it never claimed.
     */
    public static int declaredLevel(byte[] data) throws IOException {
        if (!isContainer(data)) {
            return 0;
        }
        long off = SIGNATURE_BOX.length;
        while (off + 8 <= data.length) {
            long boxSize = u32be(data, (int) off);   // whole box, header included
            int type = (int) u32be(data, (int) off + 4);
            long payload = off + 8;
            if (boxSize == 1) {
                if (payload + 8 > data.length) {
                    throw new IOException("truncated box header");
                }
                boxSize = ((long) u32be(data, (int) payload) << 32) | u32be(data, (int) payload + 4);
                payload += 8;
            } else if (boxSize == 0) {
                boxSize = data.length - off;
            }
            if (type == 0x6a786c6c /* jxll */) {
                if (payload >= data.length) {
                    throw new IOException("empty jxll box");
                }
                return data[(int) payload] & 0xff;
            }
            if (type == 0x6a786c63 /* jxlc */ || type == 0x6a786c70 /* jxlp */) {
                break; // the level box precedes the codestream; stop once past it
            }
            off += boxSize;
        }
        return com.ebremer.cygnus.jpegxl.codestream.CodestreamLevel.DEFAULT;
    }

    /**
     * {@link #declaredLevel(byte[])} over an {@link javax.imageio.stream.ImageInputStream},
     * for the streaming decode path. Leaves the stream position where it found
     * it, so the caller's own scan is undisturbed.
     */
    public static int declaredLevel(javax.imageio.stream.ImageInputStream in) throws IOException {
        int noBox = com.ebremer.cygnus.jpegxl.codestream.CodestreamLevel.DEFAULT;
        long mark = in.getStreamPosition();
        try {
            in.seek(0);
            byte[] sig = new byte[SIGNATURE_BOX.length];
            int got = in.read(sig);
            if (got < sig.length || !java.util.Arrays.equals(sig, SIGNATURE_BOX)) {
                return 0; // bare codestream: declares nothing, unconstrained
            }
            long off = SIGNATURE_BOX.length;
            long length = in.length();
            while (length < 0 || off + 8 <= length) {
                in.seek(off);
                long boxSize;
                int type;
                try {
                    boxSize = in.readUnsignedInt();
                    type = in.readInt();
                } catch (java.io.EOFException e) {
                    break;
                }
                long payload = off + 8;
                if (boxSize == 1) {
                    boxSize = in.readLong();
                    payload += 8;
                } else if (boxSize == 0) {
                    break; // extends to end; no level box follows a codestream box
                }
                if (type == 0x6a786c6c /* jxll */) {
                    in.seek(payload);
                    return in.readUnsignedByte();
                }
                if (type == 0x6a786c63 /* jxlc */ || type == 0x6a786c70 /* jxlp */) {
                    break;
                }
                off += boxSize;
            }
            return noBox; // a container with no jxll box is level 5 by default
        } finally {
            in.seek(mark);
        }
    }

    /**
     * The level a codestream needs, read from its headers alone — the size and
     * image-metadata clauses, no ICC reconstruction and no frames. What
     * {@link #wrap} uses to decide whether to write a {@code jxll} box. Because
     * it skips the ICC, an ICC profile past the level-5 size limit (4 MiB, which
     * essentially never happens) is not seen here; the decoder's own check, run
     * on fully reconstructed metadata, catches that case.
     */
    public static int requiredLevel(byte[] codestream) throws IOException {
        com.ebremer.cygnus.jpegxl.io.Bits in =
                new com.ebremer.cygnus.jpegxl.io.Bits(codestream);
        if (in.u(16) != 0x0aff) {
            throw new IOException("not a JPEG XL codestream");
        }
        com.ebremer.cygnus.jpegxl.codestream.SizeHeader size =
                com.ebremer.cygnus.jpegxl.codestream.SizeHeader.read(in);
        com.ebremer.cygnus.jpegxl.codestream.ImageMetadata meta =
                com.ebremer.cygnus.jpegxl.codestream.ImageMetadata.read(in);
        return com.ebremer.cygnus.jpegxl.codestream.CodestreamLevel.required(
                meta, size.width, size.height);
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

    /**
     * Wraps a codestream with optional metadata boxes: an {@code Exif} box
     * (the payload is the raw TIFF stream; the 4-byte zero tiff-header
     * offset required by 18181-2 is prepended here), an {@code xml } box
     * carrying an XMP packet, or both. With {@code compressMetadata} each
     * metadata box is wrapped in a {@code brob} box; this library's Brotli
     * writer emits uncompressed metablocks, so that trades a few header
     * bytes for exercising the standard compressed-box path, not for size.
     */
    public static byte[] wrap(byte[] codestream, byte[] exif, byte[] xmp,
            boolean compressMetadata) {
        return wrap(codestream, exif, xmp, null, compressMetadata);
    }

    /** {@link #wrap(byte[], byte[], byte[], boolean)} plus a gain-map box. */
    public static byte[] wrap(byte[] codestream, byte[] exif, byte[] xmp,
            GainMap gainMap, boolean compressMetadata) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE_BOX);
        out.writeBytes(FTYP_BOX);
        writeLevelBox(out, codestream);
        if (exif != null) {
            byte[] payload = new byte[exif.length + 4]; // tiff header offset 0
            System.arraycopy(exif, 0, payload, 4, exif.length);
            writeMetaBox(out, "Exif", payload, compressMetadata);
        }
        if (xmp != null) {
            writeMetaBox(out, "xml ", xmp, compressMetadata);
        }
        if (gainMap != null) {
            try {
                writeBox(out, "jhgm", gainMap.toBytes());
            } catch (IOException e) {
                throw new IllegalArgumentException("unserialisable gain map", e);
            }
        }
        writeBox(out, "jxlc", codestream);
        return out.toByteArray();
    }

    private static void writeMetaBox(ByteArrayOutputStream out, String type, byte[] payload,
            boolean compress) {
        if (!compress) {
            writeBox(out, type, payload);
            return;
        }
        byte[] compressed = com.ebremer.cygnus.jpegxl.brotli.Brotli.encodeRaw(payload);
        byte[] inner = new byte[compressed.length + 4];
        inner[0] = (byte) type.charAt(0);
        inner[1] = (byte) type.charAt(1);
        inner[2] = (byte) type.charAt(2);
        inner[3] = (byte) type.charAt(3);
        System.arraycopy(compressed, 0, inner, 4, compressed.length);
        writeBox(out, "brob", inner);
    }

    /**
     * Writes the {@code jxll} box, right after {@code ftyp} and before any
     * codestream, when the codestream needs level 10. Level 5 is the default and
     * carries no box — so a plain image produces exactly the bytes it did before
     * this existed, and only content that reaches past the baseline gains the
     * one-byte declaration that says so.
     */
    private static void writeLevelBox(ByteArrayOutputStream out, byte[] codestream) {
        int level;
        try {
            level = requiredLevel(codestream);
        } catch (IOException e) {
            return; // not a parseable codestream; leave the level unstated
        }
        if (level == com.ebremer.cygnus.jpegxl.codestream.CodestreamLevel.DEFAULT
                || level == com.ebremer.cygnus.jpegxl.codestream.CodestreamLevel.INVALID) {
            return;
        }
        writeBox(out, "jxll", new byte[] {(byte) level});
    }

    private static void writeBox(ByteArrayOutputStream out, String type, byte[] payload) {
        long size = payload.length + 8L;
        out.write((int) (size >>> 24));
        out.write((int) (size >>> 16));
        out.write((int) (size >>> 8));
        out.write((int) size);
        out.write(type.charAt(0));
        out.write(type.charAt(1));
        out.write(type.charAt(2));
        out.write(type.charAt(3));
        out.writeBytes(payload);
    }

    /**
     * The Exif TIFF stream of a container ({@code Exif} box payload with its
     * tiff-header offset applied), or null.
     */
    public static byte[] exifPayload(byte[] data) throws IOException {
        byte[] raw = findBox(data, "Exif");
        if (raw == null || raw.length < 4) {
            return null;
        }
        long offset = u32be(raw, 0);
        long start = 4 + offset;
        if (start > raw.length) {
            return null;
        }
        return java.util.Arrays.copyOfRange(raw, (int) start, raw.length);
    }

    /** Wraps a bare codestream into a minimal box container (jxlc). */
    public static byte[] wrap(byte[] codestream) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE_BOX);
        out.writeBytes(FTYP_BOX);
        writeLevelBox(out, codestream);
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
