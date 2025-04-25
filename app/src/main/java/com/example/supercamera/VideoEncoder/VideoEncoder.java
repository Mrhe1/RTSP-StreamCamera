package com.example.supercamera.VideoEncoder;

public interface VideoEncoder {
    // 配置推流参数（线程安全，可重复调用）
    void configure(MyEncoderConfig config);
    // 启动推流（异步）
    void start();
    // 停止推流（同步阻塞）
    void stop();
    // 销毁模块
    void destroy();
    // 注册回调监听器
    void setEncoderListener(EncoderListener listener);
}
