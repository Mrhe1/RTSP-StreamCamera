package com.example.supercamera;

import com.example.supercamera.BuildConfig;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.IOException;
import java.nio.ByteBuffer;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;

public class VideoRecorder {
    private MediaCodec videoEncoder;
    private MediaMuxer mediaMuxer;
    private int trackIndex;
    private boolean isRecording = false;
    private PublishSubject<byte[]> recordingQueue = PublishSubject.create();
    private Disposable recordingDisposable;

    public void startRecording(String outputPath, int width, int height, int bitrate) {
        try {
            // 1. 初始化视频编码器（H.264）
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            videoEncoder.start();

            // 2. 初始化 MediaMuxer
            mediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            trackIndex = mediaMuxer.addTrack(videoEncoder.getOutputFormat());
            mediaMuxer.start();

            isRecording = true;

            // 3. 启动录制队列
            startProcessingQueue();
        } catch (IOException e) {
            Timber.e(e, "Recording initialization failed");
        }
    }

    private void startProcessingQueue() {
        recordingDisposable = recordingQueue
                .onBackpressureDrop(frame ->
                        Timber.w("丢弃录制帧，当前队列压力过大")
                )
                .observeOn(Schedulers.io())
                .subscribe(frame -> {
                    if (!isRecording) return;

                    ByteBuffer[] inputBuffers = videoEncoder.getInputBuffers();
                    int inputBufferIndex = videoEncoder.dequeueInputBuffer(10000);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                        inputBuffer.clear();
                        inputBuffer.put(frame);
                        videoEncoder.queueInputBuffer(inputBufferIndex, 0, frame.length, System.nanoTime() / 1000, 0);
                    }

                    // 处理输出（保持不变）
                    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                    int outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000);
                    while (outputBufferIndex >= 0) {
                        ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(outputBufferIndex);
                        mediaMuxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
                        videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                        outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 0);
                    }
                });
    }

    // 将 YUV 数据送入编码器
    public void feedData(byte[] yuvData) {
        recordingQueue.onNext(yuvData); // 改为推入队列
    }

    public void stopRecording() {
        if (isRecording) {
            // 清理队列
            if (recordingDisposable != null && !recordingDisposable.isDisposed()) {
                recordingDisposable.dispose();
            }

            // 释放编码器和Muxer
            videoEncoder.stop();
            videoEncoder.release();
            mediaMuxer.stop();
            mediaMuxer.release();
            isRecording = false;
        }
    }

    public PublishSubject<byte[]> getRecordingQueue() {
        return recordingQueue;
    }
}