package com.example.supercamera.VideoWorkflow;

public interface VideoWorkflow {
    // 配置推流参数（线程安全，可重复调用）
    void configure(WorkflowConfig config);
    // 启动工作流（异步）
    void start();
    // 停止工作流（异步）
    void stop();
    // 销毁组件
    void destroy();
    // 注册回调监听器
    void setStreamListener(WorkflowListener listener);
}
