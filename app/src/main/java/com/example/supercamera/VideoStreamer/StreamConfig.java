package com.example.supercamera.VideoStreamer;

public class StreamConfig {
    private final String url;
    private final int width;
    private final int height;
    // 其他字段...

    // 私有构造函数，只能通过Builder创建
    private StreamConfig(Builder builder) {
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
        private int fps = 25; // 默认值

        public Builder setUrl(String url) {
            this.url = url;
            return this; // 返回自身以实现链式调用
        }

        public Builder setResolution(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public StreamConfig build() {
            // 构建不可变配置对象
            return new StreamConfig(this);
        }
    }
}
