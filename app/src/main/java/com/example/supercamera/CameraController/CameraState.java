package com.example.supercamera.CameraController;

import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;

public class CameraState {
    private static final String TAG = "CameraState";
    private final AtomicReference<CameraStateEnum> currentState =
            new AtomicReference<>(CameraStateEnum.READY);

    public enum CameraStateEnum {
        READY,
        CONFIGURE,
        OPENING,
        PREVIEWING,
        ERROR,
        CLOSING,
        DESTROYED // 已销毁
    }

    public boolean setState(CameraStateEnum newState) {
        if (!isValidTransition(newState)) {
            Timber.tag(TAG).w("Invalid state transition: %s → %s",
                    currentState.get(), newState);
            return false;
        }
        return currentState.compareAndSet(currentState.get(), newState);
    }

    public CameraStateEnum getState() {
        return currentState.get();
    }

    private boolean isValidTransition(CameraStateEnum newState) {
        CameraStateEnum current = currentState.get();
        return switch (current) {
            case READY -> newState == CameraStateEnum.CONFIGURE;
            case CONFIGURE -> newState == CameraStateEnum.OPENING ||
                    newState == CameraStateEnum.ERROR ||
                    newState == CameraStateEnum.CONFIGURE;
            case OPENING -> newState == CameraStateEnum.PREVIEWING ||
                    newState == CameraStateEnum.ERROR;
            case PREVIEWING -> newState == CameraStateEnum.CLOSING ||
                    newState == CameraStateEnum.ERROR;
            case ERROR -> newState == CameraStateEnum.CLOSING ||
                    newState == CameraStateEnum.READY;
            case CLOSING -> newState == CameraStateEnum.READY ||
                    newState == CameraStateEnum.ERROR;
            case DESTROYED -> true;
        };
    }
}
