package com.example.supercamera.CameraController;

import static androidx.core.content.ContextCompat.getSystemService;

import static com.example.supercamera.CameraController.CameraConfig.Camera_Facing_BACK;
import static com.example.supercamera.CameraController.CameraConfig.Camera_Facing_Front;
import static com.example.supercamera.CameraController.CameraConfig.Stab_EIS_ONLY;
import static com.example.supercamera.CameraController.CameraConfig.Stab_HYBRID;
import static com.example.supercamera.CameraController.CameraConfig.Stab_OFF;
import static com.example.supercamera.CameraController.CameraConfig.Stab_OIS_ONLY;
import static com.example.supercamera.CameraController.CameraState.CameraStateEnum.CLOSING;
import static com.example.supercamera.CameraController.CameraState.CameraStateEnum.CONFIGURE;
import static com.example.supercamera.CameraController.CameraState.CameraStateEnum.DESTROYED;
import static com.example.supercamera.CameraController.CameraState.CameraStateEnum.ERROR;
import static com.example.supercamera.CameraController.CameraState.CameraStateEnum.OPENING;
import static com.example.supercamera.CameraController.CameraState.CameraStateEnum.PREVIEWING;
import static com.example.supercamera.CameraController.CameraState.CameraStateEnum.READY;
import static com.example.supercamera.CameraController.ErrorCode.ERROR_CAMERA_Configure;
import static com.example.supercamera.CameraController.ErrorCode.ERROR_CAMERA_DEVICE;
import static com.example.supercamera.CameraController.ErrorCode.ERROR_CAMERA_SERVICE;
import static com.example.supercamera.CameraController.ErrorCode.ERROR_OpenCamera;
import static com.example.supercamera.CameraController.ErrorCode.ERROR_Param_Illegal;
import static com.example.supercamera.CameraController.ErrorCode.ERROR_StopCamera;
import static com.example.supercamera.MyException.MyException.ILLEGAL_ARGUMENT;
import static com.example.supercamera.MyException.MyException.ILLEGAL_STATE;
import static com.example.supercamera.MyException.MyException.RUNTIME_ERROR;
import static com.example.supercamera.StreamPusher.PushStats.TimeStamp.TimeStampStyle.Captured;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.supercamera.MyException.MyException;
import com.example.supercamera.StreamPusher.PushStats.TimeStamp;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.subjects.PublishSubject;
import timber.log.Timber;

public class CameraImpl implements CameraController {
    private final CameraState state = new CameraState();
    private CameraConfig mConfig;
    private final AtomicReference<CameraListener> mListenerRef = new AtomicReference<>();
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private final String TAG = "CameraController";
    private String cameraId = null;
    private Range<Integer> fpsRange;
    private int finalStabMode;
    private final Context context;
    private final Object publicLock = new Object();
    private final Object errorLock = new Object();
    private CameraCaptureSession cameraCaptureSession;
    private final ExecutorService reportExecutor = Executors.newSingleThreadExecutor();
    private final PublishSubject<TimeStamp> reportQueue = PublishSubject.create();

    public CameraImpl(Context context) {
        this.context = context;
    }

    // throw MyException
    @Override
    public void configure(CameraConfig config) {
        synchronized (publicLock) {
            if (state.getState() != READY && state.getState() == CONFIGURE) {
                String msg = String.format("configure failed, current state: %s",
                        state.getState().toString());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_CAMERA_Configure, msg);
            }

            state.setState(CONFIGURE);
            this.mConfig = config;

            // 获取cameraId
            cameraId = getCameraId(config.cameraFacing);
            if (cameraId == null) {
                Timber.tag(TAG).e("找不到符合的camera");
                throw throwException(RUNTIME_ERROR, ERROR_CAMERA_DEVICE, "找不到符合的camera");
            }

            // 检查分辨率
            if(!checkResolutionSupport(config.previewSize, config.recordSize)) {
                throw throwException(RUNTIME_ERROR, ERROR_CAMERA_Configure, "分辨率不支持");
            }
            // 获取fpsRange
            fpsRange = getFpsRange(config.fps);
            // 获取防抖模式
            finalStabMode = getStabMode(config.stabilizationMode);
        }
    }

    @Override
    public PublishSubject<TimeStamp> getTimeStampQueue() {
        synchronized (publicLock) {
            return reportQueue;
        }
    }

    @Override
    public void openCamera(List<Surface> surfaces) {
        if (state.getState() != CONFIGURE) {
            String msg = String.format("openCamera failed, current state: %s",
                    state.getState().toString());
            Timber.tag(TAG).e(msg);
            throw throwException(ILLEGAL_STATE, ERROR_OpenCamera, msg);
        }
        state.setState(OPENING);

        Executors.newSingleThreadExecutor().submit(() -> {
            synchronized (publicLock) {
                // 初始化摄像头线程
                HandlerThread cameraThread = new HandlerThread("CameraBackground");
                cameraThread.start();
                cameraHandler = new Handler(cameraThread.getLooper());

                try {
                    CameraManager manager = getSystemService(context, CameraManager.class);
                    // 打开摄像头
                    manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(@NonNull CameraDevice device) {
                            cameraDevice = device;
                            configureSession(surfaces, fpsRange, finalStabMode);
                        }

                        @Override
                        public void onDisconnected(@NonNull CameraDevice device) {
                            device.close();
                            cameraDevice = null;
                        }

                        @Override
                        public void onError(@NonNull CameraDevice device, int error) {
                            String msg = String.format("摄像头出错，错误码：%d", error);
                            device.close();
                            cameraDevice = null;
                            Timber.tag(TAG).e(msg);
                            notifyError(RUNTIME_ERROR, ERROR_OpenCamera, msg);
                        }
                    }, cameraHandler);
                } catch (CameraAccessException | SecurityException e) {
                    notifyError(RUNTIME_ERROR, ERROR_OpenCamera, e.getMessage());
                }
            }
        });
    }

    @Override
    public void stop() {
        synchronized (publicLock) {
            if(state.getState() == READY || state.getState() == CONFIGURE) return;

            if (state.getState() != PREVIEWING) {
                String msg = String.format("stop failed, current state: %s", state.getState().toString());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_StopCamera, msg);
            }

            state.setState(CLOSING);

            try {
                // 释放资源
                if (cameraCaptureSession != null) {
                    cameraCaptureSession.abortCaptures();
                    cameraCaptureSession.close();
                    cameraCaptureSession = null;
                }

                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                }

                if (cameraHandler != null) {
                    cameraHandler.getLooper().quitSafely();
                    cameraHandler = null;
                }
            } catch (Exception e) {
                Timber.tag(TAG).e("摄像头关闭失败:%s", e.getMessage());
            }finally {
                state.setState(READY);
            }
        }
    }

    @Override
    public void destroy() {
        synchronized (publicLock) {
            try{
                stop();
                cleanRecourse();
                state.setState(DESTROYED);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void setCameraListener(CameraListener listener) {
        synchronized (publicLock) {
            mListenerRef.set(listener);
        }
    }

    private String getCameraId(int myCameraFacing) {
        try {
            CameraManager manager = getSystemService(context, CameraManager.class);

            int cameraFACING = switch (myCameraFacing) {
                case Camera_Facing_BACK -> CameraCharacteristics.LENS_FACING_BACK;
                case Camera_Facing_Front -> CameraCharacteristics.LENS_FACING_FRONT;
                default -> -1;
            };

            if (cameraFACING == -1) {
                Timber.tag(TAG).e("cameraFACING参数无效");
                notifyError(ILLEGAL_ARGUMENT, ERROR_Param_Illegal,
                        "cameraFACING参数无效");
            }

            // 查找摄像头
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == cameraFACING) {
                    return id;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // 分辨率检查
    private boolean checkResolutionSupport(Size previewSize, Size recordSize) {
        try {
            CameraManager manager = getSystemService(context, CameraManager.class);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            // 检查录制分辨率（Surface格式）
            Size[] supportedSurfaceSizes = map.getOutputSizes(SurfaceTexture.class);
            if (supportedSurfaceSizes == null) {
                Timber.e("设备不支持Surface输出");
                return false;
            }
            Set<Size> surfaceSizeSet = new HashSet<>(Arrays.asList(supportedSurfaceSizes));
            if (!surfaceSizeSet.contains(previewSize)) {
                Timber.e("预览分辨率 %dx%d 不支持Surface输出",
                        previewSize.getWidth(), previewSize.getHeight());
                return false;
            }

            if (!surfaceSizeSet.contains(recordSize)) {
                Timber.e("录制分辨率 %dx%d 不支持Surface输出",
                        recordSize.getWidth(), recordSize.getHeight());
                return false;
            }

            return true;
        } catch (CameraAccessException | NullPointerException e) {
            Timber.e("摄像头配置异常: %s", e.getMessage());
            throw throwException(RUNTIME_ERROR, ERROR_CAMERA_Configure,
                    e.getMessage());
        }
    }

    // 帧率获取方法
    private Range<Integer> getFpsRange(int targetFps) {
        try {
            CameraManager manager = getSystemService(context, CameraManager.class);
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
                        || (range.getUpper().equals(bestDynamicRange.getUpper())
                        && range.getLower() > bestDynamicRange.getLower())) { // 次优先更高下限
                    bestDynamicRange = range;
                }
            }
        }
        return bestDynamicRange;
    }

    private int getStabMode(int stabMode) {
        try {
            CameraManager manager = getSystemService(context, CameraManager.class);
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

            boolean oisON = oisSupported && (stabMode == Stab_HYBRID || stabMode == Stab_OIS_ONLY);
            boolean eisON = eisSupported && (stabMode == Stab_HYBRID || stabMode == Stab_EIS_ONLY);

            if(eisON && oisON) {
                return Stab_HYBRID;
            } else if (eisON) {
                return Stab_EIS_ONLY;
            } else if (oisON) {
                return Stab_OIS_ONLY;
            }else {
                return Stab_OFF;
            }
        } catch (CameraAccessException e) {
            Timber.e("摄像头配置异常: %s", e.getMessage());
            throw throwException(RUNTIME_ERROR, ERROR_CAMERA_Configure,
                    e.getMessage());
        }
    }

    // 配置摄像头会话
    private void configureSession(List<Surface> surfaces,
                                  Range<Integer> fpsRange, int stabMode) {
        try {
            // 创建CaptureRequest构建器
            CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);

            // 配置防抖模式
            applyStabilization(requestBuilder, finalStabMode);

            // 添加所有Surface
            for (Surface surface : surfaces) {
                requestBuilder.addTarget(surface);
            }

            // 创建摄像头会话
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    cameraCaptureSession = session;
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);

                        Timber.tag(TAG).i("摄像头初始化成功");
                        state.setState(PREVIEWING);

                        CameraListener listener = mListenerRef.get();
                        if(listener != null) {
                            reportExecutor.submit(() -> listener.onCameraOpened(mConfig.previewSize, mConfig.recordSize,
                                    fpsRange.getUpper(), finalStabMode));
                        }

                        cameraCaptureSession.setRepeatingRequest(requestBuilder.build(),
                                new CameraCaptureSession.CaptureCallback() {
                                    @Override
                                    public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                                   @NonNull CaptureRequest request,
                                                                   @NonNull TotalCaptureResult result) {
                                        // 记录采集时间戳（纳秒）
                                        long captureTimeNs = System.nanoTime();
                                        reportQueue.onNext(new TimeStamp(
                                                Captured, captureTimeNs));
                                    }
                                }, cameraHandler);
                    } catch (CameraAccessException e) {
                        notifyError(RUNTIME_ERROR, ERROR_CAMERA_SERVICE, e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    notifyError(RUNTIME_ERROR, ERROR_OpenCamera,
                            "ConfigureFailed");
                }

            }, cameraHandler);
        } catch (CameraAccessException e) {
            notifyError(RUNTIME_ERROR, ERROR_OpenCamera, e.getMessage());
        }
    }

    private void applyStabilization(CaptureRequest.Builder builder,
                                    int stabMode) {
        boolean enableOIS = false;
        boolean enableEIS = false;
        switch (stabMode) {
            case Stab_EIS_ONLY -> enableEIS = true;
            case Stab_OIS_ONLY -> enableOIS = true;
            case Stab_HYBRID -> enableEIS = enableOIS = true;
        }

        // OIS配置
        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                enableOIS ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                        : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);

        // EIS配置
        if (enableEIS) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        }
    }

    private void cleanRecourse() {
        try {
            if (reportExecutor != null) {
                reportExecutor.shutdownNow();
            }

            if (reportQueue != null) {
                reportQueue.onComplete();
            }
        } catch (Exception ignored) {}
    }

    private void notifyError(int type,int code, String message) {
        if (state.getState() != CONFIGURE &&
                state.getState() != OPENING &&
                state.getState() != PREVIEWING &&
                state.getState() != CLOSING) return;

        state.setState(ERROR);

        Executors.newSingleThreadExecutor().submit(() -> {
            synchronized (errorLock) {
                switch (code) {
                    case ERROR_CAMERA_SERVICE -> stopCamera();
                    case ERROR_OpenCamera -> stopOnlyDevice();
                }

                CameraListener listener = mListenerRef.get();
                if(listener != null) {
                    listener.onError(throwException(type,code,message));
                }
            }
        });
    }

    private void stopCamera() {
        try {
            // 释放资源
            if (cameraCaptureSession != null) {
                cameraCaptureSession.abortCaptures();
                cameraCaptureSession.close();
                cameraCaptureSession = null;
            }

            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }

            if (cameraHandler != null) {
                cameraHandler.getLooper().quitSafely();
                cameraHandler = null;
            }
        } catch (Exception e) {
            Timber.tag(TAG).e("摄像头关闭失败:%s", e.getMessage());
        }
    }

    private void stopOnlyDevice() {
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }

            if (cameraHandler != null) {
                cameraHandler.getLooper().quitSafely();
                cameraHandler = null;
            }
        } catch (Exception e) {
            Timber.tag(TAG).e("摄像头关闭失败:%s", e.getMessage());
        }
    }

    private MyException throwException(int type, int code, String message) {
        return new MyException(this.getClass().getPackageName(),
                type, code, message);
    }
}
