package com.ebremer.cygnus.svs.imageio;

import com.ebremer.cygnus.svs.AperioImageDescription;
import com.ebremer.cygnus.svs.Jp2kTileDecoder;
import com.ebremer.cygnus.svs.SVSDirectory;
import com.ebremer.cygnus.svs.SVSStructure;
import com.twelvemonkeys.imageio.plugins.tiff.TIFFImageReaderSpi;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.event.IIOReadWarningListener;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/**
 * ImageIO reader for Aperio SVS whole-slide images.
 *
 * <p>The image indices of this reader are the pyramid levels, full resolution
 * first: {@code getNumImages} is the number of levels, and {@code read(level,
 * param)} decodes from that level. The label, macro and thumbnail images that
 * Aperio stores alongside the pyramid are not levels; they are reached through
 * {@link #readAssociatedImage(String)}, and the thumbnail also through the
 * ImageIO thumbnail API on image 0.</p>
 *
 * <p>Level 0 of a slide is routinely gigapixels, so
 * {@code ImageIO.read(svsFile)} will try to allocate all of it. Ask for this
 * reader by name and read a region instead:</p>
 *
 * <pre>{@code
 * ImageReader reader = ImageIO.getImageReadersByFormatName("svs").next();
 * reader.setInput(ImageIO.createImageInputStream(file));
 *
 * ImageReadParam param = reader.getDefaultReadParam();
 * param.setSourceRegion(new Rectangle(20000, 15000, 1024, 1024));
 * BufferedImage tissue = reader.read(0, param);
 * }</pre>
 *
 * <p>The generic TIFF readers accept a slide too, so if you reach for one
 * through {@code ImageIO.read} or {@code ImageIO.getImageReaders} rather than
 * by name, see {@link SVSImageReaderSpi#prioritize()}.</p>
 *
 * <p>The TIFF container and its JPEG, LZW and uncompressed directories are
 * decoded by the TwelveMonkeys TIFF reader. Aperio's private JPEG 2000 tile
 * compressions, which no TIFF codec covers, are decoded by the Cygnus JPEG
 * 2000 decoder.</p>
 */
public final class SVSImageReader extends ImageReader {

    private static final ImageTypeSpecifier RGB8 = ImageTypeSpecifier.createInterleaved(
            ColorSpace.getInstance(ColorSpace.CS_sRGB), new int[] {0, 1, 2},
            DataBuffer.TYPE_BYTE, false, false);

    private final Forwarder forwarder = new Forwarder();

    private SVSStructure structure;
    private ImageReader tiff;
    private boolean readingThumbnail;

    public SVSImageReader(ImageReaderSpi provider) {
        super(provider);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        // A slide is read by seeking to tiles, so the forward-only hint cannot be honoured.
        super.setInput(input, false, ignoreMetadata);
        release();
    }

    private ImageInputStream stream() throws IIOException {
        if (input == null) {
            throw new IllegalStateException("No input set");
        }
        if (!(input instanceof ImageInputStream stream)) {
            throw new IIOException("Input must be an ImageInputStream");
        }
        return stream;
    }

    private SVSStructure structure() throws IOException {
        if (structure == null) {
            SVSStructure parsed = SVSStructure.read(stream());
            if (!parsed.isAperio()) {
                processWarningOccurred("Not an Aperio SVS file: the first directory's "
                        + "ImageDescription does not start with \"Aperio\". Reading its tiled "
                        + "directories as a pyramid anyway.");
            }
            structure = parsed;
        }
        return structure;
    }

    /** The TIFF reader that decodes everything but the JPEG 2000 tiles. */
    private ImageReader tiff() throws IOException {
        ImageInputStream stream = stream();
        if (tiff == null) {
            ImageReader reader = new TIFFImageReaderSpi().createReaderInstance(null);
            reader.setInput(stream, false, isIgnoringMetadata());
            reader.addIIOReadProgressListener(forwarder);
            reader.addIIOReadWarningListener(forwarder);
            tiff = reader;
        }
        // The delegate reads the TIFF header from wherever the stream happens to be, and it
        // shares the stream with the structure parse and the JPEG 2000 tile reads, which leave
        // it wherever they finished. Rewind before it can look.
        stream.seek(0);
        return tiff;
    }

    private SVSDirectory level(int imageIndex) throws IOException {
        List<SVSDirectory> levels = structure().levels();
        if (imageIndex < 0 || imageIndex >= levels.size()) {
            throw new IndexOutOfBoundsException("Level " + imageIndex + " outside 0.."
                    + (levels.size() - 1));
        }
        return levels.get(imageIndex);
    }

    // ---- the pyramid, as ImageIO images ----

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        return structure().levels().size();
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        return level(imageIndex).width();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        return level(imageIndex).height();
    }

    @Override
    public boolean isImageTiled(int imageIndex) throws IOException {
        return level(imageIndex).isTiled();
    }

    @Override
    public int getTileWidth(int imageIndex) throws IOException {
        SVSDirectory dir = level(imageIndex);
        return dir.isTiled() ? dir.tileWidth() : dir.width();
    }

    @Override
    public int getTileHeight(int imageIndex) throws IOException {
        SVSDirectory dir = level(imageIndex);
        return dir.isTiled() ? dir.tileHeight() : dir.height();
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        return imageTypes(level(imageIndex));
    }

    @Override
    public ImageTypeSpecifier getRawImageType(int imageIndex) throws IOException {
        SVSDirectory dir = level(imageIndex);
        return dir.isJpeg2000() ? RGB8 : tiff().getRawImageType(dir.index());
    }

    private Iterator<ImageTypeSpecifier> imageTypes(SVSDirectory dir) throws IOException {
        return dir.isJpeg2000() ? List.of(RGB8).iterator() : tiff().getImageTypes(dir.index());
    }

    @Override
    public ImageReadParam getDefaultReadParam() {
        return new ImageReadParam();
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        SVSDirectory dir = level(imageIndex);
        clearAbortRequest();
        processImageStarted(imageIndex);
        BufferedImage image = decode(dir, param);
        if (abortRequested()) {
            processReadAborted();
        } else {
            processImageComplete();
        }
        return image;
    }

    @Override
    public BufferedImage readTile(int imageIndex, int tileX, int tileY) throws IOException {
        SVSDirectory dir = level(imageIndex);
        if (!dir.isTiled()) {
            if (tileX != 0 || tileY != 0) {
                throw new IllegalArgumentException("Level " + imageIndex + " is not tiled");
            }
            return read(imageIndex, null);
        }
        if (tileX < 0 || tileX >= dir.tilesAcross() || tileY < 0 || tileY >= dir.tilesDown()) {
            throw new IllegalArgumentException("Tile (" + tileX + "," + tileY + ") outside the "
                    + dir.tilesAcross() + "x" + dir.tilesDown() + " tile grid of level "
                    + imageIndex);
        }
        int x = tileX * dir.tileWidth();
        int y = tileY * dir.tileHeight();
        ImageReadParam param = getDefaultReadParam();
        param.setSourceRegion(new Rectangle(x, y,
                Math.min(dir.tileWidth(), dir.width() - x),
                Math.min(dir.tileHeight(), dir.height() - y)));
        return read(imageIndex, param);
    }

    /** Decodes a directory through whichever path its compression needs. */
    private BufferedImage decode(SVSDirectory dir, ImageReadParam param) throws IOException {
        return dir.isJpeg2000() ? decodeJpeg2000(dir, param) : tiff().read(dir.index(), param);
    }

    private BufferedImage decodeJpeg2000(SVSDirectory dir, ImageReadParam param)
            throws IOException {
        Rectangle srcRegion = new Rectangle(0, 0, 0, 0);
        Rectangle destRegion = new Rectangle(0, 0, 0, 0);
        computeRegions(param, dir.width(), dir.height(),
                param != null ? param.getDestination() : null, srcRegion, destRegion);
        BufferedImage dest = getDestination(param, imageTypes(dir), dir.width(), dir.height());
        checkReadParamBandSettings(param, 3, dest.getRaster().getNumBands());

        new Jp2kTileDecoder(stream(), dir).readRegion(
                srcRegion,
                param != null ? param.getSourceXSubsampling() : 1,
                param != null ? param.getSourceYSubsampling() : 1,
                dest.getRaster(), destRegion,
                param != null ? param.getSourceBands() : null,
                param != null ? param.getDestinationBands() : null,
                (done, total) -> {
                    progress(100.0f * done / total);
                    return !abortRequested();
                });
        return dest;
    }

    // ---- label, macro and thumbnail ----

    /**
     * Names of the images Aperio stores alongside the pyramid, typically
     * {@code thumbnail}, {@code label} and {@code macro}.
     */
    public List<String> getAssociatedImageNames() throws IOException {
        return List.copyOf(structure().associatedImages().keySet());
    }

    /** Reads an associated image whole; see {@link #getAssociatedImageNames()}. */
    public BufferedImage readAssociatedImage(String name) throws IOException {
        SVSDirectory dir = structure().associatedImages().get(name);
        if (dir == null) {
            throw new IllegalArgumentException("No associated image \"" + name + "\"; this slide "
                    + "has " + getAssociatedImageNames());
        }
        clearAbortRequest();
        return decode(dir, null);
    }

    @Override
    public boolean readerSupportsThumbnails() {
        return true;
    }

    @Override
    public int getNumThumbnails(int imageIndex) throws IOException {
        level(imageIndex);
        return imageIndex == 0
                && structure().associatedImages().containsKey(SVSStructure.THUMBNAIL) ? 1 : 0;
    }

    @Override
    public int getThumbnailWidth(int imageIndex, int thumbnailIndex) throws IOException {
        return thumbnail(imageIndex, thumbnailIndex).width();
    }

    @Override
    public int getThumbnailHeight(int imageIndex, int thumbnailIndex) throws IOException {
        return thumbnail(imageIndex, thumbnailIndex).height();
    }

    @Override
    public BufferedImage readThumbnail(int imageIndex, int thumbnailIndex) throws IOException {
        SVSDirectory dir = thumbnail(imageIndex, thumbnailIndex);
        clearAbortRequest();
        readingThumbnail = true;
        try {
            processThumbnailStarted(imageIndex, thumbnailIndex);
            BufferedImage image = decode(dir, null);
            if (abortRequested()) {
                processReadAborted();
            } else {
                processThumbnailComplete();
            }
            return image;
        } finally {
            readingThumbnail = false;
        }
    }

    private SVSDirectory thumbnail(int imageIndex, int thumbnailIndex) throws IOException {
        if (thumbnailIndex != 0 || getNumThumbnails(imageIndex) == 0) {
            throw new IndexOutOfBoundsException("No thumbnail " + thumbnailIndex
                    + " for image " + imageIndex);
        }
        return structure().associatedImages().get(SVSStructure.THUMBNAIL);
    }

    // ---- slide properties ----

    /** The parsed SVS layout: every directory, the pyramid and the associated images. */
    public SVSStructure getStructure() throws IOException {
        return structure();
    }

    /** Number of pyramid levels, i.e. {@code getNumImages(false)}. */
    public int getNumLevels() throws IOException {
        return structure().levels().size();
    }

    /** How much smaller a level is than full resolution; 1.0 for level 0. */
    public double getLevelDownsample(int level) throws IOException {
        level(level);
        return structure().downsample(level);
    }

    /** The level to read from when rendering the slide at the given scale. */
    public int getBestLevelForDownsample(double downsample) throws IOException {
        return structure().bestLevelForDownsample(downsample);
    }

    /** The full-resolution directory's Aperio ImageDescription, parsed. */
    public AperioImageDescription getAperioDescription() throws IOException {
        return structure().description();
    }

    /** The slide's Aperio properties: {@code AppMag}, {@code MPP}, {@code ScanScope ID}, ... */
    public Map<String, String> getProperties() throws IOException {
        return structure().description().properties();
    }

    /** Microns per pixel at full resolution, if the scanner recorded it. */
    public OptionalDouble getMicronsPerPixel() throws IOException {
        return structure().description().micronsPerPixel();
    }

    /** Objective magnification, if the scanner recorded it. */
    public OptionalDouble getMagnification() throws IOException {
        return structure().description().magnification();
    }

    // ---- metadata and lifecycle ----

    @Override
    public IIOMetadata getStreamMetadata() {
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        return new SVSMetadata(structure(), imageIndex, level(imageIndex));
    }

    @Override
    public synchronized void abort() {
        super.abort();
        ImageReader delegate = tiff;
        if (delegate != null) {
            delegate.abort();
        }
    }

    @Override
    public void dispose() {
        release();
        super.dispose();
    }

    private void release() {
        if (tiff != null) {
            tiff.dispose();
            tiff = null;
        }
        structure = null;
    }

    private void progress(float percent) {
        if (readingThumbnail) {
            processThumbnailProgress(percent);
        } else {
            processImageProgress(percent);
        }
    }

    /**
     * Relays the delegate's progress and warnings to this reader's listeners.
     * Start and completion are announced by this reader itself, since the
     * delegate reports TIFF directory indices rather than level indices.
     */
    private final class Forwarder implements IIOReadProgressListener, IIOReadWarningListener {

        @Override
        public void imageProgress(ImageReader source, float percentageDone) {
            progress(percentageDone);
        }

        @Override
        public void warningOccurred(ImageReader source, String warning) {
            processWarningOccurred(warning);
        }

        @Override
        public void sequenceStarted(ImageReader source, int minIndex) {
        }

        @Override
        public void sequenceComplete(ImageReader source) {
        }

        @Override
        public void imageStarted(ImageReader source, int imageIndex) {
        }

        @Override
        public void imageComplete(ImageReader source) {
        }

        @Override
        public void thumbnailStarted(ImageReader source, int imageIndex, int thumbnailIndex) {
        }

        @Override
        public void thumbnailProgress(ImageReader source, float percentageDone) {
        }

        @Override
        public void thumbnailComplete(ImageReader source) {
        }

        @Override
        public void readAborted(ImageReader source) {
        }
    }
}
