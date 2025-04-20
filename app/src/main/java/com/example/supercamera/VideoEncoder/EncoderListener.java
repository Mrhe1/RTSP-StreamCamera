package com.example.supercamera.VideoEncoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import com.example.supercamera.MyException.MyException;

import java.util.List;

public interface EncoderListener {

    void onError(MyException e);
    void onStart(MediaFormat format);
    void onSurfaceAvailable(Surface surface);
    // 无需releaseOutputBuffer
    void onOutputBufferAvailable(int index, MediaCodec.BufferInfo info);
    void onInputBufferAvailable(int index);
}