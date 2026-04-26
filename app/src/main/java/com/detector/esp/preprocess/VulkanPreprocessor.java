package com.detector.esp.preprocess;

import android.media.Image;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Vulkan GPU 前处理器 (JNI 封装)
 *
 * Vulkan Compute Shader 在 GPU 上完成 YUV→RGB + Zoom
 * 耗时 <1ms，零 CPU 占用
 *
 * 如果 Vulkan 不可用（设备不支持），自动回退到 CpuPreprocessor
 */
public class VulkanPreprocessor {

    private static final String TAG = "VulkanPreprocessor";

    private boolean nativeAvailable = false;
    private CpuPreprocessor cpuFallback;

    // 预分配 byte 数组 — 避免 GC
    private byte[] yBytes;
    private byte[] uvBytes;
    private byte[] rgbOutput;
    private final int targetSize;

    static {
        try {
            System.loadLibrary("vulkan_preprocess");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Native lib MemuatGagal: " + e.getMessage());
        }
    }

    public VulkanPreprocessor(int srcWidth, int srcHeight, int targetSize) {
        this.targetSize = targetSize;
        this.rgbOutput = new byte[targetSize * targetSize * 3];
        this.cpuFallback = new CpuPreprocessor(targetSize);

        try {
            nativeAvailable = nativeInit(srcWidth, srcHeight, targetSize);
            if (nativeAvailable) {
                // 预分配 YUV buffer
                yBytes = new byte[srcWidth * srcHeight];
                uvBytes = new byte[srcWidth * (srcHeight / 2)];
                Log.i(TAG, "Vulkan GPU 前处理已启用");
            }
        } catch (Exception e) {
            nativeAvailable = false;
            Log.w(TAG, "Vulkan Inisialisasi异常: " + e.getMessage());
        }

        if (!nativeAvailable) {
            Log.i(TAG, "回退到 CPU 前处理");
        }
    }

    /**
     * 处理 Camera2 Frame
     * @return RGB byte[320*320*3] 或 null
     */
    public byte[] process(Image image) {
        if (nativeAvailable) {
            return processVulkan(image);
        } else {
            return cpuFallback.processYuvImage(image);
        }
    }

    private byte[] processVulkan(Image image) {
        try {
            Image.Plane yPlane = image.getPlanes()[0];
            Image.Plane uvPlane = image.getPlanes()[2]; // V plane (NV21)

            ByteBuffer yBuf = yPlane.getBuffer();
            ByteBuffer uvBuf = uvPlane.getBuffer();

            int ySize = yBuf.remaining();
            int uvSize = uvBuf.remaining();

            // 确保预分配数组够大
            if (yBytes.length < ySize) yBytes = new byte[ySize];
            if (uvBytes.length < uvSize) uvBytes = new byte[uvSize];

            yBuf.get(yBytes, 0, ySize);
            uvBuf.get(uvBytes, 0, uvSize);
            yBuf.rewind();
            uvBuf.rewind();

            float ms = nativeProcess(
                    yBytes, uvBytes,
                    yPlane.getRowStride(), uvPlane.getRowStride(),
                    rgbOutput
            );

            if (ms < 0) {
                // Vulkan 处理Gagal，回退
                nativeAvailable = false;
                return cpuFallback.processYuvImage(image);
            }

            return rgbOutput;
        } catch (Exception e) {
            Log.e(TAG, "Vulkan 处理异常", e);
            nativeAvailable = false;
            return cpuFallback.processYuvImage(image);
        }
    }

    public boolean isVulkanActive() { return nativeAvailable; }

    public void release() {
        if (nativeAvailable) {
            try { nativeCleanup(); } catch (Exception ignored) {}
        }
    }

    // Native 方法
    private native boolean nativeInit(int srcWidth, int srcHeight, int dstSize);
    private native float nativeProcess(byte[] yData, byte[] uvData,
                                        int yRowStride, int uvRowStride,
                                        byte[] output);
    private native void nativeCleanup();
    private native boolean nativeIsAvailable();
}
