package com.example.supercamera;

import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.javacpp.*;
import org.bytedeco.javacpp.BytePointer;
import static org.bytedeco.javacpp.Pointer.*;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

import android.media.MediaCodec;

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
    private int avgBitrate;
    private int maxBitrate;
    private int minBitrate;
    private String url;
    private final Object initStopLock = new Object();

    public FFmpegPusher(String url, int width, int height, int fps,
                           int avgBitrate) {
            this.url = url;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.avgBitrate = avgBitrate;
    }

    public void initPusher(byte[] header)
    {
        synchronized (initStopLock) {
            try {
                avformat_network_init();
                outputContext = avformat_alloc_context();
                AVOutputFormat outputFormat = av_guess_format("rtsp", null, null);
                outputContext.oformat(outputFormat);

                // 创建视频流
                videoStream = avformat_new_stream(outputContext, null);
                videoStream.codecpar().codec_type(AVMEDIA_TYPE_VIDEO);
                videoStream.codecpar().codec_id(AV_CODEC_ID_H264);
                videoStream.codecpar().width(width);
                videoStream.codecpar().height(height);
                videoStream.codecpar().codec_tag(0); // 关键修改

                // 设置时间基
                videoStream.time_base(new AVRational().num(1).den(fps));

                // 设置 extradata
                videoStream.codecpar().extradata(new org.bytedeco.javacpp.BytePointer(header));
                videoStream.codecpar().extradata_size(header.length);

                // 打开输出
                AVDictionary options = new AVDictionary(null);
                av_dict_set(options, "rtsp_transport", "tcp", 0);
                av_dict_set(options, "preset", "ultrafast", 0);
                av_dict_set(options, "tune", "zerolatency", 0);

                if (avio_open(outputContext.pb(), url, AVIO_FLAG_WRITE) < 0) {
                    throw new RuntimeException("无法打开输出URL");
                }

                avformat_write_header(outputContext, options);
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

}
