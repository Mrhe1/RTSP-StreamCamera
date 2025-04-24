package com.example.supercamera.VideoStreamer;

import static com.example.supercamera.MyException.MyException.ILLEGAL_ARGUMENT;
import static com.example.supercamera.MyException.MyException.ILLEGAL_STATE;
import static com.example.supercamera.StreamPusher.PushStats.TimeStamp.TimeStampStyle.Encoded;
import static com.example.supercamera.VideoStreamer.ErrorCode.ERROR_Stream_CONFIG;
import static com.example.supercamera.VideoStreamer.ErrorCode.ERROR_Stream_START;
import static com.example.supercamera.VideoStreamer.ErrorCode.ERROR_Stream_STOP;
import static com.example.supercamera.VideoStreamer.StreamState.StreamStateEnum.CONFIGURED;
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

import com.example.supercamera.MyException.MyException;
import com.example.supercamera.StreamPusher.FFmpegPusherImpl;
import com.example.supercamera.StreamPusher.PushListener;
import com.example.supercamera.StreamPusher.PushStats.PushStatsInfo;
import com.example.supercamera.StreamPusher.PushStats.TimeStamp;
import com.example.supercamera.StreamPusher.StreamPusher;
import com.example.supercamera.VideoEncoder.EncoderListener;
import com.example.supercamera.VideoEncoder.MediaCodecImpl;
import com.example.supercamera.VideoEncoder.VideoEncoder;

import io.reactivex.rxjava3.subjects.PublishSubject;
import timber.log.Timber;

public class VideoStreamerImpl implements VideoStreamer {
    private VideoEncoder mVideoEncoder = new MediaCodecImpl();
    private StreamPusher mPusher = new FFmpegPusherImpl();
    private StreamConfig mConfig;
    private StreamListener mListener;
    private ExecutorService report = Executors.newSingleThreadExecutor();
    // 外部调用锁
    private final Object publicLock = new Object();
    private final Object dimensionLock = new Object();
    private String TAG = "VideoStreamer";
    PublishSubject<TimeStamp> CaptureTimeStampQueue = null;
    PublishSubject<TimeStamp> EncodedTimeStampQueue = PublishSubject.create();
    private long lastPresentationTimeUs = 0;

    // throw MyException
    @Override
    public void configure(StreamConfig config) {
        synchronized (publicLock) {
            if (StreamState.getState() != READY) {
                String msg = String.format("configure failed, current state: %s", StreamState.getState());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_Stream_CONFIG, msg);
            }
            mConfig = config;
            checkParams(mConfig);

            mVideoEncoder.configure(mConfig.getEncoderConfig());
            mPusher.configure(mConfig.getPushConfig());

            setListener();
        }
    }

    @Override
    public void setTimeStampQueue(PublishSubject<TimeStamp> timeStampQueue) {
        this.CaptureTimeStampQueue = timeStampQueue;
    }

    // throw MyException
    @Override
    public void start() {
        synchronized (publicLock) {
            if (StreamState.getState() != CONFIGURED) {
                String msg = String.format("start failed, current state: %s", StreamState.getState());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_Stream_START, msg);
            }

            if(this.CaptureTimeStampQueue == null) {
                Timber.tag(TAG).e("CaptureTimeStampQueue，未设置");
                throw throwException(ILLEGAL_ARGUMENT, ERROR_Stream_START,
                        "CaptureTimeStampQueue，未设置");
            }

            mVideoEncoder.start();
        }
    }

    // throw MyException
    @Override
    public void stop() {
        synchronized (publicLock) {
            if (StreamState.getState() != STREAMING) {
                String msg = String.format("stop failed, current state: %s", StreamState.getState());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_Stream_STOP, msg);
            }
        }
        try {
            mPusher.stop();
            mVideoEncoder.stop();
        } catch (MyException e) {
            throw e;
        }finally {
            cleanResource();
        }
    }


    @Override
    public void setStreamListener(StreamListener listener) {
        synchronized (publicLock) {
            this.mListener = listener;
        }
    }

    private void setListener() {
        mVideoEncoder.setEncoderListener(new EncoderListener() {
            @Override
            public void onError(MyException e) {
                notifyError(e,0,0,null);
            }

            @Override
            public void onStart(MediaCodec codec, MediaFormat format) {}

            @Override
            public void onSurfaceAvailable(Surface surface) {
                if(mListener != null) mListener.onSurfaceAvailable(surface);
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec,
                                                int index, MediaCodec.BufferInfo info) {
                synchronized (dimensionLock) {
                    if(StreamState.getState() != STREAMING &&
                    StreamState.getState() != STARTING) return;

                    // 获取实际的 ByteBuffer
                    ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                    if (outputBuffer == null) {
                        Timber.tag(TAG).w("获取输出缓冲区失败");
                        codec.releaseOutputBuffer(index, false);
                        return;
                    }

                    // 忽略配置数据（如 SPS/PPS）
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        if (StreamState.getState() == StartPUSHING) {
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
                notifyError(e,0,0,null);
            }

            @Override
            public void onStatistics(PushStatsInfo stats) {
                if(mListener != null) mListener.onStatistics(stats);
            }

            @Override
            public void onStart() {
                StreamState.setState(STREAMING);
                if(mListener != null) mListener.onStart();
            }

            @Override
            public void onReconnect(boolean ifSuccess, int reconnectAttempts) {

            }

            @Override
            public void onReconnectFail(MyException e) {
                notifyError(e,0,0,null);
            }
        });
    }

    private void startPush(byte[] configData) {
        StreamState.setState(StartPUSHING);
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
            notifyError(e,0,0,null);
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
        if (StreamState.getState() != STREAMING &&
                StreamState.getState() != STREAMING &&
                StreamState.getState() != STOPPING) return;

        StreamState.setState(ERROR);

        Executors.newSingleThreadExecutor().submit(() -> {
        });
    }
}
