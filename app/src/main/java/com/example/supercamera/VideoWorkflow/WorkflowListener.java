package com.example.supercamera.VideoWorkflow;

import com.example.supercamera.StreamPusher.PushStats.PushStatsInfo;

public interface WorkflowListener {
    void onStateChanged(WorkflowState state);
    void onError(int code, String message);
    void onStatistics(PushStatsInfo stats);
}