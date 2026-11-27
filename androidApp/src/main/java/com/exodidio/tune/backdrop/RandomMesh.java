package com.exodidio.tune.backdrop;

import java.util.Random;

/**
 * Port of AMLL cp-generate.ts generateControlPoints():
 * random interior control points + smooth noise offsets, then a few smoothing passes.
 * AMLL uses this for ~20% of album loads; here it's a button so the shape can be rerolled.
 */
final class RandomMesh {

    private static final Random RND = new Random();

    private RandomMesh() {
    }

    static Presets.Preset generate(int w, int h) {
        return generate(w, h, System.nanoTime());
    }

    /** 带种子的生成：同一首歌（同一哈希种子）始终得到同一套随机形变。 */
    static synchronized Presets.Preset generate(int w, int h, long seed) {
        RND.setSeed(seed);
        float variationFraction = range(0.4f, 0.6f);
        float normalOffset = range(0.3f, 0.6f);
        float blendFactor = 0.8f;
        int smoothIters = (int) Math.floor(range(3f, 5f));
        float smoothFactor = range(0.2f, 0.3f);
        float smoothModifier = range(-0.1f, -0.05f);

        float[] conf = new float[w * h * 8];
        float dx = w == 1 ? 0f : 2f / (w - 1);
        float dy = h == 1 ? 0f : 2f / (h - 1);

        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                float baseX = (i / (float) (w - 1)) * 2f - 1f;
                float baseY = (j / (float) (h - 1)) * 2f - 1f;
                boolean border = i == 0 || i == w - 1 || j == 0 || j == h - 1;

                float pertX = border ? 0f : range(-variationFraction * dx, variationFraction * dx);
                float pertY = border ? 0f : range(-variationFraction * dy, variationFraction * dy);
                float x = baseX + pertX;
                float y = baseY + pertY;
                float ur = border ? 0f : range(-60f, 60f);
                float vr = border ? 0f : range(-60f, 60f);
                float up = border ? 1f : range(0.8f, 1.2f);
                float vp = border ? 1f : range(0.8f, 1.2f);

                if (!border) {
                    float uNorm = (baseX + 1f) / 2f;
                    float vNorm = (baseY + 1f) / 2f;
                    float[] grad = noiseGradient(uNorm, vNorm);
                    float offsetX = grad[0] * normalOffset;
                    float offsetY = grad[1] * normalOffset;
                    float distToBorder = Math.min(Math.min(uNorm, 1f - uNorm), Math.min(vNorm, 1f - vNorm));
                    float weight = smoothstep01(distToBorder);
                    offsetX *= weight;
                    offsetY *= weight;
                    x = x * (1f - blendFactor) + (x + offsetX) * blendFactor;
                    y = y * (1f - blendFactor) + (y + offsetY) * blendFactor;
                }

                int o = (j * w + i) * 8;
                conf[o] = i;
                conf[o + 1] = j;
                conf[o + 2] = x;
                conf[o + 3] = y;
                conf[o + 4] = ur;
                conf[o + 5] = vr;
                conf[o + 6] = up;
                conf[o + 7] = vp;
            }
        }

        smoothify(conf, w, h, smoothIters, smoothFactor, smoothModifier);
        return new Presets.Preset("随机 " + w + "x" + h, w, h, conf);
    }

    private static void smoothify(
            float[] conf, int w, int h, int iterations, float factor, float factorModifier) {
        float[][] grid = new float[w * h][];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int o = (j * w + i) * 8;
                grid[j * w + i] = new float[]{
                        conf[o + 2], conf[o + 3], conf[o + 4], conf[o + 5], conf[o + 6], conf[o + 7]
                };
            }
        }
        int[] kernel = {1, 2, 1, 2, 4, 2, 1, 2, 1};
        float kernelSum = 16f;
        float f = factor;

        for (int iter = 0; iter < iterations; iter++) {
            float[][] next = new float[w * h][];
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    if (i == 0 || i == w - 1 || j == 0 || j == h - 1) {
                        next[j * w + i] = grid[j * w + i];
                        continue;
                    }
                    float sumX = 0, sumY = 0, sumUR = 0, sumVR = 0, sumUP = 0, sumVP = 0;
                    for (int dj = -1; dj <= 1; dj++) {
                        for (int di = -1; di <= 1; di++) {
                            float weight = kernel[(dj + 1) * 3 + (di + 1)];
                            float[] nb = grid[(j + dj) * w + (i + di)];
                            sumX += nb[0] * weight;
                            sumY += nb[1] * weight;
                            sumUR += nb[2] * weight;
                            sumVR += nb[3] * weight;
                            sumUP += nb[4] * weight;
                            sumVP += nb[5] * weight;
                        }
                    }
                    float[] cur = grid[j * w + i];
                    next[j * w + i] = new float[]{
                            cur[0] * (1f - f) + (sumX / kernelSum) * f,
                            cur[1] * (1f - f) + (sumY / kernelSum) * f,
                            cur[2] * (1f - f) + (sumUR / kernelSum) * f,
                            cur[3] * (1f - f) + (sumVR / kernelSum) * f,
                            cur[4] * (1f - f) + (sumUP / kernelSum) * f,
                            cur[5] * (1f - f) + (sumVP / kernelSum) * f
                    };
                }
            }
            grid = next;
            f = clamp01(f + factorModifier);
        }

        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                float[] g = grid[j * w + i];
                int o = (j * w + i) * 8;
                conf[o] = i;
                conf[o + 1] = j;
                conf[o + 2] = g[0];
                conf[o + 3] = g[1];
                conf[o + 4] = g[2];
                conf[o + 5] = g[3];
                conf[o + 6] = g[4];
                conf[o + 7] = g[5];
            }
        }
    }

    private static float[] noiseGradient(float x, float y) {
        float eps = 0.001f;
        float n1 = smoothNoise(x + eps, y);
        float n2 = smoothNoise(x - eps, y);
        float n3 = smoothNoise(x, y + eps);
        float n4 = smoothNoise(x, y - eps);
        float gx = (n1 - n2) / (2f * eps);
        float gy = (n3 - n4) / (2f * eps);
        float len = (float) Math.sqrt(gx * gx + gy * gy);
        if (len == 0f) {
            len = 1f;
        }
        return new float[]{gx / len, gy / len};
    }

    private static float noise(float x, float y) {
        return fract((float) (Math.sin(x * 12.9898f + y * 78.233f) * 43758.5453f));
    }

    private static float fract(float x) {
        return x - (float) Math.floor(x);
    }

    private static float smoothNoise(float x, float y) {
        float x0 = (float) Math.floor(x);
        float y0 = (float) Math.floor(y);
        float x1 = x0 + 1f;
        float y1 = y0 + 1f;
        float xf = x - x0;
        float yf = y - y0;
        float u = xf * xf * (3f - 2f * xf);
        float v = yf * yf * (3f - 2f * yf);
        float n00 = noise(x0, y0);
        float n10 = noise(x1, y0);
        float n01 = noise(x0, y1);
        float n11 = noise(x1, y1);
        float nx0 = n00 * (1f - u) + n10 * u;
        float nx1 = n01 * (1f - u) + n11 * u;
        return nx0 * (1f - v) + nx1 * v;
    }

    private static float smoothstep01(float x) {
        float t = clamp01(x);
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float x) {
        return Math.max(0f, Math.min(1f, x));
    }

    private static float range(float min, float max) {
        return min + RND.nextFloat() * (max - min);
    }
}
