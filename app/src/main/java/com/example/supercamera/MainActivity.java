package com.example.supercamera;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import java.util.Arrays;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import timber.log.Timber;
import android.view.View;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Date;
import android.Manifest;
import android.widget.Button;
import android.util.Range;


@androidx.camera.core.ExperimentalGetImage
public class MainActivity extends AppCompatActivity {
    public enum WorkflowState {
        IDLE,             // 初始状态
        READY,              // 准备就绪
        STARTING,       // 初始化中
        WORKING,          // 工作中
        STOPPING,       // 关闭中
        ERROR          //出错
    }

    private final AtomicReference<WorkflowState> currentState =
            new AtomicReference<>(WorkflowState.IDLE);

    private VideoPusher videoPusher;
    private VideoRecorder videoRecorder;
    private PreviewView previewView;
    private static final String TAG = "MaiActivity";
    private static final String TAGCamera = "StartCamera";
    private static final String TAGWorkflowState = "WorkflowState";
    private final Object startStopLock = new Object();
    private final Object checkPermissionLock = new Object();
    private volatile boolean ispermitted = false;
    //摄像头控制相关变量
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis streamingAnalysis;
    private ImageAnalysis recordingAnalysis;
    private Preview preview;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    //按钮变量
    private Button btnStart;
    private Button btnStop;
    private final CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
    private CompositeDisposable compositeDisposable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        compositeDisposable = new CompositeDisposable();
        setContentView(R.layout.activity_main);

        setpermitted(checkCameraPermission());//权限检查
        if (!ispermitted()) {
            requestCameraPermission();
        }

        // 初始化按钮
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        // 设置按钮点击监听
        btnStart.setOnClickListener(v -> handleStart());
        btnStop.setOnClickListener(v -> handleStop());

        // 初始化按钮状态
        updateButtonState();

        setState(WorkflowState.READY);
    }

    public boolean setState(WorkflowState newState) {
        // 状态校验
        if (!isValidTransition(newState)) {
            Timber.tag(TAGWorkflowState).w("非法状态转换: %s → %s",
                    currentState.get(), newState);
            return false;
        }
        return currentState.compareAndSet(currentState.get(), newState);
    }

    private boolean isValidTransition(WorkflowState newState) {
        // 实现状态转换规则校验
        WorkflowState current = currentState.get();
        switch (current) {
            case IDLE: return newState == WorkflowState.STARTING;
            case READY: return newState == WorkflowState.STARTING;
            case STARTING: return newState == WorkflowState.WORKING || newState == WorkflowState.ERROR;
            case WORKING: return newState == WorkflowState.STOPPING || newState == WorkflowState.ERROR;
            case STOPPING: return newState == WorkflowState.READY;
            case ERROR: return newState == WorkflowState.STOPPING;
            default: return false;
        }
    }

    private void handleStart()
    {
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
        String push_Url = "artc://example.com/live/stream"; // WebRTC 推流地址，可为空？？？

        if(currentState.get() != WorkflowState.READY)
        {
            Timber.tag(TAG).e("无法重复开启工作流");return;
        }
        if(!ispermitted()) {
            requestCameraPermission();
            Timber.tag(TAG).e("权限被拒，工作流无法开始");return;
        }
        if(Start(this, push_Url, push_width, push_height,
                push_fps, push_initAvgBitrate, push_initMaxBitrate, push_initMinBitrate,
                record_width, record_height, record_bitrate, record_fps) == 0)
        {
            Timber.tag(TAG).e("工作流开始成功");
        }
        else{
            Timber.tag(TAG).e("工作流开始失败");
        }
        updateButtonState();
    }

    private void handleStop()
    {
        if(currentState.get() != WorkflowState.WORKING)
        {
            Timber.tag(TAG).e("工作流未开始，无法关闭");return;
        }
        if(!Stop())
        {
            Timber.tag(TAG).e("工作流关闭失败");return;
        }
        updateButtonState();
    }

    private int Start(Context context, String push_Url,int push_width, int push_height,
                          int push_fps, int push_initAvgBitrate, int push_initMaxBitrate,
                          int push_initMinBitrate, int record_width,
                          int record_height, int record_bitrate, int record_fps)
    {//return 0:成功，1：正在推流，2：码率等设置不合规，3：推流出错，4：recordpath生成出错 ，5：其他错误
        synchronized (startStopLock) {
            if (currentState.get() != WorkflowState.READY) {
                Timber.tag(TAG).e("开始失败，工作流已启动");
                return 1;
            }
            setState(WorkflowState.STARTING);

            //验证push码率设置是否正确
            if (push_initMaxBitrate < push_initAvgBitrate || push_initMinBitrate > push_initAvgBitrate) {
                Timber.tag(TAG).e("码率设置不合规");
                setState(WorkflowState.READY);
                return 2;
            }
            //生成recordpath
            String record_Path = generateUniqueFileName(context);
            if (record_Path == null) {
                Timber.tag(TAG).e("无法生成录制路径");
                setState(WorkflowState.READY);
                return 4;
            }

            Timber.tag(TAG).e("正在开启工作流");
            try {
                // 2. 初始化推流服务
                try {
                    videoPusher = new VideoPusher(context, push_width, push_height, push_fps, push_initAvgBitrate, push_initMaxBitrate, push_initMinBitrate);
                } catch (RuntimeException e) {
                    Timber.tag(TAG).e("推流服务出错：%s", e.getMessage());
                    setState(WorkflowState.ERROR);
                    //Stop();//通过事件总线统一处理
                    return 3;
                }
                // 3. 初始化录制服务
                videoRecorder = new VideoRecorder();
                videoRecorder.startRecording(record_Path, record_width, record_height, record_bitrate);
                // 4. 初始化摄像头预览
                previewView = findViewById(R.id.previewView);
                // 5. 启动摄像头
                startCamera(push_width, push_height, record_width, record_height, push_fps, record_fps);
                // 6. 开始推流
                videoPusher.startPush(push_Url);
                // 7. 设置事件处理器
                setupEventHandlers();
                setState(WorkflowState.WORKING);
                return 0;
            } catch (Exception e) {
                setState(WorkflowState.ERROR);
                Timber.tag(TAG).e("启动异常: %s", e.getMessage());
                // 释放锁后再执行停止操作
                new Handler(Looper.getMainLooper()).post(() -> Stop());
                return 5;
            }
        }
    }

    private boolean Stop() {
        synchronized (startStopLock) {
            if (currentState.get() != WorkflowState.WORKING && currentState.get() != WorkflowState.ERROR) {
                Timber.tag(TAG).i("无需重复停止");
                return true;
            }
            setState(WorkflowState.STOPPING);
            try {
                // 1. 停止推流
                if (videoPusher != null) {
                    videoPusher.stopPush();
                    videoPusher = null;
                }
                // 2. 停止摄像头
                if (cameraProvider != null) {
                    stopCamera();
                }
                // 3. 停止录制
                if (videoRecorder != null) {
                    videoRecorder.stopRecording();
                }

                setState(WorkflowState.READY);
                return true;
            } catch (Exception e) {
                Timber.tag(TAG).e(e, "停止操作异常");
                setState(WorkflowState.READY);//忽略停止错误
                return false;
            }
        }
    }

    private void updateButtonState() {//更新按钮状态
        boolean isWorking = currentState.get() == WorkflowState.WORKING;
        btnStart.setEnabled(!isWorking);
        btnStop.setEnabled(isWorking);
    }

    //获取fps的range
    private Range<Integer> getFpsRange(int fps) {
        try {
            // 获取设备支持的帧率范围
            CameraInfo cameraInfo = cameraProvider.getAvailableCameraInfos().get(0);
            Set<Range<Integer>> supportedRanges = cameraInfo.getSupportedFrameRateRanges();

            // 策略 1：优先匹配精确帧率
            for (Range<Integer> range : supportedRanges) {
                if (range.getLower() == fps && range.getUpper() == fps) {
                    return range;
                }
            }

            // 策略 2：匹配包含目标帧率的范围
            for (Range<Integer> range : supportedRanges) {
                if (range.contains(fps)) {
                    return new Range<>(fps, fps); // 限定为固定值
                }
            }

            // 策略 3：降级到最低可用帧率
            int minFps = Integer.MAX_VALUE;
            for (Range<Integer> range : supportedRanges) {
                minFps = Math.min(minFps, range.getLower());
            }
            Timber.tag(TAGCamera).w("设备不支持 %dfps，降级到 %dfps", fps, minFps);
            return new Range<>(minFps, minFps);
        }
        catch (Exception e) {
            Timber.tag(TAGCamera).e(e, "帧率配置失败");
            return new Range<>(30, 30); // 最终保底值
        }
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private boolean checkStreamCombinationSupport(int push_width, int push_height,
                                                  int record_width, int record_height) {
        if (cameraProvider == null) {
            Timber.tag(TAGCamera).e("CameraProvider 未初始化");
            return false;
        }

        // 通过 CameraX 直接获取 Camera2 特性
        Camera camera = cameraProvider.bindToLifecycle(this, cameraSelector);
        Camera2CameraInfo camera2Info = Camera2CameraInfo.from(camera.getCameraInfo());

        // 获取 YUV_420_888 格式的所有支持分辨率
        Size[] supportedSizes = Objects.requireNonNull(camera2Info.getCameraCharacteristic(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )).getOutputSizes(ImageFormat.YUV_420_888);

        // 要检查的目标分辨率数组
        Size[] targetSizes = {
                new Size(push_width, push_height),  // 预览
                new Size(record_width, record_height)  // 录制
        };

        // 将支持的尺寸转换为HashSet加速查找
        Set<Size> sizeSet = new HashSet<>(Arrays.asList(supportedSizes));

        // 检查所有目标尺寸是否存在于支持列表中
        for (Size targetSize : targetSizes) {
            if (!sizeSet.contains(targetSize)) {
                Timber.tag(TAGCamera).e("分辨率 %dx%d 不支持", targetSize.getWidth(), targetSize.getHeight());
                return false;
            }
        }
        return true;
    }

    //检查硬件防抖支持
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private boolean isHardwareStabilizationSupported() {
        try {
            Camera camera = cameraProvider.bindToLifecycle(this, cameraSelector);
            Camera2CameraInfo camera2Info = Camera2CameraInfo.from(camera.getCameraInfo());
            int[] modes = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
            );

            for (int mode : modes) {
                if (mode == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                    return true;
                }
            }
        } catch (Exception e) {
            Timber.e("检查硬件防抖支持失败: %s", e.getMessage());
        }
        return false;
    }


    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void startCamera(int push_width, int push_height, int record_width,
                             int record_height, int push_fps, int record_fps) {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                //获取fps——range
                Range<Integer> push_fps_Range = getFpsRange(push_fps);
                Range<Integer> record_fps_Range = getFpsRange(record_fps);//不支持返回最低帧率
                //检查摄像头是否支持流组合
                if (!checkStreamCombinationSupport(push_width, push_height, record_width, record_height)) {
                    Timber.tag(TAGCamera).e("相机不支持流组合");
                    throw new RuntimeException("相机不支持流组合");
                }
                // ========== 预览配置 ==========
                Preview.Builder previewBuilder = new Preview.Builder()
                        .setTargetResolution(new Size(push_width, push_height));
                //用camera2配置扩展
                new Camera2Interop.Extender<>(previewBuilder)
                        .setCaptureRequestOption(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                push_fps_Range
                        );

                preview = previewBuilder.build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // ========== 推流分析器配置 ==========
                ImageAnalysis.Builder streamingBuilder = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(push_width, push_height))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);

                new Camera2Interop.Extender<>(streamingBuilder)
                        .setCaptureRequestOption(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                push_fps_Range
                        )
                        .setCaptureRequestOption(
                                CaptureRequest.SENSOR_FRAME_DURATION,
                                (long)(1_000_000_000 / ((Integer) push_fps_Range.getUpper()))
                        )
                        .setCaptureRequestOption(
                                CaptureRequest.CONTROL_MODE,
                                CaptureRequest.CONTROL_MODE_AUTO
                        )
                        .setCaptureRequestOption(//防抖
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                        )
                        .setCaptureRequestOption(
                                CaptureRequest.EDGE_MODE,
                                CaptureRequest.EDGE_MODE_FAST
                        );

                streamingAnalysis = streamingBuilder.build();

                // ========== 录制分析器配置 ==========
                ImageAnalysis.Builder recordingBuilder = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(record_width, record_height))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);

                new Camera2Interop.Extender<>(recordingBuilder)
                        .setCaptureRequestOption(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                record_fps_Range
                        )
                        .setCaptureRequestOption(
                                CaptureRequest.SENSOR_FRAME_DURATION,
                                (long)(1_000_000_000 / ((Integer) record_fps_Range.getUpper()))
                        )
                        .setCaptureRequestOption(//防抖
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        )
                        .setCaptureRequestOption(
                                CaptureRequest.NOISE_REDUCTION_MODE,
                                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                        );

                recordingAnalysis = recordingBuilder.build();

                // ========== 绑定用例 ==========
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        streamingAnalysis,
                        recordingAnalysis
                );

            } catch (ExecutionException | InterruptedException e) {
                if (cameraProvider != null) {
                    cameraProvider.unbindAll();
                    cameraProvider = null;
                }
                Timber.e(e, "摄像头初始化失败");
                throw new RuntimeException("摄像头初始化失败",e);
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
                    streamingAnalysis = null;
                }
                if (recordingAnalysis != null) {
                    recordingAnalysis.clearAnalyzer();
                    recordingAnalysis = null;
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
            cameraProvider = null;
        }
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION_REQUEST_CODE
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        //判断请求码是否匹配
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {

            //检查结果数组是否非空
            if (grantResults.length > 0) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    setpermitted(true);// 权限通过
                    Timber.tag(TAG).i("camera权限通过");
                } else {
                    Timber.tag(TAG).i("camera权限被拒");// 权限被拒
                }
            }
        }
    }


    private void setupEventHandlers() {
        Disposable disposable = videoPusher.getReportSubject()
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
        compositeDisposable.add(disposable);
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
        setState(WorkflowState.ERROR);
        Stop();
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
        Timber.tag(TAG).e("推流改变，url：%s",report.message);
    }

    private void handleReconnectionSuccess(VideoPusher.PushReport report) {
        Timber.tag(TAG).e("重连成功");
    }

    public boolean ispermitted() {
        synchronized (checkPermissionLock) {
            return ispermitted;
        }
    }
    private void setpermitted(boolean permitted) {
        synchronized (checkPermissionLock) {
            ispermitted = permitted;
        }
    }

    public static String generateUniqueFileName(Context context) {
        //创建专属存储目录
        File recordsDir = new File(context.getExternalFilesDir(null), "SuperRecords");
        if (!recordsDir.exists() && !recordsDir.mkdirs()) {
            Timber.e("创建目录失败");
            return null;
        }
        //获取当前日期字符串
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        String dateStr = dateFormat.format(new Date());
        //查找当天最大序号
        int maxNumber = 0;
        Pattern pattern = Pattern.compile("^supercamera_record-"+dateStr+"_#(\\d{3})\\.mp4$");
        File[] files = recordsDir.listFiles();
        if (files != null) {
            for (File file : files) {
                try {
                    Matcher matcher = pattern.matcher(file.getName());
                    if (matcher.find()) {
                        int num = Integer.parseInt(matcher.group(1));
                        maxNumber = Math.max(maxNumber, num);
                    }
                } catch (NumberFormatException e) {
                    Timber.e(e, "Invalid file number format");
                }
            }
        }
        //生成新序号（两位数格式）
        String newNumber = String.format(Locale.CHINA, "%02d", maxNumber + 1);
        //组合完整路径
        return new File(recordsDir,
                "supercamera_record-" + dateStr + "_#" + newNumber + ".mp4"
        ).getAbsolutePath();
    }


    @Override
    protected void onDestroy() {
        try {
            Stop();
        } catch (Exception e) {}
        //释放RxJava资源
        if (compositeDisposable != null && !compositeDisposable.isDisposed()) {
            compositeDisposable.dispose();
        }
        super.onDestroy();
    }
}