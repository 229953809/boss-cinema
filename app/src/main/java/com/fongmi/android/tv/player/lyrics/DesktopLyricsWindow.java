package com.fongmi.android.tv.player.lyrics;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.custom.LyricsOverlayView;

public class DesktopLyricsWindow {

    private static final long UPDATE_INTERVAL_MS = 500L;

    private final WindowManager windowManager;
    private final Context context;
    private final Runnable tick = this::onTick;

    private LyricsController controller;
    private LyricsOverlayView view;
    private PlayerManager player;
    private boolean attached;
    private boolean foreground = true;

    public DesktopLyricsWindow(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void setForeground(boolean foreground) {
        this.foreground = foreground;
        update(player, true);
    }

    public void refresh(PlayerManager player) {
        update(player, true);
    }

    public void update(PlayerManager player) {
        update(player, false);
    }

    public void release() {
        hide();
        if (controller != null) controller.release();
        controller = null;
        view = null;
        player = null;
    }

    private void update(PlayerManager player, boolean refresh) {
        this.player = player;
        if (!canShow(player)) {
            hide();
            return;
        }
        ensureAttached();
        if (!attached || view == null) return;
        if (controller == null) controller = new LyricsController(view);
        if (refresh) controller.refresh(player, true);
        controller.update(player);
        schedule();
    }

    private boolean canShow(PlayerManager player) {
        if (foreground || App.activity() != null) return false;
        if (!PlayerSetting.isDesktopLyrics()) return false;
        if (!canDrawOverlays()) return false;
        if (player == null || player.isEmpty() || !player.isPlaying()) return false;
        return LyricsController.isAudioContent(player);
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    private void ensureAttached() {
        if (windowManager == null) return;
        if (view == null) view = new LyricsOverlayView(context);
        if (attached) return;
        try {
            windowManager.addView(view, buildParams());
            attached = true;
        } catch (Throwable ignored) {
            attached = false;
        }
    }

    private WindowManager.LayoutParams buildParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(56);
        params.format = PixelFormat.TRANSLUCENT;
        params.type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        return params;
    }

    private void onTick() {
        update(player, false);
    }

    private void schedule() {
        App.post(tick, UPDATE_INTERVAL_MS);
    }

    private void hide() {
        App.removeCallbacks(tick);
        if (controller != null) controller.clear();
        if (!attached || windowManager == null || view == null) return;
        try {
            windowManager.removeView(view);
        } catch (Throwable ignored) {
        }
        attached = false;
    }

    private int dp(float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
