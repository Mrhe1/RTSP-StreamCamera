package com.example.supercamera;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;

import java.util.ArrayList;
import java.util.Arrays;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import timber.log.Timber;

import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Date;
import android.Manifest;
import android.view.ViewGroup;
import android.widget.Button;
import android.util.Range;
import android.widget.FrameLayout;

/////////////////////////////////////////////注意要求push与record帧率一致！！！！#####
public class MainActivity extends AppCompatActivity {
    // 新增防抖模式枚举
    public enum StabilizationMode {
        OFF,
        OIS_ONLY,   // 仅光学防抖
        EIS_ONLY,   // 仅电子防抖
        HYBRID      // 混合模式（OIS+EIS）
    }

    public final onStartedCheck checker = new onStartedCheck();
    private final AtomicReference<StabilizationMode> nowPushStabMode =
            new AtomicReference<>(StabilizationMode.OFF);
    private final AtomicReference<StabilizationMode> nowRecordStabMode =
            new AtomicReference<>(StabilizationMode.OFF);
    private final AtomicInteger Now_Push_fps = new AtomicInteger(-1);
    private final AtomicInteger Now_Record_fps = new AtomicInteger(-1);

    public enum WorkflowState {
        IDLE,             // 初始状态
        READY,              // 准备就绪
        STARTING,       // 初始化中
        WORKING,          // 工作中
        STOPPING,       // 关闭中
        ERROR          //出错
    }

    private static final AtomicReference<WorkflowState> currentState =
            new AtomicReference<>(WorkflowState.IDLE);

    private VideoPusher videoPusher;
    private VideoRecorder videoRecorder;
    private static final String TAG = "MaiActivity";
    private static final String TAGCamera = "StartCamera";
    private static final String TAGWorkflowState = "WorkflowState";
    private final Object startStopLock = new Object();
    private final Object checkPermissionLock = new Object();
    private volatile boolean ispermitted = false;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    //按钮变量
    private Button btnStart;
    private Button btnStop;
    private CompositeDisposable compositeDisposable;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private Handler cameraHandler;
    private String cameraId;
    // camera错误代码常量
    private static final int ERROR_CAMERA_IN_USE = 1;
    private static final int ERROR_MAX_CAMERAS_IN_USE = 2;
    private static final int ERROR_CAMERA_DEVICE = 3;
    private static final int ERROR_CAMERA_DISABLED = 4;
    private static final int ERROR_CAMERA_SERVICE = 5;
    private static final int SURFACE_TIMEOUT = 6;
    private TextureView textureView;
    private SurfaceTexture surfaceTexture;
    private volatile boolean isSurfaceAvailable = false;
    private final Object surfaceLock = new Object();
    private Surface previewSurface = null;
    private HandlerThread streamingHandlerThread;
    private Handler streamingHandler;
    private ImageReader streamingReader;

    public class onStartedCheck{
        private AtomicIntegerArray isStarted = new AtomicIntegerArray(3);
        private static final int TIMEOUT_MILLISECONDS = 142500;
        private ScheduledExecutorService timeoutScheduler;
        public enum StartPart {
            CAMERA(0),
            PUSH(1),
            RECORD(2);

            private final int index;

            StartPart(int index) {
                this.index = index;
            }

            public int getIndex() {
                return index;
            }
        }

        public void onStarted(StartPart part, boolean isSuccess)
        {
            // 使用原子操作设置状态（1表示成功，-1表示失败）
            isStarted.set(part.getIndex(), isSuccess ? 1 : -1);

            if(currentState.get() != WorkflowState.STARTING || !isSuccess){
                // 取消超时检测
                try {
                    timeoutScheduler.shutdownNow();
                } catch (Exception e) {}
                return;
            }
            if(isAllStartedSuccess())
            {
                Timber.tag(TAG).i("工作流开始成功");
                // 取消超时检测
                timeoutScheduler.shutdownNow();
                setState(WorkflowState.WORKING);
                updateButtonState();
            }
        }

        public void reset() {
            if (timeoutScheduler != null) {
                try {
                    timeoutScheduler.shutdownNow();
                } catch (Exception e) {}
            }
            timeoutScheduler = null; // 释放引用

            for (int i = 0; i < isStarted.length(); i++) {
                isStarted.set(i, 0);
            }
        }

        public void onBeforeStart()
        {
            // 确保每次启动都创建新线程池
            if (timeoutScheduler == null || timeoutScheduler.isShutdown()) {
                timeoutScheduler = new ScheduledThreadPoolExecutor(1, r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true); // 设置为守护线程
                    return t;
                });
            }

            // 重置所有状态
            for (int i = 0; i < isStarted.length(); i++) {
                isStarted.set(i, 0); // 0表示未完成
            }

            // 启动超时检测
            timeoutScheduler.schedule(this::interrupt, TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
        }

        private void interrupt()
        {
            currentState.set(WorkflowState.ERROR);
            Timber.tag(TAG).w("启动超时，未完成组件状态:");
            for (int i = 0; i < isStarted.length(); i++) {
                int status = isStarted.get(i);
                Timber.tag(TAG).w("%s: %s",
                        StartPart.values()[i].name(),
                        status == 1 ? "成功" : status == -1 ? "失败" : "未完成"
                );
            }
            Stop();
        }

        // 状态检查
        public boolean isAllStartedSuccess() {
            for (int i = 0; i < isStarted.length(); i++) {
                if (isStarted.get(i) != 1) {
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        compositeDisposable = new CompositeDisposable();
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.textureView);
        textureView.setSurfaceTextureListener(surfaceTextureListener);

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

        textureView = findViewById(R.id.textureView);
        textureView.setVisibility(View.VISIBLE); // 立即可见
        textureView.setSurfaceTextureListener(surfaceTextureListener);

        streamingHandlerThread = new HandlerThread("StreamingImageProcessor");
        streamingHandlerThread.start();
        streamingHandler = new Handler(streamingHandlerThread.getLooper());

        // 初始化按钮状态
        updateButtonState();

        setState(WorkflowState.READY);
    }

    public static boolean setState(WorkflowState newState) {
        // 状态校验
        if (!isValidTransition(newState)) {
            Timber.tag(TAGWorkflowState).w("非法状态转换: %s → %s",
                    currentState.get(), newState);
            return false;
        }
        return currentState.compareAndSet(currentState.get(), newState);
    }

    private static boolean isValidTransition(WorkflowState newState) {
        // 实现状态转换规则校验
        WorkflowState current = currentState.get();
        switch (current) {
            case IDLE: return newState == WorkflowState.READY;
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
        int push_fps = 30;//**现阶段push——fps必须============record——fps**&*&&&&
        int push_initAvgBitrate = 1000;//单位kbps
        int push_initMaxBitrate = 1200;
        int push_initMinBitrate = 400;
        StabilizationMode push_StabilizationMode = StabilizationMode.OIS_ONLY;//防抖
        StabilizationMode record_StabilizationMode = StabilizationMode.HYBRID;
        int record_width = 1920;
        int record_height = 1080;
        int record_bitrate = 2000;//单位kbps
        int record_fps =30;
        String push_Url = "artc://susivvkjnfkj.xyz/supercamera/test?auth_key=1741096726-0-0-ba74d4cf9425560e376b453db36528e0"; // WebRTC 推流地址，可为空？？？

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
                record_width, record_height, record_bitrate, record_fps,
                push_StabilizationMode, record_StabilizationMode) == 0)
        {

        }
        else{
            Timber.tag(TAG).e("工作流开始失败");
        }

        Timber.tag(TAG).i("最终防抖配置 => 推流模式:%s, 录制模式:%s",
                getCurrentPushStabMode().name(),
                getCurrentRecordStabMode().name());

        if(getCurrentPushStabMode() != push_StabilizationMode)
        {
            Timber.tag(TAG).w("推流防抖设置不支持 => 设置:%s, 实际:%s",
                    push_StabilizationMode.name(),
                    getCurrentPushStabMode().name());
        }
        if(getCurrentRecordStabMode() != record_StabilizationMode)
        {
            Timber.tag(TAG).w("录制防抖设置不支持 => 设置:%s, 实际:%s",
                    record_StabilizationMode.name(),
                    getCurrentRecordStabMode().name());
        }
        if(Now_Push_fps.get() != push_fps && Now_Push_fps.get() != -1)
        {
            Timber.tag(TAG).w("推流帧率设置不支持 => 设置:%d, 实际:%d", push_fps, Now_Push_fps.get());
        }
        if(Now_Record_fps.get() != record_fps && Now_Record_fps.get() != -1)
        {
            Timber.tag(TAG).w("录制帧率设置不支持 => 设置:%d, 实际:%d", record_fps, Now_Record_fps.get());
        }
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
                          int record_height, int record_bitrate, int record_fps,
                      StabilizationMode push_StabilizationMode,
                      StabilizationMode record_StabilizationMode)
    {//return 0:成功，1：正在推流，2：码率等设置不合规，3：推流出错，4：recordpath生成出错 ，5：Surface 初始化失败，6：其他错误
        synchronized (startStopLock) {
            if (!setState(WorkflowState.STARTING)) {
                Timber.tag(TAG).e("开始失败，工作流已启动");
                return 1;
            }

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

            Timber.tag(TAG).i("正在开启工作流");
            try {
                // 1.初始化推流服务
                try {
                    videoPusher = new VideoPusher(push_Url, push_width, push_height, push_fps, push_initAvgBitrate);
                    videoPusher.startPush();
                } catch (RuntimeException e) {
                    Timber.tag(TAG).e("推流服务出错：%s", e.getMessage());
                    setState(WorkflowState.ERROR);
                    //Stop();//通过事件总线统一处理
                    return 3;
                }
                //检查 push Surface 有效性
                if (!videoPusher.isSurfaceValid()) {
                    Timber.tag(TAG).e("推流器 Surface 初始化失败");
                    return 5;
                }

                // 2. 初始化录制服务
                videoRecorder = new VideoRecorder();
                videoRecorder.startRecording(record_width, record_height, push_fps, record_bitrate, record_Path); // 提前准备
                // 检查 record Surface 有效性
                if (!videoRecorder.isSurfaceValid()) {
                    Timber.tag(TAG).e("录制器 Surface 初始化失败");
                    return 5;
                }

                // 3. 启动摄像头
                startCamera(push_width, push_height, record_width, record_height, 60,
                        push_StabilizationMode, record_StabilizationMode);

                setupEventHandlers();
                //开始异步回调
                checker.onBeforeStart();
                return 0;
            } catch (Exception e) {
                setState(WorkflowState.ERROR);
                Timber.tag(TAG).e("启动异常: %s", e.getMessage());
                // 释放锁后再执行停止操作
                new Handler(Looper.getMainLooper()).post(() -> Stop());
                return 6;
            }
        }
    }

    public boolean Stop() {
        synchronized (startStopLock) {
            if (!setState(WorkflowState.STOPPING)) {
                Timber.tag(TAG).i("无需重复停止");
                return true;
            }

            try {
                // 1. 停止推流
                if (videoPusher != null) {
                    videoPusher.stopPush();
                    videoPusher = null;
                }
                // 2. 停止摄像头
                stopCamera();
                // 3. 停止录制
                if (videoRecorder != null) {
                    videoRecorder.stopRecording();
                }
                //销毁开始回调
                if(checker != null)
                {
                    checker.reset();
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
/////////////////////////////////////////////////////////////////////////////////////////////
                                  //CAMERA部分//***&&
//SurfaceTexture监听器
private final TextureView.SurfaceTextureListener surfaceTextureListener =
        new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                synchronized (surfaceLock) {
                    surfaceTexture = surface;
                    isSurfaceAvailable = true;
                    surfaceLock.notifyAll();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                // 处理尺寸变化
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                synchronized (surfaceLock) {
                    isSurfaceAvailable = false;
                }
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        };

    // 等待Surface准备方法
    private void waitForSurfaceReady() {
        synchronized (surfaceLock) {
            long endTime = System.currentTimeMillis() + 3000;
            while (!isSurfaceAvailable && System.currentTimeMillis() < endTime) {
                try {
                    surfaceLock.wait(endTime - System.currentTimeMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!isSurfaceAvailable) {
                throw new RuntimeException("Surface准备超时");
            }
        }
    }

    // 分辨率检查
    private boolean checkResolutionSupport(int pushWidth, int pushHeight, int recordWidth, int recordHeight) {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            // 检查推流分辨率（YUV格式）
            Size[] supportedYuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (supportedYuvSizes == null) {
                Timber.e("设备不支持YUV_420_888格式");
                return false;
            }
            Set<Size> yuvSizeSet = new HashSet<>(Arrays.asList(supportedYuvSizes));
            Size pushSize = new Size(pushWidth, pushHeight);
            if (!yuvSizeSet.contains(pushSize)) {
                Timber.e("推流分辨率 %dx%d 不支持YUV格式", pushWidth, pushHeight);
                return false;
            }

            // 检查录制分辨率（Surface格式）
            Size[] supportedSurfaceSizes = map.getOutputSizes(SurfaceTexture.class);
            if (supportedSurfaceSizes == null) {
                Timber.e("设备不支持Surface输出");
                return false;
            }
            Set<Size> surfaceSizeSet = new HashSet<>(Arrays.asList(supportedSurfaceSizes));
            Size recordSize = new Size(recordWidth, recordHeight);
            if (!surfaceSizeSet.contains(recordSize)) {
                Timber.e("录制分辨率 %dx%d 不支持Surface输出", recordWidth, recordHeight);
                return false;
            }

            return true;
        } catch (CameraAccessException e) {
            handleCameraError(ERROR_CAMERA_SERVICE);
            return false;
        } catch (NullPointerException e) {
            Timber.e("摄像头配置异常: %s", e.getMessage());
            return false;
        }
    }

    // 帧率获取方法
    private Range<Integer> getFpsRange(int targetFps) {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

            Range<Integer>[] availableRanges = characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

            // 第一阶段：寻找精确匹配的固定帧率
            Range<Integer> exactRange = null;
            for (Range<Integer> range : availableRanges) {
                if (range.getLower() == targetFps && range.getUpper() == targetFps) {
                    exactRange = range;
                    break; // 优先选择第一个精确匹配项
                }
            }
            if (exactRange != null) return exactRange;

            // 第二阶段：选择包含目标帧率的最佳范围
            Range<Integer> bestDynamicRange = getIntegerRange(targetFps, availableRanges);
            if (bestDynamicRange != null) return bestDynamicRange;

            // 第三阶段：降级选择最低可用帧率
            int minFps = Integer.MAX_VALUE;
            for (Range<Integer> range : availableRanges) {
                minFps = Math.min(minFps, range.getLower());
            }
            return new Range<>(Math.max(minFps, 15), Math.max(minFps, 15)); // 保证最低15fps
        } catch (CameraAccessException e) {
            handleCameraError(ERROR_CAMERA_SERVICE);
            return new Range<>(30, 30); // 默认安全值
        }
    }

    @Nullable
    private static Range<Integer> getIntegerRange(int targetFps, Range<Integer>[] availableRanges) {
        Range<Integer> bestDynamicRange = null;
        for (Range<Integer> range : availableRanges) {
            if (range.getUpper() >= targetFps && range.getLower() <= targetFps) {
                if (bestDynamicRange == null
                        || (range.getUpper() > bestDynamicRange.getUpper()) // 优先更高上限
                        || (range.getUpper() == bestDynamicRange.getUpper()
                        && range.getLower() > bestDynamicRange.getLower())) { // 次优先更高下限
                    bestDynamicRange = range;
                }
            }
        }
        return bestDynamicRange;
    }


    // 核心摄像头启动方法
    private void startCamera(int pushWidth, int pushHeight, int recordWidth,
                             int recordHeight, int targetFps,
                             StabilizationMode pushStabMode,
                             StabilizationMode recordStabMode) {
        try {
            waitForSurfaceReady(); // 确保Surface就绪
        } catch (RuntimeException e) {
            handleCameraError(SURFACE_TIMEOUT);
            return;
        }
        // 检查 textureView 是否已被释放
        if (textureView == null) {
            textureView = findViewById(R.id.textureView);
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }

        // 初始化摄像头线程
        HandlerThread cameraThread = new HandlerThread("CameraBackground");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

            // 查找后置摄像头
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }

            if (cameraId == null) {
                handleCameraError(ERROR_CAMERA_DEVICE);
                return;
            }

            // 检查硬件支持
            if (!checkResolutionSupport(pushWidth, pushHeight, recordWidth, recordHeight)) {
                handleCameraError(ERROR_CAMERA_DEVICE);
                return;
            }

            // 打开摄像头
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice device) {
                    cameraDevice = device;
                    configureSession(pushWidth, pushHeight, recordWidth, recordHeight, targetFps,
                            pushStabMode, recordStabMode);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice device) {
                    device.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice device, int error) {
                    handleCameraError(error);
                    device.close();
                    cameraDevice = null;
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            handleCameraError(ERROR_CAMERA_SERVICE);
        } catch (SecurityException e) {
            handleCameraError(ERROR_CAMERA_DISABLED);
        }
    }

    // 配置摄像头会话
    private void configureSession(int pushWidth, int pushHeight, int recordWidth,
                                  int recordHeight, int targetFps,
                                  StabilizationMode pushStabMode,
                                  StabilizationMode recordStabMode) {
        previewSurface = null;

        try {
            // 准备Surface列表
            List<Surface> surfaces = new ArrayList<>();

            // 预览Surface
            synchronized (surfaceLock) {
                if (surfaceTexture == null) {
                    handleCameraError(ERROR_CAMERA_DEVICE);
                    return;
                }

                surfaceTexture.setDefaultBufferSize(pushWidth, pushHeight);
                previewSurface = new Surface(surfaceTexture);
                surfaces.add(previewSurface);
            }

            // 推流Surface (ImageReader)
             streamingReader = ImageReader.newInstance(
                    pushWidth, pushHeight, ImageFormat.YUV_420_888, 3);
            surfaces.add(streamingReader.getSurface());

            // 设置图像可用监听器
            streamingReader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) { // 使用try-with-resources确保自动关闭
                    if (image != null && videoPusher != null) {
                        try {
                            byte[] yuvData = YUVConverter.convertYUV420888ToYUV420P(image);
                            if (yuvData != null) { // 添加空校验
                                videoPusher.getStreamingQueue().onNext(yuvData);
                            }
                        } catch (IllegalArgumentException e) {
                            Timber.tag(TAGCamera).e(e, "帧格式转换出错");
                        }
                    }
                } catch (Exception e) {
                    Timber.tag(TAGCamera).e(e, "图像处理异常");
                }
            }, streamingHandler);


            // 录制Surface
            try {
                Surface recordingSurface = videoRecorder.getInputSurface();
                surfaces.add(recordingSurface);
            } catch (IllegalStateException e) {
                handleCameraError(SURFACE_TIMEOUT);
                return;
            }

            // 创建CaptureRequest构建器
            CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            // 添加预览Surface
            if (previewSurface != null) {
                requestBuilder.addTarget(previewSurface);
            } else {
                handleCameraError(ERROR_CAMERA_DEVICE);
                return;
            }

            // 配置公共参数
            Range<Integer> fpsRange = getFpsRange(targetFps);
            requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);

            // 配置防抖模式
            configureStabilization(requestBuilder, pushStabMode, recordStabMode);

            // 添加所有Surface
            for (Surface surface : surfaces) {
                requestBuilder.addTarget(surface);
            }

            // 预览方向
            runOnUiThread(() -> textureView.setRotation(270));

            // 计算正确的宽高比
            float aspectRatio = (float) pushHeight / pushWidth;

            // 更新布局参数
            textureView.post(() -> {
                ViewGroup parent = (ViewGroup) textureView.getParent();
                int viewWidth = parent.getWidth();
                int viewHeight = parent.getHeight();

                // 计算适配后的尺寸
                int targetWidth, targetHeight;
                targetWidth = viewHeight;
                targetHeight = (int) (viewHeight / aspectRatio);

                // 设置居中布局参数
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        targetWidth,
                        targetHeight
                );
                params.gravity = Gravity.CENTER;
                textureView.setLayoutParams(params);

                    // 延迟显示确保布局完成
                    new Handler().postDelayed(() ->
                            textureView.setVisibility(View.VISIBLE), 100);
                });


            // 创建摄像头会话
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    cameraCaptureSession = session;
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
                        Now_Push_fps.set(fpsRange.getUpper());
                        Now_Record_fps.set(fpsRange.getUpper());

                        Timber.tag(TAGCamera).i("摄像头初始化成功");
                        checker.onStarted(onStartedCheck.StartPart.CAMERA, true);
                    } catch (CameraAccessException e) {
                        handleCameraError(ERROR_CAMERA_SERVICE);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    handleCameraError(ERROR_CAMERA_DEVICE);
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            handleCameraError(ERROR_CAMERA_SERVICE);
        }
    }

    // 防抖配置方法
    private void configureStabilization(CaptureRequest.Builder builder,
                                        StabilizationMode pushMode,
                                        StabilizationMode recordMode) {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

            // 获取设备支持情况
            int[] videoStabModes = characteristics.get(
                    CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            boolean eisSupported = Arrays.stream(videoStabModes).anyMatch(
                    m -> m == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);

            int[] oisModes = characteristics.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            boolean oisSupported = Arrays.stream(oisModes).anyMatch(
                    m -> m == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);

            // OIS全局配置（任一模式请求即开启）
            boolean enableOIS = (pushMode == StabilizationMode.OIS_ONLY ||
                    pushMode == StabilizationMode.HYBRID ||
                    recordMode == StabilizationMode.OIS_ONLY
                    || recordMode == StabilizationMode.HYBRID) && oisSupported;


            // EIS全局配置（任一模式请求即开启）
            boolean enableEIS = (pushMode == StabilizationMode.EIS_ONLY ||
                    pushMode == StabilizationMode.HYBRID ||
                    recordMode == StabilizationMode.EIS_ONLY
                    || recordMode == StabilizationMode.HYBRID) && eisSupported;

            // 应用配置
            applyStabilization(builder, enableOIS , enableEIS);

            // 记录实际生效模式
            nowPushStabMode.set(calcEffectiveMode(enableOIS , enableEIS));
            nowRecordStabMode.set(calcEffectiveMode(enableOIS, enableEIS));

        } catch (CameraAccessException e) {
            handleCameraError(ERROR_CAMERA_SERVICE);
        }
    }

    private StabilizationMode calcEffectiveMode(boolean oisEnabled, boolean eisEnabled) {
        if (oisEnabled && eisEnabled) return StabilizationMode.HYBRID;
        if (oisEnabled) return StabilizationMode.OIS_ONLY;
        if (eisEnabled) return StabilizationMode.EIS_ONLY;
        return StabilizationMode.OFF;
    }

    private void applyStabilization(CaptureRequest.Builder builder,
                                    boolean enableOIS,
                                    boolean enableEIS) {
        // OIS配置（全局生效）
        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                enableOIS ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                        : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);

        // OIS配置（全局生效）
        if (enableEIS) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        }
    }

    // 错误处理方法
    private void handleCameraError(int errorCode) {
        String errorDesc;
        switch (errorCode) {
            case ERROR_CAMERA_IN_USE:
                errorDesc = "摄像头被其他进程占用";
                break;
            case ERROR_MAX_CAMERAS_IN_USE:
                errorDesc = "达到最大摄像头使用数";
                break;
            case ERROR_CAMERA_DEVICE:
                errorDesc = "摄像头硬件故障";
                break;
            case ERROR_CAMERA_DISABLED:
                errorDesc = "摄像头被管理员禁用";
                break;
            case ERROR_CAMERA_SERVICE:
                errorDesc = "摄像头服务异常";
                break;
            case SURFACE_TIMEOUT:
                errorDesc = "Surface准备超时";
                break;
            default:
                errorDesc = "未知错误";
        }

        Timber.tag(TAGCamera).e("摄像头错误[%d]: %s", errorCode, errorDesc);
        setState(WorkflowState.ERROR);
        checker.onStarted(onStartedCheck.StartPart.CAMERA, false);
        Stop();
    }

    // 停止摄像头
    private void stopCamera() {
        try {
            runOnUiThread(() -> {
                if (textureView != null) {
                    textureView.setVisibility(View.GONE);
                }
            });

            // 先停止回调处理
            if (streamingReader != null) {
                streamingReader.setOnImageAvailableListener(null, null); // 移除监听器
            }

            // 按正确顺序释放资源
            if (cameraCaptureSession != null) {
                cameraCaptureSession.abortCaptures();
                cameraCaptureSession.close();
                cameraCaptureSession = null;
            }

            if (streamingReader != null) {
                streamingReader.close();
                streamingReader = null;
            }

            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }

            // 最后停止线程
            if (streamingHandlerThread != null) {
                streamingHandlerThread.quitSafely();
                try {
                    streamingHandlerThread.join(500);
                } catch (InterruptedException e) {
                    Timber.e("等待线程停止异常");
                }
                streamingHandlerThread = null;
                streamingHandler = null;
            }

            if (cameraHandler != null) {
                cameraHandler.getLooper().quitSafely();
                cameraHandler = null;
            }
        } catch (Exception e) {
            Timber.e("摄像头关闭失败:%s", e.getMessage());
        }
    }


    ////////////////////////////////////////////////////////////////////////////////////////
                                  //权限请求部分//***&&
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

    /////////////////////////////////////////////////////////////////////////////////////////
                                  //推流与录制事件处理部分//***&&

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
                        case CUR_BITRATE:
                            handleBitrateReport(report);
                            break;
                        case ERROR:
                            handleError(report);
                            break;
                        case CONNECTION_ERROR:
                            handleReconnectError(report);
                            break;
                        case NETWORK_DELAY:
                            handleNetworkDelay(report);
                            break;
                        case RECONNECTION_SUCCESS:
                            handleReconnectionSuccess(report);
                            break;
                    }
                });
        compositeDisposable.add(disposable);

        Disposable recorddisposable = videoRecorder.getReportSubject()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(report -> {
                    switch (report.type) {
                        case ERROR:
                            handleRecordError(report);
                            break;
                        case STARTED:
                            handleRecordStart(report);
                            break;
                    }
                });
        compositeDisposable.add(recorddisposable);
    }

    private void handlePushStart(VideoPusher.PushReport report) {
        int initialAvg = report.avgBitrate;
        int initialMax = report.maxBitrate;
        int initialMin = report.minBitrate;
        String url = report.message;
        Timber.tag(TAG).i("推流已开始,平均码率：%dkbps,最大码率:%dbps,最小码率:%dbps.推流url%s"
        ,initialAvg,initialMax,initialMin,url);

        checker.onStarted(onStartedCheck.StartPart.PUSH, true);
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
        checker.onStarted(onStartedCheck.StartPart.PUSH, false);
        Stop();
    }

    private void handleReconnectError(VideoPusher.PushReport report) {
        Timber.tag(TAG).e("重连异常");
    }

    private void handlePusherDestroy(VideoPusher.PushReport report) {
        Timber.tag(TAG).i("推流引擎已销毁");
    }

    private void handleNetworkDelay(VideoPusher.PushReport report) {
        Timber.tag(TAG).i("网络延迟rtt：%dms",report.code);
    }

    private void handleUrlChange(VideoPusher.PushReport report) {
        Timber.tag(TAG).i("推流改变，url：%s",report.message);
    }

    private void handleReconnectionSuccess(VideoPusher.PushReport report) {
        Timber.tag(TAG).e("重连成功");
    }

    private void handleRecordError(VideoRecorder.RecordReport report)
    {
        String errormsg = report.message;
        int errorcode = report.code;
        Timber.tag(TAG).e("录制出错,代码：%d,消息：%s",errorcode,errormsg);
        setState(WorkflowState.ERROR);
        checker.onStarted(onStartedCheck.StartPart.RECORD, false);
        Stop();
    }

    private void handleRecordStart(VideoRecorder.RecordReport report)
    {
        Timber.tag(TAG).i("录制启动成功");
        checker.onStarted(onStartedCheck.StartPart.RECORD, true);
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
        // 获取DCIM目录下的自定义文件夹
        File recordsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        //File recordsDir = new File(dcimDir, "SuperRecords");
        //if (!recordsDir.exists() && !recordsDir.mkdirs()) {
            //Timber.e("创建目录失败");
            //return null;
        //}
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
        //生成新序号（三位数格式）
        String newNumber = String.format(Locale.CHINA, "%03d", maxNumber + 1);
        //组合完整路径
        return new File(recordsDir,
                "supercamera_record-" + dateStr + "_#" + newNumber + ".mp4"
        ).getAbsolutePath();
    }

    public StabilizationMode getCurrentPushStabMode() {
        return nowPushStabMode.get();
    }

    public StabilizationMode getCurrentRecordStabMode() {
        return nowRecordStabMode.get();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Stop();
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