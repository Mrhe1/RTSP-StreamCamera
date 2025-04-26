package com.example.supercamera.CameraController;

import android.view.Surface;

import java.util.List;

public interface CameraController {
    void configure(CameraConfig config);
    void openCamera(List<Surface> surfaces);
    void stop();
    void destroy();
    void setCameraListener(CameraListener listener);
}
