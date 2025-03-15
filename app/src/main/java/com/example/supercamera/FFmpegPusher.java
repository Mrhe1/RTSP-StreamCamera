package com.example.supercamera;

import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.javacpp.BytePointer;

import static org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy;
import static org.bytedeco.ffmpeg.global.avformat.AVFMT_NOFILE;
import static org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE;
import static org.bytedeco.ffmpeg.global.avformat.av_guess_format;
import static org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame;
import static org.bytedeco.ffmpeg.global.avformat.av_write_trailer;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_free_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_network_init;
import static org.bytedeco.ffmpeg.global.avformat.avformat_new_stream;
import static org.bytedeco.ffmpeg.global.avformat.avformat_write_header;
import static org.bytedeco.ffmpeg.global.avformat.avio_closep;
import static org.bytedeco.ffmpeg.global.avformat.avio_open;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;
import static org.bytedeco.ffmpeg.global.avutil.av_rescale_q;
import static org.bytedeco.ffmpeg.global.avutil.av_strerror;

import android.media.MediaCodec;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;
import timber.log.Timber;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.util.concurrent.AtomicDouble;
import com.stealthcopter.networktools.Ping;
import com.stealthcopter.networktools.ping.PingStats;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FFmpegPusher {
    private static final String TAG = "FFmpegPusher";
    private AVFormatContext outputContext;
    private AVStream videoStream;
    private final AtomicBoolean isPushing = new AtomicBoolean(false);
    private final int width;
    private final int height;
    private final int fps;
    private final int Bitrate;
    private final String url;
    private byte[] header;
    private AVCodecParameters srcParams;
    private final Object initStopLock = new Object();
    private final Object reconnectLock = new Object();
    private Disposable reconnectDisposable;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private PushStatistics statistics;
    private final PublishSubject<VideoPusher.PushReport> reportSubject = PublishSubject.create();

    public class PushStatistics{
        List<FrameInfo> frameList = Collections.synchronizedList(new ArrayList<>());
        private AtomicInteger rtt = new AtomicInteger(0);
        private AtomicDouble curPushFailureRate = new AtomicDouble(0);
        private AtomicDouble pushFailureRate = new AtomicDouble(0);
        private AtomicBoolean ifNeedReconnect = new AtomicBoolean(false);
        private PingHelper pingHelper;
        private ScheduledExecutorService executor;

        public final class FrameInfo {
            private final boolean isPushFrameSuccess;
            private final int frameSize;
            //private int rtt;
            public FrameInfo(boolean isPushFrameSuccess, int frameSize) {
                this.isPushFrameSuccess = isPushFrameSuccess;
                this.frameSize = frameSize;
                //this.rtt = rtt;
            }

            public boolean getIfPushFrameSuccess() {
                return isPushFrameSuccess;
            }

            public int getFrameSize() {
                return frameSize;
            }

            //public int getRtt() {
                //return rtt;
            //}
        }

        public class PingHelper {
            private ScheduledExecutorService pingExecutor;

            // 开始定时 Ping
            public void startPeriodicPing(String host, int intervalSeconds) {
                pingExecutor = Executors.newSingleThreadScheduledExecutor();
                pingExecutor.scheduleWithFixedDelay(() -> doPing(host), intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
            }

            // 停止 Ping
            public void stopPeriodicPing() {
                if (pingExecutor != null) {
                    pingExecutor.shutdown();
                }
            }

            // 执行单次 Ping
            private void doPing(String host) {
                Ping.onAddress(host).setTimeOutMillis(1000).doPing(new Ping.PingListener() {
                    @Override
                    public void onResult(com.stealthcopter.networktools.ping.PingResult pingResult) {
                        if(pingResult.isReachable){
                            rtt.set((int)Math.round(pingResult.timeTaken));
                            Timber.tag(TAG).i("ping成功，rtt：%d", rtt.get());
                        }else{
                            rtt.set(-1);
                            Timber.tag(TAG).e("ping失败");
                        }
                    }

                    @Override
                    public void onFinished(PingStats pingStats) {

                    }

                    @Override
                    public void onError(Exception e) {
                        rtt.set(-1);
                        Timber.tag(TAG).e("ping失败");
                    }
                });
            }
        }

        public void startPushStatistics(int intervalSeconds,
                                        int pingIntervalSeconds, String url,
                                        double pushFailureRateSet){
            pushFailureRate.set(pushFailureRateSet);
            // 获取host地址
            String host = null;
            Pattern pattern = Pattern.compile("^rtsp://([^:/]+)");
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                host = matcher.group(1);
            } else {
                Timber.tag(TAG).e("rtsp地址无法解析");
                throw new RuntimeException ("rtsp地址无法解析");
            }

            executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleWithFixedDelay(() -> reportPushStatistics(), intervalSeconds, intervalSeconds, TimeUnit.SECONDS);

            pingHelper = new PingHelper();
            pingHelper.startPeriodicPing(host, pingIntervalSeconds);
        }

        public void stopPushStatistics()
        {
            //停止ping
            pingHelper.stopPeriodicPing();

            //停止report
            if (executor != null) {
                executor.shutdown();
            }
        }

        public void setPushStatistics(boolean isSuccess, int size){
            frameList.add(new FrameInfo(isSuccess, size));
        }

        private void  reportPushStatistics(){
            if(VideoPusher.currentState.get() != VideoPusher.PushState.PUSHING) {
                return;
            }

            int ErrorFrameNum = 0;
            int size = 0;
            for(FrameInfo frame : frameList){
                if(!frame.getIfPushFrameSuccess()) ErrorFrameNum++;
                size += frame.getFrameSize();
            }
            int currentBitrate = size*8/1024;//kbps
            curPushFailureRate.set(Math.round(ErrorFrameNum/frameList.size() * 100.0) / 100.0);

            reportSubject.onNext(new VideoPusher.PushReport(
                    VideoPusher.EventType.Statistics, 0, "推流统计回调",
                    currentBitrate, rtt.get(),  curPushFailureRate.get()));

            if(curPushFailureRate.get()> pushFailureRate.get()){
                ifNeedReconnect.set(true);
            }else ifNeedReconnect.set(false);
        }

        // 判断是否需要重连（可指定pushFrame出现Error的比例超过pushFailureRate就重连）
        public boolean ifNeedReconnect(){
            return ifNeedReconnect.get();
        }
    }

    // 获取错误码对应内容
    private static String av_err2str(int errcode) {
        BytePointer buffer = new BytePointer(128);
        try {
            int ret = av_strerror(errcode, buffer, buffer.capacity());
            if (ret < 0) {
                return "Unknown error (" + errcode + ")";
            }
            return buffer.getString();
        } finally {
            buffer.deallocate(); // 确保释放内存
        }
    }


    public FFmpegPusher(String url, int width, int height, int fps,
                           int Bitrate) {
            this.url = url;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.Bitrate = Bitrate;
    }

    public void initPusher(byte[] header, AVCodecParameters srcParams)
    {
        this.header = header;
        this.srcParams = srcParams;

        try{
            startFfmpeg();
        } catch (Exception e) {
            Timber.tag(TAG).e(e.getMessage(),"初始化ffmpeg失败");
            throw new RuntimeException(e);
        }
    }

    public void pushFrame(ByteBuffer data, MediaCodec.BufferInfo bufferInfo) {
        if (VideoPusher.currentState.get() != VideoPusher.PushState.PUSHING
                || (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            return;
        }

        //检查是否需要重连
        if(statistics.ifNeedReconnect()) {
            reconnect(5, 4);
        }

        boolean isSuccess = true;

        // 创建足够大的BytePointer
        BytePointer nativeBuffer = new BytePointer(bufferInfo.size);

        // 正确拷贝数据的方式
        byte[] tempArray = new byte[bufferInfo.size];
        data.position(bufferInfo.offset);
        data.get(tempArray, 0, bufferInfo.size);
        nativeBuffer.put(tempArray);

        AVPacket pkt = av_packet_alloc();
        pkt.data(nativeBuffer);
        pkt.size(bufferInfo.size);

        // 时间基计算修正
        AVRational srcTimeBase = new AVRational();
        srcTimeBase.num(1);
        srcTimeBase.den(1000000);
        pkt.pts(av_rescale_q(bufferInfo.presentationTimeUs,
                srcTimeBase,
                videoStream.time_base()));
        pkt.stream_index(videoStream.index());
        pkt.flags((bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ? AV_PKT_FLAG_KEY : 0);

        int ret = av_interleaved_write_frame(outputContext, pkt);
        if (ret < 0) {
            isSuccess = false;
            Timber.tag(TAG).e("帧写入失败: %d", ret);
        }
        // 在packet释放后需要手动释放内存
        av_packet_unref(pkt);
        nativeBuffer.deallocate();

        statistics.setPushStatistics(isSuccess, bufferInfo.size);
    }

    public void stopPush() {
        synchronized (initStopLock) {
            // 停止统计
            statistics.stopPushStatistics();
            //销毁重连
            disposeReconnect();

            stopFfmpeg();
        }
    }

    /////////////////////////////////////////////////////////////////////////////////
                                //%!以下方法为内部方法！%//

    private void startFfmpeg(){
        synchronized (initStopLock) {
            try {
                // 1. 初始化上下文
                avformat_network_init();
                outputContext = avformat_alloc_context();
                AVOutputFormat outputFormat = av_guess_format("rtsp", null, null);
                outputContext.oformat(outputFormat);

                // 2. 创建流并复制参数
                videoStream = avformat_new_stream(outputContext, null);
                if (videoStream == null) {
                    throw new RuntimeException("无法创建视频流");
                }

                // 使用参数复制
                AVCodecParameters dstParams = videoStream.codecpar();
                int ret = avcodec_parameters_copy(dstParams, srcParams);
                if (ret < 0) {
                    throw new RuntimeException("参数复制失败: " + av_err2str(ret));
                }

                // 3. 强制设置关键参数（确保与编码器一致）
                dstParams.codec_tag(0); // 必须清除原有tag
                dstParams.extradata(new BytePointer(header)); // 设置SPS/PPS
                dstParams.extradata_size(header.length);

                // 4. 时间基配置（必须与帧时间戳匹配）
                videoStream.time_base().num(1);
                videoStream.time_base().den(fps);

                // 5. 打开输出
                AVDictionary options = new AVDictionary();
                av_dict_set(options, new BytePointer("rtsp_transport"), new BytePointer("tcp"), 0);
                av_dict_set(options, new BytePointer("preset"), new BytePointer("ultrafast"), 0);
                av_dict_set(options, new BytePointer("tune"), new BytePointer("zerolatency"), 0);

                if (avio_open(outputContext.pb(), url, AVIO_FLAG_WRITE) < 0) {
                    throw new RuntimeException("无法打开输出URL");
                }

                // 6. 写入头部
                ret = avformat_write_header(outputContext, options);
                if (ret < 0) {
                    throw new RuntimeException("写头失败: " + av_err2str(ret));
                }

                // 7. 开始统计帧数据
                statistics = new PushStatistics();
                statistics.startPushStatistics(2, 4,
                        url, 0.4);

                isPushing.set(true);
            } catch (Exception e) {
                stopFfmpeg();
                throw new RuntimeException(e);
            }
        }
    }

    private void stopFfmpeg() {
        av_write_trailer(outputContext);
        if (outputContext != null) {
            if ((outputContext.oformat().flags() & AVFMT_NOFILE) == 0) {
                avio_closep(outputContext.pb());
            }
            avformat_free_context(outputContext);
        }
    }

    private void restartPusher(){
        if(VideoPusher.currentState.get() != VideoPusher.PushState.RECONNECTING) {
            throw new RuntimeException("状态不对，无法重连");
        }
        // 先停止
        stopFfmpeg();

        //再开始
        try{
            startFfmpeg();
        }catch(Exception e){
            Timber.tag(TAG).e(e.getMessage(),"ffmpeg重新启动失败");
            throw new RuntimeException("ffmpeg重新启动失败" + e.getMessage());
        }
    }

    private void reconnect(int MAX_RECONNECT_ATTEMPTS, int periodSeconds){
        synchronized (reconnectLock) {
            if (VideoPusher.currentState.get() != VideoPusher.PushState.PUSHING) {
                Timber.tag(TAG).w("已有正在进行的重连任务");
                return;
            }

            Timber.tag(TAG).i("启动新重连任务");
            VideoPusher.setState(VideoPusher.PushState.RECONNECTING);

            // 清理旧任务
            if (reconnectDisposable != null && !reconnectDisposable.isDisposed()) {
                reconnectDisposable.dispose();
            }

            reconnectDisposable = Observable.interval(periodSeconds, TimeUnit.SECONDS)
                    .take(MAX_RECONNECT_ATTEMPTS) // 限制次数
                    .subscribeOn(Schedulers.io())
                    .subscribe(
                            tick -> {
                                if (tick >= MAX_RECONNECT_ATTEMPTS - 1) {
                                    Timber.tag(TAG).e( "达到最大重试次数");
                                    VideoPusher.setState(VideoPusher.PushState.ERROR);
                                    disposeReconnect();
                                    stopPush();
                                    reportError(0, "达到最大重试次数");
                                    throw new RuntimeException("达到最大重试次数");
                                }
                                if(VideoPusher.currentState.get() != VideoPusher.PushState.RECONNECTING){
                                    disposeReconnect();
                                    throw new RuntimeException("状态不对，重连已销毁");
                                }

                                try {
                                    Timber.i("尝试第 %d 次重连", tick + 1);
                                    restartPusher();

                                    //如果成功
                                    VideoPusher.currentState.set(VideoPusher.PushState.PUSHING);
                                    disposeReconnect();
                                    reportReconnection(1, "重连成功");
                                } catch (RuntimeException e) {
                                    // 单次重连失败
                                    Timber.tag(TAG).e(e, "单次重连操作失败");
                                    reportReconnection(-1, "单次重连操作失败" + e.getMessage());
                                }
                            },
                            throwable -> {
                                Timber.tag(TAG).e(throwable, "重连流发生致命错误");
                                VideoPusher.currentState.set(VideoPusher.PushState.ERROR);
                                disposeReconnect();
                                stopPush();
                                reportError(0,"重连流发生致命错误");
                                throw new RuntimeException("重连流发生致命错误");
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
                reconnectDisposable = null; // 状态重置
            }
        }
    }

    private void reportError(int code, String msg)
    {
        reportSubject.onNext(new VideoPusher.PushReport(
                VideoPusher.EventType.ERROR, code,
                msg,0,0, 0));
    }

    private void reportReconnection(int code, String msg)
    {
        reportSubject.onNext(new VideoPusher.PushReport(
                VideoPusher.EventType.RECONNECTION, code,
                msg,0,0, 0));
    }

    public PublishSubject<VideoPusher.PushReport> getReportSubject() {
        return reportSubject;
    }
}
