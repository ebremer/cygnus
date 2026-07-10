package com.ebremer.cygnus.t2;

/**
 * Thrown when a codestream declares structures beyond the decoder's safety
 * limits (packet counts, precinct grids, ...). Unchecked because it must
 * cross lambda boundaries; the decoder converts it to an IIOException.
 */
public class DecodeLimitException extends RuntimeException {

    public DecodeLimitException(String message) {
        super(message);
    }
}
