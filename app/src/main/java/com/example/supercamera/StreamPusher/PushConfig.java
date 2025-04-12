package com.example.supercamera.StreamPusher;

import org.bytedeco.ffmpeg.global.*;

public class PushConfig {
    public final String url;
    public final int width;
    public final int height;
    public final int fps;
    public final int Bitrate;
    public final byte[] header;
    public final int codecID;

    // 私有构造函数，只能通过Builder创建
    private PushConfig(Builder builder) {
        this.url = builder.url;
        this.width = builder.width;
        this.height = builder.height;
        this.fps = builder.fps;
        this.Bitrate = builder.Bitrate;
        this.header = builder.header;
        this.codecID = builder.codecID;
    }

    // Builder内部类
    public static class Builder {
        private String url;
        private int width;
        private int height;
        private int fps;
        private int Bitrate;
        private byte[] header;
        private int codecID;

        public Builder setUrl(String url) {
            this.url = url;
            return this; // 返回自身以实现链式调用
        }

        public Builder setResolution(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder setFPS(int fps) {
            this.fps = fps;
            return this;
        }

        public Builder setBitrate(int bitrate) {
            this.Bitrate = bitrate;
            return this;
        }

        public Builder setHeader(byte[] header) {
            this.header = header;
            return this;
        }

        // org. bytedeco. ffmpeg. avcodec. AVCodecParameters  codec_id
        public Builder setCodecID(int codecID) {
            this.codecID = codecID;
            return this;
        }

        public PushConfig build() {
            // 构建不可变配置对象
            return new PushConfig(this);
        }
    }
}
