# Cygnus — pure-Java JPEG 2000 codec

A from-scratch, dependency-free JPEG 2000 decoder and encoder for JDK 25,
exposed through the standard Java ImageIO API: Part 1 (ISO/IEC 15444-1 /
ITU-T T.800) plus High-Throughput JPEG 2000 decoding (Part 15 / ITU-T T.814,
`.jph`/`.jhc`).

- **Pure Java** — no native code, no third-party dependencies (JUnit is
  test-scope only).
- **ImageIO plug-ins** — auto-registered via `META-INF/services`; plain
  `ImageIO.read(...)` and `ImageIO.write(image, "jp2", ...)` work for `.jp2`
  and raw codestreams (`.j2k`/`.j2c`/`.jpc`).
- **Direct APIs** — `Jpeg2000Decoder` returns per-channel integer sample
  planes with full colour/palette/channel metadata; `Jpeg2000Encoder` takes
  such planes and writes a codestream, for non-ImageIO use.

## Usage

```java
// Through ImageIO (plugin discovered automatically from the classpath)
BufferedImage image = ImageIO.read(new File("photo.jp2"));

// Direct API: raw channel planes, subsampling, colourspace, alpha info
byte[] bytes = Files.readAllBytes(Path.of("photo.jp2"));
Jpeg2000Decoder decoder = new Jpeg2000Decoder();
DecodedImage decoded = decoder.decode(bytes);
int[] gray = decoded.samples[0];        // row-major, DC-shifted, clamped
```

Region and subsampled reads use the standard `ImageReadParam`; on tiled
codestreams only the tiles intersecting the source region are
entropy-decoded, so windowed reads of large tiled images cost tile-area
work, not image-area work:

```java
ImageReader reader = ImageIO.getImageReadersByFormatName("jpeg2000").next();
reader.setInput(ImageIO.createImageInputStream(new File("big.jp2")));
int w = reader.getWidth(0);              // header-only, no decoding
ImageReadParam p = reader.getDefaultReadParam();
p.setSourceRegion(new Rectangle(1024, 1024, 512, 512));
p.setSourceSubsampling(2, 2, 0, 0);
BufferedImage window = reader.read(0, p); // decodes intersecting tiles only
```

The codestream tile grid is exposed through the standard ImageIO tile API
(`isImageTiled`, `getTileWidth`/`getTileHeight`, `getTileGridXOffset`/`YOffset`,
`readTile(0, tileX, tileY)`), and reduced-resolution reads discard DWT
levels - essentially free downscaling, since discarded resolutions are never
entropy-decoded:

```java
CygnusImageReadParam p = (CygnusImageReadParam) reader.getDefaultReadParam();
p.setResolutionReduction(3);              // ~1/8 scale
p.setSourceRegion(new Rectangle(0, 0, 256, 256));  // reduced coordinates
BufferedImage thumb = reader.read(0, p);
```

The direct API mirrors all of it:

```java
Jpeg2000Decoder decoder = new Jpeg2000Decoder();
decoder.open(bytes);
DecodedImage shape = decoder.shape();                     // headers only
DecodedImage roi = decoder.decode(new Rectangle(x, y, w, h));
DecodedImage overview = decoder.decode(decoder.maxReduction());
```

## Writing

`ImageIO.write(image, "jp2", output)` produces a lossless JP2 (reversible
5/3 wavelet with RCT): reading it back reproduces the samples bit-exactly.
All standard `BufferedImage` types are accepted — gray and RGB(A) in 8 and
16 bit, indexed images (expanded through their palette), plus bare `Raster`s
via `canWriteRasters`. Alpha is recorded with a `cdef` box, non-sRGB ICC
colour spaces are embedded, and source region/subsampling/band selection on
the write param are honored.

`CygnusImageWriteParam` exposes the JPEG 2000 knobs: the `"Lossy"`
compression type (irreversible 9/7 wavelet with ICT; the compression quality
scales the quantization step sizes — 1.0 near-lossless, lower = smaller
files), tiling, decomposition levels, code-block size, SOP/EPH markers, and
raw-codestream output:

```java
ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg2000").next();
CygnusImageWriteParam p = (CygnusImageWriteParam) writer.getDefaultWriteParam();
p.setCompressionType("Lossy");
p.setCompressionQuality(0.8f);
p.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
p.setTiling(1024, 1024, 0, 0);            // tiled: bounded encode memory,
                                          // selective reads on decode
p.setWriteCodeStreamOnly(true);           // raw .j2k instead of JP2
writer.setOutput(ImageIO.createImageOutputStream(new File("out.j2k")));
writer.write(null, new IIOImage(image, null, null), p);
```

The direct encoder API works on component planes (subsampling, custom
depths) and streams tiles as they are encoded:

```java
Jpeg2000Encoder.Params ep = new Jpeg2000Encoder.Params();
ep.width = w;
ep.height = h;
ep.precision = new int[] {8, 8, 8};
new Jpeg2000Encoder(ep).encode(planes, outputStream);
```

Build: `mvn package` (JDK 25, Maven 3.9+).

## Feature coverage (T.800 Part 1)

| Area | Support |
|---|---|
| Codestream syntax | SIZ, COD/COC, QCD/QCC, RGN (max-shift ROI), POC, PPM/PPT, SOP/EPH, TLM/PLM/PLT/CRG/COM (skipped hints), multiple tiles and tile-parts, `Psot = 0`, truncated-stream tolerance |
| Progressions | LRCP, RLCP, RPCL, PCRL, CPRL; progression-order changes (POC) with packet-once semantics |
| Tier-2 | Tag trees, inclusion/zero-bit-planes, Lblock code, codeword segments, packed packet headers (PPM/PPT), precinct partitions, quality layers |
| Tier-1 (EBCOT) | MQ decoder (Annex C), all three passes, run-length + UNI coding, selective arithmetic bypass (raw segments), context reset, terminate-all, vertically causal contexts, segmentation symbols |
| Quantization | Reversible (exponents only), scalar derived, scalar expounded; guard bits; half-bit reconstruction for truncated data |
| Wavelets | 5/3 reversible (exact integer) and 9/7 irreversible, arbitrary decomposition levels, whole-sample symmetric extension, correct parity for odd grid origins |
| Component | RCT and ICT inverse transforms, DC level shift, component subsampling (`XRsiz`/`YRsiz`), signed and unsigned samples, up to 31 magnitude bit-planes |
| JP2 container | Signature/ftyp walk, `ihdr`, `bpcc`, `colr` (enumerated sRGB/grey/sYCC and restricted ICC), palette (`pclr` + `cmap`), channel definitions (`cdef`, alpha/premultiplied), XL boxes |
| Selective access | Header-only structure queries; region decoding limited to intersecting tiles; ImageIO tile API (`readTile`, tile grid geometry) |
| Reduced resolution | Decode at 1/2^d scale by discarding DWT levels (`decode(region, d)`, `CygnusImageReadParam.setResolutionReduction`); discarded resolutions are never entropy-decoded; composes with regions/tiles |
| Streaming input | Seekable streams are decoded without buffering: headers indexed once, tile bodies read on demand per decoded tile; inputs beyond 2 GiB supported; a windowed read touches only the bytes of the tiles it needs |
| HTJ2K (Part 15) | HT block coder (Cleanup MEL/VLC/MagSgn + SigProp + MagRef), signalled per tile-component (code-block style 0x40); JPH/JHC files; stripe-causal mode; CAP/CPF markers; composes with everything above (tiles, regions, reduced resolution, streaming) |
| Parallel decoding | Code-block entropy decoding and per-component wavelet synthesis fan out onto the common ForkJoin pool (`setParallelism`, default all cores; output bit-identical at any setting); ~14x measured on a 32-core machine |
| Encoding | `Jpeg2000Encoder` + `CygnusImageWriter`: lossless (5/3 + RCT, bit-exact round-trips) and lossy (9/7 + ICT, scalar-expounded quantization scaled by the compression quality); tiling, subsampled components, depths to 16 bit via ImageIO (26 direct), alpha `cdef`, ICC embedding, SOP/EPH, multi-precinct images, parallel Tier-1, tile-streamed output with patched `jp2c` length; single quality layer, LRCP |
| Tuned hot loops | Table-driven T1 contexts (incremental neighbor bytes + LUTs), boundary-peeled DWT lifting, and a raster fill that writes rows directly into the image buffer |
| ImageIO metadata & params | Standard `javax_imageio_1.0` metadata (chroma/data/dimension incl. `res ` box resolution/compression/transparency) plus a native tree with SIZ/COD/QCD and container details; source/destination band selection; hardened against hostile input (memory budget via `setMemoryLimit`, structural caps, mutation-fuzzed) |

## Validation

Three independent lines of evidence, all run by `mvn test`:

0. **ISO/IEC 15444-4 conformance** - the official decoder conformance
   exercises `p0_01..p0_16` and `p1_01..p1_07` are decoded and compared
   against the ISO reference images within the standard's maximum-peak-error
   and MSE limits. **Class 1** (full resolution, every component): all 23
   pass, most bit-exact, including `p0_08` at its ResFactor-1 reference.
   **Class 0** (reduced resolution, first component, minimal-decoder rules):
   19 of 23 pass; four are skipped because their references are not
   producible by DWT-level discard (documented in the test) - for those,
   reduced decoding is instead verified bit-identical against ffmpeg's
   independent `-lowres` decoder. The nine `fileN.jp2` container exercises
   decode sanely. Test data is fetched once from `uclouvain/openjpeg-data`
   into `test-data/` and cached; the tests skip when offline.

1. **Self round-trips** — the production encoder and a separate test-scope
   reversible encoder (forward 5/3 DWT, mirror EBCOT coder, MQ encoder with
   proper SETBITS termination, packet writer) produce streams the decoder
   must reproduce **bit-exactly**: sizes from 1×1 up, odd origins, tiled
   grids with offsets, RCT, subsampled components, 8/12/16-bit depths,
   SOP/EPH, and a 40000-pixel-wide two-precinct image. Encoded bytes are
   asserted identical at any parallelism setting.
2. **Cross-validation against ffmpeg** (skipped if ffmpeg is absent) — files
   encoded by ffmpeg's independent JPEG 2000 implementation are decoded and
   compared against ffmpeg's own decoder output: bit-exact for every
   reversible case (gray, RGB-in-JP2, YUV 4:2:0 subsampling, 64×64 tiles,
   SOP+EPH, RLCP/RPCL/PCRL/CPRL, 16-bit, RGBA + `cdef` alpha, multi-layer),
   tolerance ±3 for the integer-vs-float 9/7 case. In the other direction,
   files written by the ImageIO writer are decoded by ffmpeg and compared
   against the source: bit-exact for every lossless case (gray, RGB JP2,
   RGBA + alpha, 16-bit, tiles, SOP/EPH, 16×32 code-blocks), tolerance ±4
   for the 9/7 lossy case.

Unit tests additionally pin the MQ coder (including multi-segment context
retention), tag-tree protocol, and both wavelet kernels (5/3 identity for all
lengths/parities; 9/7 reconstruction and DC-gain checks). Region decoding is
verified to (a) reproduce the corresponding window of a full decode exactly -
including across tile boundaries, RCT, offset origins and subsampled
components - and (b) touch only the tiles that intersect the region.

**HTJ2K** is validated against the OpenJPH test corpus
(`aous72/jp2k_test_codestreams`): ~70 HT codestreams covering code-blocks
from 4x4 to 1024x4, tiled images, all five progression orders, gray/RGB/
YUV 4:2:0, 8- and 16-bit depths and both wavelets, each checked against the
corpus' per-component MSE/peak limits - every reversible case decodes
bit-exactly - plus OpenJPEG's HT files including the stripe-causal variant.
The HT block decoder is a Java port of the BSD-2-licensed reference decoder
by Aous Naman (ht_dec.c, as distributed with OpenJPEG).

## Design

```
com.ebremer.cygnus.jpeg2000
├── codestream   Marker parsing → Codestream model (headers, tile-parts, precedence)
├── jp2          JP2 box walk (ihdr/colr/pclr/cmap/cdef, codestream location)
│                + Jp2Writer (signature/ftyp/jp2h boxes)
├── t2           Tile geometry (resolutions/bands/precincts/code-blocks),
│                tag trees, packet header decoding, progression iteration;
│                encoder counterparts (PacketBitWriter, TagTreeEncoder)
├── t1           MQ arithmetic coder pair + EBCOT block decoder/encoder
│                (Annex C/D), HTJ2K block decoder (T.814: MEL, VLC, MagSgn,
│                SigProp, MagRef)
├── wavelet      Inverse + forward DWT, 5/3 int + 9/7 float lifting (Annex F)
├── decoder      Pipeline orchestration: T2 → T1 → dequant → IDWT → MCT →
│                level shift → palette/cdef channels (DecodedImage)
├── encoder      Reverse pipeline: level shift → MCT → DWT → quantize → T1 →
│                packets/markers (Jpeg2000Encoder)
└── imageio      ImageReaderSpi + ImageReader (regions, subsampling, sYCC,
                 ICC, alpha, readRaster) and ImageWriterSpi + ImageWriter
                 (lossless/lossy, tiling, raw codestream or JP2)
```

Decoding and encoding are tile-at-a-time; per-tile memory is proportional
to tile area.

## Limitations

- The encoder writes single-layer LRCP codestreams with maximal precincts
  and one tile-part per tile; there is no rate targeting (lossy size is
  steered by the quality's step-size scaling, not a byte budget), no
  bypass/termall/causal code-block styles, and no HTJ2K encoding. Samples
  are written unsigned; image metadata is not written.
- Untiled codestreams have a single tile, so region reads of them still
  decode (and read) the whole image.
- Streams set with `seekForwardOnly` cannot be accessed randomly and are
  buffered in memory (2 GiB cap applies only to that fallback).
- Parts 1 and 15: JPX/Part 2 extensions (extended MCT, arbitrary wavelets,
  multiple codestreams) are out of scope; unknown boxes/markers are skipped
  with warnings where the spec allows. HT restrictions: HTONLY-style
  code-blocks (up to 3 passes, one HT set; placeholder passes and
  MIXED-mode blocks are rejected with a warning), no ROI in HT blocks.
- Component precisions above 16 bits work up to 31 total magnitude
  bit-planes (38-bit extremes are rejected); `BufferedImage` output is
  16-bit-limited - use `readRaster` (TYPE_INT samples) or the direct API
  for deeper data.
- Signed components are offset into their unsigned display range for
  `BufferedImage` output (raw values available via the direct API).
- Tiles are decoded one at a time (parallelism is within a tile), keeping
  peak memory proportional to tile area.

## License / status

Version 0.1.0-SNAPSHOT (the version lives in the pom; this line follows it).
Written as a clean-room implementation from the ITU-T T.800 specification text.
