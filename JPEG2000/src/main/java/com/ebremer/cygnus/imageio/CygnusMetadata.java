package com.ebremer.cygnus.imageio;

import com.ebremer.cygnus.codestream.Codestream;
import com.ebremer.cygnus.codestream.CodingStyle;
import com.ebremer.cygnus.codestream.Siz;
import com.ebremer.cygnus.decoder.DecodedImage;
import com.ebremer.cygnus.jp2.Jp2Info;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormat;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import org.w3c.dom.Node;

/**
 * Read-only image metadata for JPEG 2000 images: supports the standard
 * {@code javax_imageio_1.0} tree (chroma, data, dimension, compression,
 * transparency) and a native tree
 * ({@value #NATIVE_FORMAT}) exposing the main-header codestream
 * parameters (SIZ/COD/QCD), HT capability, and JP2 container information
 * (colour method, palette, channel definitions, capture/display
 * resolution).
 */
public final class CygnusMetadata extends IIOMetadata {

    public static final String NATIVE_FORMAT = "com_ebremer_cygnus_jpeg2000_1.0";

    private final DecodedImage shape;
    private final Codestream cs;
    private final Jp2Info jp2;   // may be null

    CygnusMetadata(DecodedImage shape, Codestream cs, Jp2Info jp2) {
        super(true, NATIVE_FORMAT, null, null, null);
        this.shape = shape;
        this.cs = cs;
        this.jp2 = jp2;
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

    // ---- standard tree ----

    @Override
    protected IIOMetadataNode getStandardChromaNode() {
        IIOMetadataNode chroma = new IIOMetadataNode("Chroma");
        IIOMetadataNode csType = new IIOMetadataNode("ColorSpaceType");
        int colour = shape.colourChannels.length >= 3 ? 3 : 1;
        csType.setAttribute("name", colour >= 3 ? "RGB" : "GRAY");
        chroma.appendChild(csType);
        IIOMetadataNode num = new IIOMetadataNode("NumChannels");
        num.setAttribute("value",
                Integer.toString(colour + (shape.alphaChannel >= 0 ? 1 : 0)));
        chroma.appendChild(num);
        IIOMetadataNode biz = new IIOMetadataNode("BlackIsZero");
        biz.setAttribute("value", "TRUE");
        chroma.appendChild(biz);
        return chroma;
    }

    @Override
    protected IIOMetadataNode getStandardDataNode() {
        IIOMetadataNode data = new IIOMetadataNode("Data");
        IIOMetadataNode planar = new IIOMetadataNode("PlanarConfiguration");
        planar.setAttribute("value", "PixelInterleaved");
        data.appendChild(planar);
        boolean allSigned = true;
        StringBuilder bits = new StringBuilder();
        int colour = shape.colourChannels.length >= 3 ? 3 : 1;
        for (int i = 0; i < colour; i++) {
            int c = shape.colourChannels[i];
            if (i > 0) {
                bits.append(' ');
            }
            bits.append(shape.depth[c]);
            allSigned &= shape.signed[c];
        }
        if (shape.alphaChannel >= 0) {
            bits.append(' ').append(shape.depth[shape.alphaChannel]);
            allSigned &= shape.signed[shape.alphaChannel];
        }
        IIOMetadataNode fmt = new IIOMetadataNode("SampleFormat");
        fmt.setAttribute("value", allSigned ? "SignedIntegral" : "UnsignedIntegral");
        data.appendChild(fmt);
        IIOMetadataNode bps = new IIOMetadataNode("BitsPerSample");
        bps.setAttribute("value", bits.toString());
        data.appendChild(bps);
        return data;
    }

    @Override
    protected IIOMetadataNode getStandardDimensionNode() {
        IIOMetadataNode dim = new IIOMetadataNode("Dimension");
        double resX = 0;
        double resY = 0;
        if (jp2 != null) {
            resX = jp2.captureResX > 0 ? jp2.captureResX : jp2.displayResX;
            resY = jp2.captureResY > 0 ? jp2.captureResY : jp2.displayResY;
        }
        IIOMetadataNode aspect = new IIOMetadataNode("PixelAspectRatio");
        double ar = (resX > 0 && resY > 0) ? resY / resX : 1.0;
        aspect.setAttribute("value", Double.toString(ar));
        dim.appendChild(aspect);
        IIOMetadataNode orient = new IIOMetadataNode("ImageOrientation");
        orient.setAttribute("value", "Normal");
        dim.appendChild(orient);
        if (resX > 0 && resY > 0) {
            // standard nodes carry millimeters per pixel
            IIOMetadataNode hps = new IIOMetadataNode("HorizontalPixelSize");
            hps.setAttribute("value", Double.toString(1000.0 / resX));
            dim.appendChild(hps);
            IIOMetadataNode vps = new IIOMetadataNode("VerticalPixelSize");
            vps.setAttribute("value", Double.toString(1000.0 / resY));
            dim.appendChild(vps);
        }
        return dim;
    }

    @Override
    protected IIOMetadataNode getStandardCompressionNode() {
        IIOMetadataNode comp = new IIOMetadataNode("Compression");
        IIOMetadataNode name = new IIOMetadataNode("CompressionTypeName");
        name.setAttribute("value",
                (cs.mainCod.cbStyle & CodingStyle.CB_HT) != 0 ? "HTJ2K" : "JPEG2000");
        comp.appendChild(name);
        IIOMetadataNode lossless = new IIOMetadataNode("Lossless");
        lossless.setAttribute("value", cs.mainCod.reversible() ? "TRUE" : "FALSE");
        comp.appendChild(lossless);
        IIOMetadataNode scans = new IIOMetadataNode("NumProgressiveScans");
        scans.setAttribute("value", Integer.toString(cs.mainCod.numLayers));
        comp.appendChild(scans);
        return comp;
    }

    @Override
    protected IIOMetadataNode getStandardTransparencyNode() {
        IIOMetadataNode t = new IIOMetadataNode("Transparency");
        IIOMetadataNode alpha = new IIOMetadataNode("Alpha");
        alpha.setAttribute("value", shape.alphaChannel < 0 ? "none"
                : (shape.alphaPremultiplied ? "premultiplied" : "nonpremultiplied"));
        t.appendChild(alpha);
        return t;
    }

    // ---- native tree ----

    private Node nativeTree() {
        IIOMetadataNode root = new IIOMetadataNode(NATIVE_FORMAT);
        Siz siz = cs.siz;

        IIOMetadataNode sizNode = new IIOMetadataNode("SIZ");
        sizNode.setAttribute("width", Integer.toString(siz.width()));
        sizNode.setAttribute("height", Integer.toString(siz.height()));
        sizNode.setAttribute("xOffset", Integer.toString(siz.xosiz));
        sizNode.setAttribute("yOffset", Integer.toString(siz.yosiz));
        sizNode.setAttribute("tileWidth", Integer.toString(siz.xtsiz));
        sizNode.setAttribute("tileHeight", Integer.toString(siz.ytsiz));
        sizNode.setAttribute("tileXOffset", Integer.toString(siz.xtosiz));
        sizNode.setAttribute("tileYOffset", Integer.toString(siz.ytosiz));
        sizNode.setAttribute("components", Integer.toString(siz.numComponents));
        sizNode.setAttribute("capabilities", Integer.toString(siz.capabilities));
        for (int c = 0; c < siz.numComponents; c++) {
            IIOMetadataNode comp = new IIOMetadataNode("Component");
            comp.setAttribute("index", Integer.toString(c));
            comp.setAttribute("precision", Integer.toString(siz.precision[c]));
            comp.setAttribute("signed", Boolean.toString(siz.signed[c]));
            comp.setAttribute("dx", Integer.toString(siz.xrsiz[c]));
            comp.setAttribute("dy", Integer.toString(siz.yrsiz[c]));
            sizNode.appendChild(comp);
        }
        root.appendChild(sizNode);

        CodingStyle cod = cs.mainCod;
        IIOMetadataNode codNode = new IIOMetadataNode("COD");
        codNode.setAttribute("progressionOrder", switch (cod.progression) {
            case CodingStyle.LRCP -> "LRCP";
            case CodingStyle.RLCP -> "RLCP";
            case CodingStyle.RPCL -> "RPCL";
            case CodingStyle.PCRL -> "PCRL";
            default -> "CPRL";
        });
        codNode.setAttribute("layers", Integer.toString(cod.numLayers));
        codNode.setAttribute("decompositionLevels", Integer.toString(cod.numLevels));
        codNode.setAttribute("codeBlockWidth", Integer.toString(1 << cod.xcb));
        codNode.setAttribute("codeBlockHeight", Integer.toString(1 << cod.ycb));
        codNode.setAttribute("transform", cod.reversible() ? "5-3" : "9-7");
        codNode.setAttribute("multipleComponentTransform", Boolean.toString(cod.mct == 1));
        codNode.setAttribute("codeBlockStyle", "0x" + Integer.toHexString(cod.cbStyle));
        codNode.setAttribute("htBlocks",
                Boolean.toString((cod.cbStyle & CodingStyle.CB_HT) != 0));
        codNode.setAttribute("sop", Boolean.toString(cod.useSop));
        codNode.setAttribute("eph", Boolean.toString(cod.useEph));
        root.appendChild(codNode);

        IIOMetadataNode qcdNode = new IIOMetadataNode("QCD");
        qcdNode.setAttribute("style", switch (cs.mainQcd.style) {
            case 0 -> "none";
            case 1 -> "scalarDerived";
            default -> "scalarExpounded";
        });
        qcdNode.setAttribute("guardBits", Integer.toString(cs.mainQcd.guardBits));
        root.appendChild(qcdNode);

        if (jp2 != null) {
            IIOMetadataNode jp2Node = new IIOMetadataNode("JP2");
            jp2Node.setAttribute("colourMethod", jp2.colourMethod == 1 ? "enumerated"
                    : (jp2.colourMethod == 2 ? "restrictedICC" : "none"));
            if (jp2.colourMethod == 1) {
                jp2Node.setAttribute("enumeratedColourSpace", Integer.toString(jp2.enumCs));
            }
            jp2Node.setAttribute("palette", Boolean.toString(jp2.hasPalette()));
            if (jp2.cdefChannel != null) {
                for (int i = 0; i < jp2.cdefChannel.length; i++) {
                    IIOMetadataNode ch = new IIOMetadataNode("ChannelDefinition");
                    ch.setAttribute("channel", Integer.toString(jp2.cdefChannel[i]));
                    ch.setAttribute("type", Integer.toString(jp2.cdefType[i]));
                    ch.setAttribute("association", Integer.toString(jp2.cdefAssoc[i]));
                    jp2Node.appendChild(ch);
                }
            }
            if (jp2.captureResX > 0 || jp2.displayResX > 0) {
                IIOMetadataNode res = new IIOMetadataNode("Resolution");
                if (jp2.captureResX > 0) {
                    res.setAttribute("captureX", Double.toString(jp2.captureResX));
                    res.setAttribute("captureY", Double.toString(jp2.captureResY));
                }
                if (jp2.displayResX > 0) {
                    res.setAttribute("displayX", Double.toString(jp2.displayResX));
                    res.setAttribute("displayY", Double.toString(jp2.displayResY));
                }
                jp2Node.appendChild(res);
            }
            root.appendChild(jp2Node);
        }
        return root;
    }
}
