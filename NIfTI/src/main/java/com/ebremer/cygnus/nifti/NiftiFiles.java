package com.ebremer.cygnus.nifti;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * How NIfTI files are named, and which file goes with which.
 *
 * <p>Four layouts, and the name says which:</p>
 *
 * <table border="1">
 *   <caption>NIfTI file naming</caption>
 *   <tr><th>Name</th><th>Holds</th></tr>
 *   <tr><td>{@code x.nii}</td><td>header and voxels together</td></tr>
 *   <tr><td>{@code x.nii.gz}</td><td>the same, gzipped — what FSL writes by default</td></tr>
 *   <tr><td>{@code x.hdr} + {@code x.img}</td><td>header and voxels apart</td></tr>
 *   <tr><td>{@code x.hdr.gz} + {@code x.img.gz}</td><td>the same, each gzipped</td></tr>
 * </table>
 *
 * <p>The name is a convention, not a guarantee. Whether a file is a single
 * file or half a pair is settled by its magic ({@code n+1}/{@code n+2} against
 * {@code ni1}/{@code ni2}), and whether it is gzipped is settled by its first
 * two bytes — {@link #GZIP_MAGIC} — not by whether it ends in {@code .gz}.
 * These methods are for finding the other half of a pair and for choosing
 * what to write; nothing here decides how a file is read.</p>
 */
public final class NiftiFiles {

    /** The two bytes every gzip stream begins with. */
    public static final byte[] GZIP_MAGIC = {0x1F, (byte) 0x8B};

    private static final String NII = ".nii";
    private static final String HDR = ".hdr";
    private static final String IMG = ".img";
    private static final String GZ = ".gz";

    private NiftiFiles() {
    }

    /** Whether {@code first} begins with the gzip magic. This, not the name, is what decides. */
    public static boolean looksGzipped(byte[] first) {
        return first.length >= 2
                && first[0] == GZIP_MAGIC[0]
                && first[1] == GZIP_MAGIC[1];
    }

    /** Whether the name ends in {@code .gz}, in any case. */
    public static boolean hasGzipSuffix(Path path) {
        return lower(path).endsWith(GZ);
    }

    /** Whether the name is that of a single-file image: {@code .nii} or {@code .nii.gz}. */
    public static boolean isSingleFileName(Path path) {
        return stripGz(lower(path)).endsWith(NII);
    }

    /** Whether the name is that of a pair's header: {@code .hdr} or {@code .hdr.gz}. */
    public static boolean isHeaderName(Path path) {
        return stripGz(lower(path)).endsWith(HDR);
    }

    /** Whether the name is that of a pair's image: {@code .img} or {@code .img.gz}. */
    public static boolean isImageName(Path path) {
        return stripGz(lower(path)).endsWith(IMG);
    }

    /** Whether the name is one of the four this module recognizes. */
    public static boolean isNiftiName(Path path) {
        return isSingleFileName(path) || isHeaderName(path) || isImageName(path);
    }

    /**
     * The name without its NIfTI suffix and without {@code .gz}:
     * {@code sub-01_T1w.nii.gz} gives {@code sub-01_T1w}.
     */
    public static String baseName(Path path) {
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        int end = name.length();
        if (lower.endsWith(GZ)) {
            end -= GZ.length();
            lower = lower.substring(0, end);
        }
        if (lower.endsWith(NII) || lower.endsWith(HDR) || lower.endsWith(IMG)) {
            end -= 4;
        }
        return name.substring(0, end);
    }

    /**
     * The {@code .img} that goes with a {@code .hdr}, keeping the header's own
     * casing and its {@code .gz} if it had one. This is the name a pair
     * <em>should</em> use; {@link #findImageFile} is what looks for the one
     * that is actually there.
     */
    public static Path imageFileFor(Path headerPath) {
        return sibling(headerPath, HDR, IMG);
    }

    /** The {@code .hdr} that goes with an {@code .img}, by the same rule. */
    public static Path headerFileFor(Path imagePath) {
        return sibling(imagePath, IMG, HDR);
    }

    /**
     * The image file that exists for {@code headerPath}, trying the matching
     * name first and then the other compression.
     *
     * <p>A {@code .hdr} beside an {@code .img.gz} is not what the convention
     * says, and it happens: one half gets compressed and the other does not.
     * Looking for both costs a {@code stat} and saves a puzzling failure.</p>
     *
     * @throws IOException if neither candidate exists, naming both
     */
    public static Path findImageFile(Path headerPath) throws IOException {
        Path direct = imageFileFor(headerPath);
        if (Files.isReadable(direct)) {
            return direct;
        }
        Path other = hasGzipSuffix(direct)
                ? direct.resolveSibling(stripGzName(direct))
                : direct.resolveSibling(direct.getFileName() + GZ);
        if (Files.isReadable(other)) {
            return other;
        }
        throw new IOException(headerPath.getFileName()
                + " is the header of a pair, and its image file is missing: looked for "
                + direct.getFileName() + " and " + other.getFileName()
                + " beside it");
    }

    /**
     * What to call the header and image of a pair based on {@code path},
     * whatever suffix it arrived with. {@code [0]} is the header,
     * {@code [1]} the image.
     */
    public static Path[] pairNamesFor(Path path, boolean gzip) {
        String base = baseName(path);
        Path dir = path.toAbsolutePath().getParent();
        String suffix = gzip ? GZ : "";
        Path header = dir.resolve(base + HDR + suffix);
        Path image = dir.resolve(base + IMG + suffix);
        return new Path[] {header, image};
    }

    private static Path sibling(Path path, String from, String to) {
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        boolean gz = lower.endsWith(GZ);
        String stem = gz ? name.substring(0, name.length() - GZ.length()) : name;
        String stemLower = gz ? lower.substring(0, lower.length() - GZ.length()) : lower;
        if (!stemLower.endsWith(from)) {
            throw new IllegalArgumentException(name + " does not end in " + from);
        }
        // keep the case the file used: .HDR beside .IMG, .hdr beside .img
        String replaced = stem.substring(0, stem.length() - from.length())
                + matchCase(stem.substring(stem.length() - from.length()), to);
        return path.resolveSibling(replaced + (gz ? name.substring(stem.length()) : ""));
    }

    /** {@code to} in whatever case {@code sample} was written in. */
    private static String matchCase(String sample, String to) {
        return sample.equals(sample.toUpperCase(Locale.ROOT))
                ? to.toUpperCase(Locale.ROOT) : to;
    }

    private static String stripGzName(Path path) {
        String name = path.getFileName().toString();
        return name.substring(0, name.length() - GZ.length());
    }

    private static String lower(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private static String stripGz(String lowerName) {
        return lowerName.endsWith(GZ)
                ? lowerName.substring(0, lowerName.length() - GZ.length()) : lowerName;
    }
}
