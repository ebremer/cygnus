package com.ebremer.cygnus.ndpi;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.IIOException;
import javax.imageio.stream.ImageInputStream;

/**
 * Reads the directories of an NDPI file, which no TIFF library can.
 *
 * <p>An NDPI file is a classic little-endian TIFF — magic 42, 12-byte entries —
 * except that a slide is routinely larger than the 4 GiB a 32-bit offset
 * reaches. Hamamatsu's answer was to widen every value and offset to 64 bits
 * while keeping the classic layout, by appending the <em>high</em> halves after
 * the directory:</p>
 *
 * <pre>
 * offset:                     uint16   entry count
 * offset + 2:                 count x 12-byte entries (tag, type, count, low 32 bits)
 * offset + 2 + 12 * count:    uint64   next directory        &lt;- 8 bytes, not 4
 * offset + 10 + 12 * count:   count x uint32  high 32 bits, one per entry
 * </pre>
 *
 * <p>The pointer to the first directory is likewise 8 bytes rather than 4, so
 * it runs into what a TIFF reader would take for the start of the file's data.
 * A TIFF reader therefore reads an NDPI file's offsets truncated to 32 bits: it
 * gets away with it below 4 GiB and silently reads the wrong bytes above it,
 * which is why this class exists.</p>
 *
 * <p>Values are 64 bits the same way — a LONG whose extension is non-zero is a
 * LONG8 — and an ASCII value is always out of line, even when it would fit.</p>
 */
public final class NdpiTiff {

    private static final int TIFF_VERSION = 42;
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_DIRECTORIES = 4096;

    private final List<NdpiDirectory> directories;

    private NdpiTiff(List<NdpiDirectory> directories) {
        this.directories = List.copyOf(directories);
    }

    /** Every directory, in file order. */
    public List<NdpiDirectory> directories() {
        return directories;
    }

    /** Reads the directory chain. Restores the stream's byte order. */
    public static NdpiTiff read(ImageInputStream stream) throws IOException {
        ByteOrder order = stream.getByteOrder();
        try {
            long offset = readHeader(stream);
            List<NdpiDirectory> directories = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            long[] next = new long[1];
            while (offset != 0) {
                if (offset < 0 || !seen.add(offset)) {
                    throw new IIOException("NDPI directory chain loops at " + offset);
                }
                if (directories.size() >= MAX_DIRECTORIES) {
                    throw new IIOException("More than " + MAX_DIRECTORIES + " NDPI directories");
                }
                directories.add(readDirectory(stream, directories.size(), offset, next));
                offset = next[0];
            }
            if (directories.isEmpty()) {
                throw new IIOException("NDPI file has no directories");
            }
            return new NdpiTiff(directories);
        } finally {
            stream.setByteOrder(order);
        }
    }

    /** The offset of the first directory; sets the stream's byte order. */
    private static long readHeader(ImageInputStream stream) throws IOException {
        stream.seek(0);
        int b0 = stream.read();
        int b1 = stream.read();
        if (b0 != 'I' || b1 != 'I') {
            // Hamamatsu writes only little-endian, and the 64-bit widening below assumes it.
            throw new IIOException("Not a little-endian TIFF, so not NDPI");
        }
        stream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        int version = stream.readUnsignedShort();
        if (version != TIFF_VERSION) {
            throw new IIOException("NDPI is classic TIFF; found version " + version);
        }
        long offset = stream.readLong();   // 8 bytes, where a TIFF has 4
        if (offset < 12) {
            throw new IIOException("Bad NDPI first-directory offset " + offset);
        }
        return offset;
    }

    private static NdpiDirectory readDirectory(ImageInputStream stream, int index,
                                               long offset, long[] next) throws IOException {
        stream.seek(offset);
        int count = stream.readUnsignedShort();
        if (count == 0 || count > MAX_ENTRIES) {
            throw new IIOException("NDPI directory " + index + " has " + count + " entries");
        }

        // The high halves of every value and offset, one word per entry, past the
        // 8-byte next-directory pointer. This is the whole trick.
        long[] high = new long[count];
        stream.seek(offset + 2 + 12L * count + 8);
        for (int i = 0; i < count; i++) {
            high[i] = stream.readUnsignedInt();
        }

        stream.seek(offset + 2);
        Map<Integer, NdpiDirectory.Field> fields = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int tag = stream.readUnsignedShort();
            int type = stream.readUnsignedShort();
            long valueCount = stream.readUnsignedInt();
            long low = stream.readUnsignedInt();

            int size = NdpiDirectory.typeSize(type);
            boolean inline = size != 0
                    && (long) size * valueCount <= 4
                    && type != NdpiDirectory.TYPE_ASCII;   // NDPI never inlines ASCII

            long value;
            if (!inline) {
                value = low | (high[i] << 32);
            } else if (high[i] == 0) {
                value = low;
            } else {
                // A value carrying a high half is simply a wider value.
                if (type == NdpiDirectory.TYPE_LONG) {
                    type = NdpiDirectory.TYPE_LONG8;
                } else if (type == NdpiDirectory.TYPE_SLONG) {
                    type = NdpiDirectory.TYPE_SLONG8;
                } else {
                    throw new IIOException("NDPI value extension on tag " + tag
                            + " of type " + type + ", which cannot be widened");
                }
                value = low | (high[i] << 32);
            }
            fields.put(tag, new NdpiDirectory.Field(type, valueCount, value, inline));
        }

        next[0] = stream.readLong();   // 8 bytes, where a TIFF has 4
        return new NdpiDirectory(stream, index, fields);
    }

    /**
     * Whether the stream holds an NDPI file: a little-endian classic TIFF whose
     * first directory — found through the 64-bit pointer — carries
     * {@link Ndpi#TAG_FORMAT_FLAG}. Restores the stream's position and byte
     * order.
     */
    public static boolean isNdpi(ImageInputStream stream) throws IOException {
        ByteOrder order = stream.getByteOrder();
        stream.mark();
        try {
            return sniff(stream);
        } catch (EOFException | IndexOutOfBoundsException e) {
            return false;
        } finally {
            stream.reset();
            stream.setByteOrder(order);
        }
    }

    private static boolean sniff(ImageInputStream stream) throws IOException {
        if (stream.read() != 'I' || stream.read() != 'I') {
            return false;
        }
        stream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        if (stream.readUnsignedShort() != TIFF_VERSION) {
            return false;
        }
        // In an ordinary TIFF the high half of this is the start of the file's own data,
        // so it reads as an implausible offset and the seek below runs off the end.
        long offset = stream.readLong();
        if (offset < 12) {
            return false;
        }
        stream.seek(offset);
        int count = stream.readUnsignedShort();
        if (count == 0 || count > MAX_ENTRIES) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            int tag = stream.readUnsignedShort();
            stream.readUnsignedShort();   // type
            stream.readUnsignedInt();     // count
            stream.readUnsignedInt();     // value or offset
            if (tag == Ndpi.TAG_FORMAT_FLAG) {
                return true;
            }
        }
        return false;
    }
}
