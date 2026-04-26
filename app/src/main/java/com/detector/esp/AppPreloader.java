package com.detector.esp;

import android.content.Context;
import android.util.Log;

import com.detector.esp.detector.YoloDetector;
import com.detector.esp.preprocess.CpuPreprocessor;
import com.detector.esp.utils.DetectResultPool;
import com.detector.esp.utils.DetectionStabilizer;

/**
 * 全局Prauat器 — 在启动动画期间后台Inisialisasi所有组件
 */
public class AppPreloader {

    private static final String TAG = "Preloader";

    // Prauat的组件（静态持有，MainActivity 直接取用）
    public static volatile YoloDetector detector;
    public static volatile CpuPreprocessor preprocessor;
    public static volatile DetectResultPool resultPool;
    public static volatile DetectionStabilizer stabilizer;
    public static volatile boolean ready = false;
    public static volatile String currentStep = "";
    public static volatile float progress = 0f;

    /**
     * 后台Thread调用：Memuat所有组件
     */
    public static void preload(Context context) {
        try {
            currentStep = "Loading AI model...";
            progress = 0.1f;
            Log.i(TAG, currentStep);
            detector = new YoloDetector(context);

            currentStep = "Initializing preprocessor...";
            progress = 0.5f;
            Log.i(TAG, currentStep);
            preprocessor = new CpuPreprocessor(detector.getInputSize());

            currentStep = "Allocating buffers...";
            progress = 0.7f;
            Log.i(TAG, currentStep);
            resultPool = new DetectResultPool();
            stabilizer = new DetectionStabilizer();

            currentStep = "System ready";
            progress = 1.0f;
            ready = true;
            Log.i(TAG, "Prauat完成");
        } catch (Exception e) {
            Log.e(TAG, "PrauatGagal", e);
            currentStep = "LOAD FAILED: " + e.getMessage();
        }
    }

    /** MainActivity 取走组件后清空引用 */
    public static void consume() {
        // 不清空，让 MainActivity 直接用 static 引用
        // ready 保持 true，防止重复Memuat
    }
}
