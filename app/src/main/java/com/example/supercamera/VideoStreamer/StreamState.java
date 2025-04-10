package com.example.supercamera.VideoStreamer;

public class StreamState {
    public enum PushStateEnum {
        IDLE, CONFIGURING, STARTING, RUNNING, STOPPING, RECONNECTING
    }
}