package com.ebremer.cygnus.jpegxl.decoder;

import com.ebremer.cygnus.jpegxl.codestream.FrameHeader;
import com.ebremer.cygnus.jpegxl.codestream.IccStream;
import com.ebremer.cygnus.jpegxl.codestream.ImageMetadata;
import com.ebremer.cygnus.jpegxl.codestream.SizeHeader;
import com.ebremer.cygnus.jpegxl.codestream.Toc;
import com.ebremer.cygnus.jpegxl.color.Transfer;
import com.ebremer.cygnus.jpegxl.color.XybConverter;
import com.ebremer.cygnus.jpegxl.container.Container;
import com.ebremer.cygnus.jpegxl.entropy.EntropyDecoder;
import com.ebremer.cygnus.jpegxl.features.Noise;
import com.ebremer.cygnus.jpegxl.features.PatchesDictionary;
import com.ebremer.cygnus.jpegxl.features.Splines;
import com.ebremer.cygnus.jpegxl.features.Upsampler;
import com.ebremer.cygnus.jpegxl.io.Bits;
import com.ebremer.cygnus.jpegxl.io.CodestreamSource;
import com.ebremer.cygnus.jpegxl.io.RegionUnsupportedException;
import com.ebremer.cygnus.jpegxl.modular.MaTree;
import com.ebremer.cygnus.jpegxl.modular.ModularChannel;
import com.ebremer.cygnus.jpegxl.modular.ModularStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Top-level JPEG XL decoder: modular mode (lossless and XYB), VarDCT (lossy),
 * progressive passes, patches, splines, noise, and upsampling. Remaining
 * unsupported features are rejected with a descriptive exception.
 */
public final class JxlDecoder {

    /** Number of quantisation table stream slots, part of the stream index formula. */
    private static final int NUM_DCT_PARAMS = 17;

    private JxlDecoder() {
    }

    /** Basic stream properties, readable without decoding pixel data. */
    public record Info(ImageMetadata metadata, int width, int height,
                       int orientedWidth, int orientedHeight) {
    }

    public static Info readInfo(byte[] file) throws IOException {
        return readInfo(new CodestreamSource.ArraySource(Container.extractCodestream(file)));
    }

    public static Info readInfo(CodestreamSource src) throws IOException {
        return parseWithWindow(src, 0, in -> {
            if (in.u(16) != 0x0aff) {
                throw new IOException("not a JPEG XL codestream");
            }
            SizeHeader size = SizeHeader.read(in);
            ImageMetadata meta = ImageMetadata.read(in);
            boolean transposed = meta.orientation > 4;
            return new Info(meta, size.width, size.height,
                    transposed ? size.height : size.width,
                    transposed ? size.width : size.height);
        });
    }

    /** One decoded frame before colour transformation and integer conversion. */
    private static final class FrameResult {
        FrameHeader fh;
        int width;          // current buffer size (grows with upsampling)
        int height;
        float[][] planes;   // colour planes first (XYB or display-space), then ECs in [0,1]
        int outColour;
        float baseCorrelationX;
        float baseCorrelationB;
        float[] noiseLut;
        PatchesDictionary patchesDict;
        Splines splines;
        /**
         * Raw integer samples for deep (>24-bit) integer images, kept so the
         * output is exact where float32 would round; nulled by any operation
         * that modifies the float planes.
         */
        int[][] exactInt;
    }

    public static JxlImage decode(byte[] file) throws IOException {
        JxlImage image = decode(new CodestreamSource.ArraySource(
                Container.extractCodestream(file), Container.declaredLevel(file)));
        attachContainerMetadata(image, file);
        return image;
    }

    /**
     * Decodes as much of a codestream as has arrived, instead of failing on one
     * that stops early. Sections whose bytes are not all present are left out,
     * and what they would have carried stays zero.
     *
     * <p>That is what makes a progressive file
     * ({@link com.ebremer.cygnus.jpegxl.encoder.JxlEncoder#encodeProgressive})
     * worth writing: its channels are a small image of the picture followed by
     * the detail that doubles it, so the missing detail simply does not sharpen
     * anything, and a prefix of the bytes decodes to the whole image at low
     * resolution. On an ordinary file the missing sections are whole groups, and
     * what comes back has holes in it instead — still decoded, but not a picture
     * anyone wants.
     */
    public static JxlImage decodePartial(byte[] file) throws IOException {
        return decodeImpl(new CodestreamSource.ArraySource(
                Container.extractCodestream(file), Container.declaredLevel(file)),
                null, false, false, true);
    }

    private static void attachContainerMetadata(JxlImage image, byte[] file) throws IOException {
        if (Container.isContainer(file)) {
            image.exif = Container.exifPayload(file);
            image.xmp = Container.findBox(file, "xml ");
            byte[] jhgm = Container.findBox(file, "jhgm");
            if (jhgm != null) {
                image.gainMap = com.ebremer.cygnus.jpegxl.container.GainMap.parse(jhgm);
            }
        }
    }

    /** Decodes from an {@link javax.imageio.stream.ImageInputStream} without buffering the file. */
    public static JxlImage decode(javax.imageio.stream.ImageInputStream stream) throws IOException {
        return decode(new CodestreamSource.StreamSource(
                stream, Container.scanSegments(stream), Container.declaredLevel(stream)));
    }

    /** Decodes only {@code region}; see {@link #decode(CodestreamSource, java.awt.Rectangle)}. */
    public static JxlImage decode(byte[] file, java.awt.Rectangle region) throws IOException {
        return decode(new CodestreamSource.ArraySource(
                Container.extractCodestream(file), Container.declaredLevel(file)), region);
    }

    /** Decodes only {@code region}; see {@link #decode(CodestreamSource, java.awt.Rectangle)}. */
    public static JxlImage decode(javax.imageio.stream.ImageInputStream stream,
            java.awt.Rectangle region) throws IOException {
        return decode(new CodestreamSource.StreamSource(
                stream, Container.scanSegments(stream), Container.declaredLevel(stream)),
                region);
    }

    /**
     * Decodes only the given rectangle, in oriented image coordinates. The
     * returned image keeps the full image dimensions but its frames cover just
     * the rectangle (clamped to the image; its clamped origin is reported by
     * {@link JxlImage#regionX}/{@link JxlImage#regionY}).
     *
     * <p>Only the groups whose sections intersect the region (plus a safety
     * margin for the restoration filters and upsampling) are entropy-decoded,
     * so the cost of a windowed read of a large image is proportional to the
     * covered groups, not the image. Streams whose reconstruction is non-local
     * (frame-global squeeze, delta palettes, patches from region-limited
     * snapshots) transparently fall back to decoding every group; the result
     * is identical either way. A null region decodes everything.
     */
    public static JxlImage decode(CodestreamSource src, java.awt.Rectangle region)
            throws IOException {
        if (region == null) {
            return decodeImpl(src, null, false, false, false);
        }
        try {
            return decodeImpl(src, region, true, false, false);
        } catch (RegionUnsupportedException e) {
            if (ModularStream.DEBUG) {
                System.err.println("[jxl] region fallback: " + e.getMessage());
            }
            return decodeImpl(src, region, false, false, false);
        }
    }

    /**
     * Decodes with every channel delivered as floats in
     * {@link JxlFrame#floatChannels} (nominal range [0, 1] for integer
     * images), skipping the final integer quantisation. Meant for
     * conformance testing and QA against float references; integer images
     * deeper than 24 bits lose precision here — use {@link #decode} for
     * exact deep integers.
     */
    public static JxlImage decodeToFloats(byte[] file) throws IOException {
        JxlImage image = decodeToFloats(new CodestreamSource.ArraySource(
                Container.extractCodestream(file), Container.declaredLevel(file)));
        attachContainerMetadata(image, file);
        return image;
    }

    /** See {@link #decodeToFloats(byte[])}. */
    public static JxlImage decodeToFloats(CodestreamSource src) throws IOException {
        return decodeImpl(src, null, false, true, false);
    }

    /** A header parse that runs over a byte window of the codestream. */
    private interface HeaderParse<T> {
        T parse(Bits in) throws IOException;
    }

    /**
     * Runs a header parser over a window starting at {@code offset}, growing
     * the window and retrying when the parse runs off its end.
     */
    private static <T> T parseWithWindow(CodestreamSource src, long offset,
            HeaderParse<T> parser) throws IOException {
        long remaining = src.size() - offset;
        int window = (int) Math.min(1 << 16, remaining);
        while (true) {
            Bits in = new Bits(src.readRange(offset, window), 0, window, offset);
            try {
                return parser.parse(in);
            } catch (IOException e) {
                boolean truncated = e.getMessage() != null
                        && e.getMessage().contains("end of JPEG XL bitstream");
                if (!truncated || window >= remaining) {
                    throw e;
                }
                window = (int) Math.min((long) window * 4, remaining);
            }
        }
    }

    private record FrameEntry(FrameHeader fh, Toc toc) {
    }

    private record Prelude(SizeHeader size, ImageMetadata meta, FrameEntry preview,
                           FrameEntry firstFrame) {
    }

    private static Prelude parsePrelude(CodestreamSource src) throws IOException {
        return parseWithWindow(src, 0, in -> {
            if (in.u(16) != 0x0aff) {
                throw new IOException("not a JPEG XL codestream");
            }
            SizeHeader size = SizeHeader.read(in);
            ImageMetadata meta = ImageMetadata.read(in);
            if (meta.colourEncoding.wantIcc) {
                byte[] encoded = IccStream.readEncoded(in);
                try {
                    meta.iccProfile = IccStream.reconstruct(encoded);
                } catch (IOException e) {
                    // colours fall back to the enumerated encoding
                }
            }
            FrameEntry preview = null;
            FrameEntry first = null;
            if (meta.previewWidth > 0) {
                FrameHeader pfh = FrameHeader.read(in, meta, meta.previewWidth, meta.previewHeight);
                preview = new FrameEntry(pfh, Toc.read(in, pfh));
            } else {
                FrameHeader fh = FrameHeader.read(in, meta, size.width, size.height);
                first = new FrameEntry(fh, Toc.read(in, fh));
            }
            return new Prelude(size, meta, preview, first);
        });
    }

    private static FrameEntry parseFrame(CodestreamSource src, long offset, ImageMetadata meta,
            SizeHeader size) throws IOException {
        return parseWithWindow(src, offset, in -> {
            FrameHeader fh = FrameHeader.read(in, meta, size.width, size.height);
            return new FrameEntry(fh, Toc.read(in, fh));
        });
    }

    public static JxlImage decode(CodestreamSource src) throws IOException {
        return decodeImpl(src, null, false, false, false);
    }

    /**
     * @param requested region in oriented image coordinates, or null for all
     * @param selective when true, only the groups covering the region are
     *        decoded (may throw {@link RegionUnsupportedException}); when
     *        false with a non-null region, everything is decoded and the
     *        output is merely cropped
     * @param floatOut deliver every output channel as floats
     * @param partial decode whatever sections have arrived and leave the rest
     *        at zero, instead of failing on a codestream that stops early
     */
    private static JxlImage decodeImpl(CodestreamSource src, java.awt.Rectangle requested,
            boolean selective, boolean floatOut, boolean partial) throws IOException {
        Prelude prelude = parsePrelude(src);
        SizeHeader size = prelude.size();
        ImageMetadata meta = prelude.meta();
        if (!meta.bitDepth.floatingPoint && meta.bitDepth.bitsPerSample > 31) {
            throw new IOException("more than 31 bits per integer sample is not supported");
        }

        boolean transposed = meta.orientation > 4;
        int orientedWidth = transposed ? size.height : size.width;
        int orientedHeight = transposed ? size.width : size.height;
        java.awt.Rectangle oriented = null;
        java.awt.Rectangle csRegion = null; // in codestream (pre-orientation) coordinates
        if (requested != null) {
            oriented = requested.intersection(
                    new java.awt.Rectangle(orientedWidth, orientedHeight));
            if (oriented.isEmpty()) {
                throw new IllegalArgumentException("region " + requested
                        + " lies outside the " + orientedWidth + "x" + orientedHeight + " image");
            }
            csRegion = inverseOrientRect(meta.orientation, size.width, size.height, oriented);
        }

        JxlFrame preview = null;
        FrameEntry entry;
        if (prelude.preview() != null) {
            FrameEntry pe = prelude.preview();
            try {
                FrameResult r = decodeFrame(src, pe.fh(), pe.toc(), meta,
                        new FrameResult[5], null, partial);
                upsampleFrame(r, meta);
                applyPatches(r, new FrameResult[4], meta, null);
                if (r.splines != null) {
                    r.splines.render(r.planes, r.width, r.height,
                            r.baseCorrelationX, r.baseCorrelationB);
                }
                colourTransform(r, meta);
                preview = canvasToFrame(r.planes, r.exactInt, r.outColour,
                        r.width, r.height, meta, 0, null, false);
            } catch (IOException e) {
                preview = null; // previews are decorative; keep decoding the image
            }
            entry = parseFrame(src, pe.toc().endOffset, meta, size);
        } else {
            entry = prelude.firstFrame();
        }

        List<JxlFrame> frames = new ArrayList<>();
        FrameResult[] reference = new FrameResult[4];
        FrameResult[] lfBuffer = new FrameResult[5];
        int canvasColour = meta.colourEncoding.isGrey() ? 1 : 3;
        int canvasChannels = canvasColour + meta.numExtraChannels();
        float[][] canvas = new float[canvasChannels][size.width * size.height];
        int[][] canvasExact = null; // exact deep-int samples mirroring the canvas
        long visibleFrames = 0;
        long invisibleFrames = 0;
        // region bookkeeping: the canvas is only maintained within the region
        // once a frame was partially decoded, and snapshots taken after that
        // can no longer serve as patch sources
        boolean canvasTainted = false;
        boolean[] refRegionLimited = new boolean[4];
        while (true) {
            FrameHeader fh = entry.fh();
            Toc toc = entry.toc();
            if (ModularStream.DEBUG) {
                System.err.println("[jxl] frame: type=" + fh.type + " modular=" + fh.isModular
                        + " flags=" + fh.flags + " size=" + fh.width + "x" + fh.height
                        + " display=" + fh.displayWidth + "x" + fh.displayHeight
                        + " at (" + fh.x0 + "," + fh.y0 + ")"
                        + " up=" + fh.upsampling + " passes=" + fh.passes.numPasses
                        + " isLast=" + fh.isLast + " saveAsRef=" + fh.saveAsReference
                        + " saveBeforeCT=" + fh.saveBeforeColourTransform
                        + " blend=" + fh.blendMode + " lfLevel=" + fh.lfLevel);
            }

            boolean visible = (fh.type == FrameHeader.TYPE_REGULAR
                    || fh.type == FrameHeader.TYPE_SKIP_PROGRESSIVE)
                    && (fh.duration != 0 || fh.isLast);
            boolean save = (fh.saveAsReference != 0 || fh.duration == 0)
                    && !fh.isLast && fh.type != FrameHeader.TYPE_LF;
            // saved and LF frames stay complete: they may be read at arbitrary
            // coordinates later (patches, blending sources, LF image)
            GroupSelection sel = selectGroups(fh, csRegion,
                    selective && !save && fh.type != FrameHeader.TYPE_LF);

            if (sel != null && sel.empty()) {
                // the frame does not touch the region: skip its pixel data;
                // its canvas contribution would land entirely outside
                canvasTainted = true;
                if (visible) {
                    visibleFrames++;
                    invisibleFrames = 0;
                } else {
                    invisibleFrames++;
                }
                canvasExact = null; // an intersection-free frame never covers the canvas
                if (visible) {
                    frames.add(emitFrame(canvas, canvasExact, canvasColour, size, meta,
                            fh.duration, csRegion, floatOut));
                }
                if (fh.isLast) {
                    break;
                }
                entry = parseFrame(src, toc.endOffset, meta, size);
                continue;
            }

            FrameResult r = decodeFrame(src, fh, toc, meta, lfBuffer, sel, partial);

            if (fh.type == FrameHeader.TYPE_LF) {
                // an LF frame provides the LF image for a lower level
                lfBuffer[fh.lfLevel - 1] = r;
                entry = parseFrame(src, toc.endOffset, meta, size);
                continue;
            }

            if (visible) {
                visibleFrames++;
                invisibleFrames = 0;
            } else {
                invisibleFrames++;
            }

            upsampleFrame(r, meta);
            float[][] noiseField = null;
            if (r.noiseLut != null) {
                long seed0 = (visibleFrames << 32) | invisibleFrames;
                int groupDim = fh.groupDim;
                int cols = (r.width + groupDim - 1) / groupDim;
                int rows = (r.height + groupDim - 1) / groupDim;
                noiseField = Noise.initialize(seed0, r.width, r.height, groupDim, cols, cols * rows);
            }

            if (save && fh.saveBeforeColourTransform) {
                reference[fh.saveAsReference] = copyResult(r);
                refRegionLimited[fh.saveAsReference] = false;
            }
            applyPatches(r, reference, meta, selective ? refRegionLimited : null);
            if (r.splines != null) {
                r.exactInt = null;
                r.splines.render(r.planes, r.width, r.height,
                        r.baseCorrelationX, r.baseCorrelationB);
            }
            if (noiseField != null) {
                r.exactInt = null;
                Noise.synthesize(r.planes, noiseField, r.width, r.height,
                        r.noiseLut, r.baseCorrelationX, r.baseCorrelationB);
            }
            colourTransform(r, meta);

            if (fh.type == FrameHeader.TYPE_REGULAR || fh.type == FrameHeader.TYPE_SKIP_PROGRESSIVE) {
                blendOntoCanvas(canvas, canvasColour, size.width, size.height, r, meta, reference);
                if (sel != null) {
                    canvasTainted = true; // canvas is now correct only within the region
                }
                boolean plainReplace = fh.fullFrame && r.width == size.width
                        && r.height == size.height && fh.blendMode == FrameHeader.BLEND_REPLACE
                        && r.outColour == canvasColour;
                for (FrameHeader.BlendingInfo b : fh.ecBlending) {
                    plainReplace &= b.mode() == FrameHeader.BLEND_REPLACE;
                }
                canvasExact = plainReplace && r.exactInt != null ? r.exactInt : null;
            }
            if (save && !fh.saveBeforeColourTransform) {
                // post-transform references snapshot the composited canvas
                FrameResult snap = new FrameResult();
                snap.width = size.width;
                snap.height = size.height;
                snap.outColour = canvasColour;
                snap.planes = new float[canvas.length][];
                for (int i = 0; i < canvas.length; i++) {
                    snap.planes[i] = canvas[i].clone();
                }
                reference[fh.saveAsReference] = snap;
                refRegionLimited[fh.saveAsReference] = canvasTainted;
            }
            if (visible) {
                frames.add(emitFrame(canvas, canvasExact, canvasColour, size, meta,
                        fh.duration, csRegion, floatOut));
            }
            if (fh.isLast) {
                break;
            }
            entry = parseFrame(src, toc.endOffset, meta, size);
        }
        if (frames.isEmpty()) {
            throw new IOException("codestream contains no displayable frames");
        }
        JxlImage image = new JxlImage(meta, orientedWidth, orientedHeight, frames,
                oriented != null ? oriented.x : 0, oriented != null ? oriented.y : 0);
        image.preview = preview;
        // Hold the file to the level it promised: a container that declared a
        // level but carries content beyond it is malformed, and rejected here
        // rather than returned. Done in the core so every entry point — bytes,
        // stream, region — is covered. The dimension checks are symmetric in the
        // two axes, so the oriented size serves. A bare codestream declares
        // nothing (sentinel 0) and is decoded whatever it holds.
        int declared = src.declaredLevel();
        if (declared != 0) {
            image.codestreamLevel = declared;
            com.ebremer.cygnus.jpegxl.codestream.CodestreamLevel.enforce(
                    declared, meta, orientedWidth, orientedHeight);
        }
        return image;
    }

    /** Renders spot colours if any and emits the canvas (cropped to the region). */
    private static JxlFrame emitFrame(float[][] canvas, int[][] canvasExact, int canvasColour,
            SizeHeader size, ImageMetadata meta, long duration, java.awt.Rectangle csRegion,
            boolean floatOut) {
        // spot colours are rendered at output only; the canvas and the
        // reference snapshots stay spot-free. CMYK is not composited here: unlike
        // spot colours it is not part of the normative decode (the conformance
        // references are the coded CMYK channels), so it is a viewer step, done
        // in the ImageIO reader instead.
        float[][] emit = canvas;
        int[][] emitExact = canvasExact;
        if (canvasColour >= 3 && hasSpotColours(meta)) {
            emit = canvas.clone();
            for (int c = 0; c < 3; c++) {
                emit[c] = canvas[c].clone();
            }
            renderSpotColours(emit, canvasColour, meta);
            emitExact = null;
        }
        return canvasToFrame(emit, emitExact, canvasColour,
                size.width, size.height, meta, duration, csRegion, floatOut);
    }

    private static FrameResult copyResult(FrameResult r) {
        FrameResult c = new FrameResult();
        c.fh = r.fh;
        c.width = r.width;
        c.height = r.height;
        c.outColour = r.outColour;
        c.planes = new float[r.planes.length][];
        for (int i = 0; i < r.planes.length; i++) {
            c.planes[i] = r.planes[i].clone();
        }
        return c;
    }

    private static void requireSupported(FrameHeader fh, ImageMetadata meta, int imageWidth,
            int imageHeight) throws IOException {
        if (fh.isSubsampled && fh.isModular) {
            throw new IOException("chroma subsampling requires a VarDCT frame");
        }
    }

    private static FrameResult decodeFrame(CodestreamSource src, FrameHeader fh, Toc toc,
            ImageMetadata meta, FrameResult[] lfBuffer, GroupSelection sel, boolean partial)
            throws IOException {
        requireSupported(fh, meta, fh.displayWidth, fh.displayHeight);

        FrameState state = new FrameState();
        state.fh = fh;
        state.meta = meta;
        if (fh.hasFlag(FrameHeader.FLAG_USE_LF_FRAME)) {
            FrameResult lf = lfBuffer[fh.lfLevel];
            if (lf == null) {
                throw new IOException("frame references a missing LF frame");
            }
            state.lfFramePlanes = lf.planes;
            state.lfFrameWidth = lf.width;
        }
        if (!fh.isModular) {
            state.vardct = new com.ebremer.cygnus.jpegxl.vardct.VarDctState(fh, meta);
        }

        if (toc.single) {
            Bits bits = section(src, toc, 0);
            decodeLfGlobal(bits, state);
            decodeLfGroup(bits, state, 0);
            decodeHfGlobal(bits, state);
            decodePassGroup(bits, state, 0, 0);
            bits.expectEndOfSection();
        } else {
            long have = src.size();
            if (!arrived(toc, toc.lfGlobalIndex(), have)) {
                throw new IOException("codestream ends before the frame's global section");
            }
            Bits lfg = section(src, toc, toc.lfGlobalIndex());
            decodeLfGlobal(lfg, state);
            lfg.expectEndOfSection();
            if (sel != null && state.gmodular != null) {
                // inverse transforms must refuse reconstructions that would
                // read the groups we are about to skip
                state.gmodular.regionMode = true;
            }
            int[] lfList = sel == null ? sequence(fh.numLfGroups) : sel.lfGroups(fh);
            parallelFor(lfList.length, i -> {
                int gg = lfList[i];
                if (partial && !arrived(toc, toc.lfGroupIndex(gg), have)) {
                    return;
                }
                Bits bits = section(src, toc, toc.lfGroupIndex(gg));
                decodeLfGroup(bits, state, gg);
                bits.expectEndOfSection();
            });
            if (!partial || arrived(toc, toc.hfGlobalIndex(fh), have)) {
                Bits hfg = section(src, toc, toc.hfGlobalIndex(fh));
                if (state.vardct != null) {
                    decodeHfGlobal(hfg, state);
                    hfg.expectEndOfSection();
                } else if (toc.sizes[toc.hfGlobalIndex(fh)] != 0) {
                    throw new IOException("unexpected HfGlobal data in a modular frame");
                }
            }
            // groups are independent; passes accumulate sequentially per group
            int[] gList = sel == null ? sequence(fh.numGroups) : sel.groups(fh);
            parallelFor(gList.length, i -> {
                int g = gList[i];
                for (int pass = 0; pass < fh.passes.numPasses; pass++) {
                    int idx = toc.passGroupIndex(fh, pass, g);
                    if (partial && !arrived(toc, idx, have)) {
                        break;   // and every later pass of this group too
                    }
                    Bits bits = section(src, toc, idx);
                    decodePassGroup(bits, state, pass, g);
                    bits.expectEndOfSection();
                }
            });
        }

        if (state.vardct != null) {
            state.vardct.adaptiveSmoothAll();
        }
        if (state.gmodular != null) {
            state.gmodular.applyInverseTransforms();
        }
        java.util.function.BiConsumer<com.ebremer.cygnus.jpegxl.vardct.VarDctState, FrameHeader> cap =
                JPEG_CAPTURE.get();
        if (cap != null && state.vardct != null && fh.type == FrameHeader.TYPE_REGULAR) {
            cap.accept(state.vardct, fh);
        }
        return buildResult(state, sel);
    }

    private static int[] sequence(int n) {
        int[] list = new int[n];
        for (int i = 0; i < n; i++) {
            list[i] = i;
        }
        return list;
    }

    /**
     * When set on the decoding thread, receives the VarDCT state (raw
     * quantised coefficients) of each regular frame; used for byte-exact
     * JPEG reconstruction.
     */
    public static final ThreadLocal<java.util.function.BiConsumer<
            com.ebremer.cygnus.jpegxl.vardct.VarDctState, FrameHeader>> JPEG_CAPTURE =
            new ThreadLocal<>();

    /** Decoded samples to normalized float planes, filters applied. */
    private static FrameResult buildResult(FrameState state, GroupSelection sel)
            throws IOException {
        FrameHeader fh = state.fh;
        ImageMetadata meta = state.meta;
        int w = fh.width;
        int h = fh.height;
        int numEC = meta.numExtraChannels();

        FrameResult r = new FrameResult();
        r.fh = fh;
        r.width = w;
        r.height = h;
        r.baseCorrelationX = state.baseCorrelationX;
        r.baseCorrelationB = state.baseCorrelationB;
        r.noiseLut = state.noiseLut;
        r.patchesDict = state.patches;
        r.splines = state.splines;

        boolean xyb = meta.xybEncoded;
        float[][] colorF;
        int filterW = w;
        int filterH = h;
        float[] epfSigma = null;
        int epfBlockStride = 0;

        if (state.vardct != null) {
            r.outColour = 3;
            filterW = fh.paddedWidth;
            filterH = fh.paddedHeight;
            colorF = new float[3][];
            for (int c = 0; c < 3; c++) {
                colorF[c] = new float[(filterH >> fh.jpegShiftY[c]) * (filterW >> fh.jpegShiftX[c])];
            }
            state.vardct.reconstruct(colorF);
            if (fh.isSubsampled) {
                for (int c = 0; c < 3; c++) {
                    colorF[c] = upsampleChroma(colorF[c], filterW >> fh.jpegShiftX[c],
                            filterH >> fh.jpegShiftY[c],
                            com.ebremer.cygnus.jpegxl.io.Bits.ceilDiv(w, 1 << fh.jpegShiftX[c]),
                            com.ebremer.cygnus.jpegxl.io.Bits.ceilDiv(h, 1 << fh.jpegShiftY[c]),
                            fh.jpegShiftX[c], fh.jpegShiftY[c]);
                }
            }
            if (fh.restorationFilter.epfIterations > 0) {
                epfSigma = state.vardct.epfInverseSigma(fh.restorationFilter);
                epfBlockStride = filterW >> 3;
            }
        } else {
            List<ModularChannel> out = state.gmodular.channels();
            r.outColour = (!xyb && !fh.doYCbCr && meta.colourEncoding.isGrey()) ? 1 : 3;
            for (int i = 0; i < r.outColour; i++) {
                ModularChannel c = out.get(i);
                if (c.width != w || c.height != h || c.pixels == null) {
                    throw new IOException("decoded channel has unexpected shape");
                }
            }
            if (xyb) {
                colorF = new float[3][w * h];
                int[] mY = out.get(0).pixels;
                int[] mX = out.get(1).pixels;
                int[] mB = out.get(2).pixels;
                for (int i = 0; i < w * h; i++) {
                    colorF[0][i] = state.lfDequant[0] * mX[i];
                    colorF[1][i] = state.lfDequant[1] * mY[i];
                    colorF[2][i] = state.lfDequant[2] * (mY[i] + mB[i]);
                }
            } else if (meta.bitDepth.floatingPoint) {
                colorF = new float[r.outColour][w * h];
                for (int c = 0; c < r.outColour; c++) {
                    int[] px = out.get(c).pixels;
                    for (int i = 0; i < w * h; i++) {
                        colorF[c][i] = meta.bitDepth.sampleToFloat(px[i]);
                    }
                }
                if (ModularStream.DEBUG) {
                    System.err.printf("[jxl] buildResult float: px=%08x %08x -> %08x %08x%n",
                            out.get(0).pixels[0], out.get(0).pixels[1],
                            Float.floatToIntBits(colorF[0][0]), Float.floatToIntBits(colorF[0][1]));
                }
            } else {
                colorF = new float[r.outColour][w * h];
                float scale = (float) (1.0 / ((1L << meta.bitDepth.bitsPerSample) - 1));
                for (int c = 0; c < r.outColour; c++) {
                    int[] px = out.get(c).pixels;
                    for (int i = 0; i < w * h; i++) {
                        colorF[c][i] = px[i] * scale;
                    }
                }
            }
        }

        // a region decode filters only the rows of the selected groups; the
        // margin keeps every region pixel out of the filters' edge erosion.
        // Filters mirror at the visible frame bounds, not the padded edge.
        int bandY0 = sel == null ? 0 : Math.min(filterH, sel.gy0() * fh.groupDim);
        int bandY1 = sel == null ? filterH : Math.min(filterH, sel.gy1() * fh.groupDim);
        if (fh.restorationFilter.gab) {
            RestorationFilters.gaborish(fh.restorationFilter, colorF, filterW, filterH,
                    w, h, bandY0, bandY1);
        }
        if (fh.restorationFilter.epfIterations > 0) {
            RestorationFilters.epf(fh.restorationFilter, colorF, filterW, filterH,
                    w, h, epfSigma, epfBlockStride, fh.restorationFilter.epfSigmaForModular,
                    bandY0, bandY1);
        }

        if (filterW != w) { // crop the padded VarDCT grid to the visible frame
            float[][] cropped = new float[colorF.length][w * h];
            for (int c = 0; c < colorF.length; c++) {
                for (int y = 0; y < h; y++) {
                    System.arraycopy(colorF[c], y * filterW, cropped[c], y * w, w);
                }
            }
            colorF = cropped;
        }

        r.planes = new float[r.outColour + numEC][];
        System.arraycopy(colorF, 0, r.planes, 0, r.outColour);
        if (numEC > 0) {
            List<ModularChannel> out = state.gmodular.channels();
            int modularColour = out.size() - numEC;
            for (int i = 0; i < numEC; i++) {
                ModularChannel c = out.get(modularColour + i);
                int ecW = com.ebremer.cygnus.jpegxl.io.Bits.ceilDiv(fh.displayWidth, fh.ecUpsampling[i]);
                int ecH = com.ebremer.cygnus.jpegxl.io.Bits.ceilDiv(fh.displayHeight, fh.ecUpsampling[i]);
                if (c.width != ecW || c.height != ecH || c.pixels == null) {
                    throw new IOException("decoded extra channel has unexpected shape");
                }
                var ecDepth = meta.extraChannels.get(i).bitDepth;
                float[] p = new float[ecW * ecH];
                if (ecDepth.floatingPoint) {
                    for (int k = 0; k < p.length; k++) {
                        p[k] = ecDepth.sampleToFloat(c.pixels[k]);
                    }
                } else {
                    float scale = (float) (1.0 / ((1L << ecDepth.bitsPerSample) - 1));
                    for (int k = 0; k < p.length; k++) {
                        p[k] = c.pixels[k] * scale;
                    }
                }
                r.planes[r.outColour + i] = p;
            }
        }

        // Keep the raw samples of deep integer images so plain (replace-blend,
        // feature-free) frames can be emitted without float rounding.
        //
        // "Deep" starts at 24, not above it: the canvas is float32, whose
        // mantissa is 24 bits, and a sample survives the trip to [0,1] and back
        // only while its relative error stays under half a step — which holds up
        // to 2^23 and no further. A 24-bit image reaches 2^24-1, so its top half
        // comes back off by one. (That is the whole bug: 23 bits was exact, 25
        // took this path, and 24 fell between them.)
        if (!xyb && !fh.doYCbCr && state.vardct == null
                && !meta.bitDepth.floatingPoint && meta.bitDepth.bitsPerSample > 23) {
            boolean allInt = true;
            for (var ec : meta.extraChannels) {
                allInt &= !ec.bitDepth.floatingPoint;
            }
            if (allInt) {
                List<ModularChannel> out = state.gmodular.channels();
                int modularColour = out.size() - numEC;
                r.exactInt = new int[r.outColour + numEC][];
                for (int i = 0; i < r.outColour; i++) {
                    r.exactInt[i] = out.get(i).pixels;
                }
                for (int i = 0; i < numEC; i++) {
                    r.exactInt[r.outColour + i] = out.get(modularColour + i).pixels;
                }
            }
        }
        return r;
    }

    /** Brings every plane to the display frame size with its own factor. */
    private static void upsampleFrame(FrameResult r, ImageMetadata meta) {
        FrameHeader fh = r.fh;
        if (fh.lfLevel > 0) {
            return; // LF frames stay at their own scale
        }
        int k = fh.upsampling;
        for (int c = 0; c < r.planes.length; c++) {
            int factor = c < r.outColour
                    ? k
                    : fh.ecUpsampling[c - r.outColour];
            if (factor == 1) {
                continue;
            }
            r.exactInt = null; // resampled planes exist only as floats
            int pw = c < r.outColour
                    ? r.width
                    : com.ebremer.cygnus.jpegxl.io.Bits.ceilDiv(fh.displayWidth, factor);
            int ph = c < r.outColour
                    ? r.height
                    : com.ebremer.cygnus.jpegxl.io.Bits.ceilDiv(fh.displayHeight, factor);
            r.planes[c] = Upsampler.upsample(r.planes[c], pw, ph, factor, meta);
            // crop the upsampled plane to the display size if it overshoots
            int uw = pw * factor;
            int uh = ph * factor;
            if (uw != fh.displayWidth || uh != fh.displayHeight) {
                float[] cropped = new float[fh.displayWidth * fh.displayHeight];
                for (int y = 0; y < fh.displayHeight; y++) {
                    System.arraycopy(r.planes[c], y * uw, cropped, y * fh.displayWidth,
                            fh.displayWidth);
                }
                r.planes[c] = cropped;
            }
        }
        r.width = fh.displayWidth;
        r.height = fh.displayHeight;
    }

    private static void applyPatches(FrameResult r, FrameResult[] reference, ImageMetadata meta,
            boolean[] refRegionLimited) throws IOException {
        if (r.patchesDict == null || Boolean.getBoolean("jxl.skipPatches")) {
            return;
        }
        r.exactInt = null;
        for (PatchesDictionary.Patch patch : r.patchesDict.patches) {
            if (patch.ref() > 3) {
                throw new IOException("patch reference out of range");
            }
            FrameResult ref = reference[patch.ref()];
            if (ref == null) {
                continue;
            }
            if (refRegionLimited != null && refRegionLimited[patch.ref()]) {
                // the snapshot is only valid within the region, but the patch
                // may copy from anywhere in it
                throw new RegionUnsupportedException("patch from a region-limited snapshot");
            }
            if (patch.y0() + patch.height() > ref.height || patch.x0() + patch.width() > ref.width) {
                throw new IOException("patch does not fit its reference frame");
            }
            for (int j = 0; j < patch.positions().length; j++) {
                int px = patch.positions()[j][0];
                int py = patch.positions()[j][1];
                if (px < 0 || py < 0
                        || py + patch.height() > r.height || px + patch.width() > r.width) {
                    throw new IOException("patch position out of bounds");
                }
                for (int d = 0; d < r.planes.length; d++) {
                    int blendIdx = d < r.outColour ? 0 : d - r.outColour + 1;
                    PatchesDictionary.Blending info = patch.blendings()[j][blendIdx];
                    blendPatch(r, ref, patch, px, py, d, info);
                }
            }
        }
    }

    private static void blendPatch(FrameResult r, FrameResult ref, PatchesDictionary.Patch patch,
            int px, int py, int d, PatchesDictionary.Blending info) throws IOException {
        int mode = info.mode();
        if (mode == PatchesDictionary.MODE_NONE) {
            return;
        }
        int pw = patch.width();
        int ph = patch.height();
        float[] dst = r.planes[d];
        // the reference frame has the same channel layout; map by relative index
        int refD = d < r.outColour ? Math.min(d, ref.outColour - 1) : ref.outColour + (d - r.outColour);
        float[] src = ref.planes[refD];
        int alphaIdx = r.outColour + info.alphaChannel();
        boolean isAlphaChannel = d >= r.outColour
                && r.fh != null
                && d - r.outColour == info.alphaChannel();
        float[] frameAlpha = alphaIdx < r.planes.length ? r.planes[alphaIdx] : null;
        float[] refAlpha = ref.outColour + info.alphaChannel() < ref.planes.length
                ? ref.planes[ref.outColour + info.alphaChannel()] : null;

        for (int y = 0; y < ph; y++) {
            int dRow = (py + y) * r.width + px;
            int sRow = (patch.y0() + y) * ref.width + patch.x0();
            for (int x = 0; x < pw; x++) {
                float oldS = dst[dRow + x];
                float newS = src[sRow + x];
                float v;
                switch (mode) {
                    case PatchesDictionary.MODE_REPLACE -> v = newS;
                    case PatchesDictionary.MODE_ADD -> v = oldS + newS;
                    case PatchesDictionary.MODE_MUL -> {
                        float n = info.clamp() ? clamp01(newS) : newS;
                        v = oldS * n;
                    }
                    case PatchesDictionary.MODE_BLEND_ABOVE, PatchesDictionary.MODE_BLEND_BELOW -> {
                        boolean above = mode == PatchesDictionary.MODE_BLEND_ABOVE;
                        float aAbove = above
                                ? (refAlpha != null ? refAlpha[sRow + x] : 1f)
                                : (frameAlpha != null ? frameAlpha[dRow + x] : 1f);
                        if (info.clamp()) {
                            aAbove = clamp01(aAbove);
                        }
                        float sAbove = above ? newS : oldS;
                        float sBelow = above ? oldS : newS;
                        float aBelow = above
                                ? (frameAlpha != null ? frameAlpha[dRow + x] : 1f)
                                : (refAlpha != null ? refAlpha[sRow + x] : 1f);
                        if (isAlphaChannel) {
                            v = aAbove + aBelow * (1f - aAbove);
                        } else {
                            float outAlpha = aAbove + aBelow * (1f - aAbove);
                            v = outAlpha <= 0f ? sAbove
                                    : (sAbove * aAbove + sBelow * aBelow * (1f - aAbove)) / outAlpha;
                        }
                    }
                    case PatchesDictionary.MODE_MULADD_ABOVE, PatchesDictionary.MODE_MULADD_BELOW -> {
                        boolean above = mode == PatchesDictionary.MODE_MULADD_ABOVE;
                        float aAbove = above
                                ? (refAlpha != null ? refAlpha[sRow + x] : 1f)
                                : (frameAlpha != null ? frameAlpha[dRow + x] : 1f);
                        if (info.clamp()) {
                            aAbove = clamp01(aAbove);
                        }
                        if (isAlphaChannel) {
                            v = above ? oldS : newS;
                        } else {
                            v = above ? oldS + aAbove * newS : newS + aAbove * oldS;
                        }
                    }
                    default -> throw new IOException("bad patch blend mode " + mode);
                }
                dst[dRow + x] = v;
            }
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    /**
     * JPEG-style chroma upsampling: each doubling uses the 3/4-1/4 triangle
     * filter, horizontal steps first. Neighbour reads clamp at the meaningful
     * frame extent ({@code mw} x {@code mh}), not the padded plane edge.
     */
    private static float[] upsampleChroma(float[] p, int w, int h, int mw, int mh,
            int shiftX, int shiftY) {
        while (shiftX-- > 0) {
            float[] out = new float[h * w * 2];
            for (int y = 0; y < h; y++) {
                int row = y * w;
                int orow = y * w * 2;
                for (int x = 0; x < w; x++) {
                    int cx = Math.min(x, mw - 1);
                    float b75 = 0.75f * p[row + cx];
                    out[orow + 2 * x] = b75 + 0.25f * p[row + Math.max(cx - 1, 0)];
                    out[orow + 2 * x + 1] = b75 + 0.25f * p[row + Math.min(cx + 1, mw - 1)];
                }
            }
            p = out;
            w *= 2;
            mw = Math.min(mw * 2, w);
        }
        while (shiftY-- > 0) {
            float[] out = new float[h * 2 * w];
            for (int y = 0; y < h; y++) {
                int cy = Math.min(y, mh - 1);
                int row = cy * w;
                int rowPrev = Math.max(cy - 1, 0) * w;
                int rowNext = Math.min(cy + 1, mh - 1) * w;
                for (int x = 0; x < w; x++) {
                    float b75 = 0.75f * p[row + x];
                    out[2 * y * w + x] = b75 + 0.25f * p[rowPrev + x];
                    out[(2 * y + 1) * w + x] = b75 + 0.25f * p[rowNext + x];
                }
            }
            p = out;
            h *= 2;
            mh = Math.min(mh * 2, h);
        }
        return p;
    }

    private static void colourTransform(FrameResult r, ImageMetadata meta) {
        if (r.fh != null && r.fh.doYCbCr && r.outColour >= 3) {
            // planes are (Cb, Y, Cr); output stays in the coded (gamma) space
            float[] pcb = r.planes[0];
            float[] py = r.planes[1];
            float[] pcr = r.planes[2];
            for (int i = 0; i < pcb.length; i++) {
                float cb = pcb[i];
                float yv = py[i] + (128f / 255f);
                float cr = pcr[i];
                pcb[i] = yv + 1.402f * cr;
                py[i] = yv - 0.34413628620102214650f * cb - 0.71413628620102214650f * cr;
                pcr[i] = yv + 1.772f * cb;
            }
            return;
        }
        if (!meta.xybEncoded || r.outColour < 3) {
            return;
        }
        new XybConverter(meta).invertXYB(r.planes[0], r.planes[1], r.planes[2]);
        if (meta.iccProfile != null) {
            // XYB with an arbitrary embedded ICC: libjxl leaves the samples in
            // linear sRGB primaries and attaches the profile as metadata (the
            // conformance references are linear), so do the same
            return;
        }
        for (int c = 0; c < 3; c++) {
            Transfer.fromLinear(meta.colourEncoding, r.planes[c]);
        }
    }

    private static boolean hasSpotColours(ImageMetadata meta) {
        for (var ec : meta.extraChannels) {
            if (ec.type == com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo.TYPE_SPOT_COLOUR) {
                return true;
            }
        }
        return false;
    }

    /** Composites spot-colour extra channels onto the colour planes. */
    private static void renderSpotColours(float[][] planes, int colourCount, ImageMetadata meta) {
        if (Boolean.getBoolean("jxl.skipSpot")) {
            return;
        }
        for (int i = 0; i < meta.numExtraChannels(); i++) {
            var ec = meta.extraChannels.get(i);
            if (ec.type != com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo.TYPE_SPOT_COLOUR) {
                continue;
            }
            float[] spot = planes[colourCount + i];
            float scale = ec.spotColour[3];
            for (int c = 0; c < 3; c++) {
                float colour = ec.spotColour[c];
                float[] p = planes[c];
                for (int k = 0; k < p.length; k++) {
                    float mix = scale * spot[k];
                    p[k] = mix * colour + (1f - mix) * p[k];
                }
            }
        }
    }

    /** Blends the frame onto the persistent canvas with its per-channel modes. */
    private static void blendOntoCanvas(float[][] canvas, int canvasColour,
            int canvasWidth, int canvasHeight, FrameResult r, ImageMetadata meta,
            FrameResult[] reference) throws IOException {
        FrameHeader fh = r.fh;
        int px = Math.max(0, fh.x0);
        int py = Math.max(0, fh.y0);
        int fx = px - fh.x0;
        int fy = py - fh.y0;
        int bw = Math.min(fh.x0 + r.width, canvasWidth) - px;
        int bh = Math.min(fh.y0 + r.height, canvasHeight) - py;
        if (bw <= 0 || bh <= 0) {
            return;
        }
        int numEC = r.planes.length - r.outColour;
        for (int c = 0; c < canvas.length; c++) {
            FrameHeader.BlendingInfo info = c < canvasColour
                    ? fh.blending
                    : (c - canvasColour < fh.ecBlending.length
                        ? fh.ecBlending[c - canvasColour] : FrameHeader.BlendingInfo.REPLACE);
            int srcIdx;
            if (c < canvasColour) {
                srcIdx = canvasColour == 1 && r.outColour == 3 ? 1 : c; // green carries grey
            } else {
                if (c - canvasColour >= numEC) {
                    continue;
                }
                srcIdx = r.outColour + (c - canvasColour);
            }
            float[] src = r.planes[srcIdx];
            float[] dst = canvas[c];
            FrameResult ref = reference[info.source()];
            boolean refIsCanvas = ref != null && ref.width == canvasWidth
                    && ref.height == canvasHeight && ref.planes.length == canvas.length;
            float[] old = refIsCanvas ? ref.planes[c] : null;

            // the canvas becomes the source reference frame (empty slots are
            // black) with this frame's rectangle blended on top; only full
            // frames may keep the running canvas implicitly
            if (!fh.fullFrame && old != dst) {
                if (old != null) {
                    System.arraycopy(old, 0, dst, 0, dst.length);
                } else {
                    java.util.Arrays.fill(dst, 0f);
                }
            }

            int mode = info.mode();
            if (mode == FrameHeader.BLEND_REPLACE || (old == null && mode == FrameHeader.BLEND_ADD)) {
                for (int y = 0; y < bh; y++) {
                    System.arraycopy(src, (fy + y) * r.width + fx,
                            dst, (py + y) * canvasWidth + px, bw);
                }
                continue;
            }

            // alpha planes for the alpha-driven modes
            int alphaCanvasIdx = canvasColour + info.alphaChannel();
            int alphaFrameIdx = r.outColour + info.alphaChannel();
            boolean isAlphaChannel = c >= canvasColour
                    && c - canvasColour == info.alphaChannel();
            float[] frameAlpha = alphaFrameIdx < r.planes.length ? r.planes[alphaFrameIdx] : null;
            float[] oldAlpha = refIsCanvas && alphaCanvasIdx < ref.planes.length
                    ? ref.planes[alphaCanvasIdx] : null;
            boolean premult = info.alphaChannel() < meta.extraChannels.size()
                    && meta.extraChannels.get(info.alphaChannel()).alphaAssociated;

            for (int y = 0; y < bh; y++) {
                int dRow = (py + y) * canvasWidth + px;
                int sRow = (fy + y) * r.width + fx;
                for (int x = 0; x < bw; x++) {
                    float newS = src[sRow + x];
                    float oldS = old != null ? old[dRow + x] : 0f;
                    float v;
                    switch (mode) {
                        case FrameHeader.BLEND_ADD -> v = oldS + newS;
                        case FrameHeader.BLEND_MUL -> {
                            float n = info.clamp() ? clamp01(newS) : newS;
                            v = oldS * n;
                        }
                        case FrameHeader.BLEND_BLEND -> {
                            float na = frameAlpha != null ? frameAlpha[sRow + x] : 1f;
                            if (info.clamp()) {
                                na = clamp01(na);
                            }
                            float oa = oldAlpha != null ? oldAlpha[dRow + x] : 0f;
                            if (isAlphaChannel) {
                                v = na + oa * (1f - na);
                            } else if (premult) {
                                v = newS + oldS * (1f - na);
                            } else {
                                float outAlpha = na + oa * (1f - na);
                                v = outAlpha <= 0f ? newS
                                        : (newS * na + oldS * oa * (1f - na)) / outAlpha;
                            }
                        }
                        case FrameHeader.BLEND_MUL_ADD -> {
                            float na = frameAlpha != null ? frameAlpha[sRow + x] : 1f;
                            if (info.clamp()) {
                                na = clamp01(na);
                            }
                            v = isAlphaChannel ? oldS : oldS + na * newS;
                        }
                        default -> throw new IOException("bad frame blend mode " + mode);
                    }
                    dst[dRow + x] = v;
                }
            }
        }
    }

    /**
     * Converts the canvas (or just {@code crop} of it, in codestream
     * coordinates) to output planes: clamped ints, or floats as-is.
     */
    private static JxlFrame canvasToFrame(float[][] canvas, int[][] exact, int canvasColour,
            int width, int height, ImageMetadata meta, long duration, java.awt.Rectangle crop,
            boolean floatOut) {
        int cx = crop == null ? 0 : crop.x;
        int cy = crop == null ? 0 : crop.y;
        int cw = crop == null ? width : crop.width;
        int ch = crop == null ? height : crop.height;
        int[][] intPlanes = new int[canvas.length][];
        float[][] floatPlanes = new float[canvas.length][];
        // An XYB frame with an embedded ICC profile is left in linear light by the
        // colour transform, because the ICC — not an enumerated transfer function —
        // is what the coded output declares, and the conformance references keep it
        // linear (they are the coded samples plus the profile). Displaying those
        // linear samples as if they were sRGB shows the picture far too dark. The
        // eight-bit output is display-oriented, so the sRGB transfer is applied to
        // its colour channels here — the same curve an enumerated-sRGB XYB frame
        // already gets — while the float output stays linear for anyone matching
        // the reference or applying the profile themselves.
        boolean linearColour = meta.xybEncoded && meta.iccProfile != null && canvasColour >= 3;
        for (int c = 0; c < canvas.length; c++) {
            var depth = c < canvasColour
                    ? meta.bitDepth
                    : meta.extraChannels.get(c - canvasColour).bitDepth;
            boolean toSrgb = linearColour && c < canvasColour;
            if (floatOut || depth.floatingPoint) {
                float[] p = new float[cw * ch];
                for (int y = 0; y < ch; y++) {
                    System.arraycopy(canvas[c], (cy + y) * width + cx, p, y * cw, cw);
                }
                floatPlanes[c] = p;
            } else if (exact != null && depth.bitsPerSample > 23) {
                // deep integers pass through exactly instead of via float32,
                // whose 24-bit mantissa cannot hold a sample above 2^23 and
                // return it unchanged
                long max = (1L << depth.bitsPerSample) - 1;
                int[] p = new int[cw * ch];
                int[] src = exact[c];
                for (int y = 0; y < ch; y++) {
                    int srcRow = (cy + y) * width + cx;
                    int dstRow = y * cw;
                    for (int x = 0; x < cw; x++) {
                        long v = src[srcRow + x];
                        p[dstRow + x] = v < 0 ? 0 : (int) Math.min(v, max);
                    }
                }
                intPlanes[c] = p;
            } else {
                long max = (1L << depth.bitsPerSample) - 1;
                float[] f = canvas[c];
                int[] p = new int[cw * ch];
                for (int y = 0; y < ch; y++) {
                    int srcRow = (cy + y) * width + cx;
                    int dstRow = y * cw;
                    for (int x = 0; x < cw; x++) {
                        float s = toSrgb ? srgbOetf(f[srcRow + x]) : f[srcRow + x];
                        long v = Math.round((double) s * max);
                        p[dstRow + x] = v < 0 ? 0 : (int) Math.min(v, max);
                    }
                }
                intPlanes[c] = p;
            }
        }
        return orient(meta, cw, ch, duration, intPlanes, floatPlanes);
    }

    /** The sRGB opto-electronic transfer function: linear light to the display encoding. */
    private static float srgbOetf(float v) {
        if (v <= 0f) {
            return 0f;
        }
        if (v >= 1f) {
            return 1f;
        }
        return v <= 0.0031308f ? 12.92f * v : 1.055f * (float) Math.pow(v, 1 / 2.4) - 0.055f;
    }

    private static Bits section(CodestreamSource src, Toc toc, int index) throws IOException {
        int size = toc.sizes[index];
        return new Bits(src.readRange(toc.offsets[index], size), 0, size, toc.offsets[index]);
    }

    /** Whether all of a section's bytes are in the codestream we have. */
    private static boolean arrived(Toc toc, int index, long have) {
        return toc.offsets[index] + (long) toc.sizes[index] <= have;
    }

    /** A loop body that decodes one independent unit. */
    public interface IoIntConsumer {
        void accept(int i) throws IOException;
    }

    /** Runs {@code body} for 0..n-1, in parallel when it pays off. */
    public static void parallelFor(int n, IoIntConsumer body) throws IOException {
        if (n <= 1) {
            if (n == 1) {
                body.accept(0);
            }
            return;
        }
        try {
            java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
                try {
                    body.accept(i);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static final class FrameState {
        FrameHeader fh;
        ImageMetadata meta;
        MaTree globalTree;
        EntropyDecoder globalCode;
        ModularStream gmodular;
        com.ebremer.cygnus.jpegxl.vardct.VarDctState vardct;
        int numGlobalChannels;
        int numColourChannels;
        final float[] lfDequant = {1f / 4096f, 1f / 512f, 1f / 256f};
        PatchesDictionary patches;
        Splines splines;
        float[] noiseLut;
        float baseCorrelationX = 0f;
        float baseCorrelationB = 1f;
        float[][] lfFramePlanes;
        int lfFrameWidth;
    }

    private static void decodeLfGlobal(Bits in, FrameState state) throws IOException {
        FrameHeader fh = state.fh;
        ImageMetadata meta = state.meta;

        if (fh.hasFlag(FrameHeader.FLAG_PATCHES)) {
            int numAlpha = 0;
            for (var ec : meta.extraChannels) {
                if (ec.type == com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo.TYPE_ALPHA) {
                    numAlpha++;
                }
            }
            state.patches = PatchesDictionary.read(in, meta.numExtraChannels(), numAlpha);
        }
        if (fh.hasFlag(FrameHeader.FLAG_SPLINES)) {
            if (meta.colourChannelCount() < 3 && !meta.xybEncoded) {
                throw new IOException("splines require colour images");
            }
            state.splines = Splines.read(in);
        }
        if (fh.hasFlag(FrameHeader.FLAG_NOISE)) {
            if (meta.colourChannelCount() < 3 && !meta.xybEncoded) {
                throw new IOException("noise requires colour images");
            }
            state.noiseLut = new float[8];
            for (int i = 0; i < 8; i++) {
                state.noiseLut[i] = in.u(10) / 1024f;
            }
        }
        if (!in.bool()) { // LfChannelDequantization.all_default
            for (int i = 0; i < 3; i++) {
                state.lfDequant[i] = in.f16() * (1f / 128f);
            }
        }
        if (state.vardct != null) {
            state.vardct.readLfGlobal(in, state.lfDequant);
            state.baseCorrelationX = state.vardct.baseCorrelationX;
            state.baseCorrelationB = state.vardct.baseCorrelationB;
        }

        int modularColour;
        if (fh.isModular) {
            modularColour = (!fh.doYCbCr && !meta.xybEncoded && meta.colourEncoding.isGrey()) ? 1 : 3;
        } else {
            modularColour = 0; // VarDCT codes colour separately
        }
        state.numColourChannels = fh.isModular ? modularColour : 3;
        List<ModularChannel> channels = new ArrayList<>();
        for (int i = 0; i < modularColour; i++) {
            channels.add(new ModularChannel(fh.width, fh.height));
        }
        int upShift = Bits.floorLog2(fh.upsampling);
        for (int i = 0; i < meta.numExtraChannels(); i++) {
            int ecups = fh.ecUpsampling[i];
            int shift = Bits.floorLog2(ecups) - upShift;
            channels.add(new ModularChannel(
                    Bits.ceilDiv(fh.displayWidth, ecups),
                    Bits.ceilDiv(fh.displayHeight, ecups), shift, shift));
        }

        if (in.bool()) { // global tree present
            long maxSize = 1024 + (long) fh.width * fh.height * Math.max(channels.size(), 1) / 16;
            maxSize = Math.min(1 << 22, maxSize);
            MaTree.WithCode wc = MaTree.read(in, (int) maxSize);
            state.globalTree = wc.tree();
            state.globalCode = wc.code();
        }

        if (channels.isEmpty()) {
            state.gmodular = null;
            state.numGlobalChannels = 0;
            return;
        }
        state.gmodular = ModularStream.readHeader(in, channels, state.globalTree,
                state.globalCode, meta.bitDepth.bitsPerSample, fh.groupDim);
        state.gmodular.allocate();
        // the global stream codes a prefix of channels: meta channels plus any
        // leading channels no bigger than a group; it stops at the first
        // larger one (the rest are coded per group)
        List<ModularChannel> transformed = state.gmodular.channels();
        int numGm = 0;
        for (int i = 0; i < transformed.size(); i++) {
            ModularChannel c = transformed.get(i);
            if (i >= state.gmodular.nbMetaChannels()
                    && (c.width > fh.groupDim || c.height > fh.groupDim)) {
                break;
            }
            numGm++;
        }
        state.numGlobalChannels = numGm;
        for (int i = 0; i < state.numGlobalChannels; i++) {
            state.gmodular.decodeChannel(in, i, 0);
        }
        state.gmodular.finish(in);
    }

    private static void decodeHfGlobal(Bits in, FrameState state) throws IOException {
        if (state.vardct == null) {
            return;
        }
        state.vardct.readHfGlobal(in, (bits, streamIndex, height, width) -> {
            int[][] shapes = {{height, width}, {height, width}, {height, width}};
            return readFixedModular(bits, state, streamIndex, shapes);
        });
    }

    /** Decodes a modular sub-stream with fixed channel shapes (VarDCT helper streams). */
    private static int[][] readFixedModular(Bits in, FrameState state, int sidx, int[][] shapes)
            throws IOException {
        List<ModularChannel> channels = new ArrayList<>();
        for (int[] s : shapes) {
            channels.add(s.length >= 4
                    ? new ModularChannel(s[1], s[0], s[3], s[2])
                    : new ModularChannel(s[1], s[0]));
        }
        ModularStream stream = ModularStream.readHeader(in, channels, state.globalTree,
                state.globalCode, state.meta.bitDepth.bitsPerSample);
        stream.allocate();
        stream.decodeAllChannels(in, sidx);
        stream.finish(in);
        stream.applyInverseTransforms();
        List<ModularChannel> out = stream.channels();
        int[][] planes = new int[out.size()][];
        for (int i = 0; i < out.size(); i++) {
            ModularChannel c = out.get(i);
            planes[i] = c.pixels != null ? c.pixels : new int[Math.max(c.width * c.height, 0)];
        }
        return planes;
    }

    private static void decodeLfGroup(Bits in, FrameState state, int ggIdx) throws IOException {
        FrameHeader fh = state.fh;
        if (state.vardct != null) {
            state.vardct.readLfCoefficients(in, ggIdx,
                    (bits, sidx, shapes) -> readFixedModular(bits, state, sidx, shapes),
                    state.lfFramePlanes, state.lfFrameWidth);
        }
        int lfDim = fh.groupDim * 8;
        int row = ggIdx / fh.lfGroupColumns;
        int col = ggIdx % fh.lfGroupColumns;
        int left = col * lfDim;
        int top = row * lfDim;
        int w = Math.min(lfDim, fh.width - left);
        int h = Math.min(lfDim, fh.height - top);
        int sidx = 1 + fh.numLfGroups + ggIdx;
        decodeSubStream(in, state, left, top, w, h, sidx, 3, Integer.MAX_VALUE);
        if (state.vardct != null) {
            state.vardct.readHfMetadata(in, ggIdx,
                    (bits, sidx2, shapes) -> readFixedModular(bits, state, sidx2, shapes));
        }
    }

    private static void decodePassGroup(Bits in, FrameState state, int pass, int gIdx)
            throws IOException {
        FrameHeader fh = state.fh;
        if (state.vardct != null) {
            state.vardct.readHfCoefficients(in, pass, gIdx);
        }
        int row = gIdx / fh.groupColumns;
        int col = gIdx % fh.groupColumns;
        int left = col * fh.groupDim;
        int top = row * fh.groupDim;
        int w = Math.min(fh.groupDim, fh.width - left);
        int h = Math.min(fh.groupDim, fh.height - top);
        int sidx = 1 + 3 * fh.numLfGroups + NUM_DCT_PARAMS + pass * fh.numGroups + gIdx;
        int minShift = fh.passes.minShift(pass);
        int maxShift = fh.passes.maxShift(pass);
        decodeSubStream(in, state, left, top, w, h, sidx, minShift, maxShift);
    }

    /**
     * Decodes a per-group modular sub-stream covering the given frame rectangle
     * and pastes the result into the global channels. Channels are selected by
     * their {@code min(hshift, vshift)}: LF group streams take [3, inf), pass
     * group streams take the pass's shift range.
     */
    private static void decodeSubStream(Bits in, FrameState state, int left, int top,
            int w, int h, int sidx, int minShift, int maxShift) throws IOException {
        if (state.gmodular == null) {
            return;
        }
        List<ModularChannel> global = state.gmodular.channels();
        List<ModularChannel> rect = new ArrayList<>();
        List<int[]> mapping = new ArrayList<>(); // {globalIndex, x0, y0}
        for (int i = state.numGlobalChannels; i < global.size(); i++) {
            ModularChannel gc = global.get(i);
            int m = Math.min(gc.hshift, gc.vshift);
            boolean isLf = m >= 3;
            boolean included = maxShift == Integer.MAX_VALUE
                    ? isLf
                    : !isLf && minShift <= m && m < maxShift;
            if (!included) {
                continue;
            }
            int x0 = left >> gc.hshift;
            int y0 = top >> gc.vshift;
            int cw = Math.min(Bits.ceilDiv(w, 1 << gc.hshift), gc.width - x0);
            int ch = Math.min(Bits.ceilDiv(h, 1 << gc.vshift), gc.height - y0);
            if (cw <= 0 || ch <= 0) {
                // A channel with nothing of it inside this group is not in this
                // group's stream at all — not even as an empty one. It matters:
                // the channel index is an MA-tree property, so an omitted channel
                // renumbers every channel after it. A squeeze on an odd width
                // leaves a residual one column narrower than its average, which
                // is exactly nothing when the last group column is one pixel.
                continue;
            }
            rect.add(new ModularChannel(cw, ch, gc.hshift, gc.vshift));
            mapping.add(new int[] {i, x0, y0});
        }
        if (rect.isEmpty()) {
            return;
        }
        ModularStream sub = ModularStream.readHeader(in, rect, state.globalTree,
                state.globalCode, state.meta.bitDepth.bitsPerSample);
        sub.allocate();
        sub.decodeAllChannels(in, sidx);
        sub.finish(in);
        sub.applyInverseTransforms();

        List<ModularChannel> decoded = sub.channels();
        if (decoded.size() != mapping.size()) {
            throw new IOException("group sub-stream channel mismatch");
        }
        for (int k = 0; k < decoded.size(); k++) {
            ModularChannel src = decoded.get(k);
            if (src.isEmpty()) {
                continue;
            }
            int[] map = mapping.get(k);
            ModularChannel dst = state.gmodular.channels().get(map[0]);
            for (int y = 0; y < src.height; y++) {
                System.arraycopy(src.pixels, y * src.width,
                        dst.pixels, (map[2] + y) * dst.width + map[1], src.width);
            }
            if (ModularStream.DEBUG) {
                System.err.printf("[jxl] paste ch%d -> global %d at (%d,%d): %08x %08x %08x%n",
                        k, map[0], map[1], map[2], src.pixels[0], src.pixels[1], src.pixels[2]);
            }
        }
    }

    // ------------------------------------------------------- region decoding

    /**
     * Safety margin in coded pixels around a decode region: covers the
     * restoration filters (reach ≤ 9), chroma and up-to-8x upsampling kernels,
     * and per-group extra-channel shifts (≤ 3). Non-local reconstructions
     * (frame-global squeeze, delta palettes) are excluded by fallback instead.
     */
    private static final int REGION_MARGIN = 16;

    /** End-exclusive HF and LF group grid ranges covering a decode region. */
    private record GroupSelection(int gx0, int gx1, int gy0, int gy1,
                                  int lgx0, int lgx1, int lgy0, int lgy1) {

        static final GroupSelection EMPTY = new GroupSelection(0, 0, 0, 0, 0, 0, 0, 0);

        boolean empty() {
            return gx0 >= gx1 || gy0 >= gy1;
        }

        int[] groups(FrameHeader fh) {
            int[] list = new int[(gx1 - gx0) * (gy1 - gy0)];
            int n = 0;
            for (int y = gy0; y < gy1; y++) {
                for (int x = gx0; x < gx1; x++) {
                    list[n++] = y * fh.groupColumns + x;
                }
            }
            return list;
        }

        int[] lfGroups(FrameHeader fh) {
            int[] list = new int[(lgx1 - lgx0) * (lgy1 - lgy0)];
            int n = 0;
            for (int y = lgy0; y < lgy1; y++) {
                for (int x = lgx0; x < lgx1; x++) {
                    list[n++] = y * fh.lfGroupColumns + x;
                }
            }
            return list;
        }
    }

    /**
     * Chooses the groups to decode for a region, or null to decode everything
     * (no region, a frame that must stay complete, or a region covering every
     * group anyway). {@code csRegion} is in codestream canvas coordinates.
     */
    private static GroupSelection selectGroups(FrameHeader fh, java.awt.Rectangle csRegion,
            boolean allow) {
        if (csRegion == null || !allow) {
            return null;
        }
        // canvas coordinates -> frame display coordinates
        long ix0 = Math.max(0, (long) csRegion.x - fh.x0);
        long iy0 = Math.max(0, (long) csRegion.y - fh.y0);
        long ix1 = Math.min(fh.displayWidth, (long) csRegion.x - fh.x0 + csRegion.width);
        long iy1 = Math.min(fh.displayHeight, (long) csRegion.y - fh.y0 + csRegion.height);
        if (ix0 >= ix1 || iy0 >= iy1) {
            return GroupSelection.EMPTY;
        }
        // display -> coded coordinates, expanded by the safety margin
        int up = fh.upsampling;
        int px0 = (int) Math.max(0, ix0 / up - REGION_MARGIN);
        int py0 = (int) Math.max(0, iy0 / up - REGION_MARGIN);
        int px1 = (int) Math.min(fh.width, (ix1 + up - 1) / up + REGION_MARGIN);
        int py1 = (int) Math.min(fh.height, (iy1 + up - 1) / up + REGION_MARGIN);
        int gd = fh.groupDim;
        int lfd = gd * 8;
        GroupSelection sel = new GroupSelection(
                px0 / gd, Math.min(fh.groupColumns, (px1 + gd - 1) / gd),
                py0 / gd, Math.min(fh.groupRows, (py1 + gd - 1) / gd),
                px0 / lfd, Math.min(fh.lfGroupColumns, (px1 + lfd - 1) / lfd),
                py0 / lfd, Math.min(fh.lfGroupRows, (py1 + lfd - 1) / lfd));
        boolean full = sel.gx0 == 0 && sel.gy0 == 0
                && sel.gx1 == fh.groupColumns && sel.gy1 == fh.groupRows
                && sel.lgx0 == 0 && sel.lgy0 == 0
                && sel.lgx1 == fh.lfGroupColumns && sel.lgy1 == fh.lfGroupRows;
        return full ? null : sel;
    }

    /** Maps a rectangle in oriented image coordinates back to codestream coordinates. */
    private static java.awt.Rectangle inverseOrientRect(int orientation, int csWidth,
            int csHeight, java.awt.Rectangle r) {
        if (orientation == 1) {
            return new java.awt.Rectangle(r.x, r.y, r.width, r.height);
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            int x = (corner & 1) == 0 ? r.x : r.x + r.width - 1;
            int y = (corner & 2) == 0 ? r.y : r.y + r.height - 1;
            int sx;
            int sy;
            switch (orientation) {
                case 2 -> {
                    sx = csWidth - 1 - x;
                    sy = y;
                }
                case 3 -> {
                    sx = csWidth - 1 - x;
                    sy = csHeight - 1 - y;
                }
                case 4 -> {
                    sx = x;
                    sy = csHeight - 1 - y;
                }
                case 5 -> {
                    sx = y;
                    sy = x;
                }
                case 6 -> {
                    sx = y;
                    sy = csHeight - 1 - x;
                }
                case 7 -> {
                    sx = csWidth - 1 - y;
                    sy = csHeight - 1 - x;
                }
                case 8 -> {
                    sx = csWidth - 1 - y;
                    sy = x;
                }
                default -> throw new IllegalStateException("orientation " + orientation);
            }
            minX = Math.min(minX, sx);
            minY = Math.min(minY, sy);
            maxX = Math.max(maxX, sx);
            maxY = Math.max(maxY, sy);
        }
        return new java.awt.Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /**
     * The natural tile granularity of the first frame: its group size in
     * display pixels. Header-only, nothing is decoded.
     */
    public static int readTileDim(CodestreamSource src) throws IOException {
        Prelude p = parsePrelude(src);
        FrameEntry e = p.firstFrame() != null ? p.firstFrame()
                : parseFrame(src, p.preview().toc().endOffset, p.meta(), p.size());
        return e.fh().groupDim * e.fh().upsampling;
    }

    /** Applies the EXIF-style orientation to both integer and float planes. */
    private static JxlFrame orient(ImageMetadata meta, int w, int h, long duration,
            int[][] intPlanes, float[][] floatPlanes) {
        int orientation = meta.orientation;
        if (orientation == 1) {
            return new JxlFrame(w, h, intPlanes, floatPlanes, duration);
        }
        boolean transposed = orientation > 4;
        int ow = transposed ? h : w;
        int oh = transposed ? w : h;
        int[][] outInt = new int[intPlanes.length][];
        float[][] outFloat = new float[floatPlanes.length][];
        for (int c = 0; c < intPlanes.length; c++) {
            int[] srcI = intPlanes[c];
            float[] srcF = floatPlanes[c];
            int[] dstI = srcI != null ? new int[ow * oh] : null;
            float[] dstF = srcF != null ? new float[ow * oh] : null;
            for (int y = 0; y < oh; y++) {
                for (int x = 0; x < ow; x++) {
                    int sx;
                    int sy;
                    switch (orientation) {
                        case 2 -> {
                            sx = w - 1 - x;
                            sy = y;
                        }
                        case 3 -> {
                            sx = w - 1 - x;
                            sy = h - 1 - y;
                        }
                        case 4 -> {
                            sx = x;
                            sy = h - 1 - y;
                        }
                        case 5 -> {
                            sx = y;
                            sy = x;
                        }
                        case 6 -> {
                            sx = y;
                            sy = h - 1 - x;
                        }
                        case 7 -> {
                            sx = w - 1 - y;
                            sy = h - 1 - x;
                        }
                        case 8 -> {
                            sx = w - 1 - y;
                            sy = x;
                        }
                        default -> throw new IllegalStateException();
                    }
                    if (dstI != null) {
                        dstI[y * ow + x] = srcI[sy * w + sx];
                    } else {
                        dstF[y * ow + x] = srcF[sy * w + sx];
                    }
                }
            }
            outInt[c] = dstI;
            outFloat[c] = dstF;
        }
        return new JxlFrame(ow, oh, outInt, outFloat, duration);
    }
}
