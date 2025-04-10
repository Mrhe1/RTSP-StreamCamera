package com.example.supercamera.VideoEncoder;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executors;

public class MediaCodecImpl implements VideoEncoder {
    private MyEncoderConfig mConfig;
    private EncoderListener mListener;
    private Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void configure(MyEncoderConfig config) {

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
    public void setStreamListener(EncoderListener listener) {

    }

    private void notifyError(int code, String message) {

    }

}
