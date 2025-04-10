package com.example.supercamera.VideoStreamer;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executors;

public class VideoStreamerImpl implements VideoStreamer {
    private StreamConfig mConfig;
    private StreamListener mListener;
    private volatile StreamState.PushStateEnum mState = StreamState.PushStateEnum.IDLE;
    private Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void configure(StreamConfig config) {

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
    public void setStreamListener(StreamListener listener) {

    }

    private void setState(StreamState newState) {

    }

    private void notifyError(int code, String message) {

    }

}
