package com.example.supercamera.VideoWorkflow;

import static com.example.supercamera.MyException.MyException.ILLEGAL_STATE;
import static com.example.supercamera.VideoWorkflow.ErrorCode.ERROR_Workflow_CONFIG;
import static com.example.supercamera.VideoWorkflow.ErrorCode.ERROR_Workflow_START;
import static com.example.supercamera.VideoWorkflow.ErrorCode.ERROR_Workflow_STOP;
import static com.example.supercamera.VideoWorkflow.WorkflowState.WorkflowStateEnum.CONFIGURED;
import static com.example.supercamera.VideoWorkflow.WorkflowState.WorkflowStateEnum.ERROR;
import static com.example.supercamera.VideoWorkflow.WorkflowState.WorkflowStateEnum.READY;
import static com.example.supercamera.VideoWorkflow.WorkflowState.WorkflowStateEnum.STARTING;
import static com.example.supercamera.VideoWorkflow.WorkflowState.WorkflowStateEnum.STOPPING;
import static com.example.supercamera.VideoWorkflow.WorkflowState.WorkflowStateEnum.WORKING;

import com.example.supercamera.CameraController.CameraController;
import com.example.supercamera.CameraController.CameraImpl;
import com.example.supercamera.MyException.MyException;
import com.example.supercamera.VideoRecorder.VideoRecorder;
import com.example.supercamera.VideoRecorder.VideoRecorderImpl;
import com.example.supercamera.VideoStreamer.StreamState;
import com.example.supercamera.VideoStreamer.VideoStreamer;
import com.example.supercamera.VideoStreamer.VideoStreamerImpl;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

public class VideoWorkflowImpl implements VideoWorkflow {
    private WorkflowConfig mConfig;
    private WorkflowListener mListener;
    private final VideoStreamer streamer;
    private final VideoRecorder recorder;
    private final CameraController camera;
    private final String TAG = "VideoWorkflow";
    private final Object errorLock = new Object();
    private final Object publicLock = new Object();
    private final Object textureLock = new Object();
    private final Object surfaceLock = new Object();
    private final AtomicBoolean isTextureSurfaceAvailable = new AtomicBoolean(false);
    private Surface previewSurface = null;
    private final TextureView textureView;
    private SurfaceTexture surfaceTexture;
    private Handler mMainHandler = new Handler(Looper.getMainLooper());

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
            if (WorkflowState.getState() != READY) {
                String msg = String.format("configure failed, current state: %s", StreamState.getState());
                Timber.tag(TAG).e(msg);
                throw throwException(ILLEGAL_STATE, ERROR_Workflow_CONFIG, msg);
            }

            this.mConfig = config;
            camera.configure(config.cameraConfig);
            recorder.configure(config.recorderConfig);
            streamer.configure(config.streamConfig);
        }
    }

    @Override
    public void start() {
        if (WorkflowState.getState() != CONFIGURED) {
            String msg = String.format("start failed, current state: %s", StreamState.getState());
            Timber.tag(TAG).e(msg);
            throw throwException(ILLEGAL_STATE, ERROR_Workflow_START, msg);
        }

        Executors.newSingleThreadExecutor().submit(() -> {
            synchronized (publicLock) {

            }

        });
    }

    @Override
    public void stop() {
        if (WorkflowState.getState() != WORKING) {
            String msg = String.format("stop failed, current state: %s", StreamState.getState());
            Timber.tag(TAG).e(msg);
            throw throwException(ILLEGAL_STATE, ERROR_Workflow_STOP, msg);
        }

        synchronized (publicLock) {

        }
    }

    @Override
    public void destroy() {
        synchronized (publicLock) {

        }
    }


    @Override
    public void setStreamListener(WorkflowListener listener) {
        this.mListener = listener;
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    synchronized (textureLock) {
                        surfaceTexture = surface;
                        isTextureSurfaceAvailable.set(true);
                        surfaceLock.notifyAll();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    // 处理尺寸变化
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    synchronized (textureLock) {
                        isTextureSurfaceAvailable.set(false);
                    }
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                }
            };

    private MyException throwException(int type, int code, String message) {
        return new MyException(this.getClass().getPackageName(),
                type, code, message);
    }

    private void notifyError(MyException e,int type, int code, String message) {
        if (WorkflowState.getState() != WORKING &&
                WorkflowState.getState() != STARTING &&
                WorkflowState.getState() != STOPPING) return;

        WorkflowState.setState(ERROR);

        Executors.newSingleThreadExecutor().submit(() -> {
            synchronized (errorLock) {

            }
        });
    }
}
