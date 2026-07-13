package com.ebremer.cygnus.ndpi;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import javax.imageio.IIOException;
import javax.imageio.stream.ImageInputStream;

/**
 * One directory of an NDPI file: its fields, and the slide-level meaning
 * Hamamatsu gives them.
 *
 * <p>Values are read from the stream when asked for rather than up front, since
 * a level's MCU-start table has an entry per tile and there is no reason to read
 * one for a level nobody opens.</p>
 */
public final class NdpiDirectory {

    static final int TYPE_BYTE = 1;
    static final int TYPE_ASCII = 2;
    static final int TYPE_SHORT = 3;
    static final int TYPE_LONG = 4;
    static final int TYPE_RATIONAL = 5;
    static final int TYPE_SBYTE = 6;
    static final int TYPE_UNDEFINED = 7;
    static final int TYPE_SSHORT = 8;
    static final int TYPE_SLONG = 9;
    static final int TYPE_SRATIONAL = 10;
    static final int TYPE_FLOAT = 11;
    static final int TYPE_DOUBLE = 12;
    static final int TYPE_IFD = 13;
    static final int TYPE_LONG8 = 16;
    static final int TYPE_SLONG8 = 17;
    static final int TYPE_IFD8 = 18;

    /** Refuses to allocate for an implausible value count. */
    private static final int MAX_VALUES = 1 << 24;

    /** {@code value} is the inline bits when {@code inline}, otherwise the 64-bit offset. */
    record Field(int type, long count, long value, boolean inline) {
    }

    private final ImageInputStream stream;
    private final int index;
    private final Map<Integer, Field> fields;

    NdpiDirectory(ImageInputStream stream, int index, Map<Integer, Field> fields) {
        this.stream = stream;
        this.index = index;
        this.fields = fields;
    }

    /** Position of this directory in the file. */
    public int index() {
        return index;
    }

    public Set<Integer> tags() {
        return Collections.unmodifiableSet(fields.keySet());
    }

    public boolean has(int tag) {
        return fields.containsKey(tag);
    }

    // ---- fields ----

    /** Every value of an integer field, or null when the tag is absent. */
    public long[] integers(int tag) throws IOException {
        Field field = fields.get(tag);
        if (field == null || !isInteger(field.type())) {
            return null;
        }
        int count = checkedCount(field, tag);
        long[] values = new long[count];
        if (field.inline()) {
            for (int i = 0; i < count; i++) {
                values[i] = inlineElement(field, i);
            }
        } else {
            seek(field.value());
            for (int i = 0; i < count; i++) {
                values[i] = readInteger(field.type());
            }
        }
        return values;
    }

    /** The field's first value, as an integer. */
    public OptionalLong integer(int tag) throws IOException {
        long[] values = integers(tag);
        return values == null || values.length == 0
                ? OptionalLong.empty() : OptionalLong.of(values[0]);
    }

    /** Every value of a real-valued field, or null when the tag is absent. */
    public double[] reals(int tag) throws IOException {
        Field field = fields.get(tag);
        if (field == null) {
            return null;
        }
        if (isInteger(field.type())) {
            long[] integers = integers(tag);
            double[] values = new double[integers.length];
            for (int i = 0; i < integers.length; i++) {
                values[i] = integers[i];
            }
            return values;
        }
        int count = checkedCount(field, tag);
        double[] values = new double[count];
        switch (field.type()) {
            case TYPE_FLOAT -> {
                if (field.inline()) {
                    for (int i = 0; i < count; i++) {
                        values[i] = Float.intBitsToFloat((int) (field.value() >>> (32 * i)));
                    }
                } else {
                    seek(field.value());
                    for (int i = 0; i < count; i++) {
                        values[i] = Float.intBitsToFloat(stream.readInt());
                    }
                }
            }
            case TYPE_DOUBLE -> {
                seek(field.value());
                for (int i = 0; i < count; i++) {
                    values[i] = stream.readDouble();
                }
            }
            case TYPE_RATIONAL -> {
                seek(field.value());
                for (int i = 0; i < count; i++) {
                    long numerator = stream.readUnsignedInt();
                    long denominator = stream.readUnsignedInt();
                    values[i] = denominator == 0 ? 0 : (double) numerator / denominator;
                }
            }
            case TYPE_SRATIONAL -> {
                seek(field.value());
                for (int i = 0; i < count; i++) {
                    int numerator = stream.readInt();
                    int denominator = stream.readInt();
                    values[i] = denominator == 0 ? 0 : (double) numerator / denominator;
                }
            }
            default -> {
                return null;
            }
        }
        return values;
    }

    /** The field's first value, as a real number. */
    public OptionalDouble real(int tag) throws IOException {
        double[] values = reals(tag);
        return values == null || values.length == 0
                ? OptionalDouble.empty() : OptionalDouble.of(values[0]);
    }

    /** The text of an ASCII field, with its trailing NULs removed. */
    public String ascii(int tag) throws IOException {
        Field field = fields.get(tag);
        if (field == null || (field.type() != TYPE_ASCII && field.type() != TYPE_UNDEFINED)) {
            return null;
        }
        int count = checkedCount(field, tag);
        byte[] bytes = new byte[count];
        if (field.inline()) {
            for (int i = 0; i < count; i++) {
                bytes[i] = (byte) (field.value() >>> (8 * i));
            }
        } else {
            seek(field.value());
            stream.readFully(bytes);
        }
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.ISO_8859_1);
    }

    // ---- what the fields mean ----

    public int width() throws IOException {
        return (int) require(Ndpi.TAG_IMAGE_WIDTH, "ImageWidth");
    }

    public int height() throws IOException {
        return (int) require(Ndpi.TAG_IMAGE_LENGTH, "ImageLength");
    }

    /** Every directory is a single strip holding a single JPEG. */
    public long stripOffset() throws IOException {
        return require(Ndpi.TAG_STRIP_OFFSETS, "StripOffsets");
    }

    public long stripByteCount() throws IOException {
        return require(Ndpi.TAG_STRIP_BYTE_COUNTS, "StripByteCounts");
    }

    public int compression() throws IOException {
        return (int) integer(Ndpi.TAG_COMPRESSION).orElse(Ndpi.COMPRESSION_NONE);
    }

    public int samplesPerPixel() throws IOException {
        return (int) integer(Ndpi.TAG_SAMPLES_PER_PIXEL).orElse(1);
    }

    /** Objective power: positive for a pyramid level, negative for an associated image. */
    public OptionalDouble sourceLens() throws IOException {
        return real(Ndpi.TAG_SOURCE_LENS);
    }

    /** Z-plane; only plane 0 is the focused image. */
    public long focalPlane() throws IOException {
        return integer(Ndpi.TAG_FOCAL_PLANE).orElse(0);
    }

    /** A focused pyramid level, as opposed to an associated image or a focus plane. */
    public boolean isLevel() throws IOException {
        OptionalDouble lens = sourceLens();
        return lens.isPresent() && lens.getAsDouble() > 0 && focalPlane() == 0;
    }

    /** One of a level's out-of-focus z-planes, which are not part of the pyramid. */
    public boolean isFocusPlane() throws IOException {
        OptionalDouble lens = sourceLens();
        return lens.isPresent() && lens.getAsDouble() > 0 && focalPlane() != 0;
    }

    /** The scanner's own key/value properties, from the ASCII property map. */
    public Map<String, String> propertyMap() throws IOException {
        String text = ascii(Ndpi.TAG_PROPERTY_MAP);
        if (text == null || text.isEmpty()) {
            return Map.of();
        }
        Map<String, String> properties = new LinkedHashMap<>();
        for (String record : text.split("\\R")) {
            int equals = record.indexOf('=');
            if (equals > 0 && equals + 1 < record.length()) {
                properties.put(record.substring(0, equals), record.substring(equals + 1));
            }
        }
        return Collections.unmodifiableMap(properties);
    }

    public OptionalDouble micronsPerPixelX() throws IOException {
        return micronsPerPixel(Ndpi.TAG_X_RESOLUTION);
    }

    public OptionalDouble micronsPerPixelY() throws IOException {
        return micronsPerPixel(Ndpi.TAG_Y_RESOLUTION);
    }

    private OptionalDouble micronsPerPixel(int tag) throws IOException {
        OptionalDouble resolution = real(tag);
        if (resolution.isEmpty() || resolution.getAsDouble() <= 0) {
            return OptionalDouble.empty();
        }
        double micronsPerUnit = switch ((int) integer(Ndpi.TAG_RESOLUTION_UNIT).orElse(0)) {
            case 2 -> 25400.0;   // inch
            case 3 -> 10000.0;   // centimetre
            default -> 0;        // no unit: the resolution is a bare ratio
        };
        return micronsPerUnit == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of(micronsPerUnit / resolution.getAsDouble());
    }

    @Override
    public String toString() {
        return "NdpiDirectory[" + index + " " + fields.size() + " tags]";
    }

    // ---- field decoding ----

    /**
     * Positions the shared stream for reading a value. The byte order has to be
     * set every time: the stream belongs to the reader, which hands it to other
     * things between one lazy read and the next, and NDPI is little-endian.
     */
    private void seek(long offset) throws IOException {
        stream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        stream.seek(offset);
    }

    private long require(int tag, String name) throws IOException {
        OptionalLong value = integer(tag);
        if (value.isEmpty()) {
            throw new IIOException("NDPI directory " + index + " has no " + name);
        }
        return value.getAsLong();
    }

    private int checkedCount(Field field, int tag) throws IIOException {
        if (field.count() < 0 || field.count() > MAX_VALUES) {
            throw new IIOException("NDPI tag " + tag + " claims " + field.count() + " values");
        }
        return (int) field.count();
    }

    private long readInteger(int type) throws IOException {
        return switch (type) {
            case TYPE_BYTE, TYPE_UNDEFINED -> stream.readUnsignedByte();
            case TYPE_SBYTE -> stream.readByte();
            case TYPE_SHORT -> stream.readUnsignedShort();
            case TYPE_SSHORT -> stream.readShort();
            case TYPE_LONG, TYPE_IFD -> stream.readUnsignedInt();
            case TYPE_SLONG -> stream.readInt();
            case TYPE_LONG8, TYPE_SLONG8, TYPE_IFD8 -> stream.readLong();
            default -> throw new IIOException("Not an integer TIFF type: " + type);
        };
    }

    /** Element {@code i} of a value packed into the entry's 4 bytes, plus its extension. */
    private static long inlineElement(Field field, int i) {
        long raw = field.value();
        return switch (field.type()) {
            case TYPE_BYTE, TYPE_UNDEFINED -> (raw >>> (8 * i)) & 0xFF;
            case TYPE_SBYTE -> (byte) (raw >>> (8 * i));
            case TYPE_SHORT -> (raw >>> (16 * i)) & 0xFFFF;
            case TYPE_SSHORT -> (short) (raw >>> (16 * i));
            case TYPE_LONG, TYPE_IFD -> (raw >>> (32 * i)) & 0xFFFFFFFFL;
            case TYPE_SLONG -> (int) (raw >>> (32 * i));
            // Only ever widened from a LONG, so there is exactly one value.
            case TYPE_LONG8, TYPE_SLONG8, TYPE_IFD8 -> raw;
            default -> 0;
        };
    }

    private static boolean isInteger(int type) {
        return switch (type) {
            case TYPE_BYTE, TYPE_SBYTE, TYPE_UNDEFINED, TYPE_SHORT, TYPE_SSHORT,
                 TYPE_LONG, TYPE_SLONG, TYPE_IFD, TYPE_LONG8, TYPE_SLONG8, TYPE_IFD8 -> true;
            default -> false;
        };
    }

    /** Bytes per value of a TIFF field type; 0 when the type is unknown. */
    static int typeSize(int type) {
        return switch (type) {
            case TYPE_BYTE, TYPE_ASCII, TYPE_SBYTE, TYPE_UNDEFINED -> 1;
            case TYPE_SHORT, TYPE_SSHORT -> 2;
            case TYPE_LONG, TYPE_SLONG, TYPE_FLOAT, TYPE_IFD -> 4;
            case TYPE_RATIONAL, TYPE_SRATIONAL, TYPE_DOUBLE, TYPE_LONG8, TYPE_SLONG8,
                 TYPE_IFD8 -> 8;
            default -> 0;
        };
    }
}
