package com.example.supercamera.StreamPusher;

import com.example.supercamera.StreamPusher.PushStats.PushStatsInfo;

public interface PushListener {
    void onError(int code, String message);
    void onStatistics(PushStatsInfo stats);
    void onStarted();
}