package com.gathin.instagramremote;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class OfflinePlaybackCheckActivity extends Activity {
    private static final String TAG = "OfflineReelsCheck";
    private static final int TOTAL = 36;
    private static final String[] FIXTURES = {"portrait.mp4", "landscape.mp4",
            "sideways-cw.mp4", "sideways-ccw.mp4", "metadata90.mp4",
            "small-content.mp4", "edges-expand.mp4", "dark.mp4", "captioned-small.mp4"};
    private static final int[] ROTATIONS = {0, 0, 270, 90, 0, 0, 0, 0, 0};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONArray events = new JSONArray();
    private FrameLayout videoArea;
    private TextView statusView;
    private File directory;
    private ReelsPlayback playback;
    private boolean started;
    private boolean running;
    private String status = "running";
    private String error = "";
    private String fixture = "";
    private int step;
    private int passed;
    private int created;
    private int released;
    private long stepStarted;
    private long readyMs = -1;
    private long firstFrameMs = -1;
    private long firstFrames;
    private long middleFrames;
    private long rendered;
    private int textureWidth;
    private int textureHeight;
    private final RectF displayBounds = new RectF();
    private int rotationDegrees;
    private float textureRotation;
    private String displayInfo = "";
    private float contentTop, contentBottom = 1;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        directory = new File(getCacheDir(), "reels-playback-test");

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.HORIZONTAL);
        screen.setBackgroundColor(0xFF080B10);
        videoArea = new FrameLayout(this);
        videoArea.setBackgroundColor(Color.BLACK);
        screen.addView(videoArea, new LinearLayout.LayoutParams(0, -1, 1));
        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(17);
        statusView.setGravity(Gravity.CENTER);
        int padding = Math.round(12 * getResources().getDisplayMetrics().density);
        statusView.setPadding(padding, padding, padding, padding);
        statusView.setText("离线播放检查\n不会连接Instagram\n等待本地检查");
        screen.addView(statusView, new LinearLayout.LayoutParams(
                Math.round(190 * getResources().getDisplayMetrics().density), -1));
        setContentView(screen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (started) return;
        started = true;
        running = true;
        handler.post(() -> {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                stop("failed", "无法创建本地结果目录");
                return;
            }
            for (String name : FIXTURES) {
                File file = new File(directory, name);
                if (!file.isFile() || !file.canRead() || file.length() == 0) {
                    stop("failed", "本地视频缺失或不可读：" + name);
                    return;
                }
            }
            if (record("started")) startStep();
        });
    }

    private void startStep() {
        if (!running) return;
        step++;
        int fixtureIndex = (step - 1) % FIXTURES.length;
        fixture = FIXTURES[fixtureIndex];
        rotationDegrees = ROTATIONS[fixtureIndex];
        readyMs = -1;
        firstFrameMs = -1;
        firstFrames = 0;
        middleFrames = 0;
        rendered = 0;
        textureWidth = 0;
        textureHeight = 0;
        displayBounds.setEmpty();
        textureRotation = 0;
        displayInfo = "";
        stepStarted = SystemClock.elapsedRealtime();
        if (!record("creating")) return;
        final int currentStep = step;
        try {
            playback = new ReelsPlayback(this,
                    Uri.fromFile(new File(directory, fixture)).toString(),
                    "OfflineReelsCheck", 0, true, new ReelsPlayback.Listener() {
                        @Override
                        public void onReady() {
                            handler.post(() -> {
                                if (!running || step != currentStep) return;
                                readyMs = SystemClock.elapsedRealtime() - stepStarted;
                                record("ready");
                            });
                        }

                        @Override
                        public void onFirstFrame() {
                            handler.post(() -> {
                                if (!running || step != currentStep) return;
                                firstFrameMs = SystemClock.elapsedRealtime() - stepStarted;
                                capture();
                                firstFrames = rendered;
                                if (!playback.hasFirstFrame() || rendered < 1) {
                                    stop("failed", "首帧回调没有实际渲染帧");
                                    return;
                                }
                                if (!record("first_frame")) return;
                                handler.postDelayed(() -> {
                                    if (!running || step != currentStep) return;
                                    capture();
                                    middleFrames = rendered;
                                    record("late_frame_sample");
                                }, currentStep == 1 ? 6500 : 2500);
                                handler.postDelayed(() -> finishStep(currentStep), currentStep == 1 ? 9500 : 3500);
                            });
                        }

                        @Override
                        public void onError(int errorCode) {
                            handler.post(() -> {
                                if (running && step == currentStep) {
                                    stop("failed", "播放器错误：" + errorCode);
                                }
                            });
                        }
                    });
            created++;
            playback.setRotationDegrees(rotationDegrees);
            videoArea.addView(playback.view, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            record("created");
        } catch (RuntimeException | LinkageError e) {
            stop("failed", "无法创建播放器：" + e.getClass().getSimpleName());
        }
    }

    private void finishStep(int currentStep) {
        if (!running || step != currentStep) return;
        capture();
        if (rendered < 6 || middleFrames <= firstFrames || rendered <= middleFrames) {
            stop("failed", "渲染帧不足6帧或未持续增长");
            return;
        }
        if (textureWidth <= 0 || textureHeight <= 0
                || displayBounds.width() <= 0 || displayBounds.height() <= 0) {
            stop("failed", "视频画面尺寸无效");
            return;
        }
        if (textureRotation != rotationDegrees) {
            stop("failed", "视频画面旋转角度不符");
            return;
        }
        if (!"metadata90.mp4".equals(fixture)) {
            String[] dimensions = displayInfo.split(" ");
            String[] encoded = dimensions[0].substring("encoded=".length()).split("x");
            String[] visible = dimensions[1].substring("visible=".length()).split("x");
            String[] pixelAspect = dimensions[2].substring("pixelAspect=".length()).split("/");
            boolean trimmed = contentTop > 0 || contentBottom < 1;
            double contentSourceHeight = trimmed ? Double.parseDouble(encoded[1]) * (contentBottom - contentTop)
                    : Double.parseDouble(visible[1]);
            double expectedAspect = Double.parseDouble(visible[0]) / contentSourceHeight
                    * Double.parseDouble(pixelAspect[0]) / Double.parseDouble(pixelAspect[1]);
            if (rotationDegrees % 180 != 0) expectedAspect = 1 / expectedAspect;
            boolean quarterTurn = rotationDegrees % 180 != 0;
            double contentWidth = displayBounds.width() * Double.parseDouble(visible[quarterTurn ? 1 : 0])
                    / Double.parseDouble(encoded[quarterTurn ? 1 : 0]);
            double contentHeight = displayBounds.height() * Double.parseDouble(visible[quarterTurn ? 0 : 1])
                    / Double.parseDouble(encoded[quarterTurn ? 0 : 1]);
            if (trimmed && !quarterTurn) contentHeight = displayBounds.height();
            double actualAspect = contentWidth / contentHeight;
            if (Math.abs(actualAspect / expectedAspect - 1) > 0.02
                    || contentWidth > videoArea.getWidth() * 1.02
                    || contentHeight > videoArea.getHeight() * 1.02) {
                stop("failed", "视频画面拉伸或超出可用区域，超过2%容差");
                return;
            }
            if ("small-content.mp4".equals(fixture)) {
                if (contentTop < .08f || contentBottom > .93f || textureWidth < 750) {
                    stop("failed", "实际偏小样本未保留标题并放大");
                    return;
                }
            } else if ("edges-expand.mp4".equals(fixture)) {
                if (contentBottom < 702.0 / Double.parseDouble(encoded[1]) || contentTop > .15f) {
                    stop("failed", "新增边缘字幕未扩大保留范围");
                    return;
                }
            } else if ("captioned-small.mp4".equals(fixture)) {
                if (contentTop > .13f || contentBottom < .77f || textureWidth < 750) {
                    stop("failed", "现场标题和画面未完整保留并放大");
                    return;
                }
            } else if ("portrait.mp4".equals(fixture) || "dark.mp4".equals(fixture)) {
                if (trimmed) {
                    stop("failed", "正常竖片或暗场不应裁边");
                    return;
                }
                if (contentHeight < videoArea.getHeight() * 0.95
                        || contentWidth >= contentHeight) {
                    stop("failed", "竖图未填满至少95%可用高度或方向不符");
                    return;
                }
            } else if (contentWidth < videoArea.getWidth() * 0.95
                    || contentWidth <= contentHeight) {
                stop("failed", "转正横图未填满至少95%可用宽度或方向不符");
                return;
            }
        }
        if (!record("verified")) return;
        releasePlayback();
        passed++;
        if (passed == TOTAL) {
            running = false;
            status = "passed";
            record("completed");
        } else if (record("released")) {
            handler.post(this::startStep);
        }
    }

    private void capture() {
        if (playback == null) return;
        rendered = Long.parseLong(playback.frameStats().substring("rendered=".length()));
        TextureView texture = (TextureView) playback.view.getChildAt(0);
        textureWidth = texture.getWidth();
        textureHeight = texture.getHeight();
        displayInfo = playback.displayInfo();
        String[] dimensions = displayInfo.split(" ");
        contentTop = Float.parseFloat(dimensions[5].substring("contentTop=".length()));
        contentBottom = Float.parseFloat(dimensions[6].substring("contentBottom=".length()));
        displayBounds.set(0, textureHeight * contentTop, textureWidth, textureHeight * contentBottom);
        texture.getMatrix().mapRect(displayBounds);
        displayBounds.offset(texture.getLeft(), texture.getTop());
        textureRotation = texture.getRotation();
    }

    private void releasePlayback() {
        if (playback == null) return;
        capture();
        playback.release();
        videoArea.removeView(playback.view);
        playback = null;
        released++;
    }

    private void stop(String result, String reason) {
        running = false;
        status = result;
        error = reason;
        handler.removeCallbacksAndMessages(null);
        releasePlayback();
        record(result);
    }

    private boolean record(String event) {
        capture();
        try {
            JSONObject sample = new JSONObject();
            sample.put("event", event);
            sample.put("step", step);
            sample.put("fixture", fixture);
            sample.put("elapsed_ms", step == 0 ? 0 : SystemClock.elapsedRealtime() - stepStarted);
            sample.put("ready_ms", readyMs);
            sample.put("first_frame_ms", firstFrameMs);
            sample.put("first_rendered", firstFrames);
            sample.put("middle_rendered", middleFrames);
            sample.put("rendered", rendered);
            sample.put("texture_width", textureWidth);
            sample.put("texture_height", textureHeight);
            sample.put("rotation_degrees", rotationDegrees);
            sample.put("texture_rotation", textureRotation);
            sample.put("display_left", displayBounds.left);
            sample.put("display_top", displayBounds.top);
            sample.put("display_right", displayBounds.right);
            sample.put("display_bottom", displayBounds.bottom);
            sample.put("display_width", displayBounds.width());
            sample.put("display_height", displayBounds.height());
            sample.put("display_info", displayInfo);
            if (playback != null) {
                sample.put("position_ms", playback.getCurrentPosition());
                sample.put("duration_ms", playback.getDuration());
                sample.put("playing", playback.isPlaying());
            }
            events.put(sample);
            JSONObject result = new JSONObject();
            result.put("status", status);
            result.put("error", error);
            result.put("target", TOTAL);
            result.put("passed", passed);
            result.put("created", created);
            result.put("released", released);
            result.put("density_dpi", getResources().getDisplayMetrics().densityDpi);
            result.put("video_area_width", videoArea.getWidth());
            result.put("video_area_height", videoArea.getHeight());
            result.put("sidebar_width", statusView.getWidth());
            result.put("events", events);
            try (FileOutputStream output = new FileOutputStream(new File(directory, "results.json"))) {
                output.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | JSONException e) {
            running = false;
            status = "failed";
            error = "无法保存检查结果：" + e.getClass().getSimpleName();
            handler.removeCallbacksAndMessages(null);
            releasePlayback();
            Log.e(TAG, error);
            updateStatus();
            return false;
        }
        Log.i(TAG, "event=" + event + " step=" + step + "/" + TOTAL
                + " passed=" + passed + " created=" + created + " released=" + released
                + " fixture=" + fixture + " rendered=" + rendered
                + " texture=" + textureWidth + "x" + textureHeight
                + " bounds=" + displayBounds + " display=" + displayBounds.width() + "x" + displayBounds.height()
                + " texture_rotation=" + textureRotation + " " + displayInfo
                + (error.isEmpty() ? "" : " error=" + error));
        updateStatus();
        return true;
    }

    private void updateStatus() {
        String label = running ? "检查中" : "passed".equals(status) ? "检查完成"
                : "stopped".equals(status) ? "检查已停止" : "检查失败";
        statusView.setText("离线播放检查\n不会连接Instagram\n\n" + label
                + "\n通过 " + passed + " / " + TOTAL + "\n" + fixture
                + "\n渲染 " + rendered + " 帧\n画面 " + Math.round(displayBounds.width())
                + " × " + Math.round(displayBounds.height()) + "\n旋转 " + textureRotation
                + (error.isEmpty() ? "" : "\n\n" + error));
    }

    @Override
    protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        if (running) stop("stopped", "页面离开前台，检查已停止");
        else releasePlayback();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releasePlayback();
        super.onDestroy();
    }
}
