package com.ebremer.cygnus.jpegxl.imageio;

import com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo;
import com.ebremer.cygnus.jpegxl.codestream.ImageMetadata;
import com.ebremer.cygnus.jpegxl.color.IccColorTransform;
import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlFrame;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/** ImageIO reader for JPEG XL (modular mode). */
public final class JXLImageReader extends ImageReader {

    private com.ebremer.cygnus.jpegxl.io.CodestreamSource source;
    private JxlDecoder.Info info;
    private JxlImage decoded;

    JXLImageReader(ImageReaderSpi spi) {
        super(spi);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        source = null;
        info = null;
        decoded = null;
    }

    /** Streams sections from the input on demand; nothing is buffered up front. */
    private com.ebremer.cygnus.jpegxl.io.CodestreamSource source() throws IOException {
        if (source == null) {
            if (!(input instanceof ImageInputStream stream)) {
                throw new IllegalStateException("no input set");
            }
            source = new com.ebremer.cygnus.jpegxl.io.CodestreamSource.StreamSource(
                    stream, com.ebremer.cygnus.jpegxl.container.Container.scanSegments(stream),
                    com.ebremer.cygnus.jpegxl.container.Container.declaredLevel(stream));
        }
        return source;
    }

    private JxlDecoder.Info info() throws IOException {
        if (info == null) {
            info = JxlDecoder.readInfo(source());
        }
        return info;
    }

    private JxlImage image() throws IOException {
        if (decoded == null) {
            decoded = JxlDecoder.decode(source());
        }
        return decoded;
    }

    private void checkIndex(int imageIndex) throws IOException {
        if (imageIndex < 0 || imageIndex >= image().frames.size()) {
            throw new IndexOutOfBoundsException("image index " + imageIndex);
        }
    }

    /**
     * Validates an image index without decoding when it can: a negative index
     * is always out of range and index zero always in it — a readable stream
     * has at least one frame — so only an index past zero needs the decoded
     * frame count.
     */
    private void checkIndexLight(int imageIndex) throws IOException {
        if (imageIndex < 0 || (imageIndex > 0 && imageIndex >= image().frames.size())) {
            throw new IndexOutOfBoundsException("image index " + imageIndex);
        }
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        if (!allowSearch && decoded == null) {
            return -1;
        }
        return image().frames.size();
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        checkIndexLight(imageIndex);
        return info().orientedWidth();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        checkIndexLight(imageIndex);
        return info().orientedHeight();
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        checkIndexLight(imageIndex);
        ImageMetadata meta = info().metadata();
        boolean grey = meta.colourEncoding.isGrey();
        boolean alpha = meta.alphaChannelIndex() >= 0;
        boolean deep = meta.bitDepth.bitsPerSample > 8;
        List<ImageTypeSpecifier> types = new ArrayList<>();
        // a demosaiced CFA mosaic comes out as eight-bit RGB, not the grey it is coded as
        if (grey && cfaChannelIndex(meta) >= 0 && !meta.bitDepth.floatingPoint
                && !Boolean.getBoolean("jxl.skipCfa")) {
            types.add(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB));
            return types.iterator();
        }
        if (grey && !alpha) {
            types.add(ImageTypeSpecifier.createFromBufferedImageType(
                    deep ? BufferedImage.TYPE_USHORT_GRAY : BufferedImage.TYPE_BYTE_GRAY));
        } else if (!deep) {
            types.add(ImageTypeSpecifier.createFromBufferedImageType(
                    alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB));
        } else {
            java.awt.color.ColorSpace cs = java.awt.color.ColorSpace.getInstance(
                    grey ? java.awt.color.ColorSpace.CS_GRAY : java.awt.color.ColorSpace.CS_sRGB);
            int bands = (grey ? 1 : 3) + (alpha ? 1 : 0);
            int[] offsets = new int[bands];
            for (int i = 0; i < bands; i++) {
                offsets[i] = i;
            }
            types.add(ImageTypeSpecifier.createInterleaved(cs, offsets,
                    java.awt.image.DataBuffer.TYPE_USHORT, alpha, false));
        }
        return types.iterator();
    }

    @Override
    public boolean readerSupportsThumbnails() {
        return true;
    }

    @Override
    public int getNumThumbnails(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return image().preview != null ? 1 : 0;
    }

    @Override
    public int getThumbnailWidth(int imageIndex, int thumbnailIndex) throws IOException {
        return thumbnail(imageIndex, thumbnailIndex).width;
    }

    @Override
    public int getThumbnailHeight(int imageIndex, int thumbnailIndex) throws IOException {
        return thumbnail(imageIndex, thumbnailIndex).height;
    }

    @Override
    public BufferedImage readThumbnail(int imageIndex, int thumbnailIndex) throws IOException {
        return toBufferedImage(image().metadata, thumbnail(imageIndex, thumbnailIndex));
    }

    private JxlFrame thumbnail(int imageIndex, int thumbnailIndex) throws IOException {
        checkIndex(imageIndex);
        JxlFrame preview = image().preview;
        if (preview == null || thumbnailIndex != 0) {
            throw new IndexOutOfBoundsException("thumbnail index " + thumbnailIndex);
        }
        return preview;
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        clearAbortRequest();
        processImageStarted(imageIndex);
        BufferedImage image = readImage(imageIndex, param);
        if (abortRequested()) {
            processReadAborted();
        } else {
            processImageComplete();
        }
        return image;
    }

    private BufferedImage readImage(int imageIndex, ImageReadParam param) throws IOException {
        int width = info().orientedWidth();
        int height = info().orientedHeight();
        java.awt.Rectangle srcRegion = new java.awt.Rectangle();
        java.awt.Rectangle destRegion = new java.awt.Rectangle();
        computeRegions(param, width, height,
                param != null ? param.getDestination() : null, srcRegion, destRegion);

        JxlImage img;
        int baseX; // srcRegion's origin, relative to the decoded frame
        int baseY;
        if (decoded == null && (srcRegion.width < width || srcRegion.height < height)) {
            // group-selective decode of just the requested rectangle; not
            // cached, the result depends on the parameters
            img = JxlDecoder.decode(source(), srcRegion);
            baseX = 0;
            baseY = 0;
        } else {
            checkIndex(imageIndex);
            img = image();
            baseX = srcRegion.x;
            baseY = srcRegion.y;
        }
        if (imageIndex < 0 || imageIndex >= img.frames.size()) {
            throw new IndexOutOfBoundsException("image index " + imageIndex);
        }
        BufferedImage full = toBufferedImage(img.metadata, img.frames.get(imageIndex));
        int sx = param != null ? param.getSourceXSubsampling() : 1;
        int sy = param != null ? param.getSourceYSubsampling() : 1;
        if (param == null
                || (param.getDestination() == null && param.getDestinationType() == null
                    && param.getSourceBands() == null && param.getDestinationBands() == null
                    && destRegion.x == 0 && destRegion.y == 0 && sx == 1 && sy == 1
                    && baseX == 0 && baseY == 0
                    && srcRegion.width == full.getWidth()
                    && srcRegion.height == full.getHeight())) {
            return full; // the natural image is exactly what was asked for
        }
        BufferedImage dest = getDestination(param, getImageTypes(imageIndex), width, height);
        checkReadParamBandSettings(param, full.getRaster().getNumBands(),
                dest.getRaster().getNumBands());
        copyRegion(full, baseX, baseY, dest, destRegion, sx, sy,
                param.getSourceBands(), param.getDestinationBands());
        return dest;
    }

    /**
     * Copies the subsampled source region into the destination, band-mapped,
     * reporting progress per row and stopping early when a listener aborts.
     */
    private void copyRegion(BufferedImage src, int baseX, int baseY, BufferedImage dest,
            java.awt.Rectangle destRegion, int sx, int sy, int[] srcBands, int[] dstBands) {
        java.awt.image.Raster in = src.getRaster();
        WritableRaster out = dest.getRaster();
        int bands = srcBands != null ? srcBands.length : in.getNumBands();
        boolean floats = in.getTransferType() == java.awt.image.DataBuffer.TYPE_FLOAT
                || out.getTransferType() == java.awt.image.DataBuffer.TYPE_FLOAT;
        for (int y = 0; y < destRegion.height; y++) {
            if (abortRequested()) {
                return;
            }
            processImageProgress(100f * y / destRegion.height);
            int rowY = baseY + y * sy;
            for (int x = 0; x < destRegion.width; x++) {
                int colX = baseX + x * sx;
                for (int b = 0; b < bands; b++) {
                    int sb = srcBands != null ? srcBands[b] : b;
                    int db = dstBands != null ? dstBands[b] : b;
                    if (floats) {
                        out.setSample(destRegion.x + x, destRegion.y + y, db,
                                in.getSampleFloat(colX, rowY, sb));
                    } else {
                        out.setSample(destRegion.x + x, destRegion.y + y, db,
                                in.getSample(colX, rowY, sb));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- tile API

    private int tileDim = -1;

    /** The codestream's group size in display pixels (the natural tile size). */
    private int tileDim() throws IOException {
        if (tileDim < 0) {
            tileDim = JxlDecoder.readTileDim(source());
        }
        return tileDim;
    }

    @Override
    public boolean isImageTiled(int imageIndex) throws IOException {
        if (info().metadata().orientation != 1) {
            // an oriented tile grid would not line up with codestream groups;
            // region reads still work, the image just reports a single tile
            return false;
        }
        int td = tileDim();
        return td < getWidth(imageIndex) || td < getHeight(imageIndex);
    }

    @Override
    public int getTileWidth(int imageIndex) throws IOException {
        return isImageTiled(imageIndex) ? tileDim() : getWidth(imageIndex);
    }

    @Override
    public int getTileHeight(int imageIndex) throws IOException {
        return isImageTiled(imageIndex) ? tileDim() : getHeight(imageIndex);
    }

    @Override
    public BufferedImage readTile(int imageIndex, int tileX, int tileY) throws IOException {
        int tw = getTileWidth(imageIndex);
        int th = getTileHeight(imageIndex);
        int cols = (getWidth(imageIndex) + tw - 1) / tw;
        int rows = (getHeight(imageIndex) + th - 1) / th;
        if (tileX < 0 || tileY < 0 || tileX >= cols || tileY >= rows) {
            throw new IllegalArgumentException("tile (" + tileX + ", " + tileY + ") out of range");
        }
        ImageReadParam p = getDefaultReadParam();
        p.setSourceRegion(new java.awt.Rectangle(tileX * tw, tileY * th,
                Math.min(tw, getWidth(imageIndex) - tileX * tw),
                Math.min(th, getHeight(imageIndex) - tileY * th)));
        return read(imageIndex, p);
    }

    private static BufferedImage toBufferedImage(ImageMetadata meta, JxlFrame frame) throws IOException {
        int w = frame.width;
        int h = frame.height;
        boolean grey = meta.colourEncoding.isGrey();
        int numColour = grey ? 1 : 3;
        int alphaIdx = meta.alphaChannelIndex();
        int bpp = meta.bitDepth.bitsPerSample;

        // A CMYK image is rendered to RGB for display; the black channel is
        // multiplied into the colour planes (see renderCmyk). This is a viewer
        // step, not the normative decode, so it lives here and not in JxlDecoder.
        // A single-channel image tagged with a colour-filter-array channel is a
        // Bayer mosaic: its grey plane is demosaiced to RGB for display, the
        // interpolation a raw-sensor viewer performs. The format tags the image
        // but records no Bayer pattern, so RGGB (the common one) is assumed; this
        // is a viewer step, and -Djxl.skipCfa turns it off, leaving the raw mosaic.
        if (grey && cfaChannelIndex(meta) >= 0 && !meta.bitDepth.floatingPoint
                && !Boolean.getBoolean("jxl.skipCfa")) {
            return demosaicToImage(frame.channels[0], w, h, meta.bitDepth.bitsPerSample);
        }

        int blackIdx = grey ? -1 : cmykBlackIndex(meta);
        if (blackIdx >= 0 && !Boolean.getBoolean("jxl.skipCmyk")) {
            frame = renderCmyk(frame, numColour, blackIdx, meta);
        }

        // Colour management: a matrix/TRC RGB ICC profile is applied here, mapping
        // the device samples to sRGB for display — the rendering a colour-managed
        // viewer performs, reproducing lcms2. Only for a non-XYB integer image,
        // whose colour planes are the profile's device values (an XYB frame is
        // already in sRGB primaries; the float path is left to the caller).
        // Profiles a pure-Java transform cannot render exactly (LUT/CMYK) return
        // null from forProfile, and the samples are left as sRGB. This is a viewer
        // step, so it lives here and not in the normative JxlDecoder.
        if (!grey && blackIdx < 0 && !meta.xybEncoded && !meta.bitDepth.floatingPoint
                && !Boolean.getBoolean("jxl.skipIcc")) {
            IccColorTransform icc = IccColorTransform.forProfile(meta.iccProfile);
            if (icc != null) {
                frame = renderIcc(frame, icc, (1 << bpp) - 1);
            }
        }
        int[][] ch = frame.channels;

        if (meta.bitDepth.floatingPoint) {
            return toFloatImage(meta, frame, numColour, alphaIdx);
        }

        if (grey && alphaIdx < 0 && bpp <= 16) {
            BufferedImage img = new BufferedImage(w, h,
                    bpp > 8 ? BufferedImage.TYPE_USHORT_GRAY : BufferedImage.TYPE_BYTE_GRAY);
            WritableRaster raster = img.getRaster();
            int shift = bpp > 8 ? 16 - bpp : 8 - bpp;
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    row[x] = ch[0][y * w + x] << shift;
                }
                raster.setSamples(0, y, w, 1, 0, row);
            }
            return img;
        }

        // pack into 8-bit ARGB (scaling down deeper samples) unless 16-bit RGB(A)
        if (bpp > 8) {
            return toDeepImage(meta, frame, numColour, alphaIdx);
        }
        BufferedImage img = new BufferedImage(w, h,
                alphaIdx >= 0 ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        int scale = bpp == 8 ? 1 : 0;
        int max = (1 << bpp) - 1;
        int[] argb = new int[w * h];
        int[] alphaPlane = alphaIdx >= 0 ? ch[numColour + alphaIdx] : null;
        for (int i = 0; i < w * h; i++) {
            int r;
            int g;
            int b;
            if (grey) {
                r = g = b = scaleTo8(ch[0][i], bpp, max, scale);
            } else {
                r = scaleTo8(ch[0][i], bpp, max, scale);
                g = scaleTo8(ch[1][i], bpp, max, scale);
                b = scaleTo8(ch[2][i], bpp, max, scale);
            }
            int a = alphaPlane != null ? scaleTo8(alphaPlane[i], bpp, max, scale) : 0xff;
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        img.setRGB(0, 0, w, h, argb, 0, w);
        return img;
    }

    private static int scaleTo8(int v, int bpp, int max, int identity) {
        if (identity == 1) {
            return v;
        }
        return (v * 255 + max / 2) / max;
    }

    /** The extra-channel index of the CMYK black plane, or -1 if there is none. */
    private static int cmykBlackIndex(ImageMetadata meta) {
        for (int i = 0; i < meta.numExtraChannels(); i++) {
            if (meta.extraChannels.get(i).type
                    == com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo.TYPE_BLACK) {
                return i;
            }
        }
        return -1;
    }

    /** The extra-channel index of the first colour-filter-array plane, or -1. */
    private static int cfaChannelIndex(ImageMetadata meta) {
        for (int i = 0; i < meta.numExtraChannels(); i++) {
            if (meta.extraChannels.get(i).type
                    == com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo.TYPE_CFA) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Demosaics a Bayer mosaic plane to an eight-bit RGB image. The pattern is
     * taken to be RGGB — the format records none — and the samples are scaled from
     * their own depth. See {@link com.ebremer.cygnus.jpegxl.color.CfaDemosaic}.
     */
    private static BufferedImage demosaicToImage(int[] mosaic, int w, int h, int bits) {
        int[][] rgb = com.ebremer.cygnus.jpegxl.color.CfaDemosaic.demosaic(
                mosaic, w, h, com.ebremer.cygnus.jpegxl.color.CfaDemosaic.Pattern.RGGB);
        int max = (1 << bits) - 1;
        int identity = bits == 8 ? 1 : 0;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] argb = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int r = scaleTo8(rgb[0][i], bits, max, identity);
            int g = scaleTo8(rgb[1][i], bits, max, identity);
            int b = scaleTo8(rgb[2][i], bits, max, identity);
            argb[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        img.setRGB(0, 0, w, h, argb, 0, w);
        return img;
    }

    /**
     * Renders a CMYK frame to RGB by multiplying its black channel into the three
     * colour planes — the naive device conversion. A JPEG XL CMYK image carries
     * C, M and Y as their additive complements in the colour channels (no ink is
     * the maximum value, white) and K as a separate black extra channel, likewise
     * complemented, so RGB is {@code colour * (black / blackMax)} per component.
     *
     * <p>It matters because where the black is full — the text of a scanned
     * document, say — the colour channels are still white, and a decoder that
     * shows only the colour planes (which is what dumping a CMYK file to RGB
     * usually does) loses the ink entirely. The plane arrays not touched are
     * shared with the returned frame; the three colour planes are copies, so the
     * decoded image the caller holds is unchanged. {@code -Djxl.skipCmyk} turns
     * this off, for a caller that wants the four channels as they were coded.
     */
    /**
     * Returns a frame whose three colour planes are the ICC device samples mapped
     * to sRGB; the originals (shared with the caller's decoded image) are left
     * untouched. Extra channels ride along unchanged.
     */
    private static JxlFrame renderIcc(JxlFrame frame, IccColorTransform icc, int max) {
        int[][] out = frame.channels.clone();
        out[0] = frame.channels[0].clone();
        out[1] = frame.channels[1].clone();
        out[2] = frame.channels[2].clone();
        icc.toSrgb(out[0], out[1], out[2], max);
        return new JxlFrame(frame.width, frame.height, out, frame.floatChannels, frame.duration);
    }

    private static JxlFrame renderCmyk(JxlFrame frame, int colourCount, int blackIdx,
            ImageMetadata meta) {
        int n = frame.width * frame.height;
        int bi = colourCount + blackIdx;
        float[] blackNorm = new float[n];
        if (frame.floatChannels != null && frame.floatChannels[bi] != null) {
            blackNorm = frame.floatChannels[bi]; // already in [0, 1]
        } else {
            int bmax = (1 << meta.extraChannels.get(blackIdx).bitDepth.bitsPerSample) - 1;
            int[] b = frame.channels[bi];
            for (int i = 0; i < n; i++) {
                blackNorm[i] = (float) b[i] / bmax;
            }
        }
        boolean floatColour = frame.floatChannels != null && frame.floatChannels[0] != null;
        if (floatColour) {
            float[][] out = frame.floatChannels.clone();
            for (int c = 0; c < 3; c++) {
                out[c] = frame.floatChannels[c].clone();
                for (int i = 0; i < n; i++) {
                    out[c][i] *= blackNorm[i];
                }
            }
            return new JxlFrame(frame.width, frame.height, frame.channels, out, frame.duration);
        }
        int[][] out = frame.channels.clone();
        for (int c = 0; c < 3; c++) {
            out[c] = frame.channels[c].clone();
            for (int i = 0; i < n; i++) {
                out[c][i] = Math.round(frame.channels[c][i] * blackNorm[i]);
            }
        }
        return new JxlFrame(frame.width, frame.height, out, frame.floatChannels, frame.duration);
    }

    /** Floating-point output via a TYPE_FLOAT component raster. */
    private static BufferedImage toFloatImage(ImageMetadata meta, JxlFrame frame,
            int numColour, int alphaIdx) throws IOException {
        int w = frame.width;
        int h = frame.height;
        boolean grey = numColour == 1;
        boolean hasAlpha = alphaIdx >= 0;
        int bands = numColour + (hasAlpha ? 1 : 0);
        java.awt.color.ColorSpace cs = java.awt.color.ColorSpace.getInstance(
                grey ? java.awt.color.ColorSpace.CS_GRAY : java.awt.color.ColorSpace.CS_sRGB);
        java.awt.image.ComponentColorModel cm = new java.awt.image.ComponentColorModel(
                cs, hasAlpha, false,
                hasAlpha ? java.awt.Transparency.TRANSLUCENT : java.awt.Transparency.OPAQUE,
                java.awt.image.DataBuffer.TYPE_FLOAT);
        WritableRaster raster = cm.createCompatibleWritableRaster(w, h);
        float[] row = new float[w];
        for (int b = 0; b < bands; b++) {
            int srcIdx = b < numColour ? b : numColour + alphaIdx;
            float[] src = frame.floatChannels[srcIdx];
            int[] srcInt = src == null ? frame.channels[srcIdx] : null;
            var depth = b < numColour ? meta.bitDepth
                    : meta.extraChannels.get(alphaIdx).bitDepth;
            float scale = srcInt != null
                    ? (float) (1.0 / ((1L << depth.bitsPerSample) - 1)) : 1f;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    row[x] = src != null ? src[y * w + x] : srcInt[y * w + x] * scale;
                }
                raster.setSamples(0, y, w, 1, b, row);
            }
        }
        return new BufferedImage(cm, raster, false, null);
    }

    /** 16-bit output via a ushort component raster. */
    private static BufferedImage toDeepImage(ImageMetadata meta, JxlFrame frame,
            int numColour, int alphaIdx) throws IOException {
        int w = frame.width;
        int h = frame.height;
        int bpp = meta.bitDepth.bitsPerSample;
        boolean hasAlpha = alphaIdx >= 0;
        boolean grey = numColour == 1;
        int bands = numColour + (hasAlpha ? 1 : 0);
        java.awt.color.ColorSpace cs = java.awt.color.ColorSpace.getInstance(
                grey ? java.awt.color.ColorSpace.CS_GRAY : java.awt.color.ColorSpace.CS_sRGB);
        java.awt.image.ComponentColorModel cm = new java.awt.image.ComponentColorModel(
                cs, hasAlpha, false,
                hasAlpha ? java.awt.Transparency.TRANSLUCENT : java.awt.Transparency.OPAQUE,
                java.awt.image.DataBuffer.TYPE_USHORT);
        WritableRaster raster = cm.createCompatibleWritableRaster(w, h);
        // widen shallow samples to 16 bits, narrow deeper ones (>16-bit
        // precision is only available through the direct JxlDecoder API)
        int shift = 16 - bpp;
        int[] row = new int[w];
        for (int b = 0; b < bands; b++) {
            int[] src = b < numColour ? frame.channels[b] : frame.channels[numColour + alphaIdx];
            for (int y = 0; y < h; y++) {
                if (shift >= 0) {
                    for (int x = 0; x < w; x++) {
                        row[x] = src[y * w + x] << shift;
                    }
                } else {
                    for (int x = 0; x < w; x++) {
                        row[x] = src[y * w + x] >>> -shift;
                    }
                }
                raster.setSamples(0, y, w, 1, b, row);
            }
        }
        boolean premultiplied = hasAlpha && alphaIdx >= 0
                && meta.extraChannels.get(alphaIdx).alphaAssociated;
        if (premultiplied) {
            throw new IIOException("premultiplied alpha is not supported yet");
        }
        return new BufferedImage(cm, raster, false, null);
    }

    @Override
    public IIOMetadata getStreamMetadata() throws IOException {
        return new JXLMetadata(info().metadata());
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return new JXLMetadata(image().metadata, image().frames.get(imageIndex).duration);
    }

    @Override
    public void dispose() {
        source = null;
        info = null;
        decoded = null;
        super.dispose();
    }

    static int alphaExtraChannel(ImageMetadata meta) {
        for (int i = 0; i < meta.extraChannels.size(); i++) {
            if (meta.extraChannels.get(i).type == ExtraChannelInfo.TYPE_ALPHA) {
                return i;
            }
        }
        return -1;
    }
}
