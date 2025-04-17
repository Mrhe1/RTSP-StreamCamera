package com.example.supercamera.VideoEncoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

public interface EncoderListener {
    int ERROR_CODEC_CONFIG = 0x1001;
    int ERROR_CODEC_START = 0x1002;
    int ERROR_CODEC_STOP = 0x1003;
    int ERROR_CODEC = 0x1004;

    void onError(int code, String message);
    void onStart(MediaFormat format);
    void onSurfaceAvailable(Surface surface);
    // 无需releaseOutputBuffer
    void onOutputBufferAvailable(int index, MediaCodec.BufferInfo info);
    void onInputBufferAvailable(int index);
}