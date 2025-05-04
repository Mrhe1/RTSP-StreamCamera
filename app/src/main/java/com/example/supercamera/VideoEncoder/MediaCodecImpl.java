package com.example.supercamera.VideoEncoder;

import static com.example.supercamera.MyException.MyException.ILLEGAL_STATE;
import static com.example.supercamera.MyException.MyException.RUNTIME_ERROR;
import static com.example.supercamera.VideoEncoder.ErrorCode.ERROR_CODEC;
import static com.example.supercamera.VideoEncoder.ErrorCode.ERROR_CODEC_CONFIG;
import static com.example.supercamera.VideoEncoder.ErrorCode.ERROR_CODEC_START;
import static com.example.supercamera.VideoEncoder.ErrorCode.ERROR_CODEC_STOP;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.example.supercamera.MyException.MyException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;

public class MediaCodecImpl implements VideoEncoder {
    private MyEncoderConfig mConfig;
    private final AtomicReference<EncoderListener> mListenerRef = new AtomicReference<>();
    private MediaCodec mMediaCodec;
    private final AtomicReference<Surface> mInputSurface = new AtomicReference<>();
    private final Object onErrorLock = new Object();
    private final Object publicLock = new Object();
    private final ExecutorService mEncoderExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService reportExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean onError = new AtomicBoolean(false);
    private final String TAG = "MediaCodec";

    @Override
    public void configure(MyEncoderConfig config) {
        synchronized (publicLock) {
            if (onError.get()) {
                Timber.tag(TAG).e("ILLEGAL_STATE,目前状态onError");
                throw throwException(ILLEGAL_STATE,ERROR_CODEC_CONFIG,
                        "ILLEGAL_STATE,目前状态onError");
            }

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
                        EncoderListener mListener = mListenerRef.get();
                        if (mListener != null) {
                            mListener.onInputBufferAvailable(codec, index);
                        }
                    }

                    // 调用时无需release OutputBuffer
                    @Override
                    public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index,
                                                        @NonNull MediaCodec.BufferInfo info) {
                        EncoderListener mListener = mListenerRef.get();
                        if (mListener != null) {
                            mListener.onOutputBufferAvailable(codec, index, info);
                        }

                        try {
                            codec.releaseOutputBuffer(index, false);
                        } catch (IllegalStateException ignored) {
                        }
                    }

                    @Override
                    public void onError(@NonNull MediaCodec codec,
                                        @NonNull MediaCodec.CodecException e) {
                        notifyError(RUNTIME_ERROR, ERROR_CODEC, e.getMessage());
                    }

                    @Override
                    public void onOutputFormatChanged(@NonNull MediaCodec codec,
                                                      @NonNull MediaFormat format) {
                        reportExecutor.submit(() -> {
                            EncoderListener mListener = mListenerRef.get();
                            if (mListener != null) {
                                mListener.onStart(codec, format);
                            }
                        });
                    }
                }, codecHandler);

                // 创建输入Surface
                mInputSurface.set(mMediaCodec.createInputSurface());
                reportExecutor.submit(() -> {
                    EncoderListener mListener = mListenerRef.get();
                    if (mListener != null) {
                        mListener.onSurfaceAvailable(mInputSurface.get());
                    }
                });

            } catch (Exception e) {
                Timber.tag(TAG).e(e,"codec配置失败");
                notifyError(RUNTIME_ERROR, ERROR_CODEC_CONFIG,
                        "配置失败: " + e.getMessage());
            }
        }
    }

    @Override
    public void start() {
        synchronized (publicLock) {
            if (onError.get()) {
                Timber.tag(TAG).e("ILLEGAL_STATE,目前状态onError");
                throw throwException(ILLEGAL_STATE,ERROR_CODEC_START,
                        "ILLEGAL_STATE,目前状态onError");
            }

            mEncoderExecutor.submit(() -> {
                Timber.tag(TAG).d("启动编码器");
                try {
                    if (mMediaCodec != null) {
                        mMediaCodec.start();
                    }
                } catch (IllegalStateException e) {
                    Timber.tag(TAG).e(e, "codec启动失败");
                    notifyError(RUNTIME_ERROR, ERROR_CODEC_START,
                            "codec启动失败: " + e.getMessage());
                }
            });
        }
    }

    @Override
    public void stop() {
        if (onError.get()) {
            Timber.tag(TAG).e( "ILLEGAL_STATE,目前状态onError");
            throw throwException(ILLEGAL_STATE,ERROR_CODEC_STOP,
                    "ILLEGAL_STATE,目前状态onError");
        }

        synchronized (publicLock) {
            try {
                // 发送编码结束信号前等待所有buffer处理
                if (mMediaCodec != null) {
                    mMediaCodec.signalEndOfInputStream();
                    Thread.sleep(50); // 等待50ms确保回调完成
                }
                if (mMediaCodec != null) {
                    mMediaCodec.stop();
                    mMediaCodec.release();
                    mMediaCodec = null;
                }
                if (mInputSurface.get() != null) {
                    mInputSurface.get().release();
                    mInputSurface.set(null);
                }
            } catch (Exception e) {
                Timber.tag(TAG).e(e, "停止codec出错");
            }
        }
    }

    @Override
    public void destroy() {
        synchronized (publicLock) {
            try {
                stop();
                if (mEncoderExecutor != null) {
                    mEncoderExecutor.shutdown();
                }
                if (reportExecutor != null) {
                    reportExecutor.shutdownNow();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void setEncoderListener(EncoderListener listener) {
        synchronized (publicLock) {
            mListenerRef.set(listener);
        }
    }

    //
    //---------------------------------
    //---------------------------------
    //  ERROR Handler  ****************
    //---------------------------------
    //---------------------------------
    //

    private MyException throwException(int type, int code, String message) {
        return new MyException(this.getClass().getPackageName(),
                type, code, message);
    }

    private void notifyError(int type,int code, String message) {
        if (onError.get()) return;
        onError.set(true);

        Executors.newSingleThreadExecutor().submit(() -> {
            synchronized (onErrorLock) {
                switch (code) {
                    case ERROR_CODEC -> errorStop_TypeA();
                    case ERROR_CODEC_START, ERROR_CODEC_CONFIG -> errorStop_TypeB();
                }

                onError.set(false);
                EncoderListener mListener = mListenerRef.get();
                if (mListener != null) {
                    mListener.onError(new MyException(this.getClass().getPackageName(),
                            type, code, message));
                }
            }
        });
    }

    private void errorStop_TypeA() {
        try {
            if (mMediaCodec != null) {
                mMediaCodec.stop();
                mMediaCodec.release();
            }
            if (mInputSurface != null) {
                mInputSurface.get().release();
            }

            if (mEncoderExecutor != null) {
                mEncoderExecutor.shutdown();
            }
            if (reportExecutor != null) {
                reportExecutor.shutdownNow();
            }
        } catch (Exception ignored) {}
    }

    private void errorStop_TypeB() {
        try {
            if (mMediaCodec != null) {
                mMediaCodec.release();
            }
            if (mInputSurface != null) {
                mInputSurface.get().release();
            }

            if (mEncoderExecutor != null) {
                mEncoderExecutor.shutdown();
            }
            if (reportExecutor != null) {
                reportExecutor.shutdown();
            }
        } catch (Exception ignored) {}
    }
}