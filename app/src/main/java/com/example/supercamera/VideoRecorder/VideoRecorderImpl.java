package com.example.supercamera.VideoRecorder;

import static com.example.supercamera.MyException.MyException.ILLEGAL_STATE;
import static com.example.supercamera.VideoRecorder.ErrorCode.ERROR_Recorder_CONFIG;
import static com.example.supercamera.VideoRecorder.ErrorCode.ERROR_Recorder_START;
import static com.example.supercamera.VideoRecorder.ErrorCode.ERROR_Recorder_STOP;
import static com.example.supercamera.VideoRecorder.RecorderState.RecorderStateEnum.CONFIGURED;
import static com.example.supercamera.VideoRecorder.RecorderState.RecorderStateEnum.READY;
import static com.example.supercamera.VideoRecorder.RecorderState.RecorderStateEnum.RECORDING;

import java.util.concurrent.Executors;

import com.example.supercamera.MyException.MyException;
import com.example.supercamera.StreamPusher.PushState;

import timber.log.Timber;

public class VideoRecorderImpl implements VideoRecorder {
    private RecorderConfig mConfig;
    private RecorderListener mListener;
    private final Object onErrorLock = new Object();
    private String TAG = "VideoRecorder";
    @Override
    public void configure(RecorderConfig config) {
        if(RecorderState.getState() != READY) {
            String msg = String.format("configure出错，IllegalState，目前状态：%s",
                    PushState.getState().toString());
            Timber.tag(TAG).e(msg);
            throw throwException(ILLEGAL_STATE, ERROR_Recorder_CONFIG, msg);
        }
    }

    @Override
    public void start() {
        if(RecorderState.getState() != CONFIGURED) {
            String msg = String.format("configure出错，IllegalState，目前状态：%s",
                    PushState.getState().toString());
            Timber.tag(TAG).e(msg);
            throw throwException(ILLEGAL_STATE, ERROR_Recorder_START, msg);
        }

        Executors.newSingleThreadExecutor().submit(() -> {

        });
    }

    @Override
    public void stop() {
        if(RecorderState.getState() != RECORDING) {
            String msg = String.format("configure出错，IllegalState，目前状态：%s",
                    PushState.getState().toString());
            Timber.tag(TAG).e(msg);
            throw throwException(ILLEGAL_STATE, ERROR_Recorder_STOP, msg);
        }
    }


    @Override
    public void setRecorderListener(RecorderListener listener) {
        this.mListener = listener;
    }

    private MyException throwException(int type, int code, String message) {
        return new MyException(this.getClass().getPackageName(),
                type, code, message);
    }

    private void notifyError(int type,int code, String message) {
        synchronized (onErrorLock) {

        }
    }

}
