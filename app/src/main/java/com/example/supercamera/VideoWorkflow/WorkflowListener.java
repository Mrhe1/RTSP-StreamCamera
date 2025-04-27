package com.example.supercamera.VideoWorkflow;

import com.example.supercamera.StreamPusher.PushStats.PushStatsInfo;

public interface WorkflowListener {
    void onStart();
    void onError(int code, String message);
    void onStatistics(PushStatsInfo stats);
}