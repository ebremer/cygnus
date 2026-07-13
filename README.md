# Cygnus — pure-Java image codecs

From-scratch image codecs for JDK 25, exposed through the standard Java ImageIO
API (readers and writers auto-registered via `META-INF/services`). Each is a
Maven module under this parent:

| Module | Format | Artifact |
|--------|--------|----------|
| [JPEG2000](JPEG2000/README.md) | JPEG 2000 Part 1 (ISO/IEC 15444-1 / ITU-T T.800) + HTJ2K (Part 15) decoding | `com.ebremer:jpeg2000` |
| [JPEGXL](JPEGXL/README.md) | JPEG XL (ISO/IEC 18181) | `com.ebremer:jpegxl` |
| [SVS](SVS/README.md) | Aperio SVS whole-slide images, read-only | `com.ebremer:svs` |

The two codecs are dependency-free pure Java. SVS is a reader for a TIFF-based
container rather than a codec: it builds on the TwelveMonkeys TIFF plug-in, and
on the JPEG2000 module for Aperio's private JPEG 2000 tiles.

See each module's README for format coverage, usage, and API details.

## Build

Requires JDK 25 and Maven. From the repo root:

```
mvn install
```

builds all three; `mvn -pl JPEG2000 test` (or `-pl JPEGXL`) exercises a single
module, and `mvn -pl SVS -am test` also builds the codec SVS depends on.
