package com.example.supercamera.VideoWorkflow;

import static com.example.supercamera.MyException.MyException.ILLEGAL_STATE;
import static com.example.supercamera.MyException.MyException.RUNTIME_ERROR;
import static com.example.supercamera.VideoWorkflow.ErrorCode.ERROR_Camera;
import static com.example.supercamera.VideoWorkflow.ErrorCode.ERROR_Recorder;
import static com.example.supercamera.VideoWorkflow.ErrorCode.ERROR_Streamer;
import static com.example.supercamera.VideoWorkflow.ErrorCode.ERROR_Workflow_CONFIG;
import static com.example.supercamera.VideoWorkflow.ErrorCode.ERROR_Workflow_START;
import static com.example.supercamera.VideoWorkflow.ErrorCode.ERROR_Workflow_STOP;
import static com.example.supercamera.VideoWorkflow.ErrorCode.Start_TimeOUT;
import static com.example.supercamera.VideoWorkflow.ErrorCode.Surface_TimeOUT;
import static com.example.supercamera.VideoWorkflow.WorkflowState.WorkflowStateEnum.CONFIGURED;
import static com.example.supercamera.VideoWorkflow.WorkflowState.WorkflowStateEnum.DESTROYED;
import static com.example.supercamera.VideoWorkflow.WorkflowState.WorkflowStateEnum.ERROR;
import static com.example.supercamera.VideoWorkflow.WorkflowState.WorkflowStateEnum.READY;
import static com.example.supercamera.VideoWorkflow.WorkflowState.WorkflowStateEnum.STARTING;
import static com.example.supercamera.VideoWorkflow.WorkflowState.WorkflowStateEnum.STOPPING;
import static com.example.supercamera.VideoWorkflow.WorkflowState.WorkflowStateEnum.WORKING;
import static com.example.supercamera.VideoWorkflow.onSartCheck.OnStartCheck.StartPart.CAMERA;
import static com.example.supercamera.VideoWorkflow.onSartCheck.OnStartCheck.StartPart.RECORD;
import static com.example.supercamera.VideoWorkflow.onSartCheck.OnStartCheck.StartPart.STREAM;

import com.example.supercamera.CameraController.CameraController;
import com.example.supercamera.CameraController.CameraImpl;
import com.example.supercamera.CameraController.CameraListener;
import com.example.supercamera.MyException.MyException;
import com.example.supercamera.StreamPusher.PushStats.PushStatsInfo;
import com.example.supercamera.VideoRecorder.RecorderListener;
import com.example.supercamera.VideoRecorder.VideoRecorder;
import com.example.supercamera.VideoRecorder.VideoRecorderImpl;
import com.example.supercamera.VideoStreamer.StreamListener;
import com.example.supercamera.VideoStreamer.VideoStreamer;
import com.example.supercamera.VideoStreamer.VideoStreamerImpl;
import com.example.supercamera.VideoWorkflow.onSartCheck.OnStartCheck;
import com.example.supercamera.VideoWorkflow.onSartCheck.startListener;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import timber.log.Timber;

public class VideoWorkflowImpl implements VideoWorkflow {
    // surface管理
    private class SurfaceManger{
        private Surface pushSurface = null;
        private Surface recordSurface = null;
        private SurfaceTexture surfTexture = null;
        private final Object surfLock = new Object();
        public void reset() {
            synchronized (surfLock) {
                pushSurface = null;
                recordSurface = null;
            }
        }

        public boolean waitSurfaceReady(Long timeMILLISECONDS) {
            synchronized (surfLock) {
                if (checkSurface()) return true;

                try {
                    surfLock.wait(timeMILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return checkSurface();
            }
        }

        public void reportPushSurface(Surface pushSurface) {
            synchronized (surfLock) {
                this.pushSurface = pushSurface;
                if(checkSurface()) surfLock.notifyAll();
            }
        }

        public void reportRecordSurface(Surface recordSurface) {
            synchronized (surfLock) {
                this.recordSurface = recordSurface;
                if(checkSurface()) surfLock.notifyAll();
            }
        }

        // 在SurfaceTexture被destroy时输入null表示not ready
        public void reportSurfaceTexture(SurfaceTexture surfTexture) {
            synchronized (surfLock) {
                this.surfTexture = surfTexture;
                if(checkSurface()) surfLock.notifyAll();
            }
        }

        public Surface getPushSurface() {
            synchronized (surfLock) {
                return pushSurface;
            }
        }

        public Surface getRecordSurface() {
            synchronized (surfLock) {
                return recordSurface;
            }
        }

        public SurfaceTexture getSurfaceTexture() {
            synchronized (surfLock) {
                return surfTexture;
            }
        }

        private boolean checkSurface() {
            return pushSurface != null && recordSurface != null
                    && surfTexture != null;
        }
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    surfaceManger.reportSurfaceTexture(surface);
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    // 处理尺寸变化
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    surfaceManger.reportSurfaceTexture(null);
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                }
            };

    private final startListener startListener = new startListener() {
        @Override
        public void onStart() {
            reportExecutor.submit(() -> {
                Timber.tag(TAG).i("工作流开始成功");
                state.setState(WORKING);
                // 报告最终配置
                mListener.onStart(mPreviewSize, mRecordSize, mFps, mStabMode);
            });
        }

        @Override
        public void onStartTimeOUT() {
            Timber.tag(TAG).e("工作流启动超时");
            notifyError(null, RUNTIME_ERROR, Start_TimeOUT, "工作流启动超时");
        }
    };

    private final WorkflowState state = new WorkflowState();
    private WorkflowConfig mConfig;
    private WorkflowListener mListener;
    private final VideoStreamer streamer;
    private final VideoRecorder recorder;
    private final CameraController camera;
    private final SurfaceManger surfaceManger = new SurfaceManger();
    private final OnStartCheck checker = new OnStartCheck(startListener);
    private final TextureView textureView;
    private final String TAG = "VideoWorkflow";
    private final Object errorLock = new Object();
    private final Object publicLock = new Object();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService reportExecutor = Executors.newSingleThreadExecutor();
    // 开始的超时时间
    private final Long startTimeOutMilliseconds = 3_000L;
    private final Long waitForSurfaceTimeMilliseconds = 1_500L;
    // 最终配置参数
    private Size mPreviewSize;
    private Size mRecordSize;
    private int mFps;
    private int mStabMode;

    public VideoWorkflowImpl(Context context, TextureView textureView) {
        this.streamer = new VideoStreamerImpl();
        this.recorder = new VideoRecorderImpl();
        this.camera = new CameraImpl(context);
        this.textureView = textureView;
        textureView.setSurfaceTextureListener(surfaceTextureListener);
    }

    @Override
    public void configure(WorkflowConfig config) {
        synchronized (publicLock) {
            if (state.getState() != READY && state.getState() != CONFIGURED) {
                String msg = String.format("configure failed, current state: %s",
                        state.getState().toString());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_Workflow_CONFIG, msg);
            }

            this.mConfig = config;

            // 重置surfaceManger
            surfaceManger.reset();

            camera.configure(config.cameraConfig);
            recorder.configure(config.recorderConfig);
            streamer.configure(config.streamConfig);

            state.setState(CONFIGURED);
        }
    }

    @Override
    public void start() {
        if (state.getState() != CONFIGURED) {
            String msg = String.format("start failed, current state: %s",
                    state.getState().toString());
            Timber.tag(TAG).e(msg);
            throw throwException(ILLEGAL_STATE, ERROR_Workflow_START, msg);
        }

        Executors.newSingleThreadExecutor().submit(() -> {
            synchronized (publicLock) {
                try {
                    checker.setUpChecker(startTimeOutMilliseconds);

                    // 启动录制
                    recorder.start();
                    // 启动推流
                    streamer.start();

                    if (!surfaceManger.waitSurfaceReady(waitForSurfaceTimeMilliseconds)) {
                        notifyError(null, RUNTIME_ERROR, Surface_TimeOUT,
                                "surface准备超时");
                    }

                    // 准备surfacesList
                    List<Surface> surfaces = new ArrayList<>();
                    surfaces.add(surfaceManger.getPushSurface());
                    surfaces.add(surfaceManger.getRecordSurface());
                    SurfaceTexture surfaceTexture = surfaceManger.getSurfaceTexture();
                    surfaceTexture.setDefaultBufferSize(mConfig.pushSize.getWidth(),
                            mConfig.pushSize.getHeight());
                    surfaces.add(new Surface(surfaceTexture));

                    // 打开摄像头
                    camera.openCamera(surfaces);

                    // 更新UI
                    // 预览方向
                    mMainHandler.post(() -> textureView.setRotation(270));

                    // 计算正确的宽高比
                    float aspectRatio = (float) mConfig.pushSize.getHeight() /
                            mConfig.pushSize.getWidth();

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

                    state.setState(WORKING);
                } catch (MyException e) {
                    notifyError(e,0,ERROR_Workflow_START,null);
                }
            }
        });
    }

    @Override
    public void stop() {
        if (state.getState() != WORKING) {
            String msg = String.format("stop failed, current state: %s",
                    state.getState().toString());
            Timber.tag(TAG).e(msg);
            throw throwException(ILLEGAL_STATE, ERROR_Workflow_STOP, msg);
        }

        synchronized (publicLock) {
            try{
                // 停止推流
                streamer.stop();
                // 停止摄像头
                camera.stop();
                // 停止录制
                recorder.stop();
                // 更新state
                state.setState(READY);
            } catch (MyException e) {
                Timber.tag(TAG).e(e,"停止工作流出错");
            }
        }
    }

    @Override
    public void destroy() {
        synchronized (publicLock) {
            stop();

            if(reportExecutor != null) {
                reportExecutor.shutdown();
            }

            state.setState(DESTROYED);
        }
    }


    @Override
    public void setStreamListener(WorkflowListener listener) {
        this.mListener = listener;
    }

    private void setListeners() {
        camera.setCameraListener(new CameraListener() {
            @Override
            public void onError(MyException e) {
                notifyError(e,0, ERROR_Camera, null);
            }

            @Override
            public void onCameraOpened(Size previewSize, Size recordSize, int fps, int stabMode) {
                checker.reportStart(CAMERA, true);
                mPreviewSize = previewSize;
                mRecordSize = recordSize;
                mFps = fps;
                mStabMode = stabMode;
            }
        });

        streamer.setStreamListener(new StreamListener() {
            @Override
            public void onError(MyException e) {
                notifyError(e,0, ERROR_Streamer, null);
            }

            @Override
            public void onStatistics(PushStatsInfo stats) {
                if(mListener != null) mListener.onStatistics(stats);
            }

            @Override
            public void onSurfaceAvailable(Surface surface) {
                surfaceManger.reportPushSurface(surface);
            }

            @Override
            public void onStart() {
                checker.reportStart(STREAM, true);
            }

            @Override
            public void onReconnect(boolean ifSuccess, int reconnectAttempts) {

            }
        });

        recorder.setRecorderListener(new RecorderListener() {
            @Override
            public void onError(MyException e) {
                notifyError(e,0, ERROR_Recorder, null);
            }

            @Override
            public void onStart() {
                checker.reportStart(RECORD, true);
            }

            @Override
            public void onSurfaceAvailable(Surface surface) {
                surfaceManger.reportRecordSurface(surface);
            }
        });
    }

    private MyException throwException(int type, int code, String message) {
        // 重置startChecker
        if(state.getState() == STARTING) checker.reset();
        return new MyException(this.getClass().getPackageName(),
                type, code, message);
    }

    private void notifyError(MyException e,int type, int code, String message) {
        if (state.getState() != WORKING &&
                state.getState() != STARTING &&
                state.getState() != STOPPING) return;

        // 重置startChecker
        if(state.getState() == STARTING) checker.reset();

        state.setState(ERROR);

        Executors.newSingleThreadExecutor().submit(() -> {
            synchronized (errorLock) {
                switch (code) {
                    case ERROR_Workflow_START -> {
                        stopStream();
                        stopCamera();
                        stopRecorder();
                    }
                    case ERROR_Recorder -> {
                        stopStream();
                        stopCamera();
                    }
                    case ERROR_Streamer -> {
                        stopCamera();
                        stopStream();
                    }
                    case ERROR_Camera, Surface_TimeOUT -> {
                        stopStream();
                        stopRecorder();
                    }
                }

                if(mListener != null) {
                    if(e != null) {
                        e.addCode(code);
                        mListener.onError(e);
                    }else {
                        mListener.onError(throwException(type,code,message));
                    }
                }
            }
        });
    }

    private void stopStream() {
        try{
            streamer.stop();
        } catch (Exception ignored) {
        }
    }

    private void stopCamera() {
        try{
            camera.stop();
        } catch (Exception ignored) {
        }
    }

    private void stopRecorder() {
        try{
            recorder.stop();
        } catch (Exception ignored) {
        }
    }
}
