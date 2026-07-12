package com.ebremer.cygnus.jpegxl.modular;

/**
 * One channel of a modular image: dimensions, downsampling shifts, and (once
 * allocated) sample storage. Shifts can be -1 for palette meta channels.
 */
public final class ModularChannel {

    public int width;
    public int height;
    public int hshift;
    public int vshift;
    public int[] pixels;

    public ModularChannel(int width, int height, int hshift, int vshift) {
        this.width = width;
        this.height = height;
        this.hshift = hshift;
        this.vshift = vshift;
    }

    public ModularChannel(int width, int height) {
        this(width, height, 0, 0);
    }

    public void allocate() {
        if (pixels == null && width > 0 && height > 0) {
            pixels = new int[width * height];
        }
    }

    public boolean isEmpty() {
        return width <= 0 || height <= 0;
    }

    public int get(int x, int y) {
        return pixels[y * width + x];
    }

    public void set(int x, int y, int value) {
        pixels[y * width + x] = value;
    }

    public ModularChannel copyShape() {
        return new ModularChannel(width, height, hshift, vshift);
    }
}
