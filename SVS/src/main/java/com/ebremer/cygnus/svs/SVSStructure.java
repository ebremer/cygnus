package com.ebremer.cygnus.svs;

import com.twelvemonkeys.imageio.metadata.CompoundDirectory;
import com.twelvemonkeys.imageio.metadata.Directory;
import com.twelvemonkeys.imageio.metadata.tiff.TIFFReader;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOException;
import javax.imageio.stream.ImageInputStream;

/**
 * The layout of an SVS file: which TIFF directories are pyramid levels, and
 * which are the associated label, macro and thumbnail images.
 *
 * <p>Aperio writes the full-resolution image into directory 0, the reduced
 * pyramid into further tiled directories, and the associated images into
 * stripped ones. Tiling is therefore what separates the two, which is how
 * OpenSlide reads these files as well; the associated images take their names
 * from their own {@link AperioImageDescription}.</p>
 */
public final class SVSStructure {

    private static final int TIFF_VERSION = 42;
    private static final int BIG_TIFF_VERSION = 43;
    private static final int MAX_IFD_ENTRIES = 8192;
    private static final byte[] APERIO = "Aperio".getBytes(StandardCharsets.US_ASCII);

    /** Name given to a stripped directory that does not name itself. */
    public static final String THUMBNAIL = "thumbnail";

    private final List<SVSDirectory> directories;
    private final List<SVSDirectory> levels;
    private final Map<String, SVSDirectory> associated;

    private SVSStructure(List<SVSDirectory> directories) {
        this.directories = List.copyOf(directories);

        List<SVSDirectory> pyramid = new ArrayList<>();
        Map<String, SVSDirectory> images = new LinkedHashMap<>();
        for (int i = 0; i < directories.size(); i++) {
            SVSDirectory dir = directories.get(i);
            if (i == 0 || dir.isTiled()) {
                pyramid.add(dir);
            } else {
                String name = dir.description().associatedImageName().orElse(THUMBNAIL);
                images.put(unique(images, name), dir);
            }
        }
        this.levels = List.copyOf(pyramid);
        this.associated = Collections.unmodifiableMap(images);
    }

    /** Disambiguates a repeated associated-image name rather than dropping the directory. */
    private static String unique(Map<String, SVSDirectory> images, String name) {
        if (!images.containsKey(name)) {
            return name;
        }
        for (int n = 2; ; n++) {
            String candidate = name + " " + n;
            if (!images.containsKey(candidate)) {
                return candidate;
            }
        }
    }

    /** Reads every TIFF directory and classifies it. Leaves the stream's byte order alone. */
    public static SVSStructure read(ImageInputStream stream) throws IOException {
        ByteOrder order = stream.getByteOrder();
        try {
            stream.seek(0);
            Directory root = new TIFFReader().read(stream);
            if (!(root instanceof CompoundDirectory ifds) || ifds.directoryCount() == 0) {
                throw new IIOException("TIFF has no image file directories");
            }
            List<SVSDirectory> directories = new ArrayList<>(ifds.directoryCount());
            for (int i = 0; i < ifds.directoryCount(); i++) {
                directories.add(SVSDirectory.from(i, ifds.getDirectory(i)));
            }
            return new SVSStructure(directories);
        } finally {
            stream.setByteOrder(order);
        }
    }

    /**
     * Whether the stream holds a TIFF whose first directory carries an Aperio
     * {@code ImageDescription}. Reads only the header and the first directory,
     * and restores the stream's position and byte order.
     */
    public static boolean isAperioTiff(ImageInputStream stream) throws IOException {
        ByteOrder order = stream.getByteOrder();
        stream.mark();
        try {
            return sniff(stream);
        } catch (EOFException e) {
            return false;
        } finally {
            stream.reset();
            stream.setByteOrder(order);
        }
    }

    private static boolean sniff(ImageInputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 == 'I' && b1 == 'I') {
            in.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        } else if (b0 == 'M' && b1 == 'M') {
            in.setByteOrder(ByteOrder.BIG_ENDIAN);
        } else {
            return false;
        }

        int version = in.readUnsignedShort();
        boolean big = version == BIG_TIFF_VERSION;
        if (version != TIFF_VERSION && !big) {
            return false;
        }

        long ifdOffset;
        if (big) {
            if (in.readUnsignedShort() != 8 || in.readUnsignedShort() != 0) {
                return false;
            }
            ifdOffset = in.readLong();
        } else {
            ifdOffset = in.readUnsignedInt();
        }
        if (ifdOffset < 8) {
            return false;
        }

        in.seek(ifdOffset);
        long entries = big ? in.readLong() : in.readUnsignedShort();
        if (entries <= 0 || entries > MAX_IFD_ENTRIES) {
            return false;
        }

        int valueFieldSize = big ? 8 : 4;
        for (long i = 0; i < entries; i++) {
            int tag = in.readUnsignedShort();
            in.readUnsignedShort();                                     // field type
            long count = big ? in.readLong() : in.readUnsignedInt();
            long offset = big ? in.readLong() : in.readUnsignedInt();   // or the value itself
            if (tag != Tiff.TAG_IMAGE_DESCRIPTION) {
                continue;
            }
            if (count < APERIO.length || offset < 0) {
                return false;
            }
            // Short values sit in the entry's value field; longer ones at `offset`.
            in.seek(count <= valueFieldSize ? in.getStreamPosition() - valueFieldSize : offset);
            byte[] head = new byte[APERIO.length];
            in.readFully(head);
            return Arrays.equals(head, APERIO);
        }
        return false;
    }

    /** Every TIFF directory, in file order. */
    public List<SVSDirectory> directories() {
        return directories;
    }

    /** The pyramid, full resolution first. */
    public List<SVSDirectory> levels() {
        return levels;
    }

    /** The label, macro and thumbnail images, keyed by name, in file order. */
    public Map<String, SVSDirectory> associatedImages() {
        return associated;
    }

    /** The full-resolution directory's Aperio description, which holds the slide's properties. */
    public AperioImageDescription description() {
        return levels.get(0).description();
    }

    /** Whether the file actually identifies itself as Aperio. */
    public boolean isAperio() {
        return description().isAperio();
    }

    /** How much smaller a level is than full resolution; 1.0 for level 0. */
    public double downsample(int level) {
        SVSDirectory base = levels.get(0);
        SVSDirectory dir = levels.get(level);
        double x = (double) base.width() / dir.width();
        double y = (double) base.height() / dir.height();
        return (x + y) / 2.0;
    }

    /**
     * The most reduced level that is still at least as detailed as
     * {@code downsample} — the level to read from when rendering at that scale.
     */
    public int bestLevelForDownsample(double downsample) {
        int best = 0;
        double bestScale = downsample(0);
        for (int level = 1; level < levels.size(); level++) {
            double scale = downsample(level);
            if (scale <= downsample + 1e-6 && scale > bestScale) {
                best = level;
                bestScale = scale;
            }
        }
        return best;
    }
}
