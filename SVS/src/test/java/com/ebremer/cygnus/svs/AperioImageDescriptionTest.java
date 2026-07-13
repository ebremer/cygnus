package com.ebremer.cygnus.svs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Parsing the ImageDescription strings real scanners write. */
class AperioImageDescriptionTest {

    private static final String LEVEL =
            "Aperio Image Library v11.2.1\r\n"
            + "46920x33014 [0,100 46000x32914] (240x240) JPEG/RGB Q=30"
            + "|AppMag = 20|StripeWidth = 2040|ScanScope ID = CPAPERIOCS"
            + "|Filename = 21930|Title = Case A=1|Date = 12/29/09|Time = 09:59:15"
            + "|MPP = 0.4990|Left = 25.691574|ImageID = 1004486";

    @Test
    void parsesPropertiesOfALevel() {
        AperioImageDescription description = AperioImageDescription.parse(LEVEL);

        assertTrue(description.isAperio());
        assertEquals("Aperio Image Library v11.2.1", description.header());
        assertEquals("46920x33014 [0,100 46000x32914] (240x240) JPEG/RGB Q=30",
                description.summary());
        assertEquals(0.4990, description.micronsPerPixel().getAsDouble(), 1e-9);
        assertEquals(20.0, description.magnification().getAsDouble(), 1e-9);
        assertEquals("CPAPERIOCS", description.get("ScanScope ID").orElseThrow());
        assertEquals("1004486", description.get("ImageID").orElseThrow());
        // A value may itself contain an '='; only the first one separates.
        assertEquals("Case A=1", description.get("Title").orElseThrow());
        assertTrue(description.associatedImageName().isEmpty(), "a level names no image");
    }

    @Test
    void propertiesKeepFileOrder() {
        AperioImageDescription description = AperioImageDescription.parse(LEVEL);
        assertEquals(List.of("AppMag", "StripeWidth", "ScanScope ID", "Filename", "Title",
                        "Date", "Time", "MPP", "Left", "ImageID"),
                List.copyOf(description.properties().keySet()));
    }

    @Test
    void labelAndMacroNameThemselves() {
        assertEquals("label", AperioImageDescription
                .parse("Aperio Image Library v11.2.1\r\nlabel 387x463")
                .associatedImageName().orElseThrow());
        assertEquals("macro", AperioImageDescription
                .parse("Aperio Image Library v11.2.1\r\nmacro 1280x431")
                .associatedImageName().orElseThrow());
    }

    @Test
    void thumbnailNamesNothing() {
        AperioImageDescription description = AperioImageDescription.parse(
                "Aperio Image Library v11.2.1\r\n46920x33014 -> 1024x732 - |AppMag = 20");
        assertTrue(description.associatedImageName().isEmpty(),
                "a dimension line is not an image name");
        assertEquals(20.0, description.magnification().getAsDouble(), 1e-9);
    }

    @Test
    void readsNewerScannerHeaders() {
        AperioImageDescription description = AperioImageDescription.parse(
                "Aperio Leica Biosystems GT450 DX v1.0.1\r\n"
                + "150000x80000 [0,0 150000x80000] (256x256) JPEG/YCC Q=91"
                + "|AppMag = 40|MPP = 0.263");

        assertTrue(description.isAperio());
        assertEquals(40.0, description.magnification().getAsDouble(), 1e-9);
        assertEquals(0.263, description.micronsPerPixel().getAsDouble(), 1e-9);
    }

    @Test
    void toleratesTrailingNulsAndMissingProperties() {
        AperioImageDescription description = AperioImageDescription.parse(
                "Aperio Image Library v11.2.1\r\nlabel 387x463\0\0");

        assertEquals("label", description.associatedImageName().orElseThrow());
        assertTrue(description.micronsPerPixel().isEmpty());
        assertTrue(description.properties().isEmpty());
    }

    @Test
    void nonAperioDescriptionIsRecognisedAsSuch() {
        assertFalse(AperioImageDescription.parse("Created with libtiff").isAperio());
        assertFalse(AperioImageDescription.parse(null).isAperio());
        assertFalse(AperioImageDescription.parse("").isAperio());
    }
}
