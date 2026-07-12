---
name: verify
description: Verify JPEGXL codec changes end-to-end — drive the public API from a scratch Java program and cross-validate bitstreams with ffmpeg's independent libjxl build.
---

# Verifying the JPEG XL codec

The surface is the library boundary: `com.ebremer.cygnus.jpegxl.*` public APIs
(`JxlEncoder`, `JxlStreamingEncoder`, `VarDctEncoder`, `JpegRecompressor`,
`JxlDecoder`, `JpegReconstructor`, the ImageIO plug-ins).

## Handle

```bash
cd JPEGXL && mvn -q -ntp -DskipTests package     # target/classes is the handle
java -cp JPEGXL/target/classes MyProbe.java args # single-file source launch
```

Write probe programs in the session scratchpad, not the repo.

## External judge

ffmpeg on this machine has libjxl (encoder + decoder) and mjpeg — an
independent implementation for cross-validation:

```bash
ffmpeg -y -i file.jxl -f rawvideo -pix_fmt rgb24 out.raw   # decode ours
ffmpeg -y -f rawvideo -pixel_format rgb24 -video_size WxH -i in.raw \
       -frames:v 1 -c:v libjxl -distance 0 out.jxl         # produce theirs
```

Compare raw dumps with `cmp` or a streamed formula check. rgb24/rgba/
gray/rgb48le/gray16le pix_fmts all work. cjxl/djxl are NOT installed;
the conformance corpus needs `JXL_CONFORMANCE` (not set here).

## Memory claims

Test bounded-memory features in a child JVM: `java -Xmx200m -cp ... Probe.java`.
Gotcha found this way: per-group encode transients are ~10-20 MB, so
anything that fans groups out in parallel must bound workers by heap
(see `JxlStreamingEncoder.bandWorkers`), or 32-core machines blow small
heaps. Peak streaming-encode memory = band + compressed sections + 
workers x ~20 MB.

## Conformance corpus

`test-data/conformance/` holds all 40 official testcases (655MB cache);
`OfficialConformanceTest` validates against the corpus's own reference data
(npy + ICC + reconstructed JPEGs) — no djxl needed. Objects download from
`https://storage.googleapis.com/jxl-conformance/objects/<sha>` (shas listed
in each test.json). For fast iteration on failures, write a standalone
sweep (see the CorpusRun/DiffCase pattern: per-case pass/fail line, coarse
error maps, per-phase RMS) instead of driving maven each cycle. References
keep spot colours un-composited (`-Djxl.skipSpot=true`) and XYB-with-ICC
images linear. Three cases have documented small deviations
(KNOWN_DEVIATIONS in the test).

## Gotchas

- `TYPE_BYTE_GRAY` is a linear colorspace: compare `raster.getSample`,
  never `getRGB`, against codec output.
- Encoder output must be byte-identical regardless of row batching and
  worker count — always worth a `cmp` probe.
- Decoding large images needs real heap (full-frame float planes):
  give check programs `-Xmx6g` for 8k x 8k.
