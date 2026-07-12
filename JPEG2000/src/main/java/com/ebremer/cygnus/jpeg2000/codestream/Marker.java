package com.ebremer.cygnus.jpeg2000.codestream;

/** JPEG 2000 codestream marker codes (ITU-T T.800 Table A.2). */
public final class Marker {

    public static final int SOC = 0xFF4F; // start of codestream
    public static final int SOT = 0xFF90; // start of tile-part
    public static final int SOD = 0xFF93; // start of data
    public static final int EOC = 0xFFD9; // end of codestream
    public static final int SIZ = 0xFF51; // image and tile size
    public static final int CAP = 0xFF50; // extended capabilities (T.814)
    public static final int CPF = 0xFF59; // corresponding profile (T.814)
    public static final int COD = 0xFF52; // coding style default
    public static final int COC = 0xFF53; // coding style component
    public static final int RGN = 0xFF5E; // region of interest
    public static final int QCD = 0xFF5C; // quantization default
    public static final int QCC = 0xFF5D; // quantization component
    public static final int POC = 0xFF5F; // progression order change
    public static final int TLM = 0xFF55; // tile-part lengths
    public static final int PLM = 0xFF57; // packet lengths, main header
    public static final int PLT = 0xFF58; // packet lengths, tile-part
    public static final int PPM = 0xFF60; // packed packet headers, main
    public static final int PPT = 0xFF61; // packed packet headers, tile-part
    public static final int SOP = 0xFF91; // start of packet
    public static final int EPH = 0xFF92; // end of packet header
    public static final int CRG = 0xFF63; // component registration
    public static final int COM = 0xFF64; // comment

    private Marker() {
    }

    public static String name(int marker) {
        return switch (marker) {
            case SOC -> "SOC"; case SOT -> "SOT"; case SOD -> "SOD";
            case EOC -> "EOC"; case SIZ -> "SIZ"; case COD -> "COD";
            case COC -> "COC"; case RGN -> "RGN"; case QCD -> "QCD";
            case QCC -> "QCC"; case POC -> "POC"; case TLM -> "TLM";
            case PLM -> "PLM"; case PLT -> "PLT"; case PPM -> "PPM";
            case PPT -> "PPT"; case SOP -> "SOP"; case EPH -> "EPH";
            case CRG -> "CRG"; case COM -> "COM";
            case CAP -> "CAP"; case CPF -> "CPF";
            default -> String.format("0x%04X", marker);
        };
    }

    /** True for delimiting markers that have no length field. */
    public static boolean isDelimiter(int marker) {
        return marker == SOC || marker == SOD || marker == EOC || marker == EPH;
    }
}
