package com.example.supercamera.StreamPusher.PushStats;

public class PushStatsInfo {
    public final int currentBitrate;
    public final int rtt;
    public final double pushFailureRate;
    public final int totalLatency;

    public PushStatsInfo(
            int currentBitrate, int rtt, double pushFailureRate, int totalLatency) {
        this.currentBitrate = currentBitrate;
        this.rtt = rtt;
        this.pushFailureRate = pushFailureRate;
        this.totalLatency = totalLatency;
    }
}
