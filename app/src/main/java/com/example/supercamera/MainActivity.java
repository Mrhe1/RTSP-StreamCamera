package com.example.supercamera;

import android.content.Context;
import android.os.Bundle;
import android.util.Size;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import java.util.concurrent.ExecutionException;
import timber.log.Timber;
import android.media.Image;
import java.util.concurrent.TimeUnit;
import android.view.View;

@androidx.camera.core.ExperimentalGetImage
public class MainActivity extends AppCompatActivity {
    private VideoPusher videoPusher;
    private VideoRecorder videoRecorder;
    private PreviewView previewView;
    private static final String TAG = "MaiActivity";
    private final Object reconnectLock = new Object(); // 同步锁对象
    private volatile boolean isworking = false;
    //摄像头控制相关变量
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis streamingAnalysis;
    private ImageAnalysis recordingAnalysis;
    private Preview preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化推流参数
        int push_width = 1280;
        int push_height = 720;
        int push_fps = 30;
        int push_initAvgBitrate = 2500;//单位kbps
        int push_initMaxBitrate = 4000;
        int push_initMinBitrate = 1000;
        int record_width = 3840;
        int record_height = 2560;
        int record_bitrate = 10000;//单位kbps
        int record_fps =30;
        String push_Url = "artc://example.com/live/stream"; // WebRTC 推流地址
        String record_Path = getExternalFilesDir(null) + "/4k_record.mp4"; // 录制文件路径
        //保证名字不重复！！！
        //开始工作流
        if(Start(this, push_Url, push_width, push_height,
                push_fps, push_initAvgBitrate, push_initMaxBitrate,
                push_initMinBitrate, record_Path, record_width,
                record_height, record_bitrate, record_fps) != 0)
        {
            Timber.tag(TAG).e("工作流开始出错");
        }

        try {
            TimeUnit.SECONDS.sleep(300);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        Stop();//停止
    }

    private int Start(Context context, String push_Url,int push_width, int push_height,
                          int push_fps, int push_initAvgBitrate, int push_initMaxBitrate,
                          int push_initMinBitrate, String record_Path, int record_width,
                          int record_height, int record_bitrate, int record_fps)
    {//return 0:成功，1：正在推流，2：码率等设置不合规，3：启动出错//fps未用！！！！！！！！
        if(isworking())
        {
            Timber.tag(TAG).e("开始失败，正在推流");
            return 1;
        }
        //1.验证push码率设置是否正确
        if(push_initMaxBitrate < push_initAvgBitrate || push_initMinBitrate > push_initAvgBitrate)
        {
            Timber.tag(TAG).e("码率设置不合规");
            return 2;
        }
        // 2. 初始化推流服务
        try {
            videoPusher = new VideoPusher(context, push_width, push_height, push_fps, push_initAvgBitrate, push_initMaxBitrate, push_initMinBitrate);
        } catch (RuntimeException e) {
            Timber.tag(TAG).e("推流服务出错：%s",e.getMessage());
            Stop();//停止整个工作流。ps：handle部分只负责消息发送等外围操作，不影响主要程序执行
            return 3;
        }
        // 3. 初始化录制服务
        videoRecorder = new VideoRecorder();
        videoRecorder.startRecording(record_Path, record_width, record_height, record_bitrate);
        // 4. 初始化摄像头预览
        previewView = findViewById(R.id.previewView);
        // 5. 启动摄像头
        startCamera(push_width, push_height, record_width, record_height);
        // 6. 开始推流
        videoPusher.startPush(push_Url);
        // 7. 设置事件处理器
        setupEventHandlers();
        setworking(true);
        return 0;
    }

    private boolean Stop()
    {
        // 停止顺序很重要!!!
        try {
            // 1. 先停止推流
            videoPusher.stopPush();
            // 2. 停止摄像头采集
            stopCamera();
            // 3. 最后停止录制
            videoRecorder.stopRecording();
            setworking(false);
            return true;
        }
        catch (Exception e) {
            Timber.tag(TAG).e(e, "停止操作异常,程序退出");
            finishAffinity();//退出程序
            return false;
        }
    }

    private void startCamera(int push_width, int push_height, int record_width, int record_height) {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                // 保存cameraProvider引用到成员变量
                cameraProvider = cameraProviderFuture.get();

                // 1. 配置预览（低分辨率）
                Preview preview = new Preview.Builder()
                        .setTargetResolution(new Size(push_width, push_height)) // 720P
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // 2. 配置推流分析器（低分辨率）
                ImageAnalysis streamingAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(push_width, push_height)) // 720P
                        .build();
                streamingAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), image -> {
                    @SuppressWarnings("UnsafeExperimentalUsageError")
                    Image lowResImage = image.getImage();
                    try {
                        byte[] yuvData = YUVConverter.convertYUV420888ToYUV420P(lowResImage);
                        videoPusher.getStreamingQueue().onNext(yuvData);
                    } catch (IllegalArgumentException e) {
                        Timber.e(e, "Image format error");
                    } finally {
                        image.close(); // 确保关闭 Image
                    }
                });

                // 3. 配置录制分析器（高分辨率）
                ImageAnalysis recordingAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(record_width, record_height))
                        .build();
                recordingAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), image -> {
                    @SuppressWarnings("UnsafeExperimentalUsageError")
                    Image highResImage = image.getImage();
                    try {
                        byte[] yuvData = YUVConverter.convertYUV420888ToYUV420P(highResImage);
                        videoRecorder.getRecordingQueue().onNext(yuvData); // 改为队列方式
                    } catch (IllegalArgumentException e) {
                        Timber.e(e, "Image format error"); // 修改为Timber
                    } finally {
                        image.close(); // 确保关闭 Image
                    }
                });

                // 4. 绑定摄像头
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, streamingAnalysis, recordingAnalysis);
            } catch (ExecutionException | InterruptedException e) {
                Timber.e(e, "Camera initialization failed");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopCamera() {
        if (cameraProvider != null) {
            try {
                // 1. 解除所有绑定
                cameraProvider.unbindAll();

                // 2. 关闭分析器
                if (streamingAnalysis != null) {
                    streamingAnalysis.clearAnalyzer();
                }
                if (recordingAnalysis != null) {
                    recordingAnalysis.clearAnalyzer();
                }

                // 3. 释放预览资源
                if (preview != null) {
                    preview.setSurfaceProvider(null);
                }

                // 4. 重置预览视图
                previewView.post(() -> previewView.setVisibility(View.GONE));

                Timber.tag(TAG).i("摄像头已停止");
            } catch (Exception e) {
                Timber.tag(TAG).e(e, "停止摄像头失败");
            }
        }
    }

    private void setupEventHandlers() {
        videoPusher.getReportSubject()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(report -> {
                    switch (report.type) {
                        case PUSH_STARTED:
                            handlePushStart(report);
                            break;
                        case PUSH_STOPPED:
                            handlePushStop(report);
                            break;
                        case NETWORK_POOR:
                            handleNetworkPoor(report);
                            break;
                        case NETWORK_RECOVERY:
                            handleNetworkRecovery(report);
                            break;
                        case CUR_BITRATE:
                            handleBitrateReport(report);
                            break;
                        case ERROR:
                            handleError(report);
                            break;
                        case RECONNECTION_ERROR:
                            handleReconnectError(report);
                            break;
                        case PUSHER_DESTROY:
                            handlePusherDestroy(report);
                            break;
                        case NETWORK_DELAY:
                            handleNetworkDelay(report);
                            break;
                        case URLCHANGE:
                            handleUrlChange(report);
                            break;
                        case RECONNECTION_SUCCESS:
                            handleReconnectionSuccess(report);
                            break;
                    }
                });
    }

    private void handlePushStart(VideoPusher.PushReport report) {
        int initialAvg = report.avgBitrate;
        int initialMax = report.maxBitrate;
        int initialMin = report.minBitrate;
        String url = report.message;
        Timber.tag(TAG).i("推流已开始,平均码率：%dkbps,最大码率:%dbps,最小码率:%dbps.推流url%s"
        ,initialAvg,initialMax,initialMin,url);
    }

    private void handlePushStop(VideoPusher.PushReport report) {
        Timber.tag(TAG).i(report.message);
    }

    private void handleNetworkPoor(VideoPusher.PushReport report) {
        Timber.tag(TAG).i("网络质量差");
    }

    private void handleNetworkRecovery(VideoPusher.PushReport report) {
        Timber.tag(TAG).i("网络恢复");
    }

    private void handleBitrateReport(VideoPusher.PushReport report) {
        int initialAvg = report.avgBitrate;
        int initialMax = report.maxBitrate;
        int initialMin = report.minBitrate;
        Timber.tag(TAG).i("码率报告：平均码率：%dkbps,最大码率:%dbps,最小码率:%dbps"
                ,initialAvg,initialMax,initialMin);
    }

    private void handleError(VideoPusher.PushReport report) {
        String errormsg = report.message;
        int errorcode = report.code;
        Timber.tag(TAG).e("推流出错,代码：%d,消息：%s",errorcode,errormsg);
    }

    private void handleReconnectError(VideoPusher.PushReport report) {
        Timber.tag(TAG).e("重连异常");
    }

    private void handlePusherDestroy(VideoPusher.PushReport report) {
        Timber.tag(TAG).i("推流引擎已销毁");
    }

    private void handleNetworkDelay(VideoPusher.PushReport report) {
        Timber.tag(TAG).e("网络延迟rtt：%dms",report.code);
    }

    private void handleUrlChange(VideoPusher.PushReport report) {
        Timber.tag(TAG).e("推流url：%s",report.message);
    }

    private void handleReconnectionSuccess(VideoPusher.PushReport report) {
        Timber.tag(TAG).e("重连成功");
    }

    public boolean isworking() {
        synchronized (reconnectLock) {
            return isworking;
        }
    }
    private void setworking(boolean reconnecting) {
        synchronized (reconnectLock) {
            isworking = reconnecting;
        }
    }

    @Override
    protected void onDestroy() {
        try {
            Stop();
        } finally {
            super.onDestroy();
        }
    }
}