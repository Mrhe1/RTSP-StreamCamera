package com.example.supercamera.StreamPusher;

import static com.example.supercamera.MyException.MyException.ILLEGAL_ARGUMENT;
import static com.example.supercamera.MyException.MyException.ILLEGAL_STATE;
import static com.example.supercamera.MyException.MyException.RUNTIME_ERROR;
import static com.example.supercamera.StreamPusher.ErrorCode.ERROR_FFmpeg;
import static com.example.supercamera.StreamPusher.ErrorCode.ERROR_FFmpeg_CONFIG;
import static com.example.supercamera.StreamPusher.ErrorCode.ERROR_FFmpeg_Reconnect;
import static com.example.supercamera.StreamPusher.ErrorCode.ERROR_FFmpeg_ReconnectFail;
import static com.example.supercamera.StreamPusher.ErrorCode.ERROR_FFmpeg_START;
import static com.example.supercamera.StreamPusher.ErrorCode.ERROR_FFmpeg_STOP;
import static com.example.supercamera.StreamPusher.PushState.PushStateEnum.CONFIGURED;
import static com.example.supercamera.StreamPusher.PushState.PushStateEnum.ERROR;
import static com.example.supercamera.StreamPusher.PushState.PushStateEnum.PUSHING;
import static com.example.supercamera.StreamPusher.PushState.PushStateEnum.READY;
import static com.example.supercamera.StreamPusher.PushState.PushStateEnum.RECONNECTING;
import static com.example.supercamera.StreamPusher.PushState.PushStateEnum.STARTING;
import static com.example.supercamera.StreamPusher.PushState.PushStateEnum.STOPPING;
import static org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY;
import static org.bytedeco.ffmpeg.global.avcodec.av_new_packet;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_free;
import static org.bytedeco.ffmpeg.global.avformat.AVFMT_NOFILE;
import static org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE;
import static org.bytedeco.ffmpeg.global.avformat.av_guess_format;
import static org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame;
import static org.bytedeco.ffmpeg.global.avformat.av_write_trailer;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2;
import static org.bytedeco.ffmpeg.global.avformat.avformat_free_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_network_init;
import static org.bytedeco.ffmpeg.global.avformat.avformat_new_stream;
import static org.bytedeco.ffmpeg.global.avformat.avformat_write_header;
import static org.bytedeco.ffmpeg.global.avformat.avio_alloc_context;
import static org.bytedeco.ffmpeg.global.avformat.avio_closep;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_free;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;
import static org.bytedeco.ffmpeg.global.avutil.av_malloc;
import static org.bytedeco.ffmpeg.global.avutil.av_rescale_q;
import static org.bytedeco.ffmpeg.global.avutil.av_strerror;

import android.media.MediaCodec;

import com.example.supercamera.MyException.MyException;
import com.example.supercamera.StreamPusher.PushStats.PushStatistics;
import com.example.supercamera.StreamPusher.PushStats.PushStatsInfo;
import com.example.supercamera.StreamPusher.PushStats.PushStatsListener;
import com.example.supercamera.StreamPusher.PushStats.TimeStamp;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVOutputFormat;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;
import timber.log.Timber;

public class FFmpegPusherImpl implements StreamPusher {
    private final String TAG = "FFmpegPusher";
    private PushConfig mConfig;
    private AVCodecParameters params = avcodec_parameters_alloc();
    private PushListener listener;
    private final Object FFmpegLock = new Object();
    private final Object reconnectLock = new Object();
    // 外部调用锁
    private final Object publicLock = new Object();
    private final Object onErrorLock = new Object();
    private int startCount = 0;
    private AVFormatContext outputContext;
    private AVStream videoStream;
    private AVCodecParameters srcParams;
    private PushStatistics statistics;
    private PublishSubject<TimeStamp> pushReportQueue = PublishSubject.create();
    private ExecutorService reportExecutor = Executors.newSingleThreadExecutor();
    private Disposable reconnectDisposable;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    // throw IllegalArgumentException AND IllegalStateException
    @Override
    public void configure(PushConfig config) {
        synchronized (publicLock) {
            if (PushState.getState() != READY) {
                String msg = String.format("configure出错，IllegalState，目前状态：%s",
                        PushState.getState().toString());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_FFmpeg_CONFIG, msg);
            }

            if (config.url.startsWith("rtsp://")) {
                Timber.tag(TAG).e("URL必须以rtsp://开头");
                throw throwException(ILLEGAL_ARGUMENT, ERROR_FFmpeg_CONFIG,
                        "URL必须以rtsp://开头");
            }

            if (config.header == null || config.Bitrate <= 0 ||
                    config.fps <= 0 || config.height <= 0 || config.width <= 0) {
                Timber.tag(TAG).e("config参数错误");
                throw throwException(ILLEGAL_ARGUMENT, ERROR_FFmpeg_CONFIG,
                        "config参数错误");
            }

            this.mConfig = config;

            // 设置AVCodecParameters
            this.params = setEncoderParameters(config, params);

            PushState.setState(CONFIGURED);
        }
    }

    // throw IllegalStateException
    @Override
    public void start() {
        synchronized (publicLock) {
            if (PushState.getState() != CONFIGURED) {
                String msg = String.format("start出错，IllegalState，目前状态：%s",
                        PushState.getState().toString());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_FFmpeg_START, msg);
            }

            PushState.setState(STARTING);

            Executors.newSingleThreadExecutor().submit(() -> {
                try {
                    startFFmpeg(mConfig);

                    PushState.setState(PUSHING);
                    if (listener != null) {
                        reportExecutor.submit(() -> listener.onStart());
                    }
                } catch (Exception e) {
                    String msg = String.format("初始化ffmpeg失败:%s", e.getMessage());
                    Timber.tag(TAG).e(e.getMessage(), "初始化ffmpeg失败");
                    notifyError(RUNTIME_ERROR, ERROR_FFmpeg_START, msg);
                }
            });
        }
    }

    // throw IllegalStateException
    @Override
    public void stop() {
        synchronized (publicLock) {
            if (PushState.getState() != PUSHING &&
                    PushState.getState() != RECONNECTING) {
                String msg = String.format("stop出错，IllegalState，目前状态：%s",
                        PushState.getState().toString());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_FFmpeg_STOP, msg);
            }

            PushState.setState(STOPPING);

            try {
                if (statistics != null) {
                    // 停止统计
                    statistics.stopPushStatistics();
                }
                //销毁重连
                disposeReconnect();
                // 停止ffmpeg
                stopFFmpeg();

                if (reportExecutor != null) {
                    reportExecutor.shutdown();
                }

                PushState.setState(READY);
            } catch (Exception e) {
                Timber.tag(TAG).e(e, "停止FFmpeg出错");
                notifyError(RUNTIME_ERROR, ERROR_FFmpeg_STOP, e.getMessage());
            }
        }
    }

    @Override
    public void pushFrame(ByteBuffer data, MediaCodec.BufferInfo bufferInfo, Long encodedTime) {
        synchronized (publicLock) {
            synchronized (FFmpegLock) {
                if (PushState.getState() != PushState.PushStateEnum.PUSHING
                        || (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    return;
                }

                // 关键指针状态检查
                if (outputContext == null || outputContext.isNull() ||
                        videoStream == null || videoStream.isNull()) {
                    Timber.tag(TAG).d("推流上下文未初始化，丢弃帧数据");
                    return;
                }

                boolean isSuccess = true;
                AVPacket pkt = acquirePacket();
                try {
                    // 验证bufferInfo参数有效性
                    if (bufferInfo.offset < 0 || bufferInfo.size <= 0
                            || (bufferInfo.offset + bufferInfo.size) > data.capacity()) {
                        Timber.tag(TAG).e("无效的BufferInfo参数: offset=%d, size=%d, capacity=%d",
                                bufferInfo.offset, bufferInfo.size, data.capacity());
                        return;
                    }

                    int ret = av_new_packet(pkt, bufferInfo.size);
                    if (ret < 0) {
                        Timber.tag(TAG).e("帧写入分配内存失败: %s", av_err2str(ret));
                        return;
                    }

                    if (pkt == null || data == null || bufferInfo == null) {
                        Timber.tag(TAG).w("Invalid null object detected");
                        return;
                    }

                    try {
                        BytePointer dstPtr = pkt.data();
                        if (dstPtr == null) {
                            Timber.tag(TAG).w("Packet data is null");
                            return;
                        }

                        // 创建临时局部变量避免竞态条件
                        final int targetOffset = bufferInfo.offset;
                        final int targetSize = bufferInfo.size;
                        final int bufferCapacity = data.capacity();

                        // 验证偏移量和大小有效性
                        if (targetOffset < 0 || targetSize <= 0 || (targetOffset + targetSize) > bufferCapacity) {
                            Timber.tag(TAG).e("Invalid buffer range: offset=%d size=%d capacity=%d",
                                    targetOffset, targetSize, bufferCapacity);
                            return;
                        }

                        BytePointer srcPtr = new BytePointer(data);
                        if (srcPtr == null) {
                            Timber.tag(TAG).w("Failed to create source pointer");
                            return;
                        }

                        synchronized (data) {
                            int oldPosition = data.position();
                            int oldLimit = data.limit();
                            try {
                                data.position(targetOffset);
                                data.limit(targetOffset + targetSize);
                                srcPtr.position(targetOffset).limit(targetOffset + targetSize);

                                if (dstPtr == null || srcPtr == null ||
                                        dstPtr.address() == 0 || srcPtr.address() == 0) {
                                    Timber.tag(TAG).w("Invalid pointer detected");
                                    return;
                                }

                                dstPtr.put(srcPtr);
                                dstPtr.get();
                            } finally {
                                data.position(oldPosition);
                                data.limit(oldLimit);
                            }
                        }
                    } catch (Exception e) {
                        Timber.tag(TAG).e(e, "Unexpected error during buffer copy");
                    }

                    // 时间基计算(使用微秒时间基)
                    AVRational srcTimeBase = new AVRational().num(1).den(1000000);
                    pkt.pts(av_rescale_q(bufferInfo.presentationTimeUs,
                            srcTimeBase,
                            videoStream.time_base()));
                    pkt.dts(AV_NOPTS_VALUE); // 让FFmpeg自动计算
                    pkt.stream_index(videoStream.index());
                    int flags = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ? AV_PKT_FLAG_KEY : 0;
                    pkt.flags(flags);
                    ret = av_interleaved_write_frame(outputContext, pkt);

                    // 记录发送时间戳（纳秒）
                    pushReportQueue.onNext(new TimeStamp(TimeStamp.TimeStampStyle.Pushed,
                            System.nanoTime() - encodedTime));

                    if (ret < 0) {
                        isSuccess = false;
                        Timber.tag(TAG).e("帧写入失败: %d", ret);
                    }
                } catch (Exception ignored) {
                } finally {
                    statistics.setPushStatistics(isSuccess, bufferInfo.size);
                    if (pkt != null && !pkt.isNull()) {
                        releasePacket(pkt);
                    }
                }
            }
        }
    }


    @Override
    public void setStreamListener(PushListener listener) {
        synchronized (publicLock) {
            this.listener = listener;
        }
    }

    private AVCodecParameters setEncoderParameters(PushConfig config,
                                                   AVCodecParameters params) {
        if (params == null || params.isNull()) {
            params = avcodec_parameters_alloc(); // 重新分配
        }
        try {
            params.codec_type(AVMEDIA_TYPE_VIDEO);
            params.codec_id(config.codecID);
            params.width(config.width);
            params.height(config.height);
            params.bit_rate(config.Bitrate);
            return params.retainReference(); // 保持引用计数
        } catch (Exception e) {
            avcodec_parameters_free(params); // 异常时释放内存
            throw e;
        }
    }


    // throw RuntimeException
    private void startFFmpeg(PushConfig config) {
        synchronized (FFmpegLock) {
            AVFormatContext outputContext = null;
            AVDictionary options = null;
            try {

                // 1. 初始化网络（只进行一次）
                if (startCount == 0) {
                    avformat_network_init();
                }
                startCount++;

                // 2. 使用alloc_output_context2自动创建上下文
                AVOutputFormat outputFormat = av_guess_format("rtsp", null, null);
                if (outputFormat == null) {
                    throw new RuntimeException("FFFmpeg未编译RTSP支持");
                }

                PointerPointer<AVFormatContext> ctxPtr = new PointerPointer<>(1);

                int ret = avformat_alloc_output_context2(
                        ctxPtr,          // 双指针容器
                        outputFormat,
                        new BytePointer("rtsp"),
                        new BytePointer(config.url)
                );

                if (ret < 0 || ctxPtr.get() == null) {
                    throw new RuntimeException("上下文分配失败: " + av_err2str(ret));
                }
                outputContext = new AVFormatContext(ctxPtr.get());

                // 检查pb是否已初始化
                if (outputContext.pb() == null || outputContext.pb().isNull()) {
                    // 需要手动初始化AVIOContext
                    outputContext.pb(avio_alloc_context(
                            new BytePointer(av_malloc(4096)),
                            4096,
                            AVIO_FLAG_WRITE,
                            null, null, null, null
                    ));
                }

                // 3. 创建视频流
                videoStream = avformat_new_stream(outputContext, null);
                if (videoStream == null || videoStream.isNull()) {
                    throw new RuntimeException("无法创建视频流");
                }

                // 参数复制
                AVCodecParameters dstParams = videoStream.codecpar();
                ret = avcodec_parameters_copy(dstParams, srcParams);
                if (ret < 0) {
                    throw new RuntimeException("参数复制失败: " + av_err2str(ret));
                }

                // 设置关键参数
                dstParams.codec_tag(0);
                BytePointer extradata = new BytePointer(av_malloc(config.header.length));
                extradata.put(config.header);
                dstParams.extradata(extradata);
                dstParams.extradata_size(config.header.length);

                // 时间基配置
                videoStream.time_base().num(1);
                videoStream.time_base().den(config.fps);

                // 4. 设置选项
                options = new AVDictionary();
                av_dict_set(options, "rtsp_transport", "tcp", 0);
                av_dict_set(options, "max_delay", "200000", 0);
                av_dict_set(options, "tune", "zerolatency", 0);
                av_dict_set(options, "timeout", "3000000", 0); // 3秒超时\
                av_dict_set(options, "f", "rtsp", 0);

                // 6. 写入头部
                ret = avformat_write_header(outputContext, options);
                if (ret < 0) {
                    throw new RuntimeException("写头失败: " + av_err2str(ret));
                }

                this.outputContext = outputContext;

                List<PublishSubject<TimeStamp>> totalQueue = config.timeStampQueue;
                totalQueue.add(pushReportQueue);

                // 7. 开始统计帧数据
                statistics = new PushStatistics(config.statsIntervalSeconds,
                        config.pingIntervalSeconds, config.url,
                        config.pushFailureRateSet, totalQueue);

                statistics.setPushStatListener(new PushStatsListener() {
                    @Override
                    public void onStatistics(PushStatsInfo info) {
                        if (listener != null) {
                            reportExecutor.submit(() -> listener.onStatistics(info));
                        }
                    }

                    @Override
                    public void onNeedReconnect() {
                        Executors.newSingleThreadExecutor().submit(() -> {
                            reconnect(config.maxReconnectAttempts,
                                    config.reconnectPeriodSeconds);
                        });
                    }
                });

                statistics.startPushStatistics();
            } catch (Exception e) {
                // 错误处理
                if (outputContext != null) {
                    avformat_free_context(outputContext);
                    outputContext = null;
                }
                videoStream = null;
                throw new RuntimeException(e);
            } finally {
                if (options != null) av_dict_free(options);
            }
        }
    }

    private void stopFFmpeg() {
        synchronized (FFmpegLock) {
            try {
                if (outputContext != null && !outputContext.isNull()) {
                    // 显式释放videoStream相关资源
                    if (videoStream != null && !videoStream.isNull()) {
                        videoStream.close();
                    }

                    // 必须检查是否已写入头
                    if (!((outputContext.oformat().flags() & AVFMT_NOFILE) == 0)
                    ) {
                        av_write_trailer(outputContext); // 仅在成功初始化后调用
                        avio_closep(outputContext.pb());
                    }
                    avformat_free_context(outputContext);
                }
            } catch (Exception e) {
                Timber.tag(TAG).e(e, "释放FFmpeg资源异常");
            } finally {
                outputContext = null;
                videoStream = null;
            }
        }
    }

    private void restartPusher() {
        // 先停止
        stopFFmpeg();
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }

        //再开始
        try {
            startFFmpeg(mConfig);
        } catch (Exception e) {
            Timber.tag(TAG).e(e.getMessage(), "ffmpeg重新启动失败");
            throw new RuntimeException("ffmpeg重新启动失败" + e.getMessage());
        }
    }

    private void reconnect(int MAX_RECONNECT_ATTEMPTS, int periodSeconds) {
        synchronized (reconnectLock) {
            if (!PushState.setState(RECONNECTING)) {
                Timber.tag(TAG).w("已有正在进行的重连任务");
                return;
            }

            Timber.tag(TAG).i("启动新重连任务");

            // 清理旧任务
            if (reconnectDisposable != null && !reconnectDisposable.isDisposed()) {
                reconnectDisposable.dispose();
            }

            // 先停止推流
            stopFFmpeg();

            // 延迟确保资源释放
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }

            reconnectDisposable = Observable.interval(periodSeconds, TimeUnit.SECONDS)
                    .take(MAX_RECONNECT_ATTEMPTS) // 限制次数
                    .subscribeOn(Schedulers.io())
                    .subscribe(
                            tick -> {
                                if (tick >= MAX_RECONNECT_ATTEMPTS - 1) {
                                    Timber.tag(TAG).e("达到最大重连次数");
                                    disposeReconnect();
                                    stop();

                                    notifyError(RUNTIME_ERROR, ERROR_FFmpeg_ReconnectFail,
                                            "达到最大重连次数");
                                }
                                if (PushState.getState() != RECONNECTING) {
                                    disposeReconnect();
                                }

                                try {
                                    Timber.i("尝试第 %d 次重连", tick + 1);
                                    restartPusher();

                                    //如果成功
                                    PushState.setState(PUSHING);
                                    disposeReconnect();

                                    if (listener != null) {
                                        reportExecutor.submit(() -> {
                                            listener.onReconnect(true, (int) (tick + 1));
                                        });
                                    }
                                } catch (RuntimeException e) {
                                    // 单次重连失败
                                    Timber.tag(TAG).e(e, "单次重连操作失败");
                                    reportExecutor.submit(() -> {
                                        listener.onReconnect(false, (int)(tick + 1));
                                    });
                                }
                            },
                            throwable -> {
                                Timber.tag(TAG).e(throwable, "重连流发生致命错误");
                                disposeReconnect();
                                stop();

                                notifyError(RUNTIME_ERROR,
                                        ERROR_FFmpeg_Reconnect,"重连流发生致命错误");
                            }
                    );
            compositeDisposable.add(reconnectDisposable);
        }
    }

    private void disposeReconnect() {
        synchronized (reconnectLock) {
            if (reconnectDisposable != null) {
                if (!reconnectDisposable.isDisposed()) {
                    reconnectDisposable.dispose();
                }
                compositeDisposable.delete(reconnectDisposable); // 从组合订阅中移除
                reconnectDisposable = null;
            }
        }
    }

    private AVPacket acquirePacket() {
        return av_packet_alloc();
    }

    private void releasePacket(AVPacket pkt) {
        av_packet_unref(pkt);
        av_packet_free(pkt);
    }

    // 获取错误码对应内容
    private static String av_err2str(int errcode) {
        try (BytePointer buffer = new BytePointer(128)) {
            int ret = av_strerror(errcode, buffer, buffer.capacity());
            return (ret < 0) ? "Unknown error" : buffer.getString();
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
        synchronized (onErrorLock) {
            if (PushState.getState() == ERROR &&
                    PushState.getState() == READY) return;

            PushState.setState(ERROR);

            switch (code) {
                case ERROR_FFmpeg_START, ERROR_FFmpeg -> errorStop_TypeA();
                case ERROR_FFmpeg_Reconnect, ERROR_FFmpeg_ReconnectFail
                        -> errorStop_TypeB();
            }

            PushState.setState(READY);

            MyException e = new MyException(this.getClass().getPackageName(),
                    type, code, message);

            if(listener != null) {
                if(code == ERROR_FFmpeg_ReconnectFail ||
                code == ERROR_FFmpeg_Reconnect) {
                    listener.onReconnectFail(e);
                }else {
                    listener.onError(e);
                }
            }
        }
    }

    private void errorStop_TypeA() {
        try {
            if (statistics != null) {
                // 停止统计
                statistics.stopPushStatistics();
            }
            //销毁重连
            disposeReconnect();
            // 停止ffmpeg
            stopFFmpeg();

            if (reportExecutor != null) {
                reportExecutor.shutdown();
            }

            PushState.setState(READY);
        } catch (Exception ignored) {}
    }

    private void errorStop_TypeB() {
        try {
            if (statistics != null) {
                // 停止统计
                statistics.stopPushStatistics();
            }
            // 停止ffmpeg
            stopFFmpeg();

            if (reportExecutor != null) {
                reportExecutor.shutdown();
            }

            PushState.setState(READY);
        } catch (Exception ignored) {}
    }
}