package com.ebremer.cygnus.ndpi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import javax.imageio.IIOException;
import javax.imageio.stream.ImageInputStream;

/**
 * The layout of an NDPI slide: which directories are pyramid levels, which are
 * the associated images, and which are the out-of-focus planes of a z-stack.
 *
 * <p>A directory says which it is through its {@link Ndpi#TAG_SOURCE_LENS}: the
 * objective power it was captured at, or a negative number standing for one of
 * the pictures the scanner took of the slide itself.</p>
 */
public final class NdpiStructure {

    private final List<NdpiDirectory> directories;
    private final List<NdpiDirectory> levels;
    private final List<NdpiDirectory> focusPlanes;
    private final Map<String, NdpiDirectory> associated;

    private NdpiStructure(List<NdpiDirectory> directories, List<NdpiDirectory> levels,
                          List<NdpiDirectory> focusPlanes,
                          Map<String, NdpiDirectory> associated) {
        this.directories = List.copyOf(directories);
        this.levels = List.copyOf(levels);
        this.focusPlanes = List.copyOf(focusPlanes);
        this.associated = Collections.unmodifiableMap(associated);
    }

    public static NdpiStructure read(ImageInputStream stream) throws IOException {
        List<NdpiDirectory> directories = NdpiTiff.read(stream).directories();

        List<NdpiDirectory> levels = new ArrayList<>();
        List<NdpiDirectory> focusPlanes = new ArrayList<>();
        Map<String, NdpiDirectory> associated = new LinkedHashMap<>();
        for (NdpiDirectory directory : directories) {
            OptionalDouble lens = directory.sourceLens();
            if (lens.isEmpty()) {
                continue;                                  // not something NDPI defines
            }
            if (directory.isLevel()) {
                levels.add(directory);
            } else if (directory.isFocusPlane()) {
                focusPlanes.add(directory);
            } else {
                String name = Ndpi.associatedImageName(lens.getAsDouble());
                associated.put(unique(associated, name), directory);
            }
        }
        if (levels.isEmpty()) {
            throw new IIOException("NDPI file has no pyramid levels");
        }
        return new NdpiStructure(directories, levels, focusPlanes, associated);
    }

    private static String unique(Map<String, NdpiDirectory> images, String name) {
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

    /** Every directory, in file order. */
    public List<NdpiDirectory> directories() {
        return directories;
    }

    /** The pyramid, full resolution first. */
    public List<NdpiDirectory> levels() {
        return levels;
    }

    /** The macro and map images, keyed by name. */
    public Map<String, NdpiDirectory> associatedImages() {
        return associated;
    }

    /**
     * The out-of-focus planes of a z-stack, which are not part of the pyramid.
     * Empty for the great majority of slides, which are scanned at one focus.
     */
    public List<NdpiDirectory> focusPlanes() {
        return focusPlanes;
    }

    /** How much smaller a level is than full resolution; 1.0 for level 0. */
    public double downsample(int level) throws IOException {
        NdpiDirectory base = levels.get(0);
        NdpiDirectory directory = levels.get(level);
        double x = (double) base.width() / directory.width();
        double y = (double) base.height() / directory.height();
        return (x + y) / 2.0;
    }

    /**
     * The most reduced level that is still at least as detailed as
     * {@code downsample} — the level to read from when rendering at that scale.
     */
    public int bestLevelForDownsample(double downsample) throws IOException {
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

    /** The scanner's own key/value properties, from the full-resolution level. */
    public Map<String, String> properties() throws IOException {
        return levels.get(0).propertyMap();
    }

    /** Objective power the slide was scanned at. */
    public OptionalDouble magnification() throws IOException {
        return levels.get(0).sourceLens();
    }

    public OptionalDouble micronsPerPixelX() throws IOException {
        return levels.get(0).micronsPerPixelX();
    }

    public OptionalDouble micronsPerPixelY() throws IOException {
        return levels.get(0).micronsPerPixelY();
    }
}
