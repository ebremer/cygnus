package com.ebremer.cygnus.jpeg2000.t2;

import com.ebremer.cygnus.jpeg2000.t1.Passes;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes one packet: its header (inclusion, zero bit-planes, pass counts,
 * codeword lengths - T.800 B.10) and the assignment of body bytes to
 * code-block codeword segments.
 */
public final class PacketDecoder {

    private PacketDecoder() {
    }

    private record Contribution(CodeBlock cb, int passes, int length, boolean terminated) {
    }

    /**
     * Decodes the packet for precinct {@code precIdx} of {@code res} at
     * {@code layer}, reading the header from {@code packedHeaders} when
     * non-null (PPM/PPT) and the body from {@code body} at {@code cursor}.
     *
     * @param storeData false to parse the packet (headers and cursor
     *        advance) without retaining codeword bytes - used when this
     *        resolution level is being discarded
     * @return the body cursor after the packet
     */
    public static int decode(Resolution res, int precIdx, int layer,
                             byte[] body, int cursor, PacketBitReader packedHeaders,
                             boolean useSop, boolean useEph, int cbStyle,
                             boolean storeData) {
        Precinct prec = res.precincts[precIdx];

        // optional SOP marker segment before the packet (in the body stream)
        if (useSop && cursor + 2 <= body.length
                && (body[cursor] & 0xFF) == 0xFF && (body[cursor + 1] & 0xFF) == 0x91) {
            cursor += 6; // marker + Lsop(=4) + Nsop
        }

        PacketBitReader br = packedHeaders != null
                ? packedHeaders
                : new PacketBitReader(body, cursor, body.length);

        List<Contribution> contribs = new ArrayList<>();
        if (br.readBit() != 0) {
            for (int b = 0; b < prec.blocks.length; b++) {
                int nw = prec.cbWide[b];
                int nh = prec.cbHigh[b];
                for (int j = 0; j < nh; j++) {
                    for (int i = 0; i < nw; i++) {
                        CodeBlock cb = prec.blocks[b][j * nw + i];
                        boolean included;
                        if (!cb.included) {
                            included = prec.inclusionTree[b].decode(br, i, j, layer + 1);
                        } else {
                            included = br.readBit() == 1;
                        }
                        if (!included) {
                            continue;
                        }
                        if (!cb.included) {
                            cb.zeroBitplanes = prec.msbTree[b].decodeValue(br, i, j);
                            cb.included = true;
                        }
                        int newPasses = decodePassCount(br);
                        while (br.readBit() == 1) {
                            cb.lblock++;
                        }
                        // split the new passes into codeword-segment chunks
                        int pabs = cb.totalPasses;
                        int remaining = newPasses;
                        while (remaining > 0) {
                            int chunk = 0;
                            boolean term = false;
                            while (chunk < remaining) {
                                chunk++;
                                if (Passes.terminatedAfter(pabs + chunk - 1, cbStyle)) {
                                    term = true;
                                    break;
                                }
                            }
                            int bits = Math.min(31,
                                    cb.lblock + (31 - Integer.numberOfLeadingZeros(chunk)));
                            int len = br.readBits(bits);
                            contribs.add(new Contribution(cb, chunk, len, term));
                            pabs += chunk;
                            remaining -= chunk;
                        }
                    }
                }
            }
        }

        // finish the header, then an optional EPH marker
        int headerEnd = br.align();
        if (useEph) {
            br.trySkipMarker(0xFF92);
            headerEnd = br.position();
        }
        if (packedHeaders == null) {
            cursor = headerEnd;
        }

        // packet body: bytes for each contribution, in header order
        // (clamped so a truncated codestream degrades instead of failing)
        for (Contribution ct : contribs) {
            int srcPos = Math.max(0, Math.min(cursor, body.length));
            int n = Math.max(0, Math.min(ct.length, body.length - srcPos));
            ct.cb.contribute(ct.passes, body, srcPos, n, ct.terminated, storeData);
            cursor += ct.length;
        }
        return cursor;
    }

    /** Number-of-coding-passes code, T.800 Table B.4. */
    static int decodePassCount(PacketBitReader br) {
        if (br.readBit() == 0) {
            return 1;
        }
        if (br.readBit() == 0) {
            return 2;
        }
        int v = br.readBits(2);
        if (v < 3) {
            return 3 + v;
        }
        v = br.readBits(5);
        if (v < 31) {
            return 6 + v;
        }
        return 37 + br.readBits(7);
    }
}
