package com.example.supercamera.VideoRecorder;

public interface VideoRecorder {
    // 配置推流参数（线程安全，可重复调用）
    void configure(RecorderConfig config);
    // 启动推流（异步）
    void start();
    // 停止推流（同步阻塞）
    void stop();
    // 注册回调监听器
    void setRecorderListener(RecorderListener listener);
}
