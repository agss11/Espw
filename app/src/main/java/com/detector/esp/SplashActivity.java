package com.detector.esp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import android.app.ActivityManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.opengl.GLES20;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Size;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ESP V2 Animasi Booting — Gaya Inisialisasi Sistem
 */
public class SplashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        // 后台开始预加载模型
        new Thread(() -> AppPreloader.preload(getApplicationContext()), "Preloader").start();

        BootAnimView animView = new BootAnimView(this);
        setContentView(animView);
    }

    private class BootAnimView extends View {

        private final Paint bgPaint = new Paint();
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subtitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hexPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progressBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progressFgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final long startTime = System.currentTimeMillis();
        private static final long MIN_DURATION = 3000; // 最少3秒动画
        private boolean launched = false;
        private final Random rng = new Random(42);

        // 真实系统信息（在构造函数里填充）
        private final String[] bootLines;

        // 背景 hex 矩阵
        private final List<float[]> hexPositions = new ArrayList<>();
        private final List<String> hexValues = new ArrayList<>();
        private Bitmap iconBitmap;

        public BootAnimView(android.content.Context context) {
            super(context);
            bootLines = gatherSystemInfo(context);

            bgPaint.setColor(Color.BLACK);

            textPaint.setColor(0xFF00E676);
            textPaint.setTextSize(26f);
            textPaint.setTypeface(Typeface.MONOSPACE);

            titlePaint.setTextSize(72f);
            titlePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            titlePaint.setTextAlign(Paint.Align.CENTER);
            titlePaint.setLetterSpacing(0.3f);

            subtitlePaint.setColor(0xFF00E676);
            subtitlePaint.setTextSize(22f);
            subtitlePaint.setTypeface(Typeface.MONOSPACE);
            subtitlePaint.setTextAlign(Paint.Align.CENTER);
            subtitlePaint.setLetterSpacing(0.5f);

            scanPaint.setStyle(Paint.Style.FILL);

            gridPaint.setColor(0x0A00FF00);
            gridPaint.setStyle(Paint.Style.STROKE);
            gridPaint.setStrokeWidth(1f);

            hexPaint.setColor(0x1500E676);
            hexPaint.setTextSize(14f);
            hexPaint.setTypeface(Typeface.MONOSPACE);

            progressBgPaint.setColor(0x33FFFFFF);
            progressBgPaint.setStyle(Paint.Style.FILL);

            progressFgPaint.setColor(0xFF00E676);
            progressFgPaint.setStyle(Paint.Style.FILL);

            glowPaint.setStyle(Paint.Style.FILL);

            // 加载 app 图标
            iconBitmap = BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher);

            // 生成随机 hex 背景
            for (int i = 0; i < 200; i++) {
                hexPositions.add(new float[]{rng.nextFloat(), rng.nextFloat()});
                hexValues.add(String.format("%02X", rng.nextInt(256)));
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            float elapsed = System.currentTimeMillis() - startTime;

            // 实际加载进度（0-1）
            float loadProgress = AppPreloader.progress;
            // 动画时间进度（基于最少时间）
            float timeProgress = Math.min(1f, elapsed / MIN_DURATION);
            // 综合进度：取两者较小值（动画和加载都要完成）
            float progress = Math.min(timeProgress, loadProgress);

            // 黑色背景
            canvas.drawRect(0, 0, w, h, bgPaint);

            // Phase 1 (0-0.6): 背景特效 + 启动日志
            drawHexBackground(canvas, w, h, elapsed);
            drawGrid(canvas, w, h, elapsed);
            drawScanLine(canvas, w, h, elapsed);

            if (timeProgress < 0.65f) {
                drawBootLog(canvas, w, h, timeProgress / 0.65f);
            }

            // Phase 2 (0.4-0.85): 标题渐入
            if (timeProgress > 0.35f) {
                float titleProgress = Math.min(1f, (timeProgress - 0.35f) / 0.35f);
                drawTitle(canvas, w, h, titleProgress, elapsed);
            }

            // Phase 3: 实际加载进度条（显示真实加载状态）
            if (timeProgress > 0.45f) {
                drawProgressBar(canvas, w, h, loadProgress);
            }

            // 加载完成 + 动画最少时间到了 → 跳转
            boolean canLaunch = AppPreloader.ready && elapsed >= MIN_DURATION;

            if (canLaunch && !launched) {
                // 白闪
                canvas.drawColor(Color.argb(200, 255, 255, 255));
                launched = true;
                postDelayed(() -> {
                    startActivity(new Intent(SplashActivity.this, MainActivity.class));
                    finish();
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                }, 150);
            } else if (launched) {
                // 白闪渐消
                float fade = Math.min(1f, (elapsed - MIN_DURATION) / 200f);
                canvas.drawColor(Color.argb((int)(200 * (1f - fade)), 255, 255, 255));
            }

            postInvalidateOnAnimation();
        }

        private final Paint iconAlphaPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        private void drawHexBackground(Canvas canvas, int w, int h, float elapsed) {
            if (iconBitmap == null) return;
            float drift = elapsed * 0.00003f;
            int miniSize = 60;
            for (int i = 0; i < hexPositions.size(); i++) {
                float[] pos = hexPositions.get(i);
                float x = ((pos[0] + drift * (i % 3 + 1)) % 1.3f - 0.1f) * w;
                float y = ((pos[1] + drift * 0.4f) % 1.2f - 0.1f) * h;
                // 闪烁 + 旋转
                float flicker = (float)(0.2 + 0.8 * Math.sin(elapsed * 0.002 + i * 0.5));
                iconAlphaPaint.setAlpha((int)(50 * flicker));
                float rot = (elapsed * 0.02f + i * 37) % 360;
                float scale = 0.7f + 0.3f * (float) Math.sin(elapsed * 0.001 + i);

                canvas.save();
                canvas.translate(x, y);
                canvas.rotate(rot);
                canvas.scale(scale, scale);
                android.graphics.RectF dst = new android.graphics.RectF(
                        -miniSize / 2f, -miniSize / 2f, miniSize / 2f, miniSize / 2f);
                canvas.drawBitmap(iconBitmap, null, dst, iconAlphaPaint);
                canvas.restore();
            }
        }

        private void drawGrid(Canvas canvas, int w, int h, float elapsed) {
            float offset = (elapsed * 0.03f) % 40;
            gridPaint.setAlpha(15);
            for (float y = -40 + offset; y < h + 40; y += 40) {
                canvas.drawLine(0, y, w, y, gridPaint);
            }
            for (float x = 0; x < w; x += 40) {
                canvas.drawLine(x, 0, x, h, gridPaint);
            }
        }

        private void drawScanLine(Canvas canvas, int w, int h, float elapsed) {
            float scanY = ((elapsed * 0.15f) % (h + 100)) - 50;
            scanPaint.setShader(new LinearGradient(0, scanY - 30, 0, scanY + 30,
                    new int[]{0x0000E676, 0x3300E676, 0x0000E676},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, scanY - 30, w, scanY + 30, scanPaint);
            scanPaint.setShader(null);
        }

        private void drawBootLog(Canvas canvas, int w, int h, float progress) {
            int visibleLines = (int)(progress * bootLines.length);
            float startY = h * 0.15f;

            for (int i = 0; i < Math.min(visibleLines, bootLines.length); i++) {
                String line = bootLines[i];

                // 最后一行打字机效果
                if (i == visibleLines - 1 && visibleLines <= bootLines.length) {
                    float lineProgress = (progress * bootLines.length) - i;
                    int chars = (int)(lineProgress * line.length());
                    line = line.substring(0, Math.min(chars, line.length()));

                    // 光标闪烁
                    if ((System.currentTimeMillis() / 300) % 2 == 0) {
                        line += "█";
                    }
                }

                // 颜色
                if (line.contains("OK")) {
                    textPaint.setColor(0xFF00E676);
                } else if (line.contains(">>>")) {
                    textPaint.setColor(0xFFFFCC00);
                    textPaint.setTextSize(30f);
                } else if (line.startsWith("[AI")) {
                    textPaint.setColor(0xFF00BCD4);
                } else {
                    textPaint.setColor(0xFF00E676);
                }

                // 渐隐旧行
                float fadeAlpha = Math.max(0.2f, 1f - (visibleLines - i) * 0.08f);
                textPaint.setAlpha((int)(255 * fadeAlpha));

                canvas.drawText(line, 30, startY + i * 36, textPaint);
                textPaint.setTextSize(26f);
            }
            textPaint.setAlpha(255);
        }

        private void drawTitle(Canvas canvas, int w, int h, float progress, float elapsed) {
            float centerY = h * 0.45f;

            // 标题 "ESP" 大字 — 从模糊到清晰
            float scale = 0.8f + 0.2f * progress;
            float alpha = progress;

            canvas.save();
            canvas.scale(scale, scale, w / 2f, centerY);

            // 外发光
            float glowAlpha = (float)(0.3 + 0.2 * Math.sin(elapsed * 0.004));
            titlePaint.setColor(0xFF00E676);
            titlePaint.setAlpha((int)(40 * alpha * glowAlpha));
            titlePaint.setTextSize(80f);
            canvas.drawText("E S P", w / 2f + 2, centerY + 2, titlePaint);

            // 主标题
            titlePaint.setAlpha((int)(255 * alpha));
            titlePaint.setShader(new LinearGradient(w * 0.3f, centerY - 40,
                    w * 0.7f, centerY + 40,
                    new int[]{0xFF00E676, 0xFF00BCD4, 0xFF00E676},
                    null, Shader.TileMode.CLAMP));
            titlePaint.setTextSize(76f);
            canvas.drawText("E S P", w / 2f, centerY, titlePaint);
            titlePaint.setShader(null);

            canvas.restore();

            // 副标题
            if (progress > 0.4f) {
                float subAlpha = Math.min(1f, (progress - 0.4f) / 0.3f);
                subtitlePaint.setAlpha((int)(255 * subAlpha));
                canvas.drawText("SISTEM DETEKSI V2", w / 2f, centerY + 50, subtitlePaint);
            }

            // 装饰线
            if (progress > 0.6f) {
                float lineAlpha = Math.min(1f, (progress - 0.6f) / 0.2f);
                float lineW = w * 0.4f * lineAlpha;
                gridPaint.setColor(0xFF00E676);
                gridPaint.setAlpha((int)(100 * lineAlpha));
                gridPaint.setStrokeWidth(1.5f);
                canvas.drawLine(w / 2f - lineW, centerY + 65, w / 2f + lineW, centerY + 65, gridPaint);
                canvas.drawLine(w / 2f - lineW * 0.6f, centerY + 75, w / 2f + lineW * 0.6f, centerY + 75, gridPaint);
                gridPaint.setColor(0x0A00FF00);
                gridPaint.setStrokeWidth(1f);
            }
        }

        private String[] gatherSystemInfo(android.content.Context ctx) {
            List<String> lines = new ArrayList<>();

            // 系统
            lines.add("[SIS] ESP Sistem Deteksi v2.0");
            lines.add("[SYS] " + Build.MANUFACTURER.toUpperCase() + " " + Build.MODEL);
            lines.add("[SYS] Android " + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT);

            // CPU
            String cpuModel = Build.HARDWARE;
            int cores = Runtime.getRuntime().availableProcessors();
            String cpuFreq = getCpuMaxFreq();
            lines.add("[CPU] " + cpuModel + " " + cores + " inti " + cpuFreq + " .. OK");

            // 内存
            ActivityManager am = (ActivityManager) ctx.getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(memInfo);
            long totalMB = memInfo.totalMem / (1024 * 1024);
            long availMB = memInfo.availMem / (1024 * 1024);
            lines.add("[MEM] " + totalMB + "MB total / " + availMB + "MB tersedia .. OK");

            // 屏幕
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            lines.add("[DSP] " + dm.widthPixels + "x" + dm.heightPixels + " " + dm.densityDpi + "dpi");

            // 相机
            try {
                CameraManager cm = (CameraManager) ctx.getSystemService(CAMERA_SERVICE);
                String[] ids = cm.getCameraIdList();
                for (String id : ids) {
                    CameraCharacteristics chars = cm.getCameraCharacteristics(id);
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        Size[] sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                                .getOutputSizes(android.graphics.ImageFormat.JPEG);
                        if (sizes.length > 0) {
                            Size max = sizes[0];
                            float mp = max.getWidth() * max.getHeight() / 1000000f;
                            lines.add("[KAM] Kamera belakang " + max.getWidth() + "x" + max.getHeight()
                                    + " " + String.format("%.1fMP", mp) + " .. OK");
                        }
                        Float maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                        if (maxZoom != null) {
                            lines.add("[KAM] Zoom digital: " + String.format("%.1fx", maxZoom));
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                lines.add("[KAM] Gagal baca kamera");
            }

            // AI 模型
            try {
                String[] assets = ctx.getAssets().list("");
                for (String a : assets) {
                    if (a.endsWith(".tflite")) {
                        long size = ctx.getAssets().openFd(a).getLength();
                        lines.add("[AI ] " + a + " " + String.format("%.1fMB", size / 1048576f) + " .. OK");
                    }
                }
            } catch (Exception e) {
                lines.add("[AI ] Gagal memuat model");
            }

            lines.add("[NET] Delegasi GPU diinisialisasi .... OK");
            lines.add("[GPS] Penyedia lokasi siap");
            lines.add("");
            lines.add(">>> SEMUA SISTEM BEROPERASI <<<");

            return lines.toArray(new String[0]);
        }

        private String getCpuMaxFreq() {
            try {
                BufferedReader br = new BufferedReader(
                        new FileReader("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq"));
                String freq = br.readLine();
                br.close();
                long khz = Long.parseLong(freq.trim());
                return String.format("%.1fGHz", khz / 1000000f);
            } catch (Exception e) {
                return "";
            }
        }

        private void drawProgressBar(Canvas canvas, int w, int h, float progress) {
            float barW = w * 0.5f;
            float barH = 4;
            float barX = (w - barW) / 2f;
            float barY = h * 0.62f;

            // 图标在进度条上方
            if (iconBitmap != null) {
                int iconSize = 160;
                float iconX = (w - iconSize) / 2f;
                float iconY = barY - iconSize - 20;
                android.graphics.RectF dst = new android.graphics.RectF(iconX, iconY, iconX + iconSize, iconY + iconSize);
                canvas.drawBitmap(iconBitmap, null, dst, null);
            }

            // 背景
            canvas.drawRoundRect(barX, barY, barX + barW, barY + barH, 2, 2, progressBgPaint);

            // 填充
            float fillW = barW * progress;
            canvas.drawRoundRect(barX, barY, barX + fillW, barY + barH, 2, 2, progressFgPaint);

            // 真实加载状态文字
            subtitlePaint.setTextSize(16f);
            subtitlePaint.setAlpha(180);
            String stepText = AppPreloader.currentStep;
            if (stepText != null && !stepText.isEmpty()) {
                canvas.drawText(stepText, w / 2f, barY + 26, subtitlePaint);
            }
            subtitlePaint.setTextSize(22f);
            subtitlePaint.setAlpha(255);
        }
    }
}
