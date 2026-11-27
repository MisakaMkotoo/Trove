// Whole-track PCM extraction for lyric alignment: 16 kHz mono float32.
// Decoding stays local (FFmpeg); the alignment models never touch the network.
#include <jni.h>

#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libswresample/swresample.h>
}

namespace {
    constexpr int kTargetRate = 16000;
    // 20 minutes of 16 kHz mono float ≈ 77 MB; refuse anything longer.
    constexpr int64_t kMaxSamples = static_cast<int64_t>(kTargetRate) * 60 * 20;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_exodidio_tune_lyrics_autosync_AutoSyncPcmKt_nativeDecodePcm16k(JNIEnv *env, jclass, jstring source) {
    if (source == nullptr) return nullptr;
    const char *raw = env->GetStringUTFChars(source, nullptr);
    if (raw == nullptr) return nullptr;
    std::string path(raw);
    env->ReleaseStringUTFChars(source, raw);

    AVFormatContext *format = nullptr;
    if (avformat_open_input(&format, path.c_str(), nullptr, nullptr) < 0) return nullptr;
    if (avformat_find_stream_info(format, nullptr) < 0) {
        avformat_close_input(&format);
        return nullptr;
    }
    const int stream_index = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (stream_index < 0) {
        avformat_close_input(&format);
        return nullptr;
    }
    AVStream *stream = format->streams[stream_index];
    const AVCodec *codec = avcodec_find_decoder(stream->codecpar->codec_id);
    AVCodecContext *context = codec ? avcodec_alloc_context3(codec) : nullptr;
    if (context == nullptr || avcodec_parameters_to_context(context, stream->codecpar) < 0 ||
        avcodec_open2(context, codec, nullptr) < 0) {
        avcodec_free_context(&context);
        avformat_close_input(&format);
        return nullptr;
    }

    AVChannelLayout mono = AV_CHANNEL_LAYOUT_MONO;
    SwrContext *resampler = nullptr;
    if (swr_alloc_set_opts2(&resampler, &mono, AV_SAMPLE_FMT_FLT, kTargetRate,
                            &context->ch_layout, context->sample_fmt, context->sample_rate, 0,
                            nullptr) < 0 ||
        swr_init(resampler) < 0) {
        swr_free(&resampler);
        avcodec_free_context(&context);
        avformat_close_input(&format);
        return nullptr;
    }

    std::vector<float> samples;
    samples.reserve(kTargetRate * 180);
    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    bool too_long = false;
    while (packet != nullptr && frame != nullptr && !too_long) {
        if (av_read_frame(format, packet) < 0) break;
        if (packet->stream_index != stream_index) {
            av_packet_unref(packet);
            continue;
        }
        if (avcodec_send_packet(context, packet) < 0) {
            av_packet_unref(packet);
            continue;
        }
        av_packet_unref(packet);
        while (true) {
            const int ret = avcodec_receive_frame(context, frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
            if (ret < 0) break;
            const int out_capacity = swr_get_out_samples(resampler, frame->nb_samples);
            if (out_capacity <= 0) continue;
            const size_t offset = samples.size();
            samples.resize(offset + static_cast<size_t>(out_capacity));
            uint8_t *out_ptr = reinterpret_cast<uint8_t *>(samples.data() + offset);
            const int converted = swr_convert(resampler, &out_ptr, out_capacity,
                                              const_cast<const uint8_t **>(frame->extended_data),
                                              frame->nb_samples);
            if (converted > 0) {
                samples.resize(offset + static_cast<size_t>(converted));
                if (static_cast<int64_t>(samples.size()) >= kMaxSamples) {
                    too_long = true;
                    break;
                }
            } else {
                samples.resize(offset);
            }
            if (converted <= 0) break;
        }
    }

    av_frame_free(&frame);
    av_packet_free(&packet);
    swr_free(&resampler);
    avcodec_free_context(&context);
    avformat_close_input(&format);

    if (samples.empty()) return nullptr;
    if (static_cast<int64_t>(samples.size()) > kMaxSamples) samples.resize(kMaxSamples);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(samples.size()));
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(samples.size()), samples.data());
    }
    return result;
}
