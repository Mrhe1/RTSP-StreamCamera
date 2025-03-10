package com.example.supercamera;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;

import androidx.annotation.NonNull;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.rxjava3.subjects.PublishSubject;
import timber.log.Timber;

public class VideoPusher {
    private MediaCodec videoEncoder;
    private int trackIndex;
    private static final String TAGcodec = "StreamEncoder";
    private static final String TAG = "VideoPusher";
    public final AtomicBoolean isRecording = new AtomicBoolean(false);
    private int width;
    private int height;
    private int fps;
    private int avgBitrate;
    private int maxBitrate;
    private int minBitrate;
    private FFmpegPusher pusher;
    private final Object dimensionLock = new Object();
    private Surface inputSurface; //Surface成员
    private long lastPresentationTimeUs = 0;
    private final ExecutorService encoderExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isInitializing = new AtomicBoolean(false);
    private final PublishSubject<PushReport> reportSubject = PublishSubject.create();

    // 事件类型定义
    public enum EventType  {
        ERROR,
        STARTED
    }

    // 事件报告类
    public static class PushReport  {
        public final EventType type;
        public final int code;
        public final String message;

        public PushReport (EventType type, int code, String message) {
            this.type = type;
            this.code = code;
            this.message = message;
        }
    }

    public VideoPusher(String url, int width, int height, int fps,
                         int avgBitrate)
    {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.avgBitrate = avgBitrate;

        pusher = new FFmpegPusher(url, width, height, fps, avgBitrate);
        startStreamEncoder(width, height, fps, avgBitrate);
    }

    public void stopPush() {
        pusher.stopPush();
        stopRecording();
    }

    private void startStreamEncoder(int width, int height, int fps, int bitrate) {
        if (isInitializing.get()) return;
        isInitializing.set(true);
        if (isRecording.get()) {
            throw new RuntimeException("已经开始录制，无法重复开启");
        }

        try {//bitrate单位kbps
            this.width = width;
            this.height = height;

            // 1. 提前初始化编码器（H.264）
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

            Timber.tag(TAGcodec).d("编码器 Surface 已准备");

            //异步回调
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
                        if () return;

                        // 获取实际的 ByteBuffer
                        ByteBuffer outputBuffer = mc.getOutputBuffer(outputBufferId);
                        if (outputBuffer == null) {
                            Timber.tag(TAGcodec).w("获取输出缓冲区失败");
                            mc.releaseOutputBuffer(outputBufferId, false);
                            return;
                        }

                        // 忽略配置数据（如 SPS/PPS）
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            // 提取 SPS/PPS
                            byte[] configData = new byte[bufferInfo.size];
                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.get(configData);

                            // 初始化推流器（确保只执行一次）
                            if (!pusher.isInitialized()) {
                                pusher.initPusher(configData);
                            }

                            mc.releaseOutputBuffer(outputBufferId, false);
                            return;
                        }

                        try {
                            // 时间戳修正
                            if (bufferInfo.presentationTimeUs <= lastPresentationTimeUs) {
                                bufferInfo.presentationTimeUs = lastPresentationTimeUs + 1000000 / fps;
                            }
                            lastPresentationTimeUs = bufferInfo.presentationTimeUs;

                            encoderExecutor.submit(() ->
                                    //mediaMuxer.writeSampleData(trackIndex, outputBuffer, bufferInfo));
                        } catch (Exception e) {
                            Timber.tag(TAGcodec).e("写入数据失败: %s", e.getMessage());
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
                    if () return;

                    // 确保格式有效
                    if (!format.containsKey(MediaFormat.KEY_MIME)) {
                        Timber.tag(TAGcodec).e("无效的媒体格式");
                        return;
                    }

                    Timber.tag(TAGcodec).d("Muxer 启动完成");
                    reportSubject.onNext(new PushReport(
                            EventType.STARTED, 0,
                            "录制启动成功"));
                }
            });

            // 2. 提前创建 Surface
            inputSurface = videoEncoder.createInputSurface();
            videoEncoder.start(); // 启动编码器但不立即开始录制

            isRecording.set(true);

            // 3. 启动录制队列
            //startProcessingQueue();
        } catch (Exception e) {
            Timber.tag(TAGcodec).e(e, "录制初始化失败");
            cleanupResources();
            reportSubject.onNext(new PushReport(
                    EventType.ERROR, 0,
                    "录制初始化失败"));
            throw new RuntimeException("录制初始化失败");
        }
    }

    // 获取编码器参数的示例方法
    private AVCodecParameters getEncoderParameters() {
        AVCodecParameters params = AVCodecParameters.();
        params.codec_type(AVMEDIA_TYPE_VIDEO);
        params.codec_id(AV_CODEC_ID_H264);
        params.width(width);
        params.height(height);
        params.bit_rate(avgBitrate);
        return params;
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

                // 最后释放 Surface
                if (inputSurface != null) {
                    inputSurface.release();
                    inputSurface = null;
                }
            } catch (Exception e) {
                Timber.tag(TAGcodec).e(e, "资源清理异常");
            }
        }
    }

    public PublishSubject<PushReport> getReportSubject() {
        return reportSubject;
    }
}
