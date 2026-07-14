package com.ebremer.cygnus.jpegxl.encoder;

import com.ebremer.cygnus.jpegxl.modular.InverseTransforms;
import com.ebremer.cygnus.jpegxl.modular.ModularChannel;
import com.ebremer.cygnus.jpegxl.modular.ModularStream;
import com.ebremer.cygnus.jpegxl.modular.Transform;
import java.util.ArrayList;
import java.util.List;

/**
 * Forward Squeeze (18181-1 H.6.4), the encoder's half of the transform the
 * decoder undoes in {@link InverseTransforms}.
 *
 * <p>One step halves a channel along one axis, replacing each pair of samples
 * with a rounded average and a residual. The averages become a half-size
 * channel; the residuals become a channel of their own, sitting after it. Run
 * the steps until nothing is bigger than eight and what is left is a small
 * image of the picture followed by the detail needed to double it, again and
 * again — which is what makes a prefix of the file a coarse but whole image
 * rather than a fraction of a sharp one.
 *
 * <p>The residual is not the plain difference but the difference less a
 * <em>tendency</em> predicted from the neighbouring averages, so that a smooth
 * gradient — where the difference across a pair is entirely predictable from
 * its surroundings — costs nothing. The encoder subtracts exactly what
 * {@link InverseTransforms#smoothTendency} adds back, and takes the plan from
 * {@link ModularStream#defaultSqueezeSteps}, so neither the arithmetic nor the
 * channel shapes can drift from what the decoder expects.
 */
final class Squeeze {

    private Squeeze() {
    }

    /**
     * A residual is a difference of samples less a tendency built from further
     * differences, and the format codes it in 32 bits. Bound it: the colour
     * transform leaves a sample below 2·2^bits, a difference below 4·2^bits, and
     * the tendency's clamps hold it to twice the differences around it, so a
     * residual stays under 12·2^bits. At 27 bits per sample that is 2^30.6 and
     * it cannot overflow; above that it can, and this is what says so rather
     * than wrapping silently into a wrong picture.
     */
    static final int SAFE_BITS = 27;

    private static int checked(long v) {
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("squeeze residual " + v
                    + " does not fit in the 32 bits the format codes it in; samples this deep"
                    + " cannot be coded progressively (at most " + SAFE_BITS
                    + " bits per sample is always safe)");
        }
        return (int) v;
    }

    /**
     * The plan the decoder will rebuild for itself from {@code num_sq == 0}.
     * Derived from the channel list as it stands after the colour transform,
     * which is where the decoder reads the squeeze from.
     */
    static List<Transform.Squeeze> plan(List<JxlEncoder.Chan> chans, int nbMeta) {
        List<ModularChannel> shapes = new ArrayList<>(chans.size());
        for (JxlEncoder.Chan c : chans) {
            shapes.add(new ModularChannel(c.w, c.h, c.hshift, c.vshift));
        }
        return ModularStream.defaultSqueezeSteps(shapes, nbMeta);
    }

    /**
     * Applies one step, rewriting {@code chans} the way the decoder's meta
     * transform rewrites its own channel list: each squeezed channel is
     * replaced in place by its averages, and the residuals are inserted as a
     * block — straight after the squeezed range when the step is in place,
     * at the very end when it is not.
     */
    static void apply(List<JxlEncoder.Chan> chans, Transform.Squeeze sq) {
        int beginC = sq.beginC();
        int endC = beginC + sq.numC();
        int offset = sq.inPlace() ? endC : chans.size();
        List<JxlEncoder.Chan> residuals = new ArrayList<>(sq.numC());
        for (int c = beginC; c < endC; c++) {
            JxlEncoder.Chan ch = chans.get(c);
            JxlEncoder.Chan[] split = sq.horizontal() ? horizontal(ch) : vertical(ch);
            chans.set(c, split[0]);
            residuals.add(split[1]);
        }
        for (int i = 0; i < residuals.size(); i++) {
            chans.add(offset + i, residuals.get(i));
        }
    }

    /**
     * Splits a channel into averages and residuals along x.
     *
     * <p>The average is chosen as the value that makes the decoder's
     * reconstruction land back on the left sample exactly: it rebuilds
     * {@code A = avg + diff/2} with the division truncating toward zero, so the
     * average that inverts it is {@code A - diff/2} with the same truncation.
     * (That is the same number as the format's {@code (A + B + (A > B)) >> 1};
     * written this way it is evident why it round-trips.)
     */
    private static JxlEncoder.Chan[] horizontal(JxlEncoder.Chan ch) {
        int w = ch.w;
        int h = ch.h;
        int aw = (w + 1) / 2;       // averages; keeps the odd sample out
        int rw = w - aw;            // residuals; one per pair
        JxlEncoder.Chan avg = new JxlEncoder.Chan(aw, h, ch.hshift + 1, ch.vshift,
                new int[Math.max(aw * h, 0)]);
        JxlEncoder.Chan res = new JxlEncoder.Chan(rw, h, ch.hshift + 1, ch.vshift,
                new int[Math.max(rw * h, 0)]);
        for (int y = 0; y < h; y++) {
            int rowIn = y * w;
            int rowA = y * aw;
            int rowR = y * rw;
            for (int x = 0; x < rw; x++) {
                long a = ch.px[rowIn + 2 * x];
                long b = ch.px[rowIn + 2 * x + 1];
                avg.px[rowA + x] = (int) (a - (a - b) / 2);
            }
            if (aw > rw) {
                avg.px[rowA + rw] = ch.px[rowIn + 2 * rw];   // odd width: no partner
            }
            // second sweep: the tendency needs the average to its right, which
            // only exists once the whole row of averages does
            for (int x = 0; x < rw; x++) {
                long left = x > 0 ? ch.px[rowIn + 2 * x - 1] : avg.px[rowA + x];
                long a = avg.px[rowA + x];
                long next = x + 1 < aw ? avg.px[rowA + x + 1] : a;
                long diff = (long) ch.px[rowIn + 2 * x] - ch.px[rowIn + 2 * x + 1];
                res.px[rowR + x] = checked(diff - InverseTransforms.smoothTendency(left, a, next));
            }
        }
        return new JxlEncoder.Chan[] {avg, res};
    }

    /** Splits a channel into averages and residuals along y. */
    private static JxlEncoder.Chan[] vertical(JxlEncoder.Chan ch) {
        int w = ch.w;
        int h = ch.h;
        int ah = (h + 1) / 2;
        int rh = h - ah;
        JxlEncoder.Chan avg = new JxlEncoder.Chan(w, ah, ch.hshift, ch.vshift + 1,
                new int[Math.max(w * ah, 0)]);
        JxlEncoder.Chan res = new JxlEncoder.Chan(w, rh, ch.hshift, ch.vshift + 1,
                new int[Math.max(w * rh, 0)]);
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < w; x++) {
                long a = ch.px[2 * y * w + x];
                long b = ch.px[(2 * y + 1) * w + x];
                avg.px[y * w + x] = (int) (a - (a - b) / 2);
            }
        }
        if (ah > rh) {
            System.arraycopy(ch.px, 2 * rh * w, avg.px, rh * w, w);   // odd height
        }
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < w; x++) {
                long top = y > 0 ? ch.px[(2 * y - 1) * w + x] : avg.px[y * w + x];
                long a = avg.px[y * w + x];
                long next = y + 1 < ah ? avg.px[(y + 1) * w + x] : a;
                long diff = (long) ch.px[2 * y * w + x] - ch.px[(2 * y + 1) * w + x];
                res.px[y * w + x] = checked(diff - InverseTransforms.smoothTendency(top, a, next));
            }
        }
        return new JxlEncoder.Chan[] {avg, res};
    }
}
