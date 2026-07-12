package com.ebremer.cygnus.jpegxl.imageio;

import com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo;
import com.ebremer.cygnus.jpegxl.codestream.ImageMetadata;
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
                    stream, com.ebremer.cygnus.jpegxl.container.Container.scanSegments(stream));
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

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        if (!allowSearch && decoded == null) {
            return -1;
        }
        return image().frames.size();
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        return info().orientedWidth();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        return info().orientedHeight();
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        ImageMetadata meta = info().metadata();
        boolean grey = meta.colourEncoding.isGrey();
        boolean alpha = meta.alphaChannelIndex() >= 0;
        boolean deep = meta.bitDepth.bitsPerSample > 8;
        List<ImageTypeSpecifier> types = new ArrayList<>();
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
        java.awt.Rectangle wanted = param != null ? param.getSourceRegion() : null;
        if (wanted != null) {
            wanted = wanted.intersection(new java.awt.Rectangle(
                    info().orientedWidth(), info().orientedHeight()));
            if (wanted.isEmpty()) {
                throw new IIOException("source region " + param.getSourceRegion()
                        + " lies outside the image");
            }
        }
        JxlImage img;
        java.awt.Rectangle region; // remaining crop, relative to the frame
        if (decoded == null && wanted != null
                && (wanted.width < info().orientedWidth()
                    || wanted.height < info().orientedHeight())) {
            // group-selective decode of just the requested rectangle; not
            // cached, the result depends on the parameters
            img = JxlDecoder.decode(source(), wanted);
            region = null; // the frames already cover exactly the region
        } else {
            checkIndex(imageIndex);
            img = image();
            region = wanted;
        }
        if (imageIndex < 0 || imageIndex >= img.frames.size()) {
            throw new IndexOutOfBoundsException("image index " + imageIndex);
        }
        JxlFrame frame = img.frames.get(imageIndex);
        BufferedImage full = toBufferedImage(img.metadata, frame);
        if (param == null) {
            return full;
        }
        // honor any remaining source region / subsampling by extraction
        if (region == null) {
            region = new java.awt.Rectangle(full.getWidth(), full.getHeight());
        }
        int sx = Math.max(1, param.getSourceXSubsampling());
        int sy = Math.max(1, param.getSourceYSubsampling());
        if (region.x == 0 && region.y == 0 && region.width == full.getWidth()
                && region.height == full.getHeight() && sx == 1 && sy == 1) {
            return full;
        }
        int ow = (region.width + sx - 1) / sx;
        int oh = (region.height + sy - 1) / sy;
        BufferedImage out = new BufferedImage(full.getColorModel(),
                full.getRaster().createCompatibleWritableRaster(ow, oh),
                full.isAlphaPremultiplied(), null);
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                int[] px = full.getRaster().getPixel(region.x + x * sx, region.y + y * sy, (int[]) null);
                out.getRaster().setPixel(x, y, px);
            }
        }
        return out;
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
