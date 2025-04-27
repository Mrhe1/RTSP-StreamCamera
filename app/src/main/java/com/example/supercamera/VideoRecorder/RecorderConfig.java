package com.example.supercamera.VideoRecorder;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;

public class RecorderConfig {
    public final int width;
    public final int height;
    public final int fps;
    public final int iFrameInterval;
    public final String mimeType;
    public final int profile;
    public final int bitrate; // 4mbps

    private RecorderConfig(Builder builder) {
        this.width = builder.width;
        this.height = builder.height;
        this.fps = builder.fps;
        this.iFrameInterval = builder.iFrameInterval;
        this.mimeType = builder.mimeType;
        this.profile = builder.profile;
        this.bitrate = builder.bitrate;
    }

    public static class Builder {
        // Required parameters
        private final int width;
        private final int height;

        // Optional parameters with defaults
        private int fps = 30;
        private int iFrameInterval = 1;
        private String mimeType = MediaFormat.MIMETYPE_VIDEO_AVC;
        private int profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
        private int bitrate = 4_000; // 4mbps

        public Builder(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public Builder setFps(int fps) {
            this.fps = fps;
            return this;
        }

        public Builder setIFrameInterval(int interval) {
            this.iFrameInterval = interval;
            return this;
        }

        public Builder setMimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public Builder setProfile(int profile) {
            this.profile = profile;
            return this;
        }

        public Builder setBitrate(int bitrateKbps) {
            this.bitrate = bitrateKbps;
            return this;
        }

        public RecorderConfig build() {
            return new RecorderConfig(this);
        }
    }
}