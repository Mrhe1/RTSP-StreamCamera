package com.example.supercamera;

import android.content.Context;
import com.alivc.live.pusher.AlivcLivePusher;
import com.alivc.live.pusher.AlivcLivePushConfig;
import com.alivc.live.pusher.AlivcLivePushErrorListener;
import com.alivc.live.pusher.AlivcLivePushInfoListener;
import com.alivc.live.pusher.AlivcLivePushNetworkListener;
import com.alivc.live.pusher.AlivcResolutionEnum;
import com.alivc.live.pusher.AlivcQualityModeEnum;
import com.alivc.live.pusher.AlivcFpsEnum;
import com.alivc.live.pusher.AlivcEncodeType;
import com.alivc.live.pusher.AlivcEncodeModeEnum;
import com.alivc.live.pusher.AlivcLivePushError;
import com.alivc.live.pusher.AlivcImageFormat;
import com.alivc.live.pusher.AlivcLivePushStatsInfo;
import com.alivc.live.pusher.AlivcVideoEncodeGopEnum;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.concurrent.TimeUnit;
import timber.log.Timber;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import java.util.concurrent.atomic.AtomicReference;

public class VideoPusher {
    private static final String TAG = "VideoPusher";
    private final AlivcLivePusher livePusher;
    private final AlivcLivePushConfig pushConfig;
    private final PublishSubject<byte[]> streamingQueue = PublishSubject.create();
    private final PublishSubject<PushReport> reportSubject = PublishSubject.create();
    private final int width;
    private final int height;
    private final int fps;
    private final int initAvgBitrate;
    private final int initMaxBitrate;
    private final int initMinBitrate;
    private final int stride;
    private final int rotation;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private long mStartTimeNs = System.nanoTime();
    //private String pushUrl;
    private Disposable reconnectDisposable;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private volatile boolean isReconnecting = false;
    private final Object reconnectLock = new Object(); // 同步锁对象
    private final AtomicReference<String> mPushUrlRef = new AtomicReference<>();//url使用原子引用

    // 事件类型定义
    public enum EventType {
        ERROR,
        NETWORK_POOR,
        NETWORK_RECOVERY,
        CUR_BITRATE,
        PUSH_STARTED,
        PUSH_STOPPED,
        RECONNECTION_ERROR,//重连错误
        //PACKETSLOST,
        PUSHER_DESTROY,//推流引擎已销毁
        NETWORK_DELAY,//网络往返延时（ms）:RTT
        URLCHANGE,//url鉴权过期,会进行重连,message为新url
        RECONNECTION_SUCCESS//重连成功
    }

    // 事件报告类
    public static class PushReport {
        public final EventType type;
        public final int code;
        public final String message;
        public final int avgBitrate;
        public final int maxBitrate;
        public final int minBitrate;

        public PushReport(EventType type, int code, String message, int avgBitrate, int maxBitrate, int minBitrate) {
            this.type = type;
            this.code = code;
            this.message = message;
            this.avgBitrate = avgBitrate;
            this.maxBitrate = maxBitrate;
            this.minBitrate = minBitrate;
        }
    }

    public VideoPusher(Context context, int width, int height, int fps,
                       int initAvgBitrate, int initMaxBitrate, int initMinBitrate) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.initAvgBitrate = initAvgBitrate;
        this.initMaxBitrate = initMaxBitrate;
        this.initMinBitrate = initMinBitrate;
        this.stride = width;// 计算 stride（YUV420 格式，stride=width）
        this.rotation = 0;

        // 初始化推流配置
        pushConfig = new AlivcLivePushConfig();
        if(width == 1920 && height == 1080)
        {
            pushConfig.setResolution(AlivcResolutionEnum.RESOLUTION_1080P);
        }
        else if(width == 1280 && height == 720)
        {
            pushConfig.setResolution(AlivcResolutionEnum.RESOLUTION_720P);
        }
        else if(width == 960 && height == 540)
        {
            pushConfig.setResolution(AlivcResolutionEnum.RESOLUTION_540P);
        }
        else
        {
            Timber.tag(TAG).e("推流分辨率错误");
            stopPush();
            reportSubject.onNext(new PushReport(
                    EventType.ERROR, 0,
                    "推流分辨率错误 " ,
                    0, 0, 0
            ));
            throw new RuntimeException("推流分辨率错误");
        }

        if (fps <= 0 || fps > 60) {
            Timber.tag(TAG).e("帧率超出范围");
            stopPush();
            reportSubject.onNext(new PushReport(
                    EventType.ERROR, 0,
                    "帧率超出范围 " ,
                    0, 0, 0
            ));
            throw new IllegalArgumentException("帧率超出范围");
        }
        pushConfig.setFps(AlivcFpsEnum.fromValue(fps));
        pushConfig.setVideoOnly	(true);
        pushConfig.setVideoEncodeType(AlivcEncodeType.Encode_TYPE_H264);
        pushConfig.setVideoEncodeGop(AlivcVideoEncodeGopEnum.fromValue(2));
        pushConfig.setQualityMode(AlivcQualityModeEnum.QM_CUSTOM);
        pushConfig.setTargetVideoBitrate(initMaxBitrate);
        pushConfig.setMinVideoBitrate(initMinBitrate);
        pushConfig.setInitialVideoBitrate(initAvgBitrate);
        // 打开码率自适应，默认为true
        pushConfig.setEnableBitrateControl(true);
        pushConfig.setVideoEncodeMode(AlivcEncodeModeEnum.Encode_MODE_HARD);
        pushConfig.setExternMainStream(true);
        pushConfig.setAlivcExternMainImageFormat(AlivcImageFormat.IMAGE_FORMAT_YUV420P);

        // 初始化推流 SDK
        try {
            livePusher = new AlivcLivePusher();
            livePusher.init(context, pushConfig);
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            Timber.tag(TAG).e(e, "推流初始化失败：%s", e.getMessage());
            stopPush();
            throw new RuntimeException("推流器初始化失败", e); // 抛出致命错误
        }
        // 设置网络监听
        livePusher.setLivePushNetworkListener(new AlivcLivePushNetworkListener() {
            @Override
            public void onNetworkPoor(AlivcLivePusher pusher) {
                Timber.tag(TAG).i("网络质量差");
                reportSubject.onNext(new PushReport(EventType.NETWORK_POOR, 0, "网络质量差", 0, 0, 0));
            }

            @Override
            public void onNetworkRecovery(AlivcLivePusher pusher) {
                Timber.tag(TAG).i("网络恢复");
                reportSubject.onNext(new PushReport(EventType.NETWORK_RECOVERY, 0, "网络恢复", 0, 0, 0));
            }

            @Override
            public void onReconnectFail(AlivcLivePusher pusher) {
                Timber.tag(TAG).e("重连失败，尝试重新连接");
                reportSubject.onNext(new PushReport(EventType.RECONNECTION_ERROR, 0, "重连失败", 0, 0, 0));
                handleReconnectError();
            }
            @Override
            public void onPacketsLost(AlivcLivePusher pusher) {
                Timber.tag(TAG).e("推流丢包");
                //reportSubject.onNext(new PushReport(EventType.PACKETSLOST, 0, "推流丢包", 0, 0, 0));
            }
            @Override
            public void onConnectFail(AlivcLivePusher pusher) {
                Timber.tag(TAG).e("推流器连接失败");
                reportSubject.onNext(new PushReport(EventType.ERROR, 0, "连接失败", 0, 0, 0));
                stopPush();
                throw new RuntimeException("推流器连接失败"); // 抛出致命错误
            }
            @Override public void onReconnectStart(AlivcLivePusher pusher) {
                Timber.tag(TAG).i("重连开始");
            }
            @Override public void onConnectionLost(AlivcLivePusher pusher) {
                Timber.tag(TAG).w("连接丢失");
            }
            @Override public void onReconnectSucceed(AlivcLivePusher pusher) {
                Timber.tag(TAG).i("重连成功");
            }
            @Override public void onSendDataTimeout(AlivcLivePusher pusher) {
                Timber.tag(TAG).i("数据发送超时");
            }
            @Override public String onPushURLAuthenticationOverdue(AlivcLivePusher pusher) {
                //url鉴权过期
                mPushUrlRef.set(livePusher.getPushUrl());
                Timber.tag(TAG).w("url鉴权过期,开始重连,新url：%s",mPushUrlRef.get());
                reportSubject.onNext(new PushReport(EventType.URLCHANGE, 0,
                        mPushUrlRef.get(),
                        0, 0, 0));
                try{
                    livePusher.stopPush();
                    livePusher.startPush(mPushUrlRef.get());
                } catch (IllegalStateException | IllegalArgumentException e) {
                    Timber.tag(TAG).e(e, "重连操作异常");
                    reportSubject.onNext(new PushReport(
                            EventType.RECONNECTION_ERROR, 0,
                            "重连异常: " + e.getMessage(),
                            0, 0, 0
                    ));
                    handleReconnectError();//处理连接失败
                }
                return null;
            }
            @Override public void onSendMessage(AlivcLivePusher pusher) {}
        });

        // 设置错误监听
        livePusher.setLivePushErrorListener(new AlivcLivePushErrorListener() {
            @Override
            public void onSystemError(AlivcLivePusher livePusher, AlivcLivePushError error) {
                if (error != null) {
                    int errorCode = error.getCode();
                    String errorMsg = error.getMsg();
                    Timber.tag(TAG).e("推流系统错误: %d %s", errorCode,errorMsg);
                    String errorMessage = String.format("推流错误: %d %s", errorCode, errorMsg);
                    reportSubject.onNext(new PushReport(EventType.ERROR, errorCode, errorMessage, 0, 0, 0));
                    stopPush();
                    throw new RuntimeException(errorMessage); // 抛出致命错误
                }
            }

            @Override
            public void onSDKError(AlivcLivePusher livePusher, AlivcLivePushError error) {
                if (error != null) {
                    int errorCode = error.getCode();
                    String errorMsg = error.getMsg();
                    Timber.tag(TAG).e("推流系统错误: %d %s", errorCode,errorMsg);
                    String errorMessage = String.format("推流错误: %d %s", errorCode, errorMsg);
                    reportSubject.onNext(new PushReport(EventType.ERROR, errorCode, errorMessage, 0, 0, 0));
                    stopPush();
                    throw new RuntimeException(errorMessage); // 抛出致命错误
                }
            }
        });

        livePusher.setLivePushInfoListener(new AlivcLivePushInfoListener() {
            @Override
            public void onPreviewStarted(AlivcLivePusher pusher) {
                // 预览开始回调
                Timber.tag(TAG).i("预览已开始");
            }

            @Override
            public void onPreviewStopped(AlivcLivePusher pusher) {
                // 预览结束回调
                Timber.tag(TAG).i("预览已停止");
            }

            @Override
            public void onPushStarted(AlivcLivePusher pusher) {
                // 推流开始回调
                Timber.tag(TAG).i("推流实际开始");
                disposeReconnect(); // 成功时停止重试
                reportSubject.onNext(new PushReport(
                        EventType.PUSH_STARTED,
                        0,
                        mPushUrlRef.get(),
                        pushConfig.getInitialVideoBitrate(),
                        pushConfig.getTargetVideoBitrate(),
                        pushConfig.getMinVideoBitrate()
                ));
            }

            @Override
            public void onPushPaused (AlivcLivePusher pusher)	 {
                // 推流暂停回调
                Timber.tag(TAG).i("推流已暂停");
                reportSubject.onNext(new PushReport(
                        EventType.PUSH_STOPPED, 0,
                        "推流已暂停",0,0,0
                ));
            }

            @Override
            public void onPushResumed (AlivcLivePusher pusher)	 {
                // 推流恢复回调
                disposeReconnect(); // 成功时停止重试
                Timber.tag(TAG).i("推流已恢复");
            }

            @Override
            public void onPushStopped (AlivcLivePusher pusher)	 {
                // 推流结束回调
                Timber.tag(TAG).i("推流已结束");
                reportSubject.onNext(new PushReport(
                        EventType.PUSH_STOPPED, 0,
                        "推流已结束",0,0,0
                ));
            }

            @Override
            public void onPushRestarted (AlivcLivePusher pusher)	 {
                // 推流重启回调
                disposeReconnect(); // 成功时停止重试
                Timber.tag(TAG).i("推流已重启");
            }

            @Override
            public void onFirstFramePreviewed(AlivcLivePusher pusher) {
                // 首帧渲染回调（渲染第一帧音视频流）
                Timber.tag(TAG).i("首帧已渲染");
            }

            @Override
            public void onFirstFramePushed(AlivcLivePusher pusher) {
                // 首帧发送回调（发送第一帧音视频流）
                Timber.tag(TAG).i("首帧已发送");
            }

            @Override
            public void onDropFrame(AlivcLivePusher pusher, int beforeCount, int afterCount) {
                // 丢帧回调
                Timber.tag(TAG).w("发生丢帧，丢帧前视频帧数量：%d，丢帧后视频帧数量：%d", beforeCount, afterCount);
            }

            @Override
            public void onAdjustBitrate(AlivcLivePusher pusher, int currentBitrate, int targetBitrate) {
                // 调整码率回调
                Timber.tag(TAG).i("码率调整：当前码率：%d，目标码率：%d", currentBitrate, targetBitrate);
            }

            @Override
            public void onAdjustFps(AlivcLivePusher pusher, int currentFps, int targetFps) {
                // 调整帧率回调
                Timber.tag(TAG).i("帧率调整：当前帧率：%d，目标帧率：%d", currentFps, targetFps);
            }

            @Override
            public void onPushStatistics(AlivcLivePusher pusher, AlivcLivePushStatsInfo statistics)	 {
                // 视频流推流状态变化回调callback every 2s
                //网络往返延时（ms）:RTT
                int duration = livePusher.getLivePushStatsInfo().rtt;
                int cur_bitrate = livePusher.getLivePushStatsInfo().videoUploadBitrate;
                //int ConsumingTimePerFrame = livePusher.getLivePushStatsInfo().videoRenderConsumingTimePerFrame;
                Timber.tag(TAG).i("rtt网络延迟：%d ms",duration);
                Timber.tag(TAG).i("视频上传码率：%d kbps",cur_bitrate);
                //Timber.i("每帧平均渲染时长：%d ms",ConsumingTimePerFrame);
                reportSubject.onNext(new PushReport(
                        EventType.CUR_BITRATE, 0,
                        "视频上传码率",cur_bitrate,0,0
                ));
                reportSubject.onNext(new PushReport(
                        EventType.NETWORK_DELAY, duration,
                        "rtt网络延迟",0,0,0
                ));
            }
        });


        // 启动视频帧处理队列
        startStreamingQueue();
    }

    private void startStreamingQueue() {
        Disposable disposable = streamingQueue
                .toFlowable(BackpressureStrategy.LATEST)
                .onBackpressureDrop(frame ->
                        Timber.tag(TAG).w("丢弃帧，当前队列压力过大")
                )
                // 使用计算线程池
                .observeOn(Schedulers.computation())
                .subscribe(frame -> {
                    if (livePusher != null && livePusher.isPushing()) {
                        // 校验视频帧格式（ YUV420 ）
                        int expectedSize = width * height * 3 / 2;
                        if (frame.length < expectedSize) {
                            Timber.tag(TAG).e("视频帧数据异常: 实际大小=%d，预期大小=%d", frame.length, expectedSize);
                            return;
                        }
                        long elapsedUs = (System.nanoTime() - mStartTimeNs) / 1000L;//使用相对时间戳
                        livePusher.inputStreamVideoData(
                                frame,    // data
                                width,    // width
                                height,   // height
                                stride,   // stride
                                frame.length, // size
                                elapsedUs, // 微秒
                                rotation  // rotation
                        );
                    }
                });
        compositeDisposable.add(disposable);
    }

    private void handleReconnectError() {
        synchronized (reconnectLock) {
            if (isReconnecting()) {
                Timber.tag(TAG).w("已有正在进行的重连任务");
                return;
            }

            Timber.tag(TAG).i("启动新重连任务");
            setReconnecting(true);

            // 清理旧任务
            if (reconnectDisposable != null && !reconnectDisposable.isDisposed()) {
                reconnectDisposable.dispose();
            }

            reconnectDisposable = Observable.interval(3, TimeUnit.SECONDS)
                    .take(MAX_RECONNECT_ATTEMPTS) // 限制次数
                    .subscribeOn(Schedulers.io())
                    .doOnDispose(() -> {
                        Timber.i("重连任务已终止");
                        setReconnecting(false); // 状态重置在dispose之后
                    })
                    .subscribe(
                            tick -> {
                                if (tick >= MAX_RECONNECT_ATTEMPTS - 1) {
                                    Timber.tag(TAG).e( "达到最大重试次数");
                                    disposeReconnect();
                                    stopPush();
                                    reportSubject.onNext(new PushReport(
                                            EventType.ERROR,
                                            0,
                                            "达到最大重试次数" ,
                                            0, 0, 0
                                    ));
                                    throw new RuntimeException("达到最大重试次数");
                                }
                                try {
                                    Timber.i("尝试第 %d 次重连", tick + 1);

                                    // 先检查是否已成功
                                    if (livePusher.isPushing()) {
                                        Timber.tag(TAG).i("检测到推流已恢复");
                                        disposeReconnect();
                                        return;
                                    }
                                } catch (IllegalStateException e) {  }
                                try {
                                        // 执行重连操作,先stop
                                        livePusher.stopPush();
                                    } catch (IllegalStateException | IllegalArgumentException e) {
                                    }
                                try{
                                    //再start
                                    livePusher.startPush(mPushUrlRef.get());
                                } catch (IllegalStateException e) {
                                    //状态不对，例如还未初始化/未startPreview
                                    Timber.tag(TAG).e(e, "重连操作异常");
                                    stopPush();
                                    reportSubject.onNext(new PushReport(
                                            EventType.ERROR,
                                            0,
                                            "重连异常: " + e.getMessage(),
                                            0, 0, 0
                                    ));
                                    throw new RuntimeException("重连操作异常",e);
                                }
                                catch(IllegalArgumentException e) {
                                    //url为空，或者不是有效的url格式
                                    Timber.tag(TAG).e(e, "重连操作异常,已重新获取push url");
                                    reportSubject.onNext(new PushReport(
                                            EventType.RECONNECTION_ERROR,
                                            0,
                                            "重连异常: " + e.getMessage(),
                                            0, 0, 0
                                    ));
                                    String oldurl = null;
                                    oldurl =mPushUrlRef.get();
                                    mPushUrlRef.set(livePusher.getPushUrl());//获取正确的url
                                    if(!oldurl.equals(mPushUrlRef.get()))
                                    {
                                        reportSubject.onNext(new PushReport(EventType.URLCHANGE, 0,
                                                mPushUrlRef.get(),
                                                0, 0, 0));
                                    }
                                }
                            },
                            throwable -> {
                                Timber.tag(TAG).e(throwable, "重连流发生致命错误");
                                disposeReconnect();
                                stopPush();
                                throw new RuntimeException("重连流发生致命错误");
                            }
                    );
            compositeDisposable.add(reconnectDisposable);
        }
    }

    public void startPush(String pushUrl) {
        if (livePusher != null) {//如果url为空则get url
            if (pushUrl == null)
            {
                pushUrl = livePusher.getPushUrl();
            }
            mPushUrlRef.set(pushUrl);
            //livePusher.startPreview(mSurfaceView);
            try {
                livePusher.startPush(pushUrl);
                Timber.tag(TAG).i("开始推流: %s", pushUrl);
            } catch (IllegalArgumentException e) {
                String errorMessage = String.format("url为空，或者不是有效的url格式:%s", e);
                Timber.tag(TAG).e(errorMessage);
                reportSubject.onNext(new PushReport(EventType.ERROR, 0, errorMessage, 0, 0, 0));
                stopPush();
                throw new RuntimeException("开始推流失败，url为空，或者不是有效的url格式：",e);
            } catch (IllegalStateException e) {
                stopPush();
                String errorMessage = String.format("开始推流失败，状态不对:%s", e);
                Timber.tag(TAG).e(errorMessage);
                reportSubject.onNext(new PushReport(EventType.ERROR, 0, errorMessage, 0, 0, 0));
                throw new RuntimeException("开始推流失败，状态不对：",e);
            }
        }
    }

    public void stopPush() {
        if (livePusher == null) return;
        disposeAll(); // 清理逻辑
        try {
            if (livePusher != null) {
                livePusher.stopPush();
                Thread.sleep(300);
                livePusher.destroy();
            }
        } catch (IllegalStateException | InterruptedException e) {
            Timber.tag(TAG).e(e, "停止推流时异常");
        }
        Timber.tag(TAG).i( "推流模块已销毁");
        reportSubject.onNext(new PushReport(
                EventType.PUSHER_DESTROY, 0,
                "推流模块已销毁", 0, 0, 0
        ));
    }

    // 统一的资源释放方法
    private void disposeReconnect() {
        synchronized (reconnectLock) {
            if (reconnectDisposable != null) {
                if (!reconnectDisposable.isDisposed()) {
                    reconnectDisposable.dispose();
                }
                reconnectDisposable = null; // 状态重置
            }
            setReconnecting(false); // 确保在dispose之后设置
            Timber.tag(TAG).d("重连状态已重置 isReconnecting=%b", isReconnecting);
            reportSubject.onNext(new PushReport(
                    EventType.RECONNECTION_SUCCESS, 0,
                    "推流重连成功" , 0, 0, 0
            ));
        }
    }

    private void disposeAll() {
        // 清理所有 RxJava 订阅
        compositeDisposable.clear();
        // 处理重连相关资源
        disposeReconnect();
        release();
        Timber.tag(TAG).i("所有订阅和重连资源已释放");
    }

    public void release() {
        streamingQueue.onComplete();
        reportSubject.onComplete();
        compositeDisposable.dispose();
    }

    public PublishSubject<byte[]> getStreamingQueue() {
        return streamingQueue;
    }

    public PublishSubject<PushReport> getReportSubject() {
        return reportSubject;
    }

    // 所有访问isReconnecting的地方加锁
    public boolean isReconnecting() {
        synchronized (reconnectLock) {
            return isReconnecting;
        }
    }
    private void setReconnecting(boolean reconnecting) {
        synchronized (reconnectLock) {
            isReconnecting = reconnecting;
        }
    }
}