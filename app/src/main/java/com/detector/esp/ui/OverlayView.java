package com.detector.esp.ui;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import com.detector.esp.utils.DetectResult;
import com.detector.esp.utils.MotionDetector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ESP Overlay Modern v3 — 60fps interpolasi, Fast Motion adaptif, UI modern
 *
 * 检测线程以 ~20fps 推送新结果，OverlayView 用 Choreographer 以 60fps
 * 在两次检测之间对框坐标做线性插值，实现丝滑移动。
 */
public class OverlayView extends View implements Choreographer.FrameCallback {

    // ESP 颜色
    private static final int COLOR_PERSON = 0xFFFF2D55;
    private static final int COLOR_CAR = 0xFF00F08A;
    private static final int COLOR_ESP_GREEN = 0xFF00F080;
    private static final int COLOR_ANIMAL = 0xFFFFE000;
    private static final int COLOR_OBJECT = 0xFF18D4EE;

    /** 根据 classId 返回对应颜色 */
    private static int getColorForClass(int classId) {
        if (classId == 0) return COLOR_PERSON;
        if (classId >= 1 && classId <= 8) return COLOR_CAR;
        if (classId >= 14 && classId <= 23) return COLOR_ANIMAL;
        return COLOR_OBJECT;
    }

    // 画笔 — 预分配
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint();
    private final Paint fillPaint = new Paint();
    private final Paint labelBgPaint = new Paint();
    private final Paint labelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudBgPaint = new Paint();
    private final Paint hudBorderPaint = new Paint();
    private final Paint hudTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudInfoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // 游戏外挂特效画笔
    private final Paint snaplinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint distPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint healthBarBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint healthBarFg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radarBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radarGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radarDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radarSweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint predPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint predArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Skeleton / Arm / Head paints
    private final Paint skeletonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint armPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint jointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Mod Menu paints
    private final Paint menuBgPaint = new Paint();
    private final Paint menuBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint menuTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint menuItemPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint menuOnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint menuOffPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint menuSectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long startTime = System.currentTimeMillis();

    // GPS 数据
    private volatile double gpsLat = 0, gpsLon = 0;
    private volatile float gpsSpeed = 0;  // m/s
    private volatile int gpsSatellites = 0;
    private volatile boolean gpsAvailable = false;

    // 双缓冲：prev 和 current 两组结果，60fps 在它们之间插值
    private List<DetectResult> prevResults = Collections.emptyList();
    private List<DetectResult> currentResults = Collections.emptyList();
    private long prevTime = 0;
    private long currentTime = 0;

    // 当前插值后的渲染结果
    private final List<float[]> interpBoxes = new ArrayList<>(); // [left, top, right, bottom, classId, confidence]
    private final List<String> interpLabels = new ArrayList<>();
    private final List<DetectResult> interpResults = new ArrayList<>(); // 带轨迹数据的完整结果

    private volatile int detectFps;
    private volatile float latencyMs;
    private volatile float currentZoom = 1.0f;
    private volatile float vFovTanHalf = 0.839f;  // 默认值，会被动态更新
    private boolean rendering = false;

    // Motion state — diupdate dari MainActivity
    private volatile MotionDetector.MotionClass motionState = MotionDetector.MotionClass.DIAM;
    private volatile float currentConfidence = 0.15f;

    // ===== Mod Menu flags (diatur dari MainActivity) =====
    public volatile boolean showHeadDot       = true;   // titik kepala di atas box
    public volatile boolean showSkeleton      = true;   // garis skeleton / pose estimasi
    public volatile boolean showArmLines      = true;   // garis tangan/lengan
    public volatile boolean showSnaplines     = true;
    public volatile boolean showHealthBar     = true;
    public volatile boolean showRadar         = true;
    public volatile boolean showTrail         = true;
    public volatile boolean showPrediction    = true;
    public volatile boolean showDistance      = true;
    public volatile boolean showLockIndicator = true;
    public volatile int     boxStyle          = 0;  // 0=corner, 1=full, 2=dashed
    // Mod Menu UI state
    private volatile boolean modMenuOpen = false;

    // Warna motion state
    private static final int COLOR_MOTION_DIAM    = 0xFF00E676; // hijau
    private static final int COLOR_MOTION_LAMBAT  = 0xFF64DD17; // hijau-kuning
    private static final int COLOR_MOTION_SEDANG  = 0xFFFFD600; // kuning
    private static final int COLOR_MOTION_CEPAT   = 0xFFFF6D00; // oranye
    private static final int COLOR_MOTION_VFAST   = 0xFFFF1744; // merah

    // 渲染帧率统计
    private int renderFrameCount = 0;
    private long lastRenderFpsTime = 0;
    private int renderFps = 0;

    public OverlayView(Context context) { this(context, null); }
    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        initPaints();
    }

    private void initPaints() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3f);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(7f);
        glowPaint.setMaskFilter(new BlurMaskFilter(14f, BlurMaskFilter.Blur.OUTER));

        fillPaint.setStyle(Paint.Style.FILL);

        labelBgPaint.setColor(Color.argb(200, 0, 0, 0));
        labelBgPaint.setStyle(Paint.Style.FILL);

        labelTextPaint.setTextSize(30f);
        labelTextPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStrokeWidth(1.5f);

        hudBgPaint.setColor(Color.argb(180, 0, 5, 3));
        hudBgPaint.setStyle(Paint.Style.FILL);

        hudBorderPaint.setColor(Color.argb(100, 0, 255, 0));
        hudBorderPaint.setStyle(Paint.Style.STROKE);
        hudBorderPaint.setStrokeWidth(1.5f);

        hudTitlePaint.setColor(COLOR_ESP_GREEN);
        hudTitlePaint.setTextSize(28f);
        hudTitlePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        hudInfoPaint.setColor(Color.WHITE);
        hudInfoPaint.setTextSize(23f);
        hudInfoPaint.setTypeface(Typeface.MONOSPACE);

        hudDotPaint.setStyle(Paint.Style.FILL);

        // 游戏外挂特效
        snaplinePaint.setStyle(Paint.Style.STROKE);
        snaplinePaint.setStrokeWidth(1.5f);

        headDotPaint.setStyle(Paint.Style.FILL);

        distPaint.setTextSize(22f);
        distPaint.setTypeface(Typeface.MONOSPACE);
        distPaint.setTextAlign(Paint.Align.CENTER);

        healthBarBg.setColor(0x66000000);
        healthBarBg.setStyle(Paint.Style.FILL);
        healthBarFg.setStyle(Paint.Style.FILL);

        radarBgPaint.setColor(0x88000000);
        radarBgPaint.setStyle(Paint.Style.FILL);

        radarGridPaint.setColor(0x3300FF00);
        radarGridPaint.setStyle(Paint.Style.STROKE);
        radarGridPaint.setStrokeWidth(1f);

        radarDotPaint.setStyle(Paint.Style.FILL);

        radarSweepPaint.setColor(0x4400FF00);
        radarSweepPaint.setStyle(Paint.Style.FILL);

        lockPaint.setColor(0xFFFF1744);
        lockPaint.setStyle(Paint.Style.STROKE);
        lockPaint.setStrokeWidth(2f);
        lockPaint.setTextSize(20f);
        lockPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        lockPaint.setTextAlign(Paint.Align.CENTER);

        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);

        predPaint.setStyle(Paint.Style.STROKE);
        predPaint.setStrokeWidth(2f);
        predPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 6}, 0));

        predArrowPaint.setStyle(Paint.Style.FILL);

        // Skeleton / pose
        skeletonPaint.setStyle(Paint.Style.STROKE);
        skeletonPaint.setStrokeCap(Paint.Cap.ROUND);
        skeletonPaint.setStrokeWidth(2.5f);

        armPaint.setStyle(Paint.Style.STROKE);
        armPaint.setStrokeCap(Paint.Cap.ROUND);
        armPaint.setStrokeWidth(2f);

        jointPaint.setStyle(Paint.Style.FILL);

        // Mod Menu
        menuBgPaint.setColor(Color.argb(220, 0, 0, 0));
        menuBgPaint.setStyle(Paint.Style.FILL);

        menuBorderPaint.setColor(0xFF7C3AED);
        menuBorderPaint.setStyle(Paint.Style.STROKE);
        menuBorderPaint.setStrokeWidth(2f);

        menuTitlePaint.setColor(0xFF7C3AED);
        menuTitlePaint.setTextSize(26f);
        menuTitlePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        menuItemPaint.setColor(0xFFFFFFFF);
        menuItemPaint.setTextSize(22f);
        menuItemPaint.setTypeface(Typeface.MONOSPACE);

        menuOnPaint.setColor(0xFF00E676);
        menuOnPaint.setTextSize(22f);
        menuOnPaint.setTypeface(Typeface.MONOSPACE);

        menuOffPaint.setColor(0xFFFF1744);
        menuOffPaint.setTextSize(22f);
        menuOffPaint.setTypeface(Typeface.MONOSPACE);

        menuSectionPaint.setColor(0xFFFFD600);
        menuSectionPaint.setTextSize(20f);
        menuSectionPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }

    /** 启动 60fps 渲染循环 */
    public void startRendering() {
        if (!rendering) {
            rendering = true;
            lastRenderFpsTime = System.currentTimeMillis();
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /** 停止渲染循环 */
    public void stopRendering() {
        rendering = false;
    }

    private long lastFrameNanos = 0;
    private static final long FRAME_INTERVAL_NANOS = 33_333_333L; // 30fps = 33.3ms

    /** Choreographer 回调 — 每隔一个 VSync 渲染一次（30fps，省 GPU 给推理） */
    @Override
    public void doFrame(long frameTimeNanos) {
        if (!rendering) return;

        // 30fps 节流：跳过间隔不够的帧
        if (frameTimeNanos - lastFrameNanos >= FRAME_INTERVAL_NANOS) {
            lastFrameNanos = frameTimeNanos;

            // 统计渲染帧率
            renderFrameCount++;
            long now = System.currentTimeMillis();
            if (now - lastRenderFpsTime >= 1000) {
                renderFps = renderFrameCount;
                renderFrameCount = 0;
                lastRenderFpsTime = now;
            }

            // 触发重绘
            invalidate();
        }

        // 始终注册下一帧（让 Choreographer 保持运行）
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void setMotionState(MotionDetector.MotionClass state, float conf) {
        motionState = state;
        currentConfidence = conf;
    }

    public void setCurrentZoom(float zoom) { this.currentZoom = zoom; }
    public void setFov(float vFovDegrees) { this.vFovTanHalf = (float) Math.tan(Math.toRadians(vFovDegrees / 2)); }

    public void setGpsData(double lat, double lon, float speed, int satellites) {
        this.gpsLat = lat;
        this.gpsLon = lon;
        this.gpsSpeed = speed;
        this.gpsSatellites = satellites;
        this.gpsAvailable = true;
    }

    public void setGpsSpeed(float speed) {
        this.gpsSpeed = speed;
    }

    private int getMotionColor() {
        switch (motionState) {
            case DIAM:        return COLOR_MOTION_DIAM;
            case LAMBAT:      return COLOR_MOTION_LAMBAT;
            case SEDANG:      return COLOR_MOTION_SEDANG;
            case CEPAT:       return COLOR_MOTION_CEPAT;
            case SANGAT_CEPAT:return COLOR_MOTION_VFAST;
            default:          return COLOR_MOTION_DIAM;
        }
    }

    private String getMotionLabel() {
        switch (motionState) {
            case DIAM:        return "DIAM";
            case LAMBAT:      return "LAMBAT";
            case SEDANG:      return "SEDANG";
            case CEPAT:       return "CEPAT ⚡";
            case SANGAT_CEPAT:return "SANGAT CEPAT ⚡⚡";
            default:          return "DIAM";
        }
    }

    public void setGpsCoord(double lat, double lon) {
        this.gpsLat = lat;
        this.gpsLon = lon;
        this.gpsAvailable = true;
    }

    public void setGpsSatellites(int satellites) {
        this.gpsSatellites = satellites;
    }

    /** 检测线程调用：推送新检测结果（~20fps） */
    public void setResults(List<DetectResult> results, int fps, float latencyMs) {
        synchronized (this) {
            prevResults = currentResults;
            prevTime = currentTime;
            currentResults = new ArrayList<>(results);
            currentTime = System.currentTimeMillis();
        }
        this.detectFps = fps;
        this.latencyMs = latencyMs;
        // 不再 postInvalidate — Choreographer 会自动驱动刷新
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int cw = getWidth();
        int ch = getHeight();
        if (cw == 0 || ch == 0) return;

        // Kalkulasi interpolasi
        computeInterpolation();

        float pulse = (float)(0.6 + 0.4 * Math.sin((System.currentTimeMillis() - startTime) * 0.005));

        // 1) Snaplines
        if (showSnaplines) {
            for (float[] box : interpBoxes) {
                drawSnapline(canvas, box, cw, ch, pulse);
            }
        }

        // 2) ESP Box + Head/Skeleton/Arm + Distance + HealthBar
        for (int i = 0; i < interpBoxes.size(); i++) {
            float[] box = interpBoxes.get(i);
            drawESPBox(canvas, box, interpLabels.get(i), cw, ch, pulse);
            if ((int) box[4] == 0) {
                // Khusus manusia: gambar head dot, skeleton, arm
                if (showHeadDot) drawHeadDot(canvas, box, cw, ch, pulse);
                if (showSkeleton) drawSkeleton(canvas, box, cw, ch);
                if (showArmLines) drawArmLines(canvas, box, cw, ch);
            }
        }

        // 2.5) Trail + prediksi
        if (showTrail || showPrediction) {
            for (int i = 0; i < interpResults.size() && i < interpBoxes.size(); i++) {
                drawTrailAndPrediction(canvas, interpResults.get(i), interpBoxes.get(i), cw, ch);
            }
        }

        // 3) Lock indicator
        if (showLockIndicator) drawLockIndicator(canvas, cw, ch, pulse);

        // 4) Radar
        if (showRadar) drawRadar(canvas, cw, ch);

        // 5) HUD
        drawHUD(canvas, interpBoxes.size());

        // 6) Mod Menu (selalu di atas semua)
        if (modMenuOpen) drawModMenu(canvas, cw, ch);
    }

    /** 在 prev 和 current 结果之间做线性插值 */
    private void computeInterpolation() {
        interpBoxes.clear();
        interpLabels.clear();
        interpResults.clear();

        List<DetectResult> cur;
        List<DetectResult> prev;
        long ct, pt;

        synchronized (this) {
            cur = currentResults;
            prev = prevResults;
            ct = currentTime;
            pt = prevTime;
        }

        if (cur.isEmpty()) return;

        long now = System.currentTimeMillis();
        long interval = ct - pt;

        // 计算插值因子 t：0=上一帧位置，1=当前帧位置，>1=外推预测
        float t;
        if (interval <= 0 || prev.isEmpty()) {
            t = 1.0f; // 没有前一帧，直接用当前
        } else {
            t = (float)(now - pt) / interval;
            // Extrapolasi lebih agresif saat fast motion
            float maxExtrap = (motionState == MotionDetector.MotionClass.SANGAT_CEPAT) ? 2.0f :
                              (motionState == MotionDetector.MotionClass.CEPAT) ? 1.7f : 1.5f;
            t = Math.max(0f, Math.min(maxExtrap, t));
        }

        // 对 current 中每个结果尝试找 prev 中对应的框做插值
        for (DetectResult c : cur) {
            float bl = c.left, bt = c.top, br = c.right, bb = c.bottom;

            if (t < 1.0f && !prev.isEmpty()) {
                // 找最佳匹配的前一帧框
                DetectResult bestPrev = findBestMatch(c, prev);
                if (bestPrev != null) {
                    // 线性插值
                    bl = bestPrev.left + (c.left - bestPrev.left) * t;
                    bt = bestPrev.top + (c.top - bestPrev.top) * t;
                    br = bestPrev.right + (c.right - bestPrev.right) * t;
                    bb = bestPrev.bottom + (c.bottom - bestPrev.bottom) * t;
                }
            } else if (t > 1.0f && !prev.isEmpty()) {
                // 外推：基于速度预测
                DetectResult bestPrev = findBestMatch(c, prev);
                if (bestPrev != null) {
                    float extraT = t - 1.0f;
                    bl = c.left + (c.left - bestPrev.left) * extraT;
                    bt = c.top + (c.top - bestPrev.top) * extraT;
                    br = c.right + (c.right - bestPrev.right) * extraT;
                    bb = c.bottom + (c.bottom - bestPrev.bottom) * extraT;
                }
            }

            // clamp
            bl = Math.max(0, Math.min(1, bl));
            bt = Math.max(0, Math.min(1, bt));
            br = Math.max(0, Math.min(1, br));
            bb = Math.max(0, Math.min(1, bb));

            interpBoxes.add(new float[]{bl, bt, br, bb, c.classId, c.confidence});
            interpLabels.add(c.label + " " + (int)(c.confidence * 100) + "%");
            interpResults.add(c);
        }
    }

    /** 找 prev 中与 det 最匹配的框（同类别 + 最高 IoU） */
    private DetectResult findBestMatch(DetectResult det, List<DetectResult> prev) {
        float bestIou = 0.2f; // 最低匹配阈值
        DetectResult best = null;
        for (DetectResult p : prev) {
            if (p.classId != det.classId) continue;
            float iou = calcIou(det, p);
            if (iou > bestIou) {
                bestIou = iou;
                best = p;
            }
        }
        return best;
    }

    private float calcIou(DetectResult a, DetectResult b) {
        float iL = Math.max(a.left, b.left);
        float iT = Math.max(a.top, b.top);
        float iR = Math.min(a.right, b.right);
        float iB = Math.min(a.bottom, b.bottom);
        float iArea = Math.max(0, iR - iL) * Math.max(0, iB - iT);
        float aArea = (a.right - a.left) * (a.bottom - a.top);
        float bArea = (b.right - b.left) * (b.bottom - b.top);
        float u = aArea + bArea - iArea;
        return u > 0 ? iArea / u : 0f;
    }

    /** 轨迹线 + 预测虚线箭头 */
    private void drawTrailAndPrediction(Canvas canvas, DetectResult result, float[] box, int cw, int ch) {
        int color = getColorForClass((int) box[4]);

        // Trail — hanya jika showTrail aktif
        if (showTrail && result.trailLen >= 2) {
            for (int i = 1; i < result.trailLen; i++) {
                float alpha = (float) i / result.trailLen;
                trailPaint.setColor(color);
                trailPaint.setAlpha((int)(200 * alpha));
                trailPaint.setStrokeWidth(2f + alpha * 3f);
                canvas.drawLine(
                    result.trailX[i-1] * cw, result.trailY[i-1] * ch,
                    result.trailX[i] * cw, result.trailY[i] * ch,
                    trailPaint);
            }
        }

        // Prediksi — hanya jika showPrediction aktif
        if (showPrediction) {
            float speed = (float) Math.sqrt(result.velX * result.velX + result.velY * result.velY);
            if (speed > 0.003f) {
                float cx = (box[0] + box[2]) / 2f * cw;
                float cy = (box[1] + box[3]) / 2f * ch;
                float px = result.predX * cw;
                float py = result.predY * ch;

                int predColor;
                if (color == COLOR_PERSON) predColor = 0xFF00FFFF;
                else if (color == COLOR_CAR) predColor = 0xFFFF00FF;
                else if (color == COLOR_ANIMAL) predColor = 0xFF8000FF;
                else predColor = 0xFFFF4444;

                predPaint.setColor(predColor);
                predPaint.setAlpha(220);
                predPaint.setStrokeWidth(3f);
                canvas.drawLine(cx, cy, px, py, predPaint);

                float angle = (float) Math.atan2(py - cy, px - cx);
                float arrowLen = 15;
                predArrowPaint.setColor(predColor);
                predArrowPaint.setAlpha(240);
                android.graphics.Path arrow = new android.graphics.Path();
                arrow.moveTo(px, py);
                arrow.lineTo(px - arrowLen * (float) Math.cos(angle - 0.4f),
                             py - arrowLen * (float) Math.sin(angle - 0.4f));
                arrow.lineTo(px - arrowLen * (float) Math.cos(angle + 0.4f),
                             py - arrowLen * (float) Math.sin(angle + 0.4f));
                arrow.close();
                canvas.drawPath(arrow, predArrowPaint);
            }
        }
    }

    /** Snapline: 屏幕顶部中心 → 目标顶部中心 */
    private void drawSnapline(Canvas canvas, float[] box, int cw, int ch, float pulse) {
        float targetX = (box[0] + box[2]) / 2f * cw;
        float targetY = box[1] * ch;
        snaplinePaint.setColor(getColorForClass((int) box[4]));
        snaplinePaint.setAlpha(180);  // 不闪烁，固定透明度
        snaplinePaint.setStrokeWidth(4f);
        canvas.drawLine(cw / 2f, 0, targetX, targetY, snaplinePaint);
    }

    /** 锁定提示：最靠近屏幕中心的目标 */
    private void drawLockIndicator(Canvas canvas, int cw, int ch, float pulse) {
        if (interpBoxes.isEmpty()) return;

        float centerX = 0.5f, centerY = 0.5f;
        float minDist = Float.MAX_VALUE;
        float[] closest = null;

        for (float[] box : interpBoxes) {
            float bx = (box[0] + box[2]) / 2f;
            float by = (box[1] + box[3]) / 2f;
            float dist = (bx - centerX) * (bx - centerX) + (by - centerY) * (by - centerY);
            if (dist < minDist) { minDist = dist; closest = box; }
        }

        if (closest != null && minDist < 0.06f) {
            float cx = (closest[0] + closest[2]) / 2f * cw;
            float topY = closest[1] * ch;

            // "LOCKED" 文字闪烁
            if ((System.currentTimeMillis() / 500) % 2 == 0) {
                lockPaint.setStyle(Paint.Style.FILL);
                lockPaint.setAlpha((int)(255 * pulse));
                String lockText = com.detector.esp.utils.Lang.isEnglish() ? "◆ LOCKED ◆" : "◆ TERKUNCI ◆";
                canvas.drawText(lockText, cx, topY - 50, lockPaint);
                lockPaint.setStyle(Paint.Style.STROKE);
            }
        }
    }

    /** 半圆雷达：右下角，只显示前方 180° */
    private void drawRadar(Canvas canvas, int cw, int ch) {
        float radarR = 110;
        float radarX = cw - 130;
        float radarY = radarR + 20;

        // 半圆背景（上半部分）
        android.graphics.RectF oval = new android.graphics.RectF(
                radarX - radarR, radarY - radarR, radarX + radarR, radarY + radarR);
        canvas.drawArc(oval, 180, 180, true, radarBgPaint);

        // 网格弧线
        for (float f : new float[]{0.33f, 0.66f, 1.0f}) {
            android.graphics.RectF gridOval = new android.graphics.RectF(
                    radarX - radarR * f, radarY - radarR * f,
                    radarX + radarR * f, radarY + radarR * f);
            canvas.drawArc(gridOval, 180, 180, false, radarGridPaint);
        }

        // 底部水平线
        canvas.drawLine(radarX - radarR, radarY, radarX + radarR, radarY, radarGridPaint);
        // 垂直中线
        canvas.drawLine(radarX, radarY, radarX, radarY - radarR, radarGridPaint);
        // 45° 分割线
        float d45 = radarR * 0.707f;
        canvas.drawLine(radarX, radarY, radarX - d45, radarY - d45, radarGridPaint);
        canvas.drawLine(radarX, radarY, radarX + d45, radarY - d45, radarGridPaint);

        // 扫描线动画（只在上半圆 180°-360° 范围）
        float sweepAngle = 180f + ((System.currentTimeMillis() - startTime) * 0.06f) % 180f;
        float sweepEndX = radarX + radarR * (float) Math.cos(Math.toRadians(sweepAngle));
        float sweepEndY = radarY + radarR * (float) Math.sin(Math.toRadians(sweepAngle));
        radarSweepPaint.setStrokeWidth(2f);
        radarSweepPaint.setStyle(Paint.Style.STROKE);
        canvas.drawLine(radarX, radarY, sweepEndX, sweepEndY, radarSweepPaint);

        // 扫描扇形尾迹
        radarSweepPaint.setStyle(Paint.Style.FILL);
        canvas.drawArc(oval, sweepAngle - 20, 20, true, radarSweepPaint);

        // 中心点（自己）
        radarDotPaint.setColor(0xFF00FF00);
        canvas.drawCircle(radarX, radarY, 4, radarDotPaint);

        // 目标点：X=左右位置（拉宽映射），Y=距离（1m-100m 映射到雷达半径）
        int idx = 0;
        for (float[] box : interpBoxes) {
            float bx = (box[0] + box[2]) / 2f - 0.5f;  // -0.5~0.5 左右
            // 拉宽左右映射：屏幕中间一小段 → 雷达全宽
            float radarBx = bx * 3.5f;  // 放大 3.5 倍，让目标分布到雷达两侧
            radarBx = Math.max(-0.95f, Math.min(0.95f, radarBx));

            // 距离估算：用像素高度，跟标签公式完全一致
            int classId = (int) box[4];
            float realH = classId == 0 ? 1.7f : 1.5f;
            float boxPixelH = (box[3] - box[1]) * ch;
            float estDist = realH / (2f * vFovTanHalf * (boxPixelH / ch)) * currentZoom;
            estDist = Math.max(1f, Math.min(100f, estDist));
            // 1m=雷达底部，100m=雷达顶部（对数映射让近处更分散）
            float distNorm = (float)(Math.log10(estDist) / Math.log10(100));  // 0~1

            float dotX = radarX + radarBx * radarR * 0.9f;
            float dotY = radarY - distNorm * radarR * 0.9f;

            // 限制在半圆内
            float dx = dotX - radarX;
            float dy = dotY - radarY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist > radarR - 4) {
                dotX = radarX + dx / dist * (radarR - 4);
                dotY = radarY + dy / dist * (radarR - 4);
            }
            if (dotY > radarY) dotY = radarY;  // 不超过底部线

            int dotColor = getColorForClass((int) box[4]);
            radarDotPaint.setColor(dotColor);
            canvas.drawCircle(dotX, dotY, 5, radarDotPaint);

            // 雷达上画轨迹小点（如果有轨迹数据）
            if (idx < interpResults.size()) {
                DetectResult dr = interpResults.get(idx);
                if (dr.trailLen >= 2) {
                    radarDotPaint.setColor(dotColor);
                    for (int ti = 0; ti < dr.trailLen; ti++) {
                        float tbx = dr.trailX[ti] - 0.5f;
                        float tDistNorm = distNorm; // 近似用同一距离
                        float tdx = radarX + tbx * 3.5f * radarR * 0.9f;
                        float tdy = radarY - tDistNorm * radarR * 0.9f;
                        // clamp
                        float td = (float) Math.sqrt((tdx-radarX)*(tdx-radarX)+(tdy-radarY)*(tdy-radarY));
                        if (td > radarR - 4) { tdx = radarX+(tdx-radarX)/td*(radarR-4); tdy = radarY+(tdy-radarY)/td*(radarR-4); }
                        if (tdy > radarY) tdy = radarY;
                        radarDotPaint.setAlpha((int)(80 + 120f * ti / dr.trailLen));
                        canvas.drawCircle(tdx, tdy, 2, radarDotPaint);
                    }
                    radarDotPaint.setAlpha(255);
                }
            }
            idx++;
        }
    }

    private void drawESPBox(Canvas canvas, float[] box, String label, int cw, int ch, float pulse) {
        float left = box[0] * cw;
        float top = box[1] * ch;
        float right = box[2] * cw;
        float bottom = box[3] * ch;
        int classId = (int) box[4];
        float w = right - left;
        float h = bottom - top;

        if (w < 10 || h < 10) return;

        int color = getColorForClass(classId);

        // Semi-transparent fill — lebih tebal saat gerakan cepat
        float speedAlpha = motionState == MotionDetector.MotionClass.SANGAT_CEPAT ? 35 :
                           motionState == MotionDetector.MotionClass.CEPAT ? 28 : 18;
        fillPaint.setColor(Color.argb((int)speedAlpha, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawRect(left, top, right, bottom, fillPaint);

        // Glow — lebih kuat saat gerakan cepat (membantu visibilitas)
        float glowMult = motionState == MotionDetector.MotionClass.SANGAT_CEPAT ? 1.6f :
                         motionState == MotionDetector.MotionClass.CEPAT ? 1.3f : 1.0f;
        glowPaint.setColor(color);
        glowPaint.setAlpha((int)(70 * pulse * glowMult));
        glowPaint.setStrokeWidth(7f * pulse * glowMult);
        canvas.drawRect(left, top, right, bottom, glowPaint);

        // 主边框
        boxPaint.setColor(color);
        canvas.drawRect(left, top, right, bottom, boxPaint);

        // 四角加强
        float cornerLen = Math.min(w, h) * 0.18f;
        boxPaint.setStrokeWidth(4f);
        canvas.drawLine(left, top, left + cornerLen, top, boxPaint);
        canvas.drawLine(left, top, left, top + cornerLen, boxPaint);
        canvas.drawLine(right, top, right - cornerLen, top, boxPaint);
        canvas.drawLine(right, top, right, top + cornerLen, boxPaint);
        canvas.drawLine(left, bottom, left + cornerLen, bottom, boxPaint);
        canvas.drawLine(left, bottom, left, bottom - cornerLen, boxPaint);
        canvas.drawLine(right, bottom, right - cornerLen, bottom, boxPaint);
        canvas.drawLine(right, bottom, right, bottom - cornerLen, boxPaint);
        boxPaint.setStrokeWidth(3f);

        // 中心十字准星
        float cx = left + w / 2;
        float cy = top + h / 2;
        float crossLen = Math.min(w, h) * 0.08f;
        crossPaint.setColor(Color.argb(180, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawLine(cx - crossLen, cy, cx + crossLen, cy, crossPaint);
        canvas.drawLine(cx, cy - crossLen, cx, cy + crossLen, crossPaint);
        // 准星圆圈
        crossPaint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(cx, cy, crossLen * 1.5f, crossPaint);
        crossPaint.setStyle(Paint.Style.STROKE);

        // Health bar — di sisi kiri box, representasi confidence sebagai "nyawa"
        if (showHealthBar) {
            float hpBarW = 6f;
            float hpBarH = h;
            float hpFill = hpBarH * box[5];  // confidence [0..1] → tinggi bar
            // Background
            healthBarBg.setColor(0x88000000);
            canvas.drawRect(left - hpBarW - 3f, top, left - 3f, top + hpBarH, healthBarBg);
            // Foreground: hijau(>60%) → kuning(30–60%) → merah(<30%)
            float conf = box[5];
            int hpColor = conf > 0.6f ? 0xFF00E676 : conf > 0.3f ? 0xFFFFD600 : 0xFFFF1744;
            healthBarFg.setColor(hpColor);
            canvas.drawRect(left - hpBarW - 3f, top + hpBarH - hpFill, left - 3f, top + hpBarH, healthBarFg);
            // Border
            healthBarBg.setColor(Color.argb(100, Color.red(color), Color.green(color), Color.blue(color)));
            healthBarBg.setStyle(Paint.Style.STROKE);
            healthBarBg.setStrokeWidth(1f);
            canvas.drawRect(left - hpBarW - 3f, top, left - 3f, top + hpBarH, healthBarBg);
            healthBarBg.setStyle(Paint.Style.FILL);
            healthBarBg.setColor(0x88000000);
        }

        // 距离估算（基于真实物体高度 + FOV + 框占屏幕比例）
        float realHeight = classId == 0 ? 1.7f : 1.5f;  // 米
        float boxRatio = h / (float) ch;  // 框高度占屏幕比例
        float estDist = realHeight / (2f * vFovTanHalf * boxRatio) * currentZoom;
        estDist = Math.max(0.5f, Math.min(999, estDist));
        String distStr = estDist < 10 ? String.format("%.1fm", estDist) : String.format("%.0fm", estDist);

        // 标签：物体 概率% 距离m
        String fullLabel = showDistance ? label + " " + distStr : label;
        float textWidth = labelTextPaint.measureText(fullLabel);
        float pad = 6f;
        float labelTop2, labelBottom2, textY;
        if (top > 44) {
            labelTop2 = top - 40;
            labelBottom2 = top;
            textY = top - 8;
        } else {
            labelTop2 = top;
            labelBottom2 = top + 40;
            textY = top + 32;
        }
        // Modern rounded label background
        android.graphics.RectF labelRect = new android.graphics.RectF(
            left, labelTop2, Math.min(left + textWidth + pad * 2, cw), labelBottom2);
        // Semi-transparent tinted background
        labelBgPaint.setColor(Color.argb(190, Color.red(color)/4, Color.green(color)/4, Color.blue(color)/4));
        canvas.drawRoundRect(labelRect, 4, 4, labelBgPaint);
        // Colored top border
        Paint borderLine = new Paint();
        borderLine.setColor(color);
        borderLine.setAlpha(200);
        borderLine.setStrokeWidth(2f);
        borderLine.setStyle(Paint.Style.STROKE);
        canvas.drawLine(left, labelTop2, Math.min(left + textWidth + pad * 2, cw), labelTop2, borderLine);
        labelTextPaint.setColor(color);
        labelTextPaint.setFakeBoldText(true);
        canvas.drawText(fullLabel, left + pad, textY, labelTextPaint);
        labelTextPaint.setFakeBoldText(false);
    }

    private void drawHUD(Canvas canvas, int targetCount) {
        // GPS 信息需要更大的 HUD 框
        float hudBottom = gpsAvailable ? 282 : 194;
        canvas.drawRoundRect(16, 16, 340, hudBottom, 8, 8, hudBgPaint);
        canvas.drawRoundRect(16, 16, 340, hudBottom, 8, 8, hudBorderPaint);

        canvas.drawText(com.detector.esp.utils.Lang.espSystem(), 28, 48, hudTitlePaint);
        canvas.drawText("Render: " + renderFps + " fps", 28, 80, hudInfoPaint);
        canvas.drawText(String.format("Deteksi: %d fps  %.0fms", detectFps, latencyMs), 28, 108, hudInfoPaint);
        canvas.drawText(com.detector.esp.utils.Lang.targets() + ": " + targetCount, 28, 136, hudInfoPaint);

        // Motion state indicator
        int motionColor = getMotionColor();
        hudInfoPaint.setColor(motionColor);
        hudInfoPaint.setAlpha(220);
        String motionLabel = getMotionLabel();
        canvas.drawText("Gerak: " + motionLabel + "  Conf: " + String.format("%.0f%%", currentConfidence * 100), 28, 162, hudInfoPaint);
        hudInfoPaint.setColor(0xCCFFFFFF);
        hudInfoPaint.setAlpha(255);

        // GPS 信息
        if (gpsAvailable) {
            // 分割线
            hudBorderPaint.setAlpha(60);
            canvas.drawLine(28, 178, 328, 178, hudBorderPaint);
            hudBorderPaint.setAlpha(100);

            // 坐标
            hudInfoPaint.setTextSize(18f);
            canvas.drawText(String.format("%.5f, %.5f", gpsLat, gpsLon), 28, 200, hudInfoPaint);
            // 速度 + 卫星
            float speedKmh = gpsSpeed * 3.6f;  // m/s → km/h
            canvas.drawText(String.format("%.1f km/j  SAT: %d", speedKmh, gpsSatellites), 28, 224, hudInfoPaint);

            // Status satelit
            hudDotPaint.setColor(gpsSatellites >= 6 ? Color.GREEN : gpsSatellites >= 3 ? Color.YELLOW : Color.RED);
            canvas.drawCircle(318, 216, 5, hudDotPaint);

            hudInfoPaint.setTextSize(23f);
        }

        // 系统状态灯
        hudDotPaint.setColor(renderFps > 45 ? Color.GREEN : renderFps > 20 ? Color.YELLOW : Color.RED);
        canvas.drawCircle(318, 42, 6, hudDotPaint);
    }

    // ==================== HEAD DOT ====================

    /**
     * Titik kepala berwarna cerah di bagian atas bounding box manusia.
     * Estimasi: kepala ≈ 13% tinggi total tubuh dari atas box.
     * Ditambah cincin + crosshair kecil agar mudah diarahkan.
     */
    private void drawHeadDot(Canvas canvas, float[] box, int cw, int ch, float pulse) {
        float left  = box[0] * cw;
        float top   = box[1] * ch;
        float right = box[2] * cw;
        float bh    = (box[3] - box[1]) * ch;

        float cx = (left + right) / 2f;
        // Kepala ≈ 13% tinggi box dari atas (proporsi tubuh manusia rata-rata)
        float headRadius = bh * 0.10f;
        float headCy = top + headRadius * 1.1f;

        // Glow
        headDotPaint.setColor(0xFFFF2D55);
        headDotPaint.setAlpha((int)(160 * pulse));
        headDotPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, headCy, headRadius * 1.6f, headDotPaint);

        // Isi merah solid
        headDotPaint.setAlpha(255);
        canvas.drawCircle(cx, headCy, headRadius, headDotPaint);

        // Ring luar putih
        headDotPaint.setStyle(Paint.Style.STROKE);
        headDotPaint.setColor(0xFFFFFFFF);
        headDotPaint.setAlpha(200);
        headDotPaint.setStrokeWidth(1.5f);
        canvas.drawCircle(cx, headCy, headRadius + 3f, headDotPaint);

        // Crosshair kecil di kepala
        headDotPaint.setColor(0xFFFF2D55);
        headDotPaint.setAlpha(220);
        headDotPaint.setStrokeWidth(1.5f);
        float cr = headRadius * 0.6f;
        canvas.drawLine(cx - cr, headCy, cx + cr, headCy, headDotPaint);
        canvas.drawLine(cx, headCy - cr, cx, headCy + cr, headDotPaint);

        // Reset
        headDotPaint.setStyle(Paint.Style.FILL);
        headDotPaint.setAlpha(255);
    }

    // ==================== SKELETON / POSE ====================

    /**
     * Estimasi pose skeleton dari bounding box manusia.
     * Karena tidak ada model keypoint, posisi dihitung secara proporsional
     * berdasarkan proporsi anatomi tubuh manusia rata-rata.
     *
     * Titik (semua koordinat dalam piksel):
     *   head (0): cx, top + 13%h
     *   neck (1): cx, top + 20%h
     *   lShoulder(2): cx-20%w, top+27%h   rShoulder(3): cx+20%w, top+27%h
     *   lElbow(4):  cx-28%w, top+43%h     rElbow(5):  cx+28%w, top+43%h
     *   lWrist(6):  cx-25%w, top+58%h     rWrist(7):  cx+25%w, top+58%h
     *   lHip(8):  cx-13%w, top+53%h       rHip(9):  cx+13%w, top+53%h
     *   lKnee(10): cx-12%w, top+72%h      rKnee(11): cx+12%w, top+72%h
     *   lAnkle(12): cx-11%w, top+90%h     rAnkle(13): cx+11%w, top+90%h
     */
    private void drawSkeleton(Canvas canvas, float[] box, int cw, int ch) {
        float left  = box[0] * cw;
        float top   = box[1] * ch;
        float right = box[2] * cw;
        float bw    = right - left;
        float bh    = (box[3] - box[1]) * ch;
        float cx    = (left + right) / 2f;
        int color   = 0xFF00F080;  // hijau ESP

        // Hitung semua titik landmark
        float[] kpX = new float[14];
        float[] kpY = new float[14];

        kpX[0]  = cx;            kpY[0]  = top + bh * 0.07f;  // head center (di bawah headDot)
        kpX[1]  = cx;            kpY[1]  = top + bh * 0.20f;  // neck
        kpX[2]  = cx - bw*0.20f; kpY[2]  = top + bh * 0.27f;  // l shoulder
        kpX[3]  = cx + bw*0.20f; kpY[3]  = top + bh * 0.27f;  // r shoulder
        kpX[4]  = cx - bw*0.28f; kpY[4]  = top + bh * 0.43f;  // l elbow
        kpX[5]  = cx + bw*0.28f; kpY[5]  = top + bh * 0.43f;  // r elbow
        kpX[6]  = cx - bw*0.25f; kpY[6]  = top + bh * 0.58f;  // l wrist
        kpX[7]  = cx + bw*0.25f; kpY[7]  = top + bh * 0.58f;  // r wrist
        kpX[8]  = cx - bw*0.13f; kpY[8]  = top + bh * 0.53f;  // l hip
        kpX[9]  = cx + bw*0.13f; kpY[9]  = top + bh * 0.53f;  // r hip
        kpX[10] = cx - bw*0.12f; kpY[10] = top + bh * 0.72f;  // l knee
        kpX[11] = cx + bw*0.12f; kpY[11] = top + bh * 0.72f;  // r knee
        kpX[12] = cx - bw*0.11f; kpY[12] = top + bh * 0.90f;  // l ankle
        kpX[13] = cx + bw*0.11f; kpY[13] = top + bh * 0.90f;  // r ankle

        skeletonPaint.setColor(color);
        skeletonPaint.setAlpha(200);
        skeletonPaint.setStrokeWidth(Math.max(1.5f, bw * 0.025f));

        // Tulang belakang: head→neck→hip center
        float hipCx = (kpX[8] + kpX[9]) / 2f;
        float hipCy = (kpY[8] + kpY[9]) / 2f;
        canvas.drawLine(kpX[0], kpY[0], kpX[1], kpY[1], skeletonPaint); // head→neck
        canvas.drawLine(kpX[1], kpY[1], hipCx,  hipCy,  skeletonPaint); // neck→hip

        // Bahu kiri-kanan
        canvas.drawLine(kpX[2], kpY[2], kpX[3], kpY[3], skeletonPaint);
        // Neck → bahu
        canvas.drawLine(kpX[1], kpY[1], kpX[2], kpY[2], skeletonPaint);
        canvas.drawLine(kpX[1], kpY[1], kpX[3], kpY[3], skeletonPaint);

        // Pinggul
        canvas.drawLine(kpX[8], kpY[8], kpX[9], kpY[9], skeletonPaint);
        canvas.drawLine(hipCx, hipCy, kpX[8], kpY[8], skeletonPaint);
        canvas.drawLine(hipCx, hipCy, kpX[9], kpY[9], skeletonPaint);

        // Kaki kiri: hip→knee→ankle
        canvas.drawLine(kpX[8],  kpY[8],  kpX[10], kpY[10], skeletonPaint);
        canvas.drawLine(kpX[10], kpY[10], kpX[12], kpY[12], skeletonPaint);
        // Kaki kanan
        canvas.drawLine(kpX[9],  kpY[9],  kpX[11], kpY[11], skeletonPaint);
        canvas.drawLine(kpX[11], kpY[11], kpX[13], kpY[13], skeletonPaint);

        // Joints (titik-titik)
        jointPaint.setColor(0xFFFFFFFF);
        jointPaint.setAlpha(180);
        float jr = Math.max(3f, bw * 0.025f);
        for (int i = 0; i < 14; i++) {
            // Skip head (sudah ada headDot), hanya gambar neck ke bawah
            if (i == 0) continue;
            canvas.drawCircle(kpX[i], kpY[i], jr, jointPaint);
        }
    }

    // ==================== ARM / HAND LINES ====================

    /**
     * Gambar tulang tangan lebih detail: shoulder→elbow→wrist + "tangan" (segitiga kecil di ujung wrist).
     * Warna berbeda dari skeleton (cyan) agar kontras.
     */
    private void drawArmLines(Canvas canvas, float[] box, int cw, int ch) {
        float left  = box[0] * cw;
        float top   = box[1] * ch;
        float right = box[2] * cw;
        float bw    = right - left;
        float bh    = (box[3] - box[1]) * ch;
        float cx    = (left + right) / 2f;

        // Titik yang sama dengan skeleton (tidak perlu di-store karena dipanggil setelah drawSkeleton)
        float lSx = cx - bw*0.20f, lSy = top + bh*0.27f;
        float rSx = cx + bw*0.20f, rSy = top + bh*0.27f;
        float lEx = cx - bw*0.28f, lEy = top + bh*0.43f;
        float rEx = cx + bw*0.28f, rEy = top + bh*0.43f;
        float lWx = cx - bw*0.25f, lWy = top + bh*0.58f;
        float rWx = cx + bw*0.25f, rWy = top + bh*0.58f;

        armPaint.setColor(0xFF18D4EE);  // cyan
        armPaint.setAlpha(210);
        float sw = Math.max(2f, bw * 0.03f);
        armPaint.setStrokeWidth(sw);

        // Lengan kiri: shoulder→elbow→wrist
        canvas.drawLine(lSx, lSy, lEx, lEy, armPaint);
        canvas.drawLine(lEx, lEy, lWx, lWy, armPaint);
        // Lengan kanan
        canvas.drawLine(rSx, rSy, rEx, rEy, armPaint);
        canvas.drawLine(rEx, rEy, rWx, rWy, armPaint);

        // "Tangan" — segitiga kecil di ujung wrist menggambarkan telapak
        float handSize = bw * 0.06f;

        // Tangan kiri
        float lAngle = (float) Math.atan2(lWy - lEy, lWx - lEx);
        drawHandTriangle(canvas, lWx, lWy, lAngle, handSize, 0xFF18D4EE);

        // Tangan kanan
        float rAngle = (float) Math.atan2(rWy - rEy, rWx - rEx);
        drawHandTriangle(canvas, rWx, rWy, rAngle, handSize, 0xFF18D4EE);

        // Joints siku + pergelangan (lebih besar)
        jointPaint.setColor(0xFF18D4EE);
        jointPaint.setAlpha(220);
        float jr = Math.max(4f, bw * 0.032f);
        canvas.drawCircle(lEx, lEy, jr, jointPaint);
        canvas.drawCircle(rEx, rEy, jr, jointPaint);
        canvas.drawCircle(lWx, lWy, jr * 0.8f, jointPaint);
        canvas.drawCircle(rWx, rWy, jr * 0.8f, jointPaint);
    }

    /** Segitiga kecil di ujung pergelangan sebagai representasi tangan */
    private void drawHandTriangle(Canvas canvas, float wx, float wy, float angle, float size, int color) {
        android.graphics.Path p = new android.graphics.Path();
        float a1 = angle + 0.5f;
        float a2 = angle - 0.5f;
        p.moveTo(wx + size * (float) Math.cos(angle),
                 wy + size * (float) Math.sin(angle));
        p.lineTo(wx + size * 0.5f * (float) Math.cos(a1),
                 wy + size * 0.5f * (float) Math.sin(a1));
        p.lineTo(wx + size * 0.5f * (float) Math.cos(a2),
                 wy + size * 0.5f * (float) Math.sin(a2));
        p.close();
        armPaint.setStyle(Paint.Style.FILL);
        armPaint.setColor(color);
        armPaint.setAlpha(180);
        canvas.drawPath(p, armPaint);
        armPaint.setStyle(Paint.Style.STROKE);
    }

    // ==================== MOD MENU ====================

    /** Toggle visibilitas mod menu */
    public void toggleModMenu() {
        modMenuOpen = !modMenuOpen;
        invalidate();
    }

    public boolean isModMenuOpen() { return modMenuOpen; }

    /**
     * Mod Menu — panel kiri layar, bisa scroll (fixed list).
     * Setiap baris = satu toggle feature dengan status ON/OFF berwarna.
     */
    private void drawModMenu(Canvas canvas, int cw, int ch) {
        float menuW = 380f;
        float rowH  = 44f;
        float padX  = 18f;
        float padY  = 14f;

        // Daftar item mod menu
        String[] sections  = { "[ DETEKSI ]", "[ VISUAL ]", "[ INFO ]" };
        String[] itemNames = {
            "Head Dot",        // 0
            "Skeleton/Pose",   // 1
            "Tangan/Lengan",   // 2
            "Snaplines",       // 3
            "Health Bar",      // 4
            "Trail Jejak",     // 5
            "Prediksi Gerak",  // 6
            "Radar",           // 7
            "Jarak (m)",       // 8
            "Lock Indicator",  // 9
        };
        boolean[] itemValues = {
            showHeadDot,
            showSkeleton,
            showArmLines,
            showSnaplines,
            showHealthBar,
            showTrail,
            showPrediction,
            showRadar,
            showDistance,
            showLockIndicator,
        };
        // Posisi section header sebelum item index tertentu
        // section[0] sebelum item 0, section[1] sebelum item 3, section[2] sebelum item 7
        int[] sectionBefore = { 0, 3, 7 };

        // Hitung total baris (item + section header)
        float totalH = padY * 2 + 38f           // judul
                + sections.length * 30f          // section headers
                + itemNames.length * rowH;       // item rows

        float menuTop = (ch - totalH) / 2f;
        menuTop = Math.max(30f, menuTop);

        // Background
        android.graphics.RectF bg = new android.graphics.RectF(10, menuTop, 10 + menuW, menuTop + totalH);
        canvas.drawRoundRect(bg, 12, 12, menuBgPaint);
        canvas.drawRoundRect(bg, 12, 12, menuBorderPaint);

        // Judul
        canvas.drawText("[ MOD MENU ]", 10 + padX, menuTop + padY + 26f, menuTitlePaint);

        float y = menuTop + padY + 26f + padY + 4f;

        int sectionIdx = 0;
        for (int i = 0; i < itemNames.length; i++) {
            // Section header?
            for (int si = 0; si < sectionBefore.length; si++) {
                if (sectionBefore[si] == i) {
                    canvas.drawText(sections[si], 10 + padX, y + 22f, menuSectionPaint);
                    y += 30f;
                    break;
                }
            }

            // Status bullet
            boolean on = itemValues[i];
            String status = on ? "● ON " : "○ OFF";
            Paint statusPaint = on ? menuOnPaint : menuOffPaint;

            // Nama item
            menuItemPaint.setColor(0xFFDDDDDD);
            canvas.drawText(itemNames[i], 10 + padX + 6f, y + rowH * 0.65f, menuItemPaint);

            // Status (kanan)
            menuOnPaint.setTextAlign(Paint.Align.RIGHT);
            menuOffPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(status, 10 + menuW - padX, y + rowH * 0.65f, statusPaint);
            menuOnPaint.setTextAlign(Paint.Align.LEFT);
            menuOffPaint.setTextAlign(Paint.Align.LEFT);

            // Garis pemisah
            menuBorderPaint.setAlpha(40);
            canvas.drawLine(10 + padX, y + rowH, 10 + menuW - padX, y + rowH, menuBorderPaint);
            menuBorderPaint.setAlpha(255);

            y += rowH;
        }

        // Petunjuk tap
        menuSectionPaint.setTextSize(18f);
        menuSectionPaint.setColor(0xFF888888);
        canvas.drawText("Tap area item → toggle  |  Tombol M → tutup", 10 + padX, y + 24f, menuSectionPaint);
        menuSectionPaint.setTextSize(20f);
        menuSectionPaint.setColor(0xFFFFD600);
    }

    /**
     * Dipanggil dari MainActivity saat user tap di atas OverlayView saat menu terbuka.
     * Deteksi item mana yang di-tap berdasarkan posisi Y.
     */
    public boolean handleModMenuTap(float tapX, float tapY, int cw, int ch) {
        if (!modMenuOpen) return false;

        float menuW = 380f;
        float rowH  = 44f;
        float padX  = 18f;
        float padY  = 14f;

        String[] itemNames = {
            "Head Dot", "Skeleton/Pose", "Tangan/Lengan",
            "Snaplines", "Health Bar",
            "Trail Jejak", "Prediksi Gerak",
            "Radar", "Jarak (m)", "Lock Indicator"
        };
        int[] sectionBefore = { 0, 3, 7 };

        float totalH = padY * 2 + 38f
                + 3 * 30f
                + itemNames.length * rowH;
        float menuTop = Math.max(30f, (ch - totalH) / 2f);

        // Cek apakah tap dalam batas menu
        if (tapX < 10 || tapX > 10 + menuW) return false;

        float y = menuTop + padY + 26f + padY + 4f;

        for (int i = 0; i < itemNames.length; i++) {
            for (int si = 0; si < sectionBefore.length; si++) {
                if (sectionBefore[si] == i) { y += 30f; break; }
            }
            if (tapY >= y && tapY <= y + rowH) {
                toggleItem(i);
                invalidate();
                return true;
            }
            y += rowH;
        }
        return false;
    }

    private void toggleItem(int index) {
        switch (index) {
            case 0: showHeadDot       = !showHeadDot;       break;
            case 1: showSkeleton      = !showSkeleton;      break;
            case 2: showArmLines      = !showArmLines;      break;
            case 3: showSnaplines     = !showSnaplines;     break;
            case 4: showHealthBar     = !showHealthBar;     break;
            case 5: showTrail         = !showTrail;         break;
            case 6: showPrediction    = !showPrediction;    break;
            case 7: showRadar         = !showRadar;         break;
            case 8: showDistance      = !showDistance;      break;
            case 9: showLockIndicator = !showLockIndicator; break;
        }
    }
}
