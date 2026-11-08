package com.gathin.instagramremote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

public final class InstagramRemoteService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Rect bounds = new Rect();
    private final Runnable hide = this::hidePointer;
    private final Runnable move = new Runnable() {
        @Override public void run() {
            if (!moving) return;
            if (!instagramReady()) {
                stopMoving();
                hidePointer();
                positioned = false;
                return;
            }
            movePointer(24);
            showPointer();
            handler.postDelayed(this, 32);
        }
    };
    private WindowManager windows;
    private View pointer;
    private WindowManager.LayoutParams layout;
    private float x, y;
    private boolean positioned, attached, gesturing, explained, moving, filteringKeys;
    private final SparseBooleanArray heldKeys = new SparseBooleanArray();

    @Override protected void onServiceConnected() {
        windows = getSystemService(WindowManager.class);
        layout = new WindowManager.LayoutParams(48, 48,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        pointer = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(7);
                paint.setColor(Color.BLACK);
                canvas.drawCircle(24, 24, 12, paint);
                paint.setStrokeWidth(3);
                paint.setColor(Color.WHITE);
                canvas.drawCircle(24, 24, 12, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(24, 24, 3, paint);
            }
        };
        filteringKeys = (getServiceInfo().flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0;
        filterKeys(instagramReady());
    }

    private boolean instagramReady() {
        boolean keyboard = false;
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) keyboard = true;
            window.recycle();
        }
        if (keyboard) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean ready = "com.instagram.android".equals(String.valueOf(root.getPackageName()));
        if (ready) root.getBoundsInScreen(bounds);
        root.recycle();
        return ready && !bounds.isEmpty();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        boolean ready = instagramReady();
        filterKeys(ready);
        if (!ready) {
            stopMoving();
            hidePointer();
            positioned = false;
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean direction = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean click = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
        if (!direction && !click) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_BACK
                    || key == KeyEvent.KEYCODE_HOME || key == KeyEvent.KEYCODE_SETTINGS)) stopMoving();
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean handled = heldKeys.get(key);
            heldKeys.delete(key);
            if (!directionHeld()) stopMoving();
            return handled;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (!instagramReady()) {
            stopMoving();
            hidePointer();
            return heldKeys.get(key);
        }
        if (heldKeys.get(key)) return true;
        heldKeys.put(key, true);
        if (!positioned) {
            x = bounds.exactCenterX();
            y = bounds.exactCenterY();
            positioned = true;
        }
        if (!explained) {
            Toast.makeText(this, getString(R.string.msg_remote_hint), Toast.LENGTH_LONG).show();
            explained = true;
        }
        if (direction) {
            movePointer(14);
            if (!moving) {
                moving = true;
                handler.postDelayed(move, 250);
            }
        }
        x = Math.max(bounds.left + 6, Math.min(bounds.right - 6, x));
        y = Math.max(bounds.top + 6, Math.min(bounds.bottom - 6, y));
        if (!direction && event.getRepeatCount() == 0) {
            Path path = new Path();
            path.moveTo(x, y);
            if (!gesturing) {
                gesturing = dispatchGesture(new GestureDescription.Builder().addStroke(
                        new GestureDescription.StrokeDescription(path, 0, 60)).build(),
                        new GestureResultCallback() {
                            @Override public void onCompleted(GestureDescription gesture) { gesturing = false; }
                            @Override public void onCancelled(GestureDescription gesture) { gesturing = false; }
                        }, handler);
            }
        }
        showPointer();
        return true;
    }

    private boolean directionHeld() {
        return heldKeys.get(KeyEvent.KEYCODE_DPAD_LEFT) || heldKeys.get(KeyEvent.KEYCODE_DPAD_RIGHT)
                || heldKeys.get(KeyEvent.KEYCODE_DPAD_UP) || heldKeys.get(KeyEvent.KEYCODE_DPAD_DOWN);
    }

    private void filterKeys(boolean enabled) {
        if (filteringKeys == enabled) return;
        AccessibilityServiceInfo info = getServiceInfo();
        if (enabled) info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        else {
            info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            stopMoving();
            heldKeys.clear();
        }
        filteringKeys = enabled;
        setServiceInfo(info);
    }

    private void movePointer(float step) {
        if (heldKeys.get(KeyEvent.KEYCODE_DPAD_LEFT)) x -= step;
        if (heldKeys.get(KeyEvent.KEYCODE_DPAD_RIGHT)) x += step;
        if (heldKeys.get(KeyEvent.KEYCODE_DPAD_UP)) y -= step;
        if (heldKeys.get(KeyEvent.KEYCODE_DPAD_DOWN)) y += step;
        x = Math.max(bounds.left + 6, Math.min(bounds.right - 6, x));
        y = Math.max(bounds.top + 6, Math.min(bounds.bottom - 6, y));
    }

    private void stopMoving() {
        moving = false;
        handler.removeCallbacks(move);
    }

    private void showPointer() {
        layout.x = Math.round(x) - 24;
        layout.y = Math.round(y) - 24;
        if (attached) windows.updateViewLayout(pointer, layout);
        else {
            windows.addView(pointer, layout);
            attached = true;
        }
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, 4000);
    }

    private void hidePointer() {
        handler.removeCallbacks(hide);
        if (attached) {
            windows.removeView(pointer);
            attached = false;
        }
    }

    @Override public void onInterrupt() {
        stopMoving();
        hidePointer();
    }

    @Override public boolean onUnbind(Intent intent) {
        stopMoving();
        hidePointer();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        stopMoving();
        hidePointer();
        super.onDestroy();
    }
}
