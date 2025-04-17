package com.example.supercamera.VideoEncoder;

import static com.example.supercamera.VideoEncoder.EncoderListener.ERROR_CODEC;
import static com.example.supercamera.VideoEncoder.EncoderListener.ERROR_CODEC_START;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaCodecImpl implements VideoEncoder {
    private MyEncoderConfig mConfig;
    private EncoderListener mListener;
    private MediaCodec mMediaCodec;
    private Surface mInputSurface;
    private final ExecutorService mEncoderExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService reportExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void configure(MyEncoderConfig config) {
        mConfig = config;
        try {
            // 创建带Looper的HandlerThread
            HandlerThread codecThread = new HandlerThread("VideoEncoder-Callback");
            codecThread.start();
            Handler codecHandler = new Handler(codecThread.getLooper());

            // 创建并配置MediaCodec
            mMediaCodec = MediaCodec.createEncoderByType(config.mimeType);
            mMediaCodec.configure(mConfig.format, null, null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);

            // 设置异步回调
            mMediaCodec.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                    // Surface模式下无需处理输入缓冲区
                    if (mConfig.colorFormat ==
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) return;

                    if (mListener != null) {
                        mListener.onInputBufferAvailable(index);
                    }
                }

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index,
                                                    @NonNull MediaCodec.BufferInfo info) {
                    if (mListener != null) {
                        mListener.onOutputBufferAvailable(index, info);
                    }

                    try {
                        codec.releaseOutputBuffer(index, false);
                    } catch (IllegalStateException ignored) {}
                }

                @Override
                public void onError(@NonNull MediaCodec codec,
                                    @NonNull MediaCodec.CodecException e) {
                    notifyError(ERROR_CODEC, e.getMessage());
                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec codec,
                                                  @NonNull MediaFormat format) {
                    reportExecutor.submit(() -> {
                        if (mListener != null) {
                            mListener.onStart(format);
                        }
                    });
                }
            },codecHandler);

            // 创建输入Surface
            mInputSurface = mMediaCodec.createInputSurface();
            reportExecutor.submit(() -> {
                if (mListener != null) {
                    mListener.onSurfaceAvailable(mInputSurface);
                }
            });

        } catch (Exception e) {
            notifyError(EncoderListener.ERROR_CODEC_CONFIG, "配置失败: " + e.getMessage());
        }
    }

    @Override
    public void start() {
        mEncoderExecutor.submit(() -> {
            try {
                if (mMediaCodec != null) {
                    mMediaCodec.start();
                }
            } catch (IllegalStateException e) {
                notifyError(ERROR_CODEC_START, "启动失败: " + e.getMessage());
            }
        });
    }

    @Override
    public void stop() {
        try {
            if (mMediaCodec != null) {
                mMediaCodec.stop();
                mMediaCodec.release();
            }
            if (mInputSurface != null) {
                mInputSurface.release();
            }
        } catch (Exception e) {
            notifyError(EncoderListener.ERROR_CODEC_STOP, "停止失败: " + e.getMessage());
        }

        if (mEncoderExecutor != null) {
            mEncoderExecutor.shutdown();
        }
        if (reportExecutor != null) {
            reportExecutor.shutdown();
        }
    }

    @Override
    public void setStreamListener(EncoderListener listener) {
        mListener = listener;
    }

    private void notifyError(int code, String message) {
        switch (code) {
            case ERROR_CODEC: stop();
            case ERROR_CODEC_START : {
                if (mMediaCodec != null) {
                    mMediaCodec.release();
                }
                if (mInputSurface != null) {
                    mInputSurface.release();
                }
            }
        }

        Executors.newSingleThreadExecutor().submit(() -> {
            if (mListener != null) {
                mListener.onError(code, message);
            }
        });
    }
}

