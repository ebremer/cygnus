package com.ebremer.jpegxl.imageio;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataFormatImpl;

/** Describes the native {@code com_ebremer_jpegxl_1.0} metadata tree. */
public final class JXLMetadataFormat extends IIOMetadataFormatImpl {

    private static final JXLMetadataFormat INSTANCE = new JXLMetadataFormat();

    public static JXLMetadataFormat getInstance() {
        return INSTANCE;
    }

    private JXLMetadataFormat() {
        super(JXLMetadata.NATIVE_FORMAT, CHILD_POLICY_SOME);

        addElement("Image", JXLMetadata.NATIVE_FORMAT, CHILD_POLICY_EMPTY);
        addAttribute("Image", "bitsPerSample", DATATYPE_INTEGER, true, null);
        addAttribute("Image", "floatingPoint", DATATYPE_BOOLEAN, true, null);
        addAttribute("Image", "xybEncoded", DATATYPE_BOOLEAN, true, null);
        addAttribute("Image", "orientation", DATATYPE_INTEGER, true, null);
        addAttribute("Image", "extraChannels", DATATYPE_INTEGER, true, null);
        addAttribute("Image", "previewWidth", DATATYPE_INTEGER, false, null);
        addAttribute("Image", "previewHeight", DATATYPE_INTEGER, false, null);

        addElement("Animation", JXLMetadata.NATIVE_FORMAT, CHILD_POLICY_EMPTY);
        addAttribute("Animation", "tpsNumerator", DATATYPE_INTEGER, true, null);
        addAttribute("Animation", "tpsDenominator", DATATYPE_INTEGER, true, null);
        addAttribute("Animation", "numLoops", DATATYPE_INTEGER, true, null);
        addAttribute("Animation", "haveTimecodes", DATATYPE_BOOLEAN, true, null);

        addElement("Frame", JXLMetadata.NATIVE_FORMAT, CHILD_POLICY_EMPTY);
        addAttribute("Frame", "durationTicks", DATATYPE_INTEGER, true, null);
        addAttribute("Frame", "durationSeconds", DATATYPE_DOUBLE, true, null);
    }

    @Override
    public boolean canNodeAppear(String elementName, ImageTypeSpecifier imageType) {
        return true;
    }
}
