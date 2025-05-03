package com.example.supercamera.VideoRecorder;

import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;

public class RecorderState {
    private static final String TAG = "RecorderState";
    private final AtomicReference<RecorderStateEnum> currentState =
            new AtomicReference<>(RecorderStateEnum.READY);
    public enum RecorderStateEnum {
        READY,
        CONFIGURED,
        ERROR,
        STARTING,
        RECORDING,
        STOPPING,
        DESTROYED // 已销毁
    }

    // 处理工作状态转换
    public boolean setState(RecorderStateEnum newState) {
        // 状态校验
        if (!isValidTransition(newState)) {
            Timber.tag(TAG).w("非法状态转换: %s → %s",
                    currentState.get(), newState);
            return false;
        }
        return currentState.compareAndSet(currentState.get(), newState);
    }

    public RecorderStateEnum getState() {
        return currentState.get();
    }

    private boolean isValidTransition(RecorderStateEnum newState) {
        // 实现状态转换规则校验
        RecorderStateEnum current = currentState.get();
        return switch (current) {
            case READY -> newState == RecorderStateEnum.CONFIGURED || newState == RecorderStateEnum.ERROR;
            case CONFIGURED -> newState == RecorderStateEnum.STARTING || newState == RecorderStateEnum.ERROR ||
                                newState == RecorderStateEnum.CONFIGURED;
            case STARTING -> newState == RecorderStateEnum.ERROR || newState == RecorderStateEnum.RECORDING;
            case RECORDING -> newState == RecorderStateEnum.ERROR || newState == RecorderStateEnum.STOPPING;
            case ERROR -> newState == RecorderStateEnum.STOPPING || newState == RecorderStateEnum.READY;
            case STOPPING -> newState == RecorderStateEnum.READY || newState == RecorderStateEnum.ERROR;
            case DESTROYED -> true;
        };
    }
}