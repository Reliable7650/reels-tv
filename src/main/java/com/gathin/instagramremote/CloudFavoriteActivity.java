package com.gathin.instagramremote;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CloudFavoriteActivity extends Activity {
    private static final String CLOUD_URL = "https://pan.xunlei.com/?path=%2FReels%20TV";
    private static final String DESKTOP_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private WebView web;
    private TextView status;
    private String reelUrl, source, userAgent;
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final long MIN_FREE_BYTES = 256L * 1024 * 1024;
    private File media;
    private boolean destroyed, submitted, saved, chooserArmed, verifying, pageLoaded, failed, checking, folderVerified;
    private int pageGeneration;
    private long uploadDeadline;
    private String script, folderId = "";

    @SuppressWarnings("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        reelUrl = getIntent().getStringExtra("reel_url");
        source = getIntent().getStringExtra("media_source");
        userAgent = getIntent().getStringExtra("media_user_agent");
        if (!validReel(reelUrl) || !validMedia(source)) { finish(); return; }
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF090F17);
        setContentView(page);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(20, 6, 20, 6);
        page.addView(bar, new LinearLayout.LayoutParams(-1, 64));
        TextView back = new TextView(this);
        back.setText(R.string.cloud_back);
        back.setTextColor(0xFFFFFFFF);
        back.setTextSize(18);
        back.setFocusable(true);
        back.setPadding(18, 12, 18, 12);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        status = new TextView(this);
        status.setText(R.string.cloud_preparing);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(18);
        status.setPadding(24, 0, 12, 0);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        web = new WebView(this);
        page.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(DESKTOP_AGENT);
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.USER_AGENT_METADATA)) {
            androidx.webkit.WebSettingsCompat.setUserAgentMetadata(settings,
                    new androidx.webkit.UserAgentMetadata.Builder().setMobile(false).setPlatform("Linux").build());
        }
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                                       FileChooserParams params) {
                if (chooserArmed && folderVerified && !submitted && media != null && view.getUrl() != null
                        && "pan.xunlei.com".equals(Uri.parse(view.getUrl()).getHost())
                        && "/Reels TV".equals(Uri.parse(view.getUrl()).getQueryParameter("path"))) {
                    submitted = true;
                    chooserArmed = false;
                    uploadDeadline = SystemClock.elapsedRealtime() + 10 * 60_000;
                    Uri uri = new Uri.Builder().scheme("content").authority(getPackageName() + ".cloud-favorite")
                            .appendPath(media.getParentFile().getName()).appendPath(media.getName()).build();
                    callback.onReceiveValue(new Uri[]{uri});
                    status.setText(R.string.cloud_uploading);
                } else callback.onReceiveValue(null);
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                return !"https".equals(uri.getScheme()) || host == null
                        || !(host.equals("xunlei.com") || host.endsWith(".xunlei.com"));
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) { pageLoaded = false; pageGeneration++; }
            @Override public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                CookieManager.getInstance().flush();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) status.setText(R.string.cloud_page_error);
            }
        });
        try (InputStream input = getAssets().open("cloud-favorite.js")) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            script = output.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        downloads.execute(() -> {
            try {
                File file = prepareMedia();
                handler.post(() -> {
                    if (!destroyed) { media = file; handler.post(checkPage); }
                    else { file.delete(); file.getParentFile().delete(); }
                });
            } catch (Exception failure) {
                handler.post(() -> { if (!destroyed) { failed = true; status.setText(R.string.cloud_download_failed); } });
            }
        });
        web.loadUrl("https://pan.xunlei.com/?path=%2F");
        handler.post(checkPage);
        back.requestFocus();
    }

    private final Runnable checkPage = new Runnable() {
        @Override public void run() {
            if (destroyed || saved || failed || checking) return;
            String host = Uri.parse(web.getUrl() == null ? "" : web.getUrl()).getHost();
            if (!"pan.xunlei.com".equals(host)) {
                status.setText(R.string.cloud_login);
                handler.postDelayed(this, 1500);
                return;
            }
            chooserArmed = media != null && !submitted;
            String code = "(" + script + ")(" + JSONObject.quote(media == null ? "" : media.getName())
                    + "," + submitted + "," + folderVerified + "," + verifying + "," + JSONObject.quote(folderId) + ")";
            int evaluatedGeneration = pageGeneration;
            checking = true;
            web.evaluateJavascript(code, result -> {
                checking = false;
                if (destroyed || saved || failed) return;
                if (pageGeneration != evaluatedGeneration) { handler.postDelayed(this, 1500); return; }
                try {
                    Object decoded = new JSONTokener(result).nextValue();
                    JSONObject state = decoded instanceof JSONObject ? (JSONObject) decoded : new JSONObject();
                    String value = state.optString("state", "loading");
                    if (value.equals("uploaded") && pageLoaded && !verifying) {
                        verifying = true;
                        pageLoaded = false;
                        status.setText(R.string.cloud_verifying);
                        web.reload();
                    } else if (value.equals("saved") && pageLoaded && verifying) {
                        saved = true;
                        status.setText(R.string.cloud_saved);
                        media.delete();
                        media.getParentFile().delete();
                        setResult(RESULT_OK);
                        finish();
                        return;
                    }
                    if (value.equals("open-folder")) {
                        folderId = state.getString("folderId");
                        folderVerified = !folderId.isEmpty();
                    }
                    if (value.equals("touch") || value.equals("open-folder")) {
                        float scale = web.getWidth() / (float) state.getDouble("viewport");
                        float x = (float) state.getDouble("x") * scale;
                        float y = (float) state.getDouble("y") * scale;
                        long now = SystemClock.uptimeMillis();
                        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, 0, x, y, 0);
                        android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 50, 1, x, y, 0);
                        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                        web.dispatchTouchEvent(down);
                        web.dispatchTouchEvent(up);
                        down.recycle();
                        up.recycle();
                    }
                    if (value.equals("login")) status.setText(R.string.cloud_login);
                    else if (value.equals("root")) web.loadUrl("https://pan.xunlei.com/?path=%2F");
                    else if (value.equals("folder")) web.loadUrl(CLOUD_URL);
                    else if (value.equals("missing-folder")) {
                        status.setText(R.string.cloud_missing_folder);
                        return;
                    } else if (value.equals("failed")) {
                        status.setText(R.string.cloud_upload_failed);
                        return;
                    }
                    if (submitted && SystemClock.elapsedRealtime() > uploadDeadline) {
                        status.setText(R.string.cloud_timeout);
                        return;
                    }
                } catch (Exception failure) {
                    android.util.Log.w("TvHome.CloudFavorite", "Unable to read upload state", failure);
                }
                handler.removeCallbacks(this);
                handler.postDelayed(this, 1500);
            });
        }
    };

    private static boolean validReel(String value) {
        try {
            java.net.URI uri = new java.net.URI(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && ("instagram.com".equalsIgnoreCase(host) || "www.instagram.com".equalsIgnoreCase(host))
                    && uri.getRawPath() != null && uri.getRawPath().matches("/reel/[A-Za-z0-9_-]+/?");
        } catch (Exception invalid) { return false; }
    }

    private static boolean validMedia(String value) {
        try {
            java.net.URI uri = new java.net.URI(value);
            String host = uri.getHost();
            if (host == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("cdninstagram.com") || host.endsWith(".cdninstagram.com")
                    || host.equals("fbcdn.net") || host.endsWith(".fbcdn.net");
        } catch (Exception invalid) { return false; }
    }

    private File prepareMedia() throws Exception {
        File directory = new File(new File(getCacheDir(), "cloud-favorites"), java.util.UUID.randomUUID().toString());
        if (!directory.mkdirs()) throw new java.io.IOException("Cannot create cache directory");
        String base = "Reel_" + directory.getName();
        File partial = new File(directory, base + ".part");
        boolean complete = false;
        HttpURLConnection connection = null;
        try {
            String target = source;
            for (int redirect = 0; redirect < 6; redirect++) {
                if (!validMedia(target)) throw new java.io.IOException("Invalid media URL");
                connection = (HttpURLConnection) new URL(target).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(30_000);
                connection.setRequestProperty("Referer", reelUrl);
                if (userAgent != null && !userAgent.isEmpty()) connection.setRequestProperty("User-Agent", userAgent);
                int response = connection.getResponseCode();
                if (response >= 300 && response < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new java.io.IOException("Invalid redirect");
                    target = new URL(new URL(target), location).toString();
                    connection.disconnect();
                    connection = null;
                    continue;
                }
                if (response != 200) throw new java.io.IOException("Download failed: " + response);
                break;
            }
            if (connection == null) throw new java.io.IOException("Too many redirects");
            String mime = connection.getContentType();
            mime = mime == null ? "" : mime.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (mime.contains("text") || mime.contains("json") || mime.contains("mpegurl"))
                throw new java.io.IOException("Not a media file");
            String extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (extension == null || !(mime.startsWith("audio/") || mime.startsWith("video/"))) extension = "mp4";
            long expected = connection.getContentLengthLong();
            if (expected > MAX_BYTES || expected == 0) throw new java.io.IOException("Invalid file size");
            if (expected > 0 && directory.getUsableSpace() < expected + MIN_FREE_BYTES)
                throw new java.io.IOException("Insufficient space");
            long total = 0;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("Canceled");
                    total += count;
                    if (total > MAX_BYTES || directory.getUsableSpace() < count + MIN_FREE_BYTES)
                        throw new java.io.IOException("File too large or insufficient space");
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (total == 0 || (expected >= 0 && total != expected)) throw new java.io.IOException("Incomplete download");
            MediaMetadataRetriever metadata = new MediaMetadataRetriever();
            try {
                metadata.setDataSource(partial.getPath());
                String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String video = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
                if (duration == null || Long.parseLong(duration) <= 0 || !"yes".equals(video))
                    throw new java.io.IOException("Not a playable video");
            } finally { metadata.release(); }
            File result = new File(directory, base + "." + extension);
            if (!partial.renameTo(result)) throw new java.io.IOException("Cannot save cache file");
            complete = true;
            return result;
        } finally {
            if (connection != null) connection.disconnect();
            partial.delete();
            if (!complete) directory.delete();
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(checkPage);
        downloads.shutdownNow();
        if (web != null) { web.stopLoading(); web.destroy(); }
        if (media != null) { media.delete(); media.getParentFile().delete(); }
        super.onDestroy();
    }
}
