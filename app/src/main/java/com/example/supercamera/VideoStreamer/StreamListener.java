package com.example.supercamera.VideoStreamer;

import android.view.Surface;

import com.example.supercamera.MyException.MyException;
import com.example.supercamera.StreamPusher.PushStats.PushStatsInfo;

public interface StreamListener {
    void onError(MyException e);
    void onStatistics(PushStatsInfo stats);
    void onSurfaceAvailable(Surface surface);
    void onStart();
    void onReconnect(boolean ifSuccess, int reconnectAttempts);
}