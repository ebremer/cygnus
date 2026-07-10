package com.ebremer.cygnus.imageio;

import com.ebremer.cygnus.encoder.Jpeg2000Encoder;
import com.ebremer.cygnus.jp2.Jp2Info;
import com.ebremer.cygnus.jp2.Jp2Writer;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.io.OutputStream;
import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

/**
 * ImageIO writer for JPEG 2000 Part 1, backed by the pure-Java Cygnus
 * encoder. Writes JP2 files by default (with colour specification and
 * channel definitions) or raw codestreams (see
 * {@link CygnusImageWriteParam#setWriteCodeStreamOnly}).
 *
 * <p>The default compression type is lossless (reversible 5/3 wavelet with
 * RCT): decoding reproduces the written samples bit-exactly. The
 * {@code "Lossy"} type selects the irreversible 9/7 pipeline whose step
 * sizes scale with the compression quality. BufferedImages of any standard
 * type are accepted (indexed images are expanded), as are bare Rasters with
 * up to 16-bit samples; source region, subsampling and band selection are
 * honored.</p>
 */
public final class CygnusImageWriter extends ImageWriter {

    private ImageOutputStream stream;

    public CygnusImageWriter(ImageWriterSpi spi) {
        super(spi);
    }

    @Override
    public void setOutput(Object output) {
        if (output != null && !(output instanceof ImageOutputStream)) {
            throw new IllegalArgumentException("Output must be an ImageOutputStream");
        }
        super.setOutput(output);
        stream = (ImageOutputStream) output;
    }

    @Override
    public ImageWriteParam getDefaultWriteParam() {
        return new CygnusImageWriteParam();
    }

    // metadata is not supported on the write path
    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType,
                                               ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata inData, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata inData,
                                            ImageTypeSpecifier imageType,
                                            ImageWriteParam param) {
        return null;
    }

    @Override
    public boolean canWriteRasters() {
        return true;
    }

    /** Component planes plus the container metadata derived from the source. */
    private static final class Source {
        int width, height;
        int[][] planes;
        int[] depths;
        int enumCs = Jp2Info.CS_SRGB;
        byte[] icc;
        int alphaChannel = -1;
        boolean premultiplied;
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param)
            throws IOException {
        if (stream == null) {
            throw new IllegalStateException("No output has been set");
        }
        if (image == null) {
            throw new IllegalArgumentException("image is null");
        }
        clearAbortRequest();
        processImageStarted(0);

        Source src = image.hasRaster()
                ? fromRaster(image.getRaster(), param)
                : fromRenderedImage(image.getRenderedImage(), param);

        Jpeg2000Encoder.Params ep = new Jpeg2000Encoder.Params();
        ep.width = src.width;
        ep.height = src.height;
        ep.precision = src.depths;
        boolean codestreamOnly = false;
        if (param instanceof CygnusImageWriteParam cp) {
            ep.levels = cp.getNumDecompositionLevels();
            ep.xcb = 31 - Integer.numberOfLeadingZeros(cp.getCodeBlockWidth());
            ep.ycb = 31 - Integer.numberOfLeadingZeros(cp.getCodeBlockHeight());
            ep.sop = cp.getSopMarkers();
            ep.eph = cp.getEphMarkers();
            codestreamOnly = cp.getWriteCodeStreamOnly();
        }
        if (param != null && param.canWriteCompressed()
                && param.getCompressionMode() == ImageWriteParam.MODE_EXPLICIT
                && CygnusImageWriteParam.LOSSY.equalsIgnoreCase(param.getCompressionType())) {
            ep.reversible = false;
            ep.quality = param.getCompressionQuality();
        }
        if (param != null && param.canWriteTiles()
                && param.getTilingMode() == ImageWriteParam.MODE_EXPLICIT) {
            ep.tileWidth = param.getTileWidth();
            ep.tileHeight = param.getTileHeight();
        }

        Jpeg2000Encoder encoder;
        try {
            encoder = new Jpeg2000Encoder(ep);
        } catch (IllegalArgumentException e) {
            throw new IIOException(e.getMessage(), e);
        }
        encoder.setProgressListener((done, total) -> {
            processImageProgress(100.0f * done / total);
            return !abortRequested();
        });
        OutputStream sink = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                stream.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                stream.write(b, off, len);
            }
        };

        boolean completed;
        if (codestreamOnly) {
            completed = encoder.encode(src.planes, sink);
        } else {
            boolean[] signed = new boolean[src.depths.length];
            stream.write(Jp2Writer.headerBoxes(src.width, src.height,
                    src.depths, signed, src.enumCs, src.icc,
                    src.alphaChannel, src.premultiplied));
            long lboxPos = stream.getStreamPosition();
            stream.writeInt(0);   // jp2c length; 0 = to end of file, patched below
            stream.writeInt(Jp2Writer.BOX_JP2C);
            completed = encoder.encode(src.planes, sink);
            if (completed) {
                long end = stream.getStreamPosition();
                long lbox = end - lboxPos;
                if (lbox <= 0xFFFFFFFFL) {
                    try {
                        stream.seek(lboxPos);
                        stream.writeInt((int) lbox);
                        stream.seek(end);
                    } catch (IOException e) {
                        // already-flushed output: the length stays 0 (to EOF),
                        // which is valid for a final box
                        stream.seek(end);
                    }
                }
            }
        }
        if (!completed) {
            processWriteAborted();
            return;
        }
        stream.flush();
        processImageComplete();
    }

    // ---- source conversion ----

    /**
     * Output region of a write after applying the param's source region and
     * subsampling (javax.imageio region semantics), in source coordinates.
     */
    private static Rectangle sourceRegion(ImageWriteParam param, Rectangle bounds)
            throws IIOException {
        Rectangle region = new Rectangle(bounds);
        if (param != null) {
            if (param.getSourceRegion() != null) {
                region = region.intersection(param.getSourceRegion());
            }
            region.x += param.getSubsamplingXOffset();
            region.y += param.getSubsamplingYOffset();
            region.width -= param.getSubsamplingXOffset();
            region.height -= param.getSubsamplingYOffset();
        }
        if (region.width <= 0 || region.height <= 0) {
            throw new IIOException("Empty source region");
        }
        return region;
    }

    /** Extracts one band of a raster region with subsampling. */
    private static int[] extractBand(Raster raster, int band, Rectangle region,
                                     int ssx, int ssy, int dw, int dh) {
        int[] plane = new int[dw * dh];
        int spanW = (dw - 1) * ssx + 1;
        int[] tmp = new int[spanW];
        for (int dy = 0; dy < dh; dy++) {
            int sy = region.y + dy * ssy;
            raster.getSamples(region.x, sy, spanW, 1, band, tmp);
            int dst = dy * dw;
            if (ssx == 1) {
                System.arraycopy(tmp, 0, plane, dst, dw);
            } else {
                for (int dx = 0; dx < dw; dx++) {
                    plane[dst + dx] = tmp[dx * ssx];
                }
            }
        }
        return plane;
    }

    /** Raster input: bands are written as codestream components verbatim. */
    private Source fromRaster(Raster raster, ImageWriteParam param) throws IIOException {
        Rectangle region = sourceRegion(param, raster.getBounds());
        int ssx = param != null ? param.getSourceXSubsampling() : 1;
        int ssy = param != null ? param.getSourceYSubsampling() : 1;
        int[] bands = bandSelection(param, raster.getNumBands());
        Source src = new Source();
        src.width = (region.width + ssx - 1) / ssx;
        src.height = (region.height + ssy - 1) / ssy;
        src.planes = new int[bands.length][];
        src.depths = new int[bands.length];
        for (int i = 0; i < bands.length; i++) {
            int depth = raster.getSampleModel().getSampleSize(bands[i]);
            checkDepth(depth);
            src.depths[i] = depth;
            src.planes[i] = extractBand(raster, bands[i], region, ssx, ssy,
                    src.width, src.height);
        }
        src.enumCs = bands.length >= 3 ? Jp2Info.CS_SRGB : Jp2Info.CS_GREY;
        return src;
    }

    private static int[] bandSelection(ImageWriteParam param, int numBands)
            throws IIOException {
        int[] bands = param != null ? param.getSourceBands() : null;
        if (bands == null) {
            bands = new int[numBands];
            for (int i = 0; i < numBands; i++) {
                bands[i] = i;
            }
            return bands;
        }
        for (int b : bands) {
            if (b < 0 || b >= numBands) {
                throw new IIOException("Source band " + b + " out of range");
            }
        }
        return bands.clone();
    }

    private static void checkDepth(int depth) throws IIOException {
        if (depth < 1 || depth > 26) {
            throw new IIOException("Unsupported sample depth " + depth);
        }
    }

    private Source fromRenderedImage(RenderedImage ri, ImageWriteParam param)
            throws IIOException {
        ColorModel cm = ri.getColorModel();
        if (param != null && param.getSourceBands() != null) {
            // explicit band subset: write the raw raster bands
            Raster full = ri instanceof BufferedImage bi ? bi.getRaster() : ri.getData();
            return fromRaster(full, param);
        }
        Rectangle bounds = new Rectangle(ri.getMinX(), ri.getMinY(),
                ri.getWidth(), ri.getHeight());
        Rectangle region = sourceRegion(param, bounds);
        Raster raster = ri instanceof BufferedImage bi
                ? bi.getRaster() : ri.getData(region);
        int ssx = param != null ? param.getSourceXSubsampling() : 1;
        int ssy = param != null ? param.getSourceYSubsampling() : 1;
        int dw = (region.width + ssx - 1) / ssx;
        int dh = (region.height + ssy - 1) / ssy;

        Source src = new Source();
        src.width = dw;
        src.height = dh;

        if (cm instanceof IndexColorModel icm) {
            expandIndexed(src, icm, raster, region, ssx, ssy, dw, dh);
            return src;
        }

        int bands = raster.getNumBands();
        boolean alpha = cm != null && cm.hasAlpha() && bands > 1;
        src.planes = new int[bands][];
        src.depths = new int[bands];
        for (int b = 0; b < bands; b++) {
            int depth = raster.getSampleModel().getSampleSize(b);
            checkDepth(depth);
            src.depths[b] = depth;
            src.planes[b] = extractBand(raster, b, region, ssx, ssy, dw, dh);
        }
        if (alpha) {
            src.alphaChannel = bands - 1;
            src.premultiplied = cm.isAlphaPremultiplied();
        }
        int numColour = bands - (alpha ? 1 : 0);
        ColorSpace cs = cm != null ? cm.getColorSpace() : null;
        src.enumCs = numColour >= 3 ? Jp2Info.CS_SRGB : Jp2Info.CS_GREY;
        if (cs instanceof ICC_ColorSpace icc
                && !cs.isCS_sRGB()
                && cs != ColorSpace.getInstance(ColorSpace.CS_GRAY)
                && (cs.getType() == ColorSpace.TYPE_RGB
                        || cs.getType() == ColorSpace.TYPE_GRAY)
                && cs.getNumComponents() == numColour) {
            src.icc = icc.getProfile().getData();
        }
        return src;
    }

    /** Expands an indexed image through its palette to grey or RGB(A). */
    private void expandIndexed(Source src, IndexColorModel icm, Raster raster,
                               Rectangle region, int ssx, int ssy, int dw, int dh)
            throws IIOException {
        int size = icm.getMapSize();
        byte[] r = new byte[size];
        byte[] g = new byte[size];
        byte[] b = new byte[size];
        byte[] a = new byte[size];
        icm.getReds(r);
        icm.getGreens(g);
        icm.getBlues(b);
        icm.getAlphas(a);
        boolean hasAlpha = icm.getTransparency() != java.awt.Transparency.OPAQUE;
        boolean grey = !hasAlpha;
        for (int i = 0; i < size && grey; i++) {
            grey = r[i] == g[i] && g[i] == b[i];
        }
        int[] indices = extractBand(raster, 0, region, ssx, ssy, dw, dh);
        int n = indices.length;
        if (grey) {
            int[] plane = new int[n];
            for (int i = 0; i < n; i++) {
                plane[i] = r[clampIndex(indices[i], size)] & 0xFF;
            }
            src.planes = new int[][] {plane};
            src.depths = new int[] {8};
            src.enumCs = Jp2Info.CS_GREY;
            return;
        }
        int bands = hasAlpha ? 4 : 3;
        src.planes = new int[bands][n];
        src.depths = new int[bands];
        java.util.Arrays.fill(src.depths, 8);
        for (int i = 0; i < n; i++) {
            int idx = clampIndex(indices[i], size);
            src.planes[0][i] = r[idx] & 0xFF;
            src.planes[1][i] = g[idx] & 0xFF;
            src.planes[2][i] = b[idx] & 0xFF;
            if (hasAlpha) {
                src.planes[3][i] = a[idx] & 0xFF;
            }
        }
        src.enumCs = Jp2Info.CS_SRGB;
        if (hasAlpha) {
            src.alphaChannel = 3;
        }
    }

    private static int clampIndex(int i, int size) {
        return i < 0 ? 0 : Math.min(i, size - 1);
    }
}
