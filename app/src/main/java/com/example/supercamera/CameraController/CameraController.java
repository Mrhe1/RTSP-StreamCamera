package com.example.supercamera.CameraController;

import android.media.MediaCodec;

import com.example.supercamera.StreamPusher.PushConfig;
import com.example.supercamera.StreamPusher.PushListener;
import com.example.supercamera.StreamPusher.PushStats.TimeStamp;

import java.nio.ByteBuffer;
import java.util.List;

import io.reactivex.rxjava3.subjects.PublishSubject;

public interface CameraController {
    // 配置推流参数（线程安全，可重复调用）
    void configure(PushConfig config);
    // 指定报告TimeStamp的消息队列
    void setTimeStampQueue(List<PublishSubject<TimeStamp>> timeStampQueue);
    // 启动推流（异步）
    void start(byte[] header);
    // 停止推流（同步阻塞）
    void stop();
    // 销毁模块
    void destroy();
    // 推送帧数据
    void pushFrame(ByteBuffer data, MediaCodec.BufferInfo bufferInfo, Long encodedTime);
    // 注册回调监听器
    void setPushListener(PushListener listener);
}
