#include <aaudio/AAudio.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/mem.h>
#include <libswresample/swresample.h>
}

namespace {
    constexpr int kOutputChannels = 2;
    constexpr int kEqBands = 10;
    constexpr size_t kRingFrames = 240'000;
    constexpr size_t kOutRingFrames = 65'536;
    constexpr size_t kDefaultMaxOutAheadFrames = 16'384;
    constexpr int kNoSlot = -1;
    constexpr int kTransitionNone = 0;
    constexpr int kTransitionGaplessPromoted = 1;
    constexpr int kTransitionCrossfadeStarted = 2;
    constexpr int kFocusDuckFadeOutMs = 120;
    constexpr int kFocusDuckFadeInMs = 240;

    constexpr float next_focus_gain(float current, float target, int output_rate) {
        const int duration_ms = target < current ? kFocusDuckFadeOutMs : kFocusDuckFadeInMs;
        const float step = 1.0f / static_cast<float>(std::max(1, output_rate * duration_ms / 1000));
        return target < current ? std::max(target, current - step) : std::min(target, current + step);
    }

    static_assert(next_focus_gain(1.0f, 0.2f, 48'000) < 1.0f);
    static_assert(next_focus_gain(0.2f, 1.0f, 48'000) > 0.2f);

    constexpr bool should_promote_gapless(bool active_finished, bool has_preloaded) {
        return active_finished && has_preloaded;
    }

    static_assert(!should_promote_gapless(false, true));
    static_assert(should_promote_gapless(true, true));

    struct GlobalDspConfig {
        uint64_t generation = 0;
        float preamp_gain_db = 0.0f;
        float stereo_width = 1.0f;
        float eq_band_gains_db[kEqBands]{};
    };

    struct Biquad {
        bool active = false;
        float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
        float left_z1 = 0.0f, left_z2 = 0.0f, right_z1 = 0.0f, right_z2 = 0.0f;

        void reset_state() {
            left_z1 = left_z2 = right_z1 = right_z2 = 0.0f;
        }
    };

    inline float filter_sample(float input, float &z1, float &z2, const Biquad &filter) {
        const float output = filter.b0 * input + z1;
        z1 = filter.b1 * input - filter.a1 * output + z2;
        z2 = filter.b2 * input - filter.a2 * output;
        return output;
    }

    inline Biquad make_bs1770_stage1(int sample_rate) {
        const double fs = std::max(8'000, sample_rate);
        constexpr double f0 = 1681.974450955533;
        constexpr double g_db = 3.999843853973347;
        constexpr double q = 0.7071752369554196;
        const double vh = std::pow(10.0, g_db / 20.0);
        const double vb = std::pow(vh, 0.4996667741545416);
        const double k = std::tan(M_PI * f0 / fs);
        const double a0 = 1.0 + k / q + k * k;
        Biquad b{};
        b.active = true;
        b.b0 = static_cast<float>((vh + vb * k / q + k * k) / a0);
        b.b1 = static_cast<float>(2.0 * (k * k - vh) / a0);
        b.b2 = static_cast<float>((vh - vb * k / q + k * k) / a0);
        b.a1 = static_cast<float>(2.0 * (k * k - 1.0) / a0);
        b.a2 = static_cast<float>((1.0 - k / q + k * k) / a0);
        return b;
    }

    inline Biquad make_bs1770_stage2(int sample_rate) {
        const double fs = std::max(8'000, sample_rate);
        constexpr double f0 = 38.13547087602444;
        constexpr double q = 0.5003270373238773;
        const double k = std::tan(M_PI * f0 / fs);
        const double a0 = 1.0 + k / q + k * k;
        Biquad b{};
        b.active = true;
        b.b0 = static_cast<float>(1.0 / a0);
        b.b1 = static_cast<float>(-2.0 / a0);
        b.b2 = static_cast<float>(1.0 / a0);
        b.a1 = static_cast<float>(2.0 * (k * k - 1.0) / a0);
        b.a2 = static_cast<float>((1.0 - k / q + k * k) / a0);
        return b;
    }

    struct LiveLoudnessMeter {
        Biquad stage1{};
        Biquad stage2{};
        double block_energy = 0.0;
        float block_peak = 0.0f;
        int block_frames = 0;
        double est_energy = 0.0;
        uint64_t audible_blocks = 0;
        float tracked_peak = 0.0f;
        float cached_gain_db = 0.0f;

        void init(int sample_rate) {
            stage1 = make_bs1770_stage1(sample_rate);
            stage2 = make_bs1770_stage2(sample_rate);
            block_energy = 0.0;
            block_peak = 0.0f;
            block_frames = 0;
            est_energy = 0.0;
            audible_blocks = 0;
            tracked_peak = 0.0f;
            cached_gain_db = 0.0f;
        }

        void reset_filters() {
            stage1.reset_state();
            stage2.reset_state();
            block_energy = 0.0;
            block_peak = 0.0f;
            block_frames = 0;
        }

        float process_frame(float raw_l, float raw_r, float target_lufs, bool prevent_clip) {
            float kl = filter_sample(raw_l, stage1.left_z1, stage1.left_z2, stage1);
            kl = filter_sample(kl, stage2.left_z1, stage2.left_z2, stage2);
            float kr = filter_sample(raw_r, stage1.right_z1, stage1.right_z2, stage1);
            kr = filter_sample(kr, stage2.right_z1, stage2.right_z2, stage2);
            block_energy += static_cast<double>(kl) * kl + static_cast<double>(kr) * kr;
            const float frame_peak = std::max(std::abs(raw_l), std::abs(raw_r));
            if (frame_peak > block_peak) block_peak = frame_peak;
            block_frames += 1;
            if (block_frames >= 512) {
                const double mean_sq = block_energy / static_cast<double>(block_frames);
                const double block_lufs = -0.691 + 10.0 * std::log10(std::max(1e-10, mean_sq));
                tracked_peak = std::max(block_peak, tracked_peak * 0.996f);
                if (block_lufs > -55.0) {
                    audible_blocks += 1;
                    // Fast initial cumulative lock-in (~1s), then very slow (~8.5s) EMA
                    // so internal song dynamics (verses vs choruses) remain intact.
                    const double alpha = audible_blocks <= 100
                        ? (1.0 / static_cast<double>(audible_blocks))
                        : (1.0 / 800.0);
                    est_energy += alpha * (mean_sq - est_energy);
                    const double est_lufs = -0.691 + 10.0 * std::log10(std::max(1e-10, est_energy));
                    float gain_db = std::clamp(target_lufs - static_cast<float>(est_lufs), -12.0f, 6.0f);
                    if (prevent_clip && tracked_peak > 1e-4f) {
                        const float peak_db = 20.0f * std::log10(tracked_peak);
                        gain_db = std::min(gain_db, -peak_db - 0.5f);
                    }
                    cached_gain_db = gain_db;
                }
                block_energy = 0.0;
                block_peak = 0.0f;
                block_frames = 0;
            }
            return cached_gain_db;
        }
    };

    inline float soft_knee_limit(float sample) {
        constexpr float kThreshold = 0.8912509f; // -1.0 dBFS
        constexpr float kCeiling = 0.9885531f;   // -0.1 dBFS
        constexpr float kHeadroom = kCeiling - kThreshold;
        const float mag = std::abs(sample);
        if (mag <= kThreshold) return sample;
        const float limited = kThreshold + kHeadroom * std::tanh((mag - kThreshold) / kHeadroom);
        return std::copysign(limited, sample);
    }

    struct SourceSlot {
        AVFormatContext *format = nullptr;
        AVCodecContext *codec = nullptr;
        AVPacket *packet = nullptr;
        AVFrame *frame = nullptr;
        SwrContext *resampler = nullptr;
        int audio_stream = -1;
        int output_rate = 48'000;
        std::atomic<size_t> output_burst{1'024};
        std::atomic<size_t> max_out_ahead_frames{kDefaultMaxOutAheadFrames};
        std::vector<float> raw_ring = std::vector<float>(kRingFrames * kOutputChannels);
        std::vector<int64_t> raw_ring_pts_us = std::vector<int64_t>(kRingFrames, 0);
        std::atomic<size_t> raw_read_frame{0};
        std::atomic<size_t> raw_write_frame{0};
        std::atomic<bool> raw_input_exhausted{false};
        std::atomic<bool> raw_reader_active{false};
        std::atomic<uint64_t> decode_active_generation{0};
        std::vector<float> ring = std::vector<float>(kOutRingFrames * kOutputChannels);
        std::vector<int64_t> ring_pts_us = std::vector<int64_t>(kOutRingFrames, 0);
        std::atomic<size_t> read_frame{0};
        std::atomic<size_t> write_frame{0};
        std::atomic<bool> stopping{false};
        std::atomic<bool> opened{false};
        std::atomic<bool> input_exhausted{false};
        std::atomic<bool> finished{false};
        std::atomic<bool> seek_pending{false};
        std::atomic<uint64_t> seek_generation{0};
        std::atomic<uint64_t> active_seek_generation{0};
        std::atomic<bool> reader_active{false};
        std::atomic<int64_t> seek_us{0};
        std::atomic<int64_t> position_base_us{0};
        std::atomic<int64_t> current_pts_us{0};
        std::atomic<bool> has_rendered_pts{false};
        std::atomic<int64_t> rendered_frames{0};
        std::atomic<float> normalization_gain_db{0.0f};
        std::atomic<bool> has_static_analysis{false};
        std::atomic<bool> reset_meter_filters{false};
        LiveLoudnessMeter meter{};
        float current_applied_gain = 1.0f;
        float gain_smooth_alpha = 0.0005f;
        std::atomic<float> speed{1.0f};
        std::atomic<bool> rebuffering{false};
        std::atomic<uint32_t> underrun_count{0};
        std::atomic<size_t> rebuffer_target_frames{0};
        std::atomic<uint32_t> rebuffer_fade_in_rem{0};
        std::atomic<uint32_t> rebuffer_fade_in_total{0};
        std::atomic<int64_t> steady_play_frames{0};
        std::thread decode_thread;
        std::thread stretch_thread;
        struct CustomIoBridge *custom_io = nullptr;
    };

    constexpr int kAvioBufferSize = 64 * 1024;
    constexpr int kSeekModeSet = 0;
    constexpr int kSeekModeCur = 1;
    constexpr int kSeekModeEnd = 2;
    constexpr int kSeekModeSize = 3;

    struct CustomIoBridge {
        JavaVM *jvm = nullptr;
        jobject source_ref = nullptr;
        jmethodID read_method = nullptr;
        jmethodID seek_method = nullptr;
        jmethodID abort_seek_method = nullptr;
        jmethodID begin_seek_method = nullptr;
        jmethodID end_seek_method = nullptr;
        jmethodID cancel_method = nullptr;
        jmethodID close_method = nullptr;
        uint8_t *shared_buf = nullptr;
        int shared_buf_cap = 0;
        AVIOContext *avio = nullptr;
        std::atomic<bool> *stopping = nullptr;
        std::atomic<bool> cancelled{false};
    };

    struct ScopedJniEnv {
        JavaVM *jvm = nullptr;
        JNIEnv *env = nullptr;
        bool attached = false;

        explicit ScopedJniEnv(JavaVM *vm) : jvm(vm) {
            if (jvm == nullptr) return;
            const jint status = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
            if (status == JNI_EDETACHED) {
                JavaVMAttachArgs args{JNI_VERSION_1_6, "tune-ffmpeg-avio", nullptr};
                if (jvm->AttachCurrentThread(&env, &args) == JNI_OK) {
                    attached = true;
                } else {
                    env = nullptr;
                }
            } else if (status != JNI_OK) {
                env = nullptr;
            }
        }

        ~ScopedJniEnv() {
            if (attached && jvm != nullptr) {
                jvm->DetachCurrentThread();
            }
        }
    };

    struct ScopedJniThreadAttachment {
        JavaVM *jvm = nullptr;
        bool attached = false;

        explicit ScopedJniThreadAttachment(JavaVM *vm) : jvm(vm) {
            if (jvm == nullptr) return;
            JNIEnv *env = nullptr;
            if (jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
                JavaVMAttachArgs args{JNI_VERSION_1_6, "tune-ffmpeg-decode", nullptr};
                if (jvm->AttachCurrentThread(&env, &args) == JNI_OK) {
                    attached = true;
                }
            }
        }

        ~ScopedJniThreadAttachment() {
            if (attached && jvm != nullptr) {
                jvm->DetachCurrentThread();
            }
        }
    };

    bool is_io_cancelled(const CustomIoBridge *bridge) {
        if (bridge == nullptr) return true;
        if (bridge->cancelled.load(std::memory_order_acquire)) return true;
        return bridge->stopping != nullptr && bridge->stopping->load(std::memory_order_acquire);
    }

    int avio_interrupt_cb(void *opaque) {
        auto *bridge = static_cast<CustomIoBridge *>(opaque);
        return is_io_cancelled(bridge) ? 1 : 0;
    }

    int avio_read_packet(void *opaque, uint8_t *buf, int buf_size) {
        auto *bridge = static_cast<CustomIoBridge *>(opaque);
        if (is_io_cancelled(bridge) || buf == nullptr || buf_size <= 0) return AVERROR_EXIT;
        ScopedJniEnv jni(bridge->jvm);
        if (jni.env == nullptr || bridge->source_ref == nullptr || bridge->read_method == nullptr) {
            return AVERROR(EIO);
        }
        const int max_bytes = std::min(buf_size, bridge->shared_buf_cap);
        if (max_bytes <= 0) return AVERROR(EIO);
        const jint read_count = jni.env->CallIntMethod(bridge->source_ref, bridge->read_method, static_cast<jint>(max_bytes));
        if (jni.env->ExceptionCheck()) {
            jni.env->ExceptionClear();
            return AVERROR(EIO);
        }
        if (read_count == -2 || is_io_cancelled(bridge)) return AVERROR_EXIT;
        if (read_count == 0) return AVERROR_EOF;
        if (read_count < 0 || read_count > max_bytes) return AVERROR(EIO);
        std::memcpy(buf, bridge->shared_buf, static_cast<size_t>(read_count));
        return static_cast<int>(read_count);
    }

    int64_t avio_seek(void *opaque, int64_t offset, int whence) {
        auto *bridge = static_cast<CustomIoBridge *>(opaque);
        if (is_io_cancelled(bridge)) return AVERROR_EXIT;
        int mode = -1;
        if (whence == AVSEEK_SIZE) {
            mode = kSeekModeSize;
        } else {
            const int base_whence = whence & ~AVSEEK_FORCE;
            if (base_whence == SEEK_SET) mode = kSeekModeSet;
            else if (base_whence == SEEK_CUR) mode = kSeekModeCur;
            else if (base_whence == SEEK_END) mode = kSeekModeEnd;
            else return AVERROR(EINVAL);
        }
        ScopedJniEnv jni(bridge->jvm);
        if (jni.env == nullptr || bridge->source_ref == nullptr || bridge->seek_method == nullptr) {
            return AVERROR(EIO);
        }
        const jlong result = jni.env->CallLongMethod(
                bridge->source_ref,
                bridge->seek_method,
                static_cast<jlong>(offset),
                static_cast<jint>(mode));
        if (jni.env->ExceptionCheck()) {
            jni.env->ExceptionClear();
            return AVERROR(EIO);
        }
        if (result == -2 || is_io_cancelled(bridge)) return AVERROR_EXIT;
        if (result < 0) return AVERROR(EIO);
        return static_cast<int64_t>(result);
    }

    void abort_custom_io_seek(JNIEnv *env, SourceSlot &slot, uint64_t seek_gen) {
        CustomIoBridge *bridge = slot.custom_io;
        if (env == nullptr || bridge == nullptr || bridge->source_ref == nullptr ||
                bridge->abort_seek_method == nullptr) {
            return;
        }
        env->CallVoidMethod(bridge->source_ref, bridge->abort_seek_method, static_cast<jlong>(seek_gen));
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    void begin_custom_io_seek(SourceSlot &slot, uint64_t seek_gen) {
        CustomIoBridge *bridge = slot.custom_io;
        if (bridge == nullptr || bridge->source_ref == nullptr || bridge->begin_seek_method == nullptr) {
            return;
        }
        ScopedJniEnv jni(bridge->jvm);
        if (jni.env != nullptr) {
            jni.env->CallVoidMethod(bridge->source_ref, bridge->begin_seek_method, static_cast<jlong>(seek_gen));
            if (jni.env->ExceptionCheck()) jni.env->ExceptionClear();
        }
    }

    void end_custom_io_seek(SourceSlot &slot, uint64_t seek_gen) {
        CustomIoBridge *bridge = slot.custom_io;
        if (bridge == nullptr || bridge->source_ref == nullptr || bridge->end_seek_method == nullptr) {
            return;
        }
        ScopedJniEnv jni(bridge->jvm);
        if (jni.env != nullptr) {
            jni.env->CallVoidMethod(bridge->source_ref, bridge->end_seek_method, static_cast<jlong>(seek_gen));
            if (jni.env->ExceptionCheck()) jni.env->ExceptionClear();
        }
    }

    void cancel_custom_io(SourceSlot &slot) {
        CustomIoBridge *bridge = slot.custom_io;
        if (bridge == nullptr) return;
        bridge->cancelled.store(true, std::memory_order_release);
        ScopedJniEnv jni(bridge->jvm);
        if (jni.env != nullptr && bridge->source_ref != nullptr && bridge->cancel_method != nullptr) {
            jni.env->CallVoidMethod(bridge->source_ref, bridge->cancel_method);
            if (jni.env->ExceptionCheck()) jni.env->ExceptionClear();
        }
    }

    void free_custom_io(CustomIoBridge *&bridge) {
        if (bridge == nullptr) return;
        ScopedJniEnv jni(bridge->jvm);
        if (jni.env != nullptr && bridge->source_ref != nullptr) {
            if (bridge->close_method != nullptr) {
                jni.env->CallVoidMethod(bridge->source_ref, bridge->close_method);
                if (jni.env->ExceptionCheck()) jni.env->ExceptionClear();
            }
            jni.env->DeleteGlobalRef(bridge->source_ref);
            bridge->source_ref = nullptr;
        }
        if (bridge->avio != nullptr) {
            av_freep(&bridge->avio->buffer);
            avio_context_free(&bridge->avio);
        }
        delete bridge;
        bridge = nullptr;
    }

    CustomIoBridge *create_custom_io(
            JNIEnv *env,
            jobject source,
            jobject direct_buffer,
            std::atomic<bool> *stopping,
            std::string &error) {
        if (env == nullptr || source == nullptr || direct_buffer == nullptr) {
            error = "Invalid stream bridge";
            return nullptr;
        }
        auto *shared_buf = static_cast<uint8_t *>(env->GetDirectBufferAddress(direct_buffer));
        const jlong shared_cap = env->GetDirectBufferCapacity(direct_buffer);
        if (shared_buf == nullptr || shared_cap <= 0) {
            error = "Invalid direct stream buffer";
            return nullptr;
        }
        jclass source_cls = env->GetObjectClass(source);
        if (source_cls == nullptr) {
            error = "Missing stream source class";
            return nullptr;
        }
        jmethodID read_mid = env->GetMethodID(source_cls, "read", "(I)I");
        jmethodID seek_mid = env->GetMethodID(source_cls, "seek", "(JI)J");
        jmethodID abort_seek_mid = env->GetMethodID(source_cls, "abortReadForSeek", "(J)V");
        jmethodID begin_seek_mid = env->GetMethodID(source_cls, "beginSeek", "(J)V");
        jmethodID end_seek_mid = env->GetMethodID(source_cls, "endSeek", "(J)V");
        jmethodID cancel_mid = env->GetMethodID(source_cls, "cancel", "()V");
        jmethodID close_mid = env->GetMethodID(source_cls, "close", "()V");
        env->DeleteLocalRef(source_cls);
        if (env->ExceptionCheck() || read_mid == nullptr || seek_mid == nullptr ||
                abort_seek_mid == nullptr || begin_seek_mid == nullptr ||
                end_seek_mid == nullptr || cancel_mid == nullptr || close_mid == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            error = "Stream source methods are missing";
            return nullptr;
        }
        JavaVM *jvm = nullptr;
        if (env->GetJavaVM(&jvm) != JNI_OK || jvm == nullptr) {
            error = "Unable to resolve JavaVM";
            return nullptr;
        }
        auto *bridge = new CustomIoBridge();
        bridge->jvm = jvm;
        bridge->source_ref = env->NewGlobalRef(source);
        bridge->read_method = read_mid;
        bridge->seek_method = seek_mid;
        bridge->abort_seek_method = abort_seek_mid;
        bridge->begin_seek_method = begin_seek_mid;
        bridge->end_seek_method = end_seek_mid;
        bridge->cancel_method = cancel_mid;
        bridge->close_method = close_mid;
        bridge->shared_buf = shared_buf;
        bridge->shared_buf_cap = static_cast<int>(std::min<jlong>(shared_cap, kAvioBufferSize));
        bridge->stopping = stopping;
        if (bridge->source_ref == nullptr) {
            free_custom_io(bridge);
            error = "Unable to pin stream source";
            return nullptr;
        }
        auto *avio_buf = static_cast<unsigned char *>(av_malloc(kAvioBufferSize));
        if (avio_buf == nullptr) {
            free_custom_io(bridge);
            error = "Out of memory allocating AVIO buffer";
            return nullptr;
        }
        bridge->avio = avio_alloc_context(
                avio_buf,
                kAvioBufferSize,
                0,
                bridge,
                avio_read_packet,
                nullptr,
                avio_seek);
        if (bridge->avio == nullptr) {
            av_free(avio_buf);
            free_custom_io(bridge);
            error = "Unable to allocate AVIOContext";
            return nullptr;
        }
        return bridge;
    }

    struct PlaybackEngine {
        SourceSlot slots[2];
        AAudioStream *stream = nullptr;
        std::atomic<bool> playing{false};
        std::atomic<float> focus_gain{1.0f};
        float applied_focus_gain = 1.0f;
        std::atomic<bool> norm_enabled{false};
        std::atomic<float> norm_target_lufs{-14.0f};
        std::atomic<bool> norm_prevent_clip{true};
        std::atomic<bool> output_disconnected{false};
        std::atomic<int> active_slot{kNoSlot};
        std::atomic<int> preloaded_slot{kNoSlot};
        std::atomic<int> outgoing_slot{kNoSlot};
        std::atomic<bool> crossfading{false};
        std::atomic<int64_t> fade_frames_total{0};
        std::atomic<int64_t> fade_frames_rendered{0};
        std::atomic<int> transition_event{kTransitionNone};
        std::atomic<int> callback_depth{0};
        // Config instances are retired off the audio callback once callback_depth == 0.
        // The callback only atomically reads a pointer; no allocation, ref-counting or locking occurs.
        std::mutex dsp_write_mutex;
        uint64_t next_dsp_generation = 0;
        std::vector<std::unique_ptr<GlobalDspConfig>> dsp_configs;
        std::atomic<const GlobalDspConfig *> dsp_config{nullptr};
        uint64_t applied_dsp_generation = 0;
        std::array<Biquad, kEqBands> eq_filters{};
        int output_rate = 48'000;

        // Feature E: Lock-free Cassette time-warp & Radio crosstalk synthesis state.
        static constexpr size_t kCassetteDelayFrames = 4'096;
        std::vector<float> cassette_delay_ring = std::vector<float>(kCassetteDelayFrames * kOutputChannels, 0.0f);
        size_t cassette_delay_write = 0;
        double cassette_phase_u = 0.0;
        float cassette_active_pitch = 1.0f;
        float cassette_hiss_lp_l = 0.0f;
        float cassette_hiss_lp_r = 0.0f;
        float cassette_tone_lp_l = 0.0f;
        float cassette_tone_lp_r = 0.0f;
        uint32_t fun_rng = 0x13579BDFu;
        float radio_prev_noise_l = 0.0f;
        float radio_prev_noise_r = 0.0f;
        float radio_bp_l = 0.0f;
        float radio_bp_r = 0.0f;
        float radio_carrier_phase = 0.0f;
        std::atomic<bool> cassette_mode_enabled{false};
        std::atomic<bool> cassette_scrub_active{false};
        std::atomic<float> cassette_scrub_pitch{1.0f};
        std::atomic<int> cassette_spindown_remaining{0};
        std::atomic<int> cassette_spindown_total{0};
        std::atomic<int> radio_crosstalk_remaining{0};
        std::atomic<int> radio_crosstalk_total{0};

        // Lock-free telemetry for real-time CLI audio level & 3-band meters.
        float meter_lp_state = 0.0f;
        float meter_hp_prev_in = 0.0f;
        float meter_hp_state = 0.0f;
        std::atomic<float> meter_rms_l{0.0f};
        std::atomic<float> meter_rms_r{0.0f};
        std::atomic<float> meter_peak_l{0.0f};
        std::atomic<float> meter_peak_r{0.0f};
        std::atomic<float> meter_band_low{0.0f};
        std::atomic<float> meter_band_mid{0.0f};
        std::atomic<float> meter_band_high{0.0f};
    };

    inline float next_white_noise(uint32_t &state) {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        return static_cast<float>(static_cast<int32_t>(state)) * (1.0f / 2147483648.0f);
    }

    inline void sample_cassette_delay(
        const PlaybackEngine &engine,
        size_t write_idx,
        double delay_frames,
        float &out_l,
        float &out_r
    ) {
        constexpr size_t cap = PlaybackEngine::kCassetteDelayFrames;
        const double wrapped_delay = std::clamp(delay_frames, 1.0, static_cast<double>(cap - 4));
        double pos = static_cast<double>(write_idx) - wrapped_delay;
        while (pos < 0.0) pos += static_cast<double>(cap);
        const size_t i0 = static_cast<size_t>(pos) % cap;
        const size_t i1 = (i0 + 1) % cap;
        const float frac = static_cast<float>(pos - std::floor(pos));
        const float l0 = engine.cassette_delay_ring[i0 * kOutputChannels];
        const float r0 = engine.cassette_delay_ring[i0 * kOutputChannels + 1];
        const float l1 = engine.cassette_delay_ring[i1 * kOutputChannels];
        const float r1 = engine.cassette_delay_ring[i1 * kOutputChannels + 1];
        out_l = l0 + (l1 - l0) * frac;
        out_r = r0 + (r1 - r0) * frac;
    }

    void throw_java(JNIEnv *env, const char *message) {
        jclass exception = env->FindClass("java/lang/IllegalStateException");
        if (exception != nullptr) env->ThrowNew(exception, message);
    }

    size_t available_raw(const SourceSlot &slot) {
        const size_t read = slot.raw_read_frame.load(std::memory_order_acquire);
        const size_t write = slot.raw_write_frame.load(std::memory_order_acquire);
        return write >= read ? write - read : kRingFrames - read + write;
    }

    size_t available_out(const SourceSlot &slot) {
        const size_t read = slot.read_frame.load(std::memory_order_acquire);
        const size_t write = slot.write_frame.load(std::memory_order_acquire);
        return write >= read ? write - read : kOutRingFrames - read + write;
    }

    void free_media(SourceSlot &slot) {
        swr_free(&slot.resampler);
        av_frame_free(&slot.frame);
        av_packet_free(&slot.packet);
        avcodec_free_context(&slot.codec);
        avformat_close_input(&slot.format);
        free_custom_io(slot.custom_io);
        slot.audio_stream = -1;
    }

    void release_slot(SourceSlot &slot) {
        slot.stopping.store(true, std::memory_order_release);
        cancel_custom_io(slot);
        if (slot.decode_thread.joinable()) slot.decode_thread.join();
        if (slot.stretch_thread.joinable()) slot.stretch_thread.join();
        free_media(slot);
        slot.raw_read_frame.store(0, std::memory_order_release);
        slot.raw_write_frame.store(0, std::memory_order_release);
        slot.raw_input_exhausted.store(false, std::memory_order_release);
        slot.raw_reader_active.store(false, std::memory_order_release);
        slot.decode_active_generation.store(0, std::memory_order_release);
        slot.read_frame.store(0, std::memory_order_release);
        slot.write_frame.store(0, std::memory_order_release);
        slot.opened.store(false, std::memory_order_release);
        slot.input_exhausted.store(false, std::memory_order_release);
        slot.finished.store(false, std::memory_order_release);
        slot.seek_pending.store(false, std::memory_order_release);
        slot.seek_generation.store(0, std::memory_order_release);
        slot.active_seek_generation.store(0, std::memory_order_release);
        slot.reader_active.store(false, std::memory_order_release);
        slot.seek_us.store(0, std::memory_order_release);
        slot.rebuffering.store(false, std::memory_order_release);
        slot.underrun_count.store(0, std::memory_order_release);
        slot.rebuffer_target_frames.store(0, std::memory_order_release);
        slot.rebuffer_fade_in_rem.store(0, std::memory_order_release);
        slot.rebuffer_fade_in_total.store(0, std::memory_order_release);
        slot.steady_play_frames.store(0, std::memory_order_release);
    }

    void wait_for_callback_quiescence(PlaybackEngine &engine) {
        while (engine.callback_depth.load(std::memory_order_acquire) != 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    }

    constexpr size_t kMaxDecodeAheadFrames = 192'000;
    static_assert(kMaxDecodeAheadFrames < kRingFrames);

    constexpr size_t compute_rebuffer_target_frames(int output_rate, uint32_t underrun_count) {
        const size_t safe_rate = static_cast<size_t>(std::max(8'000, output_rate));
        const size_t max_safe = kMaxDecodeAheadFrames - 8'192;
        size_t target = 0;
        if (underrun_count == 0) {
            target = (safe_rate * 2) / 5; // 0.40s fast start / seek cushion
        } else if (underrun_count == 1) {
            target = (safe_rate * 3) / 2; // 1.50s first rebuffer
        } else if (underrun_count == 2) {
            target = (safe_rate * 5) / 2; // 2.50s second rebuffer
        } else {
            target = safe_rate * 3;       // 3.00s persistent poor network
        }
        return std::clamp<size_t>(target, 4'096, max_safe);
    }

    static_assert(compute_rebuffer_target_frames(48'000, 0) == 19'200);
    static_assert(compute_rebuffer_target_frames(48'000, 1) == 72'000);
    static_assert(compute_rebuffer_target_frames(48'000, 2) == 120'000);
    static_assert(compute_rebuffer_target_frames(48'000, 3) == 144'000);
    static_assert(compute_rebuffer_target_frames(192'000, 3) <= kMaxDecodeAheadFrames - 8'192);
    constexpr int kWsolaWinSize = 2048;
    constexpr int kWsolaHalfWin = kWsolaWinSize / 2;
    constexpr int kWsolaSearchDelta = 512;

    struct WsolaStretcher {
        std::vector<float> in_buf;
        std::vector<int64_t> in_pts;
        std::vector<float> overlap_buf;
        std::vector<float> hann;
        std::vector<float> out_buf;
        std::vector<int64_t> out_pts;
        int in_cursor = 0;
        bool initialized = false;

        WsolaStretcher() : overlap_buf(kWsolaHalfWin * kOutputChannels, 0.0f), hann(kWsolaWinSize, 0.0f) {
            for (int i = 0; i < kWsolaWinSize; ++i) {
                hann[i] = 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(M_PI) * static_cast<float>(i) / static_cast<float>(kWsolaWinSize)));
            }
        }

        void reset() {
            in_buf.clear();
            in_pts.clear();
            std::fill(overlap_buf.begin(), overlap_buf.end(), 0.0f);
            in_cursor = 0;
            initialized = false;
        }

        void append_with_pts(const float *samples, const int64_t *pts_us, size_t frames) {
            const size_t prev_samples = in_buf.size();
            in_buf.resize(prev_samples + frames * kOutputChannels);
            std::copy_n(samples, frames * kOutputChannels, in_buf.begin() + prev_samples);
            in_pts.insert(in_pts.end(), pts_us, pts_us + frames);
        }

        template <typename EmitFn>
        bool process(float speed, bool flush, EmitFn &&emit) {
            const int in_hop = std::max(64, static_cast<int>(std::lround(static_cast<double>(kWsolaHalfWin) * static_cast<double>(speed))));
            while (true) {
                const int total_frames = static_cast<int>(in_pts.size());
                if (!initialized) {
                    if (total_frames < kWsolaWinSize) break;
                    if (!emit(in_buf.data(), kWsolaHalfWin, in_pts.data())) return false;
                    std::copy_n(in_buf.data() + kWsolaHalfWin * kOutputChannels, kWsolaHalfWin * kOutputChannels, overlap_buf.data());
                    in_cursor = in_hop;
                    initialized = true;
                    continue;
                }
                if (in_cursor + kWsolaSearchDelta + kWsolaWinSize > total_frames) break;
                const int min_start = std::max(0, in_cursor - kWsolaSearchDelta);
                const int max_start = std::min(total_frames - kWsolaWinSize, in_cursor + kWsolaSearchDelta);
                int best_start = std::clamp(in_cursor, min_start, max_start);
                double best_score = -1e30;
                for (int cand = min_start; cand <= max_start; cand += 2) {
                    double dot = 0.0;
                    double norm = 1e-12;
                    const float *cand_ptr = in_buf.data() + static_cast<size_t>(cand) * kOutputChannels;
                    for (int i = 0; i < kWsolaHalfWin; i += 4) {
                        const float ref_l = overlap_buf[i * 2];
                        const float ref_r = overlap_buf[i * 2 + 1];
                        const float c_l = cand_ptr[i * 2];
                        const float c_r = cand_ptr[i * 2 + 1];
                        dot += static_cast<double>(ref_l) * c_l + static_cast<double>(ref_r) * c_r;
                        norm += static_cast<double>(c_l) * c_l + static_cast<double>(c_r) * c_r;
                    }
                    const double score = dot / std::sqrt(norm);
                    if (score > best_score) {
                        best_score = score;
                        best_start = cand;
                    }
                }
                out_buf.resize(kWsolaHalfWin * kOutputChannels);
                out_pts.resize(kWsolaHalfWin);
                const float *cand_ptr = in_buf.data() + static_cast<size_t>(best_start) * kOutputChannels;
                for (int i = 0; i < kWsolaHalfWin; ++i) {
                    const float w_prev = hann[kWsolaHalfWin + i];
                    const float w_curr = hann[i];
                    out_buf[i * 2] = overlap_buf[i * 2] * w_prev + cand_ptr[i * 2] * w_curr;
                    out_buf[i * 2 + 1] = overlap_buf[i * 2 + 1] * w_prev + cand_ptr[i * 2 + 1] * w_curr;
                    out_pts[i] = in_pts[best_start + i];
                }
                if (!emit(out_buf.data(), kWsolaHalfWin, out_pts.data())) return false;
                std::copy_n(cand_ptr + kWsolaHalfWin * kOutputChannels, kWsolaHalfWin * kOutputChannels, overlap_buf.data());
                in_cursor += in_hop;
                if (in_cursor > kWsolaSearchDelta + kWsolaWinSize) {
                    const int trim = in_cursor - kWsolaSearchDelta;
                    in_buf.erase(in_buf.begin(), in_buf.begin() + static_cast<size_t>(trim) * kOutputChannels);
                    in_pts.erase(in_pts.begin(), in_pts.begin() + trim);
                    in_cursor -= trim;
                }
            }
            if (flush && !in_pts.empty()) {
                const int start = std::clamp(in_cursor, 0, static_cast<int>(in_pts.size()));
                const size_t rem = in_pts.size() - static_cast<size_t>(start);
                if (rem > 0) {
                    if (!emit(in_buf.data() + static_cast<size_t>(start) * kOutputChannels, rem, in_pts.data() + start)) {
                        return false;
                    }
                }
                reset();
            }
            return true;
        }
    };

    struct ScopedReaderFlag {
        std::atomic<bool> &flag;
        explicit ScopedReaderFlag(std::atomic<bool> &f) : flag(f) {
            flag.store(true, std::memory_order_seq_cst);
        }
        ~ScopedReaderFlag() {
            flag.store(false, std::memory_order_release);
        }
    };

    bool push_raw_samples(SourceSlot &slot, const float *samples, size_t frames, int64_t start_pts_us,
                          uint64_t expected_gen) {
        for (size_t i = 0; i < frames; ++i) {
            while (!slot.stopping.load(std::memory_order_acquire) &&
                    slot.seek_generation.load(std::memory_order_acquire) == expected_gen &&
                    available_raw(slot) >= std::min(kRingFrames - 1, kMaxDecodeAheadFrames)) {
                // The stretch/audio workers never signal this wait: bounded timed wait keeps decode-ahead safe.
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
            }
            if (slot.stopping.load(std::memory_order_acquire)) return false;
            if (slot.seek_generation.load(std::memory_order_acquire) != expected_gen) return true;
            const size_t write = slot.raw_write_frame.load(std::memory_order_relaxed);
            slot.raw_ring[write * kOutputChannels] = samples[i * kOutputChannels];
            slot.raw_ring[write * kOutputChannels + 1] = samples[i * kOutputChannels + 1];
            slot.raw_ring_pts_us[write] = start_pts_us + av_rescale(static_cast<int64_t>(i), AV_TIME_BASE, slot.output_rate);
            slot.raw_write_frame.store((write + 1) % kRingFrames, std::memory_order_release);
        }
        return true;
    }

    bool push_out_samples_with_pts(SourceSlot &slot, const float *samples, size_t frames, const int64_t *pts_us,
                                   uint64_t expected_gen) {
        const size_t out_cap = std::min(kOutRingFrames - 1, slot.max_out_ahead_frames.load(std::memory_order_relaxed));
        for (size_t i = 0; i < frames; ++i) {
            while (!slot.stopping.load(std::memory_order_acquire) &&
                    slot.seek_generation.load(std::memory_order_acquire) == expected_gen &&
                    available_out(slot) >= out_cap) {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
            if (slot.stopping.load(std::memory_order_acquire)) return false;
            if (slot.seek_generation.load(std::memory_order_acquire) != expected_gen) return true;
            const size_t write = slot.write_frame.load(std::memory_order_relaxed);
            slot.ring[write * kOutputChannels] = samples[i * kOutputChannels];
            slot.ring[write * kOutputChannels + 1] = samples[i * kOutputChannels + 1];
            slot.ring_pts_us[write] = pts_us[i];
            slot.write_frame.store((write + 1) % kOutRingFrames, std::memory_order_release);
        }
        return true;
    }

    void stretch_loop(SourceSlot &slot) {
        WsolaStretcher wsola;
        constexpr size_t kChunkFrames = 1024;
        std::array<float, kChunkFrames * kOutputChannels> chunk{};
        std::array<int64_t, kChunkFrames> chunk_pts{};
        uint64_t stretch_gen = 0;
        bool awaiting_prefill = false;
        while (!slot.stopping.load(std::memory_order_acquire)) {
            const uint64_t target_gen = slot.seek_generation.load(std::memory_order_acquire);
            if (stretch_gen != target_gen) {
                while (slot.reader_active.load(std::memory_order_acquire) &&
                        !slot.stopping.load(std::memory_order_acquire)) {
                    std::this_thread::yield();
                }
                if (slot.stopping.load(std::memory_order_acquire)) return;
                wsola.reset();
                slot.read_frame.store(0, std::memory_order_release);
                slot.write_frame.store(0, std::memory_order_release);
                slot.input_exhausted.store(false, std::memory_order_release);
                slot.finished.store(false, std::memory_order_release);
                slot.rebuffering.store(false, std::memory_order_release);
                slot.rebuffer_fade_in_rem.store(0, std::memory_order_release);
                slot.steady_play_frames.store(0, std::memory_order_release);
                while (!slot.stopping.load(std::memory_order_acquire) &&
                        slot.seek_generation.load(std::memory_order_acquire) == target_gen &&
                        slot.decode_active_generation.load(std::memory_order_acquire) != target_gen) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(1));
                }
                if (slot.stopping.load(std::memory_order_acquire)) return;
                if (slot.seek_generation.load(std::memory_order_acquire) != target_gen) continue;
                stretch_gen = target_gen;
                awaiting_prefill = true;
                continue;
            }
            const size_t out_cap = std::min(kOutRingFrames - 1, slot.max_out_ahead_frames.load(std::memory_order_relaxed));
            const size_t burst = slot.output_burst.load(std::memory_order_relaxed);
            const size_t prefill_frames = std::min<size_t>(out_cap / 2, std::max<size_t>(burst * 2, 4'096));
            const bool is_stream = slot.custom_io != nullptr;
            const bool is_decode_current =
                slot.seek_generation.load(std::memory_order_acquire) == stretch_gen &&
                slot.decode_active_generation.load(std::memory_order_acquire) == stretch_gen;
            auto check_and_finish_buffering = [&]() {
                if (!is_decode_current) return;
                const size_t avail_out = available_out(slot);
                const size_t avail_raw = available_raw(slot);
                const bool raw_eof = slot.raw_input_exhausted.load(std::memory_order_acquire);
                if (awaiting_prefill) {
                    const size_t target_frames = is_stream
                        ? compute_rebuffer_target_frames(slot.output_rate, slot.underrun_count.load(std::memory_order_acquire))
                        : 0;
                    const bool stream_ready = !is_stream ||
                        (avail_raw + avail_out >= target_frames) ||
                        raw_eof;
                    if (avail_out >= prefill_frames && stream_ready) {
                        slot.active_seek_generation.store(stretch_gen, std::memory_order_release);
                        awaiting_prefill = false;
                    }
                }
                if (is_stream && slot.rebuffering.load(std::memory_order_acquire)) {
                    const size_t target_frames = std::max(
                        prefill_frames,
                        slot.rebuffer_target_frames.load(std::memory_order_acquire));
                    const bool ready =
                        (avail_out >= prefill_frames && avail_raw + avail_out >= target_frames) ||
                        (raw_eof && (avail_out >= prefill_frames || avail_raw == 0));
                    if (ready) {
                        const uint32_t fade_frames = std::max<uint32_t>(1, static_cast<uint32_t>(slot.output_rate / 200));
                        slot.rebuffer_fade_in_total.store(fade_frames, std::memory_order_release);
                        slot.rebuffer_fade_in_rem.store(fade_frames, std::memory_order_release);
                        slot.rebuffering.store(false, std::memory_order_release);
                    }
                }
            };
            check_and_finish_buffering();
            // Do not push more chunks into out_ring once it is near capacity while waiting on a
            // stream high watermark; otherwise push_out_samples_with_pts would block waiting for
            // audio_callback, while audio_callback waits for rebuffering/awaiting_prefill to clear.
            const bool hold_out_push =
                is_stream &&
                (awaiting_prefill || slot.rebuffering.load(std::memory_order_acquire)) &&
                available_out(slot) + kChunkFrames > out_cap &&
                !slot.raw_input_exhausted.load(std::memory_order_acquire);
            if (hold_out_push) {
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
                continue;
            }
            size_t pulled = 0;
            {
                ScopedReaderFlag raw_guard(slot.raw_reader_active);
                if (slot.seek_generation.load(std::memory_order_acquire) == stretch_gen &&
                        slot.decode_active_generation.load(std::memory_order_acquire) == stretch_gen) {
                    size_t raw_read = slot.raw_read_frame.load(std::memory_order_relaxed);
                    const size_t raw_write = slot.raw_write_frame.load(std::memory_order_acquire);
                    while (raw_read != raw_write && pulled < kChunkFrames) {
                        chunk[pulled * kOutputChannels] = slot.raw_ring[raw_read * kOutputChannels];
                        chunk[pulled * kOutputChannels + 1] = slot.raw_ring[raw_read * kOutputChannels + 1];
                        chunk_pts[pulled] = slot.raw_ring_pts_us[raw_read];
                        raw_read = (raw_read + 1) % kRingFrames;
                        pulled++;
                    }
                    if (pulled > 0) {
                        slot.raw_read_frame.store(raw_read, std::memory_order_release);
                    }
                }
            }
            auto emit_out = [&slot, stretch_gen](const float *s, size_t f, const int64_t *pts) -> bool {
                return push_out_samples_with_pts(slot, s, f, pts, stretch_gen);
            };
            const float speed = std::clamp(slot.speed.load(std::memory_order_acquire), 0.5f, 2.0f);
            if (pulled > 0) {
                if (std::abs(speed - 1.0f) < 0.01f) {
                    if (!wsola.in_pts.empty()) {
                        if (!wsola.process(1.0f, true, emit_out)) return;
                        if (slot.seek_generation.load(std::memory_order_acquire) != stretch_gen) continue;
                    }
                    if (!push_out_samples_with_pts(slot, chunk.data(), pulled, chunk_pts.data(), stretch_gen)) return;
                } else {
                    wsola.append_with_pts(chunk.data(), chunk_pts.data(), pulled);
                    if (!wsola.process(speed, false, emit_out)) return;
                }
                check_and_finish_buffering();
                continue;
            }
            const bool exhausted =
                slot.raw_input_exhausted.load(std::memory_order_acquire) &&
                slot.decode_active_generation.load(std::memory_order_acquire) == stretch_gen &&
                slot.seek_generation.load(std::memory_order_acquire) == stretch_gen &&
                available_raw(slot) == 0;
            if (exhausted) {
                if (!wsola.in_pts.empty()) {
                    if (!wsola.process(speed, true, emit_out)) return;
                    if (slot.seek_generation.load(std::memory_order_acquire) != stretch_gen) continue;
                }
                slot.input_exhausted.store(true, std::memory_order_release);
                if (awaiting_prefill && slot.seek_generation.load(std::memory_order_acquire) == stretch_gen) {
                    slot.active_seek_generation.store(stretch_gen, std::memory_order_release);
                    awaiting_prefill = false;
                }
                if (slot.rebuffering.load(std::memory_order_acquire) &&
                        slot.seek_generation.load(std::memory_order_acquire) == stretch_gen) {
                    const uint32_t fade_frames = std::max<uint32_t>(1, static_cast<uint32_t>(slot.output_rate / 200));
                    slot.rebuffer_fade_in_total.store(fade_frames, std::memory_order_release);
                    slot.rebuffer_fade_in_rem.store(fade_frames, std::memory_order_release);
                    slot.rebuffering.store(false, std::memory_order_release);
                }
                while (!slot.stopping.load(std::memory_order_acquire) &&
                        slot.seek_generation.load(std::memory_order_acquire) == stretch_gen) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(2));
                }
            } else {
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
            }
        }
    }

    void decoder_loop(SourceSlot &slot) {
        ScopedJniThreadAttachment jni_attachment(slot.custom_io != nullptr ? slot.custom_io->jvm : nullptr);
        std::vector<float> output;
        int64_t discard_before_us = -1;
        int64_t decode_cursor_us = slot.position_base_us.load(std::memory_order_acquire);
        uint64_t current_gen = 0;
        bool eof_drained = false;
        while (!slot.stopping.load(std::memory_order_acquire)) {
            if (slot.seek_pending.exchange(false, std::memory_order_acq_rel) ||
                    current_gen != slot.seek_generation.load(std::memory_order_acquire)) {
                while (slot.raw_reader_active.load(std::memory_order_acquire) &&
                        !slot.stopping.load(std::memory_order_acquire)) {
                    std::this_thread::yield();
                }
                if (slot.stopping.load(std::memory_order_acquire)) return;
                eof_drained = false;
                const uint64_t target_gen = slot.seek_generation.load(std::memory_order_acquire);
                const int64_t target_us = slot.seek_us.load(std::memory_order_acquire);
                slot.raw_read_frame.store(0, std::memory_order_release);
                slot.raw_write_frame.store(0, std::memory_order_release);
                slot.raw_input_exhausted.store(false, std::memory_order_release);
                begin_custom_io_seek(slot, target_gen);
                if (slot.format != nullptr && slot.format->pb != nullptr) {
                    slot.format->pb->eof_reached = 0;
                    slot.format->pb->error = 0;
                }
                av_packet_unref(slot.packet);
                const AVRational stream_tb = slot.format->streams[slot.audio_stream]->time_base;
                const int64_t target = av_rescale_q(target_us, AVRational{1, AV_TIME_BASE}, stream_tb);
                int seek_ret = av_seek_frame(slot.format, slot.audio_stream, target, AVSEEK_FLAG_BACKWARD);
                if (slot.seek_generation.load(std::memory_order_acquire) != target_gen) {
                    continue;
                }
                if (seek_ret < 0 &&
                        (slot.format->pb == nullptr || slot.format->pb->error == 0) &&
                        slot.format->duration > 0 &&
                        target_us > slot.format->duration - 1'000'000) {
                    const int64_t fallback_us = std::max<int64_t>(0, slot.format->duration - 1'000'000);
                    const int64_t fallback = av_rescale_q(fallback_us, AV_TIME_BASE_Q, stream_tb);
                    if (slot.format->pb != nullptr) {
                        slot.format->pb->eof_reached = 0;
                        slot.format->pb->error = 0;
                    }
                    seek_ret = av_seek_frame(slot.format, slot.audio_stream, fallback, AVSEEK_FLAG_BACKWARD);
                    if (slot.seek_generation.load(std::memory_order_acquire) != target_gen) {
                        continue;
                    }
                }
                end_custom_io_seek(slot, target_gen);
                avcodec_flush_buffers(slot.codec);
                swr_close(slot.resampler);
                swr_init(slot.resampler);
                if (seek_ret < 0) {
                    slot.raw_input_exhausted.store(true, std::memory_order_release);
                    if (slot.seek_generation.load(std::memory_order_acquire) == target_gen) {
                        slot.decode_active_generation.store(target_gen, std::memory_order_release);
                        current_gen = target_gen;
                    } else {
                        continue;
                    }
                    while (!slot.stopping.load(std::memory_order_acquire) &&
                            !slot.seek_pending.load(std::memory_order_acquire) &&
                            slot.seek_generation.load(std::memory_order_acquire) == current_gen) {
                        std::this_thread::sleep_for(std::chrono::milliseconds(2));
                    }
                    continue;
                }
                decode_cursor_us = target_us;
                // av_seek_frame seeks to a preceding keyframe. Do not expose
                // decoded PCM before the requested timestamp to the callback.
                discard_before_us = target_us;
                if (slot.seek_generation.load(std::memory_order_acquire) == target_gen) {
                    slot.decode_active_generation.store(target_gen, std::memory_order_release);
                    current_gen = target_gen;
                } else {
                    continue;
                }
            }
            const int read = av_read_frame(slot.format, slot.packet);
            if (read < 0) {
                av_packet_unref(slot.packet);
                if (slot.seek_pending.load(std::memory_order_acquire) ||
                        slot.seek_generation.load(std::memory_order_acquire) != current_gen) {
                    continue;
                }
                if (!eof_drained) {
                    eof_drained = true;
                    // Drain codec and resampler tail for clickless gapless transitions.
                    if (avcodec_send_packet(slot.codec, nullptr) >= 0) {
                        while (avcodec_receive_frame(slot.codec, slot.frame) >= 0) {
                            if (slot.seek_generation.load(std::memory_order_acquire) != current_gen ||
                                    slot.stopping.load(std::memory_order_acquire)) break;
                            const int out_frames = swr_get_out_samples(slot.resampler, slot.frame->nb_samples);
                            output.resize(static_cast<size_t>(out_frames) * kOutputChannels);
                            uint8_t *out_data[] = {reinterpret_cast<uint8_t *>(output.data())};
                            const int converted = swr_convert(slot.resampler, out_data, out_frames,
                                    const_cast<const uint8_t **>(slot.frame->extended_data), slot.frame->nb_samples);
                            if (converted > 0) {
                                int skip_frames = 0;
                                const int64_t frame_pts = slot.frame->best_effort_timestamp;
                                int64_t chunk_start_us = decode_cursor_us;
                                if (frame_pts != AV_NOPTS_VALUE) {
                                    const int64_t frame_start_us = av_rescale_q(
                                            frame_pts,
                                            slot.format->streams[slot.audio_stream]->time_base,
                                            AVRational{1, AV_TIME_BASE});
                                    const int64_t frame_duration_us = av_rescale(
                                            slot.frame->nb_samples, AV_TIME_BASE, slot.codec->sample_rate);
                                    if (discard_before_us >= 0) {
                                        if (frame_start_us + frame_duration_us <= discard_before_us) continue;
                                        if (frame_start_us < discard_before_us) {
                                            skip_frames = static_cast<int>(std::min<int64_t>(
                                                    converted,
                                                    av_rescale(discard_before_us - frame_start_us, slot.output_rate, AV_TIME_BASE)));
                                        }
                                        discard_before_us = -1;
                                    }
                                    chunk_start_us = frame_start_us + av_rescale(skip_frames, AV_TIME_BASE, slot.output_rate);
                                }
                                const int valid_frames = converted - skip_frames;
                                if (valid_frames > 0) {
                                    decode_cursor_us = chunk_start_us + av_rescale(valid_frames, AV_TIME_BASE, slot.output_rate);
                                    const float *pcm = output.data() + static_cast<size_t>(skip_frames) * kOutputChannels;
                                    if (!push_raw_samples(slot, pcm, static_cast<size_t>(valid_frames), chunk_start_us, current_gen)) return;
                                }
                            }
                        }
                    }
                    while (slot.seek_generation.load(std::memory_order_acquire) == current_gen &&
                            !slot.stopping.load(std::memory_order_acquire)) {
                        const int out_frames = std::max(256, swr_get_out_samples(slot.resampler, 0));
                        output.resize(static_cast<size_t>(out_frames) * kOutputChannels);
                        uint8_t *out_data[] = {reinterpret_cast<uint8_t *>(output.data())};
                        const int flushed = swr_convert(slot.resampler, out_data, out_frames, nullptr, 0);
                        if (flushed <= 0) break;
                        const int64_t chunk_start_us = decode_cursor_us;
                        decode_cursor_us += av_rescale(flushed, AV_TIME_BASE, slot.output_rate);
                        if (!push_raw_samples(slot, output.data(), static_cast<size_t>(flushed), chunk_start_us, current_gen)) return;
                    }
                    if (slot.seek_pending.load(std::memory_order_acquire) ||
                            slot.seek_generation.load(std::memory_order_acquire) != current_gen) {
                        continue;
                    }
                }
                slot.raw_input_exhausted.store(true, std::memory_order_release);
                // Keep the worker alive: short files commonly reach EOF while
                // their PCM is still buffered, and a later seek must restart
                // demuxing instead of letting that stale buffer finish the track.
                while (!slot.stopping.load(std::memory_order_acquire) &&
                        !slot.seek_pending.load(std::memory_order_acquire) &&
                        slot.seek_generation.load(std::memory_order_acquire) == current_gen) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(2));
                }
                continue;
            }
            if (slot.packet->stream_index == slot.audio_stream && avcodec_send_packet(slot.codec, slot.packet) >= 0) {
                while (avcodec_receive_frame(slot.codec, slot.frame) >= 0) {
                    if (slot.seek_generation.load(std::memory_order_acquire) != current_gen) break;
                    const int out_frames = swr_get_out_samples(slot.resampler, slot.frame->nb_samples);
                    output.resize(static_cast<size_t>(out_frames) * kOutputChannels);
                    uint8_t *out_data[] = {reinterpret_cast<uint8_t *>(output.data())};
                    const int converted = swr_convert(slot.resampler, out_data, out_frames,
                            const_cast<const uint8_t **>(slot.frame->extended_data), slot.frame->nb_samples);
                    if (converted > 0) {
                        int skip_frames = 0;
                        const int64_t frame_pts = slot.frame->best_effort_timestamp;
                        int64_t chunk_start_us = decode_cursor_us;
                        if (frame_pts != AV_NOPTS_VALUE) {
                            const int64_t frame_start_us = av_rescale_q(
                                    frame_pts,
                                    slot.format->streams[slot.audio_stream]->time_base,
                                    AVRational{1, AV_TIME_BASE});
                            const int64_t frame_duration_us = av_rescale(
                                    slot.frame->nb_samples, AV_TIME_BASE, slot.codec->sample_rate);
                            if (discard_before_us >= 0) {
                                if (frame_start_us + frame_duration_us <= discard_before_us) {
                                    continue;
                                }
                                if (frame_start_us < discard_before_us) {
                                    skip_frames = static_cast<int>(std::min<int64_t>(
                                            converted,
                                            av_rescale(discard_before_us - frame_start_us, slot.output_rate, AV_TIME_BASE)));
                                }
                                discard_before_us = -1;
                            }
                            chunk_start_us = frame_start_us + av_rescale(skip_frames, AV_TIME_BASE, slot.output_rate);
                        }
                        const int valid_frames = converted - skip_frames;
                        if (valid_frames > 0) {
                            decode_cursor_us = chunk_start_us + av_rescale(valid_frames, AV_TIME_BASE, slot.output_rate);
                            const float *pcm = output.data() + static_cast<size_t>(skip_frames) * kOutputChannels;
                            if (!push_raw_samples(slot, pcm, static_cast<size_t>(valid_frames), chunk_start_us, current_gen)) return;
                            if (slot.seek_generation.load(std::memory_order_acquire) != current_gen) break;
                        }
                    }
                }
            }
            av_packet_unref(slot.packet);
        }
    }

    bool open_slot(SourceSlot &slot, int fd, int output_rate, float normalization_gain_db,
                   bool has_static_analysis, std::string &error, CustomIoBridge *custom_io = nullptr) {
        release_slot(slot);
        slot.stopping.store(false, std::memory_order_release);
        slot.custom_io = custom_io;
        int open_result = -1;
        if (custom_io != nullptr) {
            custom_io->stopping = &slot.stopping;
            slot.format = avformat_alloc_context();
            if (slot.format == nullptr) {
                error = "FFmpeg could not allocate format context";
                free_media(slot);
                return false;
            }
            slot.format->pb = custom_io->avio;
            slot.format->flags |= AVFMT_FLAG_CUSTOM_IO;
            slot.format->interrupt_callback.callback = avio_interrupt_cb;
            slot.format->interrupt_callback.opaque = custom_io;
            open_result = avformat_open_input(&slot.format, nullptr, nullptr, nullptr);
        } else {
            const std::string path = "/proc/self/fd/" + std::to_string(fd);
            open_result = avformat_open_input(&slot.format, path.c_str(), nullptr, nullptr);
        }
        if (open_result < 0 || avformat_find_stream_info(slot.format, nullptr) < 0) {
            error = "FFmpeg could not open this audio file";
            free_media(slot);
            return false;
        }
        slot.audio_stream = av_find_best_stream(slot.format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
        if (slot.audio_stream < 0) {
            error = "No audio stream in file";
            free_media(slot);
            return false;
        }
        const AVCodecParameters *parameters = slot.format->streams[slot.audio_stream]->codecpar;
        const AVCodec *decoder = avcodec_find_decoder(parameters->codec_id);
        if (decoder == nullptr) {
            error = "This FFmpeg build has no decoder for the audio stream";
            free_media(slot);
            return false;
        }
        slot.codec = avcodec_alloc_context3(decoder);
        if (slot.codec == nullptr || avcodec_parameters_to_context(slot.codec, parameters) < 0 || avcodec_open2(slot.codec, decoder, nullptr) < 0) {
            error = "FFmpeg could not initialize the decoder";
            free_media(slot);
            return false;
        }
        AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
        if (swr_alloc_set_opts2(&slot.resampler, &stereo, AV_SAMPLE_FMT_FLT, output_rate,
                &slot.codec->ch_layout, slot.codec->sample_fmt, slot.codec->sample_rate, 0, nullptr) < 0 ||
                swr_init(slot.resampler) < 0) {
            error = "FFmpeg could not configure PCM output";
            free_media(slot);
            return false;
        }
        slot.packet = av_packet_alloc();
        slot.frame = av_frame_alloc();
        if (slot.packet == nullptr || slot.frame == nullptr) {
            error = "FFmpeg could not allocate decode buffers";
            free_media(slot);
            return false;
        }
        slot.output_rate = output_rate;
        slot.stopping.store(false, std::memory_order_release);
        slot.normalization_gain_db.store(normalization_gain_db, std::memory_order_release);
        slot.has_static_analysis.store(has_static_analysis, std::memory_order_release);
        slot.reset_meter_filters.store(false, std::memory_order_release);
        slot.meter.init(output_rate);
        slot.current_applied_gain = 1.0f;
        slot.gain_smooth_alpha = 1.0f - std::exp(-1.0f / (0.04f * static_cast<float>(std::max(8'000, output_rate))));
        slot.position_base_us.store(0, std::memory_order_release);
        slot.current_pts_us.store(0, std::memory_order_release);
        slot.has_rendered_pts.store(false, std::memory_order_release);
        slot.rendered_frames.store(0, std::memory_order_release);
        slot.raw_read_frame.store(0, std::memory_order_release);
        slot.raw_write_frame.store(0, std::memory_order_release);
        slot.raw_input_exhausted.store(false, std::memory_order_release);
        slot.raw_reader_active.store(false, std::memory_order_release);
        slot.decode_active_generation.store(0, std::memory_order_release);
        slot.seek_pending.store(false, std::memory_order_release);
        slot.seek_generation.store(0, std::memory_order_release);
        slot.active_seek_generation.store(0, std::memory_order_release);
        slot.reader_active.store(false, std::memory_order_release);
        slot.seek_us.store(0, std::memory_order_release);
        slot.underrun_count.store(0, std::memory_order_release);
        slot.rebuffer_fade_in_rem.store(0, std::memory_order_release);
        slot.rebuffer_fade_in_total.store(0, std::memory_order_release);
        slot.steady_play_frames.store(0, std::memory_order_release);
        const bool is_stream = custom_io != nullptr;
        slot.rebuffer_target_frames.store(
            is_stream ? compute_rebuffer_target_frames(output_rate, 0) : 0,
            std::memory_order_release);
        slot.rebuffering.store(is_stream, std::memory_order_release);
        slot.opened.store(true, std::memory_order_release);
        slot.decode_thread = std::thread(decoder_loop, std::ref(slot));
        slot.stretch_thread = std::thread(stretch_loop, std::ref(slot));
        return true;
    }

    bool read_frame(PlaybackEngine &engine, SourceSlot &slot, float &left, float &right) {
        ScopedReaderFlag guard(slot.reader_active);
        if (slot.seek_pending.load(std::memory_order_acquire) ||
                slot.active_seek_generation.load(std::memory_order_acquire) !=
                        slot.seek_generation.load(std::memory_order_acquire)) {
            left = right = 0.0f;
            return false;
        }
        const bool is_stream = slot.custom_io != nullptr;
        const bool in_crossfade = engine.crossfading.load(std::memory_order_acquire);
        const size_t read = slot.read_frame.load(std::memory_order_relaxed);
        const size_t write = slot.write_frame.load(std::memory_order_acquire);
        if (is_stream && !in_crossfade && slot.rebuffering.load(std::memory_order_acquire)) {
            if (read == write && slot.input_exhausted.load(std::memory_order_acquire) &&
                    !slot.seek_pending.load(std::memory_order_acquire) &&
                    slot.active_seek_generation.load(std::memory_order_acquire) ==
                            slot.seek_generation.load(std::memory_order_acquire)) {
                slot.finished.store(true, std::memory_order_release);
            }
            left = right = 0.0f;
            return false;
        }
        if (read == write) {
            if (slot.input_exhausted.load(std::memory_order_acquire) &&
                    !slot.seek_pending.load(std::memory_order_acquire) &&
                    slot.active_seek_generation.load(std::memory_order_acquire) ==
                            slot.seek_generation.load(std::memory_order_acquire)) {
                slot.finished.store(true, std::memory_order_release);
            } else if (is_stream && !in_crossfade &&
                    available_raw(slot) == 0 &&
                    !slot.raw_input_exhausted.load(std::memory_order_acquire)) {
                const uint32_t next_underruns = slot.underrun_count.fetch_add(1, std::memory_order_acq_rel) + 1;
                slot.steady_play_frames.store(0, std::memory_order_release);
                slot.rebuffer_target_frames.store(
                    compute_rebuffer_target_frames(slot.output_rate, next_underruns),
                    std::memory_order_release);
                slot.rebuffering.store(true, std::memory_order_release);
            }
            left = right = 0.0f;
            return false;
        }
        const float raw_l = slot.ring[read * kOutputChannels];
        const float raw_r = slot.ring[read * kOutputChannels + 1];
        if (slot.reset_meter_filters.exchange(false, std::memory_order_acq_rel)) {
            slot.meter.reset_filters();
        }
        const bool norm_on = engine.norm_enabled.load(std::memory_order_relaxed);
        float target_gain_db = 0.0f;
        if (norm_on) {
            if (slot.has_static_analysis.load(std::memory_order_relaxed)) {
                target_gain_db = slot.normalization_gain_db.load(std::memory_order_relaxed);
            } else {
                target_gain_db = slot.meter.process_frame(
                    raw_l,
                    raw_r,
                    engine.norm_target_lufs.load(std::memory_order_relaxed),
                    engine.norm_prevent_clip.load(std::memory_order_relaxed)
                );
            }
        }
        const float target_linear = norm_on ? std::pow(10.0f, target_gain_db / 20.0f) : 1.0f;
        slot.current_applied_gain += slot.gain_smooth_alpha * (target_linear - slot.current_applied_gain);
        float fade_env = 1.0f;
        const uint32_t fade_rem = slot.rebuffer_fade_in_rem.load(std::memory_order_relaxed);
        if (fade_rem > 0) {
            const uint32_t fade_total = std::max<uint32_t>(1, slot.rebuffer_fade_in_total.load(std::memory_order_relaxed));
            fade_env = static_cast<float>(fade_total - fade_rem + 1) / static_cast<float>(fade_total);
            slot.rebuffer_fade_in_rem.store(fade_rem - 1, std::memory_order_relaxed);
        }
        left = raw_l * slot.current_applied_gain * fade_env;
        right = raw_r * slot.current_applied_gain * fade_env;
        slot.current_pts_us.store(slot.ring_pts_us[read], std::memory_order_release);
        slot.has_rendered_pts.store(true, std::memory_order_release);
        slot.read_frame.store((read + 1) % kOutRingFrames, std::memory_order_release);
        slot.rendered_frames.fetch_add(1, std::memory_order_relaxed);
        if (is_stream) {
            const int64_t steady = slot.steady_play_frames.fetch_add(1, std::memory_order_relaxed) + 1;
            const int64_t decay_frames = static_cast<int64_t>(std::max(8'000, slot.output_rate)) * 20;
            if (steady >= decay_frames && slot.underrun_count.load(std::memory_order_relaxed) > 0) {
                slot.underrun_count.store(0, std::memory_order_release);
                slot.steady_play_frames.store(0, std::memory_order_release);
            }
        }
        return true;
    }

    void promote_gapless(PlaybackEngine &engine) {
        const int incoming = engine.preloaded_slot.exchange(kNoSlot, std::memory_order_acq_rel);
        if (incoming == kNoSlot) return;
        const int outgoing = engine.active_slot.exchange(incoming, std::memory_order_acq_rel);
        // Unlike a crossfade, the old source has already exhausted its ring.
        // It can be retired now and reclaimed by the subsequent preload.
        if (outgoing != kNoSlot) engine.slots[outgoing].stopping.store(true, std::memory_order_release);
        engine.transition_event.store(kTransitionGaplessPromoted, std::memory_order_release);
    }

    void finish_fade_in_callback(PlaybackEngine &engine) {
        const int outgoing = engine.outgoing_slot.exchange(kNoSlot, std::memory_order_acq_rel);
        if (outgoing != kNoSlot) engine.slots[outgoing].stopping.store(true, std::memory_order_release);
        engine.crossfading.store(false, std::memory_order_release);
    }

    constexpr float kEqFrequenciesHz[kEqBands] = {32.0f, 64.0f, 125.0f, 250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f};

    void configure_eq(PlaybackEngine &engine, const GlobalDspConfig *config) {
        engine.applied_dsp_generation = config == nullptr ? 0 : config->generation;
        constexpr float q = 1.0f;
        for (int i = 0; i < kEqBands; ++i) {
            auto &filter = engine.eq_filters[i];
            filter = Biquad{};
            const float gain = config == nullptr ? 0.0f : config->eq_band_gains_db[i];
            if (gain == 0.0f) continue;
            filter.active = true;
            const float omega = 2.0f * static_cast<float>(M_PI) * kEqFrequenciesHz[i] / static_cast<float>(engine.output_rate);
            const float alpha = std::sin(omega) / (2.0f * q);
            const float a = std::pow(10.0f, gain / 40.0f);
            const float a0 = 1.0f + alpha / a;
            filter.b0 = (1.0f + alpha * a) / a0;
            filter.b1 = (-2.0f * std::cos(omega)) / a0;
            filter.b2 = (1.0f - alpha * a) / a0;
            filter.a1 = (-2.0f * std::cos(omega)) / a0;
            filter.a2 = (1.0f - alpha / a) / a0;
        }
    }

    aaudio_data_callback_result_t audio_callback(AAudioStream *, void *user, void *audio_data, int32_t frames) {
        auto &engine = *static_cast<PlaybackEngine *>(user);
        engine.callback_depth.fetch_add(1, std::memory_order_seq_cst);
        auto *output = static_cast<float *>(audio_data);
        const GlobalDspConfig *dsp = engine.dsp_config.load(std::memory_order_acquire);
        const uint64_t dsp_gen = dsp == nullptr ? 0 : dsp->generation;
        if (dsp_gen != engine.applied_dsp_generation) configure_eq(engine, dsp);
        const bool limit_output = engine.norm_enabled.load(std::memory_order_relaxed) &&
            engine.norm_prevent_clip.load(std::memory_order_relaxed);
        float sum_sq_l = 0.0f;
        float sum_sq_r = 0.0f;
        float peak_l = 0.0f;
        float peak_r = 0.0f;
        float sum_low = 0.0f;
        float sum_mid = 0.0f;
        float sum_high = 0.0f;
        const float meter_sr = static_cast<float>(std::max(8'000, engine.output_rate));
        const float meter_alpha_low = std::min(1.0f, 2.0f * static_cast<float>(M_PI) * 240.0f / meter_sr);
        const float meter_alpha_high = meter_sr / (meter_sr + 2.0f * static_cast<float>(M_PI) * 2600.0f);
        for (int32_t i = 0; i < frames; ++i) {
            float left = 0.0f;
            float right = 0.0f;
            if (engine.playing.load(std::memory_order_relaxed)) {
                const int active = engine.active_slot.load(std::memory_order_acquire);
                if (active != kNoSlot) {
                    if (engine.crossfading.load(std::memory_order_acquire)) {
                        const int outgoing = engine.outgoing_slot.load(std::memory_order_acquire);
                        float in_left, in_right, out_left, out_right;
                        read_frame(engine, engine.slots[active], in_left, in_right);
                        if (outgoing != kNoSlot) read_frame(engine, engine.slots[outgoing], out_left, out_right); else out_left = out_right = 0.0f;
                        const int64_t total = std::max<int64_t>(1, engine.fade_frames_total.load(std::memory_order_relaxed));
                        const int64_t at = std::min(engine.fade_frames_rendered.fetch_add(1, std::memory_order_relaxed), total);
                        const float phase = static_cast<float>(at) / static_cast<float>(total) * static_cast<float>(M_PI_2);
                        left = out_left * std::cos(phase) + in_left * std::sin(phase);
                        right = out_right * std::cos(phase) + in_right * std::sin(phase);
                        if (at + 1 >= total) finish_fade_in_callback(engine);
                    } else if (!read_frame(engine, engine.slots[active], left, right)) {
                        const bool active_seek_settled =
                            !engine.slots[active].seek_pending.load(std::memory_order_acquire) &&
                            engine.slots[active].active_seek_generation.load(std::memory_order_acquire) ==
                                engine.slots[active].seek_generation.load(std::memory_order_acquire);
                        const bool active_finished =
                            active_seek_settled && engine.slots[active].finished.load(std::memory_order_acquire);
                        if (should_promote_gapless(
                                active_finished,
                                engine.preloaded_slot.load(std::memory_order_acquire) != kNoSlot)) {
                            promote_gapless(engine);
                            const int promoted = engine.active_slot.load(std::memory_order_acquire);
                            if (promoted != kNoSlot) read_frame(engine, engine.slots[promoted], left, right);
                        } else if (active_finished) {
                            engine.playing.store(false, std::memory_order_release);
                        }
                    }
                }
            }
            const bool cassette_on = engine.cassette_mode_enabled.load(std::memory_order_relaxed);
            if (cassette_on && engine.playing.load(std::memory_order_relaxed)) {
                const size_t w = engine.cassette_delay_write;
                engine.cassette_delay_ring[w * kOutputChannels] = left;
                engine.cassette_delay_ring[w * kOutputChannels + 1] = right;
                const int spin_rem = engine.cassette_spindown_remaining.load(std::memory_order_relaxed);
                const bool scrub_on = engine.cassette_scrub_active.load(std::memory_order_relaxed);
                if (scrub_on || spin_rem > 0) {
                    float target_pitch = 1.0f;
                    float env_gain = 1.0f;
                    float hiss_level = 0.0075f;
                    if (spin_rem > 0) {
                        const int total = std::max(1, engine.cassette_spindown_total.load(std::memory_order_relaxed));
                        const float t = std::clamp(1.0f - static_cast<float>(spin_rem) / static_cast<float>(total), 0.0f, 1.0f);
                        target_pitch = 1.0f - 0.62f * t;
                        env_gain = std::cos(t * static_cast<float>(M_PI_2));
                        hiss_level = 0.0055f * (1.0f - 0.5f * t);
                        engine.cassette_spindown_remaining.fetch_sub(1, std::memory_order_relaxed);
                    } else {
                        target_pitch = std::clamp(engine.cassette_scrub_pitch.load(std::memory_order_relaxed), 0.65f, 1.85f);
                    }
                    engine.cassette_active_pitch += 0.006f * (target_pitch - engine.cassette_active_pitch);
                    constexpr double kGrainFrames = 2048.0;
                    engine.cassette_phase_u += (1.0 - static_cast<double>(engine.cassette_active_pitch)) / kGrainFrames;
                    engine.cassette_phase_u -= std::floor(engine.cassette_phase_u);
                    const double u1 = engine.cassette_phase_u;
                    double u2 = u1 + 0.5;
                    if (u2 >= 1.0) u2 -= 1.0;
                    const double d1 = 16.0 + u1 * kGrainFrames;
                    const double d2 = 16.0 + u2 * kGrainFrames;
                    const float w1 = 1.0f - static_cast<float>(std::abs(2.0 * u1 - 1.0));
                    const float w2 = 1.0f - w1;
                    float l1 = 0.0f, r1 = 0.0f, l2 = 0.0f, r2 = 0.0f;
                    sample_cassette_delay(engine, w, d1, l1, r1);
                    sample_cassette_delay(engine, w, d2, l2, r2);
                    const float shifted_l = l1 * w1 + l2 * w2;
                    const float shifted_r = r1 * w1 + r2 * w2;
                    engine.cassette_tone_lp_l += 0.34f * (shifted_l - engine.cassette_tone_lp_l);
                    engine.cassette_tone_lp_r += 0.34f * (shifted_r - engine.cassette_tone_lp_r);
                    const float n1 = next_white_noise(engine.fun_rng);
                    const float n2 = next_white_noise(engine.fun_rng);
                    engine.cassette_hiss_lp_l += 0.28f * (n1 - engine.cassette_hiss_lp_l);
                    engine.cassette_hiss_lp_r += 0.28f * (n2 - engine.cassette_hiss_lp_r);
                    left = engine.cassette_tone_lp_l * env_gain + engine.cassette_hiss_lp_l * hiss_level;
                    right = engine.cassette_tone_lp_r * env_gain + engine.cassette_hiss_lp_r * hiss_level;
                } else {
                    engine.cassette_active_pitch = 1.0f;
                    engine.cassette_phase_u = 0.0;
                    engine.cassette_tone_lp_l = left;
                    engine.cassette_tone_lp_r = right;
                }
                engine.cassette_delay_write = (w + 1) % PlaybackEngine::kCassetteDelayFrames;
            }
            for (auto &filter : engine.eq_filters) {
                if (!filter.active) continue;
                left = filter_sample(left, filter.left_z1, filter.left_z2, filter);
                right = filter_sample(right, filter.right_z1, filter.right_z2, filter);
            }
            const float preamp = dsp == nullptr ? 1.0f : std::pow(10.0f, dsp->preamp_gain_db / 20.0f);
            const float width = dsp == nullptr ? 1.0f : dsp->stereo_width;
            const float mid = (left + right) * 0.5f;
            const float side = (left - right) * 0.5f * width;
            engine.applied_focus_gain = next_focus_gain(
                engine.applied_focus_gain,
                engine.focus_gain.load(std::memory_order_relaxed),
                engine.output_rate
            );
            float out_l = (mid + side) * preamp * engine.applied_focus_gain;
            float out_r = (mid - side) * preamp * engine.applied_focus_gain;
            const int radio_rem = engine.radio_crosstalk_remaining.load(std::memory_order_relaxed);
            if (radio_rem > 0 && engine.playing.load(std::memory_order_relaxed)) {
                const int radio_total = std::max(1, engine.radio_crosstalk_total.load(std::memory_order_relaxed));
                const float t = std::clamp(1.0f - static_cast<float>(radio_rem) / static_cast<float>(radio_total), 0.0f, 1.0f);
                const float static_env = std::sin(static_cast<float>(M_PI) * t);
                const float music_mix = std::clamp((t - 0.14f) / 0.60f, 0.0f, 1.0f);
                const float nl = next_white_noise(engine.fun_rng);
                const float nr = next_white_noise(engine.fun_rng);
                const float hpl = nl - engine.radio_prev_noise_l;
                const float hpr = nr - engine.radio_prev_noise_r;
                engine.radio_prev_noise_l = nl;
                engine.radio_prev_noise_r = nr;
                engine.radio_bp_l += 0.22f * (hpl - engine.radio_bp_l);
                engine.radio_bp_r += 0.22f * (hpr - engine.radio_bp_r);
                const float carrier_hz = 1550.0f + 780.0f * t;
                engine.radio_carrier_phase += 2.0f * static_cast<float>(M_PI) * carrier_hz / static_cast<float>(std::max(8'000, engine.output_rate));
                if (engine.radio_carrier_phase > 2.0f * static_cast<float>(M_PI)) {
                    engine.radio_carrier_phase -= 2.0f * static_cast<float>(M_PI);
                }
                const float carrier = std::sin(engine.radio_carrier_phase) * 0.0042f * static_env;
                const float radio_l = engine.radio_bp_l * 0.018f * static_env + carrier;
                const float radio_r = engine.radio_bp_r * 0.018f * static_env + carrier * 0.85f;
                out_l = out_l * music_mix + radio_l;
                out_r = out_r * music_mix + radio_r;
                engine.radio_crosstalk_remaining.fetch_sub(1, std::memory_order_relaxed);
            }
            if (limit_output) {
                out_l = soft_knee_limit(out_l);
                out_r = soft_knee_limit(out_r);
            }
            output[i * kOutputChannels] = out_l;
            output[i * kOutputChannels + 1] = out_r;
            sum_sq_l += out_l * out_l;
            sum_sq_r += out_r * out_r;
            const float abs_l = std::abs(out_l);
            const float abs_r = std::abs(out_r);
            if (abs_l > peak_l) peak_l = abs_l;
            if (abs_r > peak_r) peak_r = abs_r;
            const float mono = 0.5f * (out_l + out_r);
            engine.meter_lp_state += meter_alpha_low * (mono - engine.meter_lp_state);
            const float band_low = engine.meter_lp_state;
            const float band_high = meter_alpha_high * (engine.meter_hp_state + mono - engine.meter_hp_prev_in);
            engine.meter_hp_prev_in = mono;
            engine.meter_hp_state = band_high;
            const float band_mid = mono - band_low - band_high;
            sum_low += band_low * band_low;
            sum_mid += band_mid * band_mid;
            sum_high += band_high * band_high;
        }
        if (frames > 0) {
            const float inv_n = 1.0f / static_cast<float>(frames);
            engine.meter_rms_l.store(std::sqrt(sum_sq_l * inv_n), std::memory_order_relaxed);
            engine.meter_rms_r.store(std::sqrt(sum_sq_r * inv_n), std::memory_order_relaxed);
            engine.meter_peak_l.store(peak_l, std::memory_order_relaxed);
            engine.meter_peak_r.store(peak_r, std::memory_order_relaxed);
            engine.meter_band_low.store(std::sqrt(sum_low * inv_n), std::memory_order_relaxed);
            engine.meter_band_mid.store(std::sqrt(sum_mid * inv_n), std::memory_order_relaxed);
            engine.meter_band_high.store(std::sqrt(sum_high * inv_n), std::memory_order_relaxed);
        }
        engine.callback_depth.fetch_sub(1, std::memory_order_release);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    void error_callback(AAudioStream *, void *user, aaudio_result_t error) {
        auto &engine = *static_cast<PlaybackEngine *>(user);
        if (error == AAUDIO_ERROR_DISCONNECTED) {
            engine.output_disconnected.store(true, std::memory_order_release);
            engine.playing.store(false, std::memory_order_release);
        }
    }

    bool open_output(PlaybackEngine &engine, std::string &error) {
        if (engine.stream != nullptr) return true;
        AAudioStreamBuilder *builder = nullptr;
        AAudio_createStreamBuilder(&builder);
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setChannelCount(builder, kOutputChannels);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_POWER_SAVING);
        AAudioStreamBuilder_setDataCallback(builder, audio_callback, &engine);
        AAudioStreamBuilder_setErrorCallback(builder, error_callback, &engine);
        const aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &engine.stream);
        AAudioStreamBuilder_delete(builder);
        if (result != AAUDIO_OK) {
            error = "AAudio output is unavailable";
            return false;
        }
        engine.output_rate = AAudioStream_getSampleRate(engine.stream);
        const int32_t burst = AAudioStream_getFramesPerBurst(engine.stream);
        const size_t safe_burst = burst > 0 ? static_cast<size_t>(burst) : 1'024;
        const size_t out_ahead = burst > 0
            ? std::clamp<size_t>(safe_burst * 6, kDefaultMaxOutAheadFrames, 32'768)
            : kDefaultMaxOutAheadFrames;
        engine.slots[0].output_burst.store(safe_burst, std::memory_order_relaxed);
        engine.slots[1].output_burst.store(safe_burst, std::memory_order_relaxed);
        engine.slots[0].max_out_ahead_frames.store(out_ahead, std::memory_order_relaxed);
        engine.slots[1].max_out_ahead_frames.store(out_ahead, std::memory_order_relaxed);
        return true;
    }

    void close_output(PlaybackEngine &engine) {
        if (engine.stream != nullptr) {
            AAudioStream_requestStop(engine.stream);
            AAudioStream_close(engine.stream);
            engine.stream = nullptr;
        }
    }

    void snap_fade(PlaybackEngine &engine) {
        if (!engine.crossfading.exchange(false, std::memory_order_acq_rel)) return;
        const int outgoing = engine.outgoing_slot.exchange(kNoSlot, std::memory_order_acq_rel);
        if (outgoing != kNoSlot) engine.slots[outgoing].stopping.store(true, std::memory_order_release);
    }

    void destroy_engine(PlaybackEngine &engine) {
        engine.playing.store(false, std::memory_order_release);
        for (auto &slot: engine.slots) slot.stopping.store(true, std::memory_order_release);
        close_output(engine);
        for (auto &slot: engine.slots) release_slot(slot);
    }

    int idle_slot_for_preload(PlaybackEngine &engine) {
        const int active = engine.active_slot.load(std::memory_order_acquire);
        const int preloaded = engine.preloaded_slot.load(std::memory_order_acquire);
        const int outgoing = engine.outgoing_slot.load(std::memory_order_acquire);
        // The outgoing slot remains readable for the entire fade. It must not
        // be selected for another preload until the callback has retired it.
        for (int i = 0; i < 2; ++i) if (i != active && i != preloaded && i != outgoing) return i;
        return kNoSlot;
    }
    bool analyze_loudness_internal(int fd, CustomIoBridge *custom_io, float &out_lufs, float &out_true_peak_db) {
        constexpr int kAnalyzeRate = 48'000;
        constexpr int kSubBlockFrames = 4'800; // 100ms at 48 kHz
        constexpr int64_t kMaxAnalyzeFrames = static_cast<int64_t>(kAnalyzeRate) * 60 * 30; // 30 min safety bound

        AVFormatContext *format = nullptr;
        AVCodecContext *codec = nullptr;
        AVPacket *packet = nullptr;
        AVFrame *frame = nullptr;
        SwrContext *resampler = nullptr;
        std::atomic<bool> stopping{false};
        int open_result = -1;

        auto cleanup = [&]() {
            swr_free(&resampler);
            av_frame_free(&frame);
            av_packet_free(&packet);
            avcodec_free_context(&codec);
            avformat_close_input(&format);
            free_custom_io(custom_io);
        };

        if (custom_io != nullptr) {
            custom_io->stopping = &stopping;
            format = avformat_alloc_context();
            if (format == nullptr) {
                cleanup();
                return false;
            }
            format->pb = custom_io->avio;
            format->flags |= AVFMT_FLAG_CUSTOM_IO;
            format->interrupt_callback.callback = avio_interrupt_cb;
            format->interrupt_callback.opaque = custom_io;
            open_result = avformat_open_input(&format, nullptr, nullptr, nullptr);
        } else {
            const std::string path = "/proc/self/fd/" + std::to_string(fd);
            open_result = avformat_open_input(&format, path.c_str(), nullptr, nullptr);
        }
        if (open_result < 0 || avformat_find_stream_info(format, nullptr) < 0) {
            cleanup();
            return false;
        }
        const int audio_stream = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
        if (audio_stream < 0) {
            cleanup();
            return false;
        }
        const AVCodecParameters *parameters = format->streams[audio_stream]->codecpar;
        const AVCodec *decoder = avcodec_find_decoder(parameters->codec_id);
        if (decoder == nullptr) {
            cleanup();
            return false;
        }
        codec = avcodec_alloc_context3(decoder);
        if (codec == nullptr || avcodec_parameters_to_context(codec, parameters) < 0 || avcodec_open2(codec, decoder, nullptr) < 0) {
            cleanup();
            return false;
        }
        AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
        if (swr_alloc_set_opts2(&resampler, &stereo, AV_SAMPLE_FMT_FLT, kAnalyzeRate,
                &codec->ch_layout, codec->sample_fmt, codec->sample_rate, 0, nullptr) < 0 ||
                swr_init(resampler) < 0) {
            cleanup();
            return false;
        }
        packet = av_packet_alloc();
        frame = av_frame_alloc();
        if (packet == nullptr || frame == nullptr) {
            cleanup();
            return false;
        }

        const Biquad stage1 = make_bs1770_stage1(kAnalyzeRate);
        const Biquad stage2 = make_bs1770_stage2(kAnalyzeRate);
        Biquad s1 = stage1;
        Biquad s2 = stage2;

        std::vector<double> sub_block_energies;
        double current_sub_energy = 0.0;
        int current_sub_frames = 0;
        float max_true_peak = 1e-6f;
        float prev1_l = 0.0f, prev2_l = 0.0f, prev3_l = 0.0f;
        float prev1_r = 0.0f, prev2_r = 0.0f, prev3_r = 0.0f;
        int64_t total_frames = 0;
        std::vector<float> pcm;

        auto process_pcm = [&](const float *data, int frames_count) {
            for (int i = 0; i < frames_count; ++i) {
                const float l = data[i * 2];
                const float r = data[i * 2 + 1];
                max_true_peak = std::max(max_true_peak, std::max(std::abs(l), std::abs(r)));
                // 4x Catmull-Rom oversampled inter-sample peak estimation
                if (total_frames >= 3) {
                    auto eval_cubic = [](float p0, float p1, float p2, float p3, float t) -> float {
                        const float t2 = t * t;
                        const float t3 = t2 * t;
                        return 0.5f * ((2.0f * p1) +
                            (-p0 + p2) * t +
                            (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t2 +
                            (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t3);
                    };
                    for (float t : {0.25f, 0.5f, 0.75f}) {
                        max_true_peak = std::max(max_true_peak, std::abs(eval_cubic(prev3_l, prev2_l, prev1_l, l, t)));
                        max_true_peak = std::max(max_true_peak, std::abs(eval_cubic(prev3_r, prev2_r, prev1_r, r, t)));
                    }
                }
                prev3_l = prev2_l; prev2_l = prev1_l; prev1_l = l;
                prev3_r = prev2_r; prev2_r = prev1_r; prev1_r = r;

                float kl = filter_sample(l, s1.left_z1, s1.left_z2, s1);
                kl = filter_sample(kl, s2.left_z1, s2.left_z2, s2);
                float kr = filter_sample(r, s1.right_z1, s1.right_z2, s1);
                kr = filter_sample(kr, s2.right_z1, s2.right_z2, s2);
                current_sub_energy += static_cast<double>(kl) * kl + static_cast<double>(kr) * kr;
                current_sub_frames += 1;
                if (current_sub_frames >= kSubBlockFrames) {
                    sub_block_energies.push_back(current_sub_energy / static_cast<double>(kSubBlockFrames));
                    current_sub_energy = 0.0;
                    current_sub_frames = 0;
                }
                total_frames += 1;
            }
        };

        auto drain_codec = [&]() {
            while (avcodec_receive_frame(codec, frame) >= 0) {
                const int out_frames = swr_get_out_samples(resampler, frame->nb_samples);
                pcm.resize(static_cast<size_t>(out_frames) * kOutputChannels);
                uint8_t *out_data[] = {reinterpret_cast<uint8_t *>(pcm.data())};
                const int converted = swr_convert(resampler, out_data, out_frames,
                    const_cast<const uint8_t **>(frame->extended_data), frame->nb_samples);
                if (converted > 0) process_pcm(pcm.data(), converted);
                if (total_frames >= kMaxAnalyzeFrames) break;
            }
        };

        while (total_frames < kMaxAnalyzeFrames) {
            const int read = av_read_frame(format, packet);
            if (read < 0) {
                av_packet_unref(packet);
                break;
            }
            if (packet->stream_index == audio_stream && avcodec_send_packet(codec, packet) >= 0) {
                drain_codec();
            }
            av_packet_unref(packet);
        }
        if (total_frames < kMaxAnalyzeFrames && avcodec_send_packet(codec, nullptr) >= 0) {
            drain_codec();
        }
        while (total_frames < kMaxAnalyzeFrames) {
            const int out_frames = std::max(256, swr_get_out_samples(resampler, 0));
            pcm.resize(static_cast<size_t>(out_frames) * kOutputChannels);
            uint8_t *out_data[] = {reinterpret_cast<uint8_t *>(pcm.data())};
            const int flushed = swr_convert(resampler, out_data, out_frames, nullptr, 0);
            if (flushed <= 0) break;
            process_pcm(pcm.data(), flushed);
        }

        cleanup();
        if (total_frames == 0) return false;

        out_true_peak_db = 20.0f * std::log10(std::max(1e-6f, max_true_peak));
        if (sub_block_energies.size() < 4) {
            // Very short audio clip (< 400ms): integrate over whole clip
            const double mean_e = current_sub_frames > 0
                ? (current_sub_energy / static_cast<double>(current_sub_frames))
                : 1e-10;
            out_lufs = static_cast<float>(std::max(-70.0, -0.691 + 10.0 * std::log10(std::max(1e-10, mean_e))));
            return true;
        }

        // BS.1770-4 400ms overlapping blocks (75% overlap = step 100ms)
        std::vector<double> blocks;
        blocks.reserve(sub_block_energies.size() - 3);
        for (size_t i = 0; i + 3 < sub_block_energies.size(); ++i) {
            const double e = (sub_block_energies[i] + sub_block_energies[i + 1] +
                sub_block_energies[i + 2] + sub_block_energies[i + 3]) * 0.25;
            const double l = -0.691 + 10.0 * std::log10(std::max(1e-12, e));
            if (l > -70.0) blocks.push_back(e);
        }
        if (blocks.empty()) {
            out_lufs = -70.0f;
            return true;
        }
        double abs_sum = 0.0;
        for (double e : blocks) abs_sum += e;
        const double abs_mean = abs_sum / static_cast<double>(blocks.size());
        const double rel_threshold_db = -0.691 + 10.0 * std::log10(std::max(1e-12, abs_mean)) - 10.0;
        double rel_sum = 0.0;
        size_t rel_count = 0;
        for (double e : blocks) {
            const double l = -0.691 + 10.0 * std::log10(std::max(1e-12, e));
            if (l > rel_threshold_db) {
                rel_sum += e;
                rel_count += 1;
            }
        }
        if (rel_count == 0) {
            out_lufs = -70.0f;
            return true;
        }
        const double gated_mean = rel_sum / static_cast<double>(rel_count);
        out_lufs = static_cast<float>(std::clamp(-0.691 + 10.0 * std::log10(std::max(1e-12, gated_mean)), -70.0, 0.0));
        return true;
    }
}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeCreate(JNIEnv *, jclass) {
    auto *engine = new PlaybackEngine();
    auto config = std::make_unique<GlobalDspConfig>();
    config->generation = ++engine->next_dsp_generation;
    engine->dsp_config.store(config.get(), std::memory_order_release);
    engine->dsp_configs.push_back(std::move(config));
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeDestroy(JNIEnv *, jclass, jlong value) {
    auto *engine = reinterpret_cast<PlaybackEngine *>(value);
    if (engine == nullptr) return;
    destroy_engine(*engine);
    delete engine;
}

extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativePrepare(
        JNIEnv *env, jclass, jlong value, jint fd, jfloat gain, jboolean has_static_analysis) {
    auto *engine = reinterpret_cast<PlaybackEngine *>(value);
    std::string error;
    if (engine == nullptr || !open_output(*engine, error) ||
            !open_slot(engine->slots[0], fd, engine->output_rate, gain, has_static_analysis == JNI_TRUE, error)) {
        close(fd);
        throw_java(env, error.empty() ? "Player is closed" : error.c_str());
        return;
    }
    close(fd);
    release_slot(engine->slots[1]);
    engine->active_slot.store(0, std::memory_order_release);
    engine->preloaded_slot.store(kNoSlot, std::memory_order_release);
    engine->outgoing_slot.store(kNoSlot, std::memory_order_release);
    engine->output_disconnected.store(false, std::memory_order_release);
    engine->transition_event.store(kTransitionNone, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativePrepareStream(
        JNIEnv *env, jclass, jlong value, jobject source, jobject direct_buffer, jfloat gain, jboolean has_static_analysis) {
    auto *engine = reinterpret_cast<PlaybackEngine *>(value);
    std::string error;
    if (engine == nullptr || !open_output(*engine, error)) {
        throw_java(env, error.empty() ? "Player is closed" : error.c_str());
        return;
    }
    release_slot(engine->slots[0]);
    CustomIoBridge *bridge = create_custom_io(env, source, direct_buffer, &engine->slots[0].stopping, error);
    if (bridge == nullptr ||
            !open_slot(engine->slots[0], -1, engine->output_rate, gain, has_static_analysis == JNI_TRUE, error, bridge)) {
        throw_java(env, error.empty() ? "Unable to open audio stream" : error.c_str());
        return;
    }
    release_slot(engine->slots[1]);
    engine->active_slot.store(0, std::memory_order_release);
    engine->preloaded_slot.store(kNoSlot, std::memory_order_release);
    engine->outgoing_slot.store(kNoSlot, std::memory_order_release);
    engine->output_disconnected.store(false, std::memory_order_release);
    engine->transition_event.store(kTransitionNone, std::memory_order_release);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativePreload(
        JNIEnv *env, jclass, jlong value, jint fd, jfloat gain, jboolean has_static_analysis) {
    auto *engine = reinterpret_cast<PlaybackEngine *>(value);
    std::string error;
    if (engine == nullptr || engine->active_slot.load() == kNoSlot) {
        close(fd);
        throw_java(env, "Player is not prepared");
        return JNI_FALSE;
    }
    if (engine->preloaded_slot.load() != kNoSlot) {
        const int previous = engine->preloaded_slot.exchange(kNoSlot);
        wait_for_callback_quiescence(*engine);
        release_slot(engine->slots[previous]);
    }
    const int target = idle_slot_for_preload(*engine);
    wait_for_callback_quiescence(*engine);
    if (target == kNoSlot) {
        close(fd);
        throw_java(env, "No idle source slot");
        return JNI_FALSE;
    }
    // A retired source can still own its worker and FFmpeg allocations. Join
    // and clear it before placing the next preload in that slot; otherwise a
    // new std::thread assignment would terminate the process.
    release_slot(engine->slots[target]);
    if (!open_slot(engine->slots[target], fd, engine->output_rate, gain, has_static_analysis == JNI_TRUE, error)) {
        close(fd);
        throw_java(env, error.empty() ? "Unable to preload source" : error.c_str());
        return JNI_FALSE;
    }
    close(fd);
    engine->preloaded_slot.store(target, std::memory_order_release);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativePreloadStream(
        JNIEnv *env, jclass, jlong value, jobject source, jobject direct_buffer, jfloat gain, jboolean has_static_analysis) {
    auto *engine = reinterpret_cast<PlaybackEngine *>(value);
    std::string error;
    if (engine == nullptr || engine->active_slot.load() == kNoSlot) {
        throw_java(env, "Player is not prepared");
        return JNI_FALSE;
    }
    if (engine->preloaded_slot.load() != kNoSlot) {
        const int previous = engine->preloaded_slot.exchange(kNoSlot);
        wait_for_callback_quiescence(*engine);
        release_slot(engine->slots[previous]);
    }
    const int target = idle_slot_for_preload(*engine);
    wait_for_callback_quiescence(*engine);
    if (target == kNoSlot) {
        throw_java(env, "No idle source slot");
        return JNI_FALSE;
    }
    release_slot(engine->slots[target]);
    CustomIoBridge *bridge = create_custom_io(env, source, direct_buffer, &engine->slots[target].stopping, error);
    if (bridge == nullptr ||
            !open_slot(engine->slots[target], -1, engine->output_rate, gain, has_static_analysis == JNI_TRUE, error, bridge)) {
        throw_java(env, error.empty() ? "Unable to preload audio stream" : error.c_str());
        return JNI_FALSE;
    }
    engine->preloaded_slot.store(target, std::memory_order_release);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeSetNormalizationConfig(
        JNIEnv *, jclass, jlong value, jboolean enabled, jfloat target_lufs, jboolean prevent_clip) {
    auto *engine = reinterpret_cast<PlaybackEngine *>(value);
    if (engine == nullptr) return;
    engine->norm_enabled.store(enabled == JNI_TRUE, std::memory_order_release);
    engine->norm_target_lufs.store(std::clamp(target_lufs, -30.0f, -5.0f), std::memory_order_release);
    engine->norm_prevent_clip.store(prevent_clip == JNI_TRUE, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeSetNormalizationGains(
        JNIEnv *, jclass, jlong value, jfloat active_gain, jboolean active_has_static,
        jfloat preloaded_gain, jboolean preloaded_has_static) {
    auto *engine = reinterpret_cast<PlaybackEngine *>(value);
    if (engine == nullptr) return;
    const int active = engine->active_slot.load(std::memory_order_acquire);
    if (active != kNoSlot) {
        engine->slots[active].normalization_gain_db.store(active_gain, std::memory_order_release);
        engine->slots[active].has_static_analysis.store(active_has_static == JNI_TRUE, std::memory_order_release);
    }
    const int preloaded = engine->preloaded_slot.load(std::memory_order_acquire);
    if (preloaded != kNoSlot) {
        engine->slots[preloaded].normalization_gain_db.store(preloaded_gain, std::memory_order_release);
        engine->slots[preloaded].has_static_analysis.store(preloaded_has_static == JNI_TRUE, std::memory_order_release);
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeAnalyzeFd(
        JNIEnv *env, jclass, jint fd) {
    float lufs = -14.0f;
    float peak = 0.0f;
    const bool ok = analyze_loudness_internal(fd, nullptr, lufs, peak);
    close(fd);
    if (!ok) return nullptr;
    jfloatArray out = env->NewFloatArray(2);
    if (out == nullptr) return nullptr;
    jfloat vals[2] = {lufs, peak};
    env->SetFloatArrayRegion(out, 0, 2, vals);
    return out;
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeAnalyzeStream(
        JNIEnv *env, jclass, jobject source, jobject direct_buffer) {
    std::string error;
    std::atomic<bool> dummy_stop{false};
    CustomIoBridge *bridge = create_custom_io(env, source, direct_buffer, &dummy_stop, error);
    if (bridge == nullptr) return nullptr;
    float lufs = -14.0f;
    float peak = 0.0f;
    if (!analyze_loudness_internal(-1, bridge, lufs, peak)) return nullptr;
    jfloatArray out = env->NewFloatArray(2);
    if (out == nullptr) return nullptr;
    jfloat vals[2] = {lufs, peak};
    env->SetFloatArrayRegion(out, 0, 2, vals);
    return out;
}
extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeSetFocusGain(JNIEnv *, jclass, jlong value, jfloat gain) {
    auto *engine = reinterpret_cast<PlaybackEngine *>(value);
    if (engine != nullptr) engine->focus_gain.store(std::clamp(gain, 0.0f, 1.0f), std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeClearPreloaded(JNIEnv *, jclass, jlong value) {
    auto *engine = reinterpret_cast<PlaybackEngine *>(value);
    if (engine == nullptr) return;
    const int slot = engine->preloaded_slot.exchange(kNoSlot);
    if (slot != kNoSlot) {
        wait_for_callback_quiescence(*engine);
        release_slot(engine->slots[slot]);
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeHasPreloaded(JNIEnv *, jclass, jlong value) {
    auto *engine = reinterpret_cast<PlaybackEngine *>(value);
    return engine != nullptr && engine->preloaded_slot.load(std::memory_order_acquire) != kNoSlot;
}

extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeBeginCrossfade(JNIEnv *, jclass, jlong value, jlong duration_ms) {
    auto *engine = reinterpret_cast<PlaybackEngine *>(value);
    if (engine == nullptr || engine->crossfading.load() || engine->preloaded_slot.load() == kNoSlot) return;
    const int incoming = engine->preloaded_slot.exchange(kNoSlot);
    const int outgoing = engine->active_slot.exchange(incoming);
    engine->outgoing_slot.store(outgoing, std::memory_order_release);
    engine->fade_frames_total.store(std::max<int64_t>(1, duration_ms * engine->output_rate / 1000), std::memory_order_release);
    engine->fade_frames_rendered.store(0, std::memory_order_release);
    engine->crossfading.store(true, std::memory_order_release);
    engine->transition_event.store(kTransitionCrossfadeStarted, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeFinishCrossfade(JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    if (e != nullptr) snap_fade(*e);
}
extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeSnapCrossfade(JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    if (e != nullptr) snap_fade(*e);
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeIsCrossfading(JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    return e != nullptr && e->crossfading.load();
}
extern "C" JNIEXPORT jint JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeConsumeTransition(JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    return e == nullptr ? kTransitionNone : e->transition_event.exchange(kTransitionNone);
}

extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeSetGlobalDspConfig(JNIEnv *env, jclass, jlong value, jfloat preamp, jfloat width, jfloatArray eq) {
    auto *engine = reinterpret_cast<PlaybackEngine *>(value);
    if (engine == nullptr) return;
    auto config = std::make_unique<GlobalDspConfig>();
    config->preamp_gain_db = preamp;
    config->stereo_width = width;
    if (eq != nullptr) {
        const jsize count = std::min<jsize>(env->GetArrayLength(eq), kEqBands);
        env->GetFloatArrayRegion(eq, 0, count, config->eq_band_gains_db);
    }
    std::lock_guard<std::mutex> lock(engine->dsp_write_mutex);
    config->generation = ++engine->next_dsp_generation;
    const GlobalDspConfig *raw = config.get();
    engine->dsp_configs.push_back(std::move(config));
    engine->dsp_config.store(raw, std::memory_order_seq_cst);
    if (engine->dsp_configs.size() > 32 && engine->callback_depth.load(std::memory_order_seq_cst) != 0) {
        wait_for_callback_quiescence(*engine);
    }
    if (engine->callback_depth.load(std::memory_order_seq_cst) == 0 && engine->dsp_configs.size() > 1) {
        engine->dsp_configs.erase(engine->dsp_configs.begin(), engine->dsp_configs.end() - 1);
    }
}
extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativePlay(JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    if (e != nullptr && e->stream != nullptr) {
        e->playing.store(true);
        AAudioStream_requestStart(e->stream);
    }
}
extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativePause(JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    if (e != nullptr) {
        snap_fade(*e);
        e->playing.store(false);
        if (e->stream != nullptr) AAudioStream_requestPause(e->stream);
    }
}
extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeStop(JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    if (e != nullptr) {
        snap_fade(*e);
        e->playing.store(false);
        if (e->stream != nullptr) AAudioStream_requestPause(e->stream);
    }
}
extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeSetSpeed(JNIEnv *, jclass, jlong value, jfloat speed) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    if (e == nullptr) return;
    const float clamped = std::clamp(speed, 0.5f, 2.0f);
    e->slots[0].speed.store(clamped, std::memory_order_release);
    e->slots[1].speed.store(clamped, std::memory_order_release);
}
extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeSeekTo(JNIEnv *env, jclass, jlong value, jlong ms) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    if (e == nullptr) return;
    snap_fade(*e);
    const int active = e->active_slot.load(std::memory_order_acquire);
    if (active == kNoSlot) return;
    auto &s = e->slots[active];
    const int64_t target_us = std::max<int64_t>(0, ms) * 1000;
    s.position_base_us.store(target_us, std::memory_order_release);
    s.current_pts_us.store(target_us, std::memory_order_release);
    s.has_rendered_pts.store(false, std::memory_order_release);
    s.rendered_frames.store(0, std::memory_order_release);
    s.seek_us.store(target_us, std::memory_order_release);
    s.reset_meter_filters.store(true, std::memory_order_release);
    s.input_exhausted.store(false, std::memory_order_release);
    s.finished.store(false, std::memory_order_release);
    s.rebuffering.store(false, std::memory_order_release);
    s.rebuffer_fade_in_rem.store(0, std::memory_order_release);
    s.steady_play_frames.store(0, std::memory_order_release);
    const uint64_t next_gen = s.seek_generation.fetch_add(1, std::memory_order_seq_cst) + 1;
    s.seek_pending.store(true, std::memory_order_seq_cst);
    abort_custom_io_seek(env, s, next_gen);
}
extern "C" JNIEXPORT jlong JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeDurationMs(JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    const int i = e == nullptr ? kNoSlot : e->active_slot.load();
    auto *s = i == kNoSlot ? nullptr : &e->slots[i];
    return s != nullptr && s->format != nullptr && s->format->duration > 0 ? s->format->duration / 1000 : 0;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativePositionMs(JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    const int i = e == nullptr ? kNoSlot : e->active_slot.load();
    if (i == kNoSlot) return 0;
    auto &s = e->slots[i];
    const bool seek_settled =
        !s.seek_pending.load(std::memory_order_acquire) &&
        s.active_seek_generation.load(std::memory_order_acquire) ==
            s.seek_generation.load(std::memory_order_acquire);
    if (seek_settled && s.has_rendered_pts.load(std::memory_order_acquire)) {
        return std::max<int64_t>(0, s.current_pts_us.load(std::memory_order_acquire) / 1000);
    }
    return (s.position_base_us.load(std::memory_order_acquire) +
            av_rescale(s.rendered_frames.load(std::memory_order_acquire), AV_TIME_BASE, s.output_rate)) / 1000;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativePreloadedDurationMs(JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    const int i = e == nullptr ? kNoSlot : e->preloaded_slot.load();
    auto *s = i == kNoSlot ? nullptr : &e->slots[i];
    return s != nullptr && s->format != nullptr && s->format->duration > 0 ? s->format->duration / 1000 : 0;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativePreloadedPositionMs(JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    const int i = e == nullptr ? kNoSlot : e->preloaded_slot.load();
    if (i == kNoSlot) return 0;
    auto &s = e->slots[i];
    const bool seek_settled =
        !s.seek_pending.load(std::memory_order_acquire) &&
        s.active_seek_generation.load(std::memory_order_acquire) ==
            s.seek_generation.load(std::memory_order_acquire);
    if (seek_settled && s.has_rendered_pts.load(std::memory_order_acquire)) {
        return std::max<int64_t>(0, s.current_pts_us.load(std::memory_order_acquire) / 1000);
    }
    return (s.position_base_us.load(std::memory_order_acquire) +
            av_rescale(s.rendered_frames.load(std::memory_order_acquire), AV_TIME_BASE, s.output_rate)) / 1000;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeIsFinished(JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    const int i = e == nullptr ? kNoSlot : e->active_slot.load();
    if (i == kNoSlot) return JNI_FALSE;
    auto &s = e->slots[i];
    const bool seek_settled =
        !s.seek_pending.load(std::memory_order_acquire) &&
        s.active_seek_generation.load(std::memory_order_acquire) ==
            s.seek_generation.load(std::memory_order_acquire);
    return seek_settled && s.finished.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeIsBuffering(JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    const int i = e == nullptr ? kNoSlot : e->active_slot.load(std::memory_order_acquire);
    if (i == kNoSlot) return JNI_FALSE;
    auto &s = e->slots[i];
    if (s.custom_io == nullptr) return JNI_FALSE;
    const bool seek_settled =
        !s.seek_pending.load(std::memory_order_acquire) &&
        s.active_seek_generation.load(std::memory_order_acquire) ==
            s.seek_generation.load(std::memory_order_acquire);
    if (seek_settled && s.finished.load(std::memory_order_acquire)) return JNI_FALSE;
    return (!seek_settled || s.rebuffering.load(std::memory_order_acquire)) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeBufferedAheadMs(JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    const int i = e == nullptr ? kNoSlot : e->active_slot.load(std::memory_order_acquire);
    if (i == kNoSlot) return 0;
    auto &s = e->slots[i];
    const bool seek_settled =
        !s.seek_pending.load(std::memory_order_acquire) &&
        s.active_seek_generation.load(std::memory_order_acquire) ==
            s.seek_generation.load(std::memory_order_acquire);
    if (!seek_settled) return 0;
    const size_t total_frames = available_raw(s) + available_out(s);
    const int64_t rate = std::max(8'000, s.output_rate);
    return static_cast<jlong>((static_cast<int64_t>(total_frames) * 1000LL) / rate);
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeIsOutputDisconnected(JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    return e != nullptr && e->output_disconnected.load();
}
extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeSetCassetteEnabled(
        JNIEnv *, jclass, jlong value, jboolean enabled) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    if (e == nullptr) return;
    const bool on = enabled == JNI_TRUE;
    e->cassette_mode_enabled.store(on, std::memory_order_release);
    if (!on) {
        e->cassette_scrub_active.store(false, std::memory_order_release);
        e->cassette_spindown_remaining.store(0, std::memory_order_release);
    }
}
extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeSetCassetteScrub(
        JNIEnv *, jclass, jlong value, jboolean active, jfloat pitch) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    if (e == nullptr) return;
    e->cassette_scrub_pitch.store(std::clamp(pitch, 0.65f, 1.85f), std::memory_order_release);
    e->cassette_scrub_active.store(active == JNI_TRUE, std::memory_order_release);
}
extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeTriggerCassetteSpinDown(
        JNIEnv *, jclass, jlong value, jint duration_ms) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    if (e == nullptr) return;
    const int samples = std::max(1, static_cast<int>(static_cast<int64_t>(std::clamp(duration_ms, 40, 800)) * e->output_rate / 1000));
    e->cassette_spindown_total.store(samples, std::memory_order_release);
    e->cassette_spindown_remaining.store(samples, std::memory_order_release);
}
extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeCancelCassetteSpinDown(
        JNIEnv *, jclass, jlong value) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    if (e != nullptr) e->cassette_spindown_remaining.store(0, std::memory_order_release);
}
extern "C" JNIEXPORT void JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeTriggerRadioCrosstalk(
        JNIEnv *, jclass, jlong value, jint duration_ms) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    if (e == nullptr) return;
    const int samples = std::max(1, static_cast<int>(static_cast<int64_t>(std::clamp(duration_ms, 100, 1500)) * e->output_rate / 1000));
    e->radio_crosstalk_total.store(samples, std::memory_order_release);
    e->radio_crosstalk_remaining.store(samples, std::memory_order_release);
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_exodidio_tune_player_FfmpegDecoder_nativeReadAudioLevels(
        JNIEnv *env, jclass, jlong value, jfloatArray out_levels) {
    auto *e = reinterpret_cast<PlaybackEngine *>(value);
    if (e == nullptr || out_levels == nullptr) return JNI_FALSE;
    if (env->GetArrayLength(out_levels) < 7) return JNI_FALSE;
    float buf[7] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    if (e->playing.load(std::memory_order_relaxed)) {
        buf[0] = e->meter_rms_l.load(std::memory_order_relaxed);
        buf[1] = e->meter_rms_r.load(std::memory_order_relaxed);
        buf[2] = e->meter_peak_l.load(std::memory_order_relaxed);
        buf[3] = e->meter_peak_r.load(std::memory_order_relaxed);
        buf[4] = e->meter_band_low.load(std::memory_order_relaxed);
        buf[5] = e->meter_band_mid.load(std::memory_order_relaxed);
        buf[6] = e->meter_band_high.load(std::memory_order_relaxed);
    }
    env->SetFloatArrayRegion(out_levels, 0, 7, buf);
    return JNI_TRUE;
}
