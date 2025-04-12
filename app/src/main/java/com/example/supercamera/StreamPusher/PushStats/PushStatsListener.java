package com.example.supercamera.StreamPusher.PushStats;

public interface PushStatsListener {
    void onStatistics(int code, String message);
    //void onStatistics(PushStatistics stats);
}