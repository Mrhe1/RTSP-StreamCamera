package com.example.supercamera.VideoStreamer;

import static com.example.supercamera.MyException.MyException.ILLEGAL_ARGUMENT;
import static com.example.supercamera.MyException.MyException.ILLEGAL_STATE;
import static com.example.supercamera.StreamPusher.PushStats.TimeStamp.TimeStampStyle.Encoded;
import static com.example.supercamera.VideoStreamer.ErrorCode.ERROR_Codec;
import static com.example.supercamera.VideoStreamer.ErrorCode.ERROR_Pusher;
import static com.example.supercamera.VideoStreamer.ErrorCode.ERROR_Pusher_ReconnectFail;
import static com.example.supercamera.VideoStreamer.ErrorCode.ERROR_Pusher_START;
import static com.example.supercamera.VideoStreamer.ErrorCode.ERROR_Stream_CONFIG;
import static com.example.supercamera.VideoStreamer.ErrorCode.ERROR_Stream_START;
import static com.example.supercamera.VideoStreamer.ErrorCode.ERROR_Stream_STOP;
import static com.example.supercamera.VideoStreamer.StreamState.StreamStateEnum.CONFIGURED;
import static com.example.supercamera.VideoStreamer.StreamState.StreamStateEnum.DESTROYED;
import static com.example.supercamera.VideoStreamer.StreamState.StreamStateEnum.ERROR;
import static com.example.supercamera.VideoStreamer.StreamState.StreamStateEnum.READY;
import static com.example.supercamera.VideoStreamer.StreamState.StreamStateEnum.STARTING;
import static com.example.supercamera.VideoStreamer.StreamState.StreamStateEnum.STOPPING;
import static com.example.supercamera.VideoStreamer.StreamState.StreamStateEnum.STREAMING;
import static com.example.supercamera.VideoStreamer.StreamState.StreamStateEnum.StartPUSHING;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.example.supercamera.MyException.MyException;
import com.example.supercamera.StreamPusher.FFmpegPusherImpl;
import com.example.supercamera.StreamPusher.PushListener;
import com.example.supercamera.StreamPusher.PushStats.PushStatsInfo;
import com.example.supercamera.StreamPusher.PushStats.TimeStamp;
import com.example.supercamera.StreamPusher.StreamPusher;
import com.example.supercamera.VideoEncoder.EncoderListener;
import com.example.supercamera.VideoEncoder.MediaCodecImpl;
import com.example.supercamera.VideoEncoder.VideoEncoder;
import com.example.supercamera.VideoWorkflow.WorkflowState;

import io.reactivex.rxjava3.subjects.PublishSubject;
import timber.log.Timber;

public class VideoStreamerImpl implements VideoStreamer {
    private final StreamState state = new StreamState();
    private final VideoEncoder mVideoEncoder = new MediaCodecImpl();
    private final StreamPusher mPusher = new FFmpegPusherImpl();
    private StreamConfig mConfig;
    //private StreamListener mListener;
    private final AtomicReference<StreamListener> mListenerRef = new AtomicReference<>();
    private final ExecutorService report = Executors.newSingleThreadExecutor();
    // 外部调用锁
    private final Object publicLock = new Object();
    private final Object dimensionLock = new Object();
    private final Object errorLock = new Object();
    private final String TAG = "VideoStreamer";
    PublishSubject<TimeStamp> CaptureTimeStampQueue = null;
    PublishSubject<TimeStamp> EncodedTimeStampQueue = PublishSubject.create();
    private long lastPresentationTimeUs = 0;

    // throw MyException
    @Override
    public void configure(StreamConfig config) {
        synchronized (publicLock) {
            if (state.getState() != READY && state.getState() != CONFIGURED) {
                String msg = String.format("configure failed, current state: %s",
                        state.getState().toString());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_Stream_CONFIG, msg);
            }

            mConfig = config;
            checkParams(mConfig);

            setListeners();
            mVideoEncoder.configure(mConfig.getEncoderConfig());
            mPusher.configure(mConfig.getPushConfig());

            state.setState(CONFIGURED);
        }
    }

    @Override
    public void setTimeStampQueue(PublishSubject<TimeStamp> timeStampQueue) {
        synchronized (publicLock) {
            this.CaptureTimeStampQueue = timeStampQueue;
        }
    }

    // throw MyException
    @Override
    public void start() {
        synchronized (publicLock) {
            if (state.getState() != CONFIGURED) {
                String msg = String.format("start failed, current state: %s",
                        state.getState().toString());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_Stream_START, msg);
            }

            if(this.CaptureTimeStampQueue == null) {
                Timber.tag(TAG).e("CaptureTimeStampQueue，未设置");
                throw throwException(ILLEGAL_ARGUMENT, ERROR_Stream_START,
                        "CaptureTimeStampQueue，未设置");
            }

            boolean i = state.setState(STARTING);
            try {
                mVideoEncoder.start();
            } catch (MyException e) {
                Timber.tag(TAG).e(e,"start失败:%s", e.getMessage());
                state.setState(READY);
                throw e;
            }
        }
    }

    // throw MyException
    @Override
    public void stop() {
        synchronized (publicLock) {
            if(state.getState() == CONFIGURED || state.getState() == READY) return;

            if (state.getState() != STREAMING) {
                String msg = String.format("stop failed, current state: %s",
                        state.getState().toString());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_Stream_STOP, msg);
            }

            state.setState(STOPPING);
            try {
                mPusher.stop();
                mVideoEncoder.stop();
                state.setState(READY);
            } catch (MyException e) {
                Timber.tag(TAG).e(e,"stop失败:%s", e.getMessage());
                if(e.getExceptionType() == ILLEGAL_STATE) {
                    state.setState(STREAMING);
                }else {
                    notifyError(e, 0, ERROR_Stream_STOP, null);
                }
            }
        }
    }

    @Override
    public void destroy() {
        synchronized (publicLock) {
            mPusher.destroy();
            mVideoEncoder.destroy();
            cleanResource();
            state.setState(DESTROYED);
        }
    }


    @Override
    public void setStreamListener(StreamListener listener) {
        synchronized (publicLock) {
            mListenerRef.set(listener);
        }
    }

    private void setListeners() {
        mVideoEncoder.setEncoderListener(new EncoderListener() {
            @Override
            public void onError(MyException e) {
                notifyError(e,0,ERROR_Codec,null);
            }

            @Override
            public void onStart(MediaCodec codec, MediaFormat format) {}

            @Override
            public void onSurfaceAvailable(Surface surface) {
                report.submit(() -> {
                    StreamListener mListener = mListenerRef.get();
                    if (mListener != null) mListener.onSurfaceAvailable(surface);
                });
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec,
                                                int index, MediaCodec.BufferInfo info) {
                synchronized (dimensionLock) {
                    if(state.getState() != STREAMING &&
                    state.getState() != STARTING) return;

                    // 获取实际的 ByteBuffer
                    ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                    if (outputBuffer == null) {
                        Timber.tag(TAG).w("获取输出缓冲区失败");
                        return;
                    }

                    // 忽略配置数据（如 SPS/PPS）
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        if (state.getState() == StartPUSHING) {
                            // 提取 SPS/PPS
                            byte[] configData = new byte[info.size];
                            outputBuffer.position(info.offset);
                            outputBuffer.get(configData);
                            startPush(configData);
                        }
                        return;
                    }

                    pushFrame(outputBuffer, info);
                }
            }

            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {}
        });

        mPusher.setPushListener(new PushListener() {
            @Override
            public void onError(MyException e) {
                Timber.tag(TAG).e(e,"pusher出错:%s", e.getMessage());
                notifyError(e,0,ERROR_Pusher,null);
            }

            @Override
            public void onStatistics(PushStatsInfo stats) {
                report.submit(() -> {
                    StreamListener mListener = mListenerRef.get();
                    if(mListener != null)  mListener.onStatistics(stats);
                });
            }

            @Override
            public void onStart() {
                state.setState(STREAMING);
                report.submit(() -> {
                    StreamListener mListener = mListenerRef.get();
                    if (mListener != null) mListener.onStart();
                });
            }

            @Override
            public void onReconnect(boolean ifSuccess, int reconnectAttempts) {
                report.submit(() -> {
                    StreamListener mListener = mListenerRef.get();
                    if (mListener != null) {
                        mListener.onReconnect(ifSuccess, reconnectAttempts);
                    }
                });
            }

            @Override
            public void onReconnectFail(MyException e) {
                notifyError(e,0,ERROR_Pusher_ReconnectFail,null);
            }
        });
    }

    private void startPush(byte[] configData) {
        state.setState(StartPUSHING);
        try {
            if(CaptureTimeStampQueue == null) {
                throw throwException(ILLEGAL_ARGUMENT,ERROR_Stream_START,
                        "CaptureTimeStampQueue未设置");
            }
            List<PublishSubject<TimeStamp>> queue = new ArrayList<>();
            queue.add(EncodedTimeStampQueue);
            queue.add(CaptureTimeStampQueue);

            mPusher.setTimeStampQueue(queue);
            mPusher.start(configData);
        } catch (MyException e) {
            notifyError(e,0,ERROR_Pusher_START,null);
        }
    }

    private void pushFrame(ByteBuffer outputBuffer,MediaCodec.BufferInfo bufferInfo) {
        Long encodedTime = System.nanoTime();
        // 记录编码时间戳（纳秒）
        EncodedTimeStampQueue.onNext(new TimeStamp(Encoded, encodedTime));
        // 时间戳修正
        if (bufferInfo.presentationTimeUs <= lastPresentationTimeUs) {
            bufferInfo.presentationTimeUs = lastPresentationTimeUs + 1000000 / mConfig.fps;
        }
        lastPresentationTimeUs = bufferInfo.presentationTimeUs;

        mPusher.pushFrame(outputBuffer, bufferInfo, encodedTime);
    }

    private void cleanResource() {
        if(report != null) {
            report.shutdownNow();
        }

        if(EncodedTimeStampQueue != null) {
            EncodedTimeStampQueue.onComplete();
        }

        mListenerRef.set(null);
    }

    private void checkParams(StreamConfig config) {
        String msg = null;
        // 参数校验
        if (config.url == null) msg = "URL must be set";
        if (config.width <= 0 || config.height <= 0) msg = "Invalid resolution";
        if (config.bitrateKbps <= 0) msg = "Bitrate must be positive";
        if (config.maxReconnectAttempts < 0) msg = "Max reconnect attempts cannot be negative";
        if (config.reconnectPeriodSeconds <= 0) msg = "Reconnect period must be positive";
        if (config.iFrameInterval <= 0) msg = "I-Frame interval must be positive";

        if(msg != null) {
            throw throwException(ILLEGAL_ARGUMENT, ERROR_Stream_CONFIG, msg);
        }
    }

    private MyException throwException(int type, int code, String message) {
        return new MyException(this.getClass().getPackageName(),
                type, code, message);
    }

    private void notifyError(MyException e,int type, int code, String message) {
        if (state.getState() != STREAMING &&
                state.getState() != STREAMING &&
                state.getState() != STOPPING) return;

        state.setState(ERROR);

        Executors.newSingleThreadExecutor().submit(() -> {
            synchronized (errorLock) {
                switch (code) {
                    case ERROR_Pusher_START, ERROR_Pusher, ERROR_Pusher_ReconnectFail -> {
                        try {
                            mVideoEncoder.stop();
                        } catch (Exception ignored) {
                        }
                    }
                    case ERROR_Codec -> {
                        try {
                            mPusher.stop();
                        } catch (Exception ignored) {
                        }
                    }
                }

                state.setState(READY);
                StreamListener mListener = mListenerRef.get();
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