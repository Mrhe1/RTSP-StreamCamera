package com.example.supercamera.VideoStreamer;

import android.view.Surface;

import com.example.supercamera.StreamPusher.PushStats.PushStatsInfo;

public interface StreamListener {
    void onError(int code, String message);
    void onStatistics(PushStatsInfo stats);
    void onSurfaceAvailable(Surface surface);
    void onStart();
}