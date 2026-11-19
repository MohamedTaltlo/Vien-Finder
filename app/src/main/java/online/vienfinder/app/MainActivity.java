package online.vienfinder.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.nio.ByteBuffer;
import java.util.Collections;

public class MainActivity extends Activity {
    private final int SKY = Color.rgb(43, 220, 232);
    private final int PANEL = Color.argb(233, 7, 20, 37);
    private SurfaceView cameraView;
    private SurfaceHolder holder;
    private TextView infoLabel;
    private TextView sensitivityLabel;
    private TextView contrastLabel;
    private TextView exposureLabel;
    private Button[] modeButtons;
    private Button torchButton, invertButton, freezeButton;
    private volatile int mode = 3;
    private volatile int sensitivity = 65;
    private volatile int contrast = 58;
    private volatile boolean inverted = false;
    private volatile boolean frozen = false;
    private volatile boolean resumed = false;
    private volatile long lastFrameTime = 0;
    private long measureTime = 0;
    private int measuredFrames = 0;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private CaptureRequest.Builder request;
    private CameraManager cameraManager;
    private String cameraId;
    private ImageReader reader;
    private boolean opening = false;
    private boolean flashlightAvailable = false;
    private boolean flashlightOn = false;
    private int sensorRotation = 90;
    private int imageWidth = 640, imageHeight = 480;
    private Range<Integer> exposureRange = new Range<Integer>(0, 0);
    private float exposureStep = 1f;
    private int exposureIndex = 0;
    private int[] pixels, feature, green, featureSum, greenSum;
    private Bitmap bitmap;
    private final Matrix screenMatrix = new Matrix();
    private final Paint screenPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(5, 14, 29));
        getWindow().setNavigationBarColor(Color.rgb(5, 14, 29));
        setContentView(makeUI());
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
    private GradientDrawable background(int color, float radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(radius));
        return gd;
    }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(null, 1);
        return t;
    }
    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(11), dp(6), dp(11), dp(6));
        b.setBackground(background(Color.rgb(29, 48, 68), 12));
        return b;
    }
    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private View makeUI() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(5, 14, 29));
        cameraView = new SurfaceView(this);
        cameraView.setKeepScreenOn(true);
        holder = cameraView.getHolder();
        frame.addView(cameraView, new FrameLayout.LayoutParams(-1, -1));
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int z) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) { }
        });

        LinearLayout header = column();
        header.setPadding(dp(18), dp(12), dp(18), dp(13));
        header.setBackground(background(Color.argb(219, 6, 17, 32), 0));
        TextView title = text("Vien Finder", 24, Color.WHITE, true);
        header.addView(title);
        TextView sub = text("LIVE  •  Camera vein contrast", 12, SKY, false);
        header.addView(sub);
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        frame.addView(header, top);

        LinearLayout panel = column();
        panel.setPadding(dp(13), dp(13), dp(13), dp(11));
        panel.setBackground(background(PANEL, 23));

        TextView modes = text("طريقة العرض", 13, Color.WHITE, true);
        panel.addView(modes);
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = new String[]{"طبيعي", "أخضر", "R−G", "إبراز الأوردة"};
        modeButtons = new Button[names.length];
        for (int i = 0; i < names.length; i++) {
            final int which = i;
            Button b = button(names[i]);
            modeButtons[i] = b;
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(43));
            bp.setMargins(0, dp(8), dp(7), dp(8));
            buttons.addView(b, bp);
            b.setOnClickListener(v -> { mode = which; refreshButtons(); });
        }
        scroller.addView(buttons);
        panel.addView(scroller);
        refreshButtons();

        sensitivityLabel = text("الحساسية 65%", 13, Color.WHITE, false);
        panel.addView(sensitivityLabel);
        SeekBar sens = new SeekBar(this);
        sens.setMax(100); sens.setProgress(sensitivity);
        sens.setProgressTintList(android.content.res.ColorStateList.valueOf(SKY));
        sens.setThumbTintList(android.content.res.ColorStateList.valueOf(SKY));
        panel.addView(sens);
        sens.setOnSeekBarChangeListener(listener(value -> {
            sensitivity = value;
            sensitivityLabel.setText("الحساسية " + value + "%");
        }));

        contrastLabel = text("قوة التباين 58%", 13, Color.WHITE, false);
        panel.addView(contrastLabel);
        SeekBar con = new SeekBar(this);
        con.setMax(100); con.setProgress(contrast);
        con.setProgressTintList(android.content.res.ColorStateList.valueOf(SKY));
        con.setThumbTintList(android.content.res.ColorStateList.valueOf(SKY));
        panel.addView(con);
        con.setOnSeekBarChangeListener(listener(value -> {
            contrast = value;
            contrastLabel.setText("قوة التباين " + value + "%");
        }));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        torchButton = button("الضوء: إيقاف");
        torchButton.setOnClickListener(v -> {
            if (!flashlightAvailable) {
                Toast.makeText(this, "المصباح غير متاح للكاميرا", Toast.LENGTH_SHORT).show();
                return;
            }
            flashlightOn = !flashlightOn;
            torchButton.setText(flashlightOn ? "الضوء: تشغيل" : "الضوء: إيقاف");
            updateCameraRequest();
        });
        addAction(actions, torchButton, 1);
        invertButton = button("عكس الألوان");
        invertButton.setOnClickListener(v -> {
            inverted = !inverted;
            invertButton.setText(inverted ? "عكس: مفعل" : "عكس الألوان");
        });
        addAction(actions, invertButton, 1);
        freezeButton = button("تجميد");
        freezeButton.setOnClickListener(v -> {
            frozen = !frozen;
            freezeButton.setText(frozen ? "متابعة" : "تجميد");
        });
        addAction(actions, freezeButton, 1);
        panel.addView(actions);

        LinearLayout exposure = new LinearLayout(this);
        exposure.setGravity(Gravity.CENTER_VERTICAL);
        Button minus = button("−");
        Button plus = button("+");
        exposureLabel = text("EV 0.0", 12, SKY, true);
        exposureLabel.setGravity(Gravity.CENTER);
        exposure.addView(minus, new LinearLayout.LayoutParams(dp(55), dp(40)));
        exposure.addView(exposureLabel, new LinearLayout.LayoutParams(0, dp(40), 1));
        exposure.addView(plus, new LinearLayout.LayoutParams(dp(55), dp(40)));
        minus.setOnClickListener(v -> changeExposure(-1));
        plus.setOnClickListener(v -> changeExposure(1));
        panel.addView(exposure);
        infoLabel = text("جاري تهيئة الكاميرا...", 11, Color.rgb(167, 196, 216), false);
        infoLabel.setGravity(Gravity.CENTER);
        panel.addView(infoLabel);

        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bottom.setMargins(dp(9), dp(9), dp(9), dp(10));
        frame.addView(panel, bottom);
        return frame;
    }

    private interface ValueChange { void change(int value); }
    private SeekBar.OnSeekBarChangeListener listener(ValueChange change) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) { change.change(p); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        };
    }
    private void addAction(LinearLayout row, Button b, int weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(43), weight);
        lp.setMargins(dp(3), dp(7), dp(3), dp(4));
        row.addView(b, lp);
    }
    private void refreshButtons() {
        if (modeButtons == null) return;
        for (int i = 0; i < modeButtons.length; i++) {
            modeButtons[i].setBackground(background(i == mode ? Color.rgb(0, 130, 159) : Color.rgb(29, 48, 68), 12));
        }
    }
    private void changeExposure(int direction) {
        int amount = Math.max(1, Math.round(0.5f / Math.max(exposureStep, 0.01f)));
        exposureIndex = Math.max(exposureRange.getLower(), Math.min(exposureRange.getUpper(), exposureIndex + direction * amount));
        exposureLabel.setText(String.format(java.util.Locale.US, "EV %+.1f", exposureIndex * exposureStep));
        updateCameraRequest();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        cameraThread = new HandlerThread("vien-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, 41);
    }
    @Override protected void onPause() {
        resumed = false;
        closeCamera();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
        super.onPause();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 41 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && resumed) openCamera();
        else if (code == 41) infoLabel.setText("لازم تسمح باستخدام الكاميرا من إعدادات التطبيق");
    }
    private void openCamera() {
        if (!resumed || opening || device != null || cameraHandler == null) return;
        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            cameraId = null;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
                if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) { infoLabel.setText("لا توجد كاميرا خلفية"); return; }
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(cameraId);
            Integer rotate = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (rotate != null) sensorRotation = rotate;
            Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            flashlightAvailable = Boolean.TRUE.equals(flash);
            Range<Integer> evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (evRange != null) exposureRange = evRange;
            android.util.Rational evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if (evStep != null && evStep.floatValue() > 0) exposureStep = evStep.floatValue();

            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
            if (sizes == null || sizes.length == 0) {
                infoLabel.setText("صيغة الفيديو غير مدعومة");
                return;
            }
            Size chosen = sizes[0];
            long difference = Long.MAX_VALUE;
            for (Size s : sizes) {
                int sw = s.getWidth(), sh = s.getHeight();
                if (sw < 400 || sh < 300 || sw > 1920) continue;
                long score = Math.abs((long) sw * sh - 640L * 480L);
                if (Math.abs(sw * 3 - sh * 4) > 10) score += 4000000L;
                if (score < difference) { difference = score; chosen = s; }
            }
            imageWidth = chosen.getWidth();
            imageHeight = chosen.getHeight();
            initBuffers();
            reader = ImageReader.newInstance(imageWidth, imageHeight, android.graphics.ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(r -> {
                Image image = null;
                try {
                    image = r.acquireLatestImage();
                    if (image == null) return;
                    long now = SystemClock.elapsedRealtime();
                    if (frozen || now - lastFrameTime < 43) return;
                    lastFrameTime = now;
                    process(image);
                } catch (Exception ignored) {
                } finally {
                    if (image != null) image.close();
                }
            }, cameraHandler);
            opening = true;
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice opened) {
                    opening = false;
                    if (!resumed) { opened.close(); return; }
                    device = opened;
                    startCapture();
                }
                @Override public void onDisconnected(CameraDevice disconnected) {
                    disconnected.close();
                    if (device == disconnected) device = null;
                    opening = false;
                }
                @Override public void onError(CameraDevice bad, int error) {
                    bad.close();
                    if (device == bad) device = null;
                    opening = false;
                    runOnUiThread(() -> infoLabel.setText("تعذّر تشغيل الكاميرا: " + error));
                }
            }, cameraHandler);
        } catch (Exception e) {
            opening = false;
            infoLabel.setText("خطأ الكاميرا: " + e.getMessage());
        }
    }
    private void initBuffers() {
        int count = imageWidth * imageHeight;
        pixels = new int[count];
        feature = new int[count];
        green = new int[count];
        featureSum = new int[(imageWidth + 1) * (imageHeight + 1)];
        greenSum = new int[(imageWidth + 1) * (imageHeight + 1)];
        bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
    }
    private void startCapture() {
        if (device == null || reader == null) return;
        try {
            request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(reader.getSurface());
            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            device.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (!resumed) { s.close(); return; }
                    session = s;
                    updateCameraRequest();
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    runOnUiThread(() -> infoLabel.setText("تعذّر تهيئة تدفق الكاميرا"));
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            runOnUiThread(() -> infoLabel.setText("خطأ تشغيل الفيديو"));
        }
    }
    private void updateCameraRequest() {
        if (cameraHandler == null) return;
        cameraHandler.post(() -> {
            if (session == null || request == null) return;
            try {
                request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureIndex);
                request.set(CaptureRequest.FLASH_MODE,
                        flashlightOn && flashlightAvailable ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                session.setRepeatingRequest(request.build(), null, cameraHandler);
            } catch (Exception e) {
                runOnUiThread(() -> infoLabel.setText("تعذّر تحديث إعدادات التصوير"));
            }
        });
    }
    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        session = null;
        try { if (device != null) device.close(); } catch (Exception ignored) { }
        device = null;
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        reader = null;
        opening = false;
        flashlightOn = false;
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }
    private void process(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yData = planes[0].getBuffer();
        ByteBuffer uData = planes[1].getBuffer();
        ByteBuffer vData = planes[2].getBuffer();
        int ys = planes[0].getRowStride(), yp = planes[0].getPixelStride();
        int us = planes[1].getRowStride(), up = planes[1].getPixelStride();
        int vs = planes[2].getRowStride(), vp = planes[2].getPixelStride();
        int w = imageWidth, h = imageHeight, stride = w + 1;
        if (pixels == null || feature == null) return;
        for (int y = 0; y < h; y++) {
            int sumF = 0, sumG = 0, row = y * w;
            int upper = y * stride, lower = (y + 1) * stride;
            for (int x = 0; x < w; x++) {
                int yy = yData.get(y * ys + x * yp) & 255;
                int uu = (uData.get((y >> 1) * us + (x >> 1) * up) & 255) - 128;
                int vv = (vData.get((y >> 1) * vs + (x >> 1) * vp) & 255) - 128;
                int red = clamp((int)(yy + 1.403f * vv));
                int gg = clamp((int)(yy - 0.344f * uu - 0.714f * vv));
                int blue = clamp((int)(yy + 1.770f * uu));
                int idx = row + x;
                green[idx] = gg;
                feature[idx] = red - gg;
                pixels[idx] = Color.rgb(red, gg, blue);
                sumF += feature[idx]; sumG += gg;
                featureSum[lower + x + 1] = featureSum[upper + x + 1] + sumF;
                greenSum[lower + x + 1] = greenSum[upper + x + 1] + sumG;
            }
        }
        int activeMode = mode;
        if (activeMode != 0) {
            int threshold = 15 - sensitivity * 13 / 100;
            float gain = 4.5f + contrast * 0.20f;
            int sign = inverted ? -1 : 1;
            int radius = 15;
            for (int y = 0; y < h; y++) {
                int top = Math.max(0, y - radius), bottom = Math.min(h, y + radius + 1);
                for (int x = 0; x < w; x++) {
                    int left = Math.max(0, x - radius), right = Math.min(w, x + radius + 1);
                    int area = (right - left) * (bottom - top);
                    int idx = y * w + x;
                    int fMean = (featureSum[bottom * stride + right] -
                                 featureSum[top * stride + right] -
                                 featureSum[bottom * stride + left] +
                                 featureSum[top * stride + left]) / area;
                    int gMean = (greenSum[bottom * stride + right] -
                                 greenSum[top * stride + right] -
                                 greenSum[bottom * stride + left] +
                                 greenSum[top * stride + left]) / area;
                    float deviation = sign * ((feature[idx] - fMean) * 0.85f +
                                                    (gMean - green[idx]) * 0.38f);
                    int intensity = clamp((int)(Math.max(0, deviation - threshold) * gain));
                    if (activeMode == 1) {
                        int z = clamp((int)(green[idx] - (gMean - green[idx]) * gain * 0.55f));
                        pixels[idx] = Color.rgb(z, z, z);
                    } else if (activeMode == 2) {
                        int z = clamp((int)(102 + (deviation - threshold) * gain * 0.75f));
                        pixels[idx] = Color.rgb(z, z, z);
                    } else {
                        int base = pixels[idx];
                        int alpha = Math.min(217, intensity * 2);
                        int red = Color.red(base) * (255 - alpha) / 255;
                        int gg = Color.green(base) + (255 - Color.green(base)) * alpha / 255;
                        int blue = Color.blue(base) + (255 - Color.blue(base)) * alpha / 255;
                        pixels[idx] = Color.rgb(red, gg, blue);
                    }
                }
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        render();
        measuredFrames++;
        long now = SystemClock.elapsedRealtime();
        if (now - measureTime > 1200) {
            final int fps = Math.round(1000f * measuredFrames / Math.max(1, now - measureTime));
            measureTime = now;
            measuredFrames = 0;
            runOnUiThread(() -> {
                if (resumed) infoLabel.setText(imageWidth + "×" + imageHeight + "  •  " + fps + " FPS  •  Offline");
            });
        }
    }
    private void render() {
        if (holder == null || !holder.getSurface().isValid()) return;
        Canvas canvas = null;
        try {
            canvas = holder.lockCanvas();
            if (canvas == null) return;
            canvas.drawColor(Color.BLACK);
            float cw = canvas.getWidth(), ch = canvas.getHeight();
            int rotation = ((sensorRotation % 360) + 360) % 360;
            int rotatedWidth = rotation == 90 || rotation == 270 ? imageHeight : imageWidth;
            int rotatedHeight = rotation == 90 || rotation == 270 ? imageWidth : imageHeight;
            float scale = Math.max(cw / rotatedWidth, ch / rotatedHeight);
            screenMatrix.reset();
            screenMatrix.postTranslate(-imageWidth / 2f, -imageHeight / 2f);
            screenMatrix.postRotate(rotation);
            screenMatrix.postScale(scale, scale);
            screenMatrix.postTranslate(cw / 2f, ch / 2f);
            canvas.drawBitmap(bitmap, screenMatrix, screenPaint);
        } catch (Exception ignored) {
        } finally {
            if (canvas != null) {
                try { holder.unlockCanvasAndPost(canvas); } catch (Exception ignored) { }
            }
        }
    }
}
