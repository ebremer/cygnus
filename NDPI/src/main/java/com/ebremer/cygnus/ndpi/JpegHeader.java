package com.ebremer.cygnus.ndpi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import javax.imageio.IIOException;
import javax.imageio.stream.ImageInputStream;

/**
 * The header of a level's JPEG — everything from SOI up to and including the
 * SOS marker, i.e. the bytes before the entropy-coded data starts.
 *
 * <p>What matters about it in an NDPI file is the restart interval and the MCU
 * size, which together say where the tile boundaries are, and the SOF's
 * position, so the declared image size can be replaced. That size is not to be
 * believed: a level is routinely larger than the 65535 a SOF can express, and
 * Hamamatsu writes a zero (or an over-large value) instead.</p>
 */
public final class JpegHeader {

    private static final int MAX_HEADER_BYTES = 1 << 20;

    private final byte[] bytes;
    private final int sofOffset;
    private final int width;
    private final int height;
    private final int componentsInFrame;
    private final int componentsInScan;
    private final int maxSamplingX;
    private final int maxSamplingY;
    private final int restartInterval;

    private JpegHeader(byte[] bytes, int sofOffset, int width, int height,
                       int componentsInFrame, int componentsInScan,
                       int maxSamplingX, int maxSamplingY, int restartInterval) {
        this.bytes = bytes;
        this.sofOffset = sofOffset;
        this.width = width;
        this.height = height;
        this.componentsInFrame = componentsInFrame;
        this.componentsInScan = componentsInScan;
        this.maxSamplingX = maxSamplingX;
        this.maxSamplingY = maxSamplingY;
        this.restartInterval = restartInterval;
    }

    /** Reads the header of the JPEG starting at {@code offset}. */
    public static JpegHeader read(ImageInputStream stream, long offset, long limit)
            throws IOException {
        // JPEG is big-endian, and the stream is shared with the directory reads, which
        // leave it little-endian because NDPI is.
        stream.setByteOrder(ByteOrder.BIG_ENDIAN);
        stream.seek(offset);
        ByteArrayOutputStream header = new ByteArrayOutputStream(1024);

        if (marker(stream) != 0xD8) {
            throw new IIOException("JPEG at " + offset + " does not start with SOI");
        }
        header.write(0xFF);
        header.write(0xD8);

        int sofOffset = -1;
        int width = 0;
        int height = 0;
        int componentsInFrame = 0;
        int componentsInScan = 0;
        int maxSamplingX = 1;
        int maxSamplingY = 1;
        int restartInterval = 0;

        while (true) {
            if (stream.getStreamPosition() + 2 > limit || header.size() > MAX_HEADER_BYTES) {
                throw new IIOException("JPEG at " + offset + " has no scan");
            }
            int code = marker(stream);
            if (isStandalone(code)) {
                header.write(0xFF);
                header.write(code);
                continue;
            }

            int length = stream.readUnsignedShort();
            if (length < 2) {
                throw new IIOException("JPEG marker length " + length + " is too short");
            }
            byte[] segment = new byte[length - 2];
            stream.readFully(segment);

            int markerOffset = header.size();
            header.write(0xFF);
            header.write(code);
            header.write(length >> 8);
            header.write(length);
            header.write(segment, 0, segment.length);

            if (isStartOfFrame(code)) {
                if (segment.length < 6) {
                    throw new IIOException("Truncated JPEG frame header");
                }
                sofOffset = markerOffset;
                height = ((segment[1] & 0xFF) << 8) | (segment[2] & 0xFF);
                width = ((segment[3] & 0xFF) << 8) | (segment[4] & 0xFF);
                componentsInFrame = segment[5] & 0xFF;
                if (segment.length < 6 + 3 * componentsInFrame) {
                    throw new IIOException("Truncated JPEG frame header");
                }
                for (int c = 0; c < componentsInFrame; c++) {
                    int sampling = segment[7 + 3 * c] & 0xFF;
                    maxSamplingX = Math.max(maxSamplingX, sampling >> 4);
                    maxSamplingY = Math.max(maxSamplingY, sampling & 0x0F);
                }
            } else if (code == 0xDD) {                  // DRI
                if (segment.length < 2) {
                    throw new IIOException("Truncated JPEG restart interval");
                }
                restartInterval = ((segment[0] & 0xFF) << 8) | (segment[1] & 0xFF);
            } else if (code == 0xDA) {                  // SOS: the entropy data follows
                if (segment.length < 1) {
                    throw new IIOException("Truncated JPEG scan header");
                }
                componentsInScan = segment[0] & 0xFF;
                break;
            }
        }

        if (sofOffset < 0) {
            throw new IIOException("JPEG at " + offset + " has no frame header");
        }
        return new JpegHeader(header.toByteArray(), sofOffset, width, height,
                componentsInFrame, componentsInScan, maxSamplingX, maxSamplingY, restartInterval);
    }

    private static int marker(ImageInputStream stream) throws IOException {
        int lead = stream.readUnsignedByte();
        if (lead != 0xFF) {
            throw new IIOException(String.format(
                    "Expected a JPEG marker at %d, found 0x%02X",
                    stream.getStreamPosition() - 1, lead));
        }
        int code = stream.readUnsignedByte();
        while (code == 0xFF) {          // fill bytes
            code = stream.readUnsignedByte();
        }
        return code;
    }

    /** Markers that carry no segment, so nothing follows them to be skipped. */
    private static boolean isStandalone(int code) {
        return code == 0xD8 || code == 0xD9 || code == 0x01 || (code >= 0xD0 && code <= 0xD7);
    }

    private static boolean isStartOfFrame(int code) {
        return (code >= 0xC0 && code <= 0xC3)
                || (code >= 0xC5 && code <= 0xC7)
                || (code >= 0xC9 && code <= 0xCB)
                || (code >= 0xCD && code <= 0xCF);
    }

    /** Bytes from SOI through SOS: where the entropy data begins. */
    public int length() {
        return bytes.length;
    }

    /** Width the SOF declares, which for a full-size NDPI level is a lie. */
    public int width() {
        return width;
    }

    /** Height the SOF declares, which for a full-size NDPI level is a lie. */
    public int height() {
        return height;
    }

    /** MCUs between restart markers; 0 when the JPEG has none, and so cannot be tiled. */
    public int restartInterval() {
        return restartInterval;
    }

    public int components() {
        return componentsInFrame;
    }

    public int mcuWidth() {
        return componentsInScan > 1 ? maxSamplingX * 8 : 8;
    }

    public int mcuHeight() {
        return componentsInScan > 1 ? maxSamplingY * 8 : 8;
    }

    /**
     * The header with the SOF's dimensions replaced — the one edit that turns a
     * slice of a level's entropy data into a JPEG a decoder will accept.
     */
    public byte[] withSize(int width, int height) {
        byte[] patched = bytes.clone();
        patched[sofOffset + 5] = (byte) (height >> 8);
        patched[sofOffset + 6] = (byte) height;
        patched[sofOffset + 7] = (byte) (width >> 8);
        patched[sofOffset + 8] = (byte) width;
        return patched;
    }
}
