package com.example.supercamera.VideoWorkflow;

import static com.example.supercamera.CameraController.CameraConfig.Camera_Facing_BACK;
import static com.example.supercamera.CameraController.CameraConfig.Stab_OFF;

import android.media.MediaFormat;
import android.util.Size;

import com.example.supercamera.CameraController.CameraConfig;
import com.example.supercamera.VideoRecorder.RecorderConfig;
import com.example.supercamera.VideoStreamer.StreamConfig;


public class WorkflowConfig {
    public final String url;
    public final String recordPath;
    public final Size pushSize;
    public final Size recordSize;
    public final int fps;
    public final int pushBitrateKbps;
    public final int recordBitrateKbps;
    public final int maxReconnectAttempts;
    public final int reconnectPeriodSeconds;
    public final int iFrameInterval;
    public final String encoderFormat;
    public final int stabilizationMode;
    public int cameraFacing;
    public final CameraConfig cameraConfig;
    public final StreamConfig streamConfig;
    public final RecorderConfig recorderConfig;

    private WorkflowConfig(Builder builder) {
        this.url = builder.url;
        this.recordPath = builder.recordPath;
        this.pushSize = builder.pushSize;
        this.recordSize = builder.recordSize;
        this.fps = builder.fps;
        this.pushBitrateKbps = builder.pushBitrateKbps;
        this.recordBitrateKbps = builder.recordBitrateKbps;
        this.maxReconnectAttempts = builder.maxReconnectAttempts;
        this.reconnectPeriodSeconds = builder.reconnectPeriodSeconds;
        this.iFrameInterval = builder.iFrameInterval;
        this.encoderFormat = builder.encoderFormat;
        this.stabilizationMode = builder.stabilizationMode;
        this.cameraFacing = builder.cameraFacing;

        this.cameraConfig = new CameraConfig.Builder(pushSize, recordSize)
                .setFps(fps)
                .setStabilizationMode(stabilizationMode)
                .setCameraFacing(cameraFacing)
                .build();

        this.recorderConfig =
                new RecorderConfig.Builder(recordSize.getWidth(), recordSize.getHeight(), recordPath)
                        .setBitrate(recordBitrateKbps)
                        .setMimeType(encoderFormat)
                        .setIFrameInterval(iFrameInterval)
                        .build();

        this.streamConfig =
                new StreamConfig.Builder(url,pushSize.getWidth(),pushSize.getHeight())
                        .setBitrateKbps(pushBitrateKbps)
                        .setEncoderFormat(encoderFormat)
                        .setIFrameInterval(iFrameInterval)
                        .setFps(fps)
                        .setReconnectParams(maxReconnectAttempts, reconnectPeriodSeconds)
                        .build();
    }

    // Builder内部类
    public static class Builder {
        private String url;
        public String recordPath;
        private Size pushSize;
        private Size recordSize;
        private int fps = 30;
        private int pushBitrateKbps = 1000; // 默认1000kbps（1Mbps）
        private int recordBitrateKbps = 4000; // 默认4000kbps（4Mbps）
        private int maxReconnectAttempts = 4;
        private int reconnectPeriodSeconds = 4;
        private int iFrameInterval = 1;
        private String encoderFormat = MediaFormat.MIMETYPE_VIDEO_AVC;
        private int stabilizationMode = Stab_OFF;
        private int cameraFacing = Camera_Facing_BACK;

        public Builder(Size pushSize, Size recordSize, String url, String recordPath) {
            this.url = url;
            this.pushSize = pushSize;
            this.recordSize = recordSize;
            this.recordPath = recordPath;
        }

        public Builder setFps(int fps) {
            this.fps = fps;
            return this;
        }

        public Builder setBitrateKbps(int pushBitrateKbps, int recordBitrateKbps) {
            this.pushBitrateKbps = pushBitrateKbps;
            this.recordBitrateKbps = recordBitrateKbps;
            return this;
        }

        public Builder setReconnectParams(int maxAttempts, int periodSeconds) {
            this.maxReconnectAttempts = maxAttempts;
            this.reconnectPeriodSeconds = periodSeconds;
            return this;
        }

        public Builder setIFrameInterval(int interval) {
            this.iFrameInterval = interval;
            return this;
        }

        public Builder setEncoderFormat(String format) {
            this.encoderFormat = format;
            return this;
        }

        public Builder setStabilizationMode(int mode) {
            this.stabilizationMode = mode;
            return this;
        }

        public Builder setCameraFacing(int cameraFacing) {
            this.cameraFacing = cameraFacing;
            return this;
        }

        public WorkflowConfig build() {
            return new WorkflowConfig(this);
        }
    }
}
