package com.example.supercamera.VideoWorkflow;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executors;

public class VideoWorkflowImpl implements VideoWorkflow {
    private WorkflowConfig mConfig;
    private WorkflowListener mListener;
    private volatile WorkflowState.WorkflowEnum mState = WorkflowState.WorkflowEnum.IDLE;
    private Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void configure(WorkflowConfig config) {

    }

    @Override
    public void start() {

        Executors.newSingleThreadExecutor().submit(() -> {

        });
    }

    @Override
    public void stop() {

    }

    @Override
    public void destroy() {

    }


    @Override
    public void setStreamListener(WorkflowListener listener) {

    }

    private void setState(WorkflowState newState) {

    }

    private void notifyError(int code, String message) {

    }

}
