package com.ebremer.cygnus.nifti;

/**
 * {@code ecode} values: what a header extension's payload is.
 *
 * <p>The registry is small and the codes are even numbers from 0 to 44. An
 * unregistered code is not an error — this module carries any extension
 * through byte for byte and interprets none of them, which is what makes a
 * CIFTI-2 file ({@link #CIFTI}, an XML brain-model description riding on a
 * NIfTI-2 volume) survive a read/write cycle intact.</p>
 */
public final class NiftiEcode {

    /** No payload format stated; the bytes mean whatever the writer meant. */
    public static final int IGNORE = 0;

    /** A DICOM data set. */
    public static final int DICOM = 2;

    /** An AFNI XML header. */
    public static final int AFNI = 4;

    /** Plain text. */
    public static final int COMMENT = 6;

    /** An XCEDE XML document. */
    public static final int XCEDE = 8;

    /** JIMDIM dimension information. */
    public static final int JIMDIMINFO = 10;

    /** Workflow forwarding information. */
    public static final int WORKFLOW_FWDS = 12;

    /** FreeSurfer surface data. */
    public static final int FREESURFER = 14;

    /** A pickled Python object. */
    public static final int PYPICKLE = 16;

    /** MIND identification. */
    public static final int MIND_IDENT = 18;

    /** A diffusion b-value. */
    public static final int B_VALUE = 20;

    /** A diffusion gradient direction in spherical coordinates. */
    public static final int SPHERICAL_DIRECTION = 22;

    /** A diffusion-tensor component index. */
    public static final int DT_COMPONENT = 24;

    /** Spherical harmonic degree and order. */
    public static final int SHC_DEGREEORDER = 26;

    /** VoxBo data. */
    public static final int VOXBO = 28;

    /** Caret data. */
    public static final int CARET = 30;

    /** A CIFTI-2 XML document — the brain-model description a CIFTI file is. */
    public static final int CIFTI = 32;

    /** Variable frame timing, for PET. */
    public static final int VARIABLE_FRAME_TIMING = 34;

    /** Evaluation data. */
    public static final int EVAL = 38;

    /** A MATLAB workspace. */
    public static final int MATLAB = 40;

    /** Quantiphyse data. */
    public static final int QUANTIPHYSE = 42;

    /** Magnetic resonance spectroscopy metadata. */
    public static final int MRS = 44;

    /** The largest code in the registry as of this writing. */
    public static final int MAX = 44;

    private NiftiEcode() {
    }

    /** A readable name for {@code code}, or {@code "ecode " + code} for an unregistered one. */
    public static String name(int code) {
        return switch (code) {
            case IGNORE -> "IGNORE";
            case DICOM -> "DICOM";
            case AFNI -> "AFNI";
            case COMMENT -> "COMMENT";
            case XCEDE -> "XCEDE";
            case JIMDIMINFO -> "JIMDIMINFO";
            case WORKFLOW_FWDS -> "WORKFLOW_FWDS";
            case FREESURFER -> "FREESURFER";
            case PYPICKLE -> "PYPICKLE";
            case MIND_IDENT -> "MIND_IDENT";
            case B_VALUE -> "B_VALUE";
            case SPHERICAL_DIRECTION -> "SPHERICAL_DIRECTION";
            case DT_COMPONENT -> "DT_COMPONENT";
            case SHC_DEGREEORDER -> "SHC_DEGREEORDER";
            case VOXBO -> "VOXBO";
            case CARET -> "CARET";
            case CIFTI -> "CIFTI";
            case VARIABLE_FRAME_TIMING -> "VARIABLE_FRAME_TIMING";
            case EVAL -> "EVAL";
            case MATLAB -> "MATLAB";
            case QUANTIPHYSE -> "QUANTIPHYSE";
            case MRS -> "MRS";
            default -> "ecode " + code;
        };
    }

    /** Whether {@code code} is one this module has a name for. */
    public static boolean isRegistered(int code) {
        return code >= 0 && code <= MAX && !name(code).startsWith("ecode ");
    }
}
