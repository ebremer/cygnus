# Cygnus — Aperio SVS reader

A read-only Java ImageIO plug-in for Aperio SVS whole-slide images, for JDK 25.

An SVS file is a TIFF (or BigTIFF) whose directories hold a resolution pyramid
plus the label, macro and thumbnail images the scanner took alongside it. This
module reads that structure as ImageIO images, and decodes the two private
JPEG 2000 tile compressions (33003/33005) that no TIFF codec covers.

- **Levels are image indices** — `getNumImages()` is the number of pyramid
  levels, full resolution first, so `read(level, param)` reads from that level.
- **Associated images by name** — `readAssociatedImage("label" | "macro" |
  "thumbnail")`; the thumbnail is also reachable through ImageIO's thumbnail
  API on image 0.
- **Slide properties** — the Aperio `ImageDescription` is parsed, so `MPP`,
  `AppMag`, `ScanScope ID` and the rest are available as typed accessors and
  through `IIOMetadata`.
- **JPEG 2000 tiles** — compression 33005 (RGB) and 33003 (YCbCr, converted on
  read) are decoded by the sibling [JPEG2000](../JPEG2000/README.md) codec.

Unlike the other Cygnus modules this one is not dependency-free: the TIFF
container and its JPEG/LZW/uncompressed directories are handled by the
[TwelveMonkeys](https://github.com/haraldk/TwelveMonkeys) TIFF and JPEG
plug-ins rather than re-implemented.

## Usage

Level 0 of a slide is routinely gigapixels, so `ImageIO.read(svsFile)` will try
to allocate all of it. Ask for the reader by name and read a region:

```java
ImageReader reader = ImageIO.getImageReadersByFormatName("svs").next();
reader.setInput(ImageIO.createImageInputStream(new File("slide.svs")));

reader.getNumImages(true);                       // pyramid levels
reader.getWidth(0);                              // header-only, no decoding

ImageReadParam param = reader.getDefaultReadParam();
param.setSourceRegion(new Rectangle(20000, 15000, 1024, 1024));
BufferedImage tissue = reader.read(0, param);    // decodes intersecting tiles only
```

The TIFF tile grid is exposed through the standard ImageIO tile API, which is
usually the cheapest way to walk a slide:

```java
BufferedImage tile = reader.readTile(0, tileX, tileY);   // one TIFF tile
```

Levels, scale and the images beside the pyramid, through `SVSImageReader`:

```java
SVSImageReader svs = (SVSImageReader) reader;

int level = svs.getBestLevelForDownsample(16);   // level to render 1:16 from
double scale = svs.getLevelDownsample(level);    // its actual downsample

svs.getMicronsPerPixel();                        // OptionalDouble, from MPP
svs.getMagnification();                          // OptionalDouble, from AppMag
svs.getProperties().get("ScanScope ID");

svs.getAssociatedImageNames();                   // [thumbnail, label, macro]
BufferedImage label = svs.readAssociatedImage("label");
```

`SVSStructure` (via `svs.getStructure()`) exposes the raw layout — every TIFF
directory, which are levels, which are associated images — for callers that
want it.

## Reader selection

The generic TIFF readers accept an SVS file too, and would decode its
full-resolution level whole. This plug-in orders itself ahead of them, but
ImageIO registers plug-ins in classpath order and only lets a plug-in order
itself against the ones already registered — so if the TIFF plug-in is scanned
after this one, the ordering cannot be established at registration.

Asking for the reader by format name or suffix (as above) names this reader
alone and is always unambiguous. If `ImageIO.read` or `ImageIO.getImageReaders`
is instead the first thing that touches a slide, establish the ordering once at
start-up:

```java
SVSImageReaderSpi.prioritize();
```

Creating a reader through this plug-in by any route also establishes it.

## Format notes

Directory 0 is the full-resolution image. Aperio stores the pyramid in tiled
directories and the associated images in stripped ones, which is what separates
them; a stripped directory names itself on the second line of its
`ImageDescription` (`label 387x463`), and one that names nothing is the
thumbnail.

Tiles are JPEG (with a shared `JPEGTables`), LZW, uncompressed, or one of
Aperio's JPEG 2000 codes. Under 33003 the codestream's three components are Y,
Cb and Cr — the codestream carries no colour signalling, so the TIFF tag is the
only thing that says so — and are converted to RGB on read. Classic TIFF and
BigTIFF are both read.

## Testing

`mvn -pl SVS -am test` builds the JPEG 2000 codec it depends on and runs the
suite. The tests synthesize SVS files rather than shipping a slide: tiled
pyramid levels padded to the full tile size, abbreviated JPEG tile streams
against a shared `JPEGTables`, stripped label/macro/thumbnail directories, and
Aperio `ImageDescription`s — in classic TIFF and BigTIFF, and with JPEG 2000
tiles in both colour spaces. The JPEG 2000 fixtures are coded losslessly, so
the decoded pixels are compared bit-exactly against the source.
