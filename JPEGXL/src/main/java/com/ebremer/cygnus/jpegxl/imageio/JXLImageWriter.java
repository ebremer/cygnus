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

        byte[] encoded;
        if (lossy && bits <= 16) {
            encoded = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.encodeToTarget(
                    planes, w, h, bits, grey, alpha, false, distanceOf(param));
        } else if (progressive) {
            encoded = JxlEncoder.encodeProgressive(planes, w, h, bits, grey, alpha, false);
        } else {
            encoded = JxlEncoder.encode(planes, w, h, bits, grey, alpha, false);
        }
        out.write(encoded);
        out.flush();
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
