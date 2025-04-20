package com.example.supercamera.StreamPusher;

import com.example.supercamera.MyException.MyException;
import com.example.supercamera.StreamPusher.PushStats.PushStatsInfo;

public interface PushListener {
    void onError(MyException e);
    void onStatistics(PushStatsInfo stats);
    void onStarted();
    // reconnectAttempts:尝试的次数，第几次重连
    void onReconnect(boolean ifSuccess, int reconnectAttempts);
    void onReconnectFail(MyException e);
}