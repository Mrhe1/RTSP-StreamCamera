package com.example.supercamera.CameraController;

import android.util.Size;
import android.view.Surface;

import com.example.supercamera.MyException.MyException;

public interface CameraListener {
    void onError(MyException e);
    void onCameraOpened(Size previewSize, Size recordSize,int fps, int stabMode);
    void onPreviewStarted();
    void onSurfaceAvailable(Surface surface);
    void onCameraClosed();
}
