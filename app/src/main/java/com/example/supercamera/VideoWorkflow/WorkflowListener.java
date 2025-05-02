package com.example.supercamera.VideoWorkflow;

import android.util.Size;

import com.example.supercamera.MyException.MyException;
import com.example.supercamera.StreamPusher.PushStats.PushStatsInfo;

public interface WorkflowListener {
    void onStart(Size previewSize, Size recordSize, int fps, int stabMode);
    void onError(MyException e);
    void onStatistics(PushStatsInfo stats);
}