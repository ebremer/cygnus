package com.ebremer.cygnus.jpegxl.testutil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Minimal NumPy .npy reader for the conformance references (little-endian float32, C order). */
public final class Npy {

    public final int[] shape;
    public final float[] data;

    private Npy(int[] shape, float[] data) {
        this.shape = shape;
        this.data = data;
    }

    public static Npy read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 10 || bytes[0] != (byte) 0x93 || bytes[1] != 'N' || bytes[2] != 'U'
                || bytes[3] != 'M' || bytes[4] != 'P' || bytes[5] != 'Y') {
            throw new IOException("not an NPY file: " + file);
        }
        int major = bytes[6] & 0xff;
        int headerLen;
        int headerStart;
        if (major == 1) {
            headerLen = (bytes[8] & 0xff) | ((bytes[9] & 0xff) << 8);
            headerStart = 10;
        } else {
            headerLen = (bytes[8] & 0xff) | ((bytes[9] & 0xff) << 8)
                    | ((bytes[10] & 0xff) << 16) | ((bytes[11] & 0xff) << 24);
            headerStart = 12;
        }
        String header = new String(bytes, headerStart, headerLen, StandardCharsets.ISO_8859_1);
        if (!header.contains("'<f4'") && !header.contains("\"<f4\"")) {
            throw new IOException("unsupported NPY dtype: " + header);
        }
        if (header.contains("True") && header.contains("fortran_order': True")) {
            throw new IOException("fortran-order NPY not supported");
        }
        int lp = header.indexOf('(');
        int rp = header.indexOf(')', lp);
        String[] dims = header.substring(lp + 1, rp).split(",");
        java.util.List<Integer> shapeList = new java.util.ArrayList<>();
        for (String d : dims) {
            String t = d.trim();
            if (!t.isEmpty()) {
                shapeList.add(Integer.parseInt(t));
            }
        }
        int[] shape = new int[shapeList.size()];
        long count = 1;
        for (int i = 0; i < shape.length; i++) {
            shape[i] = shapeList.get(i);
            count *= shape[i];
        }
        int dataStart = headerStart + headerLen;
        if (bytes.length - dataStart != 4 * count) {
            throw new IOException("NPY size mismatch: " + (bytes.length - dataStart)
                    + " bytes for " + count + " floats");
        }
        float[] data = new float[(int) count];
        ByteBuffer.wrap(bytes, dataStart, bytes.length - dataStart)
                .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(data);
        return new Npy(shape, data);
    }
}
