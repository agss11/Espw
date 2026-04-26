package com.detector.esp.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * iPhone 风格半圆弧变焦刻度盘
 *
 * 默认：圆形倍数按钮（1x 2x 5x 10x 100x）
 * 长按/拖动：展开为半圆弧刻度盘，沿弧线滑动调整 1x-1000x
 */
public class ZoomDialView extends View {

    public interface OnZoomChangeListener {
        void onZoomChanged(float zoom);
    }

    private OnZoomChangeListener listener;

    // 预设
    private static final float[] PRESETS = {1f, 2f, 5f, 10f, 100f};
    private static final String[] PRESET_LABELS = {"1", "2", "5", "10", "100"};

    // 刻度标记
    private static final float[] TICK_ZOOMS = {1, 1.5f, 2, 3, 5, 7, 10, 15, 20, 30, 50, 70, 100, 150, 200, 300, 500, 700, 1000};
    private static final float[] LABEL_ZOOMS = {1, 2, 5, 10, 50, 100, 500, 1000};

    private static final float MIN_ZOOM = 1f;
    private static final float MAX_ZOOM = 1000f;

    // 弧形参数
    private static final float ARC_START_ANGLE = 200f;  // 左下方开始
    private static final float ARC_SWEEP_ANGLE = 140f;  // 顺时针经过顶部到右下方（彩虹形）

    // 状态
    private float currentZoom = 1f;
    private boolean expanded = true;
    private float expandProgress = 1f;  // 默认展开，调试用

    // 触摸
    private float touchStartAngle;
    private float zoomAtTouchStart;
    private boolean isDragging = false;
    private long touchDownTime;

    // 尺寸
    private float density;
    private float btnSize;
    private float arcRadius;
    private float arcCenterX, arcCenterY;

    // 画笔
    private final Paint btnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnActiveBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnActiveTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicatorGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoomLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arcClipPath = new Path();

    public ZoomDialView(Context context) { this(context, null); }
    public ZoomDialView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        btnSize = 34 * density;

        btnBgPaint.setColor(0x44222222);
        btnActiveBgPaint.setColor(0x66333333);
        btnBorderPaint.setColor(0xFFFFCC00);
        btnBorderPaint.setStyle(Paint.Style.STROKE);
        btnBorderPaint.setStrokeWidth(1.5f * density);

        btnTextPaint.setColor(0x99FFFFFF);
        btnTextPaint.setTextSize(12 * density);
        btnTextPaint.setTextAlign(Paint.Align.CENTER);

        btnActiveTextPaint.setColor(0xFFFFCC00);
        btnActiveTextPaint.setTextSize(13 * density);
        btnActiveTextPaint.setTextAlign(Paint.Align.CENTER);

        arcBgPaint.setColor(0xDD1A1A1A);
        arcBgPaint.setStyle(Paint.Style.FILL);

        arcStrokePaint.setColor(0x33FFFFFF);
        arcStrokePaint.setStyle(Paint.Style.STROKE);
        arcStrokePaint.setStrokeWidth(1 * density);

        tickPaint.setColor(0x88FFFFFF);
        tickPaint.setStrokeWidth(1 * density);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        tickLabelPaint.setColor(0xAAFFFFFF);
        tickLabelPaint.setTextSize(8 * density);
        tickLabelPaint.setTextAlign(Paint.Align.CENTER);

        indicatorPaint.setColor(0xFFFFCC00);
        indicatorGlowPaint.setColor(0x44FFCC00);

        zoomLabelPaint.setColor(0xFFFFCC00);
        zoomLabelPaint.setTextSize(14 * density);
        zoomLabelPaint.setTextAlign(Paint.Align.CENTER);
        zoomLabelPaint.setFakeBoldText(true);

        // 不设置 layer type，使用默认硬件加速
    }

    public void setOnZoomChangeListener(OnZoomChangeListener l) { this.listener = l; }

    public void setZoom(float zoom) {
        this.currentZoom = clamp(zoom);
        invalidate();
    }

    public float getZoom() { return currentZoom; }

    private float clamp(float z) { return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z)); }

    /** zoom → 归一化 0-1（对数） */
    private float zoomToNorm(float zoom) {
        return (float)(Math.log10(zoom) / Math.log10(MAX_ZOOM));
    }

    /** 归一化 0-1 → zoom */
    private float normToZoom(float norm) {
        return (float) Math.pow(MAX_ZOOM, Math.max(0, Math.min(1, norm)));
    }

    /** zoom → 弧上角度 */
    private float zoomToAngle(float zoom) {
        float norm = zoomToNorm(zoom);
        return ARC_START_ANGLE + norm * ARC_SWEEP_ANGLE;
    }

    /** 弧上角度 → zoom */
    private float angleToZoom(float angle) {
        float norm = (angle - ARC_START_ANGLE) / ARC_SWEEP_ANGLE;
        return normToZoom(norm);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = (int)(200 * density); // 足够高度放弧形
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        arcRadius = w * 0.38f;
        arcCenterX = w / 2f;
        arcCenterY = h * 0.85f; // 圆心在 view 底部附近，弧形向上展开
        android.util.Log.i("ZoomDial", "size=" + w + "x" + h + " radius=" + arcRadius + " center=" + arcCenterX + "," + arcCenterY);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (expandProgress > 0.01f) {
            drawArcDial(canvas);
        }

        // 始终画预设按钮（展开时变小变淡）
        drawPresetButtons(canvas);
    }

    /** 画半圆弧刻度盘 */
    private void drawArcDial(Canvas canvas) {
        float alpha = expandProgress;
        int w = getWidth();
        int h = getHeight();
        float bandWidth = 36 * density;  // 弧形宽度

        // 弧形背景
        RectF outerOval = new RectF(
                arcCenterX - arcRadius - bandWidth / 2,
                arcCenterY - arcRadius - bandWidth / 2,
                arcCenterX + arcRadius + bandWidth / 2,
                arcCenterY + arcRadius + bandWidth / 2);
        RectF innerOval = new RectF(
                arcCenterX - arcRadius + bandWidth / 2,
                arcCenterY - arcRadius + bandWidth / 2,
                arcCenterX + arcRadius - bandWidth / 2,
                arcCenterY + arcRadius - bandWidth / 2);

        // 画弧形背景
        arcBgPaint.setAlpha((int)(alpha * 0xDD));
        arcStrokePaint.setAlpha((int)(alpha * 0x33));

        // 用 Path 画环形弧
        Path arcPath = new Path();
        arcPath.arcTo(outerOval, ARC_START_ANGLE, ARC_SWEEP_ANGLE, true);
        arcPath.arcTo(innerOval, ARC_START_ANGLE + ARC_SWEEP_ANGLE, -ARC_SWEEP_ANGLE);
        arcPath.close();
        canvas.drawPath(arcPath, arcBgPaint);

        // 弧形边框
        canvas.drawArc(outerOval, ARC_START_ANGLE, ARC_SWEEP_ANGLE, false, arcStrokePaint);
        canvas.drawArc(innerOval, ARC_START_ANGLE, ARC_SWEEP_ANGLE, false, arcStrokePaint);

        // 刻度线
        for (float tz : TICK_ZOOMS) {
            float angle = zoomToAngle(tz);
            double rad = Math.toRadians(angle);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);

            boolean isLabel = false;
            for (float lz : LABEL_ZOOMS) if (lz == tz) isLabel = true;

            float innerR = arcRadius - bandWidth * 0.35f;
            float outerR = arcRadius + bandWidth * (isLabel ? 0.35f : 0.2f);

            float x1 = arcCenterX + innerR * cos;
            float y1 = arcCenterY + innerR * sin;
            float x2 = arcCenterX + outerR * cos;
            float y2 = arcCenterY + outerR * sin;

            tickPaint.setAlpha((int)(alpha * (isLabel ? 0xBB : 0x55)));
            tickPaint.setStrokeWidth((isLabel ? 1.5f : 0.8f) * density);
            canvas.drawLine(x1, y1, x2, y2, tickPaint);

            // 标签
            if (isLabel) {
                float labelR = arcRadius + bandWidth * 0.55f;
                float lx = arcCenterX + labelR * cos;
                float ly = arcCenterY + labelR * sin;
                tickLabelPaint.setAlpha((int)(alpha * 0xCC));
                String label = tz >= 1000 ? "1k" : String.valueOf((int) tz);
                canvas.drawText(label, lx, ly + 3 * density, tickLabelPaint);
            }
        }

        // 当前Lokasi指示器（金色三角 + 圆点）
        float curAngle = zoomToAngle(currentZoom);
        double curRad = Math.toRadians(curAngle);
        float cos = (float) Math.cos(curRad);
        float sin = (float) Math.sin(curRad);

        // 金色圆点在弧上
        float dotR = arcRadius;
        float dotX = arcCenterX + dotR * cos;
        float dotY = arcCenterY + dotR * sin;

        indicatorGlowPaint.setAlpha((int)(alpha * 0x44));
        canvas.drawCircle(dotX, dotY, 10 * density, indicatorGlowPaint);
        indicatorPaint.setAlpha((int)(alpha * 255));
        canvas.drawCircle(dotX, dotY, 5 * density, indicatorPaint);

        // 当前倍数文字（弧外侧）
        float labelR = arcRadius - bandWidth * 0.7f;
        float labelX = arcCenterX + labelR * cos;
        float labelY = arcCenterY + labelR * sin;
        zoomLabelPaint.setAlpha((int)(alpha * 255));
        String zoomStr;
        if (currentZoom < 10) zoomStr = String.format("%.1fx", currentZoom);
        else zoomStr = String.format("%.0fx", currentZoom);
        canvas.drawText(zoomStr, labelX, labelY + 5 * density, zoomLabelPaint);
    }

    /** 画预设按钮 */
    private void drawPresetButtons(Canvas canvas) {
        float btnAlpha = 1f - expandProgress * 0.7f;
        float scale = 1f - expandProgress * 0.3f;
        int w = getWidth();
        float cy = getHeight() - 40 * density; // 底部
        float totalW = PRESETS.length * btnSize * scale + (PRESETS.length - 1) * 6 * density;
        float startX = (w - totalW) / 2f;

        for (int i = 0; i < PRESETS.length; i++) {
            float cx = startX + i * (btnSize * scale + 6 * density) + btnSize * scale / 2;
            float r = btnSize * scale / 2;

            boolean active = isClosestPreset(i);

            // 背景圆
            Paint bg = active ? btnActiveBgPaint : btnBgPaint;
            bg.setAlpha((int)(btnAlpha * (active ? 0x66 : 0x44)));
            canvas.drawCircle(cx, cy, r, bg);

            if (active) {
                btnBorderPaint.setAlpha((int)(btnAlpha * 255));
                canvas.drawCircle(cx, cy, r, btnBorderPaint);
            }

            // 文字
            Paint tp = active ? btnActiveTextPaint : btnTextPaint;
            tp.setAlpha((int)(btnAlpha * (active ? 255 : 0x99)));
            float textY = cy - (tp.descent() + tp.ascent()) / 2;
            canvas.drawText(PRESET_LABELS[i], cx, textY, tp);
        }
    }

    private boolean isClosestPreset(int index) {
        float logZ = (float) Math.log10(currentZoom);
        float minD = Float.MAX_VALUE;
        int closest = 0;
        for (int i = 0; i < PRESETS.length; i++) {
            float d = Math.abs(logZ - (float) Math.log10(PRESETS[i]));
            if (d < minD) { minD = d; closest = i; }
        }
        return closest == index && minD < 0.15f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchDownTime = System.currentTimeMillis();
                isDragging = false;
                touchStartAngle = getTouchAngle(x, y);
                zoomAtTouchStart = currentZoom;

                if (!expanded) {
                    animateExpand(true);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float angle = getTouchAngle(x, y);
                float angleDelta = angle - touchStartAngle;

                if (Math.abs(angleDelta) > 1 || isDragging) {
                    isDragging = true;
                    // 角度变化映射到 zoom 变化
                    float startNorm = zoomToNorm(zoomAtTouchStart);
                    float normDelta = angleDelta / ARC_SWEEP_ANGLE;
                    float newNorm = startNorm + normDelta;
                    currentZoom = normToZoom(newNorm);
                    if (listener != null) listener.onZoomChanged(currentZoom);
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!isDragging && System.currentTimeMillis() - touchDownTime < 300) {
                    handlePresetTap(x, y);
                }
                animateExpand(false);
                return true;
        }
        return super.onTouchEvent(event);
    }

    /** 计算触摸点相对于弧心的角度 */
    private float getTouchAngle(float x, float y) {
        return (float) Math.toDegrees(Math.atan2(y - arcCenterY, x - arcCenterX));
    }

    private void handlePresetTap(float x, float y) {
        int w = getWidth();
        float cy = getHeight() - 40 * density;
        float totalW = PRESETS.length * btnSize + (PRESETS.length - 1) * 6 * density;
        float startX = (w - totalW) / 2f;

        for (int i = 0; i < PRESETS.length; i++) {
            float cx = startX + i * (btnSize + 6 * density) + btnSize / 2;
            float dist = (float) Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
            if (dist < btnSize) {
                animateZoomTo(PRESETS[i]);
                return;
            }
        }
    }

    private void animateZoomTo(float target) {
        ValueAnimator anim = ValueAnimator.ofFloat(currentZoom, target);
        anim.setDuration(300);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            currentZoom = (float) a.getAnimatedValue();
            if (listener != null) listener.onZoomChanged(currentZoom);
            invalidate();
        });
        anim.start();
    }

    private void animateExpand(boolean expand) {
        expanded = expand;
        ValueAnimator anim = ValueAnimator.ofFloat(expandProgress, expand ? 1f : 0f);
        anim.setDuration(250);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            expandProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }
}
