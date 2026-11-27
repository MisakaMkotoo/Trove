package com.exodidio.tune.backdrop;

import android.graphics.Bitmap;

/**
 * Port of AMLL's cover preprocessing (mesh-renderer/index.ts setAlbum):
 * downscale to 32x32, contrast 0.4 / saturate 3.0 / contrast 1.7 / brightness 0.75,
 * then two box-blur passes with radius 2 (img.ts blurImage).
 */
final class AlbumProcessor {

    static final int SIZE = 32;

    private AlbumProcessor() {
    }

    static byte[] process(Bitmap src) {
        Bitmap small = Bitmap.createScaledBitmap(src, SIZE, SIZE, true);
        int[] argb = new int[SIZE * SIZE];
        small.getPixels(argb, 0, SIZE, 0, 0, SIZE, SIZE);
        if (small != src) {
            small.recycle();
        }
        byte[] px = new byte[SIZE * SIZE * 4];
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            float r = (c >> 16) & 0xFF;
            float g = (c >> 8) & 0xFF;
            float b = c & 0xFF;
            int a = (c >>> 24) & 0xFF;

            r = (r - 128f) * 0.4f + 128f;
            g = (g - 128f) * 0.4f + 128f;
            b = (b - 128f) * 0.4f + 128f;

            float gray = r * 0.3f + g * 0.59f + b * 0.11f;
            r = gray * -2f + r * 3f;
            g = gray * -2f + g * 3f;
            b = gray * -2f + b * 3f;

            r = (r - 128f) * 1.7f + 128f;
            g = (g - 128f) * 1.7f + 128f;
            b = (b - 128f) * 1.7f + 128f;

            int o = i * 4;
            px[o] = (byte) clamp(Math.round(r * 0.75f));
            px[o + 1] = (byte) clamp(Math.round(g * 0.75f));
            px[o + 2] = (byte) clamp(Math.round(b * 0.75f));
            px[o + 3] = (byte) a;
        }
        blur(px, SIZE, SIZE, 2, 2);
        return px;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    private static int u(byte v) {
        return v & 0xFF;
    }

    private static byte b(int v) {
        return (byte) (v < 0 ? 0 : Math.min(255, v));
    }

    /** Direct port of img.ts blurImage (stack blur, radius 2, quality 2). */
    static void blur(byte[] pixels, int width, int height, int radius, int quality) {
        int wm = width - 1;
        int hm = height - 1;
        int rad1x = radius + 1;
        int divx = radius + rad1x;
        int rad1y = radius + 1;
        int divy = radius + rad1y;
        float div2 = 1f / (divx * divy);

        int[] r = new int[width * height];
        int[] g = new int[width * height];
        int[] b = new int[width * height];
        int[] a = new int[width * height];
        int[] vmin = new int[Math.max(width, height)];
        int[] vmax = new int[Math.max(width, height)];

        while (quality-- > 0) {
            int yw = 0;
            int yi = 0;
            for (int y = 0; y < height; y++) {
                int rsum = u(pixels[yw]) * rad1x;
                int gsum = u(pixels[yw + 1]) * rad1x;
                int bsum = u(pixels[yw + 2]) * rad1x;
                int asum = u(pixels[yw + 3]) * rad1x;

                for (int i = 1; i <= radius; i++) {
                    int p = yw + ((Math.min(i, wm)) << 2);
                    rsum += u(pixels[p]);
                    gsum += u(pixels[p + 1]);
                    bsum += u(pixels[p + 2]);
                    asum += u(pixels[p + 3]);
                }

                for (int x = 0; x < width; x++) {
                    r[yi] = rsum;
                    g[yi] = gsum;
                    b[yi] = bsum;
                    a[yi] = asum;

                    if (y == 0) {
                        vmin[x] = Math.min(x + rad1x, wm) << 2;
                        vmax[x] = Math.max(x - radius, 0) << 2;
                    }

                    int p1 = yw + vmin[x];
                    int p2 = yw + vmax[x];
                    rsum += u(pixels[p1]) - u(pixels[p2]);
                    gsum += u(pixels[p1 + 1]) - u(pixels[p2 + 1]);
                    bsum += u(pixels[p1 + 2]) - u(pixels[p2 + 2]);
                    asum += u(pixels[p1 + 3]) - u(pixels[p2 + 3]);
                    yi++;
                }
                yw += width << 2;
            }

            for (int x = 0; x < width; x++) {
                int yp = x;
                int rsum = r[yp] * rad1y;
                int gsum = g[yp] * rad1y;
                int bsum = b[yp] * rad1y;
                int asum = a[yp] * rad1y;

                for (int i = 1; i <= radius; i++) {
                    yp += i > hm ? 0 : width;
                    rsum += r[yp];
                    gsum += g[yp];
                    bsum += b[yp];
                    asum += a[yp];
                }

                yi = x << 2;
                for (int y = 0; y < height; y++) {
                    pixels[yi] = b(Math.round(rsum * div2));
                    pixels[yi + 1] = b(Math.round(gsum * div2));
                    pixels[yi + 2] = b(Math.round(bsum * div2));
                    pixels[yi + 3] = b(Math.round(asum * div2));

                    if (x == 0) {
                        vmin[y] = Math.min(y + rad1y, hm) * width;
                        vmax[y] = Math.max(y - radius, 0) * width;
                    }

                    int p1 = x + vmin[y];
                    int p2 = x + vmax[y];
                    rsum += r[p1] - r[p2];
                    gsum += g[p1] - g[p2];
                    bsum += b[p1] - b[p2];
                    asum += a[p1] - a[p2];
                    yi += width << 2;
                }
            }
        }
    }
}
