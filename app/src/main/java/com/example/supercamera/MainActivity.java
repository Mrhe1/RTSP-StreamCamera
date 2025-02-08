package com.example.supercamera;

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

public class MainActivity extends AppCompatActivity {
    private VideoPusher videoPusher;
    private VideoRecorder videoRecorder;
    private PreviewView previewView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. 初始化推流参数
        int width = 1280;
        int height = 720;
        int fps = 30;
        int avgBitrate = 2500;
        int maxBitrate = 4000;
        int minBitrate = 1000;
        String pushUrl = "artc://example.com/live/stream"; // WebRTC 推流地址
        String recordPath = getExternalFilesDir(null) + "/4k_record.mp4"; // 录制文件路径

        // 2. 初始化推流服务
        //videoPusher = new VideoPusher(this, width, height, fps, avgBitrate, maxBitrate, minBitrate);
        try {
            videoPusher = new VideoPusher(this, width, height, fps, avgBitrate, maxBitrate, minBitrate);
        } catch (RuntimeException e) {
            // 处理初始化失败：提示用户、记录日志、降级处理等
        }
        // 3. 初始化录制服务
        videoRecorder = new VideoRecorder();
        videoRecorder.startRecording(recordPath, 3840, 2160, 5000000); // 4K 5Mbps

        // 4. 初始化摄像头预览
        previewView = findViewById(R.id.previewView);

        // 5. 启动摄像头
        startCamera();

        // 6. 开始推流
        videoPusher.startPush(pushUrl);

        // 7. 设置事件处理器
        setupEventHandlers();
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
                        case PACKETSLOST:
                            handlePacktsLost(report);
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
        // 示例：更新UI状态
        // runOnUiThread(() -> {
        //     textStatus.setText("推流中");
        //     textBitrate.setText(String.format("初始码率：%d/%d/%d",
        //         report.avgBitrate, report.maxBitrate, report.minBitrate));
        // });
    }

    private void handlePushStop(VideoPusher.PushReport report) {
        // 示例：清理资源
        // videoRecorder.stopRecording();
    }

    private void handleNetworkPoor(VideoPusher.PushReport report) {
        // 示例：显示网络警告
        // showToast("网络质量下降，正在优化...");
    }

    private void handleNetworkRecovery(VideoPusher.PushReport report) {
        // 示例：显示网络恢复提示
        // showToast("网络已恢复");
    }

    private void handleBitrateReport(VideoPusher.PushReport report) {
        // 示例：更新码率显示
        // textBitrate.setText(String.format("当前码率：%d/%d/%d",
        //     report.avgBitrate, report.maxBitrate, report.minBitrate));
    }

    private void handleError(VideoPusher.PushReport report) {
        // 示例：显示错误对话框
        // new AlertDialog.Builder(this)
        //     .setTitle("推流错误")
        //     .setMessage(report.message)
        //     .show();
    }

    private void handleReconnectError(VideoPusher.PushReport report) {
        // 示例：显示重连提示
        // showToast("正在尝试重新连接...");
    }

    private void handlePacktsLost(VideoPusher.PushReport report) {

    }

    private void handlePusherDestroy(VideoPusher.PushReport report) {

    }

    private void handleNetworkDelay(VideoPusher.PushReport report) {

    }

    private void handleUrlChange(VideoPusher.PushReport report) {

    }

    private void handleReconnectionSuccess(VideoPusher.PushReport report) {

    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // 1. 配置预览（低分辨率）
                Preview preview = new Preview.Builder()
                        .setTargetResolution(new Size(1280, 720)) // 720P
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // 2. 配置推流分析器（低分辨率）
                ImageAnalysis streamingAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720)) // 720P
                        .build();
                streamingAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), image -> {
                    Image lowResImage = image.getImage();
                    try {
                        byte[] yuvData = YUVConverter.convertYUV420888ToYUV420P(lowResImage);
                        videoPusher.getStreamingQueue().onNext(yuvData);
                    } catch (IllegalArgumentException e) {
                        Timber.e(e, "Image format error"); // 修改为Timber
                    } finally {
                        image.close(); // 确保关闭 Image
                    }
                });

                // 3. 配置录制分析器（高分辨率）
                ImageAnalysis recordingAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(3840, 2160)) // 4K
                        .build();
                recordingAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), image -> {
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        videoPusher.stopPush();
        videoRecorder.stopRecording();
    }
}