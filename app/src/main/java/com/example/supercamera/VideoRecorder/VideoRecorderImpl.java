package com.example.supercamera.VideoRecorder;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executors;

public class VideoRecorderImpl implements VideoRecorder {
    private RecorderConfig mConfig;
    private RecorderListener mListener;
    private volatile RecorderState.RecorderStateEnum mState = RecorderState.RecorderStateEnum.IDLE;
    private Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void configure(RecorderConfig config) {

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
    public void setStreamListener(RecorderListener listener) {

    }

    private void notifyError(int code, String message) {

    }

}
