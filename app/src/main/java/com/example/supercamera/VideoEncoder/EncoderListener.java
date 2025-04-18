package com.example.supercamera.VideoEncoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.util.List;

public interface EncoderListener {

    void onError(String errorPackage, List<Integer> code, String message);
    void onStart(MediaFormat format);
    void onSurfaceAvailable(Surface surface);
    // 无需releaseOutputBuffer
    void onOutputBufferAvailable(int index, MediaCodec.BufferInfo info);
    void onInputBufferAvailable(int index);
}