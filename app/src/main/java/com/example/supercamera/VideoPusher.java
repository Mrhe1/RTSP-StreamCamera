package com.example.supercamera;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_free;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import androidx.annotation.NonNull;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;
import timber.log.Timber;

public class VideoPusher {
    private HandlerThread codecThread;
    private final ScheduledExecutorService errorHandler;
    private MediaCodec videoEncoder;
    private static final String TAGcodec = "StreamEncoder";
    private static final String TAG = "VideoPusher";
    private int width;
    private int height;
    private int fps;
    private int Bitrate;
    private FFmpegPusher pusher;
    private final Object dimensionLock = new Object();
    private Surface inputSurface; //Surface成员
    private long lastPresentationTimeUs = 0;
    private final ExecutorService encoderExecutor = Executors.newSingleThreadExecutor();
    private final PublishSubject<PushReport> reportSubject = PublishSubject.create();
    private AVCodecParameters params = avcodec_parameters_alloc();
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    public enum PushState {
        READY,
        PUSHING,
        ERROR,
        RECONNECTING,
        STARTING,
        STOPPING
    }

    public static final AtomicReference<PushState> currentState =
            new AtomicReference<>(PushState.READY);

    // 事件类型定义
    public enum EventType {
        ERROR,
        PUSH_STARTED,
        PUSH_STOPPED,
        Statistics,//统计回调
        RECONNECTION//重连相关
    }

    // 事件报告类
    public static class PushReport {
        public final EventType type;
        public final int code;
        public final String message;
        public final int BitrateNow;
        public final int rtt;
        public final double pushFailureRate;
        public final int totalLatency;

        public PushReport(EventType type, int code, String message,
                          int BitrateNow, int rtt, double pushFailureRate, int totalLatency) {
            this.type = type;
            this.code = code;
            this.message = message;
            this.BitrateNow = BitrateNow;
            this.rtt = rtt;
            this.pushFailureRate = pushFailureRate;
            this.totalLatency = totalLatency;
        }
    }

    // 处理工作状态转换
    public static boolean setState(PushState newState) {
        // 状态校验
        if (!isValidTransition(newState)) {
            Timber.tag(TAG).w("非法状态转换: %s → %s",
                    currentState.get(), newState);
            return false;
        }
        return currentState.compareAndSet(currentState.get(), newState);
    }

    private static boolean isValidTransition(PushState newState) {
        // 实现状态转换规则校验
        PushState current = currentState.get();
        return switch (current) {
            case READY -> newState == PushState.STARTING;
            case STARTING -> newState == PushState.PUSHING || newState == PushState.ERROR ||
                    newState == PushState.STOPPING;
            case PUSHING -> newState == PushState.ERROR || newState == PushState.STOPPING
                    || newState == PushState.RECONNECTING;
            case RECONNECTING -> newState == PushState.ERROR || newState == PushState.PUSHING
            || newState == PushState.STOPPING;
            case ERROR -> newState == PushState.STOPPING;
            case STOPPING -> newState == PushState.READY || newState == PushState.ERROR;
            default -> false;
        };
    }

    public VideoPusher(String url, int width, int height, int fps,
                       int Bitrate, ScheduledExecutorService errorHandler){
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.Bitrate = Bitrate;
        this.errorHandler = errorHandler; // 接收外部线程池

        pusher = new FFmpegPusher(url, width, height, fps, Bitrate);

        setState(PushState.READY);
    }

    public void startPush() {
        if(!setState(PushState.STARTING)) return;

        try {
            startStreamEncoder(width, height, fps, Bitrate);
            setupEventHandlers();
            Timber.d("推流启动成功");
        } catch (Exception e) {
            Timber.e(e, "推流启动失败");
            setState(PushState.ERROR);
        }
    }

    public void stopPush() {
        if(!setState(PushState.STOPPING)){
            Timber.tag(TAG).e("无需重复停止推流");
            return;
        }

        pusher.stopPush();
        stopEncoding();

        if(encoderExecutor != null) {
            // 关闭encoderExecutor线程池
            encoderExecutor.shutdown();
        }
        try {
            if (!encoderExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                encoderExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        //释放RxJava资源
        if (!compositeDisposable.isDisposed()) {
            compositeDisposable.dispose();
        }

        setState(PushState.READY);
    }

    //////////////////////////////////////////////////////////////////////////////////
                             //!%编码部分%！//
    private void startStreamEncoder(int width, int height, int fps, int bitrate) {
        try {//bitrate单位kbps
            this.width = width;
            this.height = height;

            // 创建带Looper的HandlerThread
            HandlerThread codecThread = new HandlerThread("VideoEncoder-Callback");
            codecThread.start();
            Handler codecHandler = new Handler(codecThread.getLooper());


            // 1. 提前初始化编码器（H.264）
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            );
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate * 1000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            format.setInteger("max-bitrate", (int)(Bitrate * 1.5 * 1000));
            format.setInteger("video-bitrate-range", Bitrate*1000);

            // MTK芯片需要特殊参数
            format.setInteger(MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            //format.setInteger("vendor.mediatek.feature.tile-encoding", 1); // 启用Tile编码
            //format.setInteger("vendor.mediatek.videoenc.tile-dimension-columns", 4);
            //format.setInteger("vendor.mediatek.videoenc.tile-dimension-rows", 2);

            // 替换颜色格式
            //format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    //MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            // 添加数据空间定义
            //format.setInteger(MediaFormat.KEY_COLOR_STANDARD,
                    //MediaFormat.COLOR_STANDARD_BT709);
            //format.setInteger(MediaFormat.KEY_COLOR_RANGE,
                    //MediaFormat.COLOR_RANGE_LIMITED);
            //format.setInteger(MediaFormat.KEY_COLOR_TRANSFER,
                    //MediaFormat.COLOR_TRANSFER_SDR_VIDEO);


            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            //异步回调
            videoEncoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec mc, int inputBufferId) {
                    // Surface模式无需处理输入缓冲区
                }

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec mc, int outputBufferId,
                                                    @NonNull MediaCodec.BufferInfo bufferInfo) {
                    synchronized (dimensionLock) {
                        if (currentState.get() != PushState.PUSHING &&
                        currentState.get() != PushState.STARTING) return;

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
                            try {
                                AVCodecParameters prt = getEncoderParameters();
                                // 初始化推流器（确保只执行一次）
                                if (currentState.get() == PushState.STARTING) {
                                    pusher.initPusher(configData, prt);
                                }

                                setState(PushState.PUSHING);
                                reportSubject.onNext(new PushReport(
                                        EventType.PUSH_STARTED,
                                        0,"推流开始成功", 0,
                                        0,0 ,0));
                            } catch (Exception e) {
                                String msg = String.format("ffmpeg初始化失败:%s",e.getMessage());
                                Timber.tag(TAG).e(msg);
                                setState(PushState.ERROR);

                                // 在单独线程中出处理错误
                                ScheduledFuture<?> future = errorHandler.schedule(() -> {
                                    stopPush();
                                    reportError(0,msg);
                                }, 100, TimeUnit.MILLISECONDS);
                            }

                            if(currentState.get() == PushState.STARTING) setState(PushState.PUSHING);
                            mc.releaseOutputBuffer(outputBufferId, false);
                            return;
                        }

                        try {
                            // 记录编码时间戳（纳秒）
                            FFmpegPusher.PushStatistics.reportTimestamp(FFmpegPusher.PushStatistics.TimeStampStyle.Encoded
                            , bufferInfo.presentationTimeUs * 1000L);
                            // 时间戳修正
                            if (bufferInfo.presentationTimeUs <= lastPresentationTimeUs) {
                                bufferInfo.presentationTimeUs = lastPresentationTimeUs + 1000000 / fps;
                            }
                            lastPresentationTimeUs = bufferInfo.presentationTimeUs;

                            encoderExecutor.submit(() ->
                                    pusher.pushFrame(outputBuffer, bufferInfo));
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

                }
            }, codecHandler);

            // 2. 提前创建 Surface
            inputSurface = videoEncoder.createInputSurface();
            Timber.tag(TAGcodec).d("编码器 Surface 已准备");
            videoEncoder.start(); // 启动编码器但不立即开始录制

        } catch (Exception e) {
            Timber.tag(TAGcodec).e(e, "推流编码器初始化失败");
            cleanupResources();
            throw new RuntimeException("推流编码器初始化失败" + e.getMessage());
        }
    }

    // 获取编码器参数
    private AVCodecParameters getEncoderParameters() {
        if (params == null || params.isNull()) {
            throw new OutOfMemoryError("无法分配 AVCodecParameters 内存");
        }
        try {
            params.codec_type(AVMEDIA_TYPE_VIDEO);
            params.codec_id(AV_CODEC_ID_H264);
            params.width(width);
            params.height(height);
            params.bit_rate(Bitrate);
            return params.retainReference(); // 保持引用计数
        } catch (Exception e) {
            avcodec_parameters_free(params); // 异常时释放内存
            throw e;
        }
    }

    // 检查 Surface 有效性
    public boolean isSurfaceValid() {
        return inputSurface != null && inputSurface.isValid();
    }

    //Surface的方法
    public Surface getInputSurface() {
        if (currentState.get() != PushState.STARTING &&
        currentState.get() != PushState.PUSHING) {
            throw new IllegalStateException("推流未开始");
        }
        return inputSurface;
    }

    public void stopEncoding() {
        synchronized (dimensionLock) {
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
                Timber.tag(TAG).e("停止编码器出错: %s", e.getMessage());
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
                if (params != null && !params.isNull()) {
                    avcodec_parameters_free(params); // 释放参数内存
                    params = null;
                }
                if (codecThread != null) {
                    codecThread.quitSafely();
                }
            }
        }
    }

    // 只在startpush出错时被调用
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

    //////////////////////////////////////////////////////////////////////////////////
                            //!%消息队列%！//

    private void reportError(int code, String msg)
    {
        reportSubject.onNext(new PushReport(
                EventType.ERROR, code,
                msg,0,0, 0, 0));
    }

    public PublishSubject<PushReport> getReportSubject() {
        return reportSubject;
    }

    private void setupEventHandlers() {
        Disposable disposable = pusher.getReportSubject()
                .observeOn(Schedulers.io())
                .subscribe(report -> {
                    reportSubject.onNext(new PushReport(report.type,report.code
                    ,"FFmpegPusher:" + report.message, report.BitrateNow,
                            report.rtt, report.pushFailureRate, report.totalLatency));
                });
        compositeDisposable.add(disposable);
    }
}
