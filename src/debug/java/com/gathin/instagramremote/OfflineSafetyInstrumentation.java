package com.gathin.instagramremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.Context;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class OfflineSafetyInstrumentation extends Instrumentation {
    private final JSONArray checks = new JSONArray();
    private ReelsTvActivity activity;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        JSONObject result = new JSONObject();
        Bundle output = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            checkMessageLanguages();
            checkRotation();
            checkAspectToggle();
            checkFavoriteReturn();
            checkActionFeedback();
            check("independent_test_package", getTargetContext().getPackageName().endsWith(".checks"));
            check("network_permission_denied", getTargetContext().checkSelfPermission("android.permission.INTERNET")
                    == PackageManager.PERMISSION_DENIED);
            Method limited = ReelsTvActivity.class.getDeclaredMethod("accessLimited", String.class, int.class);
            limited.setAccessible(true);
            String[] accepted = {"https://instagram.com/api/", "https://www.instagram.com/graphql/query",
                    "https://i.instagram.com/api/v1/", "https://I.INSTAGRAM.COM:443/api/"};
            for (String url : accepted) check("limit_" + url, (boolean) limited.invoke(null, url, 429));
            String[] rejected = {"http://i.instagram.com/api/", "https://i.instagram.com.evil.example/api/",
                    "https://evil.example/?next=https://i.instagram.com", "https://i.instagram.com:444/api/",
                    "https://someone@i.instagram.com/api/", "https://cdninstagram.com/video.mp4"};
            for (String url : rejected) check("reject_" + url, !(boolean) limited.invoke(null, url, 429));
            check("unrelated_status_untouched", !(boolean) limited.invoke(null, accepted[2], 403));

            open(true);
            runOnMainSync(() -> {
                activity.getPreferences(0).edit().clear().commit();
                set("accessBlocked", false);
                set("pageFailed", false);
                WebView web = (WebView) get("web");
                web.getWebViewClient().onReceivedHttpError(web, new WebResourceRequest() {
                    @Override public Uri getUrl() { return Uri.parse("https://i.instagram.com/api/v1/test/"); }
                    @Override public boolean isForMainFrame() { return false; }
                    @Override public boolean isRedirect() { return false; }
                    @Override public boolean hasGesture() { return false; }
                    @Override public String getMethod() { return "GET"; }
                    @Override public Map<String, String> getRequestHeaders() { return Collections.emptyMap(); }
                }, new WebResourceResponse("text/plain", "UTF-8", 429, "Too Many Requests",
                        Collections.singletonMap("Retry-After", "600"), new ByteArrayInputStream(new byte[0])));
                check("api_limit_stops_activity", (boolean) get("accessBlocked") && (boolean) get("pageFailed"));
                check("server_wait_respected", (long) get("retryAfter") >= System.currentTimeMillis() + 599000);
                check("limit_pause_saved", activity.getPreferences(0).getBoolean("access_paused", false));
                check("network_loads_blocked", web.getSettings().getBlockNetworkLoads());
                check("rate_retry_button_disabled", !((AlertDialog) get("pageDialog"))
                        .getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                ((AlertDialog) get("pageDialog")).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                check("cancel_does_not_load_page", web.getUrl() == null);
            });
            waitForIdleSync();
            open(false);
            runOnMainSync(() -> {
                check("rate_pause_survives_reopen", (boolean) get("accessBlocked"));
                check("reopen_without_page_request", ((WebView) get("web")).getUrl() == null);
                activity.getPreferences(0).edit().clear().commit();
                set("accessBlocked", false);
                set("pageFailed", false);
                WebView web = (WebView) get("web");
                web.getWebViewClient().onPageStarted(web, "https://www.instagram.com/challenge/", null);
                check("verification_pending_saved", activity.getPreferences(0).getBoolean("authentication_pending", false));
                check("verification_stops_reels", (boolean) get("awaitingLogin") && get("nativeVideo") == null);
                activity.finish();
            });
            waitForIdleSync();
            open(false);
            runOnMainSync(() -> {
                WebView web = (WebView) get("web");
                check("verification_pause_survives_reopen", (boolean) get("accessBlocked") && web.getUrl() == null);
                check("verification_requires_manual_continue", activity.getString(R.string.action_continue_official).contentEquals(
                        ((AlertDialog) get("pageDialog")).getButton(AlertDialog.BUTTON_POSITIVE).getText()));
                ((AlertDialog) get("pageDialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            waitForIdleSync();
            runOnMainSync(() -> {
                check("manual_continue_does_not_clear_pending",
                        activity.getPreferences(0).getBoolean("authentication_pending", false));
                set("accessBlocked", false);
                set("pageFailed", false);
                set("authenticationPage", true);
                WebView web = (WebView) get("web");
                web.getWebViewClient().doUpdateVisitedHistory(web, "https://www.instagram.com/reels/", false);
                check("route_alone_does_not_clear_pending", activity.getPreferences(0).getBoolean("authentication_pending", false));
                web.getWebViewClient().onPageStarted(web, "https://www.instagram.com/challenge/", null);
                activity.finish();
            });
            waitForIdleSync();
            result.put("status", "passed");
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            try { result.put("status", "failed").put("error", failure.toString()); }
            catch (org.json.JSONException ignored) { }
        }
        try {
            result.put("checks", checks);
            result.put("real_account_used", false);
            result.put("network_permission", false);
            try (FileOutputStream stream = new FileOutputStream(new File(getTargetContext().getCacheDir(), "safety-check.json"))) {
                stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            output.putString("result", result.toString());
        } catch (Exception failure) {
            output.putString("error", failure.toString());
            code = Activity.RESULT_CANCELED;
        }
        finish(code, output);
    }

    private void checkMessageLanguages() {
        int[] ids = {R.string.msg_page_network, R.string.msg_playback_failed,
                R.string.msg_verify_official, R.string.dialog_auth_title,
                R.string.action_continue_official, R.string.action_home};
        String[][] expected = {
                {"Instagram 页面暂时无法加载，请检查网络后重试", "视频播放失败，请按播放键重试",
                        "Instagram 需要验证，请在官方页面自行处理", "登录或验证已暂停", "继续官方页面", "返回桌面"},
                {"The Instagram page cannot be loaded right now. Check your network and try again",
                        "Video playback failed. Press Play to retry",
                        "Instagram requires verification. Please complete it yourself on the official page",
                        "Login or verification paused", "Continue to official page", "Return to home screen"},
                {"Instagramのページを読み込めません。ネットワークを確認してからもう一度お試しください",
                        "動画を再生できませんでした。再生ボタンを押して再試行してください",
                        "Instagramで本人確認が必要です。公式ページでご自身で対応してください",
                        "ログインまたは本人確認を一時停止しました", "公式ページへ進む", "ホーム画面に戻る"}
        };
        Locale[] locales = {Locale.CHINESE, Locale.ENGLISH, Locale.JAPANESE};
        for (int language = 0; language < locales.length; language++) {
            Configuration config = new Configuration(getTargetContext().getResources().getConfiguration());
            config.setLocale(locales[language]);
            Context localized = getTargetContext().createConfigurationContext(config);
            for (int i = 0; i < ids.length; i++) {
                check("message_" + locales[language].getLanguage() + "_" + i,
                        expected[language][i].equals(localized.getString(ids[i])));
            }
        }
    }

    private void checkRotation() {
        check("rotation_independent_test_package", "com.gathin.instagramremote.checks".equals(
                getTargetContext().getPackageName()));
        check("rotation_network_permission_denied", getTargetContext().checkSelfPermission("android.permission.INTERNET")
                == PackageManager.PERMISSION_DENIED);
        File fixture = new File(getTargetContext().getCacheDir(), "reels-playback-test/portrait.mp4");
        check("rotation_local_fixture_available", fixture.isFile() && fixture.canRead() && fixture.length() > 0);
        String firstReel = "https://www.instagram.com/reel/OfflineRotationA/";
        String secondReel = "https://www.instagram.com/reel/OfflineRotationB/";
        String firstKey = "rotation_OfflineRotationA";
        String secondKey = "rotation_OfflineRotationB";
        open(true);
        try {
            runOnMainSync(() -> {
                ((AlertDialog) get("pageDialog")).dismiss();
                activity.getPreferences(0).edit().remove(firstKey).remove(secondKey).commit();
                startLocalVideo(fixture, firstReel);
                ReelsPlayback playback = (ReelsPlayback) get("nativeVideo");
                Button rotate = ((Button[]) get("buttons"))[4];
                call("updateFavorite");
                check("rotation_without_frame_button_disabled", !playback.hasFirstFrame() && !rotate.isEnabled());
                rotate.performClick();
                check("rotation_without_frame_ignored", get("nativeVideo") == playback
                        && playback.displayInfo().contains(" rotation=0 ")
                        && playback.view.getChildAt(0).getRotation() == 0
                        && !activity.getPreferences(0).contains(firstKey));
            });
            waitForFirstFrame("rotation_first_reel_first_frame");
            runOnMainSync(() -> {
                ReelsPlayback playback = (ReelsPlayback) get("nativeVideo");
                Button rotate = ((Button[]) get("buttons"))[4];
                check("rotation_initial_zero", playback.displayInfo().contains(" rotation=0 ")
                        && playback.view.getChildAt(0).getRotation() == 0);
                check("rotation_button_available", "向左旋转".contentEquals(rotate.getText()) && rotate.isEnabled());
                for (int degrees : new int[] {270, 180, 90, 0}) {
                    rotate.performClick();
                    check("rotation_button_degrees_" + degrees,
                            playback.displayInfo().contains(" rotation=" + degrees + " ")
                                    && playback.view.getChildAt(0).getRotation() == degrees);
                    check("rotation_same_player_" + degrees, get("nativeVideo") == playback);
                    check("rotation_public_id_saved_" + degrees,
                            activity.getPreferences(0).getInt(firstKey, -1) == degrees);
                }
                for (String blocked : new String[] {"accessBlocked", "awaitingLogin"}) {
                    set(blocked, true);
                    call("updateFavorite");
                    check("rotation_" + blocked + "_button_disabled", !rotate.isEnabled());
                    rotate.performClick();
                    check("rotation_" + blocked + "_ignored", get("nativeVideo") == playback
                            && playback.displayInfo().contains(" rotation=0 ")
                            && playback.view.getChildAt(0).getRotation() == 0
                            && activity.getPreferences(0).getInt(firstKey, -1) == 0);
                    set(blocked, false);
                    call("updateFavorite");
                }
                rotate.performClick();
                check("rotation_restore_value_saved", playback.displayInfo().contains(" rotation=270 ")
                        && activity.getPreferences(0).getInt(firstKey, -1) == 270);
                startLocalVideo(fixture, firstReel);
                check("rotation_player_recreated", get("nativeVideo") != null && get("nativeVideo") != playback);
                check("rotation_same_reel_restores_270", ((ReelsPlayback) get("nativeVideo"))
                        .displayInfo().contains(" rotation=270 "));
            });
            waitForFirstFrame("rotation_recreated_first_frame");
            runOnMainSync(() -> {
                check("rotation_recreated_frame_keeps_270", ((ReelsPlayback) get("nativeVideo"))
                        .displayInfo().contains(" rotation=270 ")
                        && ((ReelsPlayback) get("nativeVideo")).view.getChildAt(0).getRotation() == 270);
                startLocalVideo(fixture, secondReel);
                check("rotation_other_reel_defaults_zero", ((ReelsPlayback) get("nativeVideo"))
                        .displayInfo().contains(" rotation=0 ") && !activity.getPreferences(0).contains(secondKey));
            });
            waitForFirstFrame("rotation_other_reel_first_frame");
            runOnMainSync(() -> {
                ReelsPlayback playback = (ReelsPlayback) get("nativeVideo");
                check("rotation_other_reel_frame_keeps_zero", playback.displayInfo().contains(" rotation=0 ")
                        && playback.view.getChildAt(0).getRotation() == 0);
                ((Button[]) get("buttons"))[4].performClick();
                check("rotation_other_public_id_saved", get("nativeVideo") == playback
                        && playback.displayInfo().contains(" rotation=270 ")
                        && playback.view.getChildAt(0).getRotation() == 270
                        && activity.getPreferences(0).getInt(secondKey, -1) == 270);
                check("rotation_first_public_id_unchanged", activity.getPreferences(0).getInt(firstKey, -1) == 270);
            });
        } finally {
            runOnMainSync(() -> activity.finish());
            waitForIdleSync();
        }
    }

    private void checkActionFeedback() throws InterruptedException {
        String reel = "https://www.instagram.com/reel/OfflineActionA/";
        String other = "https://www.instagram.com/reel/OfflineActionB/";
        CountDownLatch loaded = new CountDownLatch(1);
        WebViewClient[] navigation = new WebViewClient[1];
        open(true);
        try {
            runOnMainSync(() -> {
                ((AlertDialog) get("pageDialog")).dismiss();
                set("pageDialog", null);
                WebView web = (WebView) get("web");
                navigation[0] = web.getWebViewClient();
                web.removeJavascriptInterface("ReelsTv");
                web.getSettings().setBlockNetworkLoads(true);
                web.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) {
                        if ("https://www.instagram.com/reels/".equals(view.getUrl())) loaded.countDown();
                    }
                });
                web.loadDataWithBaseURL("https://www.instagram.com/reels/",
                        "<html><script>window.TVReels={like:function(){return true},"
                                + "notInterested:function(){return true},next:function(){return true},"
                                + "previous:function(){return true},cancelActions:function(){},state:function(){}};</script></html>",
                        "text/html", "UTF-8", "https://www.instagram.com/reels/");
            });
            check("actions_local_page_ready", loaded.await(5, TimeUnit.SECONDS));
            ReelsTvActivity.ReelsState bridge = activity.new ReelsState();
            runOnMainSync(() -> {
                activity.getPreferences(0).edit().remove("authentication_pending").commit();
                set("accessBlocked", false);
                set("awaitingLogin", false);
                set("authenticationPage", false);
                set("pageFailed", false);
                set("pageReady", true);
                set("pointerMode", true);
                set("mediaFailed", true);
                set("currentURL", reel);
                call("updateFavorite");
                Button[] buttons = (Button[]) get("buttons");
                check("actions_seven_buttons_keep_indexes", buttons.length == 7
                        && "收藏到迅雷".contentEquals(buttons[3].getText())
                        && "向左旋转".contentEquals(buttons[4].getText())
                        && "不感兴趣".contentEquals(buttons[5].getText()));
                check("actions_sidebar_scrolls", buttons[5].getParent().getParent() instanceof android.widget.ScrollView);
                check("actions_unsupported_preference_hidden", buttons[5].getVisibility() == View.GONE);
                check("actions_ready_buttons_enabled url=" + ((WebView) get("web")).getUrl()
                        + " foreground=" + get("foreground") + " leaving=" + get("leaving")
                        + " destroyed=" + get("destroyed"), buttons[2].isEnabled() && buttons[5].isEnabled());
                buttons[2].performClick();
                check("actions_like_pending_visible", !buttons[2].isEnabled()
                        && buttons[2].getText().toString().contains("处理中")
                        && ((String[]) get("actionRequests"))[0] != null);
                buttons[5].performClick();
                check("actions_like_blocks_other_action", !buttons[5].isEnabled()
                        && ((String[]) get("actionRequests"))[1] == null);
            });
            bridge.controls("[\"Like\",\"More options\"]", true, true);
            waitForIdleSync();
            runOnMainSync(() -> check("actions_controls_accept_valid_labels",
                    (boolean) get("likeControlAvailable") && (boolean) get("menuControlAvailable")
                            && "[\"Like\",\"More options\"]".equals(get("controlLabels"))));
            StringBuilder tooLong = new StringBuilder();
            for (int i = 0; i < 1201; i++) tooLong.append(' ');
            StringBuilder tooMany = new StringBuilder("[");
            for (int i = 0; i < 17; i++) tooMany.append(i == 0 ? "\"x\"" : ",\"x\"");
            tooMany.append(']');
            for (String invalid : new String[] {null, "{}", "[true]", "[\"\\u0001\"]",
                    "[\"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abc\"]",
                    tooLong.toString(), tooMany.toString()}) bridge.controls(invalid, false, false);
            waitForIdleSync();
            runOnMainSync(() -> check("actions_controls_reject_invalid_labels",
                    (boolean) get("likeControlAvailable") && (boolean) get("menuControlAvailable")
                            && "[\"Like\",\"More options\"]".equals(get("controlLabels"))));
            String first = actionRequest(0);
            bridge.state(reel, false, true, false);
            bridge.actionResult("like", other, "liked", first);
            bridge.actionResult("like", reel, "liked", "wrong-request");
            waitForIdleSync();
            runOnMainSync(() -> check("actions_pending_ignores_state_and_wrong_callback",
                    !((Button[]) get("buttons"))[2].isEnabled()
                            && ((Button[]) get("buttons"))[2].getText().toString().contains("处理中")));
            bridge.actionResult("like", reel, "liked", first);
            waitForIdleSync();
            runOnMainSync(() -> {
                Button like = ((Button[]) get("buttons"))[2];
                check("actions_liked_visible_and_enabled", like.isEnabled() && "已点赞".contentEquals(like.getText())
                        && like.getCurrentTextColor() == 0xFF81F0A5);
            });
            bridge.state(reel, false, true, false);
            waitForIdleSync();
            runOnMainSync(() -> {
                Button like = ((Button[]) get("buttons"))[2];
                check("actions_later_official_false_clears_like", "点赞".contentEquals(like.getText())
                        && like.getCurrentTextColor() == 0xFFFFFFFF && !(boolean) get("likeStateLocked"));
                like.performClick();
                check("actions_new_request_is_unique", Long.parseLong(((String[]) get("actionRequests"))[0])
                        > Long.parseLong(first));
            });
            String second = actionRequest(0);
            bridge.actionResult("like", reel, "unliked", second);
            waitForIdleSync();
            runOnMainSync(() -> {
                Button[] buttons = (Button[]) get("buttons");
                check("actions_unliked_visible", "已取消点赞".contentEquals(buttons[2].getText())
                        && buttons[2].getCurrentTextColor() == 0xFFFFFFFF);
                buttons[5].performClick();
                check("actions_not_interested_pending_visible", !buttons[5].isEnabled()
                        && buttons[5].getText().toString().contains("处理中"));
                buttons[2].performClick();
                check("actions_not_interested_blocks_other_action_and_navigation", !buttons[2].isEnabled()
                        && !buttons[0].isEnabled() && !buttons[1].isEnabled()
                        && ((String[]) get("actionRequests"))[0] == null);
            });
            String preference = actionRequest(1);
            bridge.actionResult("notInterested", reel, "liked", preference);
            waitForIdleSync();
            runOnMainSync(() -> check("actions_invalid_outcome_ignored", !((Button[]) get("buttons"))[5].isEnabled()));
            bridge.actionResult("notInterested", reel, "submitted", preference);
            waitForIdleSync();
            runOnMainSync(() -> {
                Button[] buttons = (Button[]) get("buttons");
                check("actions_submitted_visible", buttons[5].isEnabled()
                        && buttons[5].getText().toString().contains("已提交")
                        && buttons[0].isEnabled() && buttons[1].isEnabled());
                buttons[5].performClick();
            });
            String removed = actionRequest(1);
            updateLocalActionRoute(navigation[0], other);
            runOnMainSync(() -> check("actions_spa_removal_keeps_pending request=" + ((String[]) get("actionRequests"))[1]
                            + " expected=" + removed + " current=" + get("currentURL")
                            + " page=" + ((WebView) get("web")).getUrl(),
                    removed != null && removed.equals(((String[]) get("actionRequests"))[1])
                            && reel.equals(get("currentURL")) && other.equals(((WebView) get("web")).getUrl())));
            bridge.actionResult("notInterested", reel, "unconfirmed", removed);
            bridge.actionResult("notInterested", reel, "submitted", "old-removal-request");
            waitForIdleSync();
            runOnMainSync(() -> check("actions_spa_removal_requires_submitted_and_matching_request",
                    removed.equals(((String[]) get("actionRequests"))[1])
                            && ((Button[]) get("buttons"))[5].getText().toString().contains("处理中")));
            bridge.actionResult("notInterested", reel, "submitted", removed);
            waitForIdleSync();
            runOnMainSync(() -> check("actions_spa_removal_accepts_old_reel_submitted",
                    ((String[]) get("actionRequests"))[1] == null
                            && ((Button[]) get("buttons"))[5].getText().toString().contains("已提交")));
            bridge.state(other, false, true, false);
            waitForIdleSync();
            runOnMainSync(() -> {
                Button[] buttons = (Button[]) get("buttons");
                check("actions_spa_new_state_resets_feedback", other.equals(get("currentURL"))
                        && "不感兴趣".contentEquals(buttons[5].getText()));
                buttons[5].performClick();
            });
            String skipped = actionRequest(1);
            runOnMainSync(() -> {
                set("mediaFailed", true);
                call("updateFavorite");
                ((Button[]) get("buttons"))[1].performClick();
                check("actions_pending_removal_blocks_manual_next", skipped != null
                        && skipped.equals(((String[]) get("actionRequests"))[1]) && other.equals(get("currentURL"))
                        && !((Button[]) get("buttons"))[0].isEnabled() && !((Button[]) get("buttons"))[1].isEnabled());
                call("clearReel");
                check("actions_clear_cancels_removal", ((String[]) get("actionRequests"))[1] == null);
            });
            bridge.actionResult("notInterested", other, "submitted", skipped);
            bridge.actionResult("notInterested", reel, "submitted", removed);
            waitForIdleSync();
            runOnMainSync(() -> check("actions_clear_rejects_old_submitted",
                    !((Button[]) get("buttons"))[5].getText().toString().contains("已提交")));
            updateLocalActionRoute(navigation[0], "https://www.instagram.com/reels/");
            bridge.state(reel, false, true, false);
            waitForIdleSync();
            runOnMainSync(() -> {
                Button[] buttons = (Button[]) get("buttons");
                buttons[2].performClick();
                long serial = (long) get("nextActionRequest");
                ((Runnable[]) get("actionTimeouts"))[0].run();
                check("actions_timeout_unlocks_without_retry", buttons[2].isEnabled()
                        && buttons[2].getText().toString().contains("未能确认")
                        && (long) get("nextActionRequest") == serial && ((String[]) get("actionRequests"))[0] == null);
                buttons[2].performClick();
                check("actions_manual_attempt_after_timeout", ((String[]) get("actionRequests"))[0] != null);
            });
            String stale = actionRequest(0);
            bridge.state(other, false, true, false);
            waitForIdleSync();
            bridge.state(reel, false, true, false);
            waitForIdleSync();
            runOnMainSync(() -> ((Button[]) get("buttons"))[2].performClick());
            String current = actionRequest(0);
            check("actions_new_a_request_exists", current != null && !current.equals(stale));
            bridge.actionResult("like", reel, "liked", stale);
            waitForIdleSync();
            runOnMainSync(() -> check("actions_a_b_a_rejects_old_result",
                    current.equals(((String[]) get("actionRequests"))[0]) && !((Button[]) get("buttons"))[2].isEnabled()));
            bridge.actionResult("like", reel, "unavailable", current);
            bridge.controls("[\"Like\",\"More options\"]", true, true);
            waitForIdleSync();
            runOnMainSync(() -> {
                check("actions_unavailable_explicit", ((Button[]) get("buttons"))[2].getText().toString().contains("当前不可用"));
                for (String blocked : new String[] {"accessBlocked", "awaitingLogin", "authenticationPage", "leaving"}) {
                    set(blocked, true);
                    call("updateFavorite");
                    Button[] buttons = (Button[]) get("buttons");
                    check("actions_" + blocked + "_disabled", !buttons[2].isEnabled() && !buttons[5].isEnabled());
                    buttons[2].performClick();
                    buttons[5].performClick();
                    check("actions_" + blocked + "_cannot_start", ((String[]) get("actionRequests"))[0] == null
                            && ((String[]) get("actionRequests"))[1] == null);
                    set(blocked, false);
                }
                call("updateFavorite");
                ((Button[]) get("buttons"))[2].performClick();
                check("actions_background_test_starts_pending", ((String[]) get("actionRequests"))[0] != null);
            });
            String paused = actionRequest(0);
            runOnMainSync(() -> {
                call("onPause");
                check("actions_background_cancels_pending", ((String[]) get("actionRequests"))[0] == null
                        && ((Runnable[]) get("actionTimeouts"))[0] == null && !((Button[]) get("buttons"))[2].isEnabled());
                check("actions_background_clears_controls", !(boolean) get("likeControlAvailable")
                        && !(boolean) get("menuControlAvailable") && "[]".equals(get("controlLabels")));
            });
            bridge.controls("[\"Like\",\"More options\"]", true, true);
            bridge.actionResult("like", reel, "liked", paused);
            waitForIdleSync();
            runOnMainSync(() -> {
                check("actions_background_rejects_controls", !(boolean) get("likeControlAvailable")
                        && !(boolean) get("menuControlAvailable") && "[]".equals(get("controlLabels")));
                set("foreground", true);
                call("updateFavorite");
                check("actions_return_does_not_accept_old_result", !"已点赞".contentEquals(((Button[]) get("buttons"))[2].getText()));
                ((Button[]) get("buttons"))[5].performClick();
                check("actions_verification_test_starts_pending", ((String[]) get("actionRequests"))[1] != null);
                call("showLogin");
                check("actions_verification_cancels_pending", ((String[]) get("actionRequests"))[1] == null
                        && !((Button[]) get("buttons"))[5].isEnabled());
            });
        } finally {
            runOnMainSync(() -> activity.finish());
            waitForIdleSync();
        }
    }

    private void checkAspectToggle() {
        File fixture = new File(getTargetContext().getCacheDir(), "reels-playback-test/captioned-small.mp4");
        String reel = "https://www.instagram.com/reel/OfflineAspectA/";
        String other = "https://www.instagram.com/reel/OfflineAspectB/";
        open(true);
        try {
            runOnMainSync(() -> {
                ((AlertDialog) get("pageDialog")).dismiss();
                activity.getPreferences(0).edit().remove("auto_zoom_OfflineAspectA")
                        .remove("auto_zoom_OfflineAspectB").remove("rotation_OfflineAspectA").commit();
                startLocalVideo(fixture, reel);
                Button button = ((Button[]) get("buttons"))[6];
                check("aspect_before_frame_disabled", !button.isEnabled());
                button.performClick();
                check("aspect_before_frame_does_not_save", !activity.getPreferences(0).contains("auto_zoom_OfflineAspectA"));
            });
            waitForFirstFrame("aspect_first_frame");
            long deadline = SystemClock.elapsedRealtime() + 5000;
            int[] enlargedWidth = {0};
            do {
                runOnMainSync(() -> enlargedWidth[0] = ((ReelsPlayback) get("nativeVideo")).view.getChildAt(0).getWidth());
                if (enlargedWidth[0] > 700) break;
                SystemClock.sleep(50);
            } while (SystemClock.elapsedRealtime() < deadline);
            check("aspect_real_black_edges_enlarged", enlargedWidth[0] > 700);
            ReelsPlayback[] original = new ReelsPlayback[1];
            int[] position = {0};
            runOnMainSync(() -> {
                ReelsPlayback playback = (ReelsPlayback) get("nativeVideo");
                original[0] = playback;
                Button[] buttons = (Button[]) get("buttons");
                check("aspect_default_auto_visible", playback.isAutoZoom() && buttons[6].isEnabled()
                        && buttons[6].getVisibility() == View.VISIBLE
                        && "画面：自动放大".contentEquals(buttons[6].getText()));
                set("selectedButton", buttons[4]);
                buttons[4].requestFocus();
                for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                    activity.dispatchKeyEvent(new KeyEvent(action, KeyEvent.KEYCODE_DPAD_DOWN));
                }
                check("aspect_remote_down_skips_hidden_button", get("selectedButton") == buttons[6] && buttons[6].hasFocus());
                for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                    activity.dispatchKeyEvent(new KeyEvent(action, KeyEvent.KEYCODE_DPAD_UP));
                }
                check("aspect_remote_up_skips_hidden_button", get("selectedButton") == buttons[4] && buttons[4].hasFocus());
                set("shouldPlay", false);
                playback.pause();
                position[0] = playback.getCurrentPosition();
                buttons[6].performClick();
                check("aspect_original_mode_saved", !playback.isAutoZoom()
                        && !activity.getPreferences(0).getBoolean("auto_zoom_OfflineAspectA", true)
                        && "画面：原始比例".contentEquals(buttons[6].getText()));
            });
            SystemClock.sleep(100);
            runOnMainSync(() -> {
                ReelsPlayback playback = (ReelsPlayback) get("nativeVideo");
                View texture = playback.view.getChildAt(0);
                check("aspect_original_restores_full_frame", texture.getWidth() < enlargedWidth[0]
                        && texture.getTranslationX() == 0 && texture.getTranslationY() == 0
                        && playback.displayInfo().contains(" contentTop=0.0 contentBottom=1.0"));
                check("aspect_toggle_keeps_paused_player_and_position", playback == original[0]
                        && !playback.isPlaying() && Math.abs(playback.getCurrentPosition() - position[0]) < 250);
                check("aspect_toggle_no_page_or_parser_load", ((WebView) get("web")).getUrl() == null
                        && get("resolver") == null && get("sourceWait") == null);
                ((Button[]) get("buttons"))[6].performClick();
                check("aspect_auto_returns_while_paused", playback.isAutoZoom() && playback == original[0] && !playback.isPlaying());
            });
            SystemClock.sleep(100);
            runOnMainSync(() -> {
                ReelsPlayback playback = (ReelsPlayback) get("nativeVideo");
                check("aspect_auto_reuses_known_bounds", playback.view.getChildAt(0).getWidth() == enlargedWidth[0]);
                for (boolean auto : new boolean[] {false, true}) {
                    if (playback.isAutoZoom() != auto) ((Button[]) get("buttons"))[6].performClick();
                    for (int degrees : new int[] {270, 180, 90, 0}) {
                        ((Button[]) get("buttons"))[4].performClick();
                        check("aspect_rotation_" + auto + "_" + degrees, playback.isAutoZoom() == auto
                                && playback.view.getChildAt(0).getRotation() == degrees
                                && (auto || playback.view.getChildAt(0).getTranslationX() == 0
                                && playback.view.getChildAt(0).getTranslationY() == 0));
                    }
                }
                ((Button[]) get("buttons"))[6].performClick();
                check("aspect_original_keeps_rotation", !playback.isAutoZoom()
                        && playback.displayInfo().contains(" rotation=0 ")
                        && playback.view.getChildAt(0).getRotation() == 0);
                playback.start();
            });
            SystemClock.sleep(1100);
            runOnMainSync(() -> {
                ReelsPlayback playback = (ReelsPlayback) get("nativeVideo");
                check("aspect_original_stays_full_during_playback", !playback.isAutoZoom() && playback.isPlaying()
                        && playback.view.getChildAt(0).getWidth() < enlargedWidth[0]
                        && playback.displayInfo().contains(" contentTop=0.0 contentBottom=1.0"));
                startLocalVideo(fixture, reel);
                check("aspect_same_reel_restores_original", !((ReelsPlayback) get("nativeVideo")).isAutoZoom());
            });
            waitForFirstFrame("aspect_restored_first_frame");
            runOnMainSync(() -> {
                check("aspect_restored_button_matches", "画面：原始比例".contentEquals(((Button[]) get("buttons"))[6].getText()));
                startLocalVideo(fixture, other);
                check("aspect_other_reel_defaults_auto", ((ReelsPlayback) get("nativeVideo")).isAutoZoom()
                        && !activity.getPreferences(0).contains("auto_zoom_OfflineAspectB"));
            });
            waitForFirstFrame("aspect_other_first_frame");
            runOnMainSync(() -> {
                check("aspect_other_button_matches", "画面：自动放大".contentEquals(((Button[]) get("buttons"))[6].getText()));
                for (String blocked : new String[] {"accessBlocked", "awaitingLogin"}) {
                    set(blocked, true);
                    call("updateFavorite");
                    Button button = ((Button[]) get("buttons"))[6];
                    check("aspect_" + blocked + "_disabled", !button.isEnabled());
                    button.performClick();
                    check("aspect_" + blocked + "_does_not_change", ((ReelsPlayback) get("nativeVideo")).isAutoZoom());
                    set(blocked, false);
                }
            });
        } finally {
            runOnMainSync(() -> activity.finish());
            waitForIdleSync();
        }
        open(true);
        try {
            runOnMainSync(() -> {
                ((AlertDialog) get("pageDialog")).dismiss();
                startLocalVideo(fixture, reel);
                check("aspect_reopen_restores_original", !((ReelsPlayback) get("nativeVideo")).isAutoZoom());
            });
            waitForFirstFrame("aspect_reopen_first_frame");
        } finally {
            runOnMainSync(() -> activity.finish());
            waitForIdleSync();
        }
    }

    private void updateLocalActionRoute(WebViewClient navigation, String url) throws InterruptedException {
        CountDownLatch changed = new CountDownLatch(1);
        runOnMainSync(() -> {
            WebView web = (WebView) get("web");
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String loadedUrl) {
                    if (!url.equals(view.getUrl())) return;
                    navigation.doUpdateVisitedHistory(view, url, false);
                    changed.countDown();
                }
            });
            // Data documents keep their history URL after replaceState on this TV's WebView.
            web.loadDataWithBaseURL(url, "<html><script>window.TVReels={like:function(){return true},"
                            + "notInterested:function(){return true},next:function(){return true},"
                            + "previous:function(){return true},cancelActions:function(){},state:function(){}};</script></html>",
                    "text/html", "UTF-8", url);
        });
        check("actions_local_history_ready_" + Uri.parse(url).getLastPathSegment(), changed.await(5, TimeUnit.SECONDS));
    }

    private String actionRequest(int index) {
        String[] value = new String[1];
        runOnMainSync(() -> value[0] = ((String[]) get("actionRequests"))[index]);
        return value[0];
    }


    private void checkFavoriteReturn() {
        File fixture = new File(getTargetContext().getCacheDir(), "reels-playback-test/portrait.mp4");
        String reel = "https://www.instagram.com/reel/OfflineFavoriteReturn/";
        open(true);
        try {
            runOnMainSync(() -> {
                ((AlertDialog) get("pageDialog")).dismiss();
                activity.getPreferences(0).edit().remove("auto_zoom_OfflineFavoriteReturn").commit();
                startLocalVideo(fixture, reel);
            });
            waitForFirstFrame("favorite_before_return_first_frame");
            runOnMainSync(() -> {
                ((Button[]) get("buttons"))[6].performClick();
                check("favorite_original_ratio_selected", !((ReelsPlayback) get("nativeVideo")).isAutoZoom());
                set("shouldPlay", false);
                ((ReelsPlayback) get("nativeVideo")).pause();
                set("leaving", true);
                call("onPause");
                check("favorite_pause_keeps_cached_source", reel.equals(get("cachedReel"))
                        && Uri.fromFile(fixture).toString().equals(get("cachedSource")));
                int position = (int) get("cachedPosition");
                int generation = (int) get("generation");
                WebView web = (WebView) get("web");
                check("favorite_starts_without_page_load", web.getUrl() == null
                        && web.copyBackForwardList().getSize() == 0);
                try {
                    Method result = ReelsTvActivity.class.getDeclaredMethod("onActivityResult",
                            int.class, int.class, Intent.class);
                    result.setAccessible(true);
                    String[] names = {"canceled", "unrelated", "failed", "success"};
                    int[] requests = {7301, 7302, 7301, 7301};
                    int[] results = {Activity.RESULT_CANCELED, Activity.RESULT_OK, Activity.RESULT_FIRST_USER,
                            Activity.RESULT_OK};
                    for (int i = 0; i < names.length; i++) {
                        set("shouldPlay", false);
                        result.invoke(activity, requests[i], results[i], null);
                        check("favorite_" + names[i] + "_play_intent", (boolean) get("shouldPlay") == (i == 3));
                        check("favorite_" + names[i] + "_waits_for_resume", get("nativeVideo") == null
                                && !(boolean) get("foreground") && (boolean) get("leaving")
                                && (int) get("generation") == generation);
                        check("favorite_" + names[i] + "_keeps_current_cache", reel.equals(get("currentURL"))
                                && reel.equals(get("cachedReel"))
                                && Uri.fromFile(fixture).toString().equals(get("cachedSource"))
                                && (int) get("cachedPosition") == position);
                        check("favorite_" + names[i] + "_no_page_or_embed_load", web.getUrl() == null
                                && web.copyBackForwardList().getSize() == 0
                                && get("resolver") == null && get("sourceWait") == null);
                    }
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                call("onResume");
                check("favorite_resume_uses_current_cache", get("nativeVideo") != null
                        && reel.equals(get("nativeReel")) && reel.equals(get("currentURL"))
                        && (boolean) get("shouldPlay") && !(boolean) get("leaving"));
                check("favorite_resume_no_page_or_embed_load", web.getUrl() == null
                        && web.copyBackForwardList().getSize() == 0
                        && get("resolver") == null && get("sourceWait") == null);
            });
            waitForFirstFrame("favorite_return_restores_first_frame");
            runOnMainSync(() -> {
                check("favorite_return_resumes_playback", ((ReelsPlayback) get("nativeVideo")).isPlaying());
                check("favorite_return_keeps_original_ratio", !((ReelsPlayback) get("nativeVideo")).isAutoZoom()
                        && "画面：原始比例".contentEquals(((Button[]) get("buttons"))[6].getText()));
            });
        } finally {
            runOnMainSync(() -> activity.finish());
            waitForIdleSync();
        }
    }

    private void startLocalVideo(File fixture, String reel) {
        call("stopNative");
        set("accessBlocked", false);
        set("awaitingLogin", false);
        set("pageFailed", false);
        set("mediaFailed", false);
        set("pointerMode", false);
        set("pageReady", true);
        set("currentURL", reel);
        set("nativeReel", reel);
        ((FrameLayout) get("nativeLayer")).setVisibility(View.VISIBLE);
        try {
            Method start = ReelsTvActivity.class.getDeclaredMethod("startNativeVideo", String.class, int.class, String.class);
            start.setAccessible(true);
            start.invoke(activity, Uri.fromFile(fixture).toString(), (int) get("generation"), reel);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private void waitForFirstFrame(String name) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        boolean[] ready = {false};
        do {
            runOnMainSync(() -> {
                ReelsPlayback playback = (ReelsPlayback) get("nativeVideo");
                ready[0] = playback != null && playback.hasFirstFrame()
                        && SystemClock.elapsedRealtime() <= deadline;
            });
            if (ready[0]) break;
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) break;
            SystemClock.sleep(Math.min(50, remaining));
        } while (SystemClock.elapsedRealtime() < deadline);
        check(name, ready[0]);
    }

    private void call(String name) {
        try {
            Method method = ReelsTvActivity.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(activity);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private void open(boolean offline) {
        activity = (ReelsTvActivity) startActivitySync(new Intent(getTargetContext(), ReelsTvActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("offline_failure_check", offline));
        waitForIdleSync();
    }

    private void check(String name, boolean passed) {
        if (!passed) throw new AssertionError(name);
        checks.put(name);
    }

    private Object get(String name) {
        try {
            Field field = ReelsTvActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private void set(String name, Object value) {
        try {
            Field field = ReelsTvActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(activity, value);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
