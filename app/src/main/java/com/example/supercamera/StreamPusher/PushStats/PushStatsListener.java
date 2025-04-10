package com.example.supercamera.StreamPusher.PushStats;

public interface PushStatsListener {
    void onError(int code, String message);
    //void onStatistics(PushStats stats);
}