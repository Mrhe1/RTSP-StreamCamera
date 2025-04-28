package com.example.supercamera.VideoWorkflow;

import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;

public class WorkflowState {
    private static final String TAG = "WorkflowState";
    public static final AtomicReference<WorkflowStateEnum> currentState =
            new AtomicReference<>(WorkflowStateEnum.READY);
    public enum WorkflowStateEnum {
        READY,
        CONFIGURED,
        ERROR,
        STARTING,
        WORKING,
        STOPPING
    }

    // 处理工作状态转换
    public static boolean setState(WorkflowStateEnum newState) {
        // 状态校验
        if (!isValidTransition(newState)) {
            Timber.tag(TAG).w("非法状态转换: %s → %s",
                    currentState.get(), newState);
            return false;
        }
        return currentState.compareAndSet(currentState.get(), newState);
    }

    public static WorkflowStateEnum getState() {
        return currentState.get();
    }

    private static boolean isValidTransition(WorkflowStateEnum newState) {
        // 实现状态转换规则校验
        WorkflowStateEnum current = currentState.get();
        return switch (current) {
            case READY -> newState == WorkflowStateEnum.CONFIGURED || newState == WorkflowStateEnum.ERROR;
            case STARTING -> newState == WorkflowStateEnum.ERROR || newState == WorkflowStateEnum.WORKING
                    || newState == WorkflowStateEnum.CONFIGURED;
            case CONFIGURED -> newState == WorkflowStateEnum.STARTING || newState == WorkflowStateEnum.ERROR;
            case WORKING -> newState == WorkflowStateEnum.ERROR || newState == WorkflowStateEnum.STOPPING;
            case ERROR -> newState == WorkflowStateEnum.STOPPING || newState == WorkflowStateEnum.READY;
            case STOPPING -> newState == WorkflowStateEnum.READY || newState == WorkflowStateEnum.ERROR;
        };
    }
}