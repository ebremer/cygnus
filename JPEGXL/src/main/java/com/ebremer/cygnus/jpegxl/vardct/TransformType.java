package com.ebremer.cygnus.jpegxl.vardct;

/** The 27 varblock transform types (18181-1 annex E). */
public enum TransformType {
    DCT8(0, 0, 0, Method.DCT, 8, 8),
    HORNUSS(1, 1, 1, Method.HORNUSS, 8, 8),
    DCT2(2, 2, 1, Method.DCT2, 8, 8),
    DCT4(3, 3, 1, Method.DCT4, 8, 8),
    DCT16(4, 4, 2, Method.DCT, 16, 16),
    DCT32(5, 5, 3, Method.DCT, 32, 32),
    DCT16_8(6, 6, 4, Method.DCT, 16, 8),
    DCT8_16(7, 6, 4, Method.DCT, 8, 16),
    DCT32_8(8, 7, 5, Method.DCT, 32, 8),
    DCT8_32(9, 7, 5, Method.DCT, 8, 32),
    DCT32_16(10, 8, 6, Method.DCT, 32, 16),
    DCT16_32(11, 8, 6, Method.DCT, 16, 32),
    DCT4_8(12, 9, 1, Method.DCT4_8, 8, 8),
    DCT8_4(13, 9, 1, Method.DCT8_4, 8, 8),
    AFV0(14, 10, 1, Method.AFV, 8, 8),
    AFV1(15, 10, 1, Method.AFV, 8, 8),
    AFV2(16, 10, 1, Method.AFV, 8, 8),
    AFV3(17, 10, 1, Method.AFV, 8, 8),
    DCT64(18, 11, 7, Method.DCT, 64, 64),
    DCT64_32(19, 12, 8, Method.DCT, 64, 32),
    DCT32_64(20, 12, 8, Method.DCT, 32, 64),
    DCT128(21, 13, 9, Method.DCT, 128, 128),
    DCT128_64(22, 14, 10, Method.DCT, 128, 64),
    DCT64_128(23, 14, 10, Method.DCT, 64, 128),
    DCT256(24, 15, 11, Method.DCT, 256, 256),
    DCT256_128(25, 16, 12, Method.DCT, 256, 128),
    DCT128_256(26, 16, 12, Method.DCT, 128, 256);

    public enum Method {
        DCT, DCT2, DCT4, HORNUSS, DCT8_4, DCT4_8, AFV
    }

    /** Scale.F[x << (5 - ceilLog2(dim))] resampling scales for LLF (normative table). */
    private static final class Scale {
        static final float[] F = {
            1.0000000000000000000f, 1.0003954307206444720f, 1.0015830492063566798f,
            1.0035668445359847378f, 1.0063534990068075448f, 1.0099524393750471170f,
            1.0143759095929498827f, 1.0196390660646908181f, 1.0257600967811994622f,
            1.0327603660498609462f, 1.0406645869479269795f, 1.0495010240726261235f,
            1.0593017296818027804f, 1.0701028169146909598f, 1.0819447744633102634f,
            1.0948728278735071820f, 1.1089373535928257701f, 1.1241943530045446156f,
            1.1407059950032801390f, 1.1585412372562662921f, 1.1777765381971696030f,
            1.1984966740821024139f, 1.2207956782314713353f, 1.2447779229495839992f,
            1.2705593687655135089f, 1.2982690107340108228f, 1.3280505578212198723f,
            1.3600643892400108061f, 1.3944898413648201160f, 1.4315278911623840964f,
            1.4714043176060183528f, 1.5143734423313919909f,
        };
    }

    public final int type;
    public final int parameterIndex;
    public final int orderId;
    public final Method method;
    public final int pixelHeight;
    public final int pixelWidth;
    public final int blockHeight;   // in 8x8 blocks
    public final int blockWidth;
    public final int matrixHeight;  // min dimension
    public final int matrixWidth;   // max dimension
    public final float[] llfScale;  // blockHeight x blockWidth

    TransformType(int type, int parameterIndex, int orderId, Method method,
            int pixelHeight, int pixelWidth) {
        this.type = type;
        this.parameterIndex = parameterIndex;
        this.orderId = orderId;
        this.method = method;
        this.pixelHeight = pixelHeight;
        this.pixelWidth = pixelWidth;
        this.blockHeight = pixelHeight >> 3;
        this.blockWidth = pixelWidth >> 3;
        this.matrixHeight = Math.min(pixelHeight, pixelWidth);
        this.matrixWidth = Math.max(pixelHeight, pixelWidth);
        this.llfScale = new float[blockHeight * blockWidth];
        int yll = ceilLog2(blockHeight);
        int xll = ceilLog2(blockWidth);
        for (int y = 0; y < blockHeight; y++) {
            for (int x = 0; x < blockWidth; x++) {
                llfScale[y * blockWidth + x] =
                        Scale.F[y << (5 - yll)] * Scale.F[x << (5 - xll)];
            }
        }
    }

    public boolean isVertical() {
        return pixelHeight > pixelWidth;
    }

    /** Whether coefficients are stored with swapped axes. */
    public boolean flip() {
        return pixelHeight > pixelWidth || (method == Method.DCT && pixelHeight == pixelWidth);
    }

    private static final TransformType[] BY_TYPE = new TransformType[27];
    private static final TransformType[] BY_PARAM = new TransformType[17];
    private static final TransformType[] BY_ORDER = new TransformType[13];

    static {
        for (TransformType t : values()) {
            BY_TYPE[t.type] = t;
            if (!t.isVertical()) {
                if (BY_PARAM[t.parameterIndex] == null) {
                    BY_PARAM[t.parameterIndex] = t;
                }
                if (BY_ORDER[t.orderId] == null) {
                    BY_ORDER[t.orderId] = t;
                }
            }
        }
    }

    public static TransformType byType(int type) {
        return BY_TYPE[type];
    }

    public static TransformType byParameterIndex(int index) {
        return BY_PARAM[index];
    }

    public static TransformType byOrderId(int orderId) {
        return BY_ORDER[orderId];
    }

    private static int ceilLog2(int x) {
        return x > 1 ? 32 - Integer.numberOfLeadingZeros(x - 1) : 0;
    }
}
