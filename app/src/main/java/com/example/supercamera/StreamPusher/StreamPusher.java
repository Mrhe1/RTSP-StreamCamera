package com.example.supercamera.StreamPusher;

import android.media.MediaCodec;

import com.example.supercamera.StreamPusher.PushStats.PushStatsListener;

import java.nio.ByteBuffer;


public interface StreamPusher {
    // 配置推流参数（线程安全，可重复调用）
    void configure(PushConfig config);
    // 启动推流（异步）
    void start();
    // 停止推流（同步阻塞）
    void stop();
    // 推送帧数据
    void pushFrame(ByteBuffer data, MediaCodec.BufferInfo bufferInfo, Long encodedTime);
    // 注册回调监听器
    void setStreamListener(PushListener listener);
}
