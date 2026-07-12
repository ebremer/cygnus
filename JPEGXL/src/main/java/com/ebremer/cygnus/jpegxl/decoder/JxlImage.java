package com.ebremer.cygnus.jpegxl.decoder;

import com.ebremer.cygnus.jpegxl.codestream.ImageMetadata;
import java.util.List;

/** A fully decoded JPEG XL image: metadata plus one or more frames. */
public final class JxlImage {

    public final ImageMetadata metadata;
    /** Dimensions after applying the metadata orientation. */
    public final int width;
    public final int height;
    public final List<JxlFrame> frames;
    /** The decoded preview frame, or null when the image has none. */
    public JxlFrame preview;

    /** Exif TIFF stream from the container, when decoded from bytes; else null. */
    public byte[] exif;
    /** XMP packet from the container's {@code xml } box, when present. */
    public byte[] xmp;
    /** Gain-map bundle from the container's {@code jhgm} box, when present. */
    public com.ebremer.cygnus.jpegxl.container.GainMap gainMap;
    /**
     * Origin of the decoded frames in oriented image coordinates: (0, 0) for a
     * full decode; the requested rectangle's origin for a region decode, whose
     * frames then cover only that rectangle.
     */
    public final int regionX;
    public final int regionY;

    public JxlImage(ImageMetadata metadata, int width, int height, List<JxlFrame> frames) {
        this(metadata, width, height, frames, 0, 0);
    }

    public JxlImage(ImageMetadata metadata, int width, int height, List<JxlFrame> frames,
            int regionX, int regionY) {
        this.metadata = metadata;
        this.width = width;
        this.height = height;
        this.frames = frames;
        this.regionX = regionX;
        this.regionY = regionY;
    }

    public int numColourChannels() {
        return metadata.colourChannelCount();
    }
}
