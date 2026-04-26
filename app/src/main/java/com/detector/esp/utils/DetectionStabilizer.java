package com.detector.esp.utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * DetectionStabilizer v3 — Fast Motion Edition
 *
 * Peningkatan:
 *  1. MIN_HIT adaptif (1 untuk objek cepat, 3 untuk normal)
 *  2. MAX_AGE diperluas saat fast motion agar objek tidak hilang saat ter-oklude
 *  3. MotionDetector per-track untuk kecepatan individual
 *  4. Prediksi posisi lebih akurat dengan velocity smoothing
 *  5. IoU matching diperlonggar saat gerakan cepat (objek bisa pindah jauh antar frame)
 *  6. Kalman-lite: smooth posisi + velocity filter
 */
public class DetectionStabilizer {

    // Hit confirmation: lebih rendah = lebih responsif
    private static final int MIN_HIT_NORMAL    = 2;   // turun dari 3 → lebih cepat konfirmasi
    private static final int MIN_HIT_FAST      = 1;   // langsung tampil saat gerak cepat
    private static final int MAX_AGE_NORMAL    = 5;
    private static final int MAX_AGE_FAST      = 10;  // tahan lebih lama saat fast motion

    private static final float MATCH_IOU_NORMAL = 0.25f;  // sedikit lebih longgar dari 0.3
    private static final float MATCH_IOU_FAST   = 0.08f;  // sangat longgar saat fast motion
    private static final float MATCH_DIST_MAX   = 0.40f;  // fallback: jarak center max

    private static final int MAX_TRAIL = DetectResult.MAX_TRAIL;

    private final List<TrackedObject> trackedObjects = new ArrayList<>();
    private int nextId = 0;

    // Global motion state untuk semua track
    private volatile MotionDetector.MotionClass globalMotion = MotionDetector.MotionClass.DIAM;

    public List<DetectResult> update(List<DetectResult> currentDetections, float cameraDx, float cameraDy) {
        // Estimasi global motion dari rata-rata pergerakan semua track
        updateGlobalMotion();

        boolean isFastMode = globalMotion == MotionDetector.MotionClass.CEPAT
                          || globalMotion == MotionDetector.MotionClass.SANGAT_CEPAT;

        float matchIou  = isFastMode ? MATCH_IOU_FAST  : MATCH_IOU_NORMAL;
        int   minHit    = isFastMode ? MIN_HIT_FAST    : MIN_HIT_NORMAL;
        int   maxAge    = isFastMode ? MAX_AGE_FAST    : MAX_AGE_NORMAL;

        // 1. Reset matched flags
        for (TrackedObject t : trackedObjects) t.matched = false;

        // 2. Prediksi posisi setiap track ke frame saat ini
        long nowMs = System.currentTimeMillis();
        for (TrackedObject t : trackedObjects) t.predict(nowMs);

        // 3. Greedy IoU matching, dengan fallback ke distance matching
        List<DetectResult> unmatched = new ArrayList<>(currentDetections);

        for (TrackedObject tracked : trackedObjects) {
            float bestScore = -1f;
            DetectResult bestMatch = null;

            for (DetectResult det : unmatched) {
                if (det.classId != tracked.classId) continue;

                float iou = calcIou(tracked.predLeft, tracked.predTop,
                                    tracked.predRight, tracked.predBottom,
                                    det.left, det.top, det.right, det.bottom);

                // Fallback: jika IoU rendah, coba distance matching
                if (iou < matchIou) {
                    float dCx = Math.abs(tracked.predCx() - (det.left + det.right) / 2f);
                    float dCy = Math.abs(tracked.predCy() - (det.top + det.bottom) / 2f);
                    float dist = (float) Math.sqrt(dCx * dCx + dCy * dCy);
                    if (dist < MATCH_DIST_MAX) {
                        // Beri score berdasarkan jarak (lebih dekat = lebih baik)
                        float distScore = (MATCH_DIST_MAX - dist) / MATCH_DIST_MAX * 0.3f;
                        if (distScore > bestScore) {
                            bestScore = distScore;
                            bestMatch = det;
                        }
                    }
                } else if (iou > bestScore) {
                    bestScore = iou;
                    bestMatch = det;
                }
            }

            if (bestMatch != null) {
                tracked.update(bestMatch, cameraDx, cameraDy, nowMs);
                tracked.matched = true;
                unmatched.remove(bestMatch);
            }
        }

        // 4. Deteksi baru → buat track baru
        for (DetectResult det : unmatched) {
            trackedObjects.add(new TrackedObject(nextId++, det, nowMs));
        }

        // 5. Aging & removal
        Iterator<TrackedObject> it = trackedObjects.iterator();
        while (it.hasNext()) {
            TrackedObject t = it.next();
            if (!t.matched) {
                t.age++;
                if (t.age > maxAge) it.remove();
            } else {
                t.age = 0;
            }
        }

        // 6. Merge overlapping tracks (deduplicate)
        mergeOverlappingTracks();

        // 7. Output stable results
        List<DetectResult> stable = new ArrayList<>();
        for (TrackedObject t : trackedObjects) {
            if (t.hitCount >= minHit) {
                stable.add(t.toDetectResult());
            }
        }

        return stable;
    }

    public List<DetectResult> update(List<DetectResult> currentDetections) {
        return update(currentDetections, 0, 0);
    }

    private void updateGlobalMotion() {
        if (trackedObjects.isEmpty()) {
            globalMotion = MotionDetector.MotionClass.DIAM;
            return;
        }
        float maxSpeed = 0;
        for (TrackedObject t : trackedObjects) {
            float s = t.motionDetector.getSpeed();
            if (s > maxSpeed) maxSpeed = s;
        }
        // Classify based on max tracked speed
        if (maxSpeed < MotionDetector.SPEED_SLOW)   globalMotion = MotionDetector.MotionClass.DIAM;
        else if (maxSpeed < MotionDetector.SPEED_MEDIUM) globalMotion = MotionDetector.MotionClass.LAMBAT;
        else if (maxSpeed < MotionDetector.SPEED_FAST)   globalMotion = MotionDetector.MotionClass.SEDANG;
        else if (maxSpeed < 0.60f)                       globalMotion = MotionDetector.MotionClass.CEPAT;
        else                                             globalMotion = MotionDetector.MotionClass.SANGAT_CEPAT;
    }

    public MotionDetector.MotionClass getGlobalMotion() { return globalMotion; }

    private void mergeOverlappingTracks() {
        for (int i = 0; i < trackedObjects.size(); i++) {
            for (int j = i + 1; j < trackedObjects.size(); ) {
                TrackedObject a = trackedObjects.get(i);
                TrackedObject b = trackedObjects.get(j);
                if (a.classId == b.classId) {
                    float iou = calcIou(a.left, a.top, a.right, a.bottom,
                                        b.left, b.top, b.right, b.bottom);
                    if (iou > 0.75f) {
                        // Keep the one with more hits
                        if (b.hitCount > a.hitCount) {
                            trackedObjects.set(i, b);
                        }
                        trackedObjects.remove(j);
                        continue;
                    }
                }
                j++;
            }
        }
    }

    public void reset() { trackedObjects.clear(); }

    private static float calcIou(float l1, float t1, float r1, float b1,
                                  float l2, float t2, float r2, float b2) {
        float iL = Math.max(l1, l2), iT = Math.max(t1, t2);
        float iR = Math.min(r1, r2), iB = Math.min(b1, b2);
        float iA = Math.max(0, iR - iL) * Math.max(0, iB - iT);
        float a1 = (r1 - l1) * (b1 - t1);
        float a2 = (r2 - l2) * (b2 - t2);
        float u = a1 + a2 - iA;
        return u > 0 ? iA / u : 0f;
    }

    // ===== Inner TrackedObject =====
    private static class TrackedObject {
        int id, classId;
        float left, top, right, bottom;
        // Smoothed velocity (normalized per ms)
        float velX = 0, velY = 0;
        // Predicted position (updated each frame before matching)
        float predLeft, predTop, predRight, predBottom;

        int hitCount = 1;
        int age = 0;
        boolean matched = false;

        final MotionDetector motionDetector = new MotionDetector();

        // Trail data
        final float[] trailX = new float[MAX_TRAIL];
        final float[] trailY = new float[MAX_TRAIL];
        int trailLen = 0;

        // For velocity calculation
        float prevCx, prevCy;
        long prevTimeMs;

        // Smoothed display position (for jitter reduction)
        float smoothLeft, smoothTop, smoothRight, smoothBottom;
        private static final float POS_ALPHA = 0.55f; // EMA weight for position smoothing

        TrackedObject(int id, DetectResult det, long nowMs) {
            this.id = id;
            this.classId = det.classId;
            this.left = det.left; this.top = det.top;
            this.right = det.right; this.bottom = det.bottom;
            smoothLeft = left; smoothTop = top;
            smoothRight = right; smoothBottom = bottom;
            predLeft = left; predTop = top;
            predRight = right; predBottom = bottom;
            prevCx = (left + right) / 2f;
            prevCy = (top + bottom) / 2f;
            prevTimeMs = nowMs;
        }

        float predCx() { return (predLeft + predRight) / 2f; }
        float predCy() { return (predTop + predBottom) / 2f; }

        /** Predict current position using velocity */
        void predict(long nowMs) {
            long dt = nowMs - prevTimeMs;
            if (dt <= 0 || dt > 300) {
                predLeft = left; predTop = top;
                predRight = right; predBottom = bottom;
                return;
            }
            float factor = motionDetector.getPredictionFactor();
            float dx = velX * dt * factor;
            float dy = velY * dt * factor;
            predLeft   = clamp(left  + dx);
            predTop    = clamp(top   + dy);
            predRight  = clamp(right + dx);
            predBottom = clamp(bottom + dy);
        }

        void update(DetectResult det, float camDx, float camDy, long nowMs) {
            float cx = (det.left + det.right) / 2f;
            float cy = (det.top + det.bottom) / 2f;

            long dt = nowMs - prevTimeMs;
            if (dt > 0 && dt < 500) {
                float rawVelX = (cx - prevCx) / dt;
                float rawVelY = (cy - prevCy) / dt;
                // Subtract camera motion to get true object motion
                float trueVelX = rawVelX - camDx;
                float trueVelY = rawVelY - camDy;
                // EMA velocity smoothing — faster update when moving fast
                float speed = (float) Math.sqrt(rawVelX * rawVelX + rawVelY * rawVelY);
                float velAlpha = speed > 0.001f ? 0.55f : 0.30f;
                velX = velX * (1 - velAlpha) + trueVelX * velAlpha;
                velY = velY * (1 - velAlpha) + trueVelY * velAlpha;
            }

            motionDetector.addObservation(cx, cy);

            // EMA position smoothing — less smooth when fast (preserve responsiveness)
            float alpha = motionDetector.classify() == MotionDetector.MotionClass.SANGAT_CEPAT ? 0.85f : POS_ALPHA;
            smoothLeft   = smoothLeft   * (1 - alpha) + det.left   * alpha;
            smoothTop    = smoothTop    * (1 - alpha) + det.top    * alpha;
            smoothRight  = smoothRight  * (1 - alpha) + det.right  * alpha;
            smoothBottom = smoothBottom * (1 - alpha) + det.bottom * alpha;

            left = det.left; top = det.top; right = det.right; bottom = det.bottom;
            prevCx = cx; prevCy = cy;
            prevTimeMs = nowMs;
            hitCount++;

            // Trail — record center
            if (trailLen < MAX_TRAIL) {
                trailX[trailLen] = cx;
                trailY[trailLen] = cy;
                trailLen++;
            } else {
                System.arraycopy(trailX, 1, trailX, 0, MAX_TRAIL - 1);
                System.arraycopy(trailY, 1, trailY, 0, MAX_TRAIL - 1);
                trailX[MAX_TRAIL - 1] = cx;
                trailY[MAX_TRAIL - 1] = cy;
            }
        }

        DetectResult toDetectResult() {
            DetectResult r = new DetectResult();
            // Use smoothed position for display
            r.left   = smoothLeft;
            r.top    = smoothTop;
            r.right  = smoothRight;
            r.bottom = smoothBottom;
            r.classId = classId;
            r.velX = velX;
            r.velY = velY;
            float predFactor = motionDetector.getPredictionFactor();
            r.predX = clamp((smoothLeft + smoothRight) / 2f + velX * 80 * predFactor);
            r.predY = clamp((smoothTop + smoothBottom) / 2f + velY * 80 * predFactor);
            r.trailLen = trailLen;
            System.arraycopy(trailX, 0, r.trailX, 0, trailLen);
            System.arraycopy(trailY, 0, r.trailY, 0, trailLen);
            return r;
        }

        private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
    }
}
