package com.detector.esp.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 自定义拍照按钮 — 白色圆环 + 内部绿色圆
 */
public class CaptureButtonView extends View {

    private final Paint outerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CaptureButtonView(Context context) { this(context, null); }
    public CaptureButtonView(Context context, AttributeSet attrs) {
        super(context, attrs);
        outerPaint.setColor(Color.WHITE);
        outerPaint.setStyle(Paint.Style.STROKE);
        outerPaint.setStrokeWidth(4f);
        innerPaint.setColor(0xFF00E676);
        innerPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - 4;
        canvas.drawCircle(cx, cy, r, outerPaint);
        canvas.drawCircle(cx, cy, r - 8, innerPaint);
    }
}
