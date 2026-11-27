package com.exodidio.tune.backdrop;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 简化版 AMLL 取色（palette/）：在 OkLab 空间对封面做 K-Means(k=4)，
 * 再随机打乱四个颜色落到四个角，等价于 TitaniumRenderer 的 paletteOrder。
 */
final class Palette {

    static final int COLOR_COUNT = 4;

    private Palette() {
    }

    /** @return 12 个 float：4 个颜色 × OkLab(L,a,b)，顺序已打乱。 */
    static float[] extractOkLabOrdered(Bitmap src) {
        int size = 48;
        Bitmap small = Bitmap.createScaledBitmap(src, size, size, true);
        int[] argb = new int[size * size];
        small.getPixels(argb, 0, size, 0, 0, size, size);
        if (small != src) {
            small.recycle();
        }

        List<float[]> points = new ArrayList<>(size * size);
        for (int c : argb) {
            if (((c >>> 24) & 0xFF) < 8) {
                continue;
            }
            float r = ((c >> 16) & 0xFF) / 255f;
            float g = ((c >> 8) & 0xFF) / 255f;
            float b = (c & 0xFF) / 255f;
            points.add(srgbToOkLab(r, g, b));
        }
        float[] colors = defaultOkLab();
        if (points.size() < COLOR_COUNT) {
            return colors;
        }

        Random rnd = new Random(src.getGenerationId() ^ points.size());
        float[][] centers = new float[COLOR_COUNT][];
        boolean[] used = new boolean[points.size()];
        for (int i = 0; i < COLOR_COUNT; i++) {
            int pick;
            do {
                pick = rnd.nextInt(points.size());
            } while (used[pick]);
            used[pick] = true;
            centers[i] = points.get(pick).clone();
        }

        for (int iter = 0; iter < 12; iter++) {
            float[][] sum = new float[COLOR_COUNT][3];
            int[] count = new int[COLOR_COUNT];
            for (float[] p : points) {
                int best = 0;
                float bestDist = Float.MAX_VALUE;
                for (int i = 0; i < COLOR_COUNT; i++) {
                    float d = square(p[0] - centers[i][0])
                            + square(p[1] - centers[i][1])
                            + square(p[2] - centers[i][2]);
                    if (d < bestDist) {
                        bestDist = d;
                        best = i;
                    }
                }
                sum[best][0] += p[0];
                sum[best][1] += p[1];
                sum[best][2] += p[2];
                count[best]++;
            }
            for (int i = 0; i < COLOR_COUNT; i++) {
                if (count[i] == 0) {
                    centers[i] = points.get(rnd.nextInt(points.size())).clone();
                } else {
                    centers[i][0] = sum[i][0] / count[i];
                    centers[i][1] = sum[i][1] / count[i];
                    centers[i][2] = sum[i][2] / count[i];
                }
            }
        }

        int[] order = {0, 1, 2, 3};
        for (int i = order.length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        for (int i = 0; i < COLOR_COUNT; i++) {
            colors[i * 3] = centers[order[i]][0];
            colors[i * 3 + 1] = centers[order[i]][1];
            colors[i * 3 + 2] = centers[order[i]][2];
        }
        return colors;
    }

    /** AMLL 无封面时的中性深色配色，同 DEFAULT_COLORS。 */
    static float[] defaultOkLab() {
        float[][] srgb = {
                {0.13f, 0.13f, 0.15f},
                {0.10f, 0.10f, 0.12f},
                {0.17f, 0.17f, 0.19f},
                {0.11f, 0.11f, 0.13f}
        };
        float[] out = new float[COLOR_COUNT * 3];
        for (int i = 0; i < COLOR_COUNT; i++) {
            float[] lab = srgbToOkLab(srgb[i][0], srgb[i][1], srgb[i][2]);
            out[i * 3] = lab[0];
            out[i * 3 + 1] = lab[1];
            out[i * 3 + 2] = lab[2];
        }
        return out;
    }

    static float[] srgbToOkLab(float r, float g, float b) {
        float lr = toLinear(r);
        float lg = toLinear(g);
        float lb = toLinear(b);
        float l = 0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb;
        float m = 0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb;
        float s = 0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb;
        float lr3 = (float) Math.cbrt(l);
        float mr3 = (float) Math.cbrt(m);
        float sr3 = (float) Math.cbrt(s);
        return new float[]{
                0.2104542553f * lr3 + 0.7936177850f * mr3 - 0.0040720468f * sr3,
                1.9779984951f * lr3 - 2.4285922050f * mr3 + 0.4505937099f * sr3,
                0.0259040371f * lr3 + 0.7827717662f * mr3 - 0.8086757660f * sr3
        };
    }

    private static float toLinear(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
    }

    private static float square(float x) {
        return x * x;
    }
}
