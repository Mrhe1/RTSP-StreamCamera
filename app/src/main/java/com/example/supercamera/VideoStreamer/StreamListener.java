package com.example.supercamera.VideoStreamer;

import com.example.supercamera.StreamPusher.PushStats.PushStats;

public interface StreamListener {
    void onStateChanged(StreamState state);
    void onError(int code, String message);
    void onStatistics(PushStats stats);
}