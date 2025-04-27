package com.example.supercamera.VideoWorkflow;

public interface VideoWorkflow {
    // 配置推流参数（线程安全，可重复调用）
    void configure(WorkflowConfig config);
    // 启动推流（异步）
    void start();
    // 停止推流（同步阻塞）
    void stop();
    // 销毁组件
    void destroy();
    // 注册回调监听器
    void setStreamListener(WorkflowListener listener);
}
