package com.detector.esp.detector;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

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
 * 深度扫描器 — 用 YOLOv8s 大Model + 多尺度 + 低阈值
 *
 * 不要求速度，要求精度。拍照后慢慢扫：
 * 1. 全图扫描（480x480）
 * 2. 四象限分块扫描（每块单独Zoom到 480x480）
 * 3. 极低阈值（0.1）
 * 4. 合并所有Hasil NMS 去重
 */
public class DeepScanner {

    private static final String TAG = "DeepScanner";
    private static final String MODEL_FILE = "yolov8x_fp16.tflite";
    private static final int INPUT_SIZE = 640;  // YOLOv8x 用 640x640
    private static final int NUM_CLASSES = 80;
    private static final int NUM_BOXES = 8400;  // 640x640 对应
    private static final float DEEP_THRESHOLD = 0.50f;
    private static final float IOU_THRESHOLD = 0.45f;

    private static final int[] TARGET_CLASS_IDS = {0, 2};
    private static final String[] TARGET_LABELS = {"人物", "汽车"};

    private final Interpreter interpreter;
    private final ByteBuffer inputBuffer;
    private final float[][][] outputBuffer;

    // 查找表
    private static final float[] B2F = new float[256];
    static { for (int i = 0; i < 256; i++) B2F[i] = i / 255.0f; }

    public interface ProgressCallback {
        void onProgress(int step, int total, String message);
    }

    public DeepScanner(Context context) throws IOException {
        AssetFileDescriptor afd = context.getAssets().openFd(MODEL_FILE);
        FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
        MappedByteBuffer model = fis.getChannel().map(
                FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getDeclaredLength());

        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);
        interpreter = new Interpreter(model, options);

        inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());
        outputBuffer = new float[1][84][NUM_BOXES];

        Log.i(TAG, "深度扫描ModelMemuat: " + MODEL_FILE + " (" + INPUT_SIZE + "x" + INPUT_SIZE + ")");
    }

    /**
     * 对照片进行深度扫描，返回标注后的 Bitmap
     */
    public Bitmap scan(Bitmap photo, ProgressCallback progress) {
        int imgW = photo.getWidth();
        int imgH = photo.getHeight();
        Log.i(TAG, "深度扫描开始: " + imgW + "x" + imgH);

        // 阶段1: Letterbox 准备图像 10%
        if (progress != null) progress.onProgress(10, 100, "准备图像...");

        // Letterbox: 保持宽高比Zoom到 640x640
        float ratioW = (float) INPUT_SIZE / imgW;
        float ratioH = (float) INPUT_SIZE / imgH;
        float ratio = Math.min(ratioW, ratioH);
        int scaledW = (int) (imgW * ratio);
        int scaledH = (int) (imgH * ratio);
        int padX = (INPUT_SIZE - scaledW) / 2;
        int padY = (INPUT_SIZE - scaledH) / 2;

        float padLeft = (float) padX / INPUT_SIZE;
        float padTop = (float) padY / INPUT_SIZE;
        float contentW = (float) scaledW / INPUT_SIZE;
        float contentH = (float) scaledH / INPUT_SIZE;

        Bitmap resized = Bitmap.createScaledBitmap(photo, scaledW, scaledH, true);

        // 阶段2: Praproses（letterbox 填充灰色）20%
        if (progress != null) progress.onProgress(20, 100, "Praproses中...");
        int[] scaledPixels = new int[scaledW * scaledH];
        resized.getPixels(scaledPixels, 0, scaledW, 0, 0, scaledW, scaledH);
        resized.recycle();

        inputBuffer.rewind();
        // 逐行写入，包含灰色填充
        for (int row = 0; row < INPUT_SIZE; row++) {
            for (int col = 0; col < INPUT_SIZE; col++) {
                int srcRow = row - padY;
                int srcCol = col - padX;
                if (srcRow >= 0 && srcRow < scaledH && srcCol >= 0 && srcCol < scaledW) {
                    int pixel = scaledPixels[srcRow * scaledW + srcCol];
                    inputBuffer.putFloat(B2F[(pixel >> 16) & 0xFF]);
                    inputBuffer.putFloat(B2F[(pixel >> 8) & 0xFF]);
                    inputBuffer.putFloat(B2F[pixel & 0xFF]);
                } else {
                    // 灰色填充
                    inputBuffer.putFloat(0.5f);
                    inputBuffer.putFloat(0.5f);
                    inputBuffer.putFloat(0.5f);
                }
            }
        }
        inputBuffer.rewind();

        // 阶段3: YOLOv8x Inferensi 30%→80%
        if (progress != null) progress.onProgress(30, 100, "YOLOv8x 高精度Inferensi中...");
        interpreter.run(inputBuffer, outputBuffer);

        // 阶段4: 后处理 85%
        if (progress != null) progress.onProgress(85, 100, "分析DeteksiHasil...");
        float[][] output = outputBuffer[0];
        List<DetectionBox> rawResults = new ArrayList<>();

        for (int i = 0; i < NUM_BOXES; i++) {
            float cx = output[0][i];
            float cy = output[1][i];
            float w = output[2][i];
            float h = output[3][i];

            float maxScore = 0f;
            int maxClassId = -1;
            for (int c = 0; c < NUM_CLASSES; c++) {
                float score = output[4 + c][i];
                if (score > maxScore) { maxScore = score; maxClassId = c; }
            }
            if (maxScore < DEEP_THRESHOLD) continue;

            String label = null;
            for (int t = 0; t < TARGET_CLASS_IDS.length; t++) {
                if (maxClassId == TARGET_CLASS_IDS[t]) { label = TARGET_LABELS[t]; break; }
            }
            if (label == null) continue;

            // 像素坐标 → 归一化 → 去除 letterbox padding → 原图坐标
            float normLeft = (cx - w / 2f) / INPUT_SIZE;
            float normTop = (cy - h / 2f) / INPUT_SIZE;
            float normRight = (cx + w / 2f) / INPUT_SIZE;
            float normBottom = (cy + h / 2f) / INPUT_SIZE;

            // 去掉 letterbox padding
            float realLeft = (normLeft - padLeft) / contentW;
            float realTop = (normTop - padTop) / contentH;
            float realRight = (normRight - padLeft) / contentW;
            float realBottom = (normBottom - padTop) / contentH;

            rawResults.add(new DetectionBox(
                    clamp(realLeft), clamp(realTop),
                    clamp(realRight), clamp(realBottom),
                    maxClassId, label, maxScore));
        }

        // 阶段5: NMS 去重 90%
        if (progress != null) progress.onProgress(90, 100, "去重过滤...");
        List<DetectionBox> finalResults = nms(rawResults);
        Log.i(TAG, "深度扫描完成: " + finalResults.size() + " 个Target");

        // 阶段6: GambarHasil 95%→100%
        if (progress != null) progress.onProgress(95, 100, "Gambar标注...");
        Bitmap result = drawResults(photo, finalResults);

        if (progress != null) progress.onProgress(100, 100, "完成！发现 " + finalResults.size() + " 个Target");
        return result;
    }

    private List<DetectionBox> detectRegion(Bitmap photo, int x1, int y1, int x2, int y2) {
        int regionW = x2 - x1;
        int regionH = y2 - y1;
        if (regionW <= 0 || regionH <= 0) return Collections.emptyList();

        // 裁切区域并Zoom到Model输入
        Bitmap region = Bitmap.createBitmap(photo, x1, y1, regionW, regionH);
        Bitmap resized = Bitmap.createScaledBitmap(region, INPUT_SIZE, INPUT_SIZE, true);
        if (region != photo) region.recycle();

        // 转 float32 输入
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        resized.recycle();

        inputBuffer.rewind();
        for (int pixel : pixels) {
            inputBuffer.putFloat(B2F[(pixel >> 16) & 0xFF]);
            inputBuffer.putFloat(B2F[(pixel >> 8) & 0xFF]);
            inputBuffer.putFloat(B2F[pixel & 0xFF]);
        }
        inputBuffer.rewind();

        // Inferensi
        interpreter.run(inputBuffer, outputBuffer);

        // 后处理：坐标映射回原图
        List<DetectionBox> results = new ArrayList<>();
        float[][] output = outputBuffer[0];
        int imgW = photo.getWidth();
        int imgH = photo.getHeight();

        for (int i = 0; i < NUM_BOXES; i++) {
            float cx = output[0][i];
            float cy = output[1][i];
            float w = output[2][i];
            float h = output[3][i];

            float maxScore = 0f;
            int maxClassId = -1;
            for (int c = 0; c < NUM_CLASSES; c++) {
                float score = output[4 + c][i];
                if (score > maxScore) { maxScore = score; maxClassId = c; }
            }

            if (maxScore < DEEP_THRESHOLD) continue;

            String label = null;
            for (int t = 0; t < TARGET_CLASS_IDS.length; t++) {
                if (maxClassId == TARGET_CLASS_IDS[t]) { label = TARGET_LABELS[t]; break; }
            }
            if (label == null) continue;

            // Model输出是像素坐标(0-480)，先归一化到[0,1]
            float localLeft = (cx - w / 2f) / INPUT_SIZE;
            float localTop = (cy - h / 2f) / INPUT_SIZE;
            float localRight = (cx + w / 2f) / INPUT_SIZE;
            float localBottom = (cy + h / 2f) / INPUT_SIZE;

            // 映射到原图像素坐标
            float absLeft = x1 + localLeft * regionW;
            float absTop = y1 + localTop * regionH;
            float absRight = x1 + localRight * regionW;
            float absBottom = y1 + localBottom * regionH;

            // 归一化到原图 [0,1]
            results.add(new DetectionBox(
                    clamp(absLeft / imgW), clamp(absTop / imgH),
                    clamp(absRight / imgW), clamp(absBottom / imgH),
                    maxClassId, label, maxScore
            ));
        }

        return results;
    }

    private List<DetectionBox> nms(List<DetectionBox> detections) {
        if (detections.isEmpty()) return detections;
        Collections.sort(detections, (a, b) -> Float.compare(b.confidence, a.confidence));
        List<DetectionBox> kept = new ArrayList<>();

        while (!detections.isEmpty()) {
            DetectionBox best = detections.remove(0);
            kept.add(best);
            Iterator<DetectionBox> it = detections.iterator();
            while (it.hasNext()) {
                DetectionBox other = it.next();
                float overlap = iou(best, other);
                if ((best.classId == other.classId && overlap > IOU_THRESHOLD) || overlap > 0.7f) {
                    it.remove();
                }
            }
        }
        return kept;
    }

    private float iou(DetectionBox a, DetectionBox b) {
        float iL = Math.max(a.left, b.left), iT = Math.max(a.top, b.top);
        float iR = Math.min(a.right, b.right), iB = Math.min(a.bottom, b.bottom);
        float iArea = Math.max(0, iR - iL) * Math.max(0, iB - iT);
        float aArea = (a.right - a.left) * (a.bottom - a.top);
        float bArea = (b.right - b.left) * (b.bottom - b.top);
        float u = aArea + bArea - iArea;
        return u > 0 ? iArea / u : 0f;
    }

    private Bitmap drawResults(Bitmap photo, List<DetectionBox> results) {
        Bitmap output = photo.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(output);
        int w = output.getWidth();
        int h = output.getHeight();

        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4f);

        Paint fillPaint = new Paint();
        fillPaint.setStyle(Paint.Style.FILL);

        Paint textBgPaint = new Paint();
        textBgPaint.setColor(Color.argb(200, 0, 0, 0));

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(36f);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        for (DetectionBox r : results) {
            int color = r.classId == 0 ? 0xFFFF1744 : 0xFF00E676;
            boxPaint.setColor(color);
            fillPaint.setColor(Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)));
            textPaint.setColor(color);

            float left = r.left * w, top = r.top * h;
            float right = r.right * w, bottom = r.bottom * h;

            canvas.drawRect(left, top, right, bottom, fillPaint);
            canvas.drawRect(left, top, right, bottom, boxPaint);

            // 四角加强
            float cLen = Math.min(right - left, bottom - top) * 0.15f;
            boxPaint.setStrokeWidth(6f);
            canvas.drawLine(left, top, left + cLen, top, boxPaint);
            canvas.drawLine(left, top, left, top + cLen, boxPaint);
            canvas.drawLine(right, top, right - cLen, top, boxPaint);
            canvas.drawLine(right, top, right, top + cLen, boxPaint);
            canvas.drawLine(left, bottom, left + cLen, bottom, boxPaint);
            canvas.drawLine(left, bottom, left, bottom - cLen, boxPaint);
            canvas.drawLine(right, bottom, right - cLen, bottom, boxPaint);
            canvas.drawLine(right, bottom, right, bottom - cLen, boxPaint);
            boxPaint.setStrokeWidth(4f);

            String label = r.label + " " + (int) (r.confidence * 100) + "%";
            float textWidth = textPaint.measureText(label);
            float ly = top > 48 ? top - 48 : top;
            canvas.drawRect(left, ly, left + textWidth + 12, ly + 48, textBgPaint);
            canvas.drawText(label, left + 6, ly + 38, textPaint);
        }

        // 底部统计
        int personCount = 0, carCount = 0;
        for (DetectionBox r : results) {
            if (r.classId == 0) personCount++;
            else carCount++;
        }
        String info = "深度扫描完成: " + personCount + " 人  " + carCount + " 车  共 " + results.size() + " 个Target";
        textPaint.setColor(0xFF00E676);
        textPaint.setTextSize(28f);
        float infoW = textPaint.measureText(info);
        canvas.drawRect(0, h - 46, infoW + 24, h, textBgPaint);
        canvas.drawText(info, 12, h - 12, textPaint);

        return output;
    }

    public void close() { interpreter.close(); }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    static class DetectionBox {
        float left, top, right, bottom, confidence;
        int classId;
        String label;

        DetectionBox(float l, float t, float r, float b, int cls, String label, float conf) {
            this.left = l; this.top = t; this.right = r; this.bottom = b;
            this.classId = cls; this.label = label; this.confidence = conf;
        }
    }
}
