package com.ebremer.cygnus.jpegxl.container;

import com.ebremer.cygnus.jpegxl.codestream.ColourEncoding;
import com.ebremer.cygnus.jpegxl.io.BitWriter;
import com.ebremer.cygnus.jpegxl.io.Bits;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * The gain-map bundle carried in a {@code jhgm} box (ISO/IEC 18181-2
 * amendment 1), matching libjxl's {@code JxlGainMapBundle} serialisation:
 * a version byte, an ISO 21496-1 metadata blob, an optional colour encoding
 * (the codestream bundle encoding, byte-padded), an optional compressed ICC
 * profile for the alternate image (kept opaque here; it uses the codestream
 * ICC-stream format), and the gain map itself as a naked JPEG XL codestream
 * filling the rest of the box.
 */
public final class GainMap {

    public int version;
    /** ISO 21496-1 gain-map metadata, at most 65535 bytes. */
    public byte[] metadata = new byte[0];
    /** Colour encoding of the gain map, or null when unspecified. */
    public ColourEncoding colourEncoding;
    /** Compressed ICC of the alternate image (opaque), possibly empty. */
    public byte[] altIcc = new byte[0];
    /** The gain map image as a bare JPEG XL codestream. */
    public byte[] gainMapCodestream = new byte[0];

    /** Serialises the bundle as the payload of a {@code jhgm} box. */
    public byte[] toBytes() throws IOException {
        if (metadata.length > 0xffff) {
            throw new IOException("gain-map metadata over 65535 bytes");
        }
        byte[] ce = new byte[0];
        if (colourEncoding != null) {
            BitWriter w = new BitWriter();
            colourEncoding.write(w);
            w.zeroPadToByte();
            ce = w.toByteArray();
            if (ce.length > 255) {
                throw new IOException("colour encoding over 255 bytes");
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(version);
        out.write(metadata.length >>> 8);
        out.write(metadata.length);
        out.writeBytes(metadata);
        out.write(ce.length);
        out.writeBytes(ce);
        out.write(altIcc.length >>> 24);
        out.write(altIcc.length >>> 16);
        out.write(altIcc.length >>> 8);
        out.write(altIcc.length);
        out.writeBytes(altIcc);
        out.writeBytes(gainMapCodestream);
        return out.toByteArray();
    }

    /** Parses a {@code jhgm} box payload. */
    public static GainMap parse(byte[] payload) throws IOException {
        GainMap gm = new GainMap();
        int pos = 0;
        if (payload.length < 8) {
            throw new IOException("gain-map bundle too short");
        }
        gm.version = payload[pos++] & 0xff;
        int metaSize = ((payload[pos] & 0xff) << 8) | (payload[pos + 1] & 0xff);
        pos += 2;
        if (pos + metaSize > payload.length) {
            throw new IOException("truncated gain-map metadata");
        }
        gm.metadata = java.util.Arrays.copyOfRange(payload, pos, pos + metaSize);
        pos += metaSize;
        if (pos >= payload.length) {
            throw new IOException("truncated gain-map bundle");
        }
        int ceSize = payload[pos++] & 0xff;
        if (pos + ceSize > payload.length) {
            throw new IOException("truncated gain-map colour encoding");
        }
        if (ceSize > 0) {
            gm.colourEncoding = ColourEncoding.read(new Bits(payload, pos, pos + ceSize));
            pos += ceSize;
        }
        if (pos + 4 > payload.length) {
            throw new IOException("truncated gain-map ICC size");
        }
        long iccSize = ((payload[pos] & 0xffL) << 24) | ((payload[pos + 1] & 0xffL) << 16)
                | ((payload[pos + 2] & 0xffL) << 8) | (payload[pos + 3] & 0xffL);
        pos += 4;
        if (pos + iccSize > payload.length) {
            throw new IOException("truncated gain-map ICC");
        }
        gm.altIcc = java.util.Arrays.copyOfRange(payload, pos, (int) (pos + iccSize));
        pos += (int) iccSize;
        gm.gainMapCodestream = java.util.Arrays.copyOfRange(payload, pos, payload.length);
        return gm;
    }
}
