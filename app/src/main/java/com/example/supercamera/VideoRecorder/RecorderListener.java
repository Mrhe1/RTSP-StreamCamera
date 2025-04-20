package com.example.supercamera.VideoRecorder;

import android.view.Surface;

import com.example.supercamera.MyException.MyException;

public interface RecorderListener {
    void onError(MyException e);
    void onStart();
    void onSurfaceAvailable(Surface surface);
}