package com.detector.esp.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Camera2 封装 — 支持数码变焦 + 拍照
 */
public class CameraHelper {

    private static final String TAG = "CameraHelper";

    public interface PhotoCallback {
        void onPhotoTaken(Bitmap bitmap);
    }

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;       // 低Resolusi分析Frame
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Handler mainHandler;           // 主Thread Handler，用于 UI 操作
    private CaptureRequest.Builder previewBuilder;

    private final AtomicReference<Image> latestFrame = new AtomicReference<>(null);

    private final TextureView textureView;
    private final Context context;
    private Size previewSize;   // 高Resolusi预览
    private Size analysisSize;  // 低Resolusi分析
    private Size captureSize;   // 最高画质拍照
    private ImageReader jpegReader; // JPEG 拍照用

    // 传感器信息
    private Rect sensorArraySize;
    private int sensorOrientation = 90;
    private float currentZoom = 1.0f;
    private float hFovDegrees = 70f;  // 水平 FOV（动态读取）
    private float vFovDegrees = 50f;  // 垂直 FOV（动态读取）
    private float maxZoom = 1.0f;
    private static final float MAX_DIGITAL_ZOOM = 1000.0f;

    // Target FPS yang bisa diatur dari luar (3–60)
    private volatile int targetFps = 30;
    private Range<Integer>[] availableFpsRanges;  // disimpan saat openCamera


    public CameraHelper(Context context, TextureView textureView) {
        this.context = context;
        this.textureView = textureView;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void start() {
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
            });
        }
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = findBackCamera(manager);
            if (cameraId == null) {
                Log.e(TAG, "未找到后置Kamera");
                return;
            }

            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            previewSize = chooseHighResSize(map);    // 1080p 预览
            analysisSize = chooseLowResSize(map);    // 640x480 分析
            captureSize = chooseMaxJpegSize(map);    // 最高画质拍照

            sensorArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            // Simpan FPS ranges yang didukung hardware
            availableFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Integer orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (orientation != null) sensorOrientation = orientation;
            Float maxZoomVal = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = (maxZoomVal != null) ? maxZoomVal : 10.0f;

            // 动态读取 FOV（适配所有手机）
            float[] focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            android.util.SizeF sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (focalLengths != null && focalLengths.length > 0 && sensorSize != null) {
                float focalLen = focalLengths[0];
                hFovDegrees = (float) Math.toDegrees(2 * Math.atan(sensorSize.getWidth() / (2 * focalLen)));
                vFovDegrees = (float) Math.toDegrees(2 * Math.atan(sensorSize.getHeight() / (2 * focalLen)));
            }

            Log.i(TAG, "预览: " + previewSize + " 分析: " + analysisSize
                    + " 拍照: " + captureSize
                    + " 传感器Rotasi: " + sensorOrientation + "° 硬件变焦: " + maxZoom + "x"
                    + " FOV: " + String.format("%.1f°x%.1f°", hFovDegrees, vFovDegrees));

            // ImageReader 用低Resolusi做Deteksi
            imageReader = ImageReader.newInstance(
                    analysisSize.getWidth(), analysisSize.getHeight(),
                    ImageFormat.YUV_420_888, 4
            );
            imageReader.setOnImageAvailableListener(reader -> {
                try {
                    Image image = reader.acquireLatestImage();
                    if (image != null) {
                        Image old = latestFrame.getAndSet(image);
                        if (old != null) old.close();
                    }
                } catch (IllegalStateException e) {
                    // maxImages 达上限
                }
            }, cameraHandler);

            // JPEG 拍照 ImageReader（最高画质）
            jpegReader = ImageReader.newInstance(
                    captureSize.getWidth(), captureSize.getHeight(),
                    ImageFormat.JPEG, 2);
            Log.i(TAG, "JPEG拍照 ImageReader: " + captureSize);

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession();
                }
                @Override public void onDisconnected(@NonNull CameraDevice camera) { camera.close(); }
                @Override public void onError(@NonNull CameraDevice camera, int error) { camera.close(); }
            }, cameraHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera open failed", e);
        }
    }

    private void createCaptureSession() {
        try {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null) return;
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);
            Surface analysisSurface = imageReader.getSurface();
            Surface jpegSurface = jpegReader.getSurface();

            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            previewBuilder.addTarget(analysisSurface);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            // 暗光优化：场景模式夜间 + 降噪高质量
            previewBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT);
            previewBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
            previewBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            // Terapkan FPS target
            applyFpsRange(previewBuilder);

            // 应用当前变焦
            applyZoom(previewBuilder);

            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, analysisSurface, jpegSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(previewBuilder.build(), null, cameraHandler);
                                Log.i(TAG, "Kamera预览Dimulai: " + previewSize + " 变焦: " + currentZoom + "x");

                                // ✅ Bug fix #1：configureTransform 必须在主Thread调用
                                mainHandler.post(() -> configureTransform());
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Repeating request failed", e);
                            }
                        }
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Session config failed");
                        }
                    }, cameraHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Session creation failed", e);
        }
    }

    /**
     * 设置变焦倍数（1.0 ~ 1000.0）
     * 1-maxZoom: 硬件变焦
     * maxZoom-1000: 软件裁切（CpuPreprocessor 中心裁切）
     */
    public void setZoom(float zoom) {
        zoom = Math.max(1.0f, Math.min(MAX_DIGITAL_ZOOM, zoom));
        currentZoom = zoom;

        if (previewBuilder != null && captureSession != null) {
            applyZoom(previewBuilder);
            try {
                captureSession.setRepeatingRequest(previewBuilder.build(), null, cameraHandler);
            } catch (CameraAccessException e) {
                Log.e(TAG, "Zoom update failed", e);
            }
        }
    }

    public float getZoom() { return currentZoom; }
    public float getMaxHardwareZoom() { return maxZoom; }

    /**
     * 获取软件变焦因子：超过硬件最大倍数的部分
     * 例如：当前 zoom=40x，硬件 max=8x，则软件因子=40/8=5.0
     * CpuPreprocessor 用这个值来做中心裁切
     */
    public float getSoftwareZoomFactor() {
        if (currentZoom <= maxZoom) return 1.0f;
        return currentZoom / maxZoom;
    }

    /**
     * Set target FPS kamera (3–60).
     * Pilih FPS range yang paling cocok dari yang didukung hardware.
     */
    @SuppressWarnings("unchecked")
    public void setTargetFps(int fps) {
        fps = Math.max(3, Math.min(60, fps));
        targetFps = fps;
        if (previewBuilder != null && captureSession != null) {
            applyFpsRange(previewBuilder);
            try {
                captureSession.setRepeatingRequest(previewBuilder.build(), null, cameraHandler);
                Log.i(TAG, "Target FPS diubah ke " + fps);
            } catch (CameraAccessException e) {
                Log.w(TAG, "Gagal update FPS: " + e.getMessage());
            }
        }
    }

    public int getTargetFps() { return targetFps; }

    /**
     * Pilih Range<Integer> AE_TARGET_FPS_RANGE terbaik untuk targetFps.
     * Prioritas: range [targetFps, targetFps] → [x, targetFps] → [x, ≥targetFps]
     */
    @SuppressWarnings("unchecked")
    private void applyFpsRange(CaptureRequest.Builder builder) {
        if (availableFpsRanges == null || availableFpsRanges.length == 0) {
            // Fallback jika device tidak laporkan ranges
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    new Range<>(targetFps, targetFps));
            return;
        }

        Range<Integer> best = null;
        // Cari range yang upper bound == targetFps dan lower bound semaksimal mungkin
        for (Range<Integer> r : availableFpsRanges) {
            if (r.getUpper() < targetFps) continue;
            if (best == null) { best = r; continue; }
            // Prefer: upper lebih dekat ke targetFps
            int diffNew = Math.abs(r.getUpper() - targetFps);
            int diffBest = Math.abs(best.getUpper() - targetFps);
            if (diffNew < diffBest) { best = r; continue; }
            if (diffNew == diffBest && r.getLower() > best.getLower()) best = r;
        }
        if (best == null) best = availableFpsRanges[availableFpsRanges.length - 1];
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, best);
        Log.i(TAG, "AE FPS range dipilih: " + best + " untuk target " + targetFps);
    }


        if (sensorArraySize == null) return;

        // 硬件变焦：限制到硬件最大值
        float hwZoom = Math.min(currentZoom, maxZoom);

        // ✅ Bug fix #2：cropW/cropH 最小为 1，防止 zoom 极大时产生非法 Rect 导致 crash
        int cropW = Math.max(1, (int) (sensorArraySize.width() / hwZoom));
        int cropH = Math.max(1, (int) (sensorArraySize.height() / hwZoom));
        int cropX = (sensorArraySize.width() - cropW) / 2;
        int cropY = (sensorArraySize.height() - cropH) / 2;

        Rect cropRegion = new Rect(cropX, cropY, cropX + cropW, cropY + cropH);
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
    }

    /**
     * 冻结预览画面（Berhenti刷新 TextureView，但Kamera仍在运行）
     */
    public void freezePreview() {
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
                Log.i(TAG, "预览已冻结");
            } catch (CameraAccessException e) {
                Log.e(TAG, "冻结预览Gagal", e);
            }
        }
    }

    /**
     * 恢复预览画面
     */
    public void unfreezePreview() {
        if (captureSession != null && previewBuilder != null) {
            try {
                captureSession.setRepeatingRequest(previewBuilder.build(), null, cameraHandler);
                Log.i(TAG, "预览已恢复");
            } catch (CameraAccessException e) {
                Log.e(TAG, "恢复预览Gagal", e);
            }
        }
    }

    /**
     * 拍照：使用 Camera2 STILL_CAPTURE 获取传感器最高Resolusi JPEG
     */
    public void takePhoto(PhotoCallback callback) {
        if (cameraDevice == null || captureSession == null || jpegReader == null) {
            // fallback: 使用 TextureView
            mainHandler.post(() -> {
                Bitmap photo = textureView.getBitmap();
                if (photo != null) callback.onPhotoTaken(photo);
            });
            return;
        }

        // 设置 JPEG Callback
        jpegReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] jpegData = new byte[buffer.remaining()];
                    buffer.get(jpegData);
                    Bitmap raw = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
                    // JPEG 是传感器原始方向，需要根据 sensorOrientation Rotasi
                    Bitmap photo;
                    if (sensorOrientation != 0) {
                        android.graphics.Matrix rotMatrix = new android.graphics.Matrix();
                        rotMatrix.postRotate(sensorOrientation);
                        photo = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), rotMatrix, true);
                        raw.recycle();
                    } else {
                        photo = raw;
                    }
                    Log.i(TAG, "高画质拍照: " + photo.getWidth() + "x" + photo.getHeight());
                    mainHandler.post(() -> callback.onPhotoTaken(photo));
                }
            } catch (Exception e) {
                Log.e(TAG, "JPEG 解码Gagal", e);
            } finally {
                if (image != null) image.close();
            }
        }, cameraHandler);

        try {
            CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(jpegReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);  // 最高画质
            // 继承当前变焦
            applyZoom(captureBuilder);

            captureSession.capture(captureBuilder.build(), null, cameraHandler);
            Log.i(TAG, "JPEG 拍照请求已发送: " + captureSize);
        } catch (CameraAccessException e) {
            Log.e(TAG, "拍照请求Gagal", e);
        }
    }

    /**
     * InferensiThread调用：获取最新Frame
     * 注意：调用方负责在使用完毕后调用 image.close()，否则会导致内存泄漏
     */
    public Image pollLatestFrame() {
        return latestFrame.getAndSet(null);
    }

    /**
     * 配置 TextureView 变换矩阵，使预览画面居中裁切而不是拉伸
     * ✅ Bug fix #4：使用实际 sensorOrientation 而非硬编码 90°
     */
    private void configureTransform() {
        if (textureView == null || previewSize == null) return;
        int viewW = textureView.getWidth();
        int viewH = textureView.getHeight();
        if (viewW == 0 || viewH == 0) return;

        // 系统已通过 SurfaceTexture 内部矩阵处理Rotasi，不需要手动Rotasi
        // Rotasi后有效尺寸：portrait 模式下 bufH x bufW
        float effW = (sensorOrientation == 90 || sensorOrientation == 270)
                ? previewSize.getHeight() : previewSize.getWidth();
        float effH = (sensorOrientation == 90 || sensorOrientation == 270)
                ? previewSize.getWidth() : previewSize.getHeight();

        // center-crop：均匀Zoom填满 view，裁掉多余部分
        float scaleX = (float) viewW / effW;
        float scaleY = (float) viewH / effH;
        float scale = Math.max(scaleX, scaleY);

        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.setScale(scale / scaleX, scale / scaleY, viewW / 2f, viewH / 2f);

        textureView.setTransform(matrix);
        Log.i(TAG, "TextureView transform: view=" + viewW + "x" + viewH
                + " eff=" + effW + "x" + effH + " scale=" + scale);
    }

    public Size getPreviewSize() { return previewSize; }
    public int getSensorOrientation() { return sensorOrientation; }
    public float getHFovDegrees() { return hFovDegrees; }
    public float getVFovDegrees() { return vFovDegrees; }

    public void stop() {
        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
            if (jpegReader != null) { jpegReader.close(); jpegReader = null; }
            Image frame = latestFrame.getAndSet(null);
            if (frame != null) frame.close();
        } catch (Exception e) {
            Log.e(TAG, "Stop error", e);
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
        previewBuilder = null;
    }

    private String findBackCamera(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics chars = manager.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return null;
    }

    /** 预览Resolusi：1080p */
    private Size chooseHighResSize(StreamConfigurationMap map) {
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        for (Size s : sizes) {
            if (s.getWidth() == 1920 && s.getHeight() == 1080) {
                Log.i(TAG, "预览Resolusi: " + s);
                return s;
            }
        }
        Size best = sizes[0];
        int target = 1920 * 1080;
        int bestDiff = Integer.MAX_VALUE;
        for (Size s : sizes) {
            int diff = Math.abs(s.getWidth() * s.getHeight() - target);
            if (diff < bestDiff) { bestDiff = diff; best = s; }
        }
        Log.i(TAG, "预览Resolusi: " + best);
        return best;
    }

    /** 拍照Resolusi：传感器最大 JPEG 尺寸 */
    private Size chooseMaxJpegSize(StreamConfigurationMap map) {
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        Log.i(TAG, "拍照Resolusi: " + best);
        return best;
    }

    /** 低Resolusi：给 ImageReader 做Deteksi分析（匹配预览宽高比） */
    private Size chooseLowResSize(StreamConfigurationMap map) {
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        // 计算预览宽高比
        float previewRatio = (float) previewSize.getWidth() / previewSize.getHeight(); // 1920/1080 ≈ 1.78

        // 优先找跟预览宽高比一致的小Resolusi（16:9）
        Size best = null;
        float bestDiff = Float.MAX_VALUE;
        for (Size s : sizes) {
            if (s.getWidth() < 320 || s.getWidth() > 960) continue;
            float ratio = (float) s.getWidth() / s.getHeight();
            float diff = Math.abs(ratio - previewRatio);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        if (best != null && bestDiff < 0.1f) {
            Log.i(TAG, "分析Resolusi（匹配预览）: " + best);
            return best;
        }

        // 回退到 640x480
        for (Size s : sizes) {
            if (s.getWidth() == 640 && s.getHeight() == 480) return s;
        }
        return sizes[sizes.length - 1];
    }
}