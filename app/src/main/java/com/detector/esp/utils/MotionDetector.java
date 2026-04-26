package com.detector.esp.utils;

/**
 * MotionDetector — Estimasi kecepatan gerak objek antar frame
 * Digunakan untuk:
 *   1. Adaptive confidence threshold (objek bergerak cepat → turunkan threshold)
 *   2. Prediksi posisi lebih agresif
 *   3. Menentukan apakah perlu switch ke mode "fast motion"
 */
public class MotionDetector {

    // Kecepatan gerak (dalam satuan normalized per detik)
    private float currentSpeedX = 0f;
    private float currentSpeedY = 0f;
    private float smoothedSpeed = 0f;

    // Riwayat posisi center per tracked object
    private static final int HISTORY = 5;
    private final float[] histX = new float[HISTORY];
    private final float[] histY = new float[HISTORY];
    private final long[] histTime = new long[HISTORY];
    private int histHead = 0;
    private int histCount = 0;

    // Threshold klasifikasi kecepatan
    public static final float SPEED_SLOW   = 0.02f;   // < 2% layar per detik = diam
    public static final float SPEED_MEDIUM = 0.12f;   // 2–12% = sedang
    public static final float SPEED_FAST   = 0.30f;   // 12–30% = cepat
    // > 30% = sangat cepat (fast motion mode)

    public enum MotionClass { DIAM, LAMBAT, SEDANG, CEPAT, SANGAT_CEPAT }

    /**
     * Tambahkan observasi posisi baru
     * @param cx center-x normalized [0,1]
     * @param cy center-y normalized [0,1]
     */
    public void addObservation(float cx, float cy) {
        long now = System.currentTimeMillis();
        histX[histHead] = cx;
        histY[histHead] = cy;
        histTime[histHead] = now;
        histHead = (histHead + 1) % HISTORY;
        if (histCount < HISTORY) histCount++;
        computeSpeed();
    }

    private void computeSpeed() {
        if (histCount < 2) { smoothedSpeed = 0f; return; }

        // Hitung speed rata-rata dari history
        float totalDx = 0, totalDy = 0;
        long totalDt = 0;
        int n = 0;

        int cur = (histHead - 1 + HISTORY) % HISTORY;
        for (int i = 1; i < histCount; i++) {
            int prev = (cur - i + HISTORY) % HISTORY;
            float dx = histX[cur] - histX[prev];
            float dy = histY[cur] - histY[prev];
            long dt = histTime[cur] - histTime[prev];
            if (dt > 0 && dt < 500) { // max 500ms gap
                totalDx += dx;
                totalDy += dy;
                totalDt += dt;
                n++;
            }
        }

        if (n > 0 && totalDt > 0) {
            float dtSec = totalDt / 1000f;
            currentSpeedX = totalDx / dtSec;
            currentSpeedY = totalDy / dtSec;
            float rawSpeed = (float) Math.sqrt(currentSpeedX * currentSpeedX + currentSpeedY * currentSpeedY);
            // EMA smoothing
            smoothedSpeed = smoothedSpeed * 0.6f + rawSpeed * 0.4f;
        }
    }

    public float getSpeed() { return smoothedSpeed; }

    public MotionClass classify() {
        if (smoothedSpeed < SPEED_SLOW)   return MotionClass.DIAM;
        if (smoothedSpeed < SPEED_MEDIUM) return MotionClass.LAMBAT;
        if (smoothedSpeed < SPEED_FAST)   return MotionClass.SEDANG;
        if (smoothedSpeed < 0.60f)        return MotionClass.CEPAT;
        return MotionClass.SANGAT_CEPAT;
    }

    /**
     * Confidence threshold adaptif berdasarkan kecepatan
     * Semakin cepat → threshold lebih rendah agar objek tidak hilang
     */
    public float getAdaptiveConfidence(float baseThreshold) {
        switch (classify()) {
            case DIAM:        return baseThreshold;
            case LAMBAT:      return baseThreshold * 0.95f;
            case SEDANG:      return baseThreshold * 0.85f;
            case CEPAT:       return baseThreshold * 0.70f;
            case SANGAT_CEPAT:return baseThreshold * 0.55f;
            default:          return baseThreshold;
        }
    }

    /**
     * Faktor extrapolasi untuk prediksi posisi
     * Semakin cepat → lebih agresif extrapolate ke depan
     */
    public float getPredictionFactor() {
        switch (classify()) {
            case DIAM:        return 0.2f;
            case LAMBAT:      return 0.35f;
            case SEDANG:      return 0.55f;
            case CEPAT:       return 0.80f;
            case SANGAT_CEPAT:return 1.20f;
            default:          return 0.3f;
        }
    }

    public float getSpeedX() { return currentSpeedX; }
    public float getSpeedY() { return currentSpeedY; }

    public void reset() {
        histCount = 0;
        histHead = 0;
        smoothedSpeed = 0;
        currentSpeedX = currentSpeedY = 0;
    }
}
