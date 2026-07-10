package com.ebremer.cygnus.imageio;

import javax.imageio.ImageReadParam;

/**
 * Read parameters for the Cygnus JPEG 2000 reader, adding wavelet
 * resolution reduction: with a reduction of {@code d}, the {@code d}
 * highest DWT levels are discarded and the decoded image is roughly
 * 1/2^d scale. This is essentially free downscaling - the discarded
 * resolutions are not entropy-decoded at all.
 *
 * <p>When a reduction is set, the source region and the reader's output
 * dimensions are all in reduced-image coordinates. Source subsampling
 * composes on top of the reduced image.</p>
 *
 * <p>Instances are returned by
 * {@link CygnusImageReader#getDefaultReadParam()}.</p>
 */
public class CygnusImageReadParam extends ImageReadParam {

    private int resolutionReduction;

    /** Number of DWT levels to discard (0 = full resolution). */
    public int getResolutionReduction() {
        return resolutionReduction;
    }

    /**
     * Sets the number of DWT levels to discard. Must not exceed the
     * codestream's decomposition level count (see
     * {@code Jpeg2000Decoder.maxReduction()}); reads beyond it fail with
     * an exception.
     */
    public void setResolutionReduction(int reduction) {
        if (reduction < 0) {
            throw new IllegalArgumentException("Negative resolution reduction");
        }
        this.resolutionReduction = reduction;
    }
}
