package com.example.supercamera;

import com.example.supercamera.BuildConfig;
import android.media.Image;
import android.media.ImageFormat;
import java.nio.ByteBuffer;
import timber.log.Timber;

public class YUVConverter {
    private static final String TAG = "YUVConverter";
    /**
     * 将YUV420_888格式直接转换为YUV420P（紧凑排列，不考虑padding）
     * 注意：此方法假设图像数据已经是紧凑排列，无行跨度（row stride）填充
     */
    public static byte[] convertYUV420888ToYUV420P(Image image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException("Invalid image format");
        }

        // 获取图像宽高
        int width = image.getWidth();
        int height = image.getHeight();

        // 从Image中获取三个平面
        Image.Plane[] planes = image.getPlanes();

        // 提取Y数据（直接复制整个缓冲区）
        ByteBuffer yBuffer = planes[0].getBuffer();
        byte[] yData = new byte[yBuffer.remaining()];
        yBuffer.get(yData);

        // 提取U数据（直接复制整个缓冲区）
        ByteBuffer uBuffer = planes[1].getBuffer();
        byte[] uData = new byte[uBuffer.remaining()];
        uBuffer.get(uData);

        // 提取V数据（直接复制整个缓冲区）
        ByteBuffer vBuffer = planes[2].getBuffer();
        byte[] vData = new byte[vBuffer.remaining()];
        vBuffer.get(vData);

        // 合并为YUV420P（Y + U + V）
        byte[] yuv420p = new byte[yData.length + uData.length + vData.length];
        System.arraycopy(yData, 0, yuv420p, 0, yData.length);
        System.arraycopy(uData, 0, yuv420p, yData.length, uData.length);
        System.arraycopy(vData, 0, yuv420p, yData.length + uData.length, vData.length);

        Timber.tag(TAG).i("YUV420P conversion completed. Size: %d", yuv420p.length);
        logImageInfo(image);//only debug use********&&&&&&&&
        return yuv420p;
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