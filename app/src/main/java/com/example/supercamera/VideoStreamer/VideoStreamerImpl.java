package com.example.supercamera.VideoStreamer;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executors;

import com.example.supercamera.VideoStreamer.StreamState;

public class VideoStreamerImpl implements VideoStreamer {
    private StreamConfig mConfig;
    private StreamListener mListener;
    private volatile StreamStateEnum mState = StreamStateEnum.IDLE;
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

    private void notifyError(int code, String message) {

    }

}
