package com.ebremer.cygnus.nifti;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Naming and pairing: which file is which, and which one goes with it. */
class NiftiFilesTest {

    private static Path p(String name) {
        return Path.of("volumes", name);
    }

    @Test
    void namesAreClassifiedInAnyCase() {
        assertTrue(NiftiFiles.isSingleFileName(p("brain.nii")));
        assertTrue(NiftiFiles.isSingleFileName(p("brain.nii.gz")));
        assertTrue(NiftiFiles.isSingleFileName(p("BRAIN.NII.GZ")));
        assertFalse(NiftiFiles.isSingleFileName(p("brain.hdr")));

        assertTrue(NiftiFiles.isHeaderName(p("brain.hdr")));
        assertTrue(NiftiFiles.isHeaderName(p("brain.HDR.gz")));
        assertFalse(NiftiFiles.isHeaderName(p("brain.img")));

        assertTrue(NiftiFiles.isImageName(p("brain.img")));
        assertTrue(NiftiFiles.isImageName(p("brain.img.gz")));

        assertTrue(NiftiFiles.isNiftiName(p("brain.nii")));
        assertFalse(NiftiFiles.isNiftiName(p("brain.dcm")));
        assertFalse(NiftiFiles.isNiftiName(p("brain.gz")));
    }

    @Test
    void gzipSuffixAndGzipContentAreDifferentQuestions() {
        assertTrue(NiftiFiles.hasGzipSuffix(p("brain.nii.gz")));
        assertFalse(NiftiFiles.hasGzipSuffix(p("brain.nii")));

        assertTrue(NiftiFiles.looksGzipped(new byte[] {0x1F, (byte) 0x8B, 8, 0}));
        assertFalse(NiftiFiles.looksGzipped(new byte[] {0x5C, 1, 0, 0}));
        assertFalse(NiftiFiles.looksGzipped(new byte[] {0x1F}), "two bytes are needed to tell");
        assertFalse(NiftiFiles.looksGzipped(new byte[0]));
    }

    @Test
    void baseNameStripsBothSuffixes() {
        assertEquals("sub-01_T1w", NiftiFiles.baseName(p("sub-01_T1w.nii.gz")));
        assertEquals("sub-01_T1w", NiftiFiles.baseName(p("sub-01_T1w.nii")));
        assertEquals("sub-01_T1w", NiftiFiles.baseName(p("sub-01_T1w.hdr")));
        assertEquals("sub-01_T1w", NiftiFiles.baseName(p("sub-01_T1w.img.gz")));
        assertEquals("archive.tar", NiftiFiles.baseName(p("archive.tar.gz")),
                "only a NIfTI suffix is stripped under the .gz");
        assertEquals("plain", NiftiFiles.baseName(p("plain")));
    }

    @Test
    void aHeaderNamesItsImageKeepingCompressionAndCase() {
        assertEquals("brain.img", NiftiFiles.imageFileFor(p("brain.hdr")).getFileName().toString());
        assertEquals("brain.img.gz",
                NiftiFiles.imageFileFor(p("brain.hdr.gz")).getFileName().toString());
        assertEquals("BRAIN.IMG",
                NiftiFiles.imageFileFor(p("BRAIN.HDR")).getFileName().toString(),
                ".HDR pairs with .IMG, not .img");

        assertEquals("brain.hdr", NiftiFiles.headerFileFor(p("brain.img")).getFileName().toString());
        assertEquals("brain.hdr.gz",
                NiftiFiles.headerFileFor(p("brain.img.gz")).getFileName().toString());

        assertThrows(IllegalArgumentException.class,
                () -> NiftiFiles.imageFileFor(p("brain.nii")));
    }

    @Test
    void theImageStaysBesideItsHeader() {
        Path hdr = Path.of("study", "session2", "brain.hdr");
        assertEquals(Path.of("study", "session2", "brain.img"), NiftiFiles.imageFileFor(hdr));
    }

    @Test
    void pairNamesAreDerivedFromAnySuffix(@TempDir Path dir) {
        Path[] names = NiftiFiles.pairNamesFor(dir.resolve("brain.nii.gz"), false);
        assertEquals("brain.hdr", names[0].getFileName().toString());
        assertEquals("brain.img", names[1].getFileName().toString());

        Path[] gzipped = NiftiFiles.pairNamesFor(dir.resolve("brain.nii"), true);
        assertEquals("brain.hdr.gz", gzipped[0].getFileName().toString());
        assertEquals("brain.img.gz", gzipped[1].getFileName().toString());
    }

    @Test
    void findImageFileTriesTheOtherCompression(@TempDir Path dir) throws IOException {
        Path hdr = Files.createFile(dir.resolve("brain.hdr"));
        Path gzImg = Files.createFile(dir.resolve("brain.img.gz"));
        // the matching name is brain.img, which is not there; brain.img.gz is
        assertEquals(gzImg, NiftiFiles.findImageFile(hdr));

        Path plainImg = Files.createFile(dir.resolve("brain.img"));
        assertEquals(plainImg, NiftiFiles.findImageFile(hdr),
                "the matching name wins when it exists");
    }

    @Test
    void aMissingImageFileNamesWhatItLookedFor(@TempDir Path dir) throws IOException {
        Path hdr = Files.createFile(dir.resolve("orphan.hdr"));
        IOException e = assertThrows(IOException.class, () -> NiftiFiles.findImageFile(hdr));
        assertTrue(e.getMessage().contains("orphan.hdr"), e.getMessage());
        assertTrue(e.getMessage().contains("orphan.img"), e.getMessage());
        assertTrue(e.getMessage().contains("orphan.img.gz"),
                "both candidates named: " + e.getMessage());
    }
}
