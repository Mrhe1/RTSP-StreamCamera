package com.example.supercamera.StreamPusher;

import com.example.supercamera.StreamPusher.PushStats.PushStats;

public interface PushListener {
    void onStateChanged(PushState state);
    void onError(int code, String message);
    void onStatistics(PushStats stats);
}