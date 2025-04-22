package com.example.supercamera.StreamPusher;

import android.media.MediaCodec;

import com.example.supercamera.StreamPusher.PushStats.PushStatsListener;
import com.example.supercamera.StreamPusher.PushStats.TimeStamp;

import java.nio.ByteBuffer;
import java.util.List;

import io.reactivex.rxjava3.subjects.PublishSubject;


public interface StreamPusher {
    // 配置推流参数（线程安全，可重复调用）
    void configure(PushConfig config);
    // 设置header
    void setHeader(byte[] header);
    // 指定报告TimeStamp的消息队列
    void setTimeStampQueue(List<PublishSubject<TimeStamp>> timeStampQueue);
    // 启动推流（异步）
    void start();
    // 停止推流（同步阻塞）
    void stop();
    // 推送帧数据
    void pushFrame(ByteBuffer data, MediaCodec.BufferInfo bufferInfo, Long encodedTime);
    // 注册回调监听器
    void setStreamListener(PushListener listener);
}
