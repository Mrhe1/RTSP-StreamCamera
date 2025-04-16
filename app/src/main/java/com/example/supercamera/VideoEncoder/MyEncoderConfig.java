package com.example.supercamera.VideoEncoder;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import java.util.HashMap;
import java.util.Map;

public class MyEncoderConfig {
    private final int width;
    private final int height;
    private final int bitrate;
    private final int fps;
    private final int iFrameInterval;
    public final int colorFormat;
    private final Map<String, Object> customParams;
    private final String mimeType;
    private final int profile;
    public final MediaFormat format;

    private MyEncoderConfig(Builder builder) {
        this.width = builder.width;
        this.height = builder.height;
        this.bitrate = builder.bitrate;
        this.fps = builder.fps;
        this.iFrameInterval = builder.iFrameInterval;
        this.colorFormat = builder.colorFormat;
        this.customParams = builder.customParams;
        this.mimeType = builder.mimeType;
        this.profile = builder.profile;

        this.format = createMediaFormat();
    }

    public MediaFormat createMediaFormat() {
        MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
        format.setInteger(MediaFormat.KEY_PROFILE, profile);

        if (colorFormat != 0) {
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        }

        for (Map.Entry<String, Object> entry : customParams.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Integer) {
                format.setInteger(key, (Integer) value);
            } else if (value instanceof String) {
                format.setString(key, (String) value);
            }
        }
        return format;
    }

    public static class Builder {
        private int width;
        private int height;
        private int bitrate = 4_000_000; // 默认4Mbps
        private int fps = 30;      // 默认30fps
        private int iFrameInterval = 1;  // 默认1秒
        private int colorFormat = 0;     // 默认不设置
        private final Map<String, Object> customParams = new HashMap<>();
        private String mimeType;
        private int profile;
        public Builder setResolution(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder setBitrate(int bitrateKbps) {
            this.bitrate = bitrateKbps * 1000; // 转换为bps
            return this;
        }

        public Builder setFps(int fps) {
            this.fps = fps;
            return this;
        }

        public Builder setIFrameInterval(int seconds) {
            this.iFrameInterval = seconds;
            return this;
        }

        public Builder setColorFormat(int colorFormat) {
            this.colorFormat = colorFormat;
            return this;
        }

        public Builder addCustomParameter(String key, int value) {
            customParams.put(key, value);
            return this;
        }

        public Builder addCustomParameter(String key, String value) {
            customParams.put(key, value);
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

        public MyEncoderConfig build() {
            return new MyEncoderConfig(this);
        }
    }
}

