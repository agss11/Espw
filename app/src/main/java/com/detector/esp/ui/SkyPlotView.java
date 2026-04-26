package com.detector.esp.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.location.GnssStatus;
import android.util.AttributeSet;
import android.view.View;

/**
 * Satelit天空图（Sky Plot）— 极坐标显示Satelit实时Lokasi
 *
 * 圆心=头顶（仰角90°），边缘=地平线（仰角0°）
 * 颜色按Satelit系统分类：
 *   GPS(美国)=蓝色, GLONASS(俄罗斯)=红色,
 *   Galileo(欧盟)=黄色, BeiDou(中国)=品红色,
 *   QZSS(日本)=绿色, 其他=灰色
 */
public class SkyPlotView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Satelit数据
    private int satCount = 0;
    private int[] constellations;
    private int[] svids;
    private float[] azimuths;
    private float[] elevations;
    private float[] cn0s;
    private boolean[] usedInFix;

    public SkyPlotView(Context context) { this(context, null); }
    public SkyPlotView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        bgPaint.setColor(0xFF040908);
        bgPaint.setStyle(Paint.Style.FILL);

        gridPaint.setColor(0x44FFFFFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);

        textPaint.setColor(0x88FFFFFF);
        textPaint.setTextSize(24f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.MONOSPACE);

        dotPaint.setStyle(Paint.Style.FILL);

        dotStrokePaint.setStyle(Paint.Style.STROKE);
        dotStrokePaint.setStrokeWidth(2f);
        dotStrokePaint.setColor(Color.WHITE);

        labelPaint.setTextSize(18f);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        legendPaint.setTextSize(22f);
        legendPaint.setColor(Color.WHITE);
        legendPaint.setTypeface(Typeface.MONOSPACE);
    }

    public void updateSatellites(GnssStatus status) {
        satCount = status.getSatelliteCount();
        constellations = new int[satCount];
        svids = new int[satCount];
        azimuths = new float[satCount];
        elevations = new float[satCount];
        cn0s = new float[satCount];
        usedInFix = new boolean[satCount];

        for (int i = 0; i < satCount; i++) {
            constellations[i] = status.getConstellationType(i);
            svids[i] = status.getSvid(i);
            azimuths[i] = status.getAzimuthDegrees(i);
            elevations[i] = status.getElevationDegrees(i);
            cn0s[i] = status.getCn0DbHz(i);
            usedInFix[i] = status.usedInFix(i);
        }
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = w;  // 正方形
        float cx = w / 2f;
        float cy = w / 2f;
        float radius = w / 2f - 40;

        // 黑色背景圆
        canvas.drawCircle(cx, cy, radius + 10, bgPaint);

        // 同心圆（30°, 60°, 90° 仰角）
        for (int el = 30; el <= 90; el += 30) {
            float r = radius * (90 - el) / 90f;
            canvas.drawCircle(cx, cy, r, gridPaint);
        }

        // 十字线
        canvas.drawLine(cx - radius, cy, cx + radius, cy, gridPaint);
        canvas.drawLine(cx, cy - radius, cx, cy + radius, gridPaint);

        // 对角线
        float d = radius * 0.707f;
        gridPaint.setAlpha(0x22);
        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, gridPaint);
        canvas.drawLine(cx - d, cy + d, cx + d, cy - d, gridPaint);
        gridPaint.setAlpha(0x44);

        // 方向标签
        textPaint.setTextSize(28f);
        canvas.drawText("N", cx, cy - radius - 12, textPaint);
        canvas.drawText("S", cx, cy + radius + 30, textPaint);
        canvas.drawText("E", cx + radius + 20, cy + 8, textPaint);
        canvas.drawText("W", cx - radius - 20, cy + 8, textPaint);

        // 仰角标签
        textPaint.setTextSize(18f);
        textPaint.setColor(0x44FFFFFF);
        canvas.drawText("60°", cx + 5, cy - radius * 30 / 90f + 5, textPaint);
        canvas.drawText("30°", cx + 5, cy - radius * 60 / 90f + 5, textPaint);
        textPaint.setColor(0x88FFFFFF);

        // GambarSatelit
        if (satCount > 0) {
            for (int i = 0; i < satCount; i++) {
                float el = elevations[i];
                float az = azimuths[i];
                float cn0 = cn0s[i];

                if (el < 0) continue;  // 地平线以下不画

                // 极坐标 → 笛卡尔坐标
                float r = radius * (90 - el) / 90f;
                float angleRad = (float) Math.toRadians(az - 90);  // 0°=北, 顺时针
                float sx = cx + r * (float) Math.cos(angleRad);
                float sy = cy + r * (float) Math.sin(angleRad);

                int color = getConstellationColor(constellations[i]);
                float dotSize = usedInFix[i] ? 14 : 9;

                // 信号强度影响透明度
                int alpha = (int) Math.min(255, 80 + cn0 * 5);

                // 画Satelit点
                dotPaint.setColor(color);
                dotPaint.setAlpha(alpha);
                canvas.drawCircle(sx, sy, dotSize, dotPaint);

                // 已用于定位的加白色边框
                if (usedInFix[i]) {
                    canvas.drawCircle(sx, sy, dotSize, dotStrokePaint);
                }

                // Satelit编号
                labelPaint.setTextSize(usedInFix[i] ? 16f : 12f);
                labelPaint.setAlpha(alpha);
                canvas.drawText(String.valueOf(svids[i]), sx, sy - dotSize - 4, labelPaint);
            }
        }

        // 图例（底部）
        float legendY = w + 20;
        drawLegend(canvas, 20, legendY);
    }

    private void drawLegend(Canvas canvas, float x, float y) {
        String[][] legends = {
            {"GPS", "美国", "#4488FF"},
            {"GLO", "俄罗斯", "#FF4444"},
            {"GAL", "欧盟", "#FFDD00"},
            {"BDS", "中国", "#FF44FF"},
            {"QZS", "日本", "#44FF44"},
        };

        legendPaint.setTextSize(20f);
        float startX = x;

        // 统计各系统Satelit数
        int[] counts = new int[5];
        int[] usedCounts = new int[5];
        for (int i = 0; i < satCount; i++) {
            int idx = constellationToIndex(constellations[i]);
            if (idx >= 0) {
                counts[idx]++;
                if (usedInFix[i]) usedCounts[idx]++;
            }
        }

        for (int i = 0; i < legends.length; i++) {
            int color = Color.parseColor(legends[i][2]);
            dotPaint.setColor(color);
            dotPaint.setAlpha(255);
            canvas.drawCircle(startX + 10, y + 10, 8, dotPaint);

            legendPaint.setColor(Color.WHITE);
            String text = legends[i][0] + "(" + legends[i][1] + ") " + usedCounts[i] + "/" + counts[i];
            canvas.drawText(text, startX + 25, y + 18, legendPaint);

            y += 32;
        }
    }

    private int getConstellationColor(int constellation) {
        switch (constellation) {
            case GnssStatus.CONSTELLATION_GPS: return 0xFF4488FF;       // 美国 蓝
            case GnssStatus.CONSTELLATION_GLONASS: return 0xFFFF4444;   // 俄罗斯 红
            case GnssStatus.CONSTELLATION_GALILEO: return 0xFFFFDD00;   // 欧盟 黄
            case GnssStatus.CONSTELLATION_BEIDOU: return 0xFFFF44FF;    // 中国 品红
            case GnssStatus.CONSTELLATION_QZSS: return 0xFF44FF44;      // 日本 绿
            case GnssStatus.CONSTELLATION_SBAS: return 0xFFFF8800;      // SBAS 橙
            default: return 0xFF888888;
        }
    }

    private int constellationToIndex(int constellation) {
        switch (constellation) {
            case GnssStatus.CONSTELLATION_GPS: return 0;
            case GnssStatus.CONSTELLATION_GLONASS: return 1;
            case GnssStatus.CONSTELLATION_GALILEO: return 2;
            case GnssStatus.CONSTELLATION_BEIDOU: return 3;
            case GnssStatus.CONSTELLATION_QZSS: return 4;
            default: return -1;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        // 天空图正方形 + 底部图例空间
        setMeasuredDimension(w, w + 180);
    }
}
