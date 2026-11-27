package com.exodidio.tune.backdrop;

/**
 * Port of AMLL's BHPMesh (Bicubic Hermite Patch Mesh) used by the mesh gradient renderer.
 * Builds a CPU vertex/index buffer exactly like packages/core/src/bg-render/mesh-renderer.
 */
final class MeshBuilder {

    static final int SUBDIVISIONS = 50;

    static final class MeshData {
        final float[] vertices;
        final short[] indices;

        MeshData(float[] vertices, short[] indices) {
            this.vertices = vertices;
            this.indices = indices;
        }
    }

    private static final class CP {
        float x;
        float y;
        float ux;
        float uy;
        float vx;
        float vy;

        void set(float x, float y, float uRot, float vRot, float uScale, float vScale) {
            this.x = x;
            this.y = y;
            this.ux = (float) (Math.cos(uRot) * uScale);
            this.uy = (float) (Math.sin(uRot) * uScale);
            this.vx = (float) (-Math.sin(vRot) * vScale);
            this.vy = (float) (Math.cos(vRot) * vScale);
        }
    }

    // gl-matrix H matrix, same element order as upstream (column-major storage)
    private static final float[] H = {
            2, -2, 1, 1,
            -3, 3, -2, -1,
            0, 0, 1, 0,
            1, 0, 0, 0
    };

    private MeshBuilder() {
    }

    static MeshData build(Presets.Preset preset) {
        final int w = preset.width;
        final int h = preset.height;
        final int sub = SUBDIVISIONS;
        final int vw = (w - 1) * sub;
        final int vh = (h - 1) * sub;

        CP[][] cps = new CP[w][h];
        float uPower = 2f / (w - 1);
        float vPower = 2f / (h - 1);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                CP p = new CP();
                p.x = (x / (float) (w - 1)) * 2f - 1f;
                p.y = (y / (float) (h - 1)) * 2f - 1f;
                p.ux = 2f / (w - 1);
                p.uy = 0f;
                p.vx = 0f;
                p.vy = 2f / (h - 1);
                cps[x][y] = p;
            }
        }
        for (int i = 0; i < preset.conf.length; i += 8) {
            int cx = (int) preset.conf[i];
            int cy = (int) preset.conf[i + 1];
            cps[cx][cy].set(
                    preset.conf[i + 2],
                    preset.conf[i + 3],
                    (float) Math.toRadians(preset.conf[i + 4]),
                    (float) Math.toRadians(preset.conf[i + 5]),
                    uPower * preset.conf[i + 6],
                    vPower * preset.conf[i + 7]
            );
        }

        float subM1 = sub - 1;
        float tW = subM1 * (h - 1);
        float tH = subM1 * (w - 1);
        float invTH = 1f / tH;
        float invTW = 1f / tW;

        float[] norms = new float[sub * 4];
        for (int i = 0; i < sub; i++) {
            float n = i / subM1;
            int idx = i * 4;
            norms[idx] = n * n * n;
            norms[idx + 1] = n * n;
            norms[idx + 2] = n;
            norms[idx + 3] = 1f;
        }

        float[] verts = new float[vw * vh * 7];
        for (int x = 0; x < w - 1; x++) {
            for (int y = 0; y < h - 1; y++) {
                CP p00 = cps[x][y];
                CP p01 = cps[x][y + 1];
                CP p10 = cps[x + 1][y];
                CP p11 = cps[x + 1][y + 1];

                float[] preX = precompute(meshCoefficients(p00, p01, p10, p11, true));
                float[] preY = precompute(meshCoefficients(p00, p01, p10, p11, false));

                float sX = x / (float) (w - 1);
                float sY = y / (float) (h - 1);
                int baseVx = y * sub;
                int baseVy = x * sub;

                for (int u = 0; u < sub; u++) {
                    int uIdx = u * 4;
                    float[] uRow = {norms[uIdx], norms[uIdx + 1], norms[uIdx + 2], norms[uIdx + 3]};
                    float[] tmpX = transform(uRow, preX);
                    float[] tmpY = transform(uRow, preY);
                    for (int v = 0; v < sub; v++) {
                        int vIdx = v * 4;
                        float v0 = norms[vIdx];
                        float v1 = norms[vIdx + 1];
                        float v2 = norms[vIdx + 2];
                        float v3 = norms[vIdx + 3];
                        float px = v0 * tmpX[0] + v1 * tmpX[1] + v2 * tmpX[2] + v3 * tmpX[3];
                        float py = v0 * tmpY[0] + v1 * tmpY[1] + v2 * tmpY[2] + v3 * tmpY[3];

                        int vx = baseVx + u;
                        int vy = baseVy + v;
                        int o = (vx + vy * vw) * 7;
                        verts[o] = px;
                        verts[o + 1] = py;
                        verts[o + 2] = 1f;
                        verts[o + 3] = 1f;
                        verts[o + 4] = 1f;
                        verts[o + 5] = sX + v * invTH;
                        verts[o + 6] = 1f - sY - u * invTW;
                    }
                }
            }
        }

        short[] indices = new short[(vw - 1) * (vh - 1) * 6];
        int k = 0;
        for (int y = 0; y < vh - 1; y++) {
            for (int x = 0; x < vw - 1; x++) {
                indices[k++] = (short) (y * vw + x);
                indices[k++] = (short) (y * vw + x + 1);
                indices[k++] = (short) ((y + 1) * vw + x);
                indices[k++] = (short) (y * vw + x + 1);
                indices[k++] = (short) ((y + 1) * vw + x + 1);
                indices[k++] = (short) ((y + 1) * vw + x);
            }
        }
        return new MeshData(verts, indices);
    }

    /** Same layout as AMLL meshCoefficients(): row x/y position + tangent terms. */
    private static float[] meshCoefficients(CP p00, CP p01, CP p10, CP p11, boolean xAxis) {
        float l00 = xAxis ? p00.x : p00.y;
        float l01 = xAxis ? p01.x : p01.y;
        float l10 = xAxis ? p10.x : p10.y;
        float l11 = xAxis ? p11.x : p11.y;
        float u00 = xAxis ? p00.ux : p00.uy;
        float u01 = xAxis ? p01.ux : p01.uy;
        float u10 = xAxis ? p10.ux : p10.uy;
        float u11 = xAxis ? p11.ux : p11.uy;
        float v00 = xAxis ? p00.vx : p00.vy;
        float v01 = xAxis ? p01.vx : p01.vy;
        float v10 = xAxis ? p10.vx : p10.vy;
        float v11 = xAxis ? p11.vx : p11.vy;
        return new float[]{
                l00, l01, v00, v01,
                l10, l11, v10, v11,
                u00, u01, 0, 0,
                u10, u11, 0, 0
        };
    }

    private static float[] precompute(float[] m) {
        float[] mt = transpose(m);
        float[] mh = multiply(mt, H);
        return multiply(transpose(H), mh);
    }

    private static float[] transpose(float[] a) {
        float[] out = new float[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                out[i * 4 + j] = a[j * 4 + i];
            }
        }
        return out;
    }

    /** gl-matrix Mat4.multiply semantics: out = a * b. */
    private static float[] multiply(float[] a, float[] b) {
        float[] out = new float[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + i] * b[j * 4 + k];
                }
                out[j * 4 + i] = sum;
            }
        }
        return out;
    }

    /** gl-matrix Vec4.transformMat4 semantics. */
    private static float[] transform(float[] v, float[] m) {
        float[] out = new float[4];
        for (int i = 0; i < 4; i++) {
            out[i] = v[0] * m[i] + v[1] * m[4 + i] + v[2] * m[8 + i] + v[3] * m[12 + i];
        }
        return out;
    }
}
