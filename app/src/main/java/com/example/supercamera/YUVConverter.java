package com.example.supercamera;

import com.example.supercamera.BuildConfig;
import com.google.ar.core.ImageFormat;
import android.media.Image;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.util.Arrays;
import timber.log.Timber;

public class YUVConverter {
    private static final String TAG = "YUVConverter";

    // 静态缓冲池类
    private static class YUVBufferPool {
        private static final SoftReference<byte[]>[] bufferPool = new SoftReference[4];
        private static int currentIndex = 0;

        public static synchronized byte[] getBuffer(int requiredSize) {
            // 1. 尝试复用现有缓冲区
            for (int i = 0; i < bufferPool.length; i++) {
                SoftReference<byte[]> ref = bufferPool[i];
                if (ref != null) {
                    byte[] buf = ref.get();
                    if (buf != null && buf.length >= requiredSize) {
                        // 循环使用索引
                        currentIndex = (i + 1) % bufferPool.length;
                        return buf;
                    }
                }
            }

            // 2. 分配新缓冲区
            byte[] newBuf = new byte[(int) (requiredSize * 1.2)]; // 20%余量
            bufferPool[currentIndex] = new SoftReference<>(newBuf);
            currentIndex = (currentIndex + 1) % bufferPool.length;
            return newBuf;
        }

    }

    public static byte[] convertYUV420888ToYUV420P(Image image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException("Invalid image format");
        }
        if (image.getPlanes().length != 3) {
            throw new IllegalArgumentException("无效的平面数量");
        }

        byte[] buffer = YUVBufferPool.getBuffer(calculateSize(image));
        copyImageData(image, buffer);

        logImageInfo(image);//only debug use********&&&&&&&&

        return Arrays.copyOf(buffer, calculateExactSize(image));
    }

    private static int calculateSize(Image image) {
        return image.getWidth() * image.getHeight() * 3 / 2;
    }

    private static int calculateExactSize(Image image) {
        int total = 0;
        for (int i = 0; i < 3; i++) {
            Image.Plane plane = image.getPlanes()[i];
            int rowStride = plane.getRowStride();
            int rows = (i == 0) ? image.getHeight() : image.getHeight() / 2;
            total += rowStride * rows;
        }
        return total;
    }

    private static void copyImageData(Image image, byte[] output) {
        Image.Plane[] planes = image.getPlanes();
        int width = image.getWidth();
        int height = image.getHeight();

        // Y分量
        copyPlane(planes[0], output, 0,
                width, height,
                planes[0].getRowStride(),
                planes[0].getPixelStride());

        // UV分量（需要子采样）
        int uvWidth = width / 2;
        int uvHeight = height / 2;
        int uvOffset = width * height;

        // U分量
        copyPlane(planes[1], output, uvOffset,
                uvWidth, uvHeight,
                planes[1].getRowStride(),
                planes[1].getPixelStride());

        // V分量
        copyPlane(planes[2], output, uvOffset + uvWidth * uvHeight,
                uvWidth, uvHeight,
                planes[2].getRowStride(),
                planes[2].getPixelStride());
    }

    private static void copyPlane(Image.Plane plane, byte[] output, int offset,
                                  int width, int height,
                                  int rowStride, int pixelStride) {
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();

        if (pixelStride == 1 && rowStride == width) {
            // 快速路径：直接拷贝
            buffer.get(output, offset, width * height);
        } else {
            // 带跨度的逐行拷贝
            byte[] rowBuffer = new byte[rowStride];
            for (int y = 0; y < height; y++) {
                buffer.get(rowBuffer, 0, rowStride);
                for (int x = 0; x < width; x++) {
                    output[offset + y * width + x] = rowBuffer[x * pixelStride];
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
