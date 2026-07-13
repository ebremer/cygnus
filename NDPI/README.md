# Cygnus — Hamamatsu NDPI reader

A read-only Java ImageIO plug-in for Hamamatsu NDPI whole-slide images, for JDK 25.

- **Levels are image indices** — `getNumImages()` is the number of pyramid levels,
  full resolution first, so `read(level, param)` reads from that level.
- **Associated images by name** — `readAssociatedImage("macro")`, and `"map"` when
  the scanner wrote one.
- **Slide properties** — the NDPI property map, the source lens, and microns per
  pixel, as typed accessors and through `IIOMetadata`.
- **Focus stacks** — a slide's out-of-focus z-planes are kept out of the pyramid
  and listed separately (`NdpiStructure.focusPlanes()`).

## What it reads

Validated against twelve real slides: the OpenSlide reference set (CMU-1/2/3),
brightfield slides up to 5.4 GB and 20 gigapixels, and fluorescence channels.
A 1024x1024 region of a 234240x81664 level 0 comes back in tens of milliseconds.

Two things it does not read, and one that is not what it looks like:

- **JPEG XR levels** (compression 22610). Hamamatsu's newer scanners lay a level
  out as a *tiled* TIFF of JPEG XR tiles rather than as one over-large JPEG.
  Decoding those needs a JPEG XR codec, which this module does not have and
  neither does OpenSlide. The reader recognizes them and says so by name; the
  slide's geometry and properties are still readable, and its map image usually
  is too.
- **Nothing else so far** — every other slide in the corpus reads whole.
- The **map** image is not a photograph. Its samples are small integers (0, 1, 2,
  ...), one per pixel, identifying scan regions; it renders nearly black because
  that is what it holds. (Some slides also write a nonsense
  `PhotometricInterpretation` for it, which the reader ignores in favour of
  `SamplesPerPixel`.)

## Usage

Level 0 of a slide is routinely gigapixels, so `ImageIO.read(file)` will try to
allocate all of it. Ask for the reader by name and read a region:

```java
ImageReader reader = ImageIO.getImageReadersByFormatName("ndpi").next();
reader.setInput(ImageIO.createImageInputStream(new File("slide.ndpi")));

reader.getNumImages(true);                       // pyramid levels
reader.getWidth(0);                              // no decoding

ImageReadParam param = reader.getDefaultReadParam();
param.setSourceRegion(new Rectangle(20000, 15000, 1024, 1024));
BufferedImage tissue = reader.read(0, param);    // decodes the tiles it needs
```

```java
NDPIImageReader ndpi = (NDPIImageReader) reader;

int level = ndpi.getBestLevelForDownsample(16);  // level to render 1:16 from
ndpi.getMagnification();                         // OptionalDouble, from the source lens
ndpi.getMicronsPerPixelX();
ndpi.getProperties().get("Objective.Lens.Magnificant");

ndpi.getAssociatedImageNames();                  // [macro]
BufferedImage macro = ndpi.readAssociatedImage("macro");
```

## Why there is no TIFF library here

An NDPI file opens like a little-endian classic TIFF, and is not one. A slide is
routinely larger than the 4 GiB a 32-bit offset reaches, and Hamamatsu's answer
was to keep the classic 32-bit layout and widen every value and offset to 64 bits
by appending the high halves *after* each directory:

```
offset:                     uint16   entry count
offset + 2:                 count x 12-byte entries (tag, type, count, low 32 bits)
offset + 2 + 12 * count:    uint64   next directory        <- 8 bytes, not 4
offset + 10 + 12 * count:   count x uint32  high 32 bits, one per entry
```

The pointer to the first directory is 8 bytes too, so it runs into what a TIFF
reader takes for the start of the file's data. A TIFF reader therefore reads an
NDPI file's offsets truncated to 32 bits: it gets away with it below 4 GiB and
silently reads the wrong bytes above it. (ASCII values are always out of line as
well, even when they would fit.) So the container is read here, by `NdpiTiff`.

The pixels are not a TIFF codec's to decode either — see below. What *is* worth
borrowing is a JPEG decoder, and the module depends on the
[TwelveMonkeys](https://github.com/haraldk/TwelveMonkeys) JPEG plug-in for that.

## How a level is read

A level is a single JPEG, and it is larger than a JPEG is allowed to be: a frame
header cannot express a dimension past 65535, so Hamamatsu writes a zero and puts
the real size in the TIFF tags. Nothing decodes that as it stands.

What makes it readable is that the scanner places a restart marker every
*restartInterval* MCUs and records the byte offset of each one in a private tag.
A restart interval is therefore a tile — `restartInterval` MCUs wide and one MCU
tall — and because a restart marker resets the entropy coder and the DC
predictors, its bytes decode on their own.

So this reader reads a tile by building a small, entirely ordinary JPEG out of
it: the level's own header with the frame header's dimensions rewritten to the
tile's, then the tile's entropy data, then an EOI. Any JPEG decoder will read
that. (OpenSlide instead patches the frame header to libjpeg's maximum and
decodes a prefix of a nominally 65500-pixel-wide image, which works for a
streaming C decoder and would make a Java one allocate 65500 pixels a row.)

The recorded offsets are, in OpenSlide's word, unreliable; when they do not point
at a restart marker the reader scans the entropy data for the real ones.

A consequence worth knowing: the tile grid this exposes through the ImageIO tile
API is a grid of wide, one-MCU-tall strips. Reading whole tiles is not the cheap
way round an NDPI slide that it is in a tiled format. Read the region you want
and let the reader take the tiles it needs.

## Reader selection

The generic TIFF readers accept an NDPI file, and would make nonsense of it. This
plug-in orders itself ahead of them, but ImageIO only lets a plug-in order itself
against the ones already registered — so if a TIFF plug-in is scanned after this
one, the ordering cannot be established at registration. Asking for the reader by
format name or suffix (as above) names this reader alone and is always
unambiguous. If `ImageIO.read` or `ImageIO.getImageReaders` is instead the first
thing that touches a slide, establish the ordering once at start-up:

```java
NDPIImageReaderSpi.prioritize();
```

Creating a reader through this plug-in by any route also establishes it.

## Testing

`mvn -pl NDPI test` runs the suite. The tests synthesize NDPI files rather than
shipping a slide, and synthesize them the way a scanner writes them: each tile is
encoded on its own and their entropy data concatenated with restart markers
between them, which — because a tile is exactly one restart interval — is the MCU
order of the whole level, so the result is a real, decodable JPEG. That makes the
expected pixels exact rather than approximate, and the tests compare bit-for-bit.

Covered, among the rest: a level 65536 pixels wide, whose frame header cannot say
so; a level whose MCU-start table is wrong, which has to be re-derived; and a
slide whose data sits past 4 GiB, written into a sparse file so it costs a few
hundred kilobytes of disk rather than four gigabytes.

One thing the synthesized files cannot cover is a level with more than 2^31
pixels, since a fixture would have to hold that many. `ImageReader.getDestination`
refuses such an image outright — before it looks at how small a region you asked
for — so the reader sizes its own destination instead. That is what the corpus is
for: six of the twelve real slides have a level 0 past 2^31 pixels, and all six
read.
