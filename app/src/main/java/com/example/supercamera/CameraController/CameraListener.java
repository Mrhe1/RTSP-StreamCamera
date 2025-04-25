package com.example.supercamera.CameraController;

import com.example.supercamera.MyException.MyException;
import com.example.supercamera.StreamPusher.PushStats.PushStatsInfo;

public interface CameraListener {
    void onError(MyException e);
    void onStatistics(PushStatsInfo stats);
    void onStart();
    void onReconnectFail(MyException e);
}
