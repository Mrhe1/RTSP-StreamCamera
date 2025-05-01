package com.example.supercamera.VideoStreamer;

import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;
public class StreamState {
    private static final String TAG = "StreamState";
    public final AtomicReference<StreamStateEnum> currentState =
            new AtomicReference<>(StreamStateEnum.READY);
    public enum StreamStateEnum {
        READY,
        CONFIGURED,
        ERROR,
        STARTING,
        StartPUSHING,
        STREAMING,
        STOPPING
    }

    // 处理工作状态转换
    public boolean setState(StreamStateEnum newState) {
        // 状态校验
        if (!isValidTransition(newState)) {
            Timber.tag(TAG).w("非法状态转换: %s → %s",
                    currentState.get(), newState);
            return false;
        }
        return currentState.compareAndSet(currentState.get(), newState);
    }

    public StreamStateEnum getState() {
        return currentState.get();
    }

    private boolean isValidTransition(StreamStateEnum newState) {
        // 实现状态转换规则校验
        StreamStateEnum current = currentState.get();
        return switch (current) {
            case READY -> newState == StreamStateEnum.CONFIGURED || newState == StreamStateEnum.ERROR;
            case CONFIGURED -> newState == StreamStateEnum.STARTING || newState == StreamStateEnum.ERROR;
            case STARTING -> newState == StreamStateEnum.ERROR || newState == StreamStateEnum.StartPUSHING
                                || newState == StreamStateEnum.READY;
            case StartPUSHING -> newState == StreamStateEnum.ERROR || newState == StreamStateEnum.STREAMING;
            case STREAMING -> newState == StreamStateEnum.ERROR || newState == StreamStateEnum.STOPPING;
            case ERROR -> newState == StreamStateEnum.STOPPING || newState == StreamStateEnum.READY;
            case STOPPING -> newState == StreamStateEnum.READY || newState == StreamStateEnum.ERROR
                                || newState == StreamStateEnum.STREAMING;
        };
    }
}
