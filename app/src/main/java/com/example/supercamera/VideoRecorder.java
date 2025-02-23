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
    //private Disposable recordingDisposable;
    private static final String TAG = "VideoRecorder";
    public final AtomicBoolean isRecording = new AtomicBoolean(false);
    //private final ExecutorService muxerExecutor = Executors.newSingleThreadExecutor();
    private int width;
    private int height;
    private final Object dimensionLock = new Object();
    private Surface inputSurface; //Surface成员
    private long lastPresentationTimeUs = 0;
    //private final AtomicBoolean isSurfaceReady = new AtomicBoolean(false);
    private final ExecutorService encoderExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isInitializing = new AtomicBoolean(false);

    public void startRecording(int width, int height, int fps, int bitrate, String outputPath) {
        if (isInitializing.get()) return;
        isInitializing.set(true);
            if (isRecording.get()) {
                throw new RuntimeException("已经开始录制，无法重复开启");
            }

            try {//bitrate单位kbps
                this.width = width;
                this.height = height;

                // 2. 初始化 MediaMuxer
                try {
                    mediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                }
                catch (Exception e)
                {
                    Timber.tag(TAG).e(e.getMessage());
                }
                // 1. 提前初始化编码器（H.264）
                try {

                    MediaFormat format = MediaFormat.createVideoFormat(
                            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
                    );
                    format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate * 1000);
                    format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                    //format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    //MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

                    // MTK芯片需要特殊参数
                    format.setInteger("vendor.mediatek.videoenc.force-venc-profile",
                            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                    format.setInteger("vendor.mediatek.feature.tile-encoding", 1); // 启用Tile编码
                    format.setInteger("vendor.mediatek.videoenc.tile-dimension-columns", 4);
                    format.setInteger("vendor.mediatek.videoenc.tile-dimension-rows", 2);

                    // 替换颜色格式
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

                    // 添加数据空间定义
                    format.setInteger(MediaFormat.KEY_COLOR_STANDARD,
                            MediaFormat.COLOR_STANDARD_BT709);
                    format.setInteger(MediaFormat.KEY_COLOR_RANGE,
                            MediaFormat.COLOR_RANGE_LIMITED);
                    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER,
                            MediaFormat.COLOR_TRANSFER_SDR_VIDEO);


                    videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                    videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                } catch (Exception e) {
                    Timber.tag(TAG).e(e.getMessage());
                }

                Timber.tag(TAG).d("编码器 Surface 已准备");

                //异步回调
                try {
                    videoEncoder.setCallback(new MediaCodec.Callback() {
                        private volatile boolean muxerStarted = false;

                        @Override
                        public void onInputBufferAvailable(MediaCodec mc, int inputBufferId) {
                            // Surface模式无需处理输入缓冲区
                        }

                        @Override
                        public void onOutputBufferAvailable(MediaCodec mc, int outputBufferId,
                                                            MediaCodec.BufferInfo bufferInfo) {
                            synchronized (dimensionLock) {
                                if (mediaMuxer == null || !muxerStarted) return;

                                // 获取实际的 ByteBuffer
                                ByteBuffer outputBuffer = mc.getOutputBuffer(outputBufferId);
                                if (outputBuffer == null) {
                                    Timber.tag(TAG).w("获取输出缓冲区失败");
                                    mc.releaseOutputBuffer(outputBufferId, false);
                                    return;
                                }

                                // 忽略配置数据（如 SPS/PPS）
                                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    mc.releaseOutputBuffer(outputBufferId, false);
                                    return;
                                }

                                try {
                                    // 时间戳修正
                                    if (bufferInfo.presentationTimeUs <= lastPresentationTimeUs) {
                                        bufferInfo.presentationTimeUs = lastPresentationTimeUs + 1000000 / fps;
                                    }
                                    lastPresentationTimeUs = bufferInfo.presentationTimeUs;

                                    mediaMuxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
                                } catch (Exception e) {
                                    Timber.tag(TAG).e("写入数据失败: %s", e.getMessage());
                                } finally {
                                    mc.releaseOutputBuffer(outputBufferId, false);
                                }
                            }
                        }


                        @Override
                        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {

                        }

                        @Override
                        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                            if (mediaMuxer == null || muxerStarted) return;

                            // 确保格式有效
                            if (!format.containsKey(MediaFormat.KEY_MIME)) {
                                Timber.tag(TAG).e("无效的媒体格式");
                                return;
                            }

                            trackIndex = mediaMuxer.addTrack(format);
                            mediaMuxer.start();
                            muxerStarted = true;
                            Timber.tag(TAG).d("Muxer 启动完成");
                        }
                    });
                } catch (Exception e) {
                    Timber.tag(TAG).e(e.getMessage());
                }

                // 2. 提前创建 Surface
                try {
                    inputSurface = videoEncoder.createInputSurface();
                    videoEncoder.start(); // 启动编码器但不立即开始录制
                } catch (Exception e) {
                    Timber.tag(TAG).e(e.getMessage());
                }

                isRecording.set(true);

                // 3. 启动录制队列
                //startProcessingQueue();
            } catch (Exception e) {
                Timber.tag(TAG).e(e, "录制初始化失败");
                cleanupResources();
                throw new RuntimeException("录制初始化失败");
            }
    }


    // 检查 Surface 有效性
    public boolean isSurfaceValid() {
        return inputSurface != null && inputSurface.isValid();
    }

    //Surface的方法
    public Surface getInputSurface() {
        if (!isRecording.get()) {
            throw new IllegalStateException("录制未开始");
        }
        return inputSurface;
    }

    public void stopRecording() {
        synchronized (dimensionLock) {
            if (!isRecording.get()) return;

            try {
                // 1. 发送编码结束信号（仅Surface模式需要）
                if (videoEncoder != null) {
                    videoEncoder.signalEndOfInputStream();
                }

                // 2. 直接停止编码器
                if (videoEncoder != null) {
                    videoEncoder.stop();
                }

                // 3. 停止并释放Muxer
                if (mediaMuxer != null) {
                    mediaMuxer.stop();
                    mediaMuxer.release();
                    mediaMuxer = null;
                }
            } catch (Exception e) {
                Timber.e("停止录制出错: %s", e.getMessage());
            } finally {
                // 4. 强制释放所有资源
                if (videoEncoder != null) {
                    videoEncoder.release();
                    videoEncoder = null;
                }
                if (inputSurface != null) {
                    inputSurface.release();
                    inputSurface = null;
                }
                isRecording.set(false);
            }
        }
    }


    private void cleanupResources() {
        synchronized (dimensionLock) {
            try {
                // 先停止编码器
                if (videoEncoder != null) {
                    videoEncoder.stop();
                    videoEncoder.release();
                    videoEncoder = null;
                }

                // 再停止 Muxer
                if (mediaMuxer != null) {
                    try {
                        if (isRecording.get()) {
                            mediaMuxer.stop();
                        }
                    } catch (IllegalStateException e) {
                        Timber.tag(TAG).w("Muxer 停止异常: %s", e.getMessage());
                    }
                    mediaMuxer.release();
                    mediaMuxer = null;
                }

                // 最后释放 Surface
                if (inputSurface != null) {
                    inputSurface.release();
                    inputSurface = null;
                }
            } catch (Exception e) {
                Timber.tag(TAG).e(e, "资源清理异常");
            }
        }
    }

}