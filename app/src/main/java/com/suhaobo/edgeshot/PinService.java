package com.suhaobo.edgeshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 「贴图」：把图片钉在屏幕上（悬浮窗）。
 *
 * 一个前台服务管所有贴图窗口；关掉最后一张贴图时服务自己停下。
 * 需要「显示在其他应用上层」权限（Settings.canDrawOverlays）。
 */
public class PinService extends Service implements PinView.Host {

    public static final String ACTION_PIN = "com.suhaobo.edgeshot.action.PIN";
    public static final String ACTION_REPLACE = "com.suhaobo.edgeshot.action.REPLACE";
    public static final String ACTION_CLEAR = "com.suhaobo.edgeshot.action.CLEAR";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_PIN_ID = "pinId";

    private static final String CHANNEL = "edge_diary_pin";
    private static final int NOTI_ID = 4211;

    private final List<PinView> pins = new ArrayList<>();
    /** 每张贴图的窗口参数（置顶时要原样加回去） */
    private final java.util.Map<PinView, WindowManager.LayoutParams> lps = new java.util.HashMap<>();
    private int nextId = 1;

    /* ---------------- 对外入口 ---------------- */

    public static boolean canOverlay(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return Settings.canDrawOverlays(ctx);
        return true;
    }

    /** 贴一张图（bitmap 会先落到缓存文件再交给服务） */
    public static void pin(Context ctx, Bitmap bmp) {
        String path = PinView.saveCache(ctx, bmp);
        if (path == null) return;
        Intent i = new Intent(ctx, PinService.class);
        i.setAction(ACTION_PIN);
        i.putExtra(EXTRA_PATH, path);
        start(ctx, i);
    }

    /** 用剪贴板里的东西替换某张贴图的内容 */
    public static void replace(Context ctx, int pinId, Bitmap bmp) {
        String path = PinView.saveCache(ctx, bmp);
        if (path == null) return;
        Intent i = new Intent(ctx, PinService.class);
        i.setAction(ACTION_REPLACE);
        i.putExtra(EXTRA_PATH, path);
        i.putExtra(EXTRA_PIN_ID, pinId);
        start(ctx, i);
    }

    private static void start(Context ctx, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception e) {
            try { ctx.startService(i); } catch (Exception ignored) { /* 起不来就算了 */ }
        }
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureForeground();
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_PIN.equals(action)) {
                Bitmap bmp = PinView.loadFile(intent.getStringExtra(EXTRA_PATH));
                if (bmp != null) addPin(bmp);
            } else if (ACTION_REPLACE.equals(action)) {
                Bitmap bmp = PinView.loadFile(intent.getStringExtra(EXTRA_PATH));
                int id = intent.getIntExtra(EXTRA_PIN_ID, -1);
                if (bmp != null && id > 0) {
                    for (PinView v : new ArrayList<>(pins)) {
                        if (v.getId() == id) { v.setBitmap(bmp); break; }
                    }
                }
            } else if (ACTION_CLEAR.equals(action)) {
                for (PinView v : new ArrayList<>(pins)) removePin(v);
            }
        }
        if (pins.isEmpty()) stopSelf();
        return START_NOT_STICKY;
    }

    private void ensureForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                    NotificationChannel ch = new NotificationChannel(CHANNEL, "贴图", NotificationManager.IMPORTANCE_LOW);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            }
            Intent clear = new Intent(this, PinService.class).setAction(ACTION_CLEAR);
            PendingIntent pi = PendingIntent.getService(this, 9, clear,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL)
                    : new Notification.Builder(this);
            b.setContentTitle("贴图")
                    .setContentText("有图片钉在屏幕上，点这里可以全部关掉")
                    .setSmallIcon(R.drawable.ic_shot_pin)
                    .setContentIntent(pi)
                    .setOngoing(true);
            startForeground(NOTI_ID, b.build());
        } catch (Exception e) {
            /* 没有通知权限也不影响贴图 */
        }
    }

    /* ---------------- 窗口 ---------------- */

    private WindowManager wm() {
        return (WindowManager) getSystemService(Context.WINDOW_SERVICE);
    }

    private void addPin(Bitmap bmp) {
        WindowManager wm = wm();
        if (wm == null) return;

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        PinView view = new PinView(this, bmp, this);
        view.setId(nextId++);

        // 初始大小：不超过屏幕宽的 72%
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float ratio = (float) bmp.getHeight() / Math.max(1, bmp.getWidth());
        int w = Math.round(screenW * 0.72f);
        int h = Math.round(w * ratio);
        if (h > screenH * 0.72f) {
            h = Math.round(screenH * 0.72f);
            w = Math.round(h / ratio);
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int offset = (pins.size() % 5) * Math.round(24 * getResources().getDisplayMetrics().density);
        lp.x = Math.round(screenW * 0.12f) + offset;
        lp.y = Math.round(screenH * 0.18f) + offset;

        try {
            wm.addView(view, lp);
            pins.add(view);
            lps.put(view, lp);
        } catch (Exception e) {
            /* 权限被撤了就贴不上 */
        }
    }

    private void removePin(PinView v) {
        try {
            WindowManager wm = wm();
            if (wm != null) wm.removeView(v);
        } catch (Exception ignored) { }
        pins.remove(v);
        lps.remove(v);
        if (pins.isEmpty()) stopSelf();
    }

    /* ---------------- PinView.Host ---------------- */

    @Override
    public void onClose(PinView v) {
        removePin(v);
    }

    @Override
    public void onReplaceFromClipboard(PinView v) {
        // 读剪贴板需要 App 在前台（Android 10+ 的限制），所以借一个透明 Activity 来读
        try {
            Intent i = new Intent(this, ClipboardActivity.class);
            i.putExtra(EXTRA_PIN_ID, v.getId());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    @Override
    public void onCopy(PinView v) {
        Bitmap bmp = v.getBitmap();
        if (bmp == null) return;
        // 交给有焦点的透明界面去写剪贴板。贴图是悬浮窗（服务里），虽然在本机实测上直接写也能成，
        // 但 Android 10+ 对后台 App 的剪贴板读写本来就有「必须在前台」的限制（国产 ROM 更严），
        // 走前台界面是平台保证可行的做法，不依赖「有可见悬浮窗」这种豁免。
        Uri uri = ShotStore.cacheFile(this, bmp);
        if (uri == null) return;
        try {
            Intent i = new Intent(this, ClipboardActivity.class);
            i.putExtra(ClipboardActivity.EXTRA_CLIP_URI, uri.toString());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    @Override
    public void onSave(PinView v) {
        Bitmap bmp = v.getBitmap();
        if (bmp != null) ShotStore.saveToGallery(this, bmp, null);
    }

    /**
     * 把某张贴图挪到最上面。
     * 悬浮窗的层级是「后加的在上面」，updateViewLayout 不会改变顺序，
     * 所以只能摘下来重新加一次（多张贴图叠在一起时才需要动）。
     */
    @Override
    public void onBringToFront(PinView v) {
        if (pins.size() < 2 || pins.indexOf(v) == pins.size() - 1) return;
        WindowManager wm = wm();
        if (wm == null) return;
        WindowManager.LayoutParams lp = lps.get(v);
        if (lp == null) return;
        try {
            wm.removeView(v);
        } catch (Exception e) {
            return;
        }
        try {
            wm.addView(v, lp);
            pins.remove(v);
            pins.add(v);
        } catch (Exception ignored) { /* 加回去失败就算了 */ }
    }

    @Override
    public void onDestroy() {
        for (PinView v : new ArrayList<>(pins)) {
            try {
                WindowManager wm = wm();
                if (wm != null) wm.removeView(v);
            } catch (Exception ignored) { }
        }
        pins.clear();
        lps.clear();
        super.onDestroy();
    }
}
