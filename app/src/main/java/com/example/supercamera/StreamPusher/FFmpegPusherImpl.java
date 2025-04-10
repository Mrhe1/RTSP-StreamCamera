package com.example.supercamera.StreamPusher;

import android.os.Handler;
import android.os.Looper;

import com.example.supercamera.StreamPusher.PushStats.PushStatsListener;
import com.example.supercamera.VideoStreamer.StreamConfig;
import com.example.supercamera.VideoWorkflow.WorkflowConfig;

import java.util.concurrent.Executors;

public class FFmpegPusherImpl implements StreamPusher {
    private StreamConfig mConfig;
    //private com.example.supercamera.VideoWorkflow.PushStatsListener mListener;
    private volatile PushState.PushStateEnum mState = PushState.PushStateEnum.IDLE;
    private Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void configure(PushConfig config) {

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
    public void setStreamListener(PushStatsListener listener) {

    }

    private void setState(PushState newState) {

    }

    private void notifyError(int code, String message) {

    }

}
