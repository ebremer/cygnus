package com.ebremer.cygnus.ndpi.imageio;

import com.ebremer.cygnus.ndpi.NdpiDirectory;
import com.ebremer.cygnus.ndpi.NdpiStructure;
import com.ebremer.cygnus.ndpi.NdpiTileDecoder;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/**
 * ImageIO reader for Hamamatsu NDPI whole-slide images.
 *
 * <p>The image indices of this reader are the pyramid levels, full resolution
 * first: {@code getNumImages} is the number of levels, and {@code read(level,
 * param)} decodes from that level. The macro and map images the scanner takes
 * of the slide itself are not levels; they are reached through
 * {@link #readAssociatedImage(String)}.</p>
 *
 * <p>Level 0 of a slide is routinely gigapixels, so {@code ImageIO.read(file)}
 * will try to allocate all of it. Ask for this reader by name and read a region
 * instead:</p>
 *
 * <pre>{@code
 * ImageReader reader = ImageIO.getImageReadersByFormatName("ndpi").next();
 * reader.setInput(ImageIO.createImageInputStream(file));
 *
 * ImageReadParam param = reader.getDefaultReadParam();
 * param.setSourceRegion(new Rectangle(20000, 15000, 1024, 1024));
 * BufferedImage tissue = reader.read(0, param);
 * }</pre>
 *
 * <p>A level's tile grid — reported through the standard ImageIO tile API — is
 * the grid of the level JPEG's restart intervals, which are wide and one MCU
 * tall. Reading whole tiles is therefore not the cheap way round a slide that
 * it is in a tiled format; read the region you want.</p>
 *
 * <p>The generic TIFF readers accept an NDPI file (it opens like a TIFF) and
 * would make nonsense of it. See {@link NDPIImageReaderSpi#prioritize()}.</p>
 */
public final class NDPIImageReader extends ImageReader {

    private final Map<Integer, NdpiTileDecoder> decoders = new HashMap<>();

    private NdpiStructure structure;
    private ImageReader jpeg;

    public NDPIImageReader(ImageReaderSpi provider) {
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

    private NdpiStructure structure() throws IOException {
        if (structure == null) {
            structure = NdpiStructure.read(stream());
        }
        return structure;
    }

    /**
     * The JPEG decoder every level and associated image is read through. The
     * TwelveMonkeys plug-in orders itself ahead of the JDK's, so this is that
     * one whenever it is on the classpath.
     */
    private ImageReader jpeg() throws IOException {
        if (jpeg == null) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("JPEG");
            if (!readers.hasNext()) {
                throw new IIOException("No JPEG reader is registered with ImageIO");
            }
            ImageReader reader = readers.next();
            reader.addIIOReadWarningListener((source, warning) -> processWarningOccurred(warning));
            jpeg = reader;
        }
        return jpeg;
    }

    private NdpiDirectory level(int imageIndex) throws IOException {
        List<NdpiDirectory> levels = structure().levels();
        if (imageIndex < 0 || imageIndex >= levels.size()) {
            throw new IndexOutOfBoundsException("Level " + imageIndex + " outside 0.."
                    + (levels.size() - 1));
        }
        return levels.get(imageIndex);
    }

    private NdpiTileDecoder decoder(NdpiDirectory directory) throws IOException {
        NdpiTileDecoder decoder = decoders.get(directory.index());
        if (decoder == null) {
            decoder = new NdpiTileDecoder(stream(), directory, jpeg());
            decoders.put(directory.index(), decoder);
        }
        return decoder;
    }

    // ---- the pyramid, as ImageIO images ----

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        return structure().levels().size();
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        return decoder(level(imageIndex)).width();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        return decoder(level(imageIndex)).height();
    }

    @Override
    public boolean isImageTiled(int imageIndex) throws IOException {
        return decoder(level(imageIndex)).isTiled();
    }

    @Override
    public int getTileWidth(int imageIndex) throws IOException {
        return decoder(level(imageIndex)).tileWidth();
    }

    @Override
    public int getTileHeight(int imageIndex) throws IOException {
        return decoder(level(imageIndex)).tileHeight();
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        return decoder(level(imageIndex)).imageTypes().iterator();
    }

    @Override
    public ImageTypeSpecifier getRawImageType(int imageIndex) throws IOException {
        return decoder(level(imageIndex)).imageTypes().get(0);
    }

    @Override
    public ImageReadParam getDefaultReadParam() {
        return new ImageReadParam();
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        NdpiTileDecoder decoder = decoder(level(imageIndex));
        clearAbortRequest();
        processImageStarted(imageIndex);
        BufferedImage image = decode(decoder, param);
        if (abortRequested()) {
            processReadAborted();
        } else {
            processImageComplete();
        }
        return image;
    }

    @Override
    public BufferedImage readTile(int imageIndex, int tileX, int tileY) throws IOException {
        NdpiTileDecoder decoder = decoder(level(imageIndex));
        if (tileX < 0 || tileX >= decoder.tilesAcross()
                || tileY < 0 || tileY >= decoder.tilesDown()) {
            throw new IllegalArgumentException("Tile (" + tileX + "," + tileY + ") outside the "
                    + decoder.tilesAcross() + "x" + decoder.tilesDown() + " tile grid of level "
                    + imageIndex);
        }
        int x = tileX * decoder.tileWidth();
        int y = tileY * decoder.tileHeight();
        ImageReadParam param = getDefaultReadParam();
        param.setSourceRegion(new Rectangle(x, y,
                Math.min(decoder.tileWidth(), decoder.width() - x),
                Math.min(decoder.tileHeight(), decoder.height() - y)));
        return read(imageIndex, param);
    }

    private BufferedImage decode(NdpiTileDecoder decoder, ImageReadParam param)
            throws IOException {
        int width = decoder.width();
        int height = decoder.height();
        Rectangle srcRegion = new Rectangle(0, 0, 0, 0);
        Rectangle destRegion = new Rectangle(0, 0, 0, 0);
        computeRegions(param, width, height,
                param != null ? param.getDestination() : null, srcRegion, destRegion);

        List<ImageTypeSpecifier> types = decoder.imageTypes();
        BufferedImage dest = destination(param, types, destRegion);
        checkReadParamBandSettings(param, types.get(0).getNumBands(),
                dest.getRaster().getNumBands());

        decoder.readRegion(srcRegion,
                param != null ? param.getSourceXSubsampling() : 1,
                param != null ? param.getSourceYSubsampling() : 1,
                dest, destRegion,
                param != null ? param.getSourceBands() : null,
                param != null ? param.getDestinationBands() : null,
                (done, total) -> {
                    processImageProgress(100.0f * done / total);
                    return !abortRequested();
                });
        return dest;
    }

    /**
     * The image a read writes into, sized to the region asked for.
     *
     * <p>This is what {@link #getDestination} does, minus the one thing that
     * makes it useless here: it rejects any image whose width times height
     * overflows an int — which is most of a slide's full-resolution level —
     * before it so much as looks at how small a region you asked for.</p>
     */
    private BufferedImage destination(ImageReadParam param, List<ImageTypeSpecifier> types,
                                      Rectangle destRegion) throws IIOException {
        if (param != null && param.getDestination() != null) {
            return param.getDestination();
        }
        ImageTypeSpecifier type = param != null ? param.getDestinationType() : null;
        if (type == null) {
            type = types.get(0);
        } else if (!types.contains(type)) {
            throw new IIOException("Destination type from ImageReadParam does not match!");
        }
        return type.createBufferedImage(destRegion.x + destRegion.width,
                destRegion.y + destRegion.height);
    }

    // ---- the pictures of the slide itself ----

    /** Names of the images the scanner took of the slide, typically {@code macro}. */
    public List<String> getAssociatedImageNames() throws IOException {
        return List.copyOf(structure().associatedImages().keySet());
    }

    /** Reads an associated image whole; see {@link #getAssociatedImageNames()}. */
    public BufferedImage readAssociatedImage(String name) throws IOException {
        NdpiDirectory directory = structure().associatedImages().get(name);
        if (directory == null) {
            throw new IllegalArgumentException("No associated image \"" + name + "\"; this slide "
                    + "has " + getAssociatedImageNames());
        }
        clearAbortRequest();
        return decode(decoder(directory), null);
    }

    // ---- slide properties ----

    /** The parsed NDPI layout: every directory, the pyramid, and the rest. */
    public NdpiStructure getStructure() throws IOException {
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

    /** The scanner's own key/value properties, from the NDPI property map. */
    public Map<String, String> getProperties() throws IOException {
        return structure().properties();
    }

    /** Objective power the slide was scanned at, from the source lens. */
    public OptionalDouble getMagnification() throws IOException {
        return structure().magnification();
    }

    /** Microns per pixel at full resolution, from the TIFF resolution tags. */
    public OptionalDouble getMicronsPerPixelX() throws IOException {
        return structure().micronsPerPixelX();
    }

    public OptionalDouble getMicronsPerPixelY() throws IOException {
        return structure().micronsPerPixelY();
    }

    // ---- metadata and lifecycle ----

    @Override
    public IIOMetadata getStreamMetadata() {
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        NdpiDirectory directory = level(imageIndex);
        return new NDPIMetadata(structure(), imageIndex, directory, decoder(directory));
    }

    @Override
    public synchronized void abort() {
        super.abort();
        ImageReader delegate = jpeg;
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
        if (jpeg != null) {
            jpeg.dispose();
            jpeg = null;
        }
        decoders.clear();
        structure = null;
    }
}
