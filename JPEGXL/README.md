# JPEG XL for Java — pure-Java reader and writer

A from-scratch, dependency-free JPEG XL (ISO/IEC 18181) decoder and encoder
for JDK 25, exposed through the standard Java ImageIO API.

- **Pure Java** — no native code, no third-party dependencies (JUnit is
  test-scope only).
- **ImageIO plug-ins** — auto-registered via `META-INF/services`; plain
  `ImageIO.read(...)` and `ImageIO.write(image, "jxl", ...)` work for both
  bare codestreams and ISOBMFF `.jxl` containers. Previews surface as
  thumbnails, animations read frame by frame and write through the sequence API
  (`writeToSequence`) with timing in native image metadata, an explicit
  compression quality below 1.0 selects lossy encoding, `setProgressiveMode`
  selects the responsive layout, and a `TYPE_FLOAT` raster is written (and read
  back) as a floating-point image.
- **Direct APIs** — `JxlDecoder` returns per-channel sample planes (integer
  or float) with full metadata; `JxlEncoder` writes lossless codestreams
  (integer or floating-point samples, optionally progressive) and lossy
  XYB-modular ones (`encodeXyb`), and `VarDctEncoder` writes lossy VarDCT, for
  non-ImageIO use.
- **Streaming input** — decoding from an `ImageInputStream` reads section
  ranges on demand instead of buffering the whole file.
- **Streaming output** — `JxlStreamingEncoder` takes rows top to bottom and
  never holds the image, lossless or lossy, integer or float: gigapixel encodes
  run in a heap sized by the image's width, not its area.
- **Validated against libjxl** — the test suite cross-checks against the
  reference implementation (cjxl/djxl and ffmpeg's libjxl) plus the official
  conformance test corpus: lossless paths bit-exactly (including 32-bit
  floats), lossy paths within a small tolerance (this decoder uses exact math
  where libjxl uses fast approximations).

## Usage

```java
// Through ImageIO (plugin discovered automatically from the classpath)
BufferedImage image = ImageIO.read(new File("photo.jxl"));
ImageIO.write(image, "jxl", new File("out.jxl"));   // lossless

// Lossy writing via the standard compression-quality knob
ImageWriter writer = ImageIO.getImageWritersByFormatName("jxl").next();
ImageWriteParam param = writer.getDefaultWriteParam();
param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
param.setCompressionType("lossy");
param.setCompressionQuality(0.9f);

// Direct API: raw channel planes plus metadata
byte[] bytes = Files.readAllBytes(Path.of("photo.jxl"));
JxlImage decoded = JxlDecoder.decode(bytes);
int[] red = decoded.frames.get(0).channels[0];      // row-major, oriented

// Direct encoding of sample planes (colour channels first, then alpha)
byte[] jxl = JxlEncoder.encode(planes, width, height, /*bits*/ 8,
        /*grey*/ false, /*alpha*/ true, /*alphaAssociated*/ false);
```

`reader.getWidth(0)`/`getHeight(0)` parse only the headers; pixel decoding is
deferred until `read(...)`.

Windowed reads decode only the 256×256 groups covering the requested
rectangle (plus a filter margin), so a small read of a large image costs
group-area work, not image-area work:

```java
// ImageIO: the standard source-region and tile APIs are group-selective
ImageReader reader = ImageIO.getImageReadersByFormatName("jxl").next();
reader.setInput(ImageIO.createImageInputStream(new File("big.jxl")));
ImageReadParam p = reader.getDefaultReadParam();
p.setSourceRegion(new Rectangle(4096, 4096, 512, 512));
BufferedImage window = reader.read(0, p);
BufferedImage tile = reader.readTile(0, 3, 7);      // tiles = codestream groups

// Direct API: region in oriented image coordinates
JxlImage part = JxlDecoder.decode(bytes, new Rectangle(4096, 4096, 512, 512));
```

Streams whose reconstruction is inherently non-local (a frame-global squeeze
transform, delta-palette entries, patches copied from region-limited
snapshots) transparently fall back to a full decode and return the same
cropped result; reference, LF and preview frames always decode whole.

## What is implemented

**Decoder** — both coding modes of ISO/IEC 18181-1:

- **VarDCT (lossy)**: all 27 transform types (DCT2 through DCT256, AFV,
  Hornuss and the rectangular DCTs), dequantisation with all weight
  encodings, HF context modelling, chroma-from-luma, adaptive LF smoothing,
  gaborish and the edge-preserving filter, and the XYB to RGB pipeline with
  all enumerated transfer functions (sRGB, linear, gamma, BT.709, PQ, DCI,
  HLG).
- **Modular (lossless and XYB lossy)**: the full entropy layer (prefix codes,
  rANS with the normative alias mapping, hybrid integers, LZ77, context
  clustering), meta-adaptive trees with every property and predictor
  (including the self-correcting weighted predictor with libjxl's normative
  32-bit error arithmetic), and the RCT, palette (including implicit and
  delta palettes) and squeeze transforms.
- **Sample types**: integers from 1 to 31 bits and floating-point samples
  (binary32/16 and custom float layouts); float images decode bit-exactly
  and surface as `TYPE_FLOAT` rasters in ImageIO; integers wider than 24
  bits round-trip exactly through a dedicated integer canvas path.
- **Region (windowed) decoding**: `JxlDecoder.decode(bytes, rect)` and the
  ImageIO source-region/tile APIs entropy-decode only the LF and pass groups
  covering the rectangle, with a 16-pixel margin so gaborish/EPF, chroma
  upsampling and extra-channel shifts are bit-identical to a full decode;
  non-local features fall back to decoding every group automatically.
- **Colour management**: embedded ICC profiles are reconstructed from the
  encoded ICC stream byte-exactly, and *applied* for display through the ImageIO
  reader. A modular image whose profile is a matrix/TRC RGB profile — the kind a
  photograph carries (sRGB, Display P3, Adobe RGB, a scanner's) — has its device
  samples mapped to sRGB through the profile (tone curves, colorant matrix, D50
  connection space); `IccColorTransform` does it in pure Java and reproduces
  lcms2, the engine libjxl uses, to within a single eight-bit step. Profiles a
  matrix/TRC transform cannot render exactly (LUT-based, CMYK, greyscale) fall
  back to an sRGB reading of the samples; `-Djxl.skipIcc` turns the transform off.
  An XYB image with an embedded profile is coded in linear light, so its eight-bit
  decode applies the sRGB transfer to gamma-encode it; otherwise the picture comes
  out far too dark. The float decode (`decodeToFloats`) stays linear, matching the
  profile-plus-linear conformance references — colour management is a viewer step,
  not the normative decode. A CMYK image (a black extra channel) is composited to
  RGB for display through ImageIO — the black multiplied back into the colour
  planes, so a scanned document's text shows rather than dropping out to white
  paper; the raw four channels remain available (`JxlDecoder.decode`, or
  `-Djxl.skipCmyk`). A single-channel image tagged with a colour-filter-array
  channel is a Bayer mosaic, and the reader demosaics it to RGB (`CfaDemosaic`,
  bilinear, RGGB assumed — the format records no pattern; `-Djxl.skipCfa` leaves
  the raw mosaic).
- **YCbCr frames**: recompressed-JPEG streams (`cjxl in.jpg`) decode to
  pixels, including 4:2:0/4:2:2 chroma subsampling with the JPEG-style
  triangle upsampling.
- **Byte-exact JPEG reconstruction** (`jbrd`): files produced with
  `cjxl --lossless_jpeg=1` rebuild the original JPEG bit for bit —
  baseline and progressive scans, restart intervals, recorded padding
  bits, grayscale/4:4:4/4:2:2/4:2:0, ICC/Exif/XMP markers. Includes a
  self-contained Brotli (RFC 7932) decoder with the static dictionary
  and all 121 word transforms.
  `JpegReconstructor.reconstruct(fileBytes)` returns the JPEG bytes;
  `JpegReconstructor.hasJpegData(fileBytes)` probes for the box.
- **Progressive decoding**: multi-pass frames (AC refinement passes and
  squeeze-based modular passes) and progressive DC via LF frames.
- **Restoration and synthesis features**: patches (with reference frames and
  all patch blend modes), splines, photon noise synthesis, and spot-colour
  extra channels rendered at output time.
- **Geometry**: upsampling (2x/4x/8x, default and custom weights),
  independent extra-channel upsampling and `dim_shift`, cropped frames
  composited onto the canvas, per-channel frame blending (replace, add,
  blend, mul-add, multiply), EXIF orientations 1–8, group-split images of
  any size, permuted TOCs.
- Bare codestreams and ISOBMFF containers, animations (each visible frame is
  an ImageIO image index, with durations in the native metadata), preview
  frames (ImageIO thumbnails), alpha and other extra channels.
- **Codestream level enforcement**: a container's declared level (the `jxll`
  box; 5 baseline, 10 extended) is checked against the decoded content, so a
  file that claims the baseline but carries level-10 features — 32-bit samples,
  a CMYK black channel, more than four extra channels, outsized dimensions — is
  rejected rather than quietly returned. `JxlImage.codestreamLevel` reports the
  declaration; a bare codestream declares nothing and is decoded as-is.
- **Parallel decoding**: group sections, reconstruction, restoration filters
  and colour conversion fan out across cores.

**Encoder**:

- **Lossless modular mode**: cost-based reversible colour transform
  selection (RCT types 0–6), global palette detection for few-colour images,
  run-length LZ77 copies, learned (content-adaptive) MA trees — greedy
  entropy-driven splits over the modular properties, including the
  previous-channel ones, with each leaf choosing its own predictor from all
  fourteen the format defines — plus per-group local trees where a private
  code beats the global one, histogram clustering, histogram-optimised prefix
  codes (package-merge, RFC 7932 simple and complex descriptions), optional
  embedded previews. On the conformance photographs the output runs a few
  percent over `cjxl -e7` (`bike` +5.3%, `bicycles` +3.4%, `cafe` −1.5%).
- **Patches** (`encodeWithPatches`): a screenshot's repeated tiles — the same
  glyph, button or icon stamped over and over — are coded once into a
  reference-only frame and REPLACE-stamped at each site, the main frame carrying
  only the flattened background (~12% smaller on a synthetic screenshot, libjxl
  reads it back exactly). Lossless, and it never grows an image: the plain encode
  is produced alongside and the smaller returned.
- **Lossy (VarDCT) mode**: XYB colour, square 8×8/16×16/32×32, rectangular
  8×16/16×8/32×16/16×32, and the small DCT2/DCT4/DCT4×8/DCT8×4 blocks chosen by a
  rate estimate — the 32×32 taking a smooth region (a sky, a road) in one block,
  the rectangular blocks content that runs one way (a horizon, a wall's edge), the
  small transforms the piecewise-flat blocks a plain DCT8 rings on (a hard edge, a
  text stroke), about
  10% smaller on a directional photograph (`bike`) — activity-masked adaptive
  quantisation, default quantisation tables with a distance-controlled quantiser
  (`VarDctEncoder.encode(rgb, w, h, distance)`), and an iterative
  rate-control mode (`encodeToTarget`) that refines the quantiser against
  the achieved error; the ImageIO quality knob uses the latter.
- **Lossy XYB-modular mode**: the second lossy path, coding the same XYB colour
  through the modular coder instead of the DCT — `JxlEncoder.encodeXyb(rgb, w, h,
  bits, distance)`, or the `modular-lossy` ImageIO compression type. Each XYB
  channel is divided by a DC step and coded as it stands (Y, X, and B carried as
  `B − Y`, the steps written in `LfChannelDequantization`); the frame is an
  ordinary modular one with `xyb_encoded` set. libjxl decodes our output and
  agrees with our inverse XYB to within rounding (worst 1). With no DCT it codes
  a photograph far less tightly than VarDCT — it is the tool for flat, synthetic
  or near-lossless material, and VarDCT stays the default lossy path.
- **Extra channels**: as many as the image needs, of every type the format
  defines — alpha, depth, selection mask, CMYK black, CFA, thermal, spot
  colour, and the catch-all — each with its own name, its own bit depth
  (integer or float, independent of the colour planes) and its own resolution.
  Pass a `List<ExtraChannelInfo>` and one plane per entry:

  ```java
  var extras = List.of(
      ExtraChannelInfo.alpha(BitDepth.of(8), false),
      ExtraChannelInfo.of(ExtraChannelInfo.TYPE_DEPTH, BitDepth.of(16), "depth"),
      ExtraChannelInfo.spot(BitDepth.of(8), "pantone-032", 0.94f, 0.28f, 0.32f, 1f));
  byte[] jxl = JxlEncoder.encode(planes, w, h, 8, false, extras);   // lossless
  byte[] lossy = VarDctEncoder.encode(planes, w, h, 8, false, extras, 1.0f);
  ```

  In the lossy path only the colour is quantised; the extra channels ride
  beside it losslessly, so a lossy render still has an exact depth buffer.
  A `dimShift` stores a channel at half, a quarter or an eighth of the size and
  lets the decoder stretch it back out. Works lossless, lossy, progressive,
  float and streamed — except `dimShift`, which the row-at-a-time streaming
  encoder refuses, having nowhere to put a quarter-width row. ImageIO remains
  alpha-only: a `BufferedImage` has nowhere to put a channel called "thermal".
- **Both restoration filters are accounted for on encode.** The decoder blurs
  every frame with gaborish and then runs an edge-preserving filter over it, and
  an encoder that ignores either is aiming at the wrong target. Gaborish is
  linear, so it is *inverted*: the input is pre-sharpened by a relaxed
  fixed-point solve until the decoder's blur lands back on the source. EPF is a
  bilateral filter and cannot be inverted, so instead it is *steered* — the
  encoder reconstructs what the decoder will see, runs the decoder's own filters
  over it, and uses the format's per-block sharpness field to ask for the filter
  only on the blocks it measurably helps. Together these are what let a fine
  distance actually buy quality: a greyscale image at distance 0.3 comes back
  bit-exact.
- **Photon-noise synthesis**: `VarDctEncoder.encodeWithPhotonNoise(..., iso)`
  writes a noise model (the same eight-point table `cjxl --photon_noise_iso`
  computes) and flags the frame, so the decoder synthesizes film grain rather
  than the encoder coding it — eighty bits that turn a plasticky smooth lossy
  render grainy again. The synthesis is normative, so libjxl reproduces it pixel
  for pixel.
- **Animation**: `JxlEncoder.encodeAnimation` writes a sequence of frames with a
  timebase (ticks per second, loop count) and, per frame, a duration and a way
  of joining the canvas — a whole picture that replaces (`AnimationFrame.full`),
  a rectangle that updates in place with the rest inherited
  (`AnimationFrame.patch`), or a whole frame laid over the canvas through its
  alpha (`AnimationFrame.blended`). Patches and blends use the reference-frame
  machinery, which the encoder manages itself. Lossless, any extra channels, and
  writable through the standard ImageIO sequence API (`writeToSequence`) as well.
- **Streaming (chunked) encoding, lossless or lossy**: `JxlStreamingEncoder`
  consumes rows top to bottom and compresses each 256-row band of groups as it
  completes, so peak memory is one band plus the compressed sections — the image
  never has to fit in memory (nor under the 2³¹ samples-per-plane array limit).
  A 32768×32768 RGB image (1.07 gigapixels, 12.9 GB of samples) encodes lossily
  in 44 s through a 768 MB heap, most of which is the 262 MB output being held
  for the TOC. Working memory scales with width, not height.
  - *Lossless* codes each group as a self-contained section with its own RCT,
    predictors, learned tree and entropy code; files run a few percent larger
    than `JxlEncoder.encode`'s.
  - *Lossy* runs the VarDCT path band by band. The two steps that ordinarily
    need the whole image are answered without it: masking's activity reference
    is accumulated as bands arrive (exact for a one-band image), and the frame's
    coefficient code becomes a small pool of them via `num_hf_presets`, claimed
    per band, so blank bands and dense bands are not forced to share an alphabet.
    The pool more than pays for the drifting reference: multi-band files come out
    **smaller** than `VarDctEncoder.encode`'s at equal fidelity. An image no taller
    than one band is byte-identical to the whole-image encoder.
  - *Rate control* (`targetingQuality`) sets each band's quantiser by encoding and
    decoding the busiest group in it, so quality tracks the requested distance
    without a whole-image pass. On slide-like content it beats whole-image
    `encodeToTarget` outright — that loop drives the error *averaged over the
    frame* to the target, and when most of the frame is blank, the average is the
    blank.
- **Floating-point samples**, everywhere the integer path goes: whole-image
  (`JxlEncoder.encodeFloat`), streaming (`JxlStreamingEncoder.floatSamples`),
  lossy (`VarDctEncoder.encodeFloat`), and through ImageIO on a `TYPE_FLOAT`
  raster. IEEE binary32, binary16, or any of the format's custom layouts (2–8
  exponent bits).
  - *Losslessly*, the format carries a float as its bit pattern coded as an
    integer, so this is the ordinary lossless path with the samples
    reinterpreted: bit-exact on negative zero, subnormals, infinities and NaN,
    and a narrow layout refuses a sample it cannot hold rather than rounding it.
    Float images are less compressible than the same picture as integers —
    across a power of two the bit pattern jumps, and the predictors see a cliff
    where the picture is smooth. That is the format's bargain, not this
    encoder's.
  - *Lossily*, the float goes straight into the XYB conversion instead of being
    quantised onto an integer grid first. On a field running from −2 to 2 that
    is **70× more accurate** than quantising first, which has nowhere to put a
    negative sample; on one running to 400 it holds the relative error at 0.2%.
    Nothing clamps to the display range in either direction.
- **Progressive (responsive) lossless**: `JxlEncoder.encodeProgressive` applies
  the Squeeze transform — the channels become a small image of the picture
  followed by the detail that doubles it, repeatedly — and cuts the frame into
  passes, coarsest first. A fifth of the bytes then decodes to the **whole**
  image at low resolution, where a fifth of an ordinary file decodes to a fifth
  of the image and leaves the rest black. Read a prefix with
  `JxlDecoder.decodePartial`, or select the layout through ImageIO with
  `ImageWriteParam.setProgressiveMode`. Squeeze buys the layout, not the ratio:
  files run a few percent larger.
- **JPEG → JPEG XL recompression**: `JpegRecompressor.encode(jpegBytes)`
  losslessly repacks a JPEG as a JPEG XL container with reconstruction data
  (`jbrd`), the write-side twin of `JpegReconstructor` — the quantised DCT
  coefficients become a `doYCbCr` VarDCT frame with the JPEG's own quant
  tables, and `reconstruct` (or `djxl`) rebuilds the original file byte for
  byte while the `.jxl` still decodes to pixels normally. Baseline and
  progressive Huffman JPEGs (grey/YCbCr/RGB, sampling factors 1–2, restart
  intervals, reset points, extra zero runs); APP/COM markers are carried
  verbatim. Arithmetic-coded, 12-bit and hierarchical JPEGs are rejected.

```java
// lossless
try (var enc = new JxlStreamingEncoder(outputStream, width, height, 8,
        /*grey*/ false, /*alpha*/ false, /*alphaAssociated*/ false)) {
    while (moreRows) {
        enc.writeRows(rowPlanes, rowCount);  // any batch size, top to bottom
    }
}   // close() finishes the codestream

// lossy: the same, with a Butteraugli-style distance
try (var enc = new JxlStreamingEncoder(outputStream, width, height, 8,
        false, false, false, /*distance*/ 1.0f)) { ... }

// lossy, with quality measured per band rather than assumed
try (var enc = JxlStreamingEncoder.targetingQuality(outputStream, width, height, 8,
        false, false, false, /*distance*/ 1.0f)) { ... }
```

JPEGs recompress losslessly in both directions:

```java
byte[] jxl = JpegRecompressor.encode(jpegBytes);   // ~20% smaller, decodable
byte[] jpeg = JpegReconstructor.reconstruct(jxl);  // the original, byte-exact
```
- 256×256 groups with a proper TOC, so large images decode in bounded memory
  and are parallelisable by conforming decoders.
- Greyscale/RGB, 1–31 bits, any number of extra channels (lossless).

## Building

```
mvn verify
```

JDK 25 and Maven are the only requirements. Cross-validation tests activate
automatically when tooling is present and are skipped otherwise:

- ffmpeg with `--enable-libjxl` on the `PATH`;
- cjxl/djxl (e.g. a libjxl release) via the `JXL_TOOLS` environment variable
  or the `PATH`;
- the testcases of [libjxl/conformance](https://github.com/libjxl/conformance)
  as flat `<name>.jxl` files in a directory named by `JXL_CONFORMANCE`.

Run with `-Djxl.debug=true` to trace stream structure while decoding.
