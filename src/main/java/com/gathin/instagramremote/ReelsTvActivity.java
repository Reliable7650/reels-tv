package com.gathin.instagramremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

public final class ReelsTvActivity extends Activity {
    private static final String HOME = "https://www.instagram.com/reels/";
    private static final String PAUSE_MEDIA = "window.__reelsTvPaused=true;document.querySelectorAll('video,audio').forEach(function(v){v.pause();});";
    private static final String EMBED_PAUSE = "(function(){if(window.__reelsTvEmbedPause)return;"
            + "window.__reelsTvEmbedPause=true;function pause(v){if(v&&typeof v.pause==='function'){v.muted=true;v.pause();}}"
            + "document.addEventListener('play',function(e){pause(e.target);},true);"
            + "document.querySelectorAll('video,audio').forEach(pause);})();";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SparseBooleanArray consumedKeys = new SparseBooleanArray();
    private final SparseBooleanArray heldDirections = new SparseBooleanArray();
    private final Button[] buttons = new Button[7];
    private final String[] actionRequests = new String[2];
    private final String[] actionReels = new String[2];
    private final Runnable[] actionTimeouts = new Runnable[2];
    private long nextActionRequest;
    private volatile long actionEpoch;
    private boolean likeStateLocked;
    private boolean likeControlAvailable, menuControlAvailable;
    private String controlLabels = "[]";
    private String actionHistoryURL;
    private LinearLayout screen;
    private FrameLayout content, nativeLayer;
    private ReelsWebView web;
    private WebView resolver;
    private ReelsPlayback nativeVideo;
    private TextView nativeMessage;
    private TextView status;
    private Button selectedButton;
    private String script, sourceScript, currentURL, nativeReel, cachedReel, cachedSource, publicUserAgent;
    private Runnable lookup, lookupTimeout, sourceWait;
    private int generation, cachedPosition;
    private boolean foreground, destroyed, pointerMode, moving, pageReady, nativePrepared, leaving, shouldPlay = true;
    private boolean awaitingLogin, authenticationPage;
    private volatile boolean pageFailed;
    private boolean mediaFailed;
    private volatile boolean accessBlocked;
    private long retryAfter, lastMediaRetry;
    private String requestedPage;
    private AlertDialog pageDialog;
    private final android.util.LruCache<String, String> reelSources = new android.util.LruCache<>(8);
    private final Runnable move = new Runnable() {
        @Override public void run() {
            if (!moving) return;
            if (!foreground || !pointerMode || !web.hasFocus() || keyboardVisible()) {
                stopMoving();
                return;
            }
            movePointer(dp(18));
            if (moving) handler.postDelayed(this, 32);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        screen = new LinearLayout(this);
        screen.setBackgroundColor(0xFF080B10);
        screen.setOrientation(LinearLayout.HORIZONTAL);
        setContentView(screen);

        content = new FrameLayout(this);
        content.setBackgroundColor(0xFF000000);
        screen.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        web = new ReelsWebView();
        web.setBackgroundColor(0xFF000000);
        web.setId(View.generateViewId());
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        content.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.setOnFocusChangeListener((view, focused) -> {
            if (!focused) stopMoving();
            web.invalidate();
        });
        nativeLayer = new FrameLayout(this);
        nativeLayer.setBackgroundColor(0xFF000000);
        nativeLayer.setVisibility(View.GONE);
        content.addView(nativeLayer, new FrameLayout.LayoutParams(-1, -1));
        nativeMessage = new TextView(this);
        nativeMessage.setTextColor(0xFFD7DFE8);
        nativeMessage.setTextSize(20);
        nativeMessage.setGravity(Gravity.CENTER);
        nativeMessage.setPadding(dp(24), dp(24), dp(24), dp(24));
        nativeLayer.addView(nativeMessage, new FrameLayout.LayoutParams(-1, -1));

        ScrollView sidebarScroll = new ScrollView(this);
        sidebarScroll.setFillViewport(true);
        screen.addView(sidebarScroll, new LinearLayout.LayoutParams(dp(190), -1));
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setGravity(Gravity.CENTER);
        sidebar.setPadding(dp(12), dp(12), dp(12), dp(12));
        sidebarScroll.addView(sidebar, new ScrollView.LayoutParams(-1, -2));
        String[] labels = {"上一条", "下一条", "点赞", "收藏到迅雷", "向左旋转", "不感兴趣", "画面：自动放大"};
        for (int i = 0; i < buttons.length; i++) {
            Button button = new RailButton(this, i);
            button.setId(View.generateViewId());
            button.setText(labels[i]);
            button.setTextSize(17);
            button.setTextColor(0xFFFFFFFF);
            button.setAllCaps(false);
            button.setPadding(dp(8), 0, dp(8), 0);
            button.setFocusableInTouchMode(true);
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(14));
            background.setColor(0x88434B57);
            button.setBackground(background);
            button.setOnFocusChangeListener((view, focused) -> {
                background.setColor(focused ? 0xCC52667D : 0x88434B57);
                background.setStroke(dp(2), focused ? 0xFFFFFFFF : 0x00000000);
                if (focused) {
                    selectedButton = button;
                    pointerMode = false;
                    stopMoving();
                    web.invalidate();
                    resumeNative();
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(54));
            params.bottomMargin = dp(12);
            sidebar.addView(button, params);
            buttons[i] = button;
        }
        buttons[3].setVisibility(BuildConfig.XUNLEI ? View.VISIBLE : View.GONE);
        buttons[5].setVisibility(View.GONE);
        for (int i = 0; i < buttons.length; i++) {
            int up = i - 1;
            while (up >= 0 && buttons[up].getVisibility() != View.VISIBLE) up--;
            int down = i + 1;
            while (down < buttons.length && buttons[down].getVisibility() != View.VISIBLE) down++;
            buttons[i].setNextFocusUpId(buttons[up >= 0 ? up : i].getId());
            buttons[i].setNextFocusDownId(buttons[down < buttons.length ? down : i].getId());
            buttons[i].setNextFocusRightId(buttons[i].getId());
            buttons[i].setNextFocusLeftId(web.getId());
        }
        buttons[0].setOnClickListener(view -> command("previous"));
        buttons[1].setOnClickListener(view -> command("next"));
        buttons[2].setOnClickListener(view -> startAction(0));
        buttons[3].setOnClickListener(view -> favorite());
        buttons[4].setOnClickListener(view -> rotateVideo());
        buttons[5].setOnClickListener(view -> startAction(1));
        buttons[6].setOnClickListener(view -> toggleAutoZoom());
        buttons[6].setEnabled(false);
        buttons[6].setAlpha(.45f);
        buttons[2].setEnabled(false);
        buttons[2].setAlpha(.45f);
        buttons[5].setEnabled(false);
        buttons[5].setAlpha(.45f);
        buttons[4].setEnabled(false);
        buttons[4].setAlpha(.45f);
        buttons[3].setEnabled(false);
        buttons[3].setAlpha(.45f);
        status = new TextView(this);
        status.setTextColor(0xFFD7DFE8);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(16), 0, 0);
        sidebar.addView(status, new LinearLayout.LayoutParams(-1, -2));
        screen.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (keyboardVisible()) stopMoving();
        });

        try (InputStream input = getAssets().open("reels-tv.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            script = "(function(){if(window!==window.top||location.protocol!=='https:'||"
                    + "(location.hostname!=='www.instagram.com'&&location.hostname!=='instagram.com')||"
                    + "!/^\\/(?:reels\\/?|reels?\\/[A-Za-z0-9_-]+\\/?|reel\\/[A-Za-z0-9_-]+\\/embed\\/)$/.test(location.pathname))return;"
                    + "if(!window.__reelsTvPauseGuard){window.__reelsTvPauseGuard=true;"
                    + "document.addEventListener('play',function(e){"
                    + "if(/^\\/(?:reels\\/?|reels?\\/[A-Za-z0-9_-]+\\/?|reel\\/[A-Za-z0-9_-]+\\/embed\\/)$/.test(location.pathname)"
                    + "&&(window.__reelsTvPaused||document.hidden)&&typeof e.target.pause==='function')e.target.pause();},true);}"
                    + "function fit(){if(document.getElementById('reels-tv-fit'))return;"
                    + "var s=document.createElement('style');s.id='reels-tv-fit';"
                    + "s.textContent='video{object-fit:contain!important}';"
                    + "(document.head||document.documentElement).appendChild(s);}"
                    + "if(document.documentElement)fit();else document.addEventListener('DOMContentLoaded',fit,{once:true});\n"
                    + new String(output.toByteArray(), StandardCharsets.UTF_8) + "\n})();";
        } catch (IOException failure) {
            Toast.makeText(this, getString(R.string.msg_script_missing), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try (InputStream input = getAssets().open("reels-video-source.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            sourceScript = new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            Toast.makeText(this, getString(R.string.msg_player_missing), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        publicUserAgent = WebSettings.getDefaultUserAgent(this);
        configureWeb();
        String initial = getIntent().getDataString();
        if (initial != null && !officialUrl(initial)) {
            Toast.makeText(this, getString(R.string.msg_https_only), Toast.LENGTH_LONG).show();
            initial = null;
        }
        requestedPage = initial == null ? HOME : initial;
        accessBlocked = getPreferences(MODE_PRIVATE).getBoolean("access_paused", false)
                || getPreferences(MODE_PRIVATE).getBoolean("authentication_pending", false);
        retryAfter = getPreferences(MODE_PRIVATE).getLong("retry_after", 0);
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent().getBooleanExtra("offline_failure_check", false)) {
            accessBlocked = true;
            retryAfter = System.currentTimeMillis() + 300000;
            handler.post(() -> showPageError(getString(R.string.msg_offline_test)));
        } else if (!accessBlocked) web.loadUrl(requestedPage);
        else handler.post(() -> showPageError(pausedMessage()));
        selectedButton = buttons[1];
        selectedButton.requestFocus();
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWeb() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUserAgentString(settings.getUserAgentString()
                .replaceFirst("\\([^)]*\\)", "(X11; Linux x86_64)")
                .replace(" Version/4.0", "").replace(" Mobile", ""));
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            WebSettingsCompat.setUserAgentMetadata(settings,
                    new UserAgentMetadata.Builder().setMobile(false).setPlatform("Linux").build());
        }
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new ReelsState(), "ReelsTv");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, script,
                    new HashSet<>(Arrays.asList("https://www.instagram.com", "https://instagram.com")));
        }
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !officialUrl(request.getUrl().toString());
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (accessBlocked || pageFailed || !"https".equalsIgnoreCase(request.getUrl().getScheme())
                        || request.isForMainFrame() && !officialUrl(request.getUrl().toString())) {
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                cancelActions();
                actionHistoryURL = url;
                if (accessBlocked || pageFailed) { view.stopLoading(); return; }
                if (pageDialog != null) pageDialog.dismiss();
                pageDialog = null;
                requestedPage = url;
                pageFailed = false;
                pageReady = false;
                clearReel();
                stopMoving();
                if (!officialUrl(url)) {
                    view.stopLoading();
                    status.setText(getString(R.string.msg_unofficial_blocked));
                    return;
                }
                status.setText(getString(R.string.msg_open_instagram));
                if (authenticationUrl(url)) {
                    authenticationPage = true;
                    showLogin();
                    if (verificationPage(url)) status.setText(getString(R.string.msg_verify_official));
                } else if (reelsPage(url) && awaitingLogin) {
                    authenticationPage = false;
                    awaitingLogin = false;
                    pointerMode = false;
                    selectedButton = buttons[1];
                }
                if (!pointerMode && reelsPage(url)) {
                    nativeLayer.setVisibility(View.VISIBLE);
                    showNativeMessage(getString(R.string.msg_open_video));
                }
            }

            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                boolean pendingRemoval = actionRequests[1] != null && !reload && reelsPage(url);
                if ((reload || !java.util.Objects.equals(actionHistoryURL, url)) && !pendingRemoval) cancelActions();
                actionHistoryURL = url;
                if (destroyed || accessBlocked || pageFailed || !officialUrl(url)) return;
                if (authenticationUrl(url)) {
                    authenticationPage = true;
                    showLogin();
                    if (verificationPage(url)) status.setText(getString(R.string.msg_verify_official));
                } else if (authenticationPage && reelsPage(url)) {
                    authenticationPage = false;
                    awaitingLogin = false;
                    pointerMode = false;
                    selectedButton = buttons[1];
                    requestedPage = url;
                    if (foreground) {
                        web.evaluateJavascript("window.__reelsTvPaused=false;", null);
                        web.evaluateJavascript(script, null);
                        web.evaluateJavascript("if(window.TVReels)window.TVReels.state();", null);
                    }
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (destroyed || pageFailed || !officialUrl(url) || !url.equals(view.getUrl())) return;
                pageReady = true;
                updateFavorite();
                CookieManager.getInstance().flush();
                if (currentURL == null) status.setText(awaitingLogin
                        ? getString(R.string.msg_login_official) : getString(R.string.msg_wait_video));
                if (foreground && !accessBlocked && !awaitingLogin && reelsPage(url)) view.evaluateJavascript(script, null);
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                showPageError(getString(R.string.msg_page_network));
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                    WebResourceResponse response) {
                if (accessLimited(request.getUrl().toString(), response.getStatusCode())) {
                    blockAccess(response);
                    return;
                }
                if (!request.isForMainFrame()) return;
                showPageError(getString(R.string.msg_page_unavailable));
            }
        });
    }

    private static boolean officialUrl(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getEncodedUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && ("www.instagram.com".equalsIgnoreCase(uri.getHost())
                || "instagram.com".equalsIgnoreCase(uri.getHost()));
    }

    private static boolean reelUrl(String url) {
        return url != null && url.matches("https://www\\.instagram\\.com/reel/[A-Za-z0-9_-]+/");
    }

    private static boolean accessLimited(String url, int status) {
        if (status != 429 || url == null) return false;
        Uri uri = Uri.parse(url);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getEncodedUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && ("instagram.com".equalsIgnoreCase(uri.getHost())
                || "www.instagram.com".equalsIgnoreCase(uri.getHost())
                || "i.instagram.com".equalsIgnoreCase(uri.getHost()));
    }

    private static boolean verificationPage(String url) {
        if (!officialUrl(url)) return false;
        String path = Uri.parse(url).getPath();
        return path != null && (path.startsWith("/challenge") || path.startsWith("/checkpoint")
                || path.startsWith("/accounts/confirm_email") || path.startsWith("/accounts/suspended"));
    }

    private static boolean authenticationUrl(String url) {
        return officialUrl(url) && (Uri.parse(url).getPath().startsWith("/accounts/login") || verificationPage(url));
    }

    private static boolean reelsPage(String url) {
        if (!officialUrl(url)) return false;
        String path = Uri.parse(url).getPath();
        return path != null && path.matches("/(?:reels/?|reels?/[A-Za-z0-9_-]+/?|reel/[A-Za-z0-9_-]+/embed/)");
    }

    public final class ReelsState {
        @JavascriptInterface public void source(String reel, String source) {
            handler.post(() -> {
                if (destroyed || !foreground || accessBlocked || awaitingLogin || !officialUrl(web.getUrl()) || !reelUrl(reel)
                        || !nativeCurrent(generation, reel) || resolver == null && sourceWait == null || nativeVideo != null
                        || source == null || !mediaUrl(source)) return;
                startNativeVideo(source, generation, reel);
            });
        }

        @JavascriptInterface public void loginRequired() {
            handler.post(() -> {
                if (destroyed || !foreground || accessBlocked || pageFailed || !officialUrl(web.getUrl()) || awaitingLogin) return;
                awaitingLogin = true;
                cancelActions();
                getPreferences(MODE_PRIVATE).edit().putBoolean("authentication_pending", true).apply();
                web.stopLoading();
                web.pauseTimers();
                stopNative();
                pointerMode = false;
                nativeLayer.setVisibility(View.VISIBLE);
                showNativeMessage(getString(R.string.msg_login_required));
                pageDialog = new AlertDialog.Builder(ReelsTvActivity.this)
                        .setTitle(getString(R.string.dialog_login_title))
                        .setMessage(getString(R.string.dialog_login_body))
                        .setPositiveButton(getString(R.string.action_open_official), (dialog, which) -> {
                            web.resumeTimers();
                            web.loadUrl("https://www.instagram.com/accounts/login/?next=%2Freels%2F");
                        })
                        .setNegativeButton(getString(R.string.action_home), (dialog, which) -> finish())
                        .setOnCancelListener(dialog -> finish())
                        .show();
            });
        }

        @JavascriptInterface public void state(String url, boolean liked, boolean video, boolean playing) {
            long epoch = actionEpoch;
            handler.post(() -> {
                if (epoch != actionEpoch || destroyed || !foreground || accessBlocked || awaitingLogin || authenticationPage
                        || pageFailed || pageDialog != null && pageDialog.isShowing()
                        || !officialUrl(web.getUrl())) return;
                String nextURL = video && actionPageMatches(url) ? url : null;
                if (nextURL == null) {
                    if (currentURL != null) clearReel();
                    status.setText(awaitingLogin ? getString(R.string.msg_login_official) : getString(R.string.msg_wait_video));
                    return;
                }
                if (!nextURL.equals(currentURL)) {
                    cancelActions();
                    stopNative();
                    currentURL = nextURL;
                    shouldPlay = true;
                    web.evaluateJavascript("if(window.TVReels)window.TVReels.state();", null);
                }
                if (!likeStateLocked) showLikeState(liked ? "已点赞" : "点赞", liked);
                updateFavorite();
                resumeNative();
            });
        }

        @JavascriptInterface public void actionResult(String action, String reel, String outcome, String requestId) {
            handler.post(() -> {
                int index = "like".equals(action) ? 0 : "notInterested".equals(action) ? 1 : -1;
                boolean submittedRemoval = index == 1 && "submitted".equals(outcome);
                if (index < 0 || !pendingActionMatches(index, reel, requestId, submittedRemoval)) return;
                if ("unconfirmed".equals(outcome) || "unavailable".equals(outcome)
                        || index == 0 && ("liked".equals(outcome) || "unliked".equals(outcome))
                        || index == 1 && "submitted".equals(outcome)) finishAction(index, outcome);
            });
        }

        @JavascriptInterface public void controls(String labelsJson, boolean likeAvailable, boolean menuAvailable) {
            if (labelsJson == null || labelsJson.length() > 1200) return;
            long epoch = actionEpoch;
            String safeLabels;
            try {
                JSONArray labels = new JSONArray(labelsJson);
                if (labels.length() > 16) return;
                for (int i = 0; i < labels.length(); i++) {
                    Object value = labels.get(i);
                    if (!(value instanceof String) || ((String) value).length() > 64) return;
                    String label = (String) value;
                    for (int j = 0; j < label.length(); j++) {
                        if (Character.isISOControl(label.charAt(j))) return;
                    }
                }
                safeLabels = labels.toString();
                if (safeLabels.length() > 1200) return;
            } catch (JSONException invalidLabels) {
                return;
            }
            handler.post(() -> {
                if (epoch != actionEpoch || !actionsAvailable()) return;
                controlLabels = safeLabels;
                likeControlAvailable = likeAvailable;
                menuControlAvailable = menuAvailable;
            });
        }
    }

    private void showLogin() {
        awaitingLogin = true;
        getPreferences(MODE_PRIVATE).edit().putBoolean("authentication_pending", true).apply();
        clearReel();
        stopMoving();
        pointerMode = true;
        status.setText(getString(R.string.msg_login_official));
        web.requestFocus();
        web.showPointer();
    }

    private String pausedMessage() {
        return getPreferences(MODE_PRIVATE).getBoolean("authentication_pending", false)
                ? getString(R.string.msg_auth_paused)
                : getString(R.string.msg_connection_paused);
    }

    private void blockAccess(WebResourceResponse response) {
        long next = System.currentTimeMillis() + 300000;
        String delay = response.getResponseHeaders() == null ? null : response.getResponseHeaders().entrySet().stream()
                .filter(entry -> "Retry-After".equalsIgnoreCase(entry.getKey())).map(Map.Entry::getValue).findFirst().orElse(null);
        if (delay != null) {
            try { next = Math.max(next, System.currentTimeMillis() + Long.parseLong(delay.trim()) * 1000); }
            catch (NumberFormatException invalidSeconds) {
                try { next = Math.max(next, java.time.ZonedDateTime.parse(delay,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()); }
                catch (java.time.format.DateTimeParseException invalidDate) { }
            }
        }
        accessBlocked = true;
        retryAfter = Math.max(retryAfter, next);
        getPreferences(MODE_PRIVATE).edit().putBoolean("access_paused", true).putLong("retry_after", retryAfter).apply();
        web.stopLoading();
        web.pauseTimers();
        showPageError(getString(R.string.msg_rate_limited));
    }

    private void showPageError(String message) {
        if (destroyed) return;
        if (pageFailed) {
            showNativeMessage(message);
            if (pageDialog != null && pageDialog.isShowing()) {
                pageDialog.setMessage(message);
                pageDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(System.currentTimeMillis() >= retryAfter);
            }
            return;
        }
        pageFailed = true;
        pageReady = false;
        web.stopLoading();
        web.pauseTimers();
        pointerMode = false;
        stopMoving();
        clearReel();
        nativeLayer.setVisibility(View.VISIBLE);
        showNativeMessage(message);
        if (!foreground) return;
        if (pageDialog != null) pageDialog.dismiss();
        String retryURL = officialUrl(requestedPage) ? requestedPage : HOME;
        pageDialog = new AlertDialog.Builder(this)
                .setTitle(getPreferences(MODE_PRIVATE).getBoolean("authentication_pending", false)
                        ? getString(R.string.dialog_auth_title) : getString(R.string.dialog_page_title))
                .setMessage(message + "\n\n" + getString(R.string.dialog_cancel_note))
                .setPositiveButton(getPreferences(MODE_PRIVATE).getBoolean("authentication_pending", false)
                        ? getString(R.string.action_continue_official) : getString(R.string.action_retry), (dialog, which) -> {
                    if (System.currentTimeMillis() < retryAfter) return;
                    accessBlocked = false;
                    awaitingLogin = getPreferences(MODE_PRIVATE).getBoolean("authentication_pending", false);
                    authenticationPage = awaitingLogin;
                    pageFailed = false;
                    getPreferences(MODE_PRIVATE).edit().putBoolean("access_paused", false).remove("retry_after").apply();
                    web.resumeTimers();
                    web.loadUrl(retryURL);
                })
                .setNegativeButton(getString(R.string.action_home), (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
        pageDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(System.currentTimeMillis() >= retryAfter);
    }

    private void clearReel() {
        cancelActions();
        stopNative();
        mediaFailed = false;
        nativeLayer.setVisibility(View.GONE);
        currentURL = null;
        updateFavorite();
    }

    private void showNativeMessage(String message) {
        status.setText(message);
        nativeMessage.setText(message);
        nativeMessage.setVisibility(View.VISIBLE);
    }

    private boolean nativeCurrent(int token, String reel) {
        return !destroyed && foreground && !accessBlocked && !awaitingLogin && !pointerMode && token == generation
                && reel.equals(currentURL) && reel.equals(nativeReel);
    }

    private static boolean mediaUrl(String source) {
        Uri uri = Uri.parse(source);
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty()) return false;
        }
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getEncodedUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && (host.endsWith(".cdninstagram.com")
                || host.equals("fbcdn.net") || host.endsWith(".fbcdn.net"));
    }

    private void resumeNative() {
        resumeNative(false);
    }

    private void resumeNative(boolean userRetry) {
        if (destroyed || !foreground || accessBlocked || awaitingLogin || pageFailed || mediaFailed
                || leaving || pointerMode || !reelUrl(currentURL)) return;
        nativeLayer.setVisibility(View.VISIBLE);
        if (currentURL.equals(nativeReel)) return;
        stopNative();
        mediaFailed = false;
        nativeReel = currentURL;
        int token = generation;
        String expectedReel = currentURL;
        showNativeMessage(getString(R.string.msg_open_video));
        if (expectedReel.equals(cachedReel) && cachedSource != null) {
            startNativeVideo(cachedSource, token, expectedReel);
        } else if (reelSources.get(expectedReel) != null) {
            startNativeVideo(reelSources.get(expectedReel), token, expectedReel);
        } else if (userRetry) resolveNative(token, expectedReel);
        else {
            sourceWait = () -> {
                if (nativeCurrent(token, expectedReel) && nativeVideo == null) {
                    failNative(getString(R.string.msg_source_missing));
                }
            };
            handler.postDelayed(sourceWait, 5000);
        }
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void resolveNative(int token, String expectedReel) {
        String embed = expectedReel + "embed/";
        WebView parser = new WebView(this);
        resolver = parser;
        parser.setAlpha(0);
        parser.setFocusable(false);
        parser.setFocusableInTouchMode(false);
        parser.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        content.addView(parser, 0, new FrameLayout.LayoutParams(-1, -1));
        WebSettings settings = parser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setUserAgentString(publicUserAgent);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setGeolocationEnabled(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(parser, EMBED_PAUSE,
                    new HashSet<>(Arrays.asList("https://www.instagram.com", "https://instagram.com")));
        }
        parser.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
        });
        parser.setWebViewClient(new WebViewClient() {
            private boolean allowed(String url) {
                return officialUrl(url) && Uri.parse(embed).getPath().equals(Uri.parse(url).getPath());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request.isForMainFrame() && authenticationUrl(request.getUrl().toString())
                        && nativeCurrent(token, expectedReel) && resolver == parser) {
                    awaitingLogin = true;
                    getPreferences(MODE_PRIVATE).edit().putBoolean("authentication_pending", true).apply();
                    accessBlocked = true;
                    showPageError(pausedMessage());
                    return true;
                }
                return !allowed(request.getUrl().toString());
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (accessBlocked || !"https".equalsIgnoreCase(request.getUrl().getScheme())
                        || request.isForMainFrame() && !allowed(request.getUrl().toString())) {
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                if (!nativeCurrent(token, expectedReel) || resolver != parser) return;
                if (!allowed(url)) {
                    failNative(getString(R.string.msg_video_unavailable));
                    return;
                }
                view.evaluateJavascript(EMBED_PAUSE, null);
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (nativeCurrent(token, expectedReel) && resolver == parser && allowed(url)) {
                    view.evaluateJavascript(EMBED_PAUSE, null);
                }
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && nativeCurrent(token, expectedReel) && resolver == parser) {
                    failNative(getString(R.string.msg_video_page_network));
                }
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (!nativeCurrent(token, expectedReel) || resolver != parser) return;
                if (accessLimited(request.getUrl().toString(), response.getStatusCode())) blockAccess(response);
                else if (request.isForMainFrame()) failNative(getString(R.string.msg_embed_unavailable));
            }
        });
        lookup = new Runnable() {
            @Override public void run() {
                if (!nativeCurrent(token, expectedReel) || resolver != parser) return;
                if (!officialUrl(parser.getUrl())
                        || !Uri.parse(embed).getPath().equals(Uri.parse(parser.getUrl()).getPath())) {
                    handler.postDelayed(this, 500);
                    return;
                }
                parser.evaluateJavascript(sourceScript, value -> {
                    if (!nativeCurrent(token, expectedReel) || resolver != parser) return;
                    try {
                        Object result = new JSONTokener(value).nextValue();
                        if (result instanceof String) {
                            if (!mediaUrl((String) result)) {
                                failNative(getString(R.string.msg_video_unavailable));
                                return;
                            }
                            startNativeVideo((String) result, token, expectedReel);
                            return;
                        }
                    } catch (JSONException invalidResult) {
                        failNative(getString(R.string.msg_video_unavailable));
                        return;
                    }
                    handler.postDelayed(this, 500);
                });
            }
        };
        lookupTimeout = () -> {
            if (nativeCurrent(token, expectedReel) && resolver == parser) {
                failNative(getString(R.string.msg_video_timeout));
            }
        };
        handler.postDelayed(lookupTimeout, 20000);
        parser.loadUrl(embed);
        handler.postDelayed(lookup, 500);
    }

    private void startNativeVideo(String source, int token, String expectedReel) {
        if (!nativeCurrent(token, expectedReel)) return;
        closeResolver();
        if (!expectedReel.equals(cachedReel)) cachedPosition = 0;
        cachedReel = expectedReel;
        cachedSource = source;
        reelSources.put(expectedReel, source);
        showNativeMessage(getString(R.string.msg_open_video));
        nativeVideo = new ReelsPlayback(this, source, publicUserAgent, cachedPosition, shouldPlay,
                new ReelsPlayback.Listener() {
            @Override public void onReady() {
                if (!nativeCurrent(token, expectedReel)) return;
                nativePrepared = true;
            }

            @Override public void onFirstFrame() {
                if (!nativeCurrent(token, expectedReel)) return;
                getPreferences(MODE_PRIVATE).edit().remove("authentication_pending").apply();
                nativeMessage.setVisibility(View.GONE);
                updateFavorite();
                selectedButton.requestFocus();
                status.setText(shouldPlay ? R.string.rail_status_playing : R.string.rail_status_paused);
            }

            @Override public void onError(int errorCode) {
                if (!nativeCurrent(token, expectedReel)) return;
                android.util.Log.w("ReelsTV", "Playback error " + errorCode);
                failNative(getString(R.string.msg_playback_failed));
            }
        });
        nativeVideo.setRotationDegrees(getPreferences(MODE_PRIVATE).getInt(
                "rotation_" + Uri.parse(expectedReel).getLastPathSegment(), 0));
        nativeVideo.setAutoZoom(getPreferences(MODE_PRIVATE).getBoolean(
                "auto_zoom_" + Uri.parse(expectedReel).getLastPathSegment(), true));
        nativeLayer.addView(nativeVideo.view, 0, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
    }

    private void closeResolver() {
        if (sourceWait != null) handler.removeCallbacks(sourceWait);
        sourceWait = null;
        if (lookup != null) handler.removeCallbacks(lookup);
        if (lookupTimeout != null) handler.removeCallbacks(lookupTimeout);
        lookup = null;
        lookupTimeout = null;
        if (resolver == null) return;
        WebView previous = resolver;
        resolver = null;
        previous.stopLoading();
        content.removeView(previous);
        previous.destroy();
    }

    private void stopNative() {
        generation++;
        closeResolver();
        if (nativeVideo != null) {
            if (nativePrepared && nativeReel != null && nativeReel.equals(cachedReel)) {
                cachedPosition = nativeVideo.getCurrentPosition();
            }
            nativeVideo.release();
            nativeLayer.removeView(nativeVideo.view);
            nativeVideo = null;
        }
        nativePrepared = false;
        nativeReel = null;
    }

    private void failNative(String message) {
        if (currentURL != null) reelSources.remove(currentURL);
        stopNative();
        cachedReel = null;
        cachedSource = null;
        cachedPosition = 0;
        nativeReel = currentURL;
        mediaFailed = true;
        updateFavorite();
        showNativeMessage(message);
    }

    private void toggleNative() {
        if (accessBlocked || awaitingLogin || pageFailed || !reelUrl(currentURL) || pointerMode) return;
        if (nativeVideo == null && resolver == null && sourceWait == null) {
            long now = SystemClock.elapsedRealtime();
            if (lastMediaRetry > 0 && now - lastMediaRetry < 30000) {
                status.setText(getString(R.string.msg_retry_wait));
                return;
            }
            lastMediaRetry = now;
            mediaFailed = false;
            nativeReel = null;
            shouldPlay = true;
            resumeNative(true);
        } else {
            shouldPlay = !shouldPlay;
            if (nativePrepared) {
                if (shouldPlay) nativeVideo.start();
                else nativeVideo.pause();
                status.setText(shouldPlay ? R.string.rail_status_playing : R.string.rail_status_paused);
            }
        }
    }

    private void updateFavorite() {
        boolean available = pageReady && !accessBlocked && !awaitingLogin && !pageFailed && reelUrl(currentURL);
        boolean pictureReady = nativeVideo != null && nativeVideo.hasFirstFrame();
        String path = web == null || web.getUrl() == null ? "" : Uri.parse(web.getUrl()).getPath();
        boolean embed = path != null && path.endsWith("/embed/");
        boolean listAvailable = available && (pictureReady || mediaFailed) && !embed && actionRequests[1] == null;
        for (int i = 0; i < 2; i++) {
            buttons[i].setEnabled(listAvailable);
            buttons[i].setAlpha(listAvailable ? 1f : .45f);
        }
        buttons[3].setEnabled(BuildConfig.XUNLEI && available && pictureReady);
        buttons[3].setAlpha(available && pictureReady ? 1f : .45f);
        buttons[4].setEnabled(available && pictureReady);
        buttons[4].setAlpha(available && pictureReady ? 1f : .45f);
        buttons[6].setEnabled(available && pictureReady);
        buttons[6].setAlpha(available && pictureReady ? 1f : .45f);
        buttons[6].setText(nativeVideo != null && !nativeVideo.isAutoZoom() ? "画面：原始比例" : "画面：自动放大");
        buttons[6].setContentDescription(getString(nativeVideo != null && !nativeVideo.isAutoZoom()
                ? R.string.rail_fit_description : R.string.rail_zoom_description));
        updateActionButtons();
        if (available && pictureReady && embed && (selectedButton == null || !selectedButton.isEnabled()
                || selectedButton.getVisibility() != View.VISIBLE)) selectedButton = buttons[BuildConfig.XUNLEI ? 3 : 4];
        if (mediaFailed && (selectedButton == buttons[3] || selectedButton == buttons[4] || selectedButton == buttons[6])) selectedButton = buttons[1];
        if (available && !pointerMode && selectedButton != null && selectedButton.isEnabled()
                && selectedButton.getVisibility() == View.VISIBLE) selectedButton.requestFocus();
    }

    private boolean actionPageMatches(String reel) {
        String page = web.getUrl();
        if (!reelUrl(reel) || !reelsPage(page)) return false;
        String path = Uri.parse(page).getPath();
        String id = Uri.parse(reel).getLastPathSegment();
        return "/reels".equals(path) || "/reels/".equals(path)
                || path.matches("/reels?/" + id + "/?") || ("/reel/" + id + "/embed/").equals(path);
    }

    private boolean actionContextAvailable() {
        return !destroyed && foreground && !leaving && pageReady && !accessBlocked && !awaitingLogin
                && !authenticationPage && !pageFailed && (pageDialog == null || !pageDialog.isShowing())
                && reelUrl(currentURL) && reelsPage(web.getUrl())
                && !Uri.parse(web.getUrl()).getPath().endsWith("/embed/");
    }

    private boolean actionsAvailable() {
        return actionContextAvailable() && actionPageMatches(currentURL);
    }

    private void updateActionButtons() {
        boolean available = actionsAvailable() && actionRequests[0] == null && actionRequests[1] == null;
        for (int i = 0; i < 2; i++) {
            Button button = buttons[i == 0 ? 2 : 5];
            button.setEnabled(available);
            button.setAlpha(button.isEnabled() ? 1f : .45f);
        }
    }

    private void showLikeState(String text, boolean liked) {
        buttons[2].setText(text);
        buttons[2].setTextColor(liked ? 0xFF81F0A5 : 0xFFFFFFFF);
        int description = liked ? R.string.rail_liked_description
                : text.contains("处理中") ? R.string.rail_like_pending
                : text.contains("不可用") ? R.string.rail_like_unavailable
                : text.contains("未能确认") ? R.string.rail_like_unconfirmed
                : R.string.rail_like_description;
        buttons[2].setContentDescription(getString(description));
        if (status != null && !"点赞".equals(text)) status.setText(description);
    }

    private void cancelActions() {
        actionEpoch++;
        for (int i = 0; i < 2; i++) {
            if (actionTimeouts[i] != null) handler.removeCallbacks(actionTimeouts[i]);
            actionTimeouts[i] = null;
            actionRequests[i] = null;
            actionReels[i] = null;
        }
        likeStateLocked = false;
        likeControlAvailable = false;
        menuControlAvailable = false;
        controlLabels = "[]";
        showLikeState("点赞", false);
        buttons[5].setText("不感兴趣");
        if (web != null && !destroyed) web.evaluateJavascript(
                "if(window.TVReels)window.TVReels.cancelActions();", null);
        updateActionButtons();
    }

    private boolean pendingActionMatches(int index, String reel, String requestId, boolean allowReelChange) {
        return requestId != null && requestId.equals(actionRequests[index]) && reel != null
                && reel.equals(actionReels[index]) && reel.equals(currentURL) && actionContextAvailable()
                && (allowReelChange || actionPageMatches(reel));
    }

    private void startAction(int index) {
        if (!actionsAvailable() || actionRequests[0] != null || actionRequests[1] != null) return;
        String reel = currentURL;
        String requestId = Long.toString(++nextActionRequest);
        String action = index == 0 ? "like" : "notInterested";
        actionRequests[index] = requestId;
        actionReels[index] = reel;
        if (index == 0) {
            likeStateLocked = true;
            showLikeState("点赞处理中…", false);
        } else buttons[5].setText("不感兴趣\n处理中…");
        updateFavorite();
        actionTimeouts[index] = () -> {
            if (pendingActionMatches(index, reel, requestId, false)) finishAction(index, "unconfirmed");
            else if (requestId.equals(actionRequests[index])) cancelActions();
        };
        handler.postDelayed(actionTimeouts[index], 6500);
        web.evaluateJavascript("(function(){return !!(window.TVReels&&window.TVReels." + action
                + "(" + JSONObject.quote(reel) + "," + JSONObject.quote(requestId) + "));})()", result -> {
            if (pendingActionMatches(index, reel, requestId, false) && !"true".equals(result)) {
                finishAction(index, "unavailable");
            }
        });
    }

    private void finishAction(int index, String outcome) {
        if (actionTimeouts[index] != null) handler.removeCallbacks(actionTimeouts[index]);
        actionTimeouts[index] = null;
        actionRequests[index] = null;
        actionReels[index] = null;
        if (index == 0) {
            String text = "liked".equals(outcome) ? "已点赞" : "unliked".equals(outcome) ? "已取消点赞"
                    : "unavailable".equals(outcome) ? "点赞\n当前不可用" : "点赞\n未能确认";
            showLikeState(text, "liked".equals(outcome));
            if ("liked".equals(outcome) || "unliked".equals(outcome)) {
                likeStateLocked = false;
                Toast.makeText(this, buttons[2].getContentDescription(), Toast.LENGTH_SHORT).show();
            }
        } else {
            buttons[5].setText("submitted".equals(outcome) ? "不感兴趣\n已提交"
                    : "unavailable".equals(outcome) ? "不感兴趣\n当前不可用" : "不感兴趣\n未能确认");
            if ("submitted".equals(outcome)) Toast.makeText(this, getString(R.string.msg_not_interested_done), Toast.LENGTH_SHORT).show();
        }
        updateFavorite();
    }

    private void command(String action) {
        if (!foreground || accessBlocked || awaitingLogin || pageFailed || !pageReady
                || actionRequests[1] != null || !officialUrl(web.getUrl())) return;
        String expectedReel = currentURL;
        if ("previous".equals(action) || "next".equals(action)) {
            String path = Uri.parse(web.getUrl()).getPath();
            if (path != null && path.endsWith("/embed/")) {
                status.setText(getString(R.string.msg_single_video));
                return;
            }
            clearReel();
            if (!pointerMode) {
                nativeLayer.setVisibility(View.VISIBLE);
                showNativeMessage(getString(R.string.msg_switching));
            }
        }
        web.evaluateJavascript("(function(){return !!(window.TVReels&&window.TVReels." + action + "());})()", result -> {
            if (!destroyed && foreground && !"true".equals(result)) {
                currentURL = expectedReel;
                updateFavorite();
                resumeNative();
                status.setText(getString(R.string.msg_switch_failed));
            }
        });
    }

    private void rotateVideo() {
        if (!foreground || accessBlocked || awaitingLogin || pageFailed || pointerMode
                || !reelUrl(currentURL) || nativeVideo == null || !nativeVideo.hasFirstFrame()) return;
        int degrees = nativeVideo.rotateLeft();
        getPreferences(MODE_PRIVATE).edit().putInt("rotation_" + Uri.parse(currentURL).getLastPathSegment(), degrees).apply();
        status.setText(R.string.rail_status_rotated);
    }

    private void toggleAutoZoom() {
        if (!foreground || accessBlocked || awaitingLogin || pageFailed || pointerMode
                || !reelUrl(currentURL) || nativeVideo == null || !nativeVideo.hasFirstFrame()) return;
        boolean enabled = !nativeVideo.isAutoZoom();
        nativeVideo.setAutoZoom(enabled);
        getPreferences(MODE_PRIVATE).edit().putBoolean(
                "auto_zoom_" + Uri.parse(currentURL).getLastPathSegment(), enabled).apply();
        updateFavorite();
        status.setText(enabled ? R.string.rail_zoom_description : R.string.rail_fit_description);
    }

    private void favorite() {
        if (leaving) return;
        if (!foreground || !pageReady || accessBlocked || awaitingLogin || pageFailed
                || !reelsPage(web.getUrl()) || !reelUrl(currentURL)
                || !currentURL.equals(cachedReel) || cachedSource == null || !mediaUrl(cachedSource)
                || nativeVideo == null || !nativeVideo.hasFirstFrame()) {
            status.setText(getString(R.string.msg_no_favorite_url));
            return;
        }
        leaving = true;
        stopNative();
        showNativeMessage(getString(R.string.rail_status_paused));
        try {
            startActivityForResult(new Intent().setClassName(this, "com.gathin.instagramremote.CloudFavoriteActivity")
                    .putExtra("reel_url", currentURL)
                    .putExtra("media_source", cachedSource)
                    .putExtra("media_user_agent", publicUserAgent), 7301);
        } catch (ActivityNotFoundException failure) {
            Toast.makeText(this, getString(R.string.msg_favorite_missing), Toast.LENGTH_LONG).show();
            leaving = false;
            resumeNative();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP && consumedKeys.get(key)) {
            consumedKeys.delete(key);
            heldDirections.delete(key);
            if (heldDirections.size() == 0) stopMoving();
            return true;
        }
        if (consumedKeys.get(key)) return true;
        if (key == KeyEvent.KEYCODE_BACK) stopMoving();
        if (web == null || !foreground || keyboardVisible()) return super.dispatchKeyEvent(event);
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && event.getAction() == KeyEvent.ACTION_DOWN) {
            consumedKeys.put(key, true);
            toggleNative();
            return true;
        }
        boolean direction = key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean click = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
        if (event.getAction() != KeyEvent.ACTION_DOWN || !direction && !click) return super.dispatchKeyEvent(event);
        if (!pointerMode) {
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                consumedKeys.put(key, true);
                int index = 0;
                while (index < buttons.length - 1 && buttons[index] != selectedButton) index++;
                int step = key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                int next = index + step;
                while (next >= 0 && next < buttons.length
                        && (!buttons[next].isEnabled() || buttons[next].getVisibility() != View.VISIBLE)) next += step;
                if (next >= 0 && next < buttons.length) selectedButton = buttons[next];
                selectedButton.requestFocus();
                return true;
            }
            if (click) {
                consumedKeys.put(key, true);
                selectedButton.requestFocus();
                if (selectedButton.isEnabled() && selectedButton.getVisibility() == View.VISIBLE) selectedButton.performClick();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                consumedKeys.put(key, true);
                selectedButton.requestFocus();
                return true;
            }
            if (key != KeyEvent.KEYCODE_DPAD_LEFT) return super.dispatchKeyEvent(event);
            consumedKeys.put(key, true);
            pointerMode = true;
            stopNative();
            nativeLayer.setVisibility(View.GONE);
            status.setText(getString(R.string.msg_browsing));
            web.requestFocus();
            web.showPointer();
            return true;
        }
        if (!web.hasFocus()) return super.dispatchKeyEvent(event);
        consumedKeys.put(key, true);
        if (click) {
            web.showPointer();
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, web.x, web.y, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, web.x, web.y, 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            web.dispatchTouchEvent(down);
            web.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
        } else {
            heldDirections.put(key, true);
            movePointer(dp(14));
            if (pointerMode && !moving) {
                moving = true;
                handler.postDelayed(move, 250);
            }
        }
        return true;
    }

    private void movePointer(int step) {
        int edge = dp(10);
        if (heldDirections.get(KeyEvent.KEYCODE_DPAD_RIGHT) && web.x >= web.getWidth() - edge) {
            stopMoving();
            pointerMode = false;
            if (!selectedButton.isEnabled() || selectedButton.getVisibility() != View.VISIBLE)
                selectedButton = buttons[4].isEnabled() ? buttons[4] : buttons[1];
            selectedButton.requestFocus();
            return;
        }
        if (heldDirections.get(KeyEvent.KEYCODE_DPAD_LEFT)) web.x -= step;
        if (heldDirections.get(KeyEvent.KEYCODE_DPAD_RIGHT)) web.x += step;
        if (heldDirections.get(KeyEvent.KEYCODE_DPAD_UP)) {
            if (web.y <= edge && web.canScrollVertically(-1)) web.scrollBy(0, -step);
            else web.y -= step;
        }
        if (heldDirections.get(KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (web.y >= web.getHeight() - edge && web.canScrollVertically(1)) web.scrollBy(0, step);
            else web.y += step;
        }
        web.x = Math.max(edge, Math.min(web.getWidth() - edge, web.x));
        web.y = Math.max(edge, Math.min(web.getHeight() - edge, web.y));
        web.showPointer();
    }

    private void stopMoving() {
        moving = false;
        heldDirections.clear();
        handler.removeCallbacks(move);
    }

    private boolean keyboardVisible() {
        WindowInsets insets = screen.getRootWindowInsets();
        if (Build.VERSION.SDK_INT >= 30 && insets != null) return insets.isVisible(WindowInsets.Type.ime());
        Rect visible = new Rect();
        screen.getWindowVisibleDisplayFrame(visible);
        int displayHeight = getResources().getDisplayMetrics().heightPixels;
        return displayHeight - visible.height() > displayHeight / 4;
    }

    @Override public void onBackPressed() {
        cancelActions();
        if (accessBlocked || pageFailed || awaitingLogin) {
            finish();
            return;
        }
        stopMoving();
        web.evaluateJavascript(PAUSE_MEDIA, null);
        if (web.canGoBack()) {
            clearReel();
            web.goBack();
        } else {
            leaving = true;
            stopNative();
            super.onBackPressed();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 7301 && resultCode == RESULT_OK) {
            shouldPlay = true;
            Toast.makeText(this, getString(R.string.msg_favorite_saved), Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        leaving = false;
        consumedKeys.clear();
        if (web != null) {
            web.onResume();
            if (!accessBlocked && !pageFailed) {
                web.resumeTimers();
                if (!awaitingLogin && reelsPage(web.getUrl())) {
                    web.evaluateJavascript("window.__reelsTvPaused=false;", null);
                    if (pageReady) {
                        web.evaluateJavascript(script, null);
                        web.evaluateJavascript("if(window.TVReels)window.TVReels.state();", null);
                    }
                }
            } else if (pageDialog == null || !pageDialog.isShowing()) {
                String message = nativeMessage.getText().toString();
                pageFailed = false;
                showPageError(getPreferences(MODE_PRIVATE).getBoolean("authentication_pending", false)
                        || message.isEmpty() ? pausedMessage() : message);
            }
        }
        resumeNative();
        updateActionButtons();
    }

    @Override protected void onPause() {
        foreground = false;
        cancelActions();
        stopMoving();
        stopNative();
        if (reelUrl(currentURL)) showNativeMessage(getString(R.string.rail_status_paused));
        if (web != null) {
            if (getPreferences(MODE_PRIVATE).getBoolean("authentication_pending", false)) {
                accessBlocked = true;
                web.stopLoading();
            }
            if (reelsPage(web.getUrl())) web.evaluateJavascript(PAUSE_MEDIA, null);
            web.onPause();
            web.pauseTimers();
        }
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) stopMoving();
        else if (!pointerMode && selectedButton != null) {
            if (!selectedButton.isEnabled() || selectedButton.getVisibility() != View.VISIBLE)
                selectedButton = buttons[4].isEnabled() ? buttons[4] : buttons[1];
            selectedButton.requestFocus();
        }
    }

    @Override protected void onDestroy() {
        if (pageDialog != null) pageDialog.dismiss();
        destroyed = true;
        cancelActions();
        stopNative();
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.removeCallbacks(web.hidePointer);
            web.stopLoading();
            web.removeJavascriptInterface("ReelsTv");
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    @Override public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        super.dump(prefix, fd, writer, args);
        if (nativeVideo != null) writer.println(prefix + "ReelsTV display: " + nativeVideo.displayInfo());
        writer.println(prefix + "ReelsTV controls: labels=" + controlLabels + " likeControl=" + likeControlAvailable
                + " menuControl=" + menuControlAvailable);
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        boolean ready = nativePrepared && nativeVideo != null;
        writer.println(prefix + "ReelsTV playback: prepared=" + ready
                + " playing=" + (ready && nativeVideo.isPlaying())
                + " position=" + (ready ? nativeVideo.getCurrentPosition() : 0)
                + " duration=" + (ready ? nativeVideo.getDuration() : 0)
                + " viewWidth=" + (nativeVideo == null ? 0 : nativeVideo.view.getWidth())
                + " viewHeight=" + (nativeVideo == null ? 0 : nativeVideo.view.getHeight())
                + " firstFrame=" + (nativeVideo != null && nativeVideo.hasFirstFrame())
                + " decoder=" + (nativeVideo == null ? "none" : nativeVideo.decoderName())
                + " " + (nativeVideo == null ? "rendered=0 dropped=0" : nativeVideo.frameStats())
                + " reel=" + (reelUrl(currentURL) ? Uri.parse(currentURL).getPath().split("/")[2] : "none")
                + " pageFailed=" + pageFailed + " loginRequired=" + awaitingLogin);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class ReelsWebView extends WebView {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x, y;
        private boolean pointerVisible;
        private final Runnable hidePointer = () -> { pointerVisible = false; invalidate(); };

        ReelsWebView() { super(ReelsTvActivity.this); }

        private void showPointer() {
            pointerVisible = true;
            removeCallbacks(hidePointer);
            postDelayed(hidePointer, 4000);
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            x = width / 2f;
            y = height / 2f;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!pointerMode || !hasFocus() || !pointerVisible || keyboardVisible()) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xAA000000);
            canvas.drawCircle(x + getScrollX(), y + getScrollY(), dp(8), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(x + getScrollX(), y + getScrollY(), dp(7), paint);
        }
    }
}
