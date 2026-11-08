package com.gathin.instagramremote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;
import org.videolan.libvlc.interfaces.IMedia;

import java.util.ArrayList;
import java.util.Arrays;

final class ReelsPlayback {
    public interface Listener {
        void onReady();
        void onFirstFrame();
        void onError(int errorCode);
    }

    public final FrameLayout view;

    private final LibVLC libVLC;
    private final MediaPlayer player;
    private final IVLCVout videoOut;
    private final TextureView textureView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Runnable firstFrameDeadline = () -> reportError(1003);
    private final Runnable fitEdges = this::fitBlackEdges;
    private boolean playWhenReady;
    private boolean ready;
    private boolean firstFrame;
    private boolean failed;
    private boolean released;
    private long renderedFrames;
    private int videoWidth;
    private int videoHeight;
    private int visibleWidth;
    private int visibleHeight;
    private int sarNumerator = 1;
    private int sarDenominator = 1;
    private int rotationDegrees;
    private boolean autoZoom = true;
    private Bitmap edgeSample;
    private int[] edgePixels;
    private long nextEdgeSample;
    private int edgeSamples;
    private float candidateTop = 1, candidateBottom;
    private float contentTop, contentBottom = 1;
    private boolean fittedBlackEdges;

    public ReelsPlayback(Context context, String source, String userAgent, int position,
                         boolean play, Listener listener) {
        this.listener = listener;
        playWhenReady = play;
        libVLC = new LibVLC(context, new ArrayList<>(Arrays.asList(
                "--avcodec-hw=none", "--no-avcodec-dr", "--vout=android-display", "--quiet",
                "--no-video-title-show", "--no-http-forward-cookies")));
        libVLC.setUserAgent("Reels TV", userAgent);
        player = new MediaPlayer(libVLC);
        videoOut = player.getVLCVout();

        view = new FrameLayout(context);
        view.setBackgroundColor(Color.BLACK);
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        textureView = new TextureView(context);
        textureView.setFocusable(false);
        textureView.setFocusableInTouchMode(false);
        view.addView(textureView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        view.addOnLayoutChangeListener((v, left, top, right, bottom,
                                       oldLeft, oldTop, oldRight, oldBottom) -> updateVideoLayout());

        videoOut.setVideoView(textureView);
        videoOut.attachViews((out, width, height, frameWidth, frameHeight, sarNum, sarDen) -> {
            if (released || failed) return;
            videoWidth = width;
            videoHeight = height;
            visibleWidth = frameWidth > 0 ? frameWidth : width;
            visibleHeight = frameHeight > 0 ? frameHeight : height;
            sarNumerator = sarNum > 0 ? sarNum : 1;
            sarDenominator = sarDen > 0 ? sarDen : 1;
            updateVideoLayout();
        });
        // attachViews installs VLC's listener; keep forwarding its surface lifecycle.
        TextureView.SurfaceTextureListener vlcListener = textureView.getSurfaceTextureListener();
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                vlcListener.onSurfaceTextureAvailable(surface, width, height);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                vlcListener.onSurfaceTextureSizeChanged(surface, width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return vlcListener.onSurfaceTextureDestroyed(surface);
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                vlcListener.onSurfaceTextureUpdated(surface);
                if (released || failed) return;
                renderedFrames++;
                long now = SystemClock.elapsedRealtime();
                if (autoZoom && now >= nextEdgeSample) {
                    nextEdgeSample = now + 500;
                    handler.post(fitEdges);
                }
                if (!firstFrame) {
                    firstFrame = true;
                    handler.removeCallbacks(firstFrameDeadline);
                    if (!playWhenReady) player.pause();
                    listener.onFirstFrame();
                }
            }
        });

        player.setEventListener(event -> {
            if (released || failed) return;
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    if (!ready) {
                        ready = true;
                        if (position > 0) player.setTime(position);
                        listener.onReady();
                    }
                    if (!released && !failed && firstFrame && !playWhenReady && player.isPlaying()) {
                        player.pause();
                    }
                    break;
                case MediaPlayer.Event.EndReached:
                    player.stop();
                    if (playWhenReady) player.play();
                    break;
                case MediaPlayer.Event.EncounteredError:
                    reportError(event.type);
                    break;
            }
        });
        Media media = new Media(libVLC, Uri.parse(source));
        media.setHWDecoderEnabled(false, false);
        media.addOption(":codec=avcodec,none");
        media.addOption(":http-referrer=https://www.instagram.com/");
        player.setMedia(media);
        media.release();
        handler.postDelayed(firstFrameDeadline, 20_000);
        player.play();
    }

    private void updateVideoLayout() {
        if (released || failed || view.getWidth() == 0 || view.getHeight() == 0) return;
        boolean quarterTurn = rotationDegrees % 180 != 0;
        int availableWidth = quarterTurn ? view.getHeight() : view.getWidth();
        int availableHeight = quarterTurn ? view.getWidth() : view.getHeight();
        videoOut.setWindowSize(availableWidth, availableHeight);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) textureView.getLayoutParams();
        int width = ViewGroup.LayoutParams.MATCH_PARENT;
        int height = ViewGroup.LayoutParams.MATCH_PARENT;
        if (videoWidth > 0 && videoHeight > 0 && visibleWidth > 0 && visibleHeight > 0) {
            boolean trimmed = autoZoom && fittedBlackEdges && (contentTop > 0 || contentBottom < 1);
            double contentHeight = trimmed ? videoHeight * (contentBottom - contentTop) : visibleHeight;
            double aspect = (double) visibleWidth * sarNumerator / sarDenominator / contentHeight;
            double displayWidth = Math.min(availableWidth, availableHeight * aspect);
            double displayHeight = displayWidth / aspect;
            width = (int) Math.ceil(displayWidth * videoWidth / visibleWidth);
            height = (int) Math.ceil(displayHeight * videoHeight / contentHeight);
        }
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            textureView.setLayoutParams(params);
        }
        float offset = autoZoom && fittedBlackEdges && (contentTop > 0 || contentBottom < 1)
                ? (.5f - (contentTop + contentBottom) / 2) * height : 0;
        double radians = Math.toRadians(rotationDegrees);
        textureView.setTranslationX((float) (-Math.sin(radians) * offset));
        textureView.setTranslationY((float) (Math.cos(radians) * offset));
    }

    private void fitBlackEdges() {
        if (released || failed || !autoZoom || videoWidth == 0 || videoHeight == 0 || !textureView.isAvailable()) return;
        if (edgeSample == null) {
            float scale = 320f / Math.max(textureView.getWidth(), textureView.getHeight());
            int width = Math.max(1, Math.round(textureView.getWidth() * scale));
            int height = Math.max(1, Math.round(textureView.getHeight() * scale));
            edgeSample = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            edgePixels = new int[width * height];
        }
        edgeSample.eraseColor(Color.TRANSPARENT);
        try { textureView.getBitmap(edgeSample); }
        catch (IllegalStateException unavailable) { return; }
        if ((edgeSample.getPixel(0, 0) >>> 24) == 0) return;
        int width = edgeSample.getWidth(), height = edgeSample.getHeight();
        edgeSample.getPixels(edgePixels, 0, width, 0, 0, width, height);
        int top = height, bottom = 0, left = width, right = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = edgePixels[y * width + x];
                if ((color & 255) <= 8 && ((color >> 8) & 255) <= 8 && ((color >> 16) & 255) <= 8) continue;
                top = Math.min(top, y);
                bottom = Math.max(bottom, y + 1);
                left = Math.min(left, x);
                right = Math.max(right, x + 1);
            }
        }
        if (bottom <= top) { edgeSamples = 0; candidateTop = 1; candidateBottom = 0; return; }
        float foundTop = Math.max(0, (top - 2f) / height);
        float foundBottom = Math.min(1, (bottom + 2f) / height);
        if (fittedBlackEdges) {
            float expandedTop = Math.min(contentTop, foundTop);
            float expandedBottom = Math.max(contentBottom, foundBottom);
            if (expandedTop != contentTop || expandedBottom != contentBottom) {
                contentTop = expandedTop;
                contentBottom = expandedBottom;
                updateVideoLayout();
            }
            return;
        }
        if (foundTop < .08f || foundBottom > .92f || foundBottom - foundTop < .3f
                || right - left < width * .9f) {
            edgeSamples = 0;
            candidateTop = 1;
            candidateBottom = 0;
            return;
        }
        candidateTop = Math.min(candidateTop, foundTop);
        candidateBottom = Math.max(candidateBottom, foundBottom);
        if (++edgeSamples < 3) return;
        contentTop = candidateTop;
        contentBottom = candidateBottom;
        fittedBlackEdges = true;
        updateVideoLayout();
    }

    private void reportError(int errorCode) {
        if (failed || released) return;
        failed = true;
        release();
        listener.onError(errorCode);
    }

    public int getCurrentPosition() {
        return released ? 0 : (int) Math.max(0, player.getTime());
    }

    public int getDuration() {
        return released ? 0 : (int) Math.max(0, player.getLength());
    }

    public boolean isPlaying() {
        return !released && !failed && player.isPlaying();
    }

    public void start() {
        if (released || failed) return;
        playWhenReady = true;
        player.play();
    }

    public void setRotationDegrees(int degrees) {
        rotationDegrees = degrees;
        textureView.setRotation(degrees);
        updateVideoLayout();
    }

    public int rotateLeft() {
        setRotationDegrees((rotationDegrees + 270) % 360);
        return rotationDegrees;
    }

    public boolean isAutoZoom() {
        return autoZoom;
    }

    public void setAutoZoom(boolean enabled) {
        autoZoom = enabled;
        if (!enabled) handler.removeCallbacks(fitEdges);
        updateVideoLayout();
    }

    public String displayInfo() {
        IMedia.VideoTrack track = released ? null : player.getCurrentVideoTrack();
        return "encoded=" + videoWidth + "x" + videoHeight
                + " visible=" + visibleWidth + "x" + visibleHeight
                + " pixelAspect=" + sarNumerator + "/" + sarDenominator
                + " rotation=" + rotationDegrees
                + " sourceOrientation=" + (track == null ? "unknown" : track.orientation)
                + " contentTop=" + (autoZoom ? contentTop : 0) + " contentBottom=" + (autoZoom ? contentBottom : 1)
                + " autoZoom=" + autoZoom;
    }

    public void pause() {
        if (released || failed) return;
        playWhenReady = false;
        if (firstFrame && player.isPlaying()) player.pause();
    }

    public void release() {
        if (released) return;
        released = true;
        handler.removeCallbacks(firstFrameDeadline);
        handler.removeCallbacks(fitEdges);
        player.setEventListener(null);
        player.stop();
        videoOut.detachViews();
        textureView.setSurfaceTextureListener(null);
        if (edgeSample != null) edgeSample.recycle();
        player.release();
        libVLC.release();
    }

    public String decoderName() {
        return ready ? "libvlc/avcodec (software)" : "";
    }

    public boolean hasFirstFrame() {
        return firstFrame;
    }

    public String frameStats() {
        return "rendered=" + renderedFrames;
    }
}
