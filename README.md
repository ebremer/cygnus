# Cygnus — pure-Java image codecs

From-scratch, dependency-free image codecs for JDK 25, exposed through the
standard Java ImageIO API (readers and writers auto-registered via
`META-INF/services`). Each codec is a Maven module under this parent:

| Module | Format | Artifact |
|--------|--------|----------|
| [JPEG2000](JPEG2000/README.md) | JPEG 2000 Part 1 (ISO/IEC 15444-1 / ITU-T T.800) + HTJ2K (Part 15) decoding | `com.ebremer:jpeg2000` |
| [JPEGXL](JPEGXL/README.md) | JPEG XL (ISO/IEC 18181) | `com.ebremer:jpegxl` |

See each module's README for format coverage, usage, and API details.

## Build

Requires JDK 25 and Maven. From the repo root:

```
mvn install
```

builds both codecs; `mvn -pl JPEG2000 test` (or `-pl JPEGXL`) exercises a
single module.
