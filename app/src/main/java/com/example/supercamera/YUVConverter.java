package com.example.supercamera;

import com.example.supercamera.BuildConfig;
import com.google.ar.core.ImageFormat;
import android.media.Image;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import timber.log.Timber;

public class YUVConverter {
    private static final String TAG = "YUVConverter";
    // 添加统计变量
    private static final int LOG_INTERVAL = 10; // 每处理10帧输出一次日志
    private static final AtomicLong totalDuration = new AtomicLong(0);
    private static final AtomicInteger frameCount = new AtomicInteger(0);

    // 硬引用缓冲池
    private static class YUVBufferPool {
        private static final byte[][] bufferPool = new byte[16][];
        private static int currentIndex = 0;
        public static synchronized byte[] getBuffer(int requiredSize) {
            // 1. 尝试复用现有缓冲区
            for (int i = 0; i < bufferPool.length; i++) {
                byte[] buf = bufferPool[i];
                if (buf != null && buf.length >= requiredSize) {
                    currentIndex = (i + 1) % bufferPool.length;
                    return buf;
                }
            }

            // 2. 分配新缓冲区
            byte[] newBuf = new byte[(int) (requiredSize * 1.2)];
            bufferPool[currentIndex] = newBuf;
            currentIndex = (currentIndex + 1) % bufferPool.length;
            return newBuf;
        }
    }

    // 使用静态线程池并添加关闭方法
    private static ExecutorService uvExecutor;

    static {
        uvExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setPriority(Thread.MAX_PRIORITY);
            t.setName("YUV-Processor");
            return t;
        });
    }

    // 添加资源释放方法
    public static void release() {
        if (uvExecutor != null && !uvExecutor.isShutdown()) {
            uvExecutor.shutdownNow();
            try {
                if (!uvExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    Timber.w("UV线程池关闭超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static byte[] convertYUV420888ToYUV420P(Image image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException("Invalid image format");
        }
        if (image.getPlanes().length != 3) {
            throw new IllegalArgumentException("无效的平面数量");
        }

        long start = System.nanoTime();
        byte[] buffer = YUVBufferPool.getBuffer(calculateSize(image));
        copyImageData(image, buffer);
        long duration = System.nanoTime() - start;

        // 使用原子变量统计
        long sum = totalDuration.addAndGet(duration);
        int count = frameCount.incrementAndGet();

        // 达到统计间隔时输出平均耗时
        if (count % LOG_INTERVAL == 0) {
            float avgMs = (sum / (float) count) / 1e6f;
            Timber.tag(TAG).d("平均处理耗时: %.2fms (基于%d帧)", avgMs, count);
            totalDuration.set(0);
            frameCount.set(0);
        }

        //return Arrays.copyOf(buffer, calculateSize(image));
        return buffer;
    }

    private static int calculateSize(Image image) {
        return image.getWidth() * image.getHeight() * 3 / 2;
    }

    private static void copyImageData(Image image, byte[] output) {
        Image.Plane[] planes = image.getPlanes();
        int width = image.getWidth();
        int height = image.getHeight();

        // Y分量
        copyPlaneOptimized(planes[0], output, 0, width, height);

        // UV分量
        int uvWidth = width / 2;
        int uvHeight = height / 2;
        int uvOffset = width * height;

        copyUVPlanes(planes[1], planes[2],
                output, uvOffset, uvWidth, uvHeight);
        // U分量
        //copyPlaneOptimized(planes[1], output, uvOffset, uvWidth, uvHeight);

        // V分量
        //copyPlaneOptimized(planes[2], output, uvOffset + uvWidth * uvHeight, uvWidth, uvHeight);
    }

    private static void copyUVPlanes(Image.Plane uPlane, Image.Plane vPlane,
                                     byte[] output, int offset, int width, int height) {
        final int uvWidth = width / 2;
        final int uvHeight = height / 2;

        CountDownLatch latch = new CountDownLatch(2);

        uvExecutor.execute(() -> {
            try {
                copyPlaneOptimized(uPlane, output, offset, uvWidth, uvHeight);
            } finally {
                latch.countDown();
            }
        });

        uvExecutor.execute(() -> {
            try {
                copyPlaneOptimized(vPlane, output, offset + uvWidth*uvHeight, uvWidth, uvHeight);
            } finally {
                latch.countDown();
            }
        });

        try {
            // 设置合理超时时间（根据帧率计算）
            long timeout = 15;
            if (!latch.await(timeout, TimeUnit.MILLISECONDS)) {
                Timber.e("UV平面处理超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    private static void copyPlaneOptimized(Image.Plane plane, byte[] output, int offset,
                                           int width, int height) {
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();

        final int rowStride = plane.getRowStride();
        final int pixelStride = plane.getPixelStride();

        if (pixelStride == 1 && rowStride == width) {
            buffer.get(output, offset, width * height);
            return;
        }

        byte[] rowBuffer = new byte[rowStride];
        for (int y = 0; y < height; y++) {
            buffer.get(rowBuffer, 0, Math.min(rowStride, buffer.remaining()));
            int destIndex = offset + y * width;

            if (pixelStride == 2) {
                // 优化步长为2的情况
                for (int x = 0; x < width; x++) {
                    output[destIndex + x] = rowBuffer[x << 1]; // x*2 使用位移优化
                }
            } else {
                // 通用处理
                for (int x = 0; x < width; x += pixelStride) {
                    output[destIndex + (x / pixelStride)] = rowBuffer[x];
                }
            }
        }
    }


    /**
     * 打印图像信息（用于调试）
     */

    public static void logImageInfo(Image image) {
        Timber.i("Image format: %d", image.getFormat());

        // 从Image中获取三个平面
        Image.Plane[] planes = image.getPlanes();

        for (int i = 0; i < planes.length; i++) {
            ByteBuffer buffer = planes[i].getBuffer();
            int bufferSize = buffer.remaining();

            Timber.tag(TAG).i("Plane %d - pixelStride: %d", i, planes[i].getPixelStride());
            Timber.tag(TAG).i("Plane %d - rowStride: %d", i, planes[i].getRowStride());
            Timber.tag(TAG).i("Plane %d - buffer size: %d", i, bufferSize);
            Timber.tag(TAG).i("Finished reading data from plane %d", i);
        }
    }
}
