package com.exodidio.tune.backdrop;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 两种 AMLL 背景模式的移植：
 * 1) Mesh Gradient（旧版 iPad 风）：32x32 调色模糊纹理 + BHP 网格形变 + 缓旋 + 音量呼吸。
 * 2) Titanium 流沙（新版风格）：OkLab 四色 + 解析高度场沙粒 + 金属高光/闪粉 + 锋线迁移。
 * 默认值均取 AMLL：flowSpeed=1、FPS=30、renderScale=0.75；mesh 低频÷10，titanium 低频 0..1。
 */
final class FluidRenderer {

    interface Host {
        void queueEvent(Runnable runnable);
        void requestRender();
    }

    interface Listener {
        void onAlbumApplied();
    }

    private static final String VERTEX_SHADER =
            "precision mediump float;\n" +
            "attribute vec2 a_pos;\n" +
            "attribute vec3 a_color;\n" +
            "attribute vec2 a_uv;\n" +
            "varying vec3 v_color;\n" +
            "varying vec2 v_uv;\n" +
            "uniform float u_aspect;\n" +
            "void main() {\n" +
            "  v_color = a_color;\n" +
            "  v_uv = a_uv;\n" +
            "  vec2 pos = a_pos;\n" +
            "  if (u_aspect > 1.0) {\n" +
            "    pos.y *= u_aspect;\n" +
            "  } else {\n" +
            "    pos.x /= u_aspect;\n" +
            "  }\n" +
            "  gl_Position = vec4(pos, 0.0, 1.0);\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec3 v_color;\n" +
            "varying vec2 v_uv;\n" +
            "uniform sampler2D u_texture;\n" +
            "uniform float u_volume;\n" +
            "uniform float u_alpha;\n" +
            "uniform float u_sinAngle;\n" +
            "uniform float u_cosAngle;\n" +
            "const float INV_255 = 1.0 / 255.0;\n" +
            "const float HALF_INV_255 = 0.5 / 255.0;\n" +
            "const float GRADIENT_NOISE_A = 52.9829189;\n" +
            "const vec2 GRADIENT_NOISE_B = vec2(0.06711056, 0.00583715);\n" +
            "float gradientNoise(in vec2 uv) {\n" +
            "  return fract(GRADIENT_NOISE_A * fract(dot(uv, GRADIENT_NOISE_B)));\n" +
            "}\n" +
            "void main() {\n" +
            "  float volumeEffect = u_volume * 2.0;\n" +
            "  float dither = INV_255 * gradientNoise(gl_FragCoord.xy) - HALF_INV_255;\n" +
            "  vec2 centeredUV = v_uv - vec2(0.2);\n" +
            "  vec2 rotatedUV = vec2(\n" +
            "    u_cosAngle * centeredUV.x - u_sinAngle * centeredUV.y,\n" +
            "    u_sinAngle * centeredUV.x + u_cosAngle * centeredUV.y\n" +
            "  );\n" +
            "  vec2 finalUV = rotatedUV * max(0.001, 1.0 - volumeEffect) + vec2(0.5);\n" +
            "  vec4 result = texture2D(u_texture, finalUV);\n" +
            "  float alphaVolumeFactor = u_alpha * max(0.5, 1.0 - u_volume * 0.5);\n" +
            "  result.rgb *= v_color * alphaVolumeFactor;\n" +
            "  result.a *= alphaVolumeFactor;\n" +
            "  result.rgb += vec3(dither);\n" +
            "  float dist = distance(v_uv, vec2(0.5));\n" +
            "  float vignette = smoothstep(0.8, 0.3, dist);\n" +
            "  float mask = 0.6 + vignette * 0.4;\n" +
            "  result.rgb *= mask;\n" +
            "  gl_FragColor = result;\n" +
            "}\n";

    private static final String QUAD_VERTEX_SHADER =
            "attribute vec2 a_pos;\n" +
            "varying vec2 v_uv;\n" +
            "void main() {\n" +
            "  v_uv = a_pos * 0.5 + 0.5;\n" +
            "  gl_Position = vec4(a_pos, 0.0, 1.0);\n" +
            "}\n";

    private static final String QUAD_FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 v_uv;\n" +
            "uniform sampler2D u_texture;\n" +
            "uniform float u_alpha;\n" +
            "void main() {\n" +
            "  vec4 color = texture2D(u_texture, v_uv);\n" +
            // 网格层自身的 alpha 约 0.96，直接相乘会让两层叠加时发暗、发灰；
            // 交叠完全由 u_alpha 控制，纹理按不透明处理。
            "  gl_FragColor = vec4(color.rgb, u_alpha);\n" +
            "}\n";

    /** AMLL titanium/titanium.frag.glsl 原样移植。 */
    private static final String TITANIUM_FRAGMENT_SHADER = """
            precision highp float;

            uniform vec2 u_resolution;
            uniform float u_colorTime;
            uniform float u_flowTime;
            uniform float u_flowSpeed;
            uniform vec3 u_colors[4];
            uniform vec3 u_seed;
            uniform vec3 u_crestMotion;
            uniform vec2 u_grainOffset;
            uniform float u_aspect;
            uniform vec4 u_metal;
            uniform float u_lyricScrim;
            uniform float u_lowFreq;
            uniform bool u_enableDithering;

            const vec3 LUMA_WEIGHT = vec3(0.2126, 0.7152, 0.0722);
            const vec3 TITANIUM_TINT = vec3(1.02, 1.0, 0.97);
            const vec3 HIGHLIGHT_COLOR = vec3(1.0, 0.99, 0.96);

            float encodeSrgb(float channel) {
                return channel <= 0.0031308
                    ? 12.92 * channel
                    : 1.055 * pow(max(channel, 0.0), 1.0 / 2.4) - 0.055;
            }

            vec3 okLabToSrgb(vec3 color) {
                float lRoot = color.x + 0.3963377774 * color.y + 0.2158037573 * color.z;
                float mRoot = color.x - 0.1055613458 * color.y - 0.0638541728 * color.z;
                float sRoot = color.x - 0.0894841775 * color.y - 1.2914855480 * color.z;
                float l = lRoot * lRoot * lRoot;
                float m = mRoot * mRoot * mRoot;
                float s = sRoot * sRoot * sRoot;
                vec3 linearColor = vec3(
                    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                    -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
                );
                return vec3(
                    encodeSrgb(linearColor.r),
                    encodeSrgb(linearColor.g),
                    encodeSrgb(linearColor.b)
                );
            }

            float interleavedGradientNoise(vec2 position) {
                return fract(
                    52.9829189 * fract(dot(position, vec2(0.06711056, 0.00583715)))
                );
            }

            vec4 hashGrid(vec2 cell) {
                vec4 p = fract(vec4(cell.xyxy) * vec4(0.1031, 0.1030, 0.0973, 0.1099));
                p += dot(p, p.wzxy + 33.33);
                return fract((p.xxyz + p.yzzw) * p.zywx);
            }

            const float MICRO_CELLS = 256.0;
            const float HASH_PERIOD = 256.0;
            const float GLINT_CELLS = 140.0;
            const float SPARKLE_THRESHOLD = 0.95;
            const float BRIGHT_FACE_PARTICLE_GAIN = 0.6;
            const float BRIGHT_FACE_RAMP_START = 0.3;
            const float BRIGHT_FACE_RAMP_END = 0.6;

            vec4 sampleSandLayer(
                vec2 point,
                float scale,
                vec2 shift,
                vec2 flowOffset,
                vec3 surfaceNormal,
                vec3 halfDir,
                float gate
            ) {
                vec2 warped =
                    point +
                        0.08 *
                        vec2(
                            sin(point.y * 2.7 + u_seed.x * 3.0),
                            cos(point.x * 2.3 + u_seed.y * 2.0)
                        );
                vec2 base = warped * (scale * MICRO_CELLS);
                base +=
                    vec2(sin(base.y * 0.11 + u_seed.x), cos(base.x * 0.09 + u_seed.y)) * 0.6;
                vec2 grid =
                    base +
                    shift +
                    mod(flowOffset * (scale * MICRO_CELLS), vec2(HASH_PERIOD));
                vec2 cell = floor(grid);
                vec2 local = fract(grid);
                vec4 rnd = hashGrid(mod(cell, HASH_PERIOD));
                float brightness = rnd.z;
                float presentRand = rnd.w;
                float sparkleRand = fract(rnd.z * 43.1 + rnd.w * 91.7);

                float threshold = mix(0.73, 0.37, gate);
                float present = smoothstep(threshold, threshold + 0.14, presentRand);
                vec2 offset = (rnd.xy - 0.5) * 0.6;
                float radius = (0.3 + 0.3 * brightness) * mix(0.55, 1.0, gate);
                float distanceToCenter =
                    length(local - 0.5 - offset) / max(radius, 1e-3);
                float mask = smoothstep(1.0, 0.5, distanceToCenter) * present;
                float pixelsPerCell = u_resolution.y / (scale * MICRO_CELLS);
                mask *= clamp(radius * pixelsPerCell, 0.2, 1.0);
                vec4 posRand = hashGrid(floor(point * GLINT_CELLS + shift * 97.0));
                vec3 grainNormal = normalize(
                    surfaceNormal +
                    vec3((rnd.xy - 0.5) * 0.8 + (posRand.xy - 0.5) * 1.4, 0.0)
                );
                float sparkle = step(SPARKLE_THRESHOLD, sparkleRand);
                float alignment = dot(grainNormal, halfDir);
                float light = smoothstep(
                    mix(0.74, 0.42, sparkle),
                    mix(0.94, 0.8, sparkle),
                    alignment
                );
                float glintGain = mix(0.9, 3.2, sparkle);

                return vec4(mask, light * mask * glintGain, brightness * mask, 0.0);
            }

            void main() {
                vec2 uv = gl_FragCoord.xy / u_resolution;
                vec2 point = vec2((uv.x - 0.5) * u_aspect, uv.y - 0.5);
                float colorPhase = u_colorTime * 6.28318530718;

                vec2 warp =
                    vec2(
                        sin(point.y * 1.7 + colorPhase + u_seed.x),
                        cos(point.x * 1.4 - colorPhase * 2.0 + u_seed.y)
                    ) * 0.08;
                vec2 blended = point + warp;
                float blendX = smoothstep(-0.6, 0.6, blended.x);
                float blendY = smoothstep(-0.6, 0.6, blended.y);
                vec3 okLab = mix(
                    mix(u_colors[2], u_colors[3], blendX),
                    mix(u_colors[0], u_colors[1], blendX),
                    blendY
                );
                vec3 base = okLabToSrgb(okLab);

                float luma = dot(base, LUMA_WEIGHT);
                base += TITANIUM_TINT * 0.02 * u_metal.z;
                vec3 color = mix(base, vec3(luma) * TITANIUM_TINT, u_metal.w);

                vec3 lightDir = normalize(
                    vec3(0.18 + 0.3 * sin(colorPhase), 0.8, 0.55)
                );
                vec3 halfDir = normalize(lightDir + vec3(0.0, 0.0, 1.0));

                float f1 =
                    point.y -
                    (
                        0.45 * sin(point.x * 1.6 + u_seed.x + u_crestMotion.x) +
                        0.10 * sin(point.x * 3.4 + u_seed.y + u_crestMotion.y)
                    ) -
                    u_crestMotion.z;
                float slope1 =
                    -0.45 * 1.6 * cos(point.x * 1.6 + u_seed.x + u_crestMotion.x) -
                    0.10 * 3.4 * cos(point.x * 3.4 + u_seed.y + u_crestMotion.y);

                float crest = f1;
                float crestProximity = exp(-crest * crest * 6.0);

                const float RIDGE_WIDTH = 0.018;
                float ridgeHeight =
                    0.55 + 0.15 * sin(point.x * 1.1 + u_seed.z * 4.0);
                float ridgeSlope =
                    -ridgeHeight * crest /
                    sqrt(crest * crest + RIDGE_WIDTH * RIDGE_WIDTH);
                float duneX = point.x * 1.8 + u_seed.x * 3.0;
                float duneY = point.y * 2.4 + point.x * 0.6 + u_seed.y * 5.0;
                float dhdx =
                    ridgeSlope * (-slope1) +
                    0.10 * 1.8 * cos(duneX) +
                    0.10 * 0.6 * cos(duneY);
                float dhdy = ridgeSlope + 0.10 * 2.4 * cos(duneY);
                vec3 surfaceNormal = normalize(vec3(-dhdx, -dhdy, 1.0));

                float diffuse = max(dot(surfaceNormal, lightDir), 0.0);
                color *= 0.16 + 0.95 * diffuse;
                color *= mix(1.0, mix(0.48, 1.0, crestProximity), diffuse);
                color += TITANIUM_TINT * (diffuse * 0.05 + (1.0 - diffuse) * 0.015) * u_metal.z;
                float lightLevel = clamp(
                    diffuse * mix(0.35, 1.0, crestProximity),
                    0.0,
                    1.0
                );
                float grainGate = 0.1 + 0.9 * lightLevel * lightLevel;
                grainGate *= mix(0.7, 1.0, smoothstep(0.06, 0.45, luma));

                float centerSlope =
                    -0.45 * 1.6 * cos(u_seed.x) - 0.10 * 3.4 * cos(u_seed.y);
                vec2 tangent = normalize(vec2(1.0, -centerSlope));
                float directionSign = step(0.0, f1) * 2.0 - 1.0;
                const float FINE_SPEED = 1.35;
                vec2 flowOffset =
                    tangent * directionSign * (u_flowTime * u_flowSpeed);
                vec2 grainPoint = point + u_grainOffset;

                vec4 coarse = sampleSandLayer(
                    grainPoint,
                    0.9,
                    vec2(0.0),
                    flowOffset,
                    surfaceNormal,
                    halfDir,
                    grainGate
                );
                vec4 fine = sampleSandLayer(
                    grainPoint,
                    2.9,
                    vec2(0.37, 0.61),
                    flowOffset * FINE_SPEED,
                    surfaceNormal,
                    halfDir,
                    grainGate
                );
                float sandMask = max(coarse.x, fine.x);
                float glint = max(coarse.y, fine.y);
                float grainBrightness =
                    (coarse.z + fine.z) / max(coarse.x + fine.x, 1e-3);

                float grainGain =
                    mix(0.34, 1.0, smoothstep(0.0, 0.6, diffuse)) *
                    mix(0.32, 1.0, crestProximity);
                grainGain *= mix(1.0, 0.7, u_lyricScrim);

                float brightFaceGain = mix(
                    1.0,
                    BRIGHT_FACE_PARTICLE_GAIN,
                    smoothstep(
                        BRIGHT_FACE_RAMP_START,
                        BRIGHT_FACE_RAMP_END,
                        diffuse
                    )
                );

                color +=
                    HIGHLIGHT_COLOR *
                    sandMask *
                    (0.015 + 0.04 * grainBrightness) *
                    u_metal.x *
                    grainGain *
                    brightFaceGain;
                color +=
                    HIGHLIGHT_COLOR *
                    glint *
                    (0.65 + 0.35 * grainBrightness) *
                    (0.35 + 0.65 * diffuse) *
                    u_metal.y *
                    grainGain *
                    brightFaceGain;

                color *= 1.0 + (grainBrightness - 0.5) * 0.04 * u_metal.x;

                color *= mix(1.0, 0.86, u_lyricScrim);

                color *= 1.0 - 0.08 * u_lowFreq;

                color = color / (1.0 + max(color, 0.0) * (0.28 + 0.35 * u_lyricScrim));

                if (u_enableDithering) {
                    float dither = interleavedGradientNoise(gl_FragCoord.xy) - 0.5;
                    color += vec3(dither / 255.0);
                }

                gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
            """;

    private static final float TAU = (float) (Math.PI * 2);
    private static final float COLOR_CYCLE_SECONDS = 8f;
    private static final float FLOW_CYCLE_SECONDS = 4f;
    private static final float FLOW_LENGTH = 0.04f;
    private static final float LOW_FREQ_FLOW_GAIN = 0.35f;
    private static final float CREST_SHIFT_Y = 0.12f;
    private static final float PALETTE_TRANSITION_MS = 1400f;

    private static final class State {
        MeshBuilder.MeshData mesh;
        int vbo;
        int ibo;
        int indexCount;
        int textureId;
        float alpha;
    }

    private final Host host;
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "fluid-backdrop-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger presetGeneration = new AtomicInteger(0);
    private final AtomicInteger albumGeneration = new AtomicInteger(0);
    private final List<State> states = new ArrayList<>();
    private final Random rnd = new Random();
    private volatile boolean released = false;

    // 公共参数（AMLL 默认值）
    private volatile float flowSpeed = 1f;
    private volatile float volume = 1f;
    private volatile boolean staticMode = false;
    private volatile float renderScale = 0.75f;
    private volatile Presets.Preset currentPreset = Presets.APPLE_A;
    /** 0 = 网格，1 = Titanium 流沙。 */
    private volatile int rendererMode = 0;
    private volatile boolean forceRedraw = true;
    private volatile Listener listener;

    // Titanium 选项（TitaniumRenderer.defaultOptions）
    private volatile float tiGrain = 0.7f;
    private volatile float tiSparkle = 0.75f;
    private volatile float tiSheen = 0.5f;
    private volatile float tiMetalness = 0.35f;
    private volatile float tiCrestShiftMs = 2000f;
    private volatile boolean tiDithering = true;
    private volatile boolean tiLyricScrimTarget = false;

    private int program;
    private int quadProgram;
    private int titaniumProgram;
    private int attrPos;
    private int attrColor;
    private int attrUv;
    private int uniformAspect;
    private int uniformTexture;
    private int uniformVolume;
    private int uniformAlpha;
    private int uniformSin;
    private int uniformCos;
    private int quadAttrPos;
    private int quadUniformTexture;
    private int quadUniformAlpha;
    private int tAttrPos;
    private int tResolution;
    private int tColorTime;
    private int tFlowTime;
    private int tFlowSpeed;
    private int tColors;
    private int tSeed;
    private int tCrestMotion;
    private int tGrainOffset;
    private int tAspect;
    private int tMetal;
    private int tLyricScrim;
    private int tLowFreq;
    private int tDithering;
    private int quadBuffer;

    private int fbo;
    private int fboTexture;
    private int fboWidth;
    private int fboHeight;
    private int screenWidth;
    private int screenHeight;

    // 上下文重建（如从系统选图返回）后恢复当前封面用
    private volatile Bitmap lastAlbumBitmap;
    private volatile byte[] lastAlbumRgba;
    private volatile float[] lastAlbumPalette;
    private volatile boolean hasRealAlbum = false;

    private double frameTimeMs = 0.0;
    private double flowTimeSec = 0.0;
    private float smoothedVolume = 0f;
    private long lastRenderNanos = 0L;
    private boolean surfaceReady = false;

    // Titanium 状态
    private final float[] seed = new float[3];
    private final float[] grainOffset = new float[2];
    private final float[] crest = new float[3];
    private final float[] crestFrom = new float[3];
    private final float[] crestTo = new float[3];
    private float crestShiftElapsed = 0f;
    private final float[] colorBuffer = new float[Palette.COLOR_COUNT * 3];
    private final float[] colorFrom = new float[Palette.COLOR_COUNT * 3];
    private final float[] colorTo = new float[Palette.COLOR_COUNT * 3];
    private float paletteElapsed = PALETTE_TRANSITION_MS;
    private float lyricScrim = 0f;

    FluidRenderer(Host host) {
        this.host = host;
        for (int i = 0; i < seed.length; i++) {
            seed[i] = rnd.nextFloat() * TAU;
        }
        grainOffset[0] = rnd.nextFloat();
        grainOffset[1] = rnd.nextFloat();
        float[] defaults = Palette.defaultOkLab();
        System.arraycopy(defaults, 0, colorBuffer, 0, defaults.length);
        System.arraycopy(defaults, 0, colorFrom, 0, defaults.length);
        System.arraycopy(defaults, 0, colorTo, 0, defaults.length);
    }

    void release() {
        released = true;
        workerExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setFlowSpeed(float value) {
        flowSpeed = value;
        forceRedraw = true;
    }

    void setVolume(float value) {
        volume = value;
        forceRedraw = true;
    }

    void setStaticMode(boolean enabled) {
        staticMode = enabled;
        forceRedraw = true;
    }

    void setRenderScale(float scale) {
        renderScale = Math.max(0.1f, Math.min(1f, scale));
        forceRedraw = true;
    }

    /** 网格在后台线程构建，避免在 GL 线程上卡掉一两帧（换形变/换歌时的“一卡一卡”）。 */
    void setPreset(Presets.Preset preset) {
        currentPreset = preset;
        forceRedraw = true;
        if (surfaceReady && !released) {
            final int gen = presetGeneration.incrementAndGet();
            try {
                workerExecutor.execute(() -> {
                    if (released || gen != presetGeneration.get()) return;
                    MeshBuilder.MeshData mesh = MeshBuilder.build(preset);
                    if (released || gen != presetGeneration.get()) return;
                    host.queueEvent(() -> {
                        if (released || gen != presetGeneration.get()) return;
                        State latest = latestState();
                        if (latest != null) {
                            latest.mesh = mesh;
                            uploadMesh(latest);
                            forceRedraw = true;
                            host.requestRender();
                        }
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }
    }

    void setRendererMode(int mode) {
        rendererMode = mode;
        forceRedraw = true;
    }

    void setTitaniumMaterial(float grain, float sparkle, float sheen, float metalness) {
        tiGrain = grain;
        tiSparkle = sparkle;
        tiSheen = sheen;
        tiMetalness = metalness;
        forceRedraw = true;
    }

    void setTitaniumCrestShiftMs(float ms) {
        tiCrestShiftMs = Math.max(0f, ms);
    }

    void setTitaniumDithering(boolean enabled) {
        tiDithering = enabled;
        forceRedraw = true;
    }

    void setTitaniumLyricScrim(boolean enabled) {
        tiLyricScrimTarget = enabled;
        forceRedraw = true;
    }

    void setAlbum(Bitmap bitmap) {
        setAlbum(bitmap, currentPreset);
    }

    /** 后台线程处理封面与取色，GL 线程更新网格状态/调色板/锋线。 */
    void setAlbum(Bitmap bitmap, Presets.Preset preset) {
        currentPreset = preset;
        if (released) return;
        final int gen = albumGeneration.incrementAndGet();
        try {
            workerExecutor.execute(() -> {
                if (released || gen != albumGeneration.get()) return;
                byte[] rgba = AlbumProcessor.process(bitmap);
                float[] palette = Palette.extractOkLabOrdered(bitmap);
                // 网格在后台线程构建，GL 线程只上传缓冲，换歌时不掉帧
                MeshBuilder.MeshData mesh = MeshBuilder.build(preset);
                if (released || gen != albumGeneration.get()) return;
                final boolean firstReal = !hasRealAlbum;
                hasRealAlbum = true;
                lastAlbumBitmap = bitmap;
                lastAlbumRgba = rgba;
                lastAlbumPalette = palette;
                host.queueEvent(() -> {
                    if (released || gen != albumGeneration.get()) return;
                    if (firstReal) {
                        for (State old : states) {
                            disposeState(old);
                        }
                        states.clear();
                        addAlbumState(rgba, mesh);
                        applyPaletteImmediate(palette);
                    } else {
                        addAlbumState(rgba, mesh);
                        applyPalette(palette);
                    }
                    rollCrestTarget();
                    host.requestRender();
                });
                Listener l = listener;
                if (l != null && !released) {
                    mainHandler.post(l::onAlbumApplied);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    void onSurfaceCreated() {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER, "a_pos", "a_color", "a_uv");
        quadProgram = buildProgram(QUAD_VERTEX_SHADER, QUAD_FRAGMENT_SHADER, "a_pos", null, null);
        titaniumProgram = buildProgram(QUAD_VERTEX_SHADER, TITANIUM_FRAGMENT_SHADER, "a_pos", null, null);
        attrPos = GLES20.glGetAttribLocation(program, "a_pos");
        attrColor = GLES20.glGetAttribLocation(program, "a_color");
        attrUv = GLES20.glGetAttribLocation(program, "a_uv");
        uniformAspect = GLES20.glGetUniformLocation(program, "u_aspect");
        uniformTexture = GLES20.glGetUniformLocation(program, "u_texture");
        uniformVolume = GLES20.glGetUniformLocation(program, "u_volume");
        uniformAlpha = GLES20.glGetUniformLocation(program, "u_alpha");
        uniformSin = GLES20.glGetUniformLocation(program, "u_sinAngle");
        uniformCos = GLES20.glGetUniformLocation(program, "u_cosAngle");
        quadAttrPos = GLES20.glGetAttribLocation(quadProgram, "a_pos");
        quadUniformTexture = GLES20.glGetUniformLocation(quadProgram, "u_texture");
        quadUniformAlpha = GLES20.glGetUniformLocation(quadProgram, "u_alpha");

        tAttrPos = GLES20.glGetAttribLocation(titaniumProgram, "a_pos");
        tResolution = GLES20.glGetUniformLocation(titaniumProgram, "u_resolution");
        tColorTime = GLES20.glGetUniformLocation(titaniumProgram, "u_colorTime");
        tFlowTime = GLES20.glGetUniformLocation(titaniumProgram, "u_flowTime");
        tFlowSpeed = GLES20.glGetUniformLocation(titaniumProgram, "u_flowSpeed");
        tColors = uniformLocation(titaniumProgram, "u_colors[0]", "u_colors");
        tSeed = GLES20.glGetUniformLocation(titaniumProgram, "u_seed");
        tCrestMotion = GLES20.glGetUniformLocation(titaniumProgram, "u_crestMotion");
        tGrainOffset = GLES20.glGetUniformLocation(titaniumProgram, "u_grainOffset");
        tAspect = GLES20.glGetUniformLocation(titaniumProgram, "u_aspect");
        tMetal = GLES20.glGetUniformLocation(titaniumProgram, "u_metal");
        tLyricScrim = GLES20.glGetUniformLocation(titaniumProgram, "u_lyricScrim");
        tLowFreq = GLES20.glGetUniformLocation(titaniumProgram, "u_lowFreq");
        tDithering = GLES20.glGetUniformLocation(titaniumProgram, "u_enableDithering");

        int[] buffers = new int[1];
        GLES20.glGenBuffers(1, buffers, 0);
        quadBuffer = buffers[0];
        FloatBuffer quad = ByteBuffer.allocateDirect(6 * 2 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1}).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 6 * 2 * 4, quad, GLES20.GL_STATIC_DRAW);

        forceRedraw = true;
        surfaceReady = true;

        // 上下文重建：旧的 GL 句柄（含 FBO 与纹理）已失效，必须重置并从当前封面恢复
        fbo = 0;
        fboTexture = 0;
        fboWidth = 0;
        fboHeight = 0;
        lastRenderNanos = 0L;
        states.clear();

        byte[] rgba = lastAlbumRgba;
        float[] palette = lastAlbumPalette;
        Bitmap cover = lastAlbumBitmap;
        if (rgba == null || palette == null || cover == null) {
            cover = defaultCover();
            rgba = AlbumProcessor.process(cover);
            palette = Palette.extractOkLabOrdered(cover);
            lastAlbumBitmap = cover;
            lastAlbumRgba = rgba;
            lastAlbumPalette = palette;
        }
        addAlbumState(rgba, MeshBuilder.build(currentPreset));
        applyPalette(palette);
        rollCrestTarget();
        host.requestRender();
    }

    void onSurfaceChanged(int width, int height) {
        screenWidth = width;
        screenHeight = height;
        GLES20.glViewport(0, 0, width, height);
        forceRedraw = true;
    }

    void onDrawFrame() {
        // 帧率由外层 Choreographer 调度，这里每次调用都完整绘制：
        // 绝不能“跳过绘制但仍交换缓冲”，否则部分驱动会显示未定义的后台缓冲，看起来在闪烁。
        long now = System.nanoTime();
        float deltaMs = lastRenderNanos == 0L ? 16f : (now - lastRenderNanos) / 1_000_000f;
        lastRenderNanos = now;
        deltaMs = Math.min(deltaMs, 100f);
        forceRedraw = false;

        if (!staticMode) {
            frameTimeMs += deltaMs * flowSpeed;
            float targetVolume = rendererMode == 0 ? volume / 10f : volume;
            smoothedVolume += (targetVolume - smoothedVolume) * Math.min(1f, deltaMs / 100f);
            flowTimeSec += (deltaMs / 1000f) * flowSpeed * (1f + LOW_FREQ_FLOW_GAIN * smoothedVolume);
            advanceCrossfade(deltaMs);
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }
        if (!ensureFbo()) {
            return;
        }

        if (rendererMode == 1) {
            drawTitanium(staticMode ? 0f : deltaMs);
        } else {
            drawMeshGradient();
        }
    }

    private void drawMeshGradient() {
        if (states.isEmpty()) {
            return;
        }
        float uTime = (float) (frameTimeMs / 10000.0);
        float angle = (uTime + smoothedVolume) * 2f;
        for (State state : states) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glViewport(0, 0, fboWidth, fboHeight);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            drawMesh(state, angle);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, screenWidth, screenHeight);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFuncSeparate(
                    GLES20.GL_SRC_ALPHA,
                    GLES20.GL_ONE_MINUS_SRC_ALPHA,
                    GLES20.GL_ONE,
                    GLES20.GL_ONE_MINUS_SRC_ALPHA);
            drawFboQuad(easeInOutSine(Math.min(1f, state.alpha)));
        }
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void drawTitanium(float deltaMs) {
        if (paletteElapsed < PALETTE_TRANSITION_MS) {
            paletteElapsed = Math.min(PALETTE_TRANSITION_MS, paletteElapsed + deltaMs);
        }
        updateColorBuffer();
        if (crestShiftElapsed < tiCrestShiftMs) {
            crestShiftElapsed = Math.min(tiCrestShiftMs, crestShiftElapsed + deltaMs);
        }
        updateCrestMotion();
        float scrimStep = Math.min(1f, deltaMs / 350f);
        lyricScrim += ((tiLyricScrimTarget ? 1f : 0f) - lyricScrim) * scrimStep;

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, fboWidth, fboHeight);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(titaniumProgram);
        GLES20.glUniform2f(tResolution, fboWidth, fboHeight);
        GLES20.glUniform1f(tAspect, fboWidth / (float) Math.max(1, fboHeight));
        GLES20.glUniform1f(tColorTime, (float) ((frameTimeMs / 1000.0 / COLOR_CYCLE_SECONDS) % 1.0));
        GLES20.glUniform1f(tFlowTime, (float) flowTimeSec);
        GLES20.glUniform1f(tFlowSpeed, FLOW_LENGTH / FLOW_CYCLE_SECONDS);
        GLES20.glUniform3fv(tColors, Palette.COLOR_COUNT, colorBuffer, 0);
        GLES20.glUniform3fv(tSeed, 1, seed, 0);
        GLES20.glUniform3fv(tCrestMotion, 1, crest, 0);
        GLES20.glUniform2fv(tGrainOffset, 1, grainOffset, 0);
        GLES20.glUniform4f(tMetal, tiGrain, tiSparkle, tiSheen, tiMetalness);
        GLES20.glUniform1f(tLyricScrim, lyricScrim);
        GLES20.glUniform1f(tLowFreq, smoothedVolume);
        GLES20.glUniform1i(tDithering, tiDithering ? 1 : 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer);
        GLES20.glEnableVertexAttribArray(tAttrPos);
        GLES20.glVertexAttribPointer(tAttrPos, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glDisableVertexAttribArray(tAttrPos);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, screenWidth, screenHeight);
        drawFboQuad(1f);
    }

    private State latestState() {
        return states.isEmpty() ? null : states.get(states.size() - 1);
    }

    /** 供外层帧调度：换歌淡入期间按屏幕刷新率逐帧请求。 */
    boolean isCrossfading() {
        State latest = latestState();
        return states.size() > 1 || (latest != null && latest.alpha < 1.1f);
    }

    private void addAlbumState(byte[] rgba, MeshBuilder.MeshData meshData) {
        State state = new State();
        state.mesh = meshData;
        // 没有旧状态时直接全量显示，避免重建/首次加载先黑屏淡入
        state.alpha = states.isEmpty() ? 1.1f : 0f;
        int[] buffers = new int[2];
        GLES20.glGenBuffers(2, buffers, 0);
        state.vbo = buffers[0];
        state.ibo = buffers[1];
        uploadMesh(state);
        state.textureId = createTexture(rgba);
        states.add(state);
        forceRedraw = true;
    }

    /** 新封面 alpha 由 0 升到 1.1（约 550ms），到顶后清掉旧状态。 */
    private void advanceCrossfade(float deltaMs) {
        State latest = latestState();
        if (latest == null) {
            return;
        }
        latest.alpha = Math.min(1.1f, latest.alpha + deltaMs / 500f);
        if (latest.alpha >= 1.1f && states.size() > 1) {
            List<State> removed = new ArrayList<>(states.subList(0, states.size() - 1));
            states.removeAll(removed);
            for (State state : removed) {
                disposeState(state);
            }
        }
    }

    // ---- Titanium 调色板 / 锋线 ----

    private void applyPalette(float[] next) {
        updateColorBuffer();
        System.arraycopy(colorBuffer, 0, colorFrom, 0, colorBuffer.length);
        System.arraycopy(next, 0, colorTo, 0, colorTo.length);
        paletteElapsed = 0f;
        forceRedraw = true;
    }

    private void applyPaletteImmediate(float[] next) {
        System.arraycopy(next, 0, colorFrom, 0, colorFrom.length);
        System.arraycopy(next, 0, colorTo, 0, colorTo.length);
        System.arraycopy(next, 0, colorBuffer, 0, colorBuffer.length);
        paletteElapsed = PALETTE_TRANSITION_MS;
        forceRedraw = true;
    }

    private void updateColorBuffer() {
        float progress = Math.min(1f, paletteElapsed / PALETTE_TRANSITION_MS);
        float eased = 0.5f - 0.5f * (float) Math.cos(Math.PI * progress);
        for (int i = 0; i < colorBuffer.length; i++) {
            colorBuffer[i] = colorFrom[i] + (colorTo[i] - colorFrom[i]) * eased;
        }
    }

    private void rollCrestTarget() {
        updateCrestMotion();
        System.arraycopy(crest, 0, crestFrom, 0, 3);
        for (int i = 0; i < 2; i++) {
            float sweep = (0.25f + rnd.nextFloat() * 0.6f) * TAU;
            crestTo[i] = crest[i] + (rnd.nextBoolean() ? -sweep : sweep);
        }
        crestTo[2] = (rnd.nextFloat() - 0.5f) * 2f * CREST_SHIFT_Y;
        crestShiftElapsed = 0f;
    }

    private void updateCrestMotion() {
        if (tiCrestShiftMs <= 0f || crestShiftElapsed >= tiCrestShiftMs) {
            System.arraycopy(crestTo, 0, crest, 0, 3);
            for (int i = 0; i < 2; i++) {
                crest[i] = ((crest[i] % TAU) + TAU) % TAU;
            }
            System.arraycopy(crest, 0, crestTo, 0, 3);
            return;
        }
        float t = crestShiftElapsed / tiCrestShiftMs;
        float eased = t * t * t * (t * (t * 6f - 15f) + 10f);
        for (int i = 0; i < 3; i++) {
            crest[i] = crestFrom[i] + (crestTo[i] - crestFrom[i]) * eased;
        }
    }

    // ---- GL helpers ----

    private boolean ensureFbo() {
        int tw = Math.max(1, Math.round(screenWidth * renderScale));
        int th = Math.max(1, Math.round(screenHeight * renderScale));
        if (fbo != 0 && fboTexture != 0 && tw == fboWidth && th == fboHeight) {
            return true;
        }
        fboWidth = tw;
        fboHeight = th;
        if (fbo == 0) {
            int[] ids = new int[1];
            GLES20.glGenFramebuffers(1, ids, 0);
            fbo = ids[0];
        }
        if (fboTexture == 0) {
            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            fboTexture = ids[0];
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture);
        GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, tw, th, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboTexture, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return status == GLES20.GL_FRAMEBUFFER_COMPLETE;
    }

    private void drawMesh(State state, float angle) {
        if (state.textureId == 0 || state.mesh == null) {
            return;
        }
        GLES20.glUseProgram(program);
        GLES20.glUniform1f(uniformAspect, fboWidth / (float) Math.max(1, fboHeight));
        GLES20.glUniform1i(uniformTexture, 0);
        GLES20.glUniform1f(uniformVolume, smoothedVolume);
        GLES20.glUniform1f(uniformAlpha, 1f);
        GLES20.glUniform1f(uniformSin, (float) Math.sin(angle));
        GLES20.glUniform1f(uniformCos, (float) Math.cos(angle));

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, state.textureId);

        int stride = 7 * 4;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, state.vbo);
        GLES20.glEnableVertexAttribArray(attrPos);
        GLES20.glVertexAttribPointer(attrPos, 2, GLES20.GL_FLOAT, false, stride, 0);
        GLES20.glEnableVertexAttribArray(attrColor);
        GLES20.glVertexAttribPointer(attrColor, 3, GLES20.GL_FLOAT, false, stride, 2 * 4);
        GLES20.glEnableVertexAttribArray(attrUv);
        GLES20.glVertexAttribPointer(attrUv, 2, GLES20.GL_FLOAT, false, stride, 5 * 4);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, state.ibo);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, state.indexCount, GLES20.GL_UNSIGNED_SHORT, 0);
        GLES20.glDisableVertexAttribArray(attrPos);
        GLES20.glDisableVertexAttribArray(attrColor);
        GLES20.glDisableVertexAttribArray(attrUv);
    }

    private void drawFboQuad(float alpha) {
        GLES20.glUseProgram(quadProgram);
        GLES20.glUniform1i(quadUniformTexture, 0);
        GLES20.glUniform1f(quadUniformAlpha, alpha);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer);
        GLES20.glEnableVertexAttribArray(quadAttrPos);
        GLES20.glVertexAttribPointer(quadAttrPos, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glDisableVertexAttribArray(quadAttrPos);
    }

    private void uploadMesh(State state) {
        MeshBuilder.MeshData data = state.mesh;
        FloatBuffer vbuf = ByteBuffer.allocateDirect(data.vertices.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vbuf.put(data.vertices).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, state.vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.vertices.length * 4, vbuf, GLES20.GL_STATIC_DRAW);

        ShortBuffer ibuf = ByteBuffer.allocateDirect(data.indices.length * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer();
        ibuf.put(data.indices).position(0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, state.ibo);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, data.indices.length * 2, ibuf, GLES20.GL_STATIC_DRAW);
        state.indexCount = data.indices.length;
    }

    private int createTexture(byte[] rgba) {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        int textureId = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                AlbumProcessor.SIZE, AlbumProcessor.SIZE, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(rgba));
        // AMLL 使用 MIRRORED_REPEAT + LINEAR：流动时的镜像衔接就是由此产生的
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_MIRRORED_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_MIRRORED_REPEAT);
        return textureId;
    }

    private void disposeState(State state) {
        int[] buffers = {state.vbo, state.ibo};
        GLES20.glDeleteBuffers(2, buffers, 0);
        if (state.textureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{state.textureId}, 0);
        }
    }

    private static float easeInOutSine(float x) {
        return -(float) (Math.cos(Math.PI * Math.max(0f, Math.min(1f, x))) - 1) / 2f;
    }

    private static int uniformLocation(int program, String... names) {
        for (String name : names) {
            int location = GLES20.glGetUniformLocation(program, name);
            if (location >= 0) {
                return location;
            }
        }
        return -1;
    }

    private static int buildProgram(
            String vertexShader, String fragmentShader,
            String posAttr, String colorAttr, String uvAttr) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glBindAttribLocation(p, 0, posAttr);
        if (colorAttr != null) {
            GLES20.glBindAttribLocation(p, 1, colorAttr);
        }
        if (uvAttr != null) {
            GLES20.glBindAttribLocation(p, 2, uvAttr);
        }
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return p;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            throw new RuntimeException(
                    "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader)
                            + " line count " + source.split("\n").length);
        }
        return shader;
    }

    /** 默认封面：无图片时也能看到效果。 */
    private static Bitmap defaultCover() {
        Bitmap bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(
                0, 0, 128, 128,
                new int[]{0xFF2B1055, 0xFF7597DE, 0xFFB83B5E},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 128, 128, paint);
        paint.setShader(new RadialGradient(
                40, 40, 80, new int[]{0xCCFFC857, 0x00000000},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 128, 128, paint);
        paint.setShader(new RadialGradient(
                100, 96, 70, new int[]{0xCC00E5FF, 0x00000000},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 128, 128, paint);
        paint.setShader(new RadialGradient(
                70, 110, 60, new int[]{0xCCFF4D6D, 0x00000000},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 128, 128, paint);
        return bmp;
    }
}
