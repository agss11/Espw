package com.detector.esp.utils;

/**
 * DeteksiHasil — 可复用对象，含Jejak数据
 */
public class DetectResult {
    public float left;    // 归一化 [0,1]
    public float top;
    public float right;
    public float bottom;
    public int classId;
    public String label;
    public float confidence;

    // Jejak数据（由 DetectionStabilizer 填充）
    public float velX, velY;     // 补偿后的真实速度
    public float predX, predY;   // 预测Lokasi（中心点）
    public static final int MAX_TRAIL = 30;  // 最多30Frame≈2秒
    public float[] trailX = new float[MAX_TRAIL];
    public float[] trailY = new float[MAX_TRAIL];
    public int trailLen = 0;

    public void set(float left, float top, float right, float bottom,
                    int classId, String label, float confidence) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.classId = classId;
        this.label = label;
        this.confidence = confidence;
        this.velX = this.velY = 0;
        this.predX = this.predY = 0;
        this.trailLen = 0;
    }

    public void reset() {
        left = top = right = bottom = confidence = 0f;
        classId = -1;
        label = null;
        velX = velY = predX = predY = 0;
        trailLen = 0;
    }
}
