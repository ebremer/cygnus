package com.ebremer.cygnus.svs.testutil;

import com.ebremer.cygnus.jpeg2000.encoder.Jpeg2000Encoder;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Builds small Aperio SVS files in memory, faithfully enough to exercise the
 * reader: pyramid levels in tiled directories, associated images in stripped
 * ones, Aperio ImageDescriptions, tiles padded to the full tile size, and JPEG
 * tiles stored as abbreviated streams against a shared {@code JPEGTables} —
 * all of which is how a scanner actually writes them.
 */
public final class SvsBuilder {

    public static final String VENDOR = "Aperio Image Library v12.0.15";

    /** How a directory's pixels are stored. */
    public enum Codec {
        JPEG(7, 6),
        UNCOMPRESSED(1, 2),
        /** Aperio's JPEG 2000 with RGB components. */
        JP2K_RGB(33005, 2),
        /** Aperio's JPEG 2000 with YCbCr components. */
        JP2K_YCBCR(33003, 6);

        final int compression;
        final int photometric;

        Codec(int compression, int photometric) {
            this.compression = compression;
            this.photometric = photometric;
        }
    }

    private static final int TYPE_ASCII = 2;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_UNDEFINED = 7;
    private static final int TYPE_LONG8 = 16;

    private final List<Page> pages = new ArrayList<>();
    private boolean bigTiff;

    private record Page(BufferedImage image, int tileWidth, int tileHeight,
                        Codec codec, String description) {
        boolean tiled() {
            return tileWidth > 0 && tileHeight > 0;
        }
    }

    private static final class Coded {
        Page page;
        byte[][] blobs;
        byte[] jpegTables;
        long[] offsets;
        long[] counts;
    }

    /** Writes a BigTIFF rather than a classic TIFF, as newer scanners do. */
    public SvsBuilder bigTiff() {
        bigTiff = true;
        return this;
    }

    /** Adds a tiled directory — a pyramid level. */
    public SvsBuilder tiled(BufferedImage image, int tileWidth, int tileHeight,
                            Codec codec, String description) {
        pages.add(new Page(image, tileWidth, tileHeight, codec, description));
        return this;
    }

    /** Adds a stripped directory — an associated image. */
    public SvsBuilder stripped(BufferedImage image, Codec codec, String description) {
        pages.add(new Page(image, 0, 0, codec, description));
        return this;
    }

    // ---- the descriptions Aperio writes ----

    public static String levelDescription(int width, int height, int tileWidth, int tileHeight,
                                          String codec) {
        return VENDOR + "\r\n"
                + width + "x" + height + " [0,0 " + width + "x" + height + "] ("
                + tileWidth + "x" + tileHeight + ") " + codec + " Q=30"
                + "|AppMag = 20|StripeWidth = 2040|ScanScope ID = CPAPERIOCS"
                + "|Filename = test|Date = 12/29/09|Time = 09:59:15|MPP = 0.4990"
                + "|ImageID = 1004486";
    }

    public static String thumbnailDescription(int fullWidth, int fullHeight,
                                              int width, int height) {
        return VENDOR + "\r\n" + fullWidth + "x" + fullHeight + " -> " + width + "x" + height
                + " - |AppMag = 20|MPP = 0.4990";
    }

    /** The label and macro name themselves on the second line. */
    public static String associatedDescription(String name, int width, int height) {
        return VENDOR + "\r\n" + name + " " + width + "x" + height;
    }

    // ---- writing ----

    public byte[] build() throws IOException {
        List<Coded> directories = new ArrayList<>(pages.size());
        for (Page page : pages) {
            directories.add(encode(page));
        }

        Buf out = new Buf();
        out.u8('I');
        out.u8('I');
        int firstIfdPointer;
        if (bigTiff) {
            out.u16(43);
            out.u16(8);     // offset size
            out.u16(0);     // reserved
            firstIfdPointer = out.size();
            out.u64(0);
        } else {
            out.u16(42);
            firstIfdPointer = out.size();
            out.u32(0);
        }

        // Pixel data first, so that the tile offsets are known before any IFD is written.
        for (Coded coded : directories) {
            coded.offsets = new long[coded.blobs.length];
            coded.counts = new long[coded.blobs.length];
            for (int i = 0; i < coded.blobs.length; i++) {
                out.align2();
                coded.offsets[i] = out.size();
                coded.counts[i] = coded.blobs[i].length;
                out.bytes(coded.blobs[i]);
            }
        }

        int pendingPointer = firstIfdPointer;
        for (Coded coded : directories) {
            out.align2();
            patchPointer(out, pendingPointer, out.size());
            pendingPointer = writeIfd(out, fieldsOf(coded));
        }
        patchPointer(out, pendingPointer, 0);
        return out.toByteArray();
    }

    private void patchPointer(Buf out, int position, long value) {
        if (bigTiff) {
            out.patchU64(position, value);
        } else {
            out.patchU32(position, value);
        }
    }

    /** Writes an IFD and its out-of-line values; returns the position of its next-IFD pointer. */
    private int writeIfd(Buf out, List<Field> fields) {
        int fieldSize = bigTiff ? 8 : 4;
        int entrySize = bigTiff ? 20 : 12;
        int countSize = bigTiff ? 8 : 2;

        int ifdStart = out.size();
        int valueStart = ifdStart + countSize + entrySize * fields.size() + fieldSize;
        valueStart += valueStart & 1;

        int cursor = valueStart;
        int[] valueOffsets = new int[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            int length = fields.get(i).value().length;
            if (length > fieldSize) {
                valueOffsets[i] = cursor;
                cursor += length + (length & 1);
            }
        }

        if (bigTiff) {
            out.u64(fields.size());
        } else {
            out.u16(fields.size());
        }
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            out.u16(field.tag());
            out.u16(field.type());
            if (bigTiff) {
                out.u64(field.count());
            } else {
                out.u32(field.count());
            }
            byte[] value = field.value();
            if (value.length > fieldSize) {
                if (bigTiff) {
                    out.u64(valueOffsets[i]);
                } else {
                    out.u32(valueOffsets[i]);
                }
            } else {
                // Short values sit left-justified in the entry's value field.
                out.bytes(value);
                for (int pad = value.length; pad < fieldSize; pad++) {
                    out.u8(0);
                }
            }
        }

        int nextPointer = out.size();
        if (bigTiff) {
            out.u64(0);
        } else {
            out.u32(0);
        }

        while (out.size() < valueStart) {
            out.u8(0);
        }
        for (Field field : fields) {
            byte[] value = field.value();
            if (value.length > fieldSize) {
                out.bytes(value);
                if ((value.length & 1) != 0) {
                    out.u8(0);
                }
            }
        }
        return nextPointer;
    }

    private List<Field> fieldsOf(Coded coded) {
        Page page = coded.page;
        int width = page.image().getWidth();
        int height = page.image().getHeight();
        Codec codec = page.codec();

        List<Field> fields = new ArrayList<>();
        fields.add(longs(256, width));
        fields.add(longs(257, height));
        fields.add(shorts(258, 8, 8, 8));
        fields.add(shorts(259, codec.compression));
        fields.add(shorts(262, codec.photometric));
        fields.add(ascii(270, page.description()));
        fields.add(shorts(277, 3));
        fields.add(shorts(284, 1));                     // chunky
        if (page.tiled()) {
            fields.add(longs(322, page.tileWidth()));
            fields.add(longs(323, page.tileHeight()));
            fields.add(pointers(324, coded.offsets));
            fields.add(pointers(325, coded.counts));
        } else {
            fields.add(pointers(273, coded.offsets));   // StripOffsets
            fields.add(longs(278, height));             // RowsPerStrip: one strip
            fields.add(pointers(279, coded.counts));    // StripByteCounts
        }
        if (coded.jpegTables != null) {
            fields.add(undefined(347, coded.jpegTables));
        }
        if (codec.photometric == 6) {
            fields.add(shorts(530, 2, 2));              // YCbCrSubSampling
        }
        fields.sort(Comparator.comparingInt(Field::tag));
        return fields;
    }

    // ---- pixel data ----

    private Coded encode(Page page) throws IOException {
        Coded coded = new Coded();
        coded.page = page;
        if (!page.tiled()) {
            coded.blobs = new byte[][] {compress(page.image(), page.codec())};
            return coded;
        }

        int across = Math.ceilDiv(page.image().getWidth(), page.tileWidth());
        int down = Math.ceilDiv(page.image().getHeight(), page.tileHeight());
        coded.blobs = new byte[across * down][];
        byte[] tables = null;
        for (int tileY = 0; tileY < down; tileY++) {
            for (int tileX = 0; tileX < across; tileX++) {
                byte[] data = compress(tileOf(page, tileX, tileY), page.codec());
                if (page.codec() == Codec.JPEG) {
                    byte[][] split = splitJpeg(data);
                    if (tables == null) {
                        tables = split[0];
                    } else if (!Arrays.equals(tables, split[0])) {
                        throw new IllegalStateException(
                                "Tiles produced different JPEG tables; cannot share JPEGTables");
                    }
                    data = split[1];
                }
                coded.blobs[tileY * across + tileX] = data;
            }
        }
        coded.jpegTables = tables;
        return coded;
    }

    /** A tile padded to the full tile size by replicating the edge, as scanners do. */
    private static BufferedImage tileOf(Page page, int tileX, int tileY) {
        BufferedImage source = page.image();
        int originX = tileX * page.tileWidth();
        int originY = tileY * page.tileHeight();
        BufferedImage tile = new BufferedImage(page.tileWidth(), page.tileHeight(),
                BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < page.tileHeight(); y++) {
            int sourceY = Math.min(source.getHeight() - 1, originY + y);
            for (int x = 0; x < page.tileWidth(); x++) {
                int sourceX = Math.min(source.getWidth() - 1, originX + x);
                tile.setRGB(x, y, source.getRGB(sourceX, sourceY));
            }
        }
        return tile;
    }

    private static byte[] compress(BufferedImage image, Codec codec) throws IOException {
        return switch (codec) {
            case JPEG -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!ImageIO.write(image, "jpeg", out)) {
                    throw new IOException("No JPEG writer");
                }
                yield out.toByteArray();
            }
            case UNCOMPRESSED -> {
                byte[] out = new byte[image.getWidth() * image.getHeight() * 3];
                int at = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int rgb = image.getRGB(x, y);
                        out[at++] = (byte) (rgb >> 16);
                        out[at++] = (byte) (rgb >> 8);
                        out[at++] = (byte) rgb;
                    }
                }
                yield out;
            }
            case JP2K_RGB -> jpeg2000(image, true);
            case JP2K_YCBCR -> jpeg2000(image, false);
        };
    }

    /**
     * A bare JPEG 2000 codestream, which is what an Aperio tile holds. RGB
     * tiles get the reversible colour transform; YCbCr tiles are stored as
     * coded, with no transform, so only the TIFF tag says what they are.
     */
    private static byte[] jpeg2000(BufferedImage image, boolean rgb) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();

        Jpeg2000Encoder.Params params = new Jpeg2000Encoder.Params();
        params.width = width;
        params.height = height;
        params.reversible = true;
        params.precision = new int[] {8, 8, 8};
        params.mct = rgb;

        int[][] components = new int[3][width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgbValue = image.getRGB(x, y);
                int r = (rgbValue >> 16) & 0xFF;
                int g = (rgbValue >> 8) & 0xFF;
                int b = rgbValue & 0xFF;
                int at = y * width + x;
                if (rgb) {
                    components[0][at] = r;
                    components[1][at] = g;
                    components[2][at] = b;
                } else {
                    components[0][at] = clamp(Math.round(0.299f * r + 0.587f * g + 0.114f * b));
                    components[1][at] = clamp(Math.round(
                            128f - 0.168736f * r - 0.331264f * g + 0.5f * b));
                    components[2][at] = clamp(Math.round(
                            128f + 0.5f * r - 0.418688f * g - 0.081312f * b));
                }
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Jpeg2000Encoder(params).encode(components, out);
        return out.toByteArray();
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    /**
     * Splits a self-contained JPEG into the abbreviated table stream a TIFF
     * puts in JPEGTables (SOI, DQT, DHT, EOI) and the abbreviated image stream
     * a tile holds (everything else).
     */
    static byte[][] splitJpeg(byte[] jpeg) throws IOException {
        ByteArrayOutputStream tables = new ByteArrayOutputStream();
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        tables.write(0xFF);
        tables.write(0xD8);
        image.write(0xFF);
        image.write(0xD8);

        int at = 2; // past SOI
        while (at + 3 < jpeg.length) {
            if ((jpeg[at] & 0xFF) != 0xFF) {
                throw new IOException("Not at a JPEG marker at offset " + at);
            }
            int marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xD9) {
                break;
            }
            int length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
            int end = at + 2 + length;
            if (marker == 0xDB || marker == 0xC4) {         // DQT, DHT
                tables.write(jpeg, at, end - at);
            } else if (marker == 0xDA) {                    // SOS: header, entropy data, EOI
                image.write(jpeg, at, jpeg.length - at);
                tables.write(0xFF);
                tables.write(0xD9);
                return new byte[][] {tables.toByteArray(), image.toByteArray()};
            } else {
                image.write(jpeg, at, end - at);
            }
            at = end;
        }
        throw new IOException("JPEG has no scan");
    }

    // ---- TIFF fields ----

    private record Field(int tag, int type, long count, byte[] value) {
    }

    private static Field shorts(int tag, int... values) {
        Buf buf = new Buf();
        for (int value : values) {
            buf.u16(value);
        }
        return new Field(tag, TYPE_SHORT, values.length, buf.toByteArray());
    }

    private static Field longs(int tag, long... values) {
        Buf buf = new Buf();
        for (long value : values) {
            buf.u32(value);
        }
        return new Field(tag, TYPE_LONG, values.length, buf.toByteArray());
    }

    /** File offsets and byte counts, widened to LONG8 in a BigTIFF. */
    private Field pointers(int tag, long[] values) {
        Buf buf = new Buf();
        for (long value : values) {
            if (bigTiff) {
                buf.u64(value);
            } else {
                buf.u32(value);
            }
        }
        return new Field(tag, bigTiff ? TYPE_LONG8 : TYPE_LONG, values.length, buf.toByteArray());
    }

    private static Field ascii(int tag, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        byte[] value = Arrays.copyOf(bytes, bytes.length + 1); // NUL-terminated
        return new Field(tag, TYPE_ASCII, value.length, value);
    }

    private static Field undefined(int tag, byte[] data) {
        return new Field(tag, TYPE_UNDEFINED, data.length, data);
    }

    /** Little-endian byte sink with patchable offsets. */
    private static final class Buf {

        private byte[] data = new byte[4096];
        private int size;

        void u8(int value) {
            ensure(1);
            data[size++] = (byte) value;
        }

        void u16(int value) {
            u8(value);
            u8(value >> 8);
        }

        void u32(long value) {
            u16((int) value);
            u16((int) (value >> 16));
        }

        void u64(long value) {
            u32(value);
            u32(value >>> 32);
        }

        void bytes(byte[] value) {
            ensure(value.length);
            System.arraycopy(value, 0, data, size, value.length);
            size += value.length;
        }

        void align2() {
            if ((size & 1) != 0) {
                u8(0);
            }
        }

        void patchU32(int position, long value) {
            for (int i = 0; i < 4; i++) {
                data[position + i] = (byte) (value >>> (8 * i));
            }
        }

        void patchU64(int position, long value) {
            for (int i = 0; i < 8; i++) {
                data[position + i] = (byte) (value >>> (8 * i));
            }
        }

        int size() {
            return size;
        }

        byte[] toByteArray() {
            return Arrays.copyOf(data, size);
        }

        private void ensure(int extra) {
            if (size + extra > data.length) {
                data = Arrays.copyOf(data, Math.max(size + extra, data.length * 2));
            }
        }
    }
}
