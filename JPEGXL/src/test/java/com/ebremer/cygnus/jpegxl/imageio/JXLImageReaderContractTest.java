package com.ebremer.cygnus.jpegxl.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.cygnus.jpegxl.encoder.JxlEncoder;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import org.junit.jupiter.api.Test;

/**
 * The {@link javax.imageio.ImageReader} contract pieces a host application
 * leans on — pre-allocated destinations, band selection, offsets — checked
 * against the same behavior the NDPI and SVS readers already provide.
 */
class JXLImageReaderContractTest {

    static byte[] rgb(int w, int h) throws IOException {
        int[][] p = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                p[0][i] = (x * 5 + y) & 0xff;
                p[1][i] = (x + y * 3) & 0xff;
                p[2][i] = (x ^ y) & 0xff;
            }
        }
        return JxlEncoder.encode(p, w, h, 8, false, false, false);
    }

    static ImageReader readerFor(byte[] jxl) throws IOException {
        ImageReader reader = new JXLImageReaderSpi().createReaderInstance(null);
        reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(jxl)));
        return reader;
    }

    @Test
    void preAllocatedDestinationIsFilledAndReturned() throws Exception {
        byte[] jxl = rgb(64, 48);
        ImageReader reader = readerFor(jxl);
        BufferedImage expect = reader.read(0, null);

        reader = readerFor(jxl);
        BufferedImage canvas = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        ImageReadParam param = reader.getDefaultReadParam();
        param.setDestination(canvas);
        BufferedImage got = reader.read(0, param);
        assertSame(canvas, got, "read must return the caller's destination");
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 64; x++) {
                assertEquals(expect.getRGB(x, y), canvas.getRGB(x, y),
                        "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void destinationOffsetPlacesTheRegion() throws Exception {
        byte[] jxl = rgb(64, 48);
        ImageReader reader = readerFor(jxl);
        BufferedImage expect = reader.read(0, null);

        reader = readerFor(jxl);
        BufferedImage canvas = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ImageReadParam param = reader.getDefaultReadParam();
        param.setDestination(canvas);
        param.setSourceRegion(new Rectangle(8, 4, 30, 20));
        param.setDestinationOffset(new java.awt.Point(10, 20));
        reader.read(0, param);
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 30; x++) {
                assertEquals(expect.getRGB(8 + x, 4 + y), canvas.getRGB(10 + x, 20 + y),
                        "region pixel " + x + "," + y);
            }
        }
        assertEquals(0xff000000, canvas.getRGB(0, 0), "outside the offset stays untouched");
        assertEquals(0xff000000, canvas.getRGB(41, 20), "right of the region stays untouched");
    }

    @Test
    void destinationTypeOutsideTheSupportedListIsRefused() throws Exception {
        ImageReader reader = readerFor(rgb(32, 24));
        ImageReadParam param = reader.getDefaultReadParam();
        param.setDestinationType(ImageTypeSpecifier.createFromBufferedImageType(
                BufferedImage.TYPE_BYTE_BINARY));
        assertThrows(IIOException.class, () -> reader.read(0, param));
    }

    @Test
    void sourceBandsSelectAndReorderChannels() throws Exception {
        byte[] jxl = rgb(32, 24);
        ImageReader reader = readerFor(jxl);
        BufferedImage expect = reader.read(0, null);

        reader = readerFor(jxl);
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceBands(new int[] {2, 1, 0});
        BufferedImage swapped = reader.read(0, param);
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 32; x++) {
                int e = expect.getRGB(x, y);
                int r = (e >> 16) & 0xff;
                int g = (e >> 8) & 0xff;
                int b = e & 0xff;
                assertEquals(0xff000000 | (b << 16) | (g << 8) | r, swapped.getRGB(x, y),
                        "swapped pixel " + x + "," + y);
            }
        }
    }

    /** Collects progress events; subclasses hook the ones they care about. */
    static class Progress implements javax.imageio.event.IIOReadProgressListener {
        final java.util.List<String> events = new java.util.ArrayList<>();

        @Override
        public void imageStarted(ImageReader source, int imageIndex) {
            events.add("started " + imageIndex);
        }

        @Override
        public void imageProgress(ImageReader source, float percentageDone) {
            events.add("progress");
        }

        @Override
        public void imageComplete(ImageReader source) {
            events.add("complete");
        }

        @Override
        public void readAborted(ImageReader source) {
            events.add("aborted");
        }

        @Override
        public void sequenceStarted(ImageReader source, int minIndex) {
        }

        @Override
        public void sequenceComplete(ImageReader source) {
        }

        @Override
        public void thumbnailStarted(ImageReader source, int imageIndex, int thumbnailIndex) {
        }

        @Override
        public void thumbnailProgress(ImageReader source, float percentageDone) {
        }

        @Override
        public void thumbnailComplete(ImageReader source) {
        }
    }

    @Test
    void listenersSeeStartedAndComplete() throws Exception {
        ImageReader reader = readerFor(rgb(64, 48));
        Progress listener = new Progress();
        reader.addIIOReadProgressListener(listener);
        reader.read(0, null);
        assertEquals("started 0", listener.events.get(0));
        assertEquals("complete", listener.events.get(listener.events.size() - 1));
    }

    @Test
    void abortFromAListenerEndsTheReadAsAborted() throws Exception {
        ImageReader reader = readerFor(rgb(64, 48));
        Progress listener = new Progress() {
            @Override
            public void imageStarted(ImageReader source, int imageIndex) {
                source.abort();
            }
        };
        reader.addIIOReadProgressListener(listener);
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceSubsampling(2, 2, 0, 0); // takes the pixel-copy path
        reader.read(0, param);
        org.junit.jupiter.api.Assertions.assertTrue(listener.events.contains("aborted"),
                "aborted reads report readAborted, got " + listener.events);
        org.junit.jupiter.api.Assertions.assertFalse(listener.events.contains("complete"),
                "an aborted read must not also report complete");
    }

    @Test
    void mismatchedBandCountsAreRefused() throws Exception {
        ImageReader reader = readerFor(rgb(32, 24));
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceBands(new int[] {0});
        BufferedImage canvas = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        param.setDestination(canvas);
        assertThrows(IllegalArgumentException.class, () -> reader.read(0, param));
    }
}
