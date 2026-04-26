package com.detector.esp.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * DetectResult 对象池 — 消除Inferensi过程中的 GC 毛刺
 *
 * 预分配固定数量的 DetectResult 对象，Inferensi时从池中取，
 * 下一Frame开始前统一归还，不产生任何堆分配。
 */
public class DetectResultPool {

    private static final int POOL_SIZE = 64;  // 单Frame最大Deteksi数
    private final DetectResult[] pool;
    private int index;

    // 双缓冲：当前FrameHasil 和 展示中Hasil
    private final List<DetectResult> currentFrame = new ArrayList<>(POOL_SIZE);
    private volatile List<DetectResult> displayFrame = new ArrayList<>(0);

    public DetectResultPool() {
        pool = new DetectResult[POOL_SIZE];
        for (int i = 0; i < POOL_SIZE; i++) {
            pool[i] = new DetectResult();
        }
        index = 0;
    }

    /** 开始新一Frame，重置池 */
    public void beginFrame() {
        currentFrame.clear();
        index = 0;
    }

    /** 从池中获取一个可用对象 */
    public DetectResult obtain() {
        if (index >= POOL_SIZE) return null;  // 超出池大小，丢弃
        DetectResult r = pool[index++];
        r.reset();
        return r;
    }

    /** 将Hasil加入当前Frame */
    public void addResult(DetectResult r) {
        if (r != null) {
            currentFrame.add(r);
        }
    }

    /** 提交当前FrameHasil供 UI 读取 */
    public void commitFrame() {
        // 创建快照供 UI Thread安全读取
        displayFrame = new ArrayList<>(currentFrame);
    }

    /** UI Thread读取最新Hasil */
    public List<DetectResult> getDisplayResults() {
        return displayFrame;
    }
}
