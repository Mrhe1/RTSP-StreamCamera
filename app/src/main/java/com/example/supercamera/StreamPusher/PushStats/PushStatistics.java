package com.example.supercamera.StreamPusher.PushStats;

import com.example.supercamera.StreamPusher.PushState;
import com.google.common.util.concurrent.AtomicDouble;
import com.stealthcopter.networktools.Ping;
import com.stealthcopter.networktools.ping.PingStats;

import org.apache.commons.collections4.queue.CircularFifoQueue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;
import timber.log.Timber;

public class PushStatistics {
    private final int intervalSeconds;
    private final int pingIntervalSeconds;
    private final String url;
    private final double pushFailureRateSet;
    List<PublishSubject<TimeStamp>> reportQueue = new ArrayList<>();
    private PushStatsListener listener;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    String TAG = "PushStatistics";
    private List<FrameInfo> frameList = Collections.synchronizedList(new ArrayList<>());
    Queue<Integer> rtt = new CircularFifoQueue<Integer>(3);
    private AtomicDouble curPushFailureRate = new AtomicDouble(0);
    private static final Object timestampLock = new Object();
    private static Queue<Long> latency1Queue = new CircularFifoQueue<>(250);
    private static Queue<Long> latency2Queue = new CircularFifoQueue<>(250);
    private static Queue<Long> capturedTimestampQueue = new CircularFifoQueue<>(250);
    private PingHelper pingHelper;
    private ScheduledExecutorService executor;
    private ExecutorService reportExecutor = Executors.newSingleThreadExecutor();
    public static final class FrameInfo {
        private boolean isPushFrameSuccess;
        private int frameSize;
        public FrameInfo(boolean isPushFrameSuccess, int frameSize) {
            this.isPushFrameSuccess = isPushFrameSuccess;
            this.frameSize = frameSize;
        }

        public boolean getIfPushFrameSuccess() {
            return isPushFrameSuccess;
        }

        public int getFrameSize() {
            return frameSize;
        }

        public void setSuccess(boolean success) {
            this.isPushFrameSuccess = success;
        }

        public void setSize(int size) {
            this.frameSize = size;
        }
    }

    // 对象池工具类
    private static class FrameInfoPool {
        private static final int MAX_POOL_SIZE = 300;
        private static Queue<FrameInfo> pool = new ConcurrentLinkedQueue<>();

        static FrameInfo obtain(boolean isSuccess, int size) {
            FrameInfo info = pool.poll();
            if (info == null) {
                info = new FrameInfo(isSuccess, size);
            } else {
                // 重用对象
                info.setSuccess(isSuccess);
                info.setSize(size);
            }
            return info;
        }

        static void recycle(FrameInfo info) {
            if (pool.size() < MAX_POOL_SIZE) {
                pool.offer(info);
            }
        }
    }

    public class PingHelper {
        private ScheduledExecutorService pingExecutor;

        // 开始定时 Ping
        public void startPeriodicPing(String host, int intervalSeconds) {
            pingExecutor = Executors.newSingleThreadScheduledExecutor();
            pingExecutor.scheduleWithFixedDelay(() -> doPing(host), intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        }

        // 停止 Ping
        public void stopPeriodicPing() {
            if (pingExecutor != null) {
                pingExecutor.shutdown();
            }
        }

        // 执行单次 Ping
        private void doPing(String host) {
            Ping.onAddress(host).setTimeOutMillis(1000).doPing(new Ping.PingListener() {
                @Override
                public void onResult(com.stealthcopter.networktools.ping.PingResult pingResult) {
                    if (pingResult.isReachable) {
                        int currentRTT = (int) Math.round(pingResult.timeTaken);
                        synchronized (rtt) {
                            rtt.add(currentRTT);
                        }
                        Timber.tag(TAG).i("ping成功，rtt：%d", currentRTT);
                    } else {
                        synchronized (rtt) {
                            rtt.add(-1);
                        }
                        Timber.tag(TAG).e("ping失败");
                    }
                }

                @Override
                public void onFinished(PingStats pingStats) {

                }

                @Override
                public void onError(Exception e) {
                    synchronized (rtt) {
                        rtt.add(-1);
                    }
                    Timber.tag(TAG).e("ping失败");
                }
            });
        }
    }

    public PushStatistics(int intervalSeconds, int pingIntervalSeconds,
                          String url, double pushFailureRateSet,
                          List<PublishSubject<TimeStamp>> reportQueue) {
        this.intervalSeconds = intervalSeconds;
        this.pingIntervalSeconds = pingIntervalSeconds;
        this.url = url;
        this.pushFailureRateSet = pushFailureRateSet;
        this.reportQueue = reportQueue;
    }

    // throw RuntimeException
    public void startPushStatistics() {
        // 获取host地址
        String host = null;
        Pattern pattern = Pattern.compile("^rtsp://([^:/]+)");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            host = matcher.group(1);
        } else {
            Timber.tag(TAG).e("rtsp地址无法解析");
            throw new RuntimeException("rtsp地址无法解析");
        }

        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(() -> reportPushStatistics(), intervalSeconds, intervalSeconds, TimeUnit.SECONDS);

        pingHelper = new PingHelper();
        pingHelper.startPeriodicPing(host, pingIntervalSeconds);

        // 订阅reportQueue
        setQueueReceiver();
    }

    // throw RuntimeException
    public void stopPushStatistics() {
        try {
            //释放RxJava资源
            if (!compositeDisposable.isDisposed()) {
                compositeDisposable.dispose();
            }

            //停止ping
            pingHelper.stopPeriodicPing();

            //停止ping线程
            if (executor != null) {
                executor.shutdown();
            }

            if (reportExecutor != null) {
                reportExecutor.shutdown();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 设置统计回调listener
    public void setPushStatListener (PushStatsListener listener) {
        this.listener = listener;
    }

    // 在每帧push时调用
    public void setPushStatistics(boolean isSuccess, int size) {
        frameList.add(FrameInfoPool.obtain(isSuccess, size));
    }

    // 在每帧push时调用
    private void reportTimestamp(TimeStamp stamp) {
        synchronized (timestampLock) {
            //if (VideoPusher.currentState.get() != VideoPusher.PushState.STREAMING) return;
            switch (stamp.style) {
                case Captured -> {
                    capturedTimestampQueue.add(stamp.timeStamp);
                }
                case Encoded -> {
                    // 遍历时同步列表
                    if (capturedTimestampQueue.isEmpty()) return;
                    List<Long> tempList = new ArrayList<>(capturedTimestampQueue);
                    for (int i = tempList.size() - 1; i >= 0; i--) {
                        if (tempList.get(i) < stamp.timeStamp &&
                                stamp.timeStamp - tempList.get(i) <= 33_000_000L) {
                            latency1Queue.add(stamp.timeStamp - tempList.get(i));
                            break;
                        }
                    }
                }
                case Pushed -> {
                    latency2Queue.add(stamp.timeStamp);
                }
            }
        }
    }

    private void reportPushStatistics() {
        try {
            if (PushState.getState() != PushState.PushStateEnum.PUSHING) {
                return;
            }

            // 计算错误帧和码率
            int ErrorFrameNum = 0;
            int size = 0;
            int frameListSize;
            synchronized (frameList) {
                if (frameList.isEmpty()) {
                    Timber.tag(TAG).d("无帧数据，跳过统计");
                    return;
                }
                frameListSize = frameList.size();
                for (FrameInfo info : frameList) {
                    if (!info.getIfPushFrameSuccess()) ErrorFrameNum++;
                    size += info.getFrameSize();
                    FrameInfoPool.recycle(info);
                }
                frameList.clear();
            }
            int currentBitrate = size * 8 / 1024 / intervalSeconds; //kbps
            double failureRate = (double) ErrorFrameNum / frameListSize;
            curPushFailureRate.set(Math.round(failureRate * 100.0) / 100.0);

            // 重连回调
            if (curPushFailureRate.get() > pushFailureRateSet) {
                reportExecutor.submit(() -> {
                    listener.onNeedReconnect();
                });
            }

            // 延迟计算
            int totalLatency = -1;
            try {
                if (!latency1Queue.isEmpty() && !latency2Queue.isEmpty()) {
                    long latency1Sum = 0;
                    long latency2Sum = 0;
                    long avgLatency1 = 0; // ns
                    long avgLatency2 = 0; // ns
                    int avgLatency3 = 0; // ms
                    int rttSum = -1;
                    int avgRTT = -1;

                    synchronized (timestampLock) {
                        for (Long latency : latency1Queue) {
                            latency1Sum += latency;
                        }
                        avgLatency1 = latency1Sum / latency1Queue.size();

                        for (Long latency : latency2Queue) {
                            latency2Sum += latency;
                        }
                        avgLatency2 = latency2Sum / latency2Queue.size();

                        if (!rtt.isEmpty()) {
                            int num = 0;
                            for (int singleRTT : rtt) {
                                if (singleRTT == -1) return;
                                rttSum += singleRTT;
                                num++;
                            }
                            if (num > 0) {
                                avgRTT = rttSum / rtt.size();
                            }
                        }
                    }

                    // 用rtt和帧大小估算I/O延迟,按上行带宽15mbps算,加5ms的tcp额外开销
                    //avgLatency3 = avgRTT + currentBitrate / 1024 / fps / 15 + 5;
                    totalLatency = ((int) ((avgLatency1 + avgLatency2) / 1_000_000L)) + avgLatency3;

                    Timber.tag(TAG).d("平均延迟计算完成: L1=%dms, L2=%dms ,L3=%dms",
                            (int) (avgLatency1 / 1_000_000L),
                            (int) (avgLatency2 / 1_000_000L), avgLatency3);
                }
            } catch (Exception e) {
                Timber.tag(TAG).e(e, "延迟计算异常");
            } finally {
                latency1Queue.clear();
                latency2Queue.clear();
            }

            int currentRTT = -1;
            // 时间戳清理
            synchronized (timestampLock) {
                if (capturedTimestampQueue.size() >= 2) {
                    // 保留最后两个元素
                    int removeCount = capturedTimestampQueue.size() - 2;
                    if (removeCount > 0) {
                        // 循环移除最旧的元素
                        for (int i = 0; i < removeCount; i++) {
                            capturedTimestampQueue.poll();
                        }
                    }
                }

                // 获取最后一次的rtt
                if (!rtt.isEmpty()) {
                    currentRTT = ((CircularFifoQueue<Integer>) rtt).get(rtt.size() - 1);
                }
            }

            // 回调
            int finalCurrentRTT = currentRTT;
            int finalTotalLatency = totalLatency;
            reportExecutor.submit(() -> {
                listener.onStatistics(new PushStatsInfo(currentBitrate, finalCurrentRTT,
                        curPushFailureRate.get(), finalTotalLatency));
            });
        } catch (Exception e) {
            Timber.tag(TAG).e(e, "统计任务异常");
        }
    }

    // 订阅reportQueue
    private void setQueueReceiver() {
        Disposable disposable = Observable.merge(
                        reportQueue.stream()
                                .map(subject -> subject.observeOn(Schedulers.io()))
                                .collect(Collectors.toList())
                )
                .subscribe(report -> reportTimestamp((TimeStamp) report));
        compositeDisposable.add(disposable);
    }
}