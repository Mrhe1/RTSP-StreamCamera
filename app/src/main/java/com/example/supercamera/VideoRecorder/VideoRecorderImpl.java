package com.example.supercamera.VideoRecorder;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executors;
import com.example.supercamera.VideoRecorder.RecorderState.RecorderStateEnum;

public class VideoRecorderImpl implements VideoRecorder {
    private RecorderConfig mConfig;
    private RecorderListener mListener;
    private volatile RecorderState.RecorderStateEnum state = RecorderState.RecorderStateEnum.IDLE;
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
    public void setRecorderListener(RecorderListener listener) {

    }

    private void notifyError(int code, String message) {

    }

}
