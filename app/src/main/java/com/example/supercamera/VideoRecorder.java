package com.example.supercamera;

import com.example.supercamera.BuildConfig;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;
import timber.log.Timber;

public class VideoRecorder {
    private MediaCodec videoEncoder;
    private MediaMuxer mediaMuxer;
    private int trackIndex;
    private Disposable recordingDisposable;
    private static final String TAG = "VideoRecorder";
    public final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final ExecutorService muxerExecutor = Executors.newSingleThreadExecutor();
    private int width;
    private int height;
    private final Object dimensionLock = new Object();
    private Surface inputSurface; //Surface成员
    private long lastPresentationTimeUs = 0;
    private final AtomicBoolean isSurfaceReady = new AtomicBoolean(false);

    public void startRecording(String outputPath) {
        synchronized (dimensionLock) {
            if (isRecording.get()) {
                throw new RuntimeException("已经开始录制，无法重复开启");
            }

            try {//bitrate单位kbps

                //异步回调
                videoEncoder.setCallback(new MediaCodec.Callback() {
                    @Override
                    public void onInputBufferAvailable(MediaCodec mc, int inputBufferId) {
                        // Surface模式无需处理输入缓冲区
                    }

                    @Override
                    public void onOutputBufferAvailable(MediaCodec mc, int outputBufferId,
                                                        MediaCodec.BufferInfo bufferInfo) {
                        // 处理编码后的数据
                        ByteBuffer outputBuffer = mc.getOutputBuffer(outputBufferId);
                        mediaMuxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
                        mc.releaseOutputBuffer(outputBufferId, false);
                    }

                    @Override
                    public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {

                    }

                    @Override
                    public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {

                    }
                });

                // 2. 初始化 MediaMuxer
                mediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                trackIndex = mediaMuxer.addTrack(videoEncoder.getOutputFormat());
                mediaMuxer.start();

                isRecording.set(true);

                // 3. 启动录制队列
                //startProcessingQueue();
            } catch (IOException | IllegalStateException e) {
                Timber.tag(TAG).e(e, "录制初始化失败");
                cleanupResources();
                throw new RuntimeException("录制初始化失败");
            }
        }
    }

    //提前创建编码器和Surface
    public void prepareRecorder(int width, int height, int fps, int bitrate) {
        synchronized (dimensionLock) {
            try {
                this.width = width;
                this.height = height;

                // 1. 提前初始化编码器（H.264）
                MediaFormat format = MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC, width, height
                );
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate * 1000);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

                videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

                // 2. 提前创建 Surface
                inputSurface = videoEncoder.createInputSurface();
                videoEncoder.start(); // 启动编码器但不立即开始录制

                Timber.tag(TAG).d("编码器 Surface 已准备");
            } catch (IOException e) {
                Timber.tag(TAG).e(e, "编码器初始化失败");
                cleanupResources();
            }
        }
    }

    // 新增方法：检查 Surface 有效性
    public boolean isSurfaceValid() {
        return inputSurface != null && inputSurface.isValid();
    }

    //Surface的方法
    public Surface getInputSurface() {
        return inputSurface;
    }

    public void stopRecording() {
        synchronized (dimensionLock) {
            this.width = 0;
            this.height = 0;

            if (isRecording.get()) {
                // 清理队列
                if (recordingDisposable != null && !recordingDisposable.isDisposed()) {
                    recordingDisposable.dispose();
                }

                if (inputSurface != null) {
                    try {
                        inputSurface.release();
                        inputSurface = null;
                    } catch (Exception e) {
                        Timber.tag(TAG).e("surface释放异常: %s", e.getMessage());
                    }
                }

                try {
                    if (videoEncoder != null) {
                        try {
                            videoEncoder.stop();
                        } catch (IllegalStateException e) {
                            Timber.tag(TAG).e("编码器停止异常: %s", e.getMessage());
                        }
                        videoEncoder.release();
                    }
                } finally {
                    try {
                        if (mediaMuxer != null) {
                            mediaMuxer.stop();
                            mediaMuxer.release();
                        }
                    } catch (IllegalStateException e) {
                        Timber.tag(TAG).e("Muxer停止异常: %s", e.getMessage());
                    }
                }
                isRecording.set(false);
            }
        }
    }

    private void cleanupResources() {
        try {
            if (inputSurface != null) {
                inputSurface.release();
                inputSurface = null;
            }
            if (videoEncoder != null) {
                videoEncoder.release();
            }
            if (mediaMuxer != null) {
                mediaMuxer.release();
            }
            isRecording.set(false);
        } catch (Exception e) {
            Timber.tag(TAG).e(e, "资源清理失败");
        }
    }
}