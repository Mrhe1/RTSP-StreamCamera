package com.example.supercamera.StreamPusher;

import static com.example.supercamera.MyException.ErrorLock.getLock;
import static com.example.supercamera.MyException.ErrorLock.releaseLock;
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
import static com.example.supercamera.StreamPusher.PushState.PushStateEnum.DESTROYED;
import static com.example.supercamera.StreamPusher.PushState.PushStateEnum.ERROR;
import static com.example.supercamera.StreamPusher.PushState.PushStateEnum.PUSHING;
import static com.example.supercamera.StreamPusher.PushState.PushStateEnum.READY;
import static com.example.supercamera.StreamPusher.PushState.PushStateEnum.RECONNECTING;
import static com.example.supercamera.StreamPusher.PushState.PushStateEnum.STARTING;
import static com.example.supercamera.StreamPusher.PushState.PushStateEnum.STOPPING;
import static com.example.supercamera.VideoStreamer.ErrorCode.ERROR_Stream_STOP;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;
import timber.log.Timber;

public class FFmpegPusherImpl implements StreamPusher {
    private final PushState state = new PushState();
    private final String TAG = "FFmpegPusher";
    private PushConfig mConfig;
    private AVCodecParameters params = avcodec_parameters_alloc();
    private final AtomicReference<PushListener> listenerRef = new AtomicReference<>();
    private final Lock FFmpegLock = new ReentrantLock();
    private final Object reconnectLock = new Object();
    // 外部调用锁
    private final Object publicLock = new Object();
    private final Object onErrorLock = new Object();
    private int startCount = 0;
    private AVFormatContext outputContext;
    private AVStream videoStream;
    private PushStatistics statistics;
    private final PublishSubject<TimeStamp> pushReportQueue = PublishSubject.create();
    private final ExecutorService reportExecutor = Executors.newSingleThreadExecutor();
    private Disposable reconnectDisposable;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    // throw IllegalArgumentException AND IllegalStateException
    @Override
    public void configure(PushConfig config) {
        synchronized (publicLock) {
            if (state.getState() != READY && state.getState() != CONFIGURED) {
                String msg = String.format("configure出错，IllegalState，目前状态：%s",
                        state.getState().toString());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_FFmpeg_CONFIG, msg);
            }

            if (!config.url.startsWith("rtsp://")) {
                Timber.tag(TAG).e("URL必须以rtsp://开头");
                throw throwException(ILLEGAL_ARGUMENT, ERROR_FFmpeg_CONFIG,
                        "URL必须以rtsp://开头");
            }

            if (config.BitrateKbps <= 0 || config.fps <= 0 ||
                    config.height <= 0 || config.width <= 0) {
                Timber.tag(TAG).e("config参数错误");
                throw throwException(ILLEGAL_ARGUMENT, ERROR_FFmpeg_CONFIG,
                        "config参数错误");
            }

            this.mConfig = config;

            // 设置AVCodecParameters
            this.params = setEncoderParameters(config, params);

            state.setState(CONFIGURED);
        }
    }

    @Override
    public void setTimeStampQueue(List<PublishSubject<TimeStamp>> timeStampQueue) {
        synchronized (publicLock) {
            mConfig.setTimeStampQueue(timeStampQueue);
        }
    }

    // throw IllegalStateException
    @Override
    public void start(byte[] header) {
        mConfig.setHeader(header);
        synchronized (publicLock) {
            if (state.getState() != CONFIGURED) {
                String msg = String.format("start出错，IllegalState，目前状态：%s",
                        state.getState().toString());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_FFmpeg_START, msg);
            }

            if(mConfig.header == null || mConfig.timeStampQueue == null) {
                Timber.tag(TAG).e("header或timeStampQueue为null，未设置");
                throw throwException(ILLEGAL_ARGUMENT, ERROR_FFmpeg_START,
                        "header或timeStampQueue为null，未设置");
            }

            state.setState(STARTING);

            Executors.newSingleThreadExecutor().submit(() -> {
                try {
                    startFFmpeg(mConfig);

                    if (!state.setState(PUSHING)){
                        throw new RuntimeException("pushing状态设置失败");
                    }

                    PushListener listener = listenerRef.get();
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
            if(state.getState() == CONFIGURED || state.getState() == READY) return;

            if (state.getState() != PUSHING &&
                    state.getState() != RECONNECTING) {
                String msg = String.format("stop出错，IllegalState，目前状态：%s",
                        state.getState().toString());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_FFmpeg_STOP, msg);
            }

            state.setState(STOPPING);

            try {
                if (statistics != null) {
                    // 停止统计
                    statistics.stopPushStatistics();
                }
                //销毁重连
                disposeReconnect();
                // 停止ffmpeg
                stopFFmpeg();

                state.setState(READY);
            } catch (Exception e) {
                Timber.tag(TAG).e(e, "停止FFmpeg出错");
            }
        }
    }

    @Override
    public void destroy() {
        synchronized (publicLock) {
            try {
                if (statistics != null) {
                    // 停止统计
                    statistics.stopPushStatistics();
                }
                //销毁重连
                disposeReconnect();
                // 停止ffmpeg
                forceStopFFmpeg();

                if (reportExecutor != null) {
                    reportExecutor.shutdownNow();
                }

                if (!compositeDisposable.isDisposed()) {
                    compositeDisposable.dispose();
                }

                state.setState(DESTROYED);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void pushFrame(ByteBuffer data, MediaCodec.BufferInfo bufferInfo, Long encodedTime) {
        synchronized (publicLock) {
            if (state.getState() != PUSHING
                    || (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                return;
            }
            if(!FFmpegLock.tryLock()) {
                Timber.tag(TAG).e("pushFrame失败，FFmpegLock锁被占用");
                return;
            }

            // 关键指针状态检查
            if (outputContext == null || outputContext.isNull() ||
                    videoStream == null || videoStream.isNull()) {
                Timber.tag(TAG).d("推流上下文未初始化，丢弃帧数据");
                FFmpegLock.unlock();
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
                //pkt.dts(av_rescale_q(bufferInfo.presentationTimeUs,
                        //srcTimeBase,
                        //videoStream.time_base()));
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
                FFmpegLock.unlock();
            }
        }
    }


    @Override
    public void setPushListener(PushListener listener) {
        synchronized (publicLock) {
            listenerRef.set(listener);
        }
    }

    private AVCodecParameters setEncoderParameters(PushConfig config,
                                                   AVCodecParameters params) {
        if (params == null || params.isNull()) {
            params = avcodec_parameters_alloc(); // 重新分配
        }
        try {
            params.codec_type(AVMEDIA_TYPE_VIDEO);
            if (config.codecID != -1) {
                params.codec_id(config.codecID);
            }
            params.width(config.width);
            params.height(config.height);
            params.bit_rate(config.BitrateKbps * 1000L);
            return params.retainReference(); // 保持引用计数
        } catch (Exception e) {
            avcodec_parameters_free(params); // 异常时释放内存
            throw e;
        }
    }


    // throw RuntimeException
    private void startFFmpeg(PushConfig config) {
        try {
            if(!FFmpegLock.tryLock(100,  TimeUnit.MILLISECONDS)) {
                Timber.tag(TAG).e("startFFmpeg失败，FFmpegLock锁被占用");
                throw throwException(ILLEGAL_STATE, ERROR_Stream_STOP,"锁被占用");
            }
        } catch (InterruptedException e) {
            Timber.tag(TAG).e("startFFmpeg失败，FFmpegLock锁被占用");
            throw throwException(ILLEGAL_STATE, ERROR_Stream_STOP,"锁被占用");
        }

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
            ret = avcodec_parameters_copy(dstParams, params);
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
            // set maximum timeout (in seconds) to wait for incoming connections (-1 is infinite, imply flag listen) (deprecated, use listen_timeout) (from INT_MIN to INT_MAX) (default -1)
            av_dict_set(options, "timeout", "2", 0); // 2秒超时
            // set timeout (in microseconds) of socket TCP I/O operations (from INT_MIN to INT_MAX) (default 0)
            av_dict_set(options, "stimeout", "1500000", 0); // 1.5秒超时
            // Timeout for IO operations (in microseconds) (from 0 to I64_MAX) (default 0)
            av_dict_set(options, "rw_timeout", "1500000", 0);
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
                    PushListener listener = listenerRef.get();
                    if (listener != null  && state.getState() == PUSHING) {
                        reportExecutor.submit(() -> listener.onStatistics(info));
                    }
                }

                @Override
                public void onNeedReconnect() {
                    Executors.newSingleThreadExecutor().submit(() -> {
                        if(state.getState() == PUSHING) {
                            reconnect(config.maxReconnectAttempts,
                                    config.reconnectPeriodSeconds);
                        }
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
            // 释放锁
            FFmpegLock.unlock();
        }
    }

    private void stopFFmpeg() {
        try {
            if(!FFmpegLock.tryLock(100,  TimeUnit.MILLISECONDS)) {
                Timber.tag(TAG).e("stopFFmpeg失败，FFmpegLock锁被占用");
                throw throwException(ILLEGAL_STATE, ERROR_Stream_STOP,"锁被占用");
            }
        } catch (InterruptedException e) {
            Timber.tag(TAG).e("stopFFmpeg失败，FFmpegLock锁被占用");
            throw throwException(ILLEGAL_STATE, ERROR_Stream_STOP,"锁被占用");
        }

        try {
            // 显式释放videoStream相关资源
            if (videoStream != null && !videoStream.isNull()) {
                videoStream.close();
            }

            if (outputContext != null && !outputContext.isNull()) {
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
            FFmpegLock.unlock();
        }
    }

    private void forceStopFFmpeg() {
        try{
            stopFFmpeg();
        } catch(MyException e){
            Timber.tag(TAG).w("强制停止ffmpeg");
            try {
                // 显式释放videoStream相关资源
                if (videoStream != null && !videoStream.isNull()) {
                    videoStream.close();
                }

                if (outputContext != null && !outputContext.isNull()) {
                    avio_closep(outputContext.pb());
                    avformat_free_context(outputContext);
                }
            } catch (Exception ex) {
                Timber.tag(TAG).e(ex, "释放FFmpeg资源异常");
            } finally {
                outputContext = null;
                videoStream = null;
            }
        }
    }

    private void restartPusher() {
        // 先停止
        try {
            stopFFmpeg();
        } catch (Exception ignored) {
            Timber.tag(TAG).w("停止ffmpeg失败");
        }

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
            if (!state.setState(RECONNECTING)) {
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
                                if (state.getState() != RECONNECTING) {
                                    disposeReconnect();
                                }

                                try {
                                    Timber.i("尝试第 %d 次重连", tick + 1);
                                    restartPusher();

                                    //如果成功
                                    state.setState(PUSHING);
                                    disposeReconnect();

                                    PushListener listener = listenerRef.get();
                                    if (listener != null) {
                                        reportExecutor.submit(() -> {
                                            listener.onReconnect(true, (int) (tick + 1));
                                        });
                                    }
                                } catch (RuntimeException e) {
                                    // 单次重连失败
                                    Timber.tag(TAG).e(e, "单次重连操作失败");
                                    PushListener listener = listenerRef.get();
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
        if (state.getState() == ERROR ||
                state.getState() == READY) return;

        state.setState(ERROR);

        Executors.newSingleThreadExecutor().submit(() -> {
            // 获取锁
            if(!getLock()) return;

            synchronized (onErrorLock) {
                switch (code) {
                    case ERROR_FFmpeg_START, ERROR_FFmpeg -> errorStop_TypeA();
                    case ERROR_FFmpeg_Reconnect, ERROR_FFmpeg_ReconnectFail -> errorStop_TypeB();
                }

                state.setState(READY);

                MyException e = new MyException(this.getClass().getPackageName(),
                        type, code, message);

                // 释放锁
                releaseLock();

                PushListener listener = listenerRef.get();
                if (listener != null) {
                    if (code == ERROR_FFmpeg_ReconnectFail ||
                            code == ERROR_FFmpeg_Reconnect) {
                        listener.onReconnectFail(e);
                    } else {
                        listener.onError(e);
                    }
                }
            }
        });
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

            state.setState(READY);
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

            state.setState(READY);
        } catch (Exception ignored) {}
    }
}