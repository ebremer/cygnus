# JPEG XL for Java — pure-Java reader and writer

A from-scratch, dependency-free JPEG XL (ISO/IEC 18181) decoder and encoder
for JDK 25, exposed through the standard Java ImageIO API.

- **Pure Java** — no native code, no third-party dependencies (JUnit is
  test-scope only).
- **ImageIO plug-ins** — auto-registered via `META-INF/services`; plain
  `ImageIO.read(...)` and `ImageIO.write(image, "jxl", ...)` work for both
  bare codestreams and ISOBMFF `.jxl` containers. Previews surface as
  thumbnails, animation timing as native image metadata, and an explicit
  compression quality below 1.0 selects lossy encoding.
- **Direct APIs** — `JxlDecoder` returns per-channel sample planes (integer
  or float) with full metadata; `JxlEncoder` writes lossless codestreams and
  `VarDctEncoder` basic lossy ones, for non-ImageIO use.
- **Streaming input** — decoding from an `ImageInputStream` reads section
  ranges on demand instead of buffering the whole file.
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
  encoded ICC stream and applied through `java.awt.color` when possible.
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
- **Parallel decoding**: group sections, reconstruction, restoration filters
  and colour conversion fan out across cores.

**Encoder**:

- **Lossless modular mode**: cost-based reversible colour transform
  selection (RCT types 0–6), global palette detection for few-colour images,
  per-channel choice between the gradient and the self-correcting weighted
  predictor, run-length LZ77 copies, learned (content-adaptive) MA trees —
  greedy entropy-driven splits over the modular properties, including the
  previous-channel ones — plus per-group local trees where a private code
  beats the global one, histogram clustering, histogram-optimised prefix
  codes (package-merge, RFC 7932 simple and complex descriptions), optional
  embedded previews. On photographic content the output lands between
  `cjxl -e2` and `-e4`.
- **Lossy (VarDCT) mode**: XYB colour, 8×8 and 16×16 DCT blocks chosen by a
  rate estimate, activity-masked adaptive quantisation, default quantisation
  tables with a distance-controlled quantiser
  (`VarDctEncoder.encode(rgb, w, h, distance)`), and an iterative
  rate-control mode (`encodeToTarget`) that refines the quantiser against
  the achieved error; the ImageIO quality knob uses the latter.
- **Streaming (chunked) lossless encoding**: `JxlStreamingEncoder` consumes
  rows top to bottom and compresses each 256-row band of groups as it
  completes, so peak memory is one band plus the compressed sections — the
  image never has to fit in memory (nor under the 2³¹ samples-per-plane
  array limit). Each group is a self-contained section with its own RCT,
  predictors, learned tree and entropy code; files are typically a few
  percent larger than `JxlEncoder.encode`'s.
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
try (var enc = new JxlStreamingEncoder(outputStream, width, height, 8,
        /*grey*/ false, /*alpha*/ false, /*alphaAssociated*/ false)) {
    while (moreRows) {
        enc.writeRows(rowPlanes, rowCount);  // any batch size, top to bottom
    }
}   // close() finishes the codestream
```

JPEGs recompress losslessly in both directions:

```java
byte[] jxl = JpegRecompressor.encode(jpegBytes);   // ~20% smaller, decodable
byte[] jpeg = JpegReconstructor.reconstruct(jxl);  // the original, byte-exact
```
- 256×256 groups with a proper TOC, so large images decode in bounded memory
  and are parallelisable by conforming decoders.
- Greyscale/RGB, 1–31 bits, optional alpha (lossless).

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
