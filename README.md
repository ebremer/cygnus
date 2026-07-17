# Cygnus — pure-Java image codecs

From-scratch image codecs for JDK 25, exposed through the standard Java ImageIO
API (readers and writers auto-registered via `META-INF/services`). Each is a
Maven module under this parent:

| Module | Format | Artifact |
|--------|--------|----------|
| [JPEG2000](JPEG2000/README.md) | JPEG 2000 Part 1 (ISO/IEC 15444-1 / ITU-T T.800) + HTJ2K (Part 15) decoding | `com.ebremer:jpeg2000` |
| [JPEGXL](JPEGXL/README.md) | JPEG XL (ISO/IEC 18181) | `com.ebremer:jpegxl` |
| [SVS](SVS/README.md) | Aperio SVS whole-slide images, read-only | `com.ebremer:svs` |
| [NDPI](NDPI/README.md) | Hamamatsu NDPI whole-slide images, read-only | `com.ebremer:ndpi` |

The two codecs are dependency-free pure Java. SVS and NDPI are readers for
whole-slide containers rather than codecs, and both expose a slide's pyramid as
ImageIO image indices, with the label/macro images beside it. They lean on the
[TwelveMonkeys](https://github.com/haraldk/TwelveMonkeys) plug-ins for the codecs
they have in common with ordinary files — SVS on its TIFF reader, both on its
JPEG reader — and handle themselves what those cannot: Aperio's private JPEG 2000
tiles (through the JPEG2000 module above), and NDPI's 64-bit-offsets-in-a-32-bit-
TIFF container and deliberately over-large level JPEGs.

See each module's README for format coverage, usage, and API details.

## Using from Maven

Releases and snapshots are published to the Halcyon repository. Add it and
whichever modules you need:

```xml
<repositories>
  <repository>
    <id>halcyon</id>
    <url>https://cursus.bmi.stonybrookmedicine.edu/releases</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.ebremer</groupId>
    <artifactId>jpeg2000</artifactId>
    <version>0.1.0</version>
  </dependency>
  <dependency>
    <groupId>com.ebremer</groupId>
    <artifactId>jpegxl</artifactId>
    <version>0.1.0</version>
  </dependency>
  <dependency>
    <groupId>com.ebremer</groupId>
    <artifactId>svs</artifactId>
    <version>0.1.0</version>
  </dependency>
  <dependency>
    <groupId>com.ebremer</groupId>
    <artifactId>ndpi</artifactId>
    <version>0.1.0</version>
  </dependency>
</dependencies>
```

Snapshot builds live at `https://cursus.bmi.stonybrookmedicine.edu/snapshots`.
Each module registers its ImageIO plug-ins via `META-INF/services`, so having a
jar on the classpath is all `ImageIO.read` needs.

## Build

Requires JDK 25 and Maven. From the repo root:

```
mvn install
```

builds all four; `mvn -pl JPEG2000 test` (or `-pl JPEGXL`, `-pl NDPI`) exercises a
single module, and `mvn -pl SVS -am test` also builds the codec SVS depends on.
