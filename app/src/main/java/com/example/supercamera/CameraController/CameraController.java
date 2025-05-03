package com.example.supercamera.CameraController;

import android.view.Surface;

import com.example.supercamera.StreamPusher.PushStats.TimeStamp;

import java.util.List;

import io.reactivex.rxjava3.subjects.PublishSubject;

public interface CameraController {
    void configure(CameraConfig config);
    PublishSubject<TimeStamp> getTimeStampQueue();
    void openCamera(List<Surface> surfaces);
    void stop();
    void destroy();
    void setCameraListener(CameraListener listener);
}
