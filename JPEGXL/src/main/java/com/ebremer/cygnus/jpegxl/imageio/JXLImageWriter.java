package com.ebremer.cygnus.jpegxl.imageio;

import com.ebremer.cygnus.jpegxl.encoder.JxlEncoder;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

/**
 * ImageIO writer producing JPEG XL codestreams. By default output is lossless
 * (modular mode); an explicit compression quality below 1.0 selects the lossy
 * (VarDCT) mode.
 *
 * <p>Two things beyond the usual ImageIO surface. A raster of
 * {@link java.awt.image.DataBuffer#TYPE_FLOAT} samples is written as a
 * floating-point image, bit for bit — which is what the reader hands back for
 * one, so the round trip closes. And
 * {@link ImageWriteParam#setProgressiveMode ImageWriteParam.setProgressiveMode}
 * selects the responsive layout, where a prefix of the bytes is already the
 * whole picture at low resolution rather than part of it at full resolution.
 */
public final class JXLImageWriter extends ImageWriter {

    JXLImageWriter(ImageWriterSpi spi) {
        super(spi);
    }

    // ------------------------------------------------------- animation output

    /** Frames accumulated across writeToSequence, encoded together at the end. */
    private java.util.List<JxlEncoder.AnimationFrame> seqFrames;
    private int seqWidth;
    private int seqHeight;
    private int seqBits;
    private boolean seqGrey;
    private boolean seqAlpha;
    private int seqTpsNumerator = 100;
    private int seqTpsDenominator = 1;
    private long seqNumLoops;

    @Override
    public boolean canWriteSequence() {
        return true;
    }

    /**
     * Begins an animation. The stream metadata may carry the timebase and loop
     * count in this writer's native format (an {@code Animation} node with
     * {@code tpsNumerator}/{@code tpsDenominator}/{@code numLoops}); anything not
     * given defaults to 100 ticks a second, looping forever — and each frame's
     * duration comes from its own metadata when {@link #writeToSequence} runs.
     */
    @Override
    public void prepareWriteSequence(IIOMetadata streamMetadata) throws IOException {
        if (getOutput() == null) {
            throw new IllegalStateException("no output set");
        }
        seqFrames = new java.util.ArrayList<>();
        seqWidth = 0;
        seqTpsNumerator = 100;
        seqTpsDenominator = 1;
        seqNumLoops = 0;
        org.w3c.dom.Node anim = findNode(streamMetadata, "Animation");
        if (anim != null) {
            seqTpsNumerator = attr(anim, "tpsNumerator", 100);
            seqTpsDenominator = attr(anim, "tpsDenominator", 1);
            seqNumLoops = attr(anim, "numLoops", 0);
        }
    }

    /**
     * Adds one frame to the animation being written. Its duration in ticks is
     * read from the image's own metadata (a native {@code Frame} node with
     * {@code durationTicks}), defaulting to one tick. Every frame must match the
     * first in size and channel layout.
     */
    @Override
    public void writeToSequence(IIOImage image, ImageWriteParam param) throws IOException {
        if (seqFrames == null) {
            throw new IllegalStateException("prepareWriteSequence was not called");
        }
        if (image.hasRaster()) {
            throw new UnsupportedOperationException("raw Raster input is not supported");
        }
        BufferedImage buffered = toBufferedImage(image.getRenderedImage());
        if (buffered.getRaster().getDataBuffer().getDataType()
                == java.awt.image.DataBuffer.TYPE_FLOAT) {
            throw new UnsupportedOperationException("float animation frames are not supported");
        }
        ColorModel cm = buffered.getColorModel();
        boolean grey = cm.getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_GRAY;
        boolean alpha = cm.hasAlpha();
        int numColour = grey ? 1 : 3;
        IntFrame f = toIntFrame(buffered, grey, alpha, numColour, numColour + (alpha ? 1 : 0));

        if (seqFrames.isEmpty()) {
            seqWidth = f.w;
            seqHeight = f.h;
            seqBits = f.bits;
            seqGrey = grey;
            seqAlpha = alpha;
        } else if (f.w != seqWidth || f.h != seqHeight || grey != seqGrey || alpha != seqAlpha
                || f.bits != seqBits) {
            throw new IllegalArgumentException("frame " + seqFrames.size()
                    + " does not match the first frame's size or channel layout");
        }
        long ticks = 1;
        org.w3c.dom.Node frameNode = findNode(image.getMetadata(), "Frame");
        if (frameNode != null) {
            ticks = attr(frameNode, "durationTicks", 1);
        }
        seqFrames.add(JxlEncoder.AnimationFrame.full(f.planes, f.w, f.h, (int) ticks));
    }

    @Override
    public void endWriteSequence() throws IOException {
        if (seqFrames == null) {
            throw new IllegalStateException("prepareWriteSequence was not called");
        }
        if (seqFrames.isEmpty()) {
            throw new IllegalStateException("an animation needs at least one frame");
        }
        if (!(getOutput() instanceof ImageOutputStream out)) {
            throw new IllegalStateException("no output set");
        }
        var extras = seqAlpha
                ? java.util.List.of(com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo.alpha(
                        com.ebremer.cygnus.jpegxl.codestream.BitDepth.of(seqBits), false))
                : java.util.List.<com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo>of();
        byte[] encoded = JxlEncoder.encodeAnimation(seqFrames, seqWidth, seqHeight, seqBits,
                seqGrey, extras, seqTpsNumerator, seqTpsDenominator, seqNumLoops);
        out.write(encoded);
        out.flush();
        seqFrames = null;
    }

    /** First descendant element with the given tag in the writer's native tree, or null. */
    private static org.w3c.dom.Node findNode(IIOMetadata metadata, String tag) {
        if (metadata == null) {
            return null;
        }
        org.w3c.dom.Node root;
        try {
            root = metadata.getAsTree(JXLMetadata.NATIVE_FORMAT);
        } catch (IllegalArgumentException e) {
            return null; // not our native format
        }
        for (org.w3c.dom.Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (tag.equals(n.getNodeName())) {
                return n;
            }
        }
        return null;
    }

    private static long attr(org.w3c.dom.Node node, String name, long fallback) {
        org.w3c.dom.Node a = node.getAttributes().getNamedItem(name);
        try {
            return a == null ? fallback : Long.parseLong(a.getNodeValue());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int attr(org.w3c.dom.Node node, String name, int fallback) {
        return (int) attr(node, name, (long) fallback);
    }

    @Override
    public ImageWriteParam getDefaultWriteParam() {
        return new JXLWriteParam(getLocale());
    }

    /** Write param exposing lossless (default) and lossy compression types. */
    private static final class JXLWriteParam extends ImageWriteParam {
        JXLWriteParam(java.util.Locale locale) {
            super(locale);
            canWriteCompressed = true;
            compressionTypes = new String[] {"lossless", "lossy"};
            compressionType = "lossless";
            compressionQuality = 1.0f;
            canWriteProgressive = true;
        }
    }

    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata inData, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata inData, ImageTypeSpecifier imageType,
            ImageWriteParam param) {
        return null;
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param)
            throws IOException {
        if (!(getOutput() instanceof ImageOutputStream out)) {
            throw new IllegalStateException("no output set");
        }
        if (image.hasRaster()) {
            throw new UnsupportedOperationException("raw Raster input is not supported");
        }
        RenderedImage rendered = image.getRenderedImage();
        BufferedImage buffered = toBufferedImage(rendered);

        ColorModel cm = buffered.getColorModel();
        boolean grey = cm.getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_GRAY;
        boolean alpha = cm.hasAlpha();
        int numColour = grey ? 1 : 3;
        int bands = numColour + (alpha ? 1 : 0);

        boolean lossy = param != null
                && (param.getCompressionMode() == ImageWriteParam.MODE_EXPLICIT
                        && ("lossy".equals(param.getCompressionType())
                            || param.getCompressionQuality() < 1.0f));
        boolean progressive = param != null
                && param.getProgressiveMode() == ImageWriteParam.MODE_DEFAULT;

        if (buffered.getRaster().getDataBuffer().getDataType()
                == java.awt.image.DataBuffer.TYPE_FLOAT) {
            writeFloat(out, buffered, grey, alpha, numColour, bands, lossy, progressive, param);
            return;
        }

        IntFrame f = toIntFrame(buffered, grey, alpha, numColour, bands);
        byte[] encoded;
        if (lossy && f.bits <= 16) {
            encoded = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.encodeToTarget(
                    f.planes, f.w, f.h, f.bits, grey, alpha, false, distanceOf(param));
        } else if (progressive) {
            encoded = JxlEncoder.encodeProgressive(f.planes, f.w, f.h, f.bits, grey, alpha, false);
        } else {
            encoded = JxlEncoder.encode(f.planes, f.w, f.h, f.bits, grey, alpha, false);
        }
        out.write(encoded);
        out.flush();
    }

    /** Integer RGB(A)/grey pixels of a BufferedImage as sample planes. */
    private record IntFrame(int[][] planes, int w, int h, int bits, boolean grey, boolean alpha) {
    }

    private static IntFrame toIntFrame(BufferedImage buffered, boolean grey, boolean alpha,
            int numColour, int bands) {
        ColorModel cm = buffered.getColorModel();
        int maxSampleBits = 0;
        for (int i = 0; i < numColour && i < cm.getNumComponents(); i++) {
            maxSampleBits = Math.max(maxSampleBits, cm.getComponentSize(i));
        }
        int bits = maxSampleBits > 8 ? 16 : 8;

        int w = buffered.getWidth();
        int h = buffered.getHeight();
        if (cm.isAlphaPremultiplied()) {
            BufferedImage copy = new BufferedImage(w, h,
                    alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            copy.createGraphics().drawImage(buffered, 0, 0, null);
            buffered = copy;
            cm = buffered.getColorModel();
        }

        int[][] planes = new int[bands][w * h];
        Raster raster = buffered.getRaster();
        int rasterBands = raster.getNumBands();
        int[] row = new int[w];
        for (int b = 0; b < bands; b++) {
            boolean isAlphaBand = alpha && b == numColour;
            int srcBand = isAlphaBand ? rasterBands - 1 : Math.min(b, rasterBands - 1);
            int srcBits = cm.getComponentSize(Math.min(isAlphaBand
                    ? cm.getNumComponents() - 1 : b, cm.getNumComponents() - 1));
            int shift = bits - srcBits;
            for (int y = 0; y < h; y++) {
                raster.getSamples(0, y, w, 1, srcBand, row);
                int base = y * w;
                if (shift == 0) {
                    System.arraycopy(row, 0, planes[b], base, w);
                } else {
                    // widen e.g. 8-bit samples to 16-bit by replication
                    for (int x = 0; x < w; x++) {
                        int v = row[x];
                        planes[b][base + x] = (v << shift) | (v >> Math.max(0, srcBits - shift));
                    }
                }
            }
        }
        return new IntFrame(planes, w, h, bits, grey, alpha);
    }

    /**
     * Writes a float raster as a floating-point image. The samples are colour
     * values already, so nothing is scaled: they go to the coder as they are, and
     * the decoder hands them back bit for bit (or, coded lossily, as close as the
     * distance allows without ever quantising them onto an integer grid first).
     */
    private void writeFloat(ImageOutputStream out, BufferedImage buffered, boolean grey,
            boolean alpha, int numColour, int bands, boolean lossy, boolean progressive,
            ImageWriteParam param) throws IOException {
        if (progressive) {
            throw new UnsupportedOperationException("progressive needs integer samples: squeeze"
                    + " averages neighbouring samples, and the average of two float bit patterns"
                    + " is not a float between them");
        }
        int w = buffered.getWidth();
        int h = buffered.getHeight();
        Raster raster = buffered.getRaster();
        int rasterBands = raster.getNumBands();
        float[][] planes = new float[bands][w * h];
        float[] row = new float[w];
        for (int b = 0; b < bands; b++) {
            boolean isAlphaBand = alpha && b == numColour;
            int srcBand = isAlphaBand ? rasterBands - 1 : Math.min(b, rasterBands - 1);
            for (int y = 0; y < h; y++) {
                raster.getSamples(0, y, w, 1, srcBand, row);
                System.arraycopy(row, 0, planes[b], y * w, w);
            }
        }
        byte[] encoded = lossy
                ? com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.encodeFloat(
                        planes, w, h, grey, alpha, false, distanceOf(param))
                : JxlEncoder.encodeFloat(planes, w, h, grey, alpha, false);
        out.write(encoded);
        out.flush();
    }

    /** ImageIO quality [0,1] as a Butteraugli-like distance in [0.5, 15]. */
    private static float distanceOf(ImageWriteParam param) {
        float quality = param.getCompressionQuality();
        return 0.5f + (1.0f - quality) * 14.5f;
    }

    private static BufferedImage toBufferedImage(RenderedImage img) {
        if (img instanceof BufferedImage buffered) {
            return buffered;
        }
        ColorModel cm = img.getColorModel();
        BufferedImage out = new BufferedImage(cm,
                cm.createCompatibleWritableRaster(img.getWidth(), img.getHeight()),
                cm.isAlphaPremultiplied(), null);
        img.copyData(out.getRaster());
        return out;
    }
}
