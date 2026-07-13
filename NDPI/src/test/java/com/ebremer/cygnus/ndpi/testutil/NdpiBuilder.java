package com.ebremer.cygnus.ndpi.testutil;

import com.ebremer.cygnus.ndpi.JpegHeader;
import com.ebremer.cygnus.ndpi.Ndpi;
import com.twelvemonkeys.imageio.stream.ByteArrayImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Builds Hamamatsu NDPI files in memory, faithfully enough to exercise the
 * reader: the 8-byte directory pointers and the block of value extensions that
 * make NDPI a 64-bit format in a 32-bit container, levels whose single JPEG is
 * cut into tiles by restart markers and indexed by an MCU-start table, a SOF
 * that lies about the size when the level is larger than a JPEG may be, and a
 * macro image.
 *
 * <p>The level JPEG is built the way a scanner's would be: each tile is encoded
 * on its own and their entropy data concatenated with restart markers between
 * them. Because a tile is one restart interval — {@code restartInterval} MCUs
 * wide and one MCU tall — the concatenation is exactly the MCU order of the
 * whole image, so the result is a real, decodable JPEG of the full level.</p>
 */
public final class NdpiBuilder {

    private static final int TYPE_ASCII = 2;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_RATIONAL = 5;
    private static final int TYPE_SLONG = 9;
    private static final int TYPE_FLOAT = 11;

    private final List<Page> pages = new ArrayList<>();
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final Map<Integer, Long> extraTags = new LinkedHashMap<>();

    private long dataOffset = 12;
    private long xResolution = 40000;   // pixels per centimetre: 0.25 microns per pixel
    private boolean corruptMcuStarts;

    private record Page(BufferedImage image, int restartInterval, double sourceLens) {
        boolean tiled() {
            return restartInterval > 0;
        }
    }

    /**
     * Puts the pixel data, and everything after it, at this offset. Past 4 GiB
     * the directories' offsets no longer fit in 32 bits, which is the whole
     * reason NDPI is shaped the way it is.
     */
    public NdpiBuilder dataAt(long offset) {
        this.dataOffset = offset;
        return this;
    }

    /** A pyramid level, cut into tiles by a restart marker every {@code restartInterval} MCUs. */
    public NdpiBuilder level(BufferedImage image, int restartInterval, double sourceLens) {
        pages.add(new Page(image, restartInterval, sourceLens));
        return this;
    }

    /** A level small enough to be one ordinary JPEG, with no restart markers. */
    public NdpiBuilder plainLevel(BufferedImage image, double sourceLens) {
        pages.add(new Page(image, 0, sourceLens));
        return this;
    }

    /** The macro ({@code -1}) or map ({@code -2}) image. */
    public NdpiBuilder associated(BufferedImage image, double sourceLens) {
        pages.add(new Page(image, 0, sourceLens));
        return this;
    }

    public NdpiBuilder property(String key, String value) {
        properties.put(key, value);
        return this;
    }

    /**
     * Records MCU starts that do not point at restart markers. OpenSlide calls
     * the recorded ones "unreliable" and re-derives them; a reader has to cope.
     */
    public NdpiBuilder corruptMcuStarts() {
        this.corruptMcuStarts = true;
        return this;
    }

    /**
     * An extra LONG tag. A value past 2^32 is written the NDPI way — low half in
     * the directory entry, high half in the extension — which a reader must
     * widen back to a 64-bit value.
     */
    public NdpiBuilder longTag(int tag, long value) {
        extraTags.put(tag, value);
        return this;
    }

    // ---- writing ----

    public byte[] build() throws IOException {
        if (dataOffset != 12) {
            throw new IllegalStateException("Data past the header needs writeTo(), not build()");
        }
        Buf header = new Buf(0);
        Buf body = new Buf(12);
        assemble(header, body);
        byte[] out = Arrays.copyOf(header.toByteArray(), (int) (12 + body.length()));
        System.arraycopy(body.toByteArray(), 0, out, 12, (int) body.length());
        return out;
    }

    /**
     * Writes the file, leaving a hole between the header and the data. The hole
     * costs no disk on a filesystem that understands sparse files, so a slide
     * past 4 GiB can be built without one.
     */
    public void writeTo(Path path) throws IOException {
        Buf header = new Buf(0);
        Buf body = new Buf(dataOffset);
        assemble(header, body);
        try (SeekableByteChannel channel = Files.newByteChannel(path, Set.of(
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SPARSE))) {
            channel.write(ByteBuffer.wrap(header.toByteArray()));
            channel.position(dataOffset);
            channel.write(ByteBuffer.wrap(body.toByteArray()));
        }
    }

    private void assemble(Buf header, Buf body) throws IOException {
        List<Strip> strips = new ArrayList<>(pages.size());
        for (Page page : pages) {
            strips.add(encode(page));
        }

        header.u8('I');
        header.u8('I');
        header.u16(42);
        int firstDirectoryPointer = (int) header.length();
        header.u64(0);                       // 8 bytes, where a TIFF has 4

        for (Strip strip : strips) {
            body.align2();
            strip.offset = body.position();
            body.bytes(strip.jpeg);
        }

        long pendingPointer = -1;
        for (int i = 0; i < strips.size(); i++) {
            body.align2();
            long directoryAt = body.position();
            if (pendingPointer < 0) {
                header.patchU64(firstDirectoryPointer, directoryAt);
            } else {
                body.patchU64(pendingPointer, directoryAt);
            }
            pendingPointer = writeDirectory(body, strips.get(i), pages.get(i));
        }
        body.patchU64(pendingPointer, 0);
    }

    /** Writes one directory and its out-of-line values; returns its next-pointer position. */
    private long writeDirectory(Buf out, Strip strip, Page page) {
        List<Field> fields = fieldsOf(strip, page);
        int count = fields.size();

        long directoryAt = out.position();
        long extensionsAt = directoryAt + 2 + 12L * count + 8;
        long valuesAt = extensionsAt + 4L * count;
        valuesAt += valuesAt & 1;

        // Where each out-of-line value will land.
        long cursor = valuesAt;
        long[] offsets = new long[count];
        for (int i = 0; i < count; i++) {
            Field field = fields.get(i);
            if (!field.inline()) {
                offsets[i] = cursor;
                cursor += field.payload().length + (field.payload().length & 1);
            }
        }

        out.u16(count);
        for (int i = 0; i < count; i++) {
            Field field = fields.get(i);
            out.u16(field.tag());
            out.u16(field.type());
            out.u32(field.count());
            if (field.inline()) {
                byte[] payload = field.payload();
                for (int b = 0; b < 4; b++) {
                    out.u8(b < payload.length ? payload[b] & 0xFF : 0);
                }
            } else {
                out.u32(offsets[i] & 0xFFFFFFFFL);
            }
        }

        long nextPointer = out.position();
        out.u64(0);

        // The high halves, one word per entry. This is what a TIFF reader never sees.
        for (int i = 0; i < count; i++) {
            Field field = fields.get(i);
            if (field.inline()) {
                byte[] payload = field.payload();
                for (int b = 4; b < 8; b++) {
                    out.u8(b < payload.length ? payload[b] & 0xFF : 0);
                }
            } else {
                out.u32(offsets[i] >>> 32);
            }
        }

        while (out.position() < valuesAt) {
            out.u8(0);
        }
        for (Field field : fields) {
            if (!field.inline()) {
                out.bytes(field.payload());
                if ((field.payload().length & 1) != 0) {
                    out.u8(0);
                }
            }
        }
        return nextPointer;
    }

    private List<Field> fieldsOf(Strip strip, Page page) {
        int width = page.image().getWidth();
        int height = page.image().getHeight();

        List<Field> fields = new ArrayList<>();
        fields.add(longField(Ndpi.TAG_IMAGE_WIDTH, width));
        fields.add(longField(Ndpi.TAG_IMAGE_LENGTH, height));
        fields.add(shortField(Ndpi.TAG_BITS_PER_SAMPLE, 8, 8, 8));
        fields.add(shortField(Ndpi.TAG_COMPRESSION, Ndpi.COMPRESSION_JPEG));
        fields.add(shortField(Ndpi.TAG_PHOTOMETRIC_INTERPRETATION, 6));   // YCbCr
        fields.add(longField(Ndpi.TAG_STRIP_OFFSETS, strip.offset));
        fields.add(shortField(Ndpi.TAG_SAMPLES_PER_PIXEL, 3));
        fields.add(longField(Ndpi.TAG_ROWS_PER_STRIP, height));
        fields.add(longField(Ndpi.TAG_STRIP_BYTE_COUNTS, strip.jpeg.length));
        fields.add(rationalField(Ndpi.TAG_X_RESOLUTION, xResolution, 1));
        fields.add(rationalField(Ndpi.TAG_Y_RESOLUTION, xResolution, 1));
        fields.add(shortField(Ndpi.TAG_RESOLUTION_UNIT, 3));              // centimetre

        fields.add(longField(Ndpi.TAG_FORMAT_FLAG, 1));
        fields.add(floatField(Ndpi.TAG_SOURCE_LENS, (float) page.sourceLens()));
        fields.add(signedLongField(Ndpi.TAG_X_OFFSET, 0));
        fields.add(signedLongField(Ndpi.TAG_Y_OFFSET, 0));
        fields.add(signedLongField(Ndpi.TAG_FOCAL_PLANE, 0));
        if (strip.mcuStarts != null) {
            fields.add(longArrayField(Ndpi.TAG_MCU_STARTS, strip.mcuStarts));
            fields.add(longArrayField(Ndpi.TAG_MCU_STARTS_HIGH, new long[strip.mcuStarts.length]));
        }
        if (!properties.isEmpty()) {
            StringBuilder text = new StringBuilder();
            properties.forEach((key, value) -> text.append(key).append('=').append(value)
                    .append("\r\n"));
            fields.add(asciiField(Ndpi.TAG_PROPERTY_MAP, text.toString()));
        }
        extraTags.forEach((tag, value) -> fields.add(longField(tag, value)));

        fields.sort(Comparator.comparingInt(Field::tag));
        return fields;
    }

    // ---- pixel data ----

    private static final class Strip {
        byte[] jpeg;
        long[] mcuStarts;   // relative to the strip; null when the level has no restart markers
        long offset;
    }

    private Strip encode(Page page) throws IOException {
        Strip strip = new Strip();
        if (!page.tiled()) {
            strip.jpeg = toJpeg(page.image());
            return strip;
        }

        BufferedImage image = page.image();
        int width = image.getWidth();
        int height = image.getHeight();

        // What the JPEG writer's chroma subsampling makes an MCU, and so a tile.
        JpegHeader probe = headerOf(toJpeg(Images.solid(64, 64, 0x808080)));
        int mcuWidth = probe.mcuWidth();
        int mcuHeight = probe.mcuHeight();
        int tileWidth = page.restartInterval() * mcuWidth;
        int tileHeight = mcuHeight;
        if (width % tileWidth != 0) {
            throw new IllegalArgumentException("Width " + width + " is not a whole number of "
                    + tileWidth + "-pixel tiles, so the restart markers would not line up");
        }
        int tilesAcross = width / tileWidth;
        int tilesDown = Math.ceilDiv(height, tileHeight);

        byte[] header = null;
        int sofOffset = -1;
        List<byte[]> entropy = new ArrayList<>(tilesAcross * tilesDown);
        for (int tileY = 0; tileY < tilesDown; tileY++) {
            for (int tileX = 0; tileX < tilesAcross; tileX++) {
                byte[] jpeg = toJpeg(tileOf(image, tileX * tileWidth, tileY * tileHeight,
                        tileWidth, tileHeight));
                Parts parts = split(jpeg);
                if (header == null) {
                    JpegHeader encoded = headerOf(jpeg);
                    if (encoded.mcuWidth() != mcuWidth || encoded.mcuHeight() != mcuHeight) {
                        throw new IllegalStateException("The JPEG writer chose a "
                                + encoded.mcuWidth() + "x" + encoded.mcuHeight() + " MCU for the "
                                + "tiles but " + mcuWidth + "x" + mcuHeight + " for the probe");
                    }
                    header = parts.header();
                    sofOffset = parts.sofOffset();
                } else if (!Arrays.equals(header, parts.header())) {
                    throw new IllegalStateException("Tiles produced different JPEG headers");
                }
                entropy.add(parts.entropy());
            }
        }

        // The level's header: the tiles' own, resized to the level and given a restart
        // interval. A level too big for a SOF gets a zero, as a scanner writes.
        byte[] levelHeader = withRestartInterval(
                withSize(header, sofOffset,
                        width > Ndpi.JPEG_MAX_DIMENSION ? 0 : width,
                        height > Ndpi.JPEG_MAX_DIMENSION ? 0 : height),
                page.restartInterval());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(levelHeader);
        long[] mcuStarts = new long[entropy.size()];
        for (int i = 0; i < entropy.size(); i++) {
            mcuStarts[i] = out.size();
            out.write(entropy.get(i));
            if (i + 1 < entropy.size()) {
                out.write(0xFF);
                out.write(0xD0 + (i % 8));   // RST0..RST7, in order
            }
        }
        out.write(0xFF);
        out.write(0xD9);                     // EOI

        strip.jpeg = out.toByteArray();
        if (corruptMcuStarts) {
            for (int i = 0; i < mcuStarts.length; i++) {
                mcuStarts[i] += 3;   // no longer just past a restart marker
            }
        }
        strip.mcuStarts = mcuStarts;
        return strip;
    }

    private static byte[] toJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpeg", out)) {
            throw new IOException("No JPEG writer");
        }
        return out.toByteArray();
    }

    /**
     * What a reader must produce for a level: every tile encoded on its own and
     * decoded again. A tile's bytes in the level's JPEG are the same bytes it
     * would have on its own — that is what a restart marker buys — so this is
     * the exact expected output, not an approximation of it.
     */
    public static BufferedImage decodeAsTiles(BufferedImage source, int tileWidth, int tileHeight)
            throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        for (int originY = 0; originY < height; originY += tileHeight) {
            for (int originX = 0; originX < width; originX += tileWidth) {
                BufferedImage tile = tileOf(source, originX, originY, tileWidth, tileHeight);
                BufferedImage decoded = ImageIO.read(
                        new ByteArrayImageInputStream(toJpeg(tile)));
                for (int y = 0; y < tileHeight && originY + y < height; y++) {
                    for (int x = 0; x < tileWidth && originX + x < width; x++) {
                        out.setRGB(originX + x, originY + y, decoded.getRGB(x, y));
                    }
                }
            }
        }
        return out;
    }

    private static JpegHeader headerOf(byte[] jpeg) throws IOException {
        return JpegHeader.read(new ByteArrayImageInputStream(jpeg), 0, jpeg.length);
    }

    /** A tile of the image, padded out to the full tile size by repeating the edge. */
    private static BufferedImage tileOf(BufferedImage source, int originX, int originY,
                                        int width, int height) {
        BufferedImage tile = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < height; y++) {
            int sourceY = Math.min(source.getHeight() - 1, originY + y);
            for (int x = 0; x < width; x++) {
                int sourceX = Math.min(source.getWidth() - 1, originX + x);
                tile.setRGB(x, y, source.getRGB(sourceX, sourceY));
            }
        }
        return tile;
    }

    /** SOI through SOS, and the entropy data between SOS and EOI. */
    private record Parts(byte[] header, byte[] entropy, int sofOffset) {
    }

    private static Parts split(byte[] jpeg) throws IOException {
        int at = 2;                     // past SOI
        int sofOffset = -1;
        while (at + 3 < jpeg.length) {
            if ((jpeg[at] & 0xFF) != 0xFF) {
                throw new IOException("Not at a JPEG marker at " + at);
            }
            int code = jpeg[at + 1] & 0xFF;
            int length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
            if ((code >= 0xC0 && code <= 0xC3) || (code >= 0xC5 && code <= 0xC7)
                    || (code >= 0xC9 && code <= 0xCB) || (code >= 0xCD && code <= 0xCF)) {
                sofOffset = at;
            }
            if (code == 0xDA) {         // SOS: the header ends with it
                int headerEnd = at + 2 + length;
                return new Parts(Arrays.copyOf(jpeg, headerEnd),
                        Arrays.copyOfRange(jpeg, headerEnd, jpeg.length - 2),   // drop EOI
                        sofOffset);
            }
            at += 2 + length;
        }
        throw new IOException("JPEG has no scan");
    }

    private static byte[] withSize(byte[] header, int sofOffset, int width, int height) {
        byte[] patched = header.clone();
        patched[sofOffset + 5] = (byte) (height >> 8);
        patched[sofOffset + 6] = (byte) height;
        patched[sofOffset + 7] = (byte) (width >> 8);
        patched[sofOffset + 8] = (byte) width;
        return patched;
    }

    /** Splices a DRI segment in ahead of the scan. */
    private static byte[] withRestartInterval(byte[] header, int interval) throws IOException {
        int sos = header.length;
        int at = 2;
        while (at + 3 < header.length) {
            if ((header[at + 1] & 0xFF) == 0xDA) {
                sos = at;
                break;
            }
            at += 2 + (((header[at + 2] & 0xFF) << 8) | (header[at + 3] & 0xFF));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header, 0, sos);
        out.write(0xFF);
        out.write(0xDD);
        out.write(0x00);
        out.write(0x04);
        out.write(interval >> 8);
        out.write(interval);
        out.write(header, sos, header.length - sos);
        return out.toByteArray();
    }

    // ---- directory fields ----

    private record Field(int tag, int type, long count, byte[] payload, boolean widened) {
        boolean inline() {
            return widened || (payload.length <= 4 && type != TYPE_ASCII);
        }
    }

    private static Field longField(int tag, long value) {
        Buf buf = new Buf(0);
        buf.u32(value & 0xFFFFFFFFL);
        boolean widened = (value >>> 32) != 0;
        if (widened) {
            buf.u32(value >>> 32);      // the high half goes to the extension
        }
        return new Field(tag, TYPE_LONG, 1, buf.toByteArray(), widened);
    }

    private static Field signedLongField(int tag, int value) {
        Buf buf = new Buf(0);
        buf.u32(value & 0xFFFFFFFFL);
        return new Field(tag, TYPE_SLONG, 1, buf.toByteArray(), false);
    }

    private static Field shortField(int tag, int... values) {
        Buf buf = new Buf(0);
        for (int value : values) {
            buf.u16(value);
        }
        return new Field(tag, TYPE_SHORT, values.length, buf.toByteArray(), false);
    }

    private static Field longArrayField(int tag, long[] values) {
        Buf buf = new Buf(0);
        for (long value : values) {
            buf.u32(value & 0xFFFFFFFFL);
        }
        return new Field(tag, TYPE_LONG, values.length, buf.toByteArray(), false);
    }

    private static Field floatField(int tag, float value) {
        Buf buf = new Buf(0);
        buf.u32(Float.floatToIntBits(value) & 0xFFFFFFFFL);
        return new Field(tag, TYPE_FLOAT, 1, buf.toByteArray(), false);
    }

    private static Field rationalField(int tag, long numerator, long denominator) {
        Buf buf = new Buf(0);
        buf.u32(numerator);
        buf.u32(denominator);
        return new Field(tag, TYPE_RATIONAL, 1, buf.toByteArray(), false);
    }

    private static Field asciiField(int tag, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = Arrays.copyOf(bytes, bytes.length + 1);   // NUL-terminated
        return new Field(tag, TYPE_ASCII, payload.length, payload, false);
    }

    /** Little-endian byte sink whose positions are absolute in the file. */
    private static final class Buf {

        private final long origin;
        private byte[] data = new byte[1 << 16];
        private int size;

        Buf(long origin) {
            this.origin = origin;
        }

        long position() {
            return origin + size;
        }

        long length() {
            return size;
        }

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
            if ((position() & 1) != 0) {
                u8(0);
            }
        }

        void patchU64(long position, long value) {
            int at = (int) (position - origin);
            for (int i = 0; i < 8; i++) {
                data[at + i] = (byte) (value >>> (8 * i));
            }
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
