package com.example.supercamera.VideoRecorder;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;

public class RecorderConfig {
    private final String url;
    private final int width;
    private final int height;
    // 其他字段...

    // 私有构造函数，只能通过Builder创建
    private RecorderConfig(Builder builder) {
        this.url = builder.url;
        this.width = builder.width;
        this.height = builder.height;
        // 其他字段赋值...
    }

    // Builder内部类
    public static class Builder {
        private String url;
        private int width;
        private int height;
        private int fps = 30; // 默认值
        private int iFrameInterval = 1; // 默认值
        public String mimeType = MediaFormat.MIMETYPE_VIDEO_AVC; // 默认值
        private int profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
        public MediaFormat format;

        public Builder setUrl(String url) {
            this.url = url;
            return this; // 返回自身以实现链式调用
        }

        public Builder setResolution(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public RecorderConfig build() {
            // 构建不可变配置对象
            return new RecorderConfig(this);
        }
    }
}
