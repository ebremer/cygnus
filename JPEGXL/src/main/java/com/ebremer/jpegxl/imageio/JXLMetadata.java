package com.ebremer.jpegxl.imageio;

import com.ebremer.jpegxl.codestream.ImageMetadata;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import org.w3c.dom.Node;

/**
 * Read-only image metadata. Exposes both the standard (plug-in neutral)
 * format and a native {@code com_ebremer_jpegxl_1.0} tree that carries the
 * animation timebase and the frame's duration in ticks.
 */
public final class JXLMetadata extends IIOMetadata {

    /** Name of the native metadata format. */
    public static final String NATIVE_FORMAT = "com_ebremer_jpegxl_1.0";

    private final ImageMetadata meta;
    private final long frameDuration; // ticks; -1 when not applicable

    JXLMetadata(ImageMetadata meta) {
        this(meta, -1);
    }

    JXLMetadata(ImageMetadata meta, long frameDuration) {
        super(true, NATIVE_FORMAT, "com.ebremer.jpegxl.imageio.JXLMetadataFormat", null, null);
        this.meta = meta;
        this.frameDuration = frameDuration;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Node getAsTree(String formatName) {
        if (IIOMetadataFormatImpl.standardMetadataFormatName.equals(formatName)) {
            return getStandardTree();
        }
        if (NATIVE_FORMAT.equals(formatName)) {
            return getNativeTree();
        }
        throw new IllegalArgumentException("unsupported metadata format " + formatName);
    }

    private Node getNativeTree() {
        IIOMetadataNode root = new IIOMetadataNode(NATIVE_FORMAT);

        IIOMetadataNode image = new IIOMetadataNode("Image");
        image.setAttribute("bitsPerSample", Integer.toString(meta.bitDepth.bitsPerSample));
        image.setAttribute("floatingPoint", Boolean.toString(meta.bitDepth.floatingPoint));
        image.setAttribute("xybEncoded", Boolean.toString(meta.xybEncoded));
        image.setAttribute("orientation", Integer.toString(meta.orientation));
        image.setAttribute("extraChannels", Integer.toString(meta.numExtraChannels()));
        if (meta.previewWidth > 0) {
            image.setAttribute("previewWidth", Integer.toString(meta.previewWidth));
            image.setAttribute("previewHeight", Integer.toString(meta.previewHeight));
        }
        root.appendChild(image);

        if (meta.haveAnimation) {
            IIOMetadataNode animation = new IIOMetadataNode("Animation");
            animation.setAttribute("tpsNumerator", Integer.toString(meta.animTpsNumerator));
            animation.setAttribute("tpsDenominator", Integer.toString(meta.animTpsDenominator));
            animation.setAttribute("numLoops", Long.toString(meta.animNumLoops));
            animation.setAttribute("haveTimecodes", Boolean.toString(meta.animHaveTimecodes));
            root.appendChild(animation);

            if (frameDuration >= 0) {
                IIOMetadataNode frame = new IIOMetadataNode("Frame");
                frame.setAttribute("durationTicks", Long.toString(frameDuration));
                double seconds = meta.animTpsNumerator == 0
                        ? 0.0
                        : frameDuration * (double) meta.animTpsDenominator / meta.animTpsNumerator;
                frame.setAttribute("durationSeconds", Double.toString(seconds));
                root.appendChild(frame);
            }
        }
        return root;
    }

    @Override
    public void mergeTree(String formatName, Node root) {
        throw new IllegalStateException("metadata is read-only");
    }

    @Override
    public void reset() {
        throw new IllegalStateException("metadata is read-only");
    }

    @Override
    protected IIOMetadataNode getStandardChromaNode() {
        IIOMetadataNode chroma = new IIOMetadataNode("Chroma");
        IIOMetadataNode csType = new IIOMetadataNode("ColorSpaceType");
        csType.setAttribute("name", meta.colourEncoding.isGrey() ? "GRAY" : "RGB");
        chroma.appendChild(csType);
        IIOMetadataNode numChannels = new IIOMetadataNode("NumChannels");
        numChannels.setAttribute("value",
                Integer.toString(meta.colourChannelCount() + meta.numExtraChannels()));
        chroma.appendChild(numChannels);
        return chroma;
    }

    @Override
    protected IIOMetadataNode getStandardDataNode() {
        IIOMetadataNode data = new IIOMetadataNode("Data");
        IIOMetadataNode sampleFormat = new IIOMetadataNode("SampleFormat");
        sampleFormat.setAttribute("value",
                meta.bitDepth.floatingPoint ? "Real" : "UnsignedIntegral");
        data.appendChild(sampleFormat);
        IIOMetadataNode bits = new IIOMetadataNode("BitsPerSample");
        StringBuilder sb = new StringBuilder();
        int channels = meta.colourChannelCount() + meta.numExtraChannels();
        for (int i = 0; i < channels; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(i < meta.colourChannelCount()
                    ? meta.bitDepth.bitsPerSample
                    : meta.extraChannels.get(i - meta.colourChannelCount()).bitDepth.bitsPerSample);
        }
        bits.setAttribute("value", sb.toString());
        data.appendChild(bits);
        return data;
    }

    @Override
    protected IIOMetadataNode getStandardTransparencyNode() {
        int alphaIdx = meta.alphaChannelIndex();
        if (alphaIdx < 0) {
            return null;
        }
        IIOMetadataNode transparency = new IIOMetadataNode("Transparency");
        IIOMetadataNode alpha = new IIOMetadataNode("Alpha");
        alpha.setAttribute("value",
                meta.extraChannels.get(alphaIdx).alphaAssociated ? "premultiplied" : "nonpremultiplied");
        transparency.appendChild(alpha);
        return transparency;
    }

    @Override
    protected IIOMetadataNode getStandardDimensionNode() {
        IIOMetadataNode dimension = new IIOMetadataNode("Dimension");
        IIOMetadataNode orientation = new IIOMetadataNode("ImageOrientation");
        orientation.setAttribute("value", "Normal"); // orientation already applied on read
        dimension.appendChild(orientation);
        return dimension;
    }
}
