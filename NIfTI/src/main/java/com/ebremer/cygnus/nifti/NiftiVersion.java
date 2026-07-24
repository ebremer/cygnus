package com.ebremer.cygnus.nifti;

/**
 * Which of the three header layouts a file carries. All three begin with a
 * 32-bit {@code sizeof_hdr} giving the header's own length, which is how a
 * reader tells them apart — and, because the value is known, how it tells the
 * byte order too.
 *
 * <p>NIfTI-2 holds the same information as NIfTI-1 and adds no fields; what it
 * changes is width. Every dimension became {@code int64}, every real became
 * {@code double}, every code became {@code int32}, and the fields were
 * reordered so the magic sits at offset 4 rather than 344. ANALYZE 7.5 is the
 * format NIfTI-1 was laid over: same 348 bytes, same {@code dim}/{@code pixdim}
 * /{@code datatype}/{@code vox_offset}, but no magic and no geometry — the
 * bytes NIfTI-1 spends on {@code qform}/{@code sform} are other things there,
 * which is why reading one as the other yields plausible nonsense.</p>
 */
public enum NiftiVersion {

    /** ANALYZE 7.5: 348 bytes, no magic, no qform/sform. Read only. */
    ANALYZE75(348, -1, 0, 0),

    /** NIfTI-1: 348 bytes, 4-byte magic at offset 344. */
    NIFTI1(348, 344, 4, 352),

    /** NIfTI-2: 540 bytes, 8-byte magic at offset 4. */
    NIFTI2(540, 4, 8, 544);

    /** {@code "n+1\0"} — NIfTI-1, header and voxels in one file. */
    public static final byte[] MAGIC_NIFTI1_SINGLE = {'n', '+', '1', 0};

    /** {@code "ni1\0"} — NIfTI-1, voxels in a separate {@code .img}. */
    public static final byte[] MAGIC_NIFTI1_PAIR = {'n', 'i', '1', 0};

    /**
     * {@code "n+2\0\r\n\032\n"} — NIfTI-2, single file. The four bytes after
     * the NUL are the same corruption sentinel PNG uses: a file mangled by
     * CR/LF translation or an ASCII-mode transfer no longer matches.
     */
    public static final byte[] MAGIC_NIFTI2_SINGLE =
            {'n', '+', '2', 0, '\r', '\n', 0x1A, '\n'};

    /** {@code "ni2\0\r\n\032\n"} — NIfTI-2, voxels in a separate {@code .img}. */
    public static final byte[] MAGIC_NIFTI2_PAIR =
            {'n', 'i', '2', 0, '\r', '\n', 0x1A, '\n'};

    /** {@code sizeof_hdr} of 348, byte-swapped: what a big-endian v1 header looks like read little-endian. */
    public static final int SWAPPED_348 = 1543569408;

    /** {@code sizeof_hdr} of 540, byte-swapped. */
    public static final int SWAPPED_540 = 469893120;

    /** The header's own length in bytes, and the value of its {@code sizeof_hdr}. */
    public final int headerSize;

    /** Where the magic begins, or -1 for a layout that has none. */
    public final int magicOffset;

    /** How many bytes of magic, or 0 for a layout that has none. */
    public final int magicLength;

    /**
     * The smallest legal {@code vox_offset} in a single-file image: the header
     * plus the 4-byte extension marker that always follows it. Zero for a
     * layout that is always a pair.
     */
    public final int minVoxOffset;

    NiftiVersion(int headerSize, int magicOffset, int magicLength, int minVoxOffset) {
        this.headerSize = headerSize;
        this.magicOffset = magicOffset;
        this.magicLength = magicLength;
        this.minVoxOffset = minVoxOffset;
    }

    /** The magic this version writes for a single-file image, or null if it has none. */
    public byte[] singleFileMagic() {
        return switch (this) {
            case NIFTI1 -> MAGIC_NIFTI1_SINGLE.clone();
            case NIFTI2 -> MAGIC_NIFTI2_SINGLE.clone();
            case ANALYZE75 -> null;
        };
    }

    /** The magic this version writes for a header/image pair, or null if it has none. */
    public byte[] pairMagic() {
        return switch (this) {
            case NIFTI1 -> MAGIC_NIFTI1_PAIR.clone();
            case NIFTI2 -> MAGIC_NIFTI2_PAIR.clone();
            case ANALYZE75 -> null;
        };
    }

    /** Whether this layout carries {@code qform}/{@code sform} geometry at all. */
    public boolean hasGeometry() {
        return this != ANALYZE75;
    }

    /** Whether this module can write this layout — ANALYZE 7.5 is read-only. */
    public boolean writable() {
        return this != ANALYZE75;
    }
}
