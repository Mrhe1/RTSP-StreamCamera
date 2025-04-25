package com.example.supercamera.CameraController;

import com.example.supercamera.StreamPusher.PushState;

import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;

public class CameraState {
    private static final String TAG = "PushState";
    public static final AtomicReference<com.example.supercamera.StreamPusher.PushState.PushStateEnum> currentState =
            new AtomicReference<>(com.example.supercamera.StreamPusher.PushState.PushStateEnum.READY);
    public enum PushStateEnum {
        READY,
        CONFIGURED,
        ERROR,
        RECONNECTING,
        STARTING,
        PUSHING,
        STOPPING
    }

    // 处理工作状态转换
    public static boolean setState(com.example.supercamera.StreamPusher.PushState.PushStateEnum newState) {
        // 状态校验
        if (!isValidTransition(newState)) {
            Timber.tag(TAG).w("非法状态转换: %s → %s",
                    currentState.get(), newState);
            return false;
        }
        return currentState.compareAndSet(currentState.get(), newState);
    }

    public static com.example.supercamera.StreamPusher.PushState.PushStateEnum getState() {
        return currentState.get();
    }

    private static boolean isValidTransition(com.example.supercamera.StreamPusher.PushState.PushStateEnum newState) {
        // 实现状态转换规则校验
        com.example.supercamera.StreamPusher.PushState.PushStateEnum current = currentState.get();
        return switch (current) {
            case READY -> newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.CONFIGURED || newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.ERROR;
            case STARTING -> newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.ERROR || newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.PUSHING;
            case CONFIGURED -> newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.STARTING || newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.ERROR;
            case PUSHING -> newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.ERROR || newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.STOPPING
                    || newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.RECONNECTING;
            case RECONNECTING -> newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.ERROR || newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.PUSHING
                    || newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.STOPPING;
            case ERROR -> newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.STOPPING || newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.READY;
            case STOPPING -> newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.READY || newState == com.example.supercamera.StreamPusher.PushState.PushStateEnum.ERROR;
            default -> false;
        };
    }
}
