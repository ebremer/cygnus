package com.ebremer.cygnus.jpegxl.features;

import com.ebremer.cygnus.jpegxl.entropy.EntropyDecoder;
import com.ebremer.cygnus.jpegxl.io.Bits;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Spline dictionary and rendering (18181-1 annex G.5). */
public final class Splines {

    public int numSplines;
    public int quantAdjust;
    public int[][] controlY;
    public int[][] controlX;
    public int[][] coeffX;
    public int[][] coeffY;
    public int[][] coeffB;
    public int[][] coeffSigma;

    public static Splines read(Bits in) throws IOException {
        Splines s = new Splines();
        EntropyDecoder stream = EntropyDecoder.read(in, 6, true);
        s.numSplines = 1 + stream.readSymbol(in, 2);
        int[] posX = new int[s.numSplines];
        int[] posY = new int[s.numSplines];
        for (int i = 0; i < s.numSplines; i++) {
            int x = stream.readSymbol(in, 1);
            int y = stream.readSymbol(in, 1);
            if (i != 0) {
                x = Bits.unpackSigned(x) + posX[i - 1];
                y = Bits.unpackSigned(y) + posY[i - 1];
            }
            posX[i] = x;
            posY[i] = y;
        }
        s.quantAdjust = Bits.unpackSigned(stream.readSymbol(in, 0));
        s.controlY = new int[s.numSplines][];
        s.controlX = new int[s.numSplines][];
        s.coeffX = new int[s.numSplines][32];
        s.coeffY = new int[s.numSplines][32];
        s.coeffB = new int[s.numSplines][32];
        s.coeffSigma = new int[s.numSplines][32];
        for (int i = 0; i < s.numSplines; i++) {
            int count = 1 + stream.readSymbol(in, 3);
            s.controlY[i] = new int[count];
            s.controlX[i] = new int[count];
            s.controlY[i][0] = posY[i];
            s.controlX[i][0] = posX[i];
            int[] deltaY = new int[count - 1];
            int[] deltaX = new int[count - 1];
            for (int j = 0; j < count - 1; j++) {
                deltaX[j] = Bits.unpackSigned(stream.readSymbol(in, 4));
                deltaY[j] = Bits.unpackSigned(stream.readSymbol(in, 4));
            }
            int cY = posY[i];
            int cX = posX[i];
            int dY = 0;
            int dX = 0;
            for (int j = 1; j < count; j++) {
                dY += deltaY[j - 1];
                dX += deltaX[j - 1];
                cY += dY;
                cX += dX;
                s.controlY[i][j] = cY;
                s.controlX[i][j] = cX;
            }
            for (int j = 0; j < 32; j++) {
                s.coeffX[i][j] = Bits.unpackSigned(stream.readSymbol(in, 5));
            }
            for (int j = 0; j < 32; j++) {
                s.coeffY[i][j] = Bits.unpackSigned(stream.readSymbol(in, 5));
            }
            for (int j = 0; j < 32; j++) {
                s.coeffB[i][j] = Bits.unpackSigned(stream.readSymbol(in, 5));
            }
            for (int j = 0; j < 32; j++) {
                s.coeffSigma[i][j] = Bits.unpackSigned(stream.readSymbol(in, 5));
            }
        }
        stream.finish(in);
        return s;
    }

    /** Renders every spline additively onto the XYB colour planes. */
    public void render(float[][] planes, int width, int height,
            float baseCorrelationX, float baseCorrelationB) {
        for (int i = 0; i < numSplines; i++) {
            renderOne(i, planes, width, height, baseCorrelationX, baseCorrelationB);
        }
    }

    private void renderOne(int id, float[][] planes, int width, int height,
            float baseCorrelationX, float baseCorrelationB) {
        float quantAdj = quantAdjust / 8f;
        float invQa = quantAdj >= 0 ? 1f / (1f + quantAdj) : 1f - quantAdj;
        float[] cX = new float[32];
        float[] cY = new float[32];
        float[] cB = new float[32];
        float[] cS = new float[32];
        for (int i = 0; i < 32; i++) {
            cY[i] = coeffY[id][i] * 0.106066017f * invQa;
            cX[i] = coeffX[id][i] * 0.005939697f * invQa + baseCorrelationX * cY[i];
            cB[i] = coeffB[id][i] * 0.098994949f * invQa + baseCorrelationB * cY[i];
            cS[i] = coeffSigma[id][i] * 0.47135738f * invQa;
        }

        float[][] upsampled = upsampleControlPoints(controlY[id], controlX[id]);
        float[] upY = upsampled[0];
        float[] upX = upsampled[1];

        // arc-length resampling at unit distance
        List<float[]> arcs = new ArrayList<>(); // {y, x, arcLength}
        float renderDistance = 1f;
        float currentY = upY[0];
        float currentX = upX[0];
        int nextId = 0;
        arcs.add(new float[] {currentY, currentX, renderDistance});
        while (nextId < upY.length) {
            float prevY = currentY;
            float prevX = currentX;
            float fromPrevious = 0f;
            while (true) {
                if (nextId >= upY.length) {
                    arcs.add(new float[] {prevY, prevX, fromPrevious});
                    break;
                }
                float dY = upY[nextId] - prevY;
                float dX = upX[nextId] - prevX;
                float toNext = (float) Math.sqrt(dY * dY + dX * dX);
                if (fromPrevious + toNext >= renderDistance) {
                    float f = (renderDistance - fromPrevious) / toNext;
                    currentY = dY * f + prevY;
                    currentX = dX * f + prevX;
                    arcs.add(new float[] {currentY, currentX, renderDistance});
                    break;
                }
                fromPrevious += toNext;
                prevY = upY[nextId];
                prevX = upX[nextId];
                nextId++;
            }
        }

        float arcLength = (arcs.size() - 2f) * renderDistance + arcs.get(arcs.size() - 1)[2];
        if (arcLength <= 0f) {
            return;
        }
        for (int i = 0; i < arcs.size(); i++) {
            float[] arc = arcs.get(i);
            float progress = Math.min(1f, i * renderDistance / arcLength);
            float t = 31f * progress;
            float vX = fourierICT(cX, t) * arc[2];
            float vY = fourierICT(cY, t) * arc[2];
            float vB = fourierICT(cB, t) * arc[2];
            float[] values = {vX, vY, vB};
            float sigma = fourierICT(cS, t);
            float inverseSigma = 1f / sigma;
            float maxColor = Math.max(Math.max(0.01f, vX), Math.max(vY, vB));
            float maxDist = (float) Math.sqrt(-2f * sigma * sigma * ((float) Math.log(0.1) * 3f - maxColor));
            int xBegin = Math.max(0, Math.round(arc[1] - maxDist));
            int xEnd = Math.min(width - 1, Math.round(arc[1] + maxDist));
            int yBegin = Math.max(0, Math.round(arc[0] - maxDist));
            int yEnd = Math.min(height - 1, Math.round(arc[0] + maxDist));
            for (int c = 0; c < 3; c++) {
                float[] plane = planes[c];
                for (int y = yBegin; y <= yEnd; y++) {
                    for (int x = xBegin; x <= xEnd; x++) {
                        float dY = y - arc[0];
                        float dX = x - arc[1];
                        float distance = (float) Math.sqrt(dY * dY + dX * dX);
                        float factor = erf((0.5f * distance + 0.35355339f) * inverseSigma)
                                - erf((0.5f * distance - 0.35355339f) * inverseSigma);
                        plane[y * width + x] += 0.25f * values[c] * sigma * factor * factor;
                    }
                }
            }
        }
    }

    private static float[][] upsampleControlPoints(int[] ctrlY, int[] ctrlX) {
        int n = ctrlY.length;
        if (n == 1) {
            return new float[][] {{ctrlY[0]}, {ctrlX[0]}};
        }
        float[] extY = new float[n + 2];
        float[] extX = new float[n + 2];
        extY[0] = ctrlY[0] * 2 - ctrlY[1];
        extX[0] = ctrlX[0] * 2 - ctrlX[1];
        for (int i = 0; i < n; i++) {
            extY[i + 1] = ctrlY[i];
            extX[i + 1] = ctrlX[i];
        }
        extY[n + 1] = ctrlY[n - 1] * 2 - ctrlY[n - 2];
        extX[n + 1] = ctrlX[n - 1] * 2 - ctrlX[n - 2];

        float[] upY = new float[16 * (n - 1) + 1];
        float[] upX = new float[upY.length];
        float[] t = new float[4];
        float[] pY = new float[4];
        float[] pX = new float[4];
        float[] dY = new float[3];
        float[] dX = new float[3];
        float[] aY = new float[3];
        float[] aX = new float[3];
        float[] bY = new float[2];
        float[] bX = new float[2];
        for (int i = 0; i < n - 1; i++) {
            for (int k = 0; k < 4; k++) {
                pY[k] = extY[i + k];
                pX[k] = extX[i + k];
            }
            upY[i << 4] = pY[1];
            upX[i << 4] = pX[1];
            t[0] = 0f;
            for (int k = 0; k < 3; k++) {
                dY[k] = pY[k + 1] - pY[k];
                dX[k] = pX[k + 1] - pX[k];
                t[k + 1] = t[k] + (float) Math.pow(dY[k] * dY[k] + dX[k] * dX[k], 0.25);
            }
            for (int step = 1; step < 16; step++) {
                float knot = t[1] + 0.0625f * step * (t[2] - t[1]);
                for (int k = 0; k < 3; k++) {
                    float f = (knot - t[k]) / (t[k + 1] - t[k]);
                    aY[k] = dY[k] * f + pY[k];
                    aX[k] = dX[k] * f + pX[k];
                }
                for (int k = 0; k < 2; k++) {
                    float f = (knot - t[k]) / (t[k + 2] - t[k]);
                    bY[k] = (aY[k + 1] - aY[k]) * f + aY[k];
                    bX[k] = (aX[k + 1] - aX[k]) * f + aX[k];
                }
                float f = (knot - t[1]) / (t[2] - t[1]);
                upY[i * 16 + step] = (bY[1] - bY[0]) * f + bY[0];
                upX[i * 16 + step] = (bX[1] - bX[0]) * f + bX[0];
            }
        }
        upY[upY.length - 1] = ctrlY[n - 1];
        upX[upX.length - 1] = ctrlX[n - 1];
        return new float[][] {upY, upX};
    }

    private static float fourierICT(float[] coeffs, float t) {
        float total = 0.70710678f * coeffs[0];
        for (int i = 1; i < 32; i++) {
            total += coeffs[i] * (float) Math.cos(i * (Math.PI / 32) * (t + 0.5));
        }
        return total;
    }

    static float erf(float z) {
        float az = Math.abs(z);
        float absErf;
        if (az > 1e-4f) {
            float t = 1f / (az * 0.5f + 1f);
            float u = t * (t * (t * (t * (t * (t * (t * (t * (t * 0.17087277f - 0.82215223f)
                    + 1.48851587f) - 1.13520398f) + 0.27886807f) - 0.18628806f) + 0.09678418f)
                    + 0.37409196f) + 1.00002368f) - 1.26551223f;
            absErf = 1f - t * (float) Math.exp(-z * z + u);
        } else {
            float t = 1f / (az * 0.47047f + 1f);
            float u = t * (t * (t * 0.7478556f - 0.0958798f) + 0.3480242f);
            absErf = 1f - u * (float) Math.exp(-z * z);
        }
        return z < 0 ? -absErf : absErf;
    }
}
