package com.ebremer.cygnus.nifti;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * One header extension: an {@code ecode} saying what the payload is, and the
 * payload. See {@link NiftiEcode} for the codes and
 * {@link NiftiExtensions} for the chain they live in.
 *
 * <p>The payload is carried, never interpreted. An extension this module has
 * no name for round-trips exactly as one it does.</p>
 *
 * <h2>Why the payload length is what it is</h2>
 *
 * <p>An extension on disk is {@code esize}, {@code ecode}, then
 * {@code esize - 8} bytes, and {@code esize} must be a positive multiple of
 * 16. The payload of an extension read from a file is therefore always
 * {@code esize - 8} bytes — 8, 24, 40, ... — including whatever padding the
 * writer added to reach the next multiple of 16, because nothing on disk says
 * where the writer's data stopped and its padding began. {@link #data} is
 * exactly those bytes.</p>
 *
 * <p>Constructing one from a payload of any other length is fine: {@link #esize()}
 * rounds up and {@link NiftiExtensions#encode} pads with zeros. A payload that
 * came from a file already has a length that rounds to its original
 * {@code esize}, so a read/write cycle adds nothing and changes nothing.</p>
 *
 * @param ecode what the payload is; see {@link NiftiEcode}
 * @param data  the payload, held by reference — do not modify it after construction
 */
public record NiftiExtension(int ecode, byte[] data) {

    /** The smallest an extension can be: the 8-byte prologue, padded to 16. */
    public static final int MIN_ESIZE = 16;

    /** {@code esize} is a multiple of this, so extensions stay aligned for memory-mapped reads. */
    public static final int ALIGNMENT = 16;

    /** Bytes of {@code esize} and {@code ecode} that precede the payload. */
    public static final int PROLOGUE = 8;

    public NiftiExtension {
        if (data == null) {
            throw new NullPointerException("extension data");
        }
    }

    /** An extension holding {@code text} as ISO-8859-1, for {@link NiftiEcode#COMMENT} and the XML codes. */
    public static NiftiExtension ofText(int ecode, String text) {
        return new NiftiExtension(ecode, text.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * The {@code esize} this extension writes: the payload plus the 8-byte
     * prologue, rounded up to the next multiple of 16, and never less than 16.
     */
    public int esize() {
        long size = (long) data.length + PROLOGUE;
        long rounded = Math.max(MIN_ESIZE, (size + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT);
        if (rounded > Integer.MAX_VALUE) {
            throw new IllegalStateException("extension payload of " + data.length
                    + " bytes cannot be expressed in a 32-bit esize");
        }
        return (int) rounded;
    }

    /** How many zero bytes {@link NiftiExtensions#encode} appends to reach {@link #esize()}. */
    public int padding() {
        return esize() - PROLOGUE - data.length;
    }

    /** The payload as ISO-8859-1 text with trailing NULs stripped, for the text-bearing codes. */
    public String text() {
        int end = data.length;
        while (end > 0 && data[end - 1] == 0) {
            end--;
        }
        return new String(data, 0, end, StandardCharsets.ISO_8859_1);
    }

    @Override
    public String toString() {
        return NiftiEcode.name(ecode) + " (" + data.length + " bytes, esize " + esize() + ")";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NiftiExtension e
                && ecode == e.ecode
                && Arrays.equals(data, e.data);
    }

    @Override
    public int hashCode() {
        return 31 * ecode + Arrays.hashCode(data);
    }
}
