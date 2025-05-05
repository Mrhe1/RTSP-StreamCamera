package com.example.supercamera.VideoRecorder;

import static com.example.supercamera.MyException.ErrorLock.getLock;
import static com.example.supercamera.MyException.ErrorLock.releaseLock;
import static com.example.supercamera.MyException.MyException.ILLEGAL_STATE;
import static com.example.supercamera.VideoRecorder.ErrorCode.ERROR_Codec;
import static com.example.supercamera.VideoRecorder.ErrorCode.ERROR_Recorder_CONFIG;
import static com.example.supercamera.VideoRecorder.ErrorCode.ERROR_Recorder_START;
import static com.example.supercamera.VideoRecorder.ErrorCode.ERROR_Recorder_STOP;
import static com.example.supercamera.VideoRecorder.RecorderState.RecorderStateEnum.*;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import com.example.supercamera.MyException.MyException;
import com.example.supercamera.VideoEncoder.EncoderListener;
import com.example.supercamera.VideoEncoder.MediaCodecImpl;
import com.example.supercamera.VideoEncoder.MyEncoderConfig;
import com.example.supercamera.VideoEncoder.VideoEncoder;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;

public class VideoRecorderImpl implements VideoRecorder {
    private final RecorderState state = new RecorderState();
    private static final String TAG = "VideoRecorder";
    private final Object publicLock = new Object();
    private final Object muxerLock = new Object();
    private final Object encoderLock = new Object();
    private final Object errorLock = new Object();

    //private RecorderConfig mConfig;
    private final AtomicReference<RecorderListener> mListenerRef = new AtomicReference<>();
    private final VideoEncoder mVideoEncoder = new MediaCodecImpl();
    private MediaMuxer mMediaMuxer;
    private int mTrackIndex = -1;
    private Surface mInputSurface;
    private final AtomicBoolean mIsMuxerStarted = new AtomicBoolean(false);
    private final ExecutorService mEncoderExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void configure(RecorderConfig config) {
        synchronized (publicLock) {
            if (state.getState() != READY && state.getState() != CONFIGURED) {
                String msg = String.format("configure failed, current state: %s",
                        state.getState().toString());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_Recorder_CONFIG, msg);
            }

            try {
                // 转换配置到EncoderConfig
                MyEncoderConfig encoderConfig = new MyEncoderConfig.Builder(config.width, config.height)
                        .setFps(config.fps)
                        .setIFrameInterval(config.iFrameInterval)
                        .setKBitrate(config.bitrate) // 单位kbps
                        .setProfile(config.profile)
                        .setMimeType(config.mimeType)
                        .setColorFormat(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        .build();

                // 初始化编码器
                synchronized (encoderLock) {
                    mVideoEncoder.configure(encoderConfig);
                    mVideoEncoder.setEncoderListener(new EncoderListener() {
                        @Override
                        public void onError(MyException e) {
                            notifyError(e, 0, 0, null);
                        }

                        @Override
                        public void onStart(MediaCodec codec, MediaFormat format) {
                            setupMuxer(format);
                            boolean i = state.setState(RECORDING);
                            RecorderListener mListener = mListenerRef.get();
                            if (mListener != null) mListener.onStart();
                        }

                        @Override
                        public void onSurfaceAvailable(Surface surface) {
                            mInputSurface = surface;
                            RecorderListener mListener = mListenerRef.get();
                            if (mListener != null) {
                                mListener.onSurfaceAvailable(surface);
                            }
                        }

                        @Override
                        public void onOutputBufferAvailable(MediaCodec codec, int index,
                                                            MediaCodec.BufferInfo info) {
                            handleEncodedData(codec, index, info);
                        }

                        @Override
                        public void onInputBufferAvailable(MediaCodec codec, int index) {
                            // Surface模式无需处理
                        }
                    });

                    // 初始化muxer
                    mMediaMuxer = new MediaMuxer(config.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                }

                state.setState(CONFIGURED);
            } catch (Exception e) {
                Timber.tag(TAG).e(e, "Encoder configuration failed");
                throw throwException(ILLEGAL_STATE, ERROR_Recorder_CONFIG, e.getMessage());
            }
        }
    }

    @Override
    public void start() {
        synchronized (publicLock) {
            if (state.getState() != CONFIGURED) {
                String msg = String.format("start failed, current state: %s",
                        state.getState().toString());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_Recorder_START, msg);
            }

            state.setState(STARTING);

            try {
                mVideoEncoder.start();
            } catch (MyException e) {
                state.setState(CONFIGURED); // 状态回退
                throw e;
            }
        }
    }

    @Override
    public void stop() {
        synchronized (publicLock) {
            if(state.getState() == CONFIGURED || state.getState() == READY) return;

            if (state.getState() != RECORDING) {
                String msg = String.format("stop failed, current state: %s",
                        state.getState().toString());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_Recorder_STOP, msg);
            }

            state.setState(STOPPING);
            try {
                mVideoEncoder.stop();
                releaseMuxer();
                if (mInputSurface != null) {
                    mInputSurface.release();
                    mInputSurface = null;
                }
                state.setState(READY);
            } catch (MyException e) {
                if (e.getExceptionType() == ILLEGAL_STATE) {
                    state.setState(RECORDING);
                } else {
                    notifyError(e, 0, ERROR_Recorder_STOP, null);
                }
            }
        }
    }

    @Override
    public void destroy() {
        synchronized (publicLock) {
            try {
                mVideoEncoder.destroy();
                releaseMuxer();
                releaseResource();
                state.setState(DESTROYED);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void setRecorderListener(RecorderListener listener) {
        mListenerRef.set(listener);
    }

    private void setupMuxer(MediaFormat format) {
        synchronized (muxerLock) {
            try {
                if (mIsMuxerStarted.get()) return;

                mTrackIndex = mMediaMuxer.addTrack(format);
                mMediaMuxer.start();
                mIsMuxerStarted.set(true);
                Timber.tag(TAG).d("Muxer started");
            } catch (Exception e) {
                Timber.tag(TAG).e(e, "Muxer initialization failed");
                notifyError(null,ILLEGAL_STATE, ERROR_Recorder_START, e.getMessage());
            }
        }
    }

    private void handleEncodedData(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
        //mEncoderExecutor.submit(() -> {
            try {
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return;

                synchronized (muxerLock) {
                    if (mIsMuxerStarted.get() && mMediaMuxer != null) {
                        mMediaMuxer.writeSampleData(mTrackIndex, buffer, info);
                    }
                }
                // 调用时无需release OutputBuffer
            } catch (Exception e) {
                Timber.tag(TAG).e(e, "Error writing sample data");
            }
        //});
    }

    private void releaseResource() {
        if(mEncoderExecutor != null) {
            mEncoderExecutor.shutdown();
        }
        if (mInputSurface != null) {
            mInputSurface.release();
        }
    }
    private void releaseMuxer() {
        synchronized (muxerLock) {
            if (mMediaMuxer != null) {
                try {
                    if (mIsMuxerStarted.get()) {
                        mMediaMuxer.stop();
                        mMediaMuxer.release();
                    }
                } catch (Exception e) {
                    Timber.tag(TAG).e(e, "Muxer release failed");
                }finally {
                    mMediaMuxer = null;
                    mIsMuxerStarted.set(false);
                }
            }
        }
    }

    private void stopCodec() {
        try {
            mVideoEncoder.stop();
        } catch (Exception ignored) {}
    }

    private MyException throwException(int type, int code, String message) {
        return new MyException(this.getClass().getPackageName(), type, code, message);
    }

    private void notifyError(MyException e,int type, int code, String message) {
        if (state.getState() != RECORDING &&
                state.getState() != STARTING &&
                state.getState() != STOPPING) return;

        state.setState(ERROR);
        // 获取errorLock
        if(!getLock()) return;

        Executors.newSingleThreadExecutor().submit(() -> {
            synchronized (errorLock) {
                switch (code) {
                    case ERROR_Codec -> releaseMuxer();
                    case ERROR_Recorder_START -> {
                        stopCodec();
                        releaseMuxer();
                    }
                }

                state.setState(READY);
                // 释放errorLock
                releaseLock();

                RecorderListener mListener = mListenerRef.get();
                if (mListener != null) {
                    if (e != null) {
                        e.addCode(code);
                        mListener.onError(e);
                    } else {
                        mListener.onError(throwException(type, code, message));
                    }
                }
            }
        });
    }
}