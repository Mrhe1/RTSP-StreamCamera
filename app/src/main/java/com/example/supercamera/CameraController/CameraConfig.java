package com.example.supercamera.CameraController;

import android.util.Size;

public class CameraConfig {
    // StabilizationMode
    public static final int Stab_OFF = 0;
    public static final int Stab_OIS_ONLY = 1;   // 仅光学防抖
    public static final int Stab_EIS_ONLY = 2;   // 仅电子防抖
    public static final int Stab_HYBRID = 3;     // 混合模式（OIS+EIS）

    // Camera Facing
    public static final int Camera_Facing_BACK = 0;
    public static final int Camera_Facing_Front = 1;

    public final Size previewSize;
    public final Size recordSize;
    public final int fps;
    public final int stabilizationMode;
    public final int cameraFacing;

    private CameraConfig(Builder builder) {
        this.previewSize = builder.previewSize;
        this.recordSize = builder.recordSize;
        this.fps = builder.fps;
        this.stabilizationMode = builder.stabilizationMode;
        this.cameraFacing = builder.cameraFacing;
    }

    public static class Builder {
        private Size previewSize;
        private Size recordSize;
        private int fps = 30;
        private int stabilizationMode = Stab_OFF;
        private int cameraFacing = Camera_Facing_BACK;

        public Builder(Size previewSize, Size recordSize) {
            this.previewSize = previewSize;
            this.recordSize = recordSize;
        }

        public Builder setFpsRange(int fps) {
            this.fps = fps;
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

        public CameraConfig build() {
            return new CameraConfig(this);
        }
    }
}
