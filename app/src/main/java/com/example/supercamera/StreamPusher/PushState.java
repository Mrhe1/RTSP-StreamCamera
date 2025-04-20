package com.example.supercamera.StreamPusher;

import java.util.concurrent.atomic.AtomicReference;
import timber.log.Timber;

public class PushState {
        private static final String TAG = "PushState";
        public static final AtomicReference<PushStateEnum> currentState =
                new AtomicReference<>(PushStateEnum.READY);
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
        public static boolean setState(PushStateEnum newState) {
                // 状态校验
                if (!isValidTransition(newState)) {
                        Timber.tag(TAG).w("非法状态转换: %s → %s",
                                currentState.get(), newState);
                        return false;
                }
                return currentState.compareAndSet(currentState.get(), newState);
        }

        public static PushStateEnum getState() {
                return currentState.get();
        }

        private static boolean isValidTransition(PushStateEnum newState) {
                // 实现状态转换规则校验
                PushStateEnum current = currentState.get();
                return switch (current) {
                        case READY -> newState == PushStateEnum.CONFIGURED || newState == PushStateEnum.ERROR;
                        case STARTING -> newState == PushStateEnum.ERROR || newState == PushStateEnum.PUSHING;
                        case CONFIGURED -> newState == PushStateEnum.STARTING || newState == PushStateEnum.ERROR;
                        case PUSHING -> newState == PushStateEnum.ERROR || newState == PushStateEnum.STOPPING
                                || newState == PushStateEnum.RECONNECTING;
                        case RECONNECTING -> newState == PushStateEnum.ERROR || newState == PushStateEnum.PUSHING
                                || newState == PushStateEnum.STOPPING;
                        case ERROR -> newState == PushStateEnum.STOPPING || newState == PushStateEnum.READY;
                        case STOPPING -> newState == PushStateEnum.READY || newState == PushStateEnum.ERROR;
                        default -> false;
                };
        }
}