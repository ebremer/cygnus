package com.ebremer.cygnus.imageio;

import com.ebremer.cygnus.decoder.DecodedImage;
import com.ebremer.cygnus.decoder.Jpeg2000Decoder;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
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

/**
 * ImageIO reader for JPEG 2000 Part 1 (JP2 files and raw codestreams),
 * backed by the pure-Java Cygnus decoder.
 *
 * <p>Structural queries (size, image types, tiling) are answered from the
 * headers without decoding samples. Reads with a source region decode only
 * the tiles that intersect the region; {@link #readTile} maps directly onto
 * the codestream's tile grid.</p>
 *
 * <p>The input stream is not buffered into memory: headers are parsed once
 * and tile-part bodies are read from the stream as tiles are decoded, so
 * windowed reads of very large files (beyond 2 GiB) touch only the bytes of
 * the tiles they need. If the input was set with {@code seekForwardOnly},
 * random access is not permitted and the stream is buffered instead.</p>
 */
public final class CygnusImageReader extends ImageReader {

    private Jpeg2000Decoder decoder;
    private DecodedImage shape;          // metadata only, no samples
    private DecodedImage cached;         // last decoded image or region
    private int cachedReduction;
    private List<String> warnings;

    public CygnusImageReader(ImageReaderSpi spi) {
        super(spi);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        decoder = null;
        shape = null;
        cached = null;
        warnings = null;
    }

    private void checkIndex(int imageIndex) throws IndexOutOfBoundsException {
        if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("Only image index 0 is available");
        }
    }

    /** Fully buffers a stream (only used when random access is not allowed). */
    private static byte[] readAll(ImageInputStream stream) throws IOException {
        long length = stream.length();
        if (length > Integer.MAX_VALUE - 8) {
            throw new IIOException(
                    "Input larger than 2 GiB requires a seekable (not forward-only) stream");
        }
        if (length >= 0) {
            byte[] data = new byte[(int) length];
            stream.readFully(data);
            return data;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[65536];
        int r;
        while ((r = stream.read(buf)) > 0) {
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    private Jpeg2000Decoder decoder() throws IOException {
        if (decoder == null) {
            if (input == null) {
                throw new IllegalStateException("No input set");
            }
            if (!(input instanceof ImageInputStream stream)) {
                throw new IIOException("Input must be an ImageInputStream");
            }
            Jpeg2000Decoder d = new Jpeg2000Decoder();
            d.setProgressListener((done, total) -> {
                processImageProgress(90.0f * done / total);
                return !abortRequested();
            });
            if (isSeekForwardOnly()) {
                d.open(readAll(stream)); // backward seeks are not permitted: buffer
            } else {
                d.open(stream);
            }
            decoder = d;
            forwardWarnings();
        }
        return decoder;
    }

    /** Header-only structure: size, channels, colour, tile grid. */
    private DecodedImage shape() throws IOException {
        if (shape == null) {
            shape = decoder().shape();
        }
        return shape;
    }

    /**
     * Returns a decoded image covering {@code region} at the given
     * resolution reduction, reusing the last decode when it covers the
     * request.
     */
    private DecodedImage decodeRegion(Rectangle region, int reduction) throws IOException {
        if (cached != null && cachedReduction == reduction
                && new Rectangle(cached.regionX, cached.regionY,
                        cached.width, cached.height).contains(region)) {
            return cached;
        }
        DecodedImage img = decoder().decode(region, reduction);
        forwardWarnings();
        cached = img;
        cachedReduction = reduction;
        return img;
    }

    private static int reductionOf(ImageReadParam param) {
        return param instanceof CygnusImageReadParam p ? p.getResolutionReduction() : 0;
    }

    private void forwardWarnings() {
        List<String> all = decoder.warnings();
        int from = warnings == null ? 0 : warnings.size();
        for (int i = from; i < all.size(); i++) {
            processWarningOccurred(all.get(i));
        }
        warnings = new ArrayList<>(all);
    }

    // ---- size, type and tiling queries (header-only) ----

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        shape();
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return shape().imageWidth;
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return shape().imageHeight;
    }

    @Override
    public boolean isImageTiled(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        DecodedImage s = shape();
        return s.numXTiles * s.numYTiles > 1;
    }

    @Override
    public int getTileWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return shape().tileWidth;
    }

    @Override
    public int getTileHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return shape().tileHeight;
    }

    @Override
    public int getTileGridXOffset(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return shape().tileGridXOff;
    }

    @Override
    public int getTileGridYOffset(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return shape().tileGridYOff;
    }

    /** Number of raster bands the reader produces: colour channels + alpha. */
    private int outputBands(DecodedImage img) {
        int colour = img.colourChannels.length >= 3 ? 3 : 1;
        return colour + (img.alphaChannel >= 0 ? 1 : 0);
    }

    private ImageTypeSpecifier imageType(DecodedImage img) {
        int bands = outputBands(img);
        boolean hasAlpha = img.alphaChannel >= 0;
        int maxDepth = 1;
        for (int i = 0; i < Math.min(img.colourChannels.length, 3); i++) {
            maxDepth = Math.max(maxDepth, img.depth[img.colourChannels[i]]);
        }
        if (hasAlpha) {
            maxDepth = Math.max(maxDepth, img.depth[img.alphaChannel]);
        }
        int dataType = maxDepth <= 8 ? DataBuffer.TYPE_BYTE : DataBuffer.TYPE_USHORT;

        ColorSpace cs;
        if (img.colourSpace == DecodedImage.ColourSpace.ICC && img.iccProfile != null) {
            ColorSpace icc = null;
            try {
                icc = new ICC_ColorSpace(ICC_Profile.getInstance(img.iccProfile));
                if (icc.getNumComponents() != (bands - (hasAlpha ? 1 : 0))) {
                    icc = null;
                }
            } catch (RuntimeException e) {
                icc = null;
            }
            cs = icc != null ? icc
                    : ColorSpace.getInstance(bands - (hasAlpha ? 1 : 0) >= 3
                            ? ColorSpace.CS_sRGB : ColorSpace.CS_GRAY);
        } else if (bands - (hasAlpha ? 1 : 0) >= 3) {
            cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);
        } else {
            cs = ColorSpace.getInstance(ColorSpace.CS_GRAY);
        }
        int[] bandOffsets = new int[bands];
        for (int i = 0; i < bands; i++) {
            bandOffsets[i] = i;
        }
        return ImageTypeSpecifier.createInterleaved(
                cs, bandOffsets, dataType, hasAlpha, img.alphaPremultiplied);
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        List<ImageTypeSpecifier> types = new ArrayList<>(1);
        types.add(imageType(shape()));
        return types.iterator();
    }

    @Override
    public ImageTypeSpecifier getRawImageType(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return imageType(shape());
    }

    // ---- decoding ----

    @Override
    public ImageReadParam getDefaultReadParam() {
        return new CygnusImageReadParam();
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        clearAbortRequest();
        processImageStarted(imageIndex);
        int reduction = reductionOf(param);
        DecodedImage struct = reduction == 0 ? shape() : decoder().shape(reduction);

        int width = struct.imageWidth;
        int height = struct.imageHeight;
        Rectangle srcRegion = new Rectangle(0, 0, 0, 0);
        Rectangle destRegion = new Rectangle(0, 0, 0, 0);
        computeRegions(param, width, height,
                param != null ? param.getDestination() : null, srcRegion, destRegion);
        int[] srcBands = param != null ? param.getSourceBands() : null;
        int[] dstBands = param != null ? param.getDestinationBands() : null;
        BufferedImage dest;
        if (srcBands == null && dstBands == null) {
            dest = getDestination(param, getImageTypes(imageIndex), width, height);
        } else {
            dest = bandSubsetDestination(param, struct, width, height,
                    srcBands != null ? srcBands.length : outputBands(struct));
            checkReadParamBandSettings(param, outputBands(struct),
                    dest.getRaster().getNumBands());
        }
        int subX = param != null ? param.getSourceXSubsampling() : 1;
        int subY = param != null ? param.getSourceYSubsampling() : 1;

        DecodedImage img = decodeRegion(srcRegion, reduction);
        if (abortRequested()) {
            processReadAborted();
            return dest;
        }

        if (srcBands != null || dstBands != null) {
            fillWithBandSelection(img, dest.getRaster(), srcRegion, destRegion,
                    subX, subY, srcBands, dstBands);
            processImageComplete();
            return dest;
        }

        WritableRaster raster = dest.getRaster();
        int outBands = outputBands(img);
        boolean sycc = img.colourSpace == DecodedImage.ColourSpace.SYCC
                && img.colourChannels.length >= 3;

        if (raster.getNumBands() == outBands) {
            // row-buffered fill, written straight into the data buffer
            // when the layout allows
            RowWriter writer = new RowWriter(raster, outBands);
            int[] row = new int[destRegion.width * outBands];
            for (int dy = 0; dy < destRegion.height; dy++) {
                int sy = srcRegion.y + dy * subY;
                fillRow(img, row, srcRegion.x, subX, destRegion.width, sy, sycc);
                writer.write(destRegion.x, destRegion.y + dy, destRegion.width, row);
                processImageProgress(90.0f + 10.0f * (dy + 1) / destRegion.height);
                if (abortRequested()) {
                    processReadAborted();
                    return dest;
                }
            }
        } else {
            // custom destination with a different band count
            int bands = Math.min(outBands, raster.getNumBands());
            int[] pixel = new int[outBands];
            for (int dy = 0; dy < destRegion.height; dy++) {
                int sy = srcRegion.y + dy * subY;
                for (int dx = 0; dx < destRegion.width; dx++) {
                    int sx = srcRegion.x + dx * subX;
                    readPixel(img, sx, sy, pixel, sycc);
                    for (int b = 0; b < bands; b++) {
                        raster.setSample(destRegion.x + dx, destRegion.y + dy, b, pixel[b]);
                    }
                }
                processImageProgress(90.0f + 10.0f * (dy + 1) / destRegion.height);
                if (abortRequested()) {
                    processReadAborted();
                    return dest;
                }
            }
        }
        processImageComplete();
        return dest;
    }

    /** Destination image for reads with a band subset selected. */
    private BufferedImage bandSubsetDestination(ImageReadParam param, DecodedImage struct,
                                                int width, int height, int bands)
            throws IOException {
        if (param != null && param.getDestination() != null) {
            return param.getDestination();
        }
        int maxDepth = 1;
        for (int d : struct.depth) {
            maxDepth = Math.max(maxDepth, d);
        }
        int dataType = maxDepth <= 8 ? DataBuffer.TYPE_BYTE : DataBuffer.TYPE_USHORT;
        boolean hasAlpha = bands == 2 || bands == 4;
        ColorSpace cs = ColorSpace.getInstance(bands >= 3
                ? ColorSpace.CS_sRGB : ColorSpace.CS_GRAY);
        int[] offs = new int[bands];
        for (int i = 0; i < bands; i++) {
            offs[i] = i;
        }
        ImageTypeSpecifier its = ImageTypeSpecifier.createInterleaved(
                cs, offs, dataType, hasAlpha, false);
        Rectangle srcRegion = new Rectangle(0, 0, 0, 0);
        Rectangle destRegion = new Rectangle(0, 0, 0, 0);
        computeRegions(param, width, height, null, srcRegion, destRegion);
        return its.createBufferedImage(destRegion.x + destRegion.width,
                destRegion.y + destRegion.height);
    }

    /** General fill honoring source/destination band selections. */
    private void fillWithBandSelection(DecodedImage img, WritableRaster raster,
                                       Rectangle srcRegion, Rectangle destRegion,
                                       int subX, int subY, int[] srcBands, int[] dstBands) {
        int outBands = outputBands(img);
        boolean sycc = img.colourSpace == DecodedImage.ColourSpace.SYCC
                && img.colourChannels.length >= 3;
        int n = srcBands != null ? srcBands.length
                : (dstBands != null ? dstBands.length : outBands);
        int[] pixel = new int[outBands];
        for (int dy = 0; dy < destRegion.height; dy++) {
            int sy = srcRegion.y + dy * subY;
            for (int dx = 0; dx < destRegion.width; dx++) {
                int sx = srcRegion.x + dx * subX;
                readPixel(img, sx, sy, pixel, sycc);
                for (int b = 0; b < n; b++) {
                    int sb = srcBands != null ? srcBands[b] : b;
                    int db = dstBands != null ? dstBands[b] : b;
                    raster.setSample(destRegion.x + dx, destRegion.y + dy, db, pixel[sb]);
                }
            }
        }
    }

    /**
     * Writes interleaved rows into a raster, storing straight into the
     * backing byte/ushort array when the sample model is the plain
     * pixel-interleaved layout the reader itself produces.
     */
    private static final class RowWriter {
        private final WritableRaster raster;
        private final byte[] bytes;
        private final short[] shorts;
        private final int scan;
        private final int pixelStride;
        private final int baseOffset;

        RowWriter(WritableRaster raster, int nb) {
            this.raster = raster;
            byte[] b = null;
            short[] s = null;
            int sc = 0;
            int ps = 0;
            int off = 0;
            if (raster.getSampleModel()
                    instanceof java.awt.image.PixelInterleavedSampleModel psm
                    && psm.getNumBands() == nb && psm.getPixelStride() == nb) {
                boolean identity = true;
                int[] offs = psm.getBandOffsets();
                for (int i = 0; i < nb; i++) {
                    identity &= offs[i] == i;
                }
                if (identity) {
                    java.awt.image.DataBuffer db = raster.getDataBuffer();
                    sc = psm.getScanlineStride();
                    ps = nb;
                    off = db.getOffset();
                    if (db instanceof java.awt.image.DataBufferByte dbb
                            && dbb.getNumBanks() == 1) {
                        b = dbb.getData();
                    } else if (db instanceof java.awt.image.DataBufferUShort dbu
                            && dbu.getNumBanks() == 1) {
                        s = dbu.getData();
                    }
                }
            }
            this.bytes = b;
            this.shorts = s;
            this.scan = sc;
            this.pixelStride = ps;
            this.baseOffset = off;
        }

        void write(int x, int y, int w, int[] row) {
            int n = w * pixelStride;
            if (bytes != null) {
                int p = baseOffset
                        + (y - raster.getSampleModelTranslateY()) * scan
                        + (x - raster.getSampleModelTranslateX()) * pixelStride;
                for (int i = 0; i < n; i++) {
                    bytes[p + i] = (byte) row[i];
                }
            } else if (shorts != null) {
                int p = baseOffset
                        + (y - raster.getSampleModelTranslateY()) * scan
                        + (x - raster.getSampleModelTranslateX()) * pixelStride;
                for (int i = 0; i < n; i++) {
                    shorts[p + i] = (short) row[i];
                }
            } else {
                raster.setPixels(x, y, w, 1, row);
            }
        }
    }

    /** Fills one interleaved output row starting at source column sx0. */
    private void fillRow(DecodedImage img, int[] row, int sx0, int subX, int count,
                         int sy, boolean sycc) {
        int nb = outputBands(img);
        int colour = img.colourChannels.length >= 3 ? 3 : 1;
        for (int i = 0; i < colour; i++) {
            fillBand(img, img.colourChannels[i], row, i, nb, sx0, subX, count, sy);
        }
        if (img.alphaChannel >= 0) {
            fillBand(img, img.alphaChannel, row, colour, nb, sx0, subX, count, sy);
        }
        if (sycc) {
            int d = img.depth[img.colourChannels[0]];
            int off = 1 << (d - 1);
            int max = (1 << d) - 1;
            for (int i = 0; i < count; i++) {
                int p = i * nb;
                float yv = row[p];
                float cb = row[p + 1] - off;
                float cr = row[p + 2] - off;
                row[p] = clamp(Math.round(yv + 1.402f * cr), max);
                row[p + 1] = clamp(Math.round(yv - 0.344136f * cb - 0.714136f * cr), max);
                row[p + 2] = clamp(Math.round(yv + 1.772f * cb), max);
            }
        }
    }

    /**
     * Fills one band of an interleaved row buffer from channel {@code c},
     * with a tight inner loop when the channel is not subsampled.
     */
    private void fillBand(DecodedImage img, int c, int[] row, int off, int nb,
                          int sx0, int subX, int count, int sy) {
        int[] src = img.samples[c];
        int cw = img.chanWidth[c];
        int refY = img.gridY0 - img.regionY + sy;
        int cy = Math.floorDiv(refY, img.dy[c]) - img.chanY0[c];
        cy = Math.max(0, Math.min(img.chanHeight[c] - 1, cy));
        int rowBase = cy * cw;
        int signedOff = img.signed[c] ? 1 << (img.depth[c] - 1) : 0;
        int refX0 = img.gridX0 - img.regionX + sx0;
        int dxc = img.dx[c];
        if (dxc == 1) {
            int cx = refX0 - img.chanX0[c];
            long last = cx + (long) (count - 1) * subX;
            if (cx >= 0 && last < cw) {
                int p = off;
                for (int i = 0; i < count; i++, p += nb, cx += subX) {
                    row[p] = src[rowBase + cx] + signedOff;
                }
                return;
            }
        }
        int p = off;
        for (int i = 0; i < count; i++, p += nb) {
            int cx = Math.floorDiv(refX0 + i * subX, dxc) - img.chanX0[c];
            cx = Math.max(0, Math.min(cw - 1, cx));
            row[p] = src[rowBase + cx] + signedOff;
        }
    }

    @Override
    public BufferedImage readTile(int imageIndex, int tileX, int tileY) throws IOException {
        checkIndex(imageIndex);
        DecodedImage s = shape();
        if (tileX < 0 || tileX >= s.numXTiles || tileY < 0 || tileY >= s.numYTiles) {
            throw new IllegalArgumentException("Tile (" + tileX + "," + tileY
                    + ") outside the " + s.numXTiles + "x" + s.numYTiles + " tile grid");
        }
        Rectangle tile = tileRect(s, tileX, tileY);
        ImageReadParam param = getDefaultReadParam();
        param.setSourceRegion(tile);
        return read(imageIndex, param);
    }

    /** Tile bounds in image-region coordinates, clipped to the image. */
    private static Rectangle tileRect(DecodedImage s, int tileX, int tileY) {
        int x0 = Math.max(s.tileGridXOff + tileX * s.tileWidth, 0);
        int y0 = Math.max(s.tileGridYOff + tileY * s.tileHeight, 0);
        int x1 = Math.min(s.tileGridXOff + (tileX + 1) * s.tileWidth, s.imageWidth);
        int y1 = Math.min(s.tileGridYOff + (tileY + 1) * s.tileHeight, s.imageHeight);
        return new Rectangle(x0, y0, x1 - x0, y1 - y0);
    }

    /** Fills {@code pixel} with the output bands at image position (x, y). */
    private void readPixel(DecodedImage img, int x, int y, int[] pixel, boolean sycc) {
        int colour = img.colourChannels.length >= 3 ? 3 : 1;
        for (int i = 0; i < colour; i++) {
            pixel[i] = channelSample(img, img.colourChannels[i], x, y);
        }
        if (sycc) {
            int d = img.depth[img.colourChannels[0]];
            int off = 1 << (d - 1);
            int max = (1 << d) - 1;
            float yv = pixel[0];
            float cb = pixel[1] - off;
            float cr = pixel[2] - off;
            pixel[0] = clamp(Math.round(yv + 1.402f * cr), max);
            int g = clamp(Math.round(yv - 0.344136f * cb - 0.714136f * cr), max);
            int b = clamp(Math.round(yv + 1.772f * cb), max);
            pixel[1] = g;
            pixel[2] = b;
        }
        if (img.alphaChannel >= 0) {
            pixel[colour] = channelSample(img, img.alphaChannel, x, y);
        }
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : Math.min(v, max);
    }

    /**
     * Channel sample at absolute image position (x, y); signed channels are
     * offset into their unsigned display range.
     */
    private int channelSample(DecodedImage img, int c, int x, int y) {
        int v = img.sampleAt(c, x, y);
        if (img.signed[c]) {
            v += 1 << (img.depth[c] - 1);
        }
        return v;
    }

    // ---- raw raster access ----

    @Override
    public boolean canReadRaster() {
        return true;
    }

    @Override
    public Raster readRaster(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        int reduction = reductionOf(param);
        DecodedImage struct = reduction == 0 ? shape() : decoder().shape(reduction);
        Rectangle srcRegion = new Rectangle(0, 0, 0, 0);
        Rectangle destRegion = new Rectangle(0, 0, 0, 0);
        computeRegions(param, struct.imageWidth, struct.imageHeight,
                null, srcRegion, destRegion);
        int subX = param != null ? param.getSourceXSubsampling() : 1;
        int subY = param != null ? param.getSourceYSubsampling() : 1;
        DecodedImage img = decodeRegion(srcRegion, reduction);
        int[] srcBands = param != null ? param.getSourceBands() : null;
        int[] channels;
        if (srcBands != null) {
            for (int b : srcBands) {
                if (b < 0 || b >= img.numChannels) {
                    throw new IllegalArgumentException("Source band " + b + " out of range");
                }
            }
            channels = srcBands.clone();
        } else {
            channels = new int[img.numChannels];
            for (int i = 0; i < channels.length; i++) {
                channels[i] = i;
            }
        }
        int maxDepth = 1;
        for (int c : channels) {
            maxDepth = Math.max(maxDepth, img.depth[c]);
        }
        int nb = channels.length;
        WritableRaster raster;
        if (maxDepth <= 16) {
            raster = Raster.createInterleavedRaster(
                    maxDepth <= 8 ? DataBuffer.TYPE_BYTE : DataBuffer.TYPE_USHORT,
                    destRegion.width, destRegion.height, nb, null);
        } else {
            // deep components need 32-bit sample storage
            int[] offs = new int[nb];
            for (int i = 0; i < nb; i++) {
                offs[i] = i;
            }
            raster = Raster.createWritableRaster(
                    new java.awt.image.PixelInterleavedSampleModel(
                            DataBuffer.TYPE_INT, destRegion.width, destRegion.height,
                            nb, destRegion.width * nb, offs), null);
        }
        RowWriter writer = new RowWriter(raster, nb);
        int[] row = new int[destRegion.width * nb];
        for (int dy = 0; dy < destRegion.height; dy++) {
            int sy = srcRegion.y + dy * subY;
            for (int i = 0; i < nb; i++) {
                fillBand(img, channels[i], row, i, nb, srcRegion.x, subX,
                        destRegion.width, sy);
            }
            writer.write(0, dy, destRegion.width, row);
        }
        return raster;
    }

    // ---- metadata ----

    @Override
    public IIOMetadata getStreamMetadata() {
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return new CygnusMetadata(shape(), decoder().codestream(),
                decoder().containerInfo());
    }

    /** Decoder warnings collected so far, for diagnostics. */
    public List<String> decoderWarnings() {
        return warnings != null ? warnings : List.of();
    }

    @Override
    public void dispose() {
        decoder = null;
        shape = null;
        cached = null;
        super.dispose();
    }
}
