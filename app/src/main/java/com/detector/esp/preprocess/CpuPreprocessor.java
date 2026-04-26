package com.detector.esp.preprocess;

import android.media.Image;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * CPU 前处理: YUV_420_888 → RGB 320x320
 *
 * 关键修复：
 * 1. Rotasi 90°（传感器横向 → 竖屏方向）
 * 2. Letterbox 保持宽高比（不拉伸）
 */
public class CpuPreprocessor {

    private static final String TAG = "CpuPreprocess";
    private static final int GRAY = 128;

    private final int targetSize;
    private final byte[] rgbBytes;
    private int rotation = 90;  // 传感器Rotasi角度

    // letterbox 参数
    private float padTop;
    private float padLeft;

    public CpuPreprocessor(int targetSize) {
        this.targetSize = targetSize;
        this.rgbBytes = new byte[targetSize * targetSize * 3];
    }

    public void setRotation(int degrees) {
        this.rotation = degrees;
    }

    /**
     * YUV_420_888 → Rotasi → Letterbox → RGB 320x320
     */
    public byte[] processYuvImage(Image image, float softwareZoom) {
        try {
            int imgW = image.getWidth();
            int imgH = image.getHeight();

            Image.Plane yPlane = image.getPlanes()[0];
            Image.Plane uPlane = image.getPlanes()[1];
            Image.Plane vPlane = image.getPlanes()[2];

            ByteBuffer yBuf = yPlane.getBuffer();
            ByteBuffer uBuf = uPlane.getBuffer();
            ByteBuffer vBuf = vPlane.getBuffer();

            int yRowStride = yPlane.getRowStride();
            int uvRowStride = uPlane.getRowStride();
            int uvPixelStride = uPlane.getPixelStride();
            int yLimit = yBuf.remaining();
            int uLimit = uBuf.remaining();
            int vLimit = vBuf.remaining();

            // 软件变焦裁切（在原始方向上裁切）
            float cropFactor = Math.max(1.0f, softwareZoom);
            int cropW = (int) (imgW / cropFactor);
            int cropH = (int) (imgH / cropFactor);
            int cropX = (imgW - cropW) / 2;
            int cropY = (imgH - cropH) / 2;

            // Rotasi后的虚拟尺寸
            int rotW, rotH;
            if (rotation == 90 || rotation == 270) {
                rotW = cropH;
                rotH = cropW;
            } else {
                rotW = cropW;
                rotH = cropH;
            }

            // Letterbox 计算
            float ratioW = (float) targetSize / rotW;
            float ratioH = (float) targetSize / rotH;
            float ratio = Math.min(ratioW, ratioH);
            int scaledW = (int) (rotW * ratio);
            int scaledH = (int) (rotH * ratio);
            int padX = (targetSize - scaledW) / 2;
            int padY = (targetSize - scaledH) / 2;

            padLeft = (float) padX / targetSize;
            padTop = (float) padY / targetSize;

            // 填灰
            for (int i = 0; i < rgbBytes.length; i++) {
                rgbBytes[i] = (byte) GRAY;
            }

            float srcScaleX = (float) rotW / scaledW;
            float srcScaleY = (float) rotH / scaledH;

            for (int row = 0; row < scaledH; row++) {
                int dstRow = row + padY;
                // Rotasi后的虚拟坐标
                int rotRow = (int) (row * srcScaleY);

                for (int col = 0; col < scaledW; col++) {
                    int dstCol = col + padX;
                    int rotCol = (int) (col * srcScaleX);

                    // 虚拟坐标 → 原始传感器坐标（逆Rotasi）
                    int srcCol, srcRow;
                    if (rotation == 90) {
                        // Rotasi90°: rotated(x,y) = original(y, W-1-x)
                        srcCol = cropX + rotRow;
                        srcRow = cropY + (cropH - 1 - rotCol);
                    } else if (rotation == 270) {
                        srcCol = cropX + (cropW - 1 - rotRow);
                        srcRow = cropY + rotCol;
                    } else if (rotation == 180) {
                        srcCol = cropX + (cropW - 1 - rotCol);
                        srcRow = cropY + (cropH - 1 - rotRow);
                    } else {
                        srcCol = cropX + rotCol;
                        srcRow = cropY + rotRow;
                    }

                    // 边界检查
                    srcCol = Math.max(0, Math.min(imgW - 1, srcCol));
                    srcRow = Math.max(0, Math.min(imgH - 1, srcRow));

                    // 读 YUV
                    int yIdx = srcRow * yRowStride + srcCol;
                    int y = (yIdx < yLimit) ? (yBuf.get(yIdx) & 0xFF) : 128;

                    int uvRow2 = srcRow >> 1;
                    int uvCol2 = srcCol >> 1;
                    int uvIdx = uvRow2 * uvRowStride + uvCol2 * uvPixelStride;
                    int u = (uvIdx < uLimit) ? (uBuf.get(uvIdx) & 0xFF) : 128;
                    int v = (uvIdx < vLimit) ? (vBuf.get(uvIdx) & 0xFF) : 128;

                    // YUV → RGB
                    int r = y + (int) (1.402f * (v - 128));
                    int g = y - (int) (0.344136f * (u - 128)) - (int) (0.714136f * (v - 128));
                    int b = y + (int) (1.772f * (u - 128));

                    int outIdx = (dstRow * targetSize + dstCol) * 3;
                    rgbBytes[outIdx] = (byte) clamp(r);
                    rgbBytes[outIdx + 1] = (byte) clamp(g);
                    rgbBytes[outIdx + 2] = (byte) clamp(b);
                }
            }

            return rgbBytes;
        } catch (Exception e) {
            Log.e(TAG, "处理Gagal", e);
            java.util.Arrays.fill(rgbBytes, (byte) GRAY);
            return rgbBytes;
        }
    }

    public byte[] processYuvImage(Image image) {
        return processYuvImage(image, 1.0f);
    }

    public float getPadTop() { return padTop; }
    public float getPadLeft() { return padLeft; }

    public float[] removePadding(float left, float top, float right, float bottom) {
        float contentW = 1.0f - 2 * padLeft;
        float contentH = 1.0f - 2 * padTop;
        if (contentW <= 0) contentW = 1;
        if (contentH <= 0) contentH = 1;

        return new float[]{
                Math.max(0, Math.min(1, (left - padLeft) / contentW)),
                Math.max(0, Math.min(1, (top - padTop) / contentH)),
                Math.max(0, Math.min(1, (right - padLeft) / contentW)),
                Math.max(0, Math.min(1, (bottom - padTop) / contentH))
        };
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
