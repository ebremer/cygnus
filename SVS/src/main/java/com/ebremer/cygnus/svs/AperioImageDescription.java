package com.ebremer.cygnus.svs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The {@code ImageDescription} string Aperio writes into every directory of an
 * SVS file. It is the only place the scanner records the slide's properties,
 * and the only thing that distinguishes the label and macro images from the
 * pyramid.
 *
 * <p>The text is a header block followed by pipe-separated {@code Key = Value}
 * pairs. A pyramid directory looks like</p>
 *
 * <pre>
 * Aperio Image Library v11.2.1
 * 46920x33014 [0,100 46000x32914] (240x240) JPEG/RGB Q=30|AppMag = 20|MPP = 0.4990|...
 * </pre>
 *
 * <p>while the associated images name themselves on the second line:</p>
 *
 * <pre>
 * Aperio Image Library v11.2.1
 * label 387x463
 * </pre>
 *
 * <p>The thumbnail names nothing and starts its second line with the slide
 * dimensions ({@code 46920x33014 -> 1024x732 - }), so a leading letter is what
 * marks a directory as an associated image.</p>
 */
public final class AperioImageDescription {

    private final String raw;
    private final String header;
    private final String summary;
    private final Map<String, String> properties;
    private final String associatedName;

    private AperioImageDescription(String raw, String header, String summary,
                                   Map<String, String> properties, String associatedName) {
        this.raw = raw;
        this.header = header;
        this.summary = summary;
        this.properties = properties;
        this.associatedName = associatedName;
    }

    /** Parses an ImageDescription; a null or empty string yields an empty description. */
    public static AperioImageDescription parse(String text) {
        String raw = text == null ? "" : trimNuls(text);
        String[] fields = raw.split("\\|", -1);

        String block = fields[0];
        int lineEnd = block.indexOf('\n');
        String header;
        String summary;
        if (lineEnd < 0) {
            header = block.trim();
            summary = "";
        } else {
            header = block.substring(0, lineEnd).trim();
            summary = block.substring(lineEnd + 1).trim();
        }

        Map<String, String> properties = new LinkedHashMap<>();
        for (int i = 1; i < fields.length; i++) {
            String field = fields[i];
            int eq = field.indexOf('=');
            if (eq < 0) {
                if (!field.isBlank()) {
                    properties.put(field.trim(), "");
                }
            } else {
                properties.put(field.substring(0, eq).trim(), field.substring(eq + 1).trim());
            }
        }

        return new AperioImageDescription(raw, header, summary,
                Collections.unmodifiableMap(properties), associatedNameOf(summary));
    }

    /**
     * The name an associated image gives itself on the second line, e.g.
     * {@code label} or {@code macro}. Empty for pyramid and thumbnail
     * directories, whose second line starts with the slide dimensions.
     */
    private static String associatedNameOf(String summary) {
        if (summary.isEmpty() || !Character.isLetter(summary.charAt(0))) {
            return null;
        }
        int end = 0;
        while (end < summary.length() && Character.isLetterOrDigit(summary.charAt(end))) {
            end++;
        }
        return summary.substring(0, end).toLowerCase(Locale.ROOT);
    }

    /** Aperio pads the tag with NULs; they are not part of the text. */
    private static String trimNuls(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\0') {
            end--;
        }
        return text.substring(0, end);
    }

    /** The ImageDescription tag verbatim. */
    public String raw() {
        return raw;
    }

    /** First line, e.g. {@code Aperio Image Library v11.2.1}. */
    public String header() {
        return header;
    }

    /** Second line, e.g. {@code label 387x463} or the slide's dimensions and tiling. */
    public String summary() {
        return summary;
    }

    /** The {@code Key = Value} pairs, in file order. */
    public Map<String, String> properties() {
        return properties;
    }

    /** Whether this looks like an Aperio-written description at all. */
    public boolean isAperio() {
        return header.regionMatches(true, 0, "Aperio", 0, 6);
    }

    /** The associated image's name ({@code label}, {@code macro}), if it names itself. */
    public Optional<String> associatedImageName() {
        return Optional.ofNullable(associatedName);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    public OptionalDouble getDouble(String key) {
        String value = properties.get(key);
        if (value == null || value.isBlank()) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Double.parseDouble(value.trim()));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    /** Microns per pixel at full resolution, from the {@code MPP} property. */
    public OptionalDouble micronsPerPixel() {
        return getDouble("MPP");
    }

    /** Objective magnification, from the {@code AppMag} property. */
    public OptionalDouble magnification() {
        return getDouble("AppMag");
    }

    @Override
    public String toString() {
        return raw;
    }
}
