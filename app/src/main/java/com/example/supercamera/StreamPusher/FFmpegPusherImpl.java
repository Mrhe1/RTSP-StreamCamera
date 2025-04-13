package com.example.supercamera.StreamPusher;

import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_free;
import static org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE;
import static org.bytedeco.ffmpeg.global.avformat.av_guess_format;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2;
import static org.bytedeco.ffmpeg.global.avformat.avformat_free_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_network_init;
import static org.bytedeco.ffmpeg.global.avformat.avformat_new_stream;
import static org.bytedeco.ffmpeg.global.avformat.avformat_write_header;
import static org.bytedeco.ffmpeg.global.avformat.avio_alloc_context;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_free;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;
import static org.bytedeco.ffmpeg.global.avutil.av_malloc;
import static org.bytedeco.ffmpeg.global.avutil.av_strerror;

import com.example.supercamera.FFmpegPusher;
import com.example.supercamera.StreamPusher.PushStats.PushStatistics;
import com.example.supercamera.StreamPusher.PushStats.PushStatsInfo;
import com.example.supercamera.StreamPusher.PushStats.PushStatsListener;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVOutputFormat;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;

import java.util.concurrent.Executors;

import timber.log.Timber;

public class FFmpegPusherImpl implements StreamPusher {
    private String TAG = "FFmpegPusher";
    private PushConfig mConfig;
    private AVCodecParameters params = avcodec_parameters_alloc();
    private PushListener listener;
    private final Object FFmpegLock = new Object();
    private int startCount = 0;
    private AVFormatContext outputContext;
    private AVStream videoStream;
    private AVCodecParameters srcParams;
    private PushStatistics statistics;


    // throw IllegalArgumentException AND IllegalStateException
    @Override
    public void configure(PushConfig config) {
        if(!PushState.setState(PushState.PushStateEnum.CONFIGURED)) {
            String msg = String.format("configure出错，IllegalState，目前状态：%s",PushState.getState().toString());
            Timber.tag(TAG).e(msg);
            throw new IllegalStateException(msg);
        }

        if(config.url.startsWith("rtsp://")) {
            Timber.tag(TAG).e("URL必须以rtsp://开头");
            throw new IllegalArgumentException("URL必须以rtsp://开头");
        }

        if(config.header == null || config.Bitrate <= 0 ||
                config.fps <= 0 || config.height <= 0 || config.width <= 0) {
            Timber.tag(TAG).e("config参数错误");
            throw new IllegalArgumentException("config参数错误");
        }

        this.mConfig = config;

        // 设置AVCodecParameters
        this.params = setEncoderParameters(config, params);
    }

    @Override
    public void start() {
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                startFFmpeg(mConfig);
                if (listener != null) {
                    listener.onStarted();
                }
            } catch (Exception e) {
                String msg = String.format("初始化ffmpeg失败:%s",e.getMessage());
                Timber.tag(TAG).e(e.getMessage(), "初始化ffmpeg失败");
                if (listener != null) {
                    listener.onError(0,msg);
                }
            }
        });
    }

    @Override
    public void stop() {

    }

    @Override
    public void setStreamListener(PushListener listener) {
        this.listener = listener;
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
                    throw new RuntimeException("FFmpeg未编译RTSP支持");
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

                // 7. 开始统计帧数据
                statistics = new PushStatistics(config.statsIntervalSeconds,
                        config.pingIntervalSeconds, config.url,
                        config.pushFailureRateSet, config.timeStampQueue);

                statistics.setPushStatListener(new PushStatsListener() {
                    @Override
                    public void onStatistics(PushStatsInfo info) {
                        if (listener != null) {
                            listener.onStatistics(info);
                        }
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

    private void notifyError(int code, String message) {

    }

    // 获取错误码对应内容
    private static String av_err2str(int errcode) {
        try (BytePointer buffer = new BytePointer(128)) {
            int ret = av_strerror(errcode, buffer, buffer.capacity());
            return (ret < 0) ? "Unknown error" : buffer.getString();
        }
    }

}
