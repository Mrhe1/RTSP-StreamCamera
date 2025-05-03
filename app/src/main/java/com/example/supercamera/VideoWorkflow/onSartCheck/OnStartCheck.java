package com.example.supercamera.VideoWorkflow.onSartCheck;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;

import timber.log.Timber;

public class OnStartCheck {
    private final String TAG = "StartChecker";
    private AtomicIntegerArray isStarted = new AtomicIntegerArray(3);
    private Long TIMEOUT_MILLISECONDS;
    private ScheduledExecutorService timeoutScheduler;
    private final startListener mListener;
    public OnStartCheck(startListener mListener) {
        this.mListener = mListener;
    }

    public enum StartPart {
        CAMERA(0),
        STREAM(1),
        RECORD(2);

        private final int index;

        StartPart(int index) {
            this.index = index;
        }

        public int getIndex() {
            return index;
        }
    }

    public void reportStart(StartPart part, boolean isSuccess)
    {
        // 使用原子操作设置状态（1表示成功，-1表示失败）
        isStarted.set(part.getIndex(), isSuccess ? 1 : -1);

        // 如果start失败
        if(!isSuccess) {
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
            if(mListener != null) mListener.onStart();
        }
    }

    // 在出现错误，start失败时调用
    public void reset() {
        if (timeoutScheduler != null) {
            try {
                timeoutScheduler.shutdownNow();
            } catch (Exception e) {}
        }
        timeoutScheduler = null;

        for (int i = 0; i < isStarted.length(); i++) {
            isStarted.set(i, 0);
        }
    }

    public void setUpChecker(Long TIMEOUT_MILLISECONDS)
    {
        this.TIMEOUT_MILLISECONDS = TIMEOUT_MILLISECONDS;
        if (timeoutScheduler == null || timeoutScheduler.isShutdown()) {
            timeoutScheduler = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
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
        Timber.tag(TAG).w("启动超时，未完成组件状态:");
        for (int i = 0; i < isStarted.length(); i++) {
            int status = isStarted.get(i);
            Timber.tag(TAG).w("%s: %s",
                    StartPart.values()[i].name(),
                    status == 1 ? "成功" : status == -1 ? "失败" : "未完成"
            );
        }

        Executors.newSingleThreadExecutor().submit(() -> {
            if(mListener != null) mListener.onStartTimeOUT();
        });
    }

    // 状态检查
    private boolean isAllStartedSuccess() {
        for (int i = 0; i < isStarted.length(); i++) {
            if (isStarted.get(i) != 1) {
                return false;
            }
        }
        return true;
    }
}
