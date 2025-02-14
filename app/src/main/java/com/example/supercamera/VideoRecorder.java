package com.example.supercamera;

import com.example.supercamera.BuildConfig;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
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
    //private boolean isRecording = false;
    private PublishSubject<byte[]> recordingQueue = PublishSubject.create();
    private Disposable recordingDisposable;
    private static final String TAG = "VideoRecorder";
    public final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final ExecutorService muxerExecutor = Executors.newSingleThreadExecutor();
    private final MediaCodec.BufferInfo reusableBufferInfo = new MediaCodec.BufferInfo();
    private int width;
    private int height;
    private final Object dimensionLock = new Object();

    public void startRecording(String outputPath, int width, int height, int fps, int bitrate) {
        synchronized (dimensionLock) {
            if (isRecording.get()) {
                throw new RuntimeException("已经开始录制，无法重复开启");
            }

            try {//bitrate单位kbps
                this.width = width;
                this.height = height;
                // 1. 初始化视频编码器（H.264）
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate * 1000);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);//关键帧间隔s
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);//vbr

                videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                videoEncoder.start();

                // 2. 初始化 MediaMuxer
                mediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                trackIndex = mediaMuxer.addTrack(videoEncoder.getOutputFormat());
                mediaMuxer.start();

                isRecording.set(true);

                // 3. 启动录制队列
                startProcessingQueue();
            } catch (IOException | IllegalStateException e) {
                Timber.tag(TAG).e(e, "录制初始化失败");
                cleanupResources();
                throw new RuntimeException("录制初始化失败");
            }
        }
    }

    private void startProcessingQueue() {
        Disposable disposable = recordingQueue
                .toFlowable(BackpressureStrategy.LATEST)
                .onBackpressureDrop(frame ->
                        Timber.tag(TAG).w("丢弃帧，当前队列压力过大")
                )
                .observeOn(Schedulers.io())
                .subscribe(frame -> {
                            if (!isRecording.get()) return;
                            int expectedSize = width * height * 3 / 2;
                            if (frame.length < expectedSize) {
                                Timber.tag(TAG).e("视频帧数据异常: 实际大小=%d，预期大小=%d", frame.length, expectedSize);
                                return;
                            }

                            int inputBufferIndex = videoEncoder.dequeueInputBuffer(10000);
                            if (inputBufferIndex >= 0) {
                                ByteBuffer inputBuffer = videoEncoder.getInputBuffer(inputBufferIndex); // 直接获取指定缓冲区
                                inputBuffer.clear();
                                inputBuffer.put(frame);
                                videoEncoder.queueInputBuffer(inputBufferIndex, 0, frame.length, System.nanoTime() / 1000, 0);
                            }

                            // 处理输出
                            int outputBufferIndex = videoEncoder.dequeueOutputBuffer(reusableBufferInfo, 10000);
                            while (outputBufferIndex >= 0) {
                                ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(outputBufferIndex);
                                MediaCodec.BufferInfo finalBufferInfo = cloneBufferInfo(reusableBufferInfo); // 深拷贝参数
                                muxerExecutor.execute(() -> {
                                    try {
                                        mediaMuxer.writeSampleData(trackIndex, outputBuffer, finalBufferInfo);
                                    } catch (IllegalStateException e) {
                                        Timber.e("Muxer写入失败: %s", e.getMessage());
                                    }
                                });
                                videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                                outputBufferIndex = videoEncoder.dequeueOutputBuffer(reusableBufferInfo, 0);
                            }
                        },
                        throwable -> {
                            Timber.e(throwable, "录制队列发生严重错误");
                            stopRecording();
                            throw new RuntimeException("录制队列发生严重错误");
                        });
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

                try {
                    if (videoEncoder != null) {
                        try {
                            videoEncoder.stop();
                        } catch (IllegalStateException e) {
                            Timber.e("编码器停止异常: %s", e.getMessage());
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
                        Timber.e("Muxer停止异常: %s", e.getMessage());
                    }
                }
            }
        }
    }

    private void cleanupResources() {
        try {
            if (videoEncoder != null) {
                videoEncoder.release();
            }
            if (mediaMuxer != null) {
                mediaMuxer.release();
            }
        } catch (Exception e) {
            Timber.tag(TAG).e(e, "资源清理失败");
        }
    }

    private MediaCodec.BufferInfo cloneBufferInfo(MediaCodec.BufferInfo source) {
        MediaCodec.BufferInfo clone = new MediaCodec.BufferInfo();
        clone.set(source.offset, source.size, source.presentationTimeUs, source.flags);
        return clone;
    }


    public PublishSubject<byte[]> getRecordingQueue() {
        return recordingQueue;
    }
}