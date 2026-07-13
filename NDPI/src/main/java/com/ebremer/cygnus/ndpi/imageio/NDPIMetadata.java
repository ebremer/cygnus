package com.ebremer.cygnus.ndpi.imageio;

import com.ebremer.cygnus.ndpi.Ndpi;
import com.ebremer.cygnus.ndpi.NdpiDirectory;
import com.ebremer.cygnus.ndpi.NdpiStructure;
import com.ebremer.cygnus.ndpi.NdpiTileDecoder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.OptionalDouble;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormat;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import org.w3c.dom.Node;

/**
 * Read-only metadata for one pyramid level: the standard
 * {@code javax_imageio_1.0} tree, and a native tree ({@value #NATIVE_FORMAT})
 * carrying the level's geometry, the scanner's property map, and the images it
 * took of the slide itself.
 */
public final class NDPIMetadata extends IIOMetadata {

    public static final String NATIVE_FORMAT = "com_ebremer_cygnus_ndpi_1.0";

    private final NdpiStructure structure;
    private final int level;
    private final NdpiDirectory directory;
    private final NdpiTileDecoder decoder;

    NDPIMetadata(NdpiStructure structure, int level, NdpiDirectory directory,
                 NdpiTileDecoder decoder) {
        super(true, NATIVE_FORMAT, null, null, null);
        this.structure = structure;
        this.level = level;
        this.directory = directory;
        this.decoder = decoder;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public IIOMetadataFormat getMetadataFormat(String formatName) {
        if (IIOMetadataFormatImpl.standardMetadataFormatName.equals(formatName)) {
            return IIOMetadataFormatImpl.getStandardFormatInstance();
        }
        if (NATIVE_FORMAT.equals(formatName)) {
            return null; // no machine-readable descriptor
        }
        throw new IllegalArgumentException("Unsupported format " + formatName);
    }

    @Override
    public Node getAsTree(String formatName) {
        if (IIOMetadataFormatImpl.standardMetadataFormatName.equals(formatName)) {
            return getStandardTree();
        }
        if (NATIVE_FORMAT.equals(formatName)) {
            return nativeTree();
        }
        throw new IllegalArgumentException("Unsupported format " + formatName);
    }

    @Override
    public void mergeTree(String formatName, Node root) {
        throw new IllegalStateException("Metadata is read-only");
    }

    @Override
    public void reset() {
        throw new IllegalStateException("Metadata is read-only");
    }

    private Node nativeTree() {
        try {
            IIOMetadataNode root = new IIOMetadataNode(NATIVE_FORMAT);

            IIOMetadataNode levelNode = new IIOMetadataNode("Level");
            levelNode.setAttribute("index", Integer.toString(level));
            levelNode.setAttribute("directory", Integer.toString(directory.index()));
            levelNode.setAttribute("width", Integer.toString(decoder.width()));
            levelNode.setAttribute("height", Integer.toString(decoder.height()));
            levelNode.setAttribute("downsample", Double.toString(structure.downsample(level)));
            levelNode.setAttribute("tileWidth", Integer.toString(decoder.tileWidth()));
            levelNode.setAttribute("tileHeight", Integer.toString(decoder.tileHeight()));
            directory.sourceLens().ifPresent(lens ->
                    levelNode.setAttribute("sourceLens", Double.toString(lens)));
            root.appendChild(levelNode);

            IIOMetadataNode properties = new IIOMetadataNode("Properties");
            for (Map.Entry<String, String> property : directory.propertyMap().entrySet()) {
                IIOMetadataNode node = new IIOMetadataNode("Property");
                node.setAttribute("name", property.getKey());
                node.setAttribute("value", property.getValue());
                properties.appendChild(node);
            }
            root.appendChild(properties);

            String reference = directory.ascii(Ndpi.TAG_REFERENCE);
            if (reference != null && !reference.isEmpty()) {
                IIOMetadataNode node = new IIOMetadataNode("Reference");
                node.setAttribute("value", reference);
                root.appendChild(node);
            }

            IIOMetadataNode associated = new IIOMetadataNode("AssociatedImages");
            for (Map.Entry<String, NdpiDirectory> image
                    : structure.associatedImages().entrySet()) {
                IIOMetadataNode node = new IIOMetadataNode("AssociatedImage");
                node.setAttribute("name", image.getKey());
                node.setAttribute("directory", Integer.toString(image.getValue().index()));
                node.setAttribute("width", Integer.toString(image.getValue().width()));
                node.setAttribute("height", Integer.toString(image.getValue().height()));
                associated.appendChild(node);
            }
            root.appendChild(associated);

            return root;
        } catch (IOException e) {
            throw new UncheckedIOException("Reading NDPI metadata", e);
        }
    }

    @Override
    protected IIOMetadataNode getStandardChromaNode() {
        IIOMetadataNode chroma = new IIOMetadataNode("Chroma");
        IIOMetadataNode colorSpace = new IIOMetadataNode("ColorSpaceType");
        colorSpace.setAttribute("name", decoder.bands() >= 3 ? "RGB" : "GRAY");
        chroma.appendChild(colorSpace);
        IIOMetadataNode channels = new IIOMetadataNode("NumChannels");
        channels.setAttribute("value", Integer.toString(decoder.bands()));
        chroma.appendChild(channels);
        return chroma;
    }

    @Override
    protected IIOMetadataNode getStandardCompressionNode() {
        IIOMetadataNode compression = new IIOMetadataNode("Compression");
        IIOMetadataNode name = new IIOMetadataNode("CompressionTypeName");
        name.setAttribute("value", "JPEG");
        compression.appendChild(name);
        IIOMetadataNode lossless = new IIOMetadataNode("Lossless");
        lossless.setAttribute("value", "FALSE");
        compression.appendChild(lossless);
        return compression;
    }

    @Override
    protected IIOMetadataNode getStandardDimensionNode() {
        OptionalDouble horizontal;
        OptionalDouble vertical;
        try {
            horizontal = structure.micronsPerPixelX();
            vertical = structure.micronsPerPixelY();
        } catch (IOException e) {
            throw new UncheckedIOException("Reading NDPI resolution", e);
        }
        if (horizontal.isEmpty() && vertical.isEmpty()) {
            return null;
        }
        double scale;
        try {
            scale = structure.downsample(level);
        } catch (IOException e) {
            throw new UncheckedIOException("Reading NDPI level dimensions", e);
        }

        // The standard tree wants millimetres per pixel; the tags give microns at level 0.
        IIOMetadataNode dimension = new IIOMetadataNode("Dimension");
        horizontal.ifPresent(microns -> {
            IIOMetadataNode node = new IIOMetadataNode("HorizontalPixelSize");
            node.setAttribute("value", Double.toString(microns * scale / 1000.0));
            dimension.appendChild(node);
        });
        vertical.ifPresent(microns -> {
            IIOMetadataNode node = new IIOMetadataNode("VerticalPixelSize");
            node.setAttribute("value", Double.toString(microns * scale / 1000.0));
            dimension.appendChild(node);
        });
        return dimension;
    }
}
