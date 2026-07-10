package com.ebremer.jpegxl.decoder;

import com.ebremer.jpegxl.codestream.ImageMetadata;
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

    public JxlImage(ImageMetadata metadata, int width, int height, List<JxlFrame> frames) {
        this.metadata = metadata;
        this.width = width;
        this.height = height;
        this.frames = frames;
    }

    public int numColourChannels() {
        return metadata.colourChannelCount();
    }
}
