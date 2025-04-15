package com.example.supercamera.StreamPusher;

import com.example.supercamera.StreamPusher.PushStats.TimeStamp;

import java.util.List;

import io.reactivex.rxjava3.subjects.PublishSubject;

public class PushConfig {
    public final String url;
    public final int width;
    public final int height;
    public final int fps;
    public final int Bitrate;
    public final byte[] header;
    public final int codecID;
    public final List<PublishSubject<TimeStamp>> timeStampQueue;
    public final int statsIntervalSeconds;  // stats回调间隔时间（秒）
    public final int pingIntervalSeconds;    // ping间隔时间（秒）
    public final double pushFailureRateSet;// 设置丢包率大于多少重连

    // 私有构造函数，只能通过Builder创建
    private PushConfig(Builder builder) {
        this.url = builder.url;
        this.width = builder.width;
        this.height = builder.height;
        this.fps = builder.fps;
        this.Bitrate = builder.Bitrate;
        this.header = builder.header;
        this.codecID = builder.codecID;
        this.timeStampQueue = builder.timeStampQueue;
        this.statsIntervalSeconds = builder.statsIntervalSeconds;
        this.pingIntervalSeconds = builder.pingIntervalSeconds;
        this.pushFailureRateSet = builder.pushFailureRateSet;
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
        private List<PublishSubject<TimeStamp>> timeStampQueue;
        private int statsIntervalSeconds;  // stats回调间隔时间（秒）
        private int pingIntervalSeconds;    // ping间隔时间（秒）
        private double pushFailureRateSet;    // 设置丢包率大于多少重连

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

        // 指定报告TimeStamp的消息队列
        public Builder setTimeStampQueue(List<PublishSubject<TimeStamp>> timeStampQueue) {
            this.timeStampQueue = timeStampQueue;
            return this;
        }

        public Builder setStatsIntervalSeconds(int seconds) {
            this.statsIntervalSeconds = seconds;
            return this;
        }

        public Builder setPingIntervalSeconds(int seconds) {
            this.pingIntervalSeconds = seconds;
            return this;
        }

        // 设置丢包率大于多少重连
        public Builder setPushFailureRate(double rate) {
            this.pushFailureRateSet = rate;
            return this;
        }

        public PushConfig build() {
            // 构建不可变配置对象
            return new PushConfig(this);
        }
    }
}
