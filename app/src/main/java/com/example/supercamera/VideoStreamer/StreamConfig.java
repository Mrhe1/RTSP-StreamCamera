package com.example.supercamera.VideoStreamer;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AV1;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H265;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_RAWVIDEO;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_VP8;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_VP9;

import com.example.supercamera.StreamPusher.PushConfig;
import com.example.supercamera.VideoEncoder.MyEncoderConfig;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

public class StreamConfig {
    public final String url;
    public final int width;
    public final int height;
    public final int fps;
    public final int bitrateKbps;
    public final int maxReconnectAttempts;
    public final int reconnectPeriodSeconds;
    public final int iFrameInterval;
    private final PushConfig pushConfig;
    private final MyEncoderConfig encoderConfig;
    public final String encoderFormat;

    private StreamConfig(Builder builder) {
        this.url = builder.url;
        this.width = builder.width;
        this.height = builder.height;
        this.fps = builder.fps;
        this.bitrateKbps = builder.bitrateKbps;
        this.maxReconnectAttempts = builder.maxReconnectAttempts;
        this.reconnectPeriodSeconds = builder.reconnectPeriodSeconds;
        this.iFrameInterval = builder.iFrameInterval;
        this.encoderFormat = builder.encoderFormat;

        // 自动构建PushConfig（内部处理单位转换）
        this.pushConfig = new PushConfig.Builder(this.url,this.width, this.height)
                .setFPS(this.fps)
                .setBitrateKbps(this.bitrateKbps)
                .serReconnectParam(this.maxReconnectAttempts, this.reconnectPeriodSeconds)
                .setCodecID(setFFmpegCodecID(this.encoderFormat))
                .build();

        // 自动构建MyEncoderConfig
        this.encoderConfig = new MyEncoderConfig.Builder(this.width, this.height)
                .setBitrate(this.bitrateKbps) // 直接传入kbps
                .setFps(this.fps)
                .setIFrameInterval(this.iFrameInterval)
                .setColorFormat(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                .setProfile(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                .setMimeType(this.encoderFormat)
                .enableLowLatency()
                .build();
    }

    public PushConfig getPushConfig() {
        return pushConfig;
    }

    public MyEncoderConfig getEncoderConfig() {
        return encoderConfig;
    }

    private int setFFmpegCodecID(String encoderFormat) {
        return switch (encoderFormat) {
            case MediaFormat.MIMETYPE_VIDEO_AVC ->  AV_CODEC_ID_H264;
            case MediaFormat.MIMETYPE_VIDEO_HEVC -> AV_CODEC_ID_H265;
            case MediaFormat.MIMETYPE_VIDEO_VP9 -> AV_CODEC_ID_VP9;
            case MediaFormat.MIMETYPE_VIDEO_VP8 -> AV_CODEC_ID_VP8;
            case MediaFormat.MIMETYPE_VIDEO_AV1 -> AV_CODEC_ID_AV1;
            case MediaFormat.MIMETYPE_VIDEO_RAW -> AV_CODEC_ID_RAWVIDEO;
            default -> -1;
        };
    }

    public static class Builder {
        private String url;
        private int width;
        private int height;
        private int fps = 30;
        private int bitrateKbps = 4000; // 默认4000kbps（4Mbps）
        private int maxReconnectAttempts = 4;
        private int reconnectPeriodSeconds = 4;
        private int iFrameInterval = 1;
        private String encoderFormat = MediaFormat.MIMETYPE_VIDEO_AVC;

        public Builder(String url,int width, int height) {
            this.url = url;
            this.width = width;
            this.height = height;
        }

        public Builder setFps(int fps) {
            this.fps = fps;
            return this;
        }

        public Builder setBitrateKbps(int bitrateKbps) {
            this.bitrateKbps = bitrateKbps;
            return this;
        }

        public Builder setReconnectParams(int maxAttempts, int periodSeconds) {
            this.maxReconnectAttempts = maxAttempts;
            this.reconnectPeriodSeconds = periodSeconds;
            return this;
        }

        public Builder setIFrameInterval(int interval) {
            this.iFrameInterval = interval;
            return this;
        }

        public Builder setEncoderFormat(String format) {
            this.encoderFormat = format;
            return this;
        }

        public StreamConfig build() {
            return new StreamConfig(this);
        }
    }
}
