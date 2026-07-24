package com.ebremer.cygnus.nifti;

/**
 * {@code intent_code} values: what the numbers in a NIfTI image mean.
 *
 * <p>Codes 2 to 24 say the image holds a statistic, and {@code intent_p1..p3}
 * carry that distribution's parameters — degrees of freedom and the like.
 * Codes from 1001 up say something else entirely: that the image is a set of
 * displacement vectors, a triangle mesh, a label map. Nothing here changes how
 * voxels are decoded; the code is carried and reported, and it is up to the
 * caller what to do about it.</p>
 */
public final class NiftiIntent {

    /** No intent stated. */
    public static final int NONE = 0;

    // ---- statistics: intent_p1..p3 are the distribution's parameters ----

    /** Correlation coefficient; p1 = degrees of freedom. */
    public static final int CORREL = 2;
    /** Student t; p1 = degrees of freedom. */
    public static final int TTEST = 3;
    /** Fisher F; p1, p2 = numerator and denominator degrees of freedom. */
    public static final int FTEST = 4;
    /** Standard normal. */
    public static final int ZSCORE = 5;
    /** Chi-squared; p1 = degrees of freedom. */
    public static final int CHISQ = 6;
    /** Beta; p1, p2 = a, b. */
    public static final int BETA = 7;
    /** Binomial; p1 = trials, p2 = probability. */
    public static final int BINOM = 8;
    /** Gamma; p1 = shape, p2 = scale. */
    public static final int GAMMA = 9;
    /** Poisson; p1 = mean. */
    public static final int POISSON = 10;
    /** Normal; p1 = mean, p2 = standard deviation. */
    public static final int NORMAL = 11;
    /** Noncentral F. */
    public static final int FTEST_NONC = 12;
    /** Noncentral chi-squared. */
    public static final int CHISQ_NONC = 13;
    /** Logistic. */
    public static final int LOGISTIC = 14;
    /** Laplace. */
    public static final int LAPLACE = 15;
    /** Uniform. */
    public static final int UNIFORM = 16;
    /** Noncentral t. */
    public static final int TTEST_NONC = 17;
    /** Weibull. */
    public static final int WEIBULL = 18;
    /** Chi. */
    public static final int CHI = 19;
    /** Inverse Gaussian. */
    public static final int INVGAUSS = 20;
    /** Extreme value type I. */
    public static final int EXTVAL = 21;
    /** A p-value. */
    public static final int PVAL = 22;
    /** ln(p). */
    public static final int LOGPVAL = 23;
    /** log10(p). */
    public static final int LOG10PVAL = 24;

    /** The first statistical code. */
    public static final int FIRST_STATCODE = 2;
    /** The last statistical code. */
    public static final int LAST_STATCODE = 24;

    // ---- not statistics ----

    /** An estimate of some parameter. */
    public static final int ESTIMATE = 1001;
    /** A label or index into a lookup table. */
    public static final int LABEL = 1002;
    /** A NeuroNames index. */
    public static final int NEURONAME = 1003;
    /** An MxN matrix per voxel; p1 = M, p2 = N. */
    public static final int GENMATRIX = 1004;
    /** A symmetric NxN matrix per voxel; p1 = N. */
    public static final int SYMMATRIX = 1005;
    /** A displacement vector per voxel. */
    public static final int DISPVECT = 1006;
    /** A vector per voxel. */
    public static final int VECTOR = 1007;
    /** A set of points; the image is a coordinate list. */
    public static final int POINTSET = 1008;
    /** A triangle mesh; the image indexes a point set. */
    public static final int TRIANGLE = 1009;
    /** A quaternion per voxel. */
    public static final int QUATERNION = 1010;
    /** A dimensionless value. */
    public static final int DIMLESS = 1011;

    // ---- GIFTI ----

    /** A time series per node. */
    public static final int TIME_SERIES = 2001;
    /** Node indices. */
    public static final int NODE_INDEX = 2002;
    /** An RGB triple per node. */
    public static final int RGB_VECTOR = 2003;
    /** An RGBA quadruple per node. */
    public static final int RGBA_VECTOR = 2004;
    /** A shape value per node. */
    public static final int SHAPE = 2005;

    private NiftiIntent() {
    }

    /** Whether {@code code} names a statistical distribution, whose parameters are in {@code intent_p1..p3}. */
    public static boolean isStatistic(int code) {
        return code >= FIRST_STATCODE && code <= LAST_STATCODE;
    }

    /** A readable name for {@code code}, or {@code "intent " + code} for one this module does not name. */
    public static String name(int code) {
        return switch (code) {
            case NONE -> "NONE";
            case CORREL -> "CORREL";
            case TTEST -> "TTEST";
            case FTEST -> "FTEST";
            case ZSCORE -> "ZSCORE";
            case CHISQ -> "CHISQ";
            case BETA -> "BETA";
            case BINOM -> "BINOM";
            case GAMMA -> "GAMMA";
            case POISSON -> "POISSON";
            case NORMAL -> "NORMAL";
            case FTEST_NONC -> "FTEST_NONC";
            case CHISQ_NONC -> "CHISQ_NONC";
            case LOGISTIC -> "LOGISTIC";
            case LAPLACE -> "LAPLACE";
            case UNIFORM -> "UNIFORM";
            case TTEST_NONC -> "TTEST_NONC";
            case WEIBULL -> "WEIBULL";
            case CHI -> "CHI";
            case INVGAUSS -> "INVGAUSS";
            case EXTVAL -> "EXTVAL";
            case PVAL -> "PVAL";
            case LOGPVAL -> "LOGPVAL";
            case LOG10PVAL -> "LOG10PVAL";
            case ESTIMATE -> "ESTIMATE";
            case LABEL -> "LABEL";
            case NEURONAME -> "NEURONAME";
            case GENMATRIX -> "GENMATRIX";
            case SYMMATRIX -> "SYMMATRIX";
            case DISPVECT -> "DISPVECT";
            case VECTOR -> "VECTOR";
            case POINTSET -> "POINTSET";
            case TRIANGLE -> "TRIANGLE";
            case QUATERNION -> "QUATERNION";
            case DIMLESS -> "DIMLESS";
            case TIME_SERIES -> "TIME_SERIES";
            case NODE_INDEX -> "NODE_INDEX";
            case RGB_VECTOR -> "RGB_VECTOR";
            case RGBA_VECTOR -> "RGBA_VECTOR";
            case SHAPE -> "SHAPE";
            default -> "intent " + code;
        };
    }
}
