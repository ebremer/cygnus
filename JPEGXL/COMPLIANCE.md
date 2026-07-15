# JPEG XL compliance

This document records how this pure-Java codec (`com.ebremer.cygnus.jpegxl`)
conforms to the JPEG XL standard, and enumerates the evidence behind each claim.

## What "compliance" means for JPEG XL

The standard is asymmetric, and this document follows that split:

- **Decoding is normative.** [ISO/IEC 18181-1](https://www.iso.org/standard/85066.html)
  specifies the exact codestream syntax and the decoding process; a conforming
  decoder reproduces the reference pixels within the tolerances that
  [ISO/IEC 18181-3](https://www.iso.org/standard/87633.html) (conformance testing)
  lays down. **This decoder is validated against the complete official
  conformance corpus and passes every case within its declared thresholds.**
- **Encoding is informative.** The standard gives *guidance* on encoding but
  does not mandate a bitstream; any codestream a conforming decoder accepts is
  valid. So the encoder's "compliance" claim is that **it produces valid
  codestreams that the reference implementation (libjxl) decodes correctly** —
  verified by round-tripping every encoder feature through libjxl (via cjxl/djxl
  and ffmpeg).

Two independent anchors are used throughout: the **conformance corpus** (the
reference pixel data of [ISO/IEC 18181-3](https://www.iso.org/standard/87633.html),
needing no external decoder) and the **reference software** libjxl
([ISO/IEC 18181-4](https://www.iso.org/standard/80619.html)).

## Specification references

JPEG XL is the multi-part **ISO/IEC 18181** series. ISO texts are published
(paywalled); the reference software and the conformance corpus are the
freely-available normative anchors.

| Part | Title | Reference | Role here |
|------|-------|-----------|-----------|
| 1 | Core coding system | [ISO/IEC 18181-1:2024](https://www.iso.org/standard/85066.html) | The codestream and decoding process this codec implements |
| 2 | File format | [ISO/IEC 18181-2](https://www.iso.org/standard/80617.html) (2021 and later editions) | ISOBMFF container, metadata boxes, JPEG bitstream reconstruction |
| 3 | Conformance testing | [ISO/IEC 18181-3:2025](https://www.iso.org/standard/87633.html) | The methodology behind the corpus the decoder is validated against |
| 4 | Reference software | [ISO/IEC 18181-4:2022](https://www.iso.org/standard/80619.html) | libjxl, used for cross-validation |

Supporting resources:

- JPEG XL homepage — <https://jpeg.org/jpegxl/>
- Workplan & specifications (part/edition status) — <https://jpeg.org/jpegxl/workplan.html>
- JPEG XL White Paper — <https://ds.jpeg.org/whitepapers/jpeg-xl-whitepaper.pdf>
- Reference implementation, libjxl — <https://github.com/libjxl/libjxl>
- Conformance corpus — <https://github.com/libjxl/conformance>
- Brotli compressed-data format (used by the `jbrd` reconstruction data) —
  [RFC 7932](https://www.rfc-editor.org/rfc/rfc7932)

## Status at a glance

| Area | Standard | Status | Primary evidence |
|------|----------|--------|------------------|
| Decoder — VarDCT (lossy) | 18181-1 | Complete | Conformance corpus, libjxl interop |
| Decoder — Modular (lossless & XYB-lossy) | 18181-1 | Complete | Conformance corpus, bit-exact round-trips |
| Decoder — entropy layer | 18181-1 | Complete | Conformance corpus |
| Decoder — colour (XYB, transfer fns, ICC) | 18181-1 | Complete (ICC *applied* for matrix/TRC) | Conformance corpus, ICC byte-exact |
| File format / container | 18181-2 | Complete (read & write) | Container tests, corpus |
| JPEG reconstruction (`jbrd`) | 18181-2 | Complete (baseline & progressive) | Byte-exact corpus + round-trip |
| Conformance corpus | 18181-3 | **40 / 40 cases pass, 0 deviations** | `OfficialConformanceTest` |
| Encoder — produces valid streams | 18181-1 (informative) | Broad coverage | libjxl decodes every path |
| Codestream level enforcement | 18181-1 | Complete (levels 5 & 10) | `LevelTest` |

**Test suite: 448 tests, 0 failures, 0 errors** (10 skipped, each gated on an
absent external tool or an opt-in flag). See [Test-suite summary](#test-suite-summary).

---

## Part 1 — Core coding system (decoder)

The decoder implements the full normative decode process of ISO/IEC 18181-1.
The table maps each normative subsystem (using the standard's own terminology)
to its implementation and the tests that exercise it.

### Codestream headers and metadata

| Subsystem | Implementation | Status |
|-----------|----------------|--------|
| Codestream signature (`0xFF0A` / container) | `CodestreamSource`, `Container` | ✅ |
| `SizeHeader` (dimensions, ratios, small/large) | `SizeHeader` | ✅ |
| `ImageMetadata` (bit depth, orientation, extra channels, preview, animation) | `ImageMetadata` | ✅ |
| `BitDepth` (integer 1–31, float binary16/32, custom float) | `BitDepth` | ✅ |
| `ColourEncoding` (enumerated & ICC) | `ColourEncoding`, `IccStream` | ✅ |
| `ExtraChannelInfo` (all types) | `ExtraChannelInfo` | ✅ |
| `CodestreamLevel` (`jxll`, levels 5 / 10) | `CodestreamLevel` | ✅ |

### Frame structure

| Subsystem | Implementation | Status |
|-----------|----------------|--------|
| `FrameHeader` (all frame types, blend info, passes, crop) | `FrameHeader` | ✅ |
| Table of contents, including permuted TOC | `Toc` | ✅ |
| `PassesInfo` (multi-pass / progressive AC) | `PassesInfo` | ✅ |
| LfGlobal / LfGroup / HfGlobal / PassGroup sections | `JxlDecoder`, `VarDctState` | ✅ |
| Reference frames & frame compositing | `JxlDecoder` | ✅ |

### Entropy coding

| Subsystem | Implementation | Status |
|-----------|----------------|--------|
| Prefix (Huffman) codes | `PrefixCode` | ✅ |
| rANS / ANS with the normative alias mapping | `AnsDecoder` | ✅ |
| Hybrid-integer configuration | `HybridUintConfig` | ✅ |
| LZ77 (run-length back-references) | `EntropyDecoder` | ✅ |
| Context clustering / histogram maps | `EntropyDecoder` | ✅ |

### Modular mode

| Subsystem | Implementation | Status |
|-----------|----------------|--------|
| Meta-adaptive (MA) tree, every property | `MaTree`, `ModularStream` | ✅ |
| All 14 predictors, incl. self-correcting weighted predictor (normative 32-bit error arithmetic) | `WpState` | ✅ |
| RCT (reversible colour transform), types 0–6 | `InverseTransforms` | ✅ |
| Palette, incl. implicit and delta palettes | `InverseTransforms` | ✅ |
| Squeeze transform | `InverseTransforms` | ✅ |

### VarDCT mode

| Subsystem | Implementation | Status |
|-----------|----------------|--------|
| All 27 transform types (DCT2…DCT256, rectangular DCTs, AFV, Hornuss) | `TransformType`, `Dct`, `Transforms`, `SmallDct` | ✅ |
| Dequantisation weights, all encodings | `DequantMatrices` | ✅ |
| HF coefficient context modelling | `HfPass` | ✅ |
| Chroma-from-luma | `VarDctState` | ✅ |
| Adaptive quant field & LF smoothing | `VarDctState` | ✅ |

### Restoration filters and features

| Subsystem | Implementation | Status |
|-----------|----------------|--------|
| Gaborish filter | `RestorationFilters` | ✅ |
| Edge-preserving filter (EPF) | `RestorationFilters` | ✅ |
| Patches (all patch blend modes, reference frames) | `PatchesDictionary` | ✅ |
| Splines (centripetal Catmull-Rom, additive render) | `Splines` | ✅ |
| Noise synthesis | `Noise` | ✅ |

### Colour and geometry

| Subsystem | Implementation | Status |
|-----------|----------------|--------|
| XYB ⇄ RGB (opsin) pipeline | `XybConverter` | ✅ |
| Transfer functions: sRGB, linear, gamma, BT.709, PQ, DCI, HLG | `Transfer` | ✅ |
| Embedded ICC reconstruction (byte-exact) | `IccStream` | ✅ |
| ICC *application* for display (matrix/TRC profiles) | `IccColorTransform` | ✅ (see [limitations](#known-limitations-and-documented-deviations)) |
| CMYK compositing, CFA (Bayer) demosaic, spot colour | `CfaDemosaic`, `JxlDecoder` | ✅ |
| Upsampling 2×/4×/8× (default & custom weights), independent extra-channel upsampling, `dim_shift` | `Upsampler` | ✅ |
| Cropped frames, per-channel blend (replace, add, blend, mul-add, multiply) | `JxlDecoder` | ✅ |
| EXIF orientations 1–8 | `JxlDecoder` | ✅ |
| Region (windowed) decode of arbitrary rectangles | `JxlDecoder`, `io` | ✅ |
| Sample types: integer 1–31 bits, float binary16/32/custom (bit-exact) | `BitDepth`, `JxlDecoder` | ✅ |

---

## Part 2 — File format (container)

| Subsystem | Implementation | Status |
|-----------|----------------|--------|
| Bare codestream and ISOBMFF `.jxl` container | `Container`, `CodestreamSource` | ✅ read & write |
| Codestream boxes (`jxlc`, partial `jxlp`) | `Container` | ✅ |
| Level box (`jxll`) | `CodestreamLevel` | ✅ |
| Metadata boxes: Exif, XMP, Brotli-compressed (`brob`) | `Container` | ✅ read & write |
| Gain-map box (`jhgm`) | `GainMap` | ✅ read & write |
| JPEG bitstream reconstruction data (`jbrd`) | `JpegReconstructor`, `JpegParser`, `JpegData` | ✅ |
| Brotli decode for reconstruction data ([RFC 7932](https://www.rfc-editor.org/rfc/rfc7932)) | `Brotli` | ✅ (static dictionary + all 121 transforms) |

**JPEG reconstruction** rebuilds the original JPEG byte-for-byte: baseline and
progressive Huffman scans, restart intervals, recorded padding bits,
grayscale / 4:4:4 / 4:2:2 / 4:2:0, and ICC/Exif/XMP markers. Arithmetic-coded,
12-bit and hierarchical JPEGs are out of scope (rejected on the encode side).

---

## Part 3 — Conformance testing (the decisive evidence)

`OfficialConformanceTest` runs the **complete official conformance corpus**
([github.com/libjxl/conformance](https://github.com/libjxl/conformance)) against
the corpus's own reference data — no external decoder required. The pass
criteria mirror the corpus's `scripts/conformance.py`:

- **per-channel RMSE** and **peak absolute error** within each testcase's
  declared limits (read from its `test.json`);
- **byte-exact** embedded ICC profiles;
- **byte-exact** reconstructed JPEGs for `jbrd` cases.

**Result: all 40 corpus cases pass. The `KNOWN_DEVIATIONS` set is empty —
there are no documented deviations.**

The corpus spans the whole format. Representative cases and what each exercises:

| Case | Exercises |
|------|-----------|
| `bike`, `bicycles`, `cafe` | VarDCT photographs (full transform/quant/filter pipeline) |
| `lossless_pfm` | 32-bit float, bit-exact (compared `floatToIntBits`) |
| `lz77_flower` | LZ77 back-references in the entropy layer |
| `delta_palette` | delta palette transform |
| `opsin_inverse` | XYB → RGB opsin inverse |
| `noise` | noise synthesis |
| `patches`, `patches_lossless` | patch dictionary + reference frames + blend modes |
| `splines` / animation-spline | spline rendering |
| `progressive` | multi-pass AC refinement + LF (DC) frames |
| `upsampling` | 2×/4×/8× upsampling |
| `spot` | spot-colour extra channel |
| `grayscale` | single-channel colour |
| animation cases | multi-frame compositing, blend modes |
| `jbrd` / reconstructed-JPEG cases | byte-exact JPEG reconstruction |

A supplementary `ConformanceTest` additionally compares decoder output against
**djxl** (the reference decoder) when a corpus checkout and libjxl tools are
present, as a second, independent oracle.

---

## Part 4 — Reference software (libjxl cross-validation)

Beyond the corpus, the decoder and encoder are cross-checked against libjxl
directly (`cjxl`/`djxl` and ffmpeg's `libjxl`). These tests are skipped when the
tools are absent.

| Test class | Cases | What it validates |
|------------|------:|-------------------|
| `OfficialConformanceTest` | 40 | Full conformance corpus (see Part 3) |
| `FfmpegInteropTest` | 37 | Our encoded streams decoded by libjxl (all modes) + libjxl streams decoded by us |
| `FfmpegFeatureCoverageTest` | 7 | Feature-by-feature coverage vs `cjxl` output |
| `ConformanceTest` | (env-gated) | Decode vs `djxl` on corpus inputs |

**Fidelity conventions.** Lossless paths agree with libjxl **bit-exactly**
(including 32-bit floats). Lossy paths agree within a small tolerance — this
decoder uses exact math where libjxl uses fast approximations, so the two land
on the same pixels to within a rounding step (typically ≤ 2, worst cases
documented per test). This is a cross-implementation delta, **not** a
conformance gap: the corpus lossy cases (Part 3) pass within their *declared*
thresholds.

---

## Encoder conformance (produces valid codestreams)

Encoding is non-normative; the criterion is that libjxl decodes what we write.
Every encoder path below is round-tripped through libjxl.

| Encoder path | API | Verified by |
|--------------|-----|-------------|
| Lossless modular (RCT select, palette, LZ77, learned MA trees, clustering) | `JxlEncoder.encode` | `RoundTripTest`, `FfmpegInteropTest` |
| Lossy VarDCT (adaptive block choice, adaptive quant, gaborish inverse + EPF steering) | `VarDctEncoder.encode` | `VarDctEncoderTest`, `FfmpegInteropTest` |
| Lossy XYB-modular | `JxlEncoder.encodeXyb` | `XybModularRoundTripTest`, `FfmpegInteropTest` |
| Transform tools: rectangular, DCT2/4/4×8/8×4, DCT64, AFV | (auto-selected) | `RectangularDctTest`, `SmallTransformEncodeTest`, `Dct64Test` |
| DCT128/256 (opt-in `-Djxl.enc.dct128`/`dct256`) | (flag) | `Dct256Test` |
| Custom 8×8 quant matrix | `encodeWithMatrix` | `CustomMatrixTest` |
| Perceptual & mean-error rate control | `encodeToTarget` | `PerceptualDistortionTest` |
| Progressive (responsive) lossless (Squeeze) | `encodeProgressive` | `ProgressiveTest` |
| Multi-pass / progressive lossy VarDCT | `encodeProgressive` (VarDCT) | `ProgressiveVarDctTest` |
| Patches | `encodeWithPatches` | `PatchEncodeTest`, `PatchDictWriterTest` |
| Photon-noise synthesis | `encodeWithPhotonNoise` | `NoiseEncodeTest` |
| Splines | `encodeWithSplines` | `SplineWriterTest`, `SplineTest` |
| Animation, lossless & lossy, incl. crop/blend compositing & timecodes | `encodeAnimation`, `encodeVarDctAnimation` | `AnimationTest`, `AnimationVarDctTest` |
| Extra channels (all types, int/float, `dim_shift`) | `encode(..., extras)` | `ExtraChannelTest` |
| Streaming (chunked) encode, lossless & lossy | `JxlStreamingEncoder` | `StreamingEncoderTest`, `StreamingVarDctTest` |
| Float samples everywhere | `encodeFloat` / streaming / lossy | `FloatEncodingTest` |
| JPEG → JPEG XL recompression (`jbrd` write side) | `JpegRecompressor.encode` | `JpegRecompressTest` |
| Container metadata (Exif/XMP/`brob`, gain map) | `Container`, `GainMap` | `ContainerMetadataTest` |
| ImageIO plug-ins (read/write/sequence/thumbnails) | `javax.imageio` | `ImageIOPluginTest` |

---

## Codestream levels

The container's declared level (`jxll` box: **5** baseline, **10** extended) is
enforced against decoded content, so a file that claims the baseline but carries
level-10 features (32-bit samples, a CMYK black channel, more than four extra
channels, outsized dimensions) is **rejected** rather than silently returned.
`JxlImage.codestreamLevel` reports the declaration; a bare codestream declares
nothing and is decoded as-is (`LevelTest`, 15 cases).

---

## Known limitations and documented deviations

These are honest scope boundaries, none of which affects a conformance-corpus
result:

1. **Lossy decode is exact-math, not bit-exact to libjxl.** libjxl uses fast
   approximations in its IDCT/filters; this decoder uses exact math. Output
   agrees to within a rounding step (documented per test), and every corpus
   lossy case passes within its declared threshold.
2. **ICC application covers matrix/TRC profiles.** Embedded profiles are
   *reconstructed* byte-exactly (normative). For *display*, matrix/TRC RGB
   profiles are converted to sRGB in pure Java (reproducing lcms2 to within one
   8-bit step); LUT-based, CMYK and greyscale profiles fall back to an sRGB
   reading of the samples. Colour management is a viewer step, not the normative
   decode — the float decode (`decodeToFloats`) stays linear and matches the
   profile-plus-linear conformance references.
3. **Encoder — DCT128/256 are off by default.** Implemented one recursion
   deeper, but the token-count rate estimate cannot price a block that large, so
   they only bloat; enable with `-Djxl.enc.dct128` / `-Djxl.enc.dct256`.
4. **Encoder — Hornuss and the 32×8/8×32 four-block tilings are implemented but
   deliberately unused.** Both round-trip correctly and the decoder reads them;
   the encoder never selects them because the alternatives always win.
5. **Encoder — spline detection is out of scope.** The encoder *carries*
   caller-supplied splines; fitting curves to an arbitrary image is a separate
   detection problem and is not attempted.
6. **Encoder — no bit-exact Butteraugli port.** Rate control uses a
   Butteraugli-*inspired* perceptual proxy; a bit-exact port of libjxl's
   Butteraugli is not implemented (its worth is a number matching the reference,
   and no Butteraugli oracle binary is available to validate against).
7. **JPEG recompression scope.** Baseline and progressive Huffman JPEGs only;
   arithmetic-coded, 12-bit and hierarchical JPEGs are rejected.
8. **`dim_shift` on the streaming encoder is refused** — the row-at-a-time
   encoder has nowhere to put a quarter-width row. It works on every other
   encode path.
9. **ImageIO extra channels are alpha-only** — a `BufferedImage` has nowhere to
   put a channel called "thermal". The direct API carries every extra-channel
   type.

---

## Test-suite summary

`mvn test` — **448 tests run, 0 failures, 0 errors, 10 skipped.** The skips are
each gated on an absent external tool (cjxl/djxl, an env var) or an opt-in flag,
never on a defect. Compliance-relevant classes:

| Class | Tests | Focus |
|-------|------:|-------|
| `OfficialConformanceTest` | 40 | Official conformance corpus (Part 3) |
| `FfmpegInteropTest` | 37 | libjxl cross-validation (Part 4) |
| `FloatEncodingTest` | 80 | Float samples, all paths |
| `StreamingVarDctTest` | 49 | Streaming lossy encode |
| `ProgressiveTest` | 28 | Progressive/responsive lossless |
| `RoundTripTest` | 23 | Lossless encode ↔ decode |
| `ImageIOPluginTest` | 18 | ImageIO integration |
| `LevelTest` | 15 | Codestream level enforcement |
| `JpegRecompressTest` | 14 | JPEG ⇄ JPEG XL recompression |
| `ExtraChannelTest` | 13 | Extra channels, all types |
| `RegionDecodeTest` | 13 | Windowed decode |
| `StreamingEncoderTest` | 13 | Streaming lossless encode |
| `VarDctEncoderTest` | 12 | Lossy VarDCT encode |
| `AnimationTest` / `AnimationVarDctTest` | 9 / 9 | Animation & compositing |
| `FfmpegFeatureCoverageTest` | 7 | Feature coverage vs cjxl |
| `BrotliTest` | 5 | RFC 7932 Brotli decode |
| others (transforms, patches, splines, noise, ICC, CFA, container, matrices) | ~60 | See per-class reports |

---

## Reproducing the conformance results

```
mvn test                                    # full suite (corpus/interop skip if absent)
mvn test -Dtest=OfficialConformanceTest     # the conformance corpus alone
```

The conformance corpus lives in `test-data/conformance/<case>/` (gitignored).
To provide it, clone [libjxl/conformance](https://github.com/libjxl/conformance)
and download the referenced objects from
`https://storage.googleapis.com/jxl-conformance/objects/<sha>`, or point
`JXL_CONFORMANCE_DIR` at an existing checkout. libjxl cross-checks additionally
require `cjxl`/`djxl` (via `JXL_TOOLS` or the `PATH`) and/or ffmpeg built with
`--enable-libjxl`.

---

*This codec is an independent implementation and is not affiliated with or
endorsed by the JPEG committee or the libjxl project. "JPEG XL" and "JPEG" are
used descriptively to identify the standard implemented.*
