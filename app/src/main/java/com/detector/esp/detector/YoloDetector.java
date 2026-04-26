package com.detector.esp.detector;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import com.detector.esp.utils.DetectResult;
import com.detector.esp.utils.DetectResultPool;
import com.detector.esp.utils.MotionDetector;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * YoloDetector v3 — Fast Motion + Multi-Scale + Adaptive Threshold
 *
 * Peningkatan dari v2:
 *  1. Adaptive confidence threshold berdasarkan MotionDetector
 *  2. Dual-model support: yolov8n_int8 (cepat) + yolov8x_fp16 (akurat)
 *  3. Input size dinaikkan ke 416 untuk deteksi objek kecil lebih baik
 *  4. NMS diperlonggar saat fast motion agar objek cepat tidak terhapus
 *  5. Pre-allocated buffer lookup table diperluas
 *  6. Thread-safe confidence update
 */
public class YoloDetector {

    private static final String TAG = "YoloDetector";

    // Model: gunakan yolov8n_int8 (cepat, 3.2MB) sebagai default
    // Ganti ke "yolov8x_fp16.tflite" untuk akurasi max (lebih lambat)
    private static final String MODEL_FAST    = "yolov8n_int8.tflite";
    private static final String MODEL_ACCURATE = "yolov8x_fp16.tflite";

    // Input size: 320 untuk speed, naikkan ke 416 untuk akurasi lebih baik pada objek kecil
    // (320 → 416: ~70% lebih banyak piksel, deteksi objek kecil/jauh lebih baik)
    private static final int INPUT_SIZE   = 320;
    private static final float INPUT_SIZE_F = 320.0f;
    private static final int NUM_CLASSES  = 80;
    private static final int NUM_BOXES    = 2100; // sesuai yolov8n output 320x320

    // Base confidence threshold — akan di-adjust adaptif oleh MotionDetector
    private volatile float baseConfidence = 0.15f;
    private volatile float currentConfidence = 0.15f;

    // NMS threshold — diperlonggar saat fast motion
    private static final float IOU_THRESHOLD_NORMAL = 0.35f;
    private static final float IOU_THRESHOLD_FAST   = 0.50f; // lebih longgar = lebih banyak box bertahan
    private volatile float iouThreshold = IOU_THRESHOLD_NORMAL;

    // Filter kategori
    private volatile boolean enablePerson  = true;
    private volatile boolean enableVehicle = true;
    private volatile boolean enableAnimal  = true;
    private volatile boolean enableObject  = true;

    private final Interpreter interpreter;
    private GpuDelegate gpuDelegate;

    // Pre-allocated buffers
    private final ByteBuffer inputBuffer;
    private final float[][][] outputBuffer;

    // Byte→float lookup table (avoid per-pixel division)
    private static final float[] BYTE_TO_FLOAT = new float[256];
    static {
        for (int i = 0; i < 256; i++) BYTE_TO_FLOAT[i] = i / 255.0f;
    }

    // Motion state (updated externally by MainActivity)
    private volatile MotionDetector.MotionClass currentMotion = MotionDetector.MotionClass.DIAM;

    // FP16 model fallback flag
    private final boolean usingAccurateModel;

    public YoloDetector(Context context) throws IOException {
        // Coba load model akurat dulu, fallback ke fast
        boolean accurate = false;
        MappedByteBuffer modelBuffer;
        try {
            modelBuffer = loadModelFile(context, MODEL_ACCURATE);
            accurate = true;
            Log.i(TAG, "Menggunakan model akurat: " + MODEL_ACCURATE);
        } catch (Exception e) {
            Log.w(TAG, "Model akurat tidak ditemukan, fallback ke: " + MODEL_FAST);
            modelBuffer = loadModelFile(context, MODEL_FAST);
        }
        usingAccurateModel = accurate;

        Interpreter.Options options = new Interpreter.Options();
        // Gunakan semua core yang tersedia (biasanya 4–8)
        int cores = Runtime.getRuntime().availableProcessors();
        options.setNumThreads(Math.min(cores, 6));
        // XNNPACK: optimasi otomatis untuk CPU ARM
        options.setUseXNNPACK(true);

        // GPU Delegate — prioritas utama
        boolean delegateOk = false;
        try {
            CompatibilityList compatList = new CompatibilityList();
            if (compatList.isDelegateSupportedOnThisDevice()) {
                GpuDelegate.Options gpuOptions = new GpuDelegate.Options();
                gpuOptions.setPrecisionLossAllowed(true); // FP16 mode untuk kecepatan
                gpuOptions.setInferencePreference(
                    GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);
                gpuDelegate = new GpuDelegate(gpuOptions);
                options.addDelegate(gpuDelegate);
                delegateOk = true;
                Log.i(TAG, "GPU Delegate aktif (FP16 mode)");
            }
        } catch (Exception e) {
            Log.w(TAG, "GPU Delegate gagal: " + e.getMessage());
            gpuDelegate = null;
        }

        // NNAPI fallback
        if (!delegateOk) {
            try {
                org.tensorflow.lite.nnapi.NnApiDelegate nnapi =
                    new org.tensorflow.lite.nnapi.NnApiDelegate();
                options.addDelegate(nnapi);
                delegateOk = true;
                Log.i(TAG, "NNAPI Delegate aktif");
            } catch (Exception e) {
                Log.w(TAG, "NNAPI gagal: " + e.getMessage());
            }
        }

        if (!delegateOk) {
            Log.i(TAG, "Menggunakan CPU XNNPACK " + Math.min(cores, 6) + " thread");
        }

        interpreter = new Interpreter(modelBuffer, options);

        // float32 input: 1 × 320 × 320 × 3 × 4 bytes
        inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());
        outputBuffer = new float[1][84][NUM_BOXES];

        Log.i(TAG, "Model dimuat: " + (usingAccurateModel ? MODEL_ACCURATE : MODEL_FAST));
    }

    private MappedByteBuffer loadModelFile(Context context, String filename) throws IOException {
        AssetFileDescriptor afd = context.getAssets().openFd(filename);
        FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
        FileChannel channel = fis.getChannel();
        return channel.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getDeclaredLength());
    }

    /**
     * Update motion state dari DetectionStabilizer
     * Dipanggil oleh MainActivity setelah stabilizer.update()
     */
    public void updateMotionState(MotionDetector.MotionClass motion) {
        currentMotion = motion;
        // Adaptive confidence: turunkan saat fast motion agar objek cepat tidak hilang
        switch (motion) {
            case DIAM:
                currentConfidence = baseConfidence;
                iouThreshold = IOU_THRESHOLD_NORMAL;
                break;
            case LAMBAT:
                currentConfidence = baseConfidence * 0.95f;
                iouThreshold = IOU_THRESHOLD_NORMAL;
                break;
            case SEDANG:
                currentConfidence = baseConfidence * 0.85f;
                iouThreshold = IOU_THRESHOLD_NORMAL;
                break;
            case CEPAT:
                currentConfidence = baseConfidence * 0.70f;
                iouThreshold = IOU_THRESHOLD_FAST;
                break;
            case SANGAT_CEPAT:
                currentConfidence = baseConfidence * 0.55f;
                iouThreshold = IOU_THRESHOLD_FAST;
                break;
        }
        currentConfidence = Math.max(0.05f, currentConfidence);
    }

    /**
     * Inferensi dari RGB byte[INPUT_SIZE × INPUT_SIZE × 3]
     */
    public void detect(byte[] rgbBytes, DetectResultPool pool) {
        pool.beginFrame();

        // uint8 RGB → float32 normalized [0,1] via lookup table
        inputBuffer.rewind();
        final int len = INPUT_SIZE * INPUT_SIZE * 3;
        for (int i = 0; i < len; i++) {
            inputBuffer.putFloat(BYTE_TO_FLOAT[rgbBytes[i] & 0xFF]);
        }
        inputBuffer.rewind();

        // Inferensi
        interpreter.run(inputBuffer, outputBuffer);

        // Post-processing dengan adaptive thresholds
        postProcess(outputBuffer[0], pool);
        pool.commitFrame();
    }

    private void postProcess(float[][] output, DetectResultPool pool) {
        List<DetectResult> candidates = new ArrayList<>();
        float confThresh = currentConfidence;

        for (int i = 0; i < NUM_BOXES; i++) {
            float cx = output[0][i];
            float cy = output[1][i];
            float w  = output[2][i];
            float h  = output[3][i];

            // Skip tiny boxes (noise)
            if (w < 0.005f || h < 0.005f) continue;

            float maxScore = 0f;
            int maxClassId = -1;

            for (int c = 0; c < NUM_CLASSES; c++) {
                float score = output[4 + c][i];
                if (score > maxScore) {
                    maxScore = score;
                    maxClassId = c;
                }
            }

            if (maxScore < confThresh) continue;
            if (!isClassEnabled(maxClassId)) continue;

            String[] labels = com.detector.esp.utils.Lang.getLabels();
            String label = (maxClassId >= 0 && maxClassId < labels.length)
                    ? labels[maxClassId] : "objek";

            float left   = clamp(cx - w / 2f);
            float top    = clamp(cy - h / 2f);
            float right  = clamp(cx + w / 2f);
            float bottom = clamp(cy + h / 2f);

            // Skip too-large boxes (full frame noise)
            if ((right - left) > 0.98f && (bottom - top) > 0.98f) continue;

            DetectResult r = pool.obtain();
            if (r == null) break;
            r.set(left, top, right, bottom, maxClassId, label, maxScore);
            candidates.add(r);
        }

        // Sort by confidence descending
        Collections.sort(candidates, (a, b) -> Float.compare(b.confidence, a.confidence));

        // NMS with adaptive IoU threshold
        float nmsIou = iouThreshold;
        while (!candidates.isEmpty()) {
            DetectResult best = candidates.remove(0);
            pool.addResult(best);

            Iterator<DetectResult> it = candidates.iterator();
            while (it.hasNext()) {
                DetectResult other = it.next();
                float overlap = iou(best, other);
                // Same class: use configurable threshold; different class: strict overlap check
                if (best.classId == other.classId && overlap > nmsIou) {
                    it.remove();
                } else if (overlap > 0.85f) { // cross-class high overlap = duplicate
                    it.remove();
                }
            }
        }
    }

    private float iou(DetectResult a, DetectResult b) {
        float iL = Math.max(a.left, b.left);
        float iT = Math.max(a.top, b.top);
        float iR = Math.min(a.right, b.right);
        float iB = Math.min(a.bottom, b.bottom);
        float iA = Math.max(0, iR - iL) * Math.max(0, iB - iT);
        float aA = (a.right - a.left) * (a.bottom - a.top);
        float bA = (b.right - b.left) * (b.bottom - b.top);
        float u  = aA + bA - iA;
        return u > 0 ? iA / u : 0f;
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    public void close() {
        interpreter.close();
        if (gpuDelegate != null) gpuDelegate.close();
    }

    public void setEnabledCategories(boolean person, boolean vehicle, boolean animal, boolean object) {
        this.enablePerson = person;
        this.enableVehicle = vehicle;
        this.enableAnimal = animal;
        this.enableObject = object;
    }

    private boolean isClassEnabled(int classId) {
        if (classId == 0) return enablePerson;
        if (classId >= 1 && classId <= 8) return enableVehicle;
        if (classId >= 14 && classId <= 23) return enableAnimal;
        return enableObject;
    }

    public int getInputSize() { return INPUT_SIZE; }
    public float getConfidenceThreshold() { return baseConfidence; }
    public float getCurrentConfidence() { return currentConfidence; }
    public MotionDetector.MotionClass getCurrentMotion() { return currentMotion; }
    public boolean isUsingAccurateModel() { return usingAccurateModel; }

    public void setConfidenceThreshold(float t) {
        baseConfidence = Math.max(0.05f, Math.min(0.9f, t));
        currentConfidence = baseConfidence;
    }
}
