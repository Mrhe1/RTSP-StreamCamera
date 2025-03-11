package com.example.supercamera;

import org.bytedeco.javacpp.*;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.javacpp.BytePointer;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
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
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;
import static org.bytedeco.ffmpeg.global.avutil.av_rescale_q;
import static org.bytedeco.ffmpeg.global.avutil.av_strerror;
import static org.bytedeco.javacpp.Pointer.*;

import android.media.MediaCodec;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import timber.log.Timber;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

// 新增FFmpeg推流器类
public class FFmpegPusher {
    private static final String TAG = "FFmpegPusher";
    private AVFormatContext outputContext;
    private AVStream videoStream;
    private long startTimeUs;
    private final AtomicBoolean isPushing = new AtomicBoolean(false);
    private int width;
    private int height;
    private int fps;
    private int Bitrate;
    private String url;
    private final Object initStopLock = new Object();
    private final PublishSubject<FFmpegReport> reportSubject = PublishSubject.create();

    public static class FFmpegReport {
        public final VideoPusher.EventType type;
        public final int code;
        public final String message;
        public final int BitrateNow;
        public final int rtt;

        public FFmpegReport(VideoPusher.EventType type, int code, String message, int BitrateNow, int rtt) {
            this.type = type;
            this.code = code;
            this.message = message;
            this.BitrateNow = BitrateNow;
            this.rtt = rtt;
        }
    }

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

                isPushing.set(true);
            } catch (Exception e) {
                cleanup();
                throw new RuntimeException(e);
            }
        }
    }

    public void pushFrame(ByteBuffer data, MediaCodec.BufferInfo bufferInfo) {
        if (!isPushing.get() || (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            return;
        }

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
            Timber.tag(TAG).e("帧写入失败: %d", ret);
        }
        // 在packet释放后需要手动释放内存
        av_packet_unref(pkt);
        nativeBuffer.deallocate();
    }

    public void stopPush() {
        synchronized (initStopLock) {
            if (isPushing.compareAndSet(true, false)) {
                av_write_trailer(outputContext);
                cleanup();
            }
        }
    }

    private void cleanup() {
        if (outputContext != null) {
            if ((outputContext.oformat().flags() & AVFMT_NOFILE) == 0) {
                avio_closep(outputContext.pb());
            }
            avformat_free_context(outputContext);
        }
    }

    public boolean isInitialized() {
        return isPushing.get() && outputContext != null;
    }

    public PublishSubject<FFmpegReport> getReportSubject() {
        return reportSubject;
    }
}
