package com.ebremer.cygnus.nifti;

/**
 * {@code qform_code} and {@code sform_code} values: what space a transform maps
 * voxel indices into. The code is not decoration — a zero means the transform
 * is absent and must not be used, and which of the two codes is nonzero decides
 * which transform a reader takes (see {@link NiftiAffine}).
 */
public final class NiftiXform {

    /** No transform. The only geometry is {@code pixdim}, and it says nothing about orientation. */
    public static final int UNKNOWN = 0;

    /** Scanner-anatomical coordinates, as the scanner reported them. */
    public static final int SCANNER_ANAT = 1;

    /** Aligned to some other image of the same subject. */
    public static final int ALIGNED_ANAT = 2;

    /** Talairach-Tournoux atlas space. */
    public static final int TALAIRACH = 3;

    /** MNI 152 template space. */
    public static final int MNI_152 = 4;

    /** Some other template space, named in {@code intent_name} if anywhere. */
    public static final int TEMPLATE_OTHER = 5;

    private NiftiXform() {
    }

    /** A readable name for {@code code}. */
    public static String name(int code) {
        return switch (code) {
            case UNKNOWN -> "UNKNOWN";
            case SCANNER_ANAT -> "SCANNER_ANAT";
            case ALIGNED_ANAT -> "ALIGNED_ANAT";
            case TALAIRACH -> "TALAIRACH";
            case MNI_152 -> "MNI_152";
            case TEMPLATE_OTHER -> "TEMPLATE_OTHER";
            default -> "xform " + code;
        };
    }
}
