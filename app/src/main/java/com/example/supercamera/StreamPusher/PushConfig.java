package com.example.supercamera.StreamPusher;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;

import com.example.supercamera.StreamPusher.PushStats.TimeStamp;

import java.util.List;

import io.reactivex.rxjava3.subjects.PublishSubject;

public class PushConfig {
    public final String url;
    public final int width;
    public final int height;
    public final int fps;
    public final int BitrateKbps;
    public final int codecID;
    public List<PublishSubject<TimeStamp>> timeStampQueue;
    public final int statsIntervalSeconds;  // stats回调间隔时间（秒）
    public final int pingIntervalSeconds;    // ping间隔时间（秒）
    public final double pushFailureRateSet;// 设置丢包率大于多少重连
    public final int maxReconnectAttempts;  // 最大重连次数
    public final int reconnectPeriodSeconds;  // 单次重连间隔时间
    public byte[] header;

    // 私有构造函数，只能通过Builder创建
    private PushConfig(Builder builder) {
        this.url = builder.url;
        this.width = builder.width;
        this.height = builder.height;
        this.fps = builder.fps;
        this.BitrateKbps = builder.BitrateKbps;
        this.codecID = builder.codecID;
        this.statsIntervalSeconds = builder.statsIntervalSeconds;
        this.pingIntervalSeconds = builder.pingIntervalSeconds;
        this.pushFailureRateSet = builder.pushFailureRateSet;
        this.maxReconnectAttempts = builder.maxReconnectAttempts;
        this.reconnectPeriodSeconds = builder.reconnectPeriodSeconds;
    }

    public void setHeader(byte[] header) {
        this.header = header;
    }

    // 指定报告TimeStamp的消息队列
    public void setTimeStampQueue(List<PublishSubject<TimeStamp>> timeStampQueue) {
        this.timeStampQueue = timeStampQueue;
    }


    // Builder内部类
    public static class Builder {
        private String url;
        private int width;
        private int height;
        private int fps = 30;
        private int BitrateKbps = 4_000;
        private int codecID = AV_CODEC_ID_H264;
        private int statsIntervalSeconds = 4;  // stats回调间隔时间（秒）
        private int pingIntervalSeconds = 6;    // ping间隔时间（秒）
        private double pushFailureRateSet = 0.6;    // 设置丢包率大于多少重连
        private int maxReconnectAttempts = 4;  // 最大重连次数
        private int reconnectPeriodSeconds = 4;  // 单次重连间隔时间

        public Builder(String url, int width, int height) {
            this.url = url;
            this.width = width;
            this.height = height;
        }

        public Builder setFPS(int fps) {
            this.fps = fps;
            return this;
        }

        public Builder setBitrateKbps(int bitrateKbps) {
            this.BitrateKbps = bitrateKbps;
            return this;
        }

        // org. bytedeco. ffmpeg. avcodec. AVCodecParameters  codec_id
        public Builder setCodecID(int codecID) {
            this.codecID = codecID;
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

        public Builder serReconnectParam(int maxReconnectAttempts,
                                         int reconnectPeriodSeconds) {
            this.maxReconnectAttempts = maxReconnectAttempts;
            this.reconnectPeriodSeconds = reconnectPeriodSeconds;
            return this;
        }

        public PushConfig build() {
            return new PushConfig(this);
        }
    }
}
