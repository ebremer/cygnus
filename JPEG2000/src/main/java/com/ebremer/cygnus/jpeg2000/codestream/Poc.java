package com.ebremer.cygnus.jpeg2000.codestream;

/** One progression order change entry from a POC marker segment (T.800 A.6.6). */
public record Poc(
        int rspoc,   // resolution level start (inclusive)
        int cspoc,   // component start (inclusive)
        int lyepoc,  // layer end (exclusive)
        int repoc,   // resolution level end (exclusive)
        int cepoc,   // component end (exclusive)
        int progression) {
}
