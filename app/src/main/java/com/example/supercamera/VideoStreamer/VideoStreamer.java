package com.example.supercamera.VideoStreamer;

import com.example.supercamera.StreamPusher.PushStats.TimeStamp;

import java.util.List;

import io.reactivex.rxjava3.subjects.PublishSubject;

public interface VideoStreamer {
    // 配置推流参数（线程安全，可重复调用）
    void configure(StreamConfig config);
    // 指定报告TimeStamp的消息队列
    void setTimeStampQueue(PublishSubject<TimeStamp> timeStampQueue);
    // 启动推流（异步）
    void start();
    // 停止推流（同步阻塞）
    void stop();
    // 注册回调监听器
    void setStreamListener(StreamListener listener);
}
