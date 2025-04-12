package com.example.supercamera.StreamPusher;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_free;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;

import com.example.supercamera.StreamPusher.PushStats.PushStatsListener;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.global.avcodec;

import java.util.concurrent.Executors;

import timber.log.Timber;

public class FFmpegPusherImpl implements StreamPusher {
    private String TAG = "FFmpegPusher";
    private PushConfig mConfig;
    private AVCodecParameters params = avcodec_parameters_alloc();
    private PushStatsListener listener;


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
        this.params = getEncoderParameters(config, params);
    }

    @Override
    public void start() {
        Executors.newSingleThreadExecutor().submit(() -> {

        });
    }

    @Override
    public void stop() {

    }

    @Override
    public void setStreamListener(PushStatsListener listener) {
        this.listener = listener;
    }

    private AVCodecParameters getEncoderParameters(PushConfig config,
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

    private void notifyError(int code, String message) {

    }

}
