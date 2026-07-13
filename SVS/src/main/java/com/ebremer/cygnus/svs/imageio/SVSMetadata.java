package com.ebremer.cygnus.svs.imageio;

import com.ebremer.cygnus.svs.AperioImageDescription;
import com.ebremer.cygnus.svs.SVSDirectory;
import com.ebremer.cygnus.svs.SVSStructure;
import com.ebremer.cygnus.svs.Tiff;
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
 * carrying the level's geometry, the Aperio properties parsed out of its
 * {@code ImageDescription}, and the slide's associated images.
 *
 * <p>The pixel size in the standard tree is derived from the scanner's
 * {@code MPP} property, scaled by the level's downsample.</p>
 */
public final class SVSMetadata extends IIOMetadata {

    public static final String NATIVE_FORMAT = "com_ebremer_cygnus_svs_1.0";

    private final SVSStructure structure;
    private final int level;
    private final SVSDirectory dir;

    SVSMetadata(SVSStructure structure, int level, SVSDirectory dir) {
        super(true, NATIVE_FORMAT, null, null, null);
        this.structure = structure;
        this.level = level;
        this.dir = dir;
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
        IIOMetadataNode root = new IIOMetadataNode(NATIVE_FORMAT);

        IIOMetadataNode levelNode = new IIOMetadataNode("Level");
        levelNode.setAttribute("index", Integer.toString(level));
        levelNode.setAttribute("tiffDirectory", Integer.toString(dir.index()));
        levelNode.setAttribute("width", Integer.toString(dir.width()));
        levelNode.setAttribute("height", Integer.toString(dir.height()));
        levelNode.setAttribute("downsample", Double.toString(structure.downsample(level)));
        if (dir.isTiled()) {
            levelNode.setAttribute("tileWidth", Integer.toString(dir.tileWidth()));
            levelNode.setAttribute("tileHeight", Integer.toString(dir.tileHeight()));
        }
        levelNode.setAttribute("compression", Integer.toString(dir.compression()));
        levelNode.setAttribute("compressionName", Tiff.compressionName(dir.compression()));
        root.appendChild(levelNode);

        AperioImageDescription description = dir.description();
        IIOMetadataNode descriptionNode = new IIOMetadataNode("ImageDescription");
        descriptionNode.setAttribute("value", description.raw());
        root.appendChild(descriptionNode);

        IIOMetadataNode properties = new IIOMetadataNode("Properties");
        for (Map.Entry<String, String> property : description.properties().entrySet()) {
            IIOMetadataNode node = new IIOMetadataNode("Property");
            node.setAttribute("name", property.getKey());
            node.setAttribute("value", property.getValue());
            properties.appendChild(node);
        }
        root.appendChild(properties);

        IIOMetadataNode associated = new IIOMetadataNode("AssociatedImages");
        for (Map.Entry<String, SVSDirectory> image : structure.associatedImages().entrySet()) {
            IIOMetadataNode node = new IIOMetadataNode("AssociatedImage");
            node.setAttribute("name", image.getKey());
            node.setAttribute("tiffDirectory", Integer.toString(image.getValue().index()));
            node.setAttribute("width", Integer.toString(image.getValue().width()));
            node.setAttribute("height", Integer.toString(image.getValue().height()));
            associated.appendChild(node);
        }
        root.appendChild(associated);

        return root;
    }

    @Override
    protected IIOMetadataNode getStandardChromaNode() {
        IIOMetadataNode chroma = new IIOMetadataNode("Chroma");
        IIOMetadataNode colorSpace = new IIOMetadataNode("ColorSpaceType");
        colorSpace.setAttribute("name", dir.samplesPerPixel() >= 3 ? "RGB" : "GRAY");
        chroma.appendChild(colorSpace);
        IIOMetadataNode channels = new IIOMetadataNode("NumChannels");
        channels.setAttribute("value", Integer.toString(dir.samplesPerPixel()));
        chroma.appendChild(channels);
        return chroma;
    }

    @Override
    protected IIOMetadataNode getStandardCompressionNode() {
        IIOMetadataNode compression = new IIOMetadataNode("Compression");
        IIOMetadataNode name = new IIOMetadataNode("CompressionTypeName");
        name.setAttribute("value", Tiff.compressionName(dir.compression()));
        compression.appendChild(name);
        IIOMetadataNode lossless = new IIOMetadataNode("Lossless");
        lossless.setAttribute("value", Boolean.toString(
                dir.compression() == Tiff.COMPRESSION_NONE
                        || dir.compression() == Tiff.COMPRESSION_LZW));
        compression.appendChild(lossless);
        return compression;
    }

    @Override
    protected IIOMetadataNode getStandardDimensionNode() {
        OptionalDouble mpp = structure.description().micronsPerPixel();
        if (mpp.isEmpty()) {
            return null;
        }
        // The standard tree wants millimetres per pixel; MPP is microns per pixel at level 0.
        String size = Double.toString(mpp.getAsDouble() * structure.downsample(level) / 1000.0);
        IIOMetadataNode dimension = new IIOMetadataNode("Dimension");
        IIOMetadataNode horizontal = new IIOMetadataNode("HorizontalPixelSize");
        horizontal.setAttribute("value", size);
        dimension.appendChild(horizontal);
        IIOMetadataNode vertical = new IIOMetadataNode("VerticalPixelSize");
        vertical.setAttribute("value", size);
        dimension.appendChild(vertical);
        return dimension;
    }
}
