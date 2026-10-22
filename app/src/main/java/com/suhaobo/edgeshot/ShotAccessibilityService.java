package com.suhaobo.edgeshot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/**
 * 「截图 / 长截图」用的无障碍服务。
 *
 * 只做两件很克制的事：
 *   1. takeScreenshot()：静默截屏（Android 11+），不用每次弹录屏授权
 *   2. dispatchGesture()：长截图时模拟一次上滑，把页面滚下去
 * 不读取窗口内容、不监听输入，权限范围见 res/xml/shot_accessibility.xml。
 */
public class ShotAccessibilityService extends AccessibilityService {

    private static ShotAccessibilityService instance;

    public static ShotAccessibilityService get() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        /* 不需要任何事件 */
    }

    @Override
    public void onInterrupt() {
        /* 不需要打断处理 */
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /* ---------------- 截屏 ---------------- */

    public interface ShotCallback {
        /** 成功时 bitmap 非空、error 为 null；失败时 bitmap 为 null */
        void onShot(Bitmap bitmap, String error);
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private void captureApi30(final ShotCallback cb) {
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(@NonNull ScreenshotResult result) {
                Bitmap bmp = null;
                try {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                    if (hw != null) bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                } catch (Exception e) {
                    bmp = null;
                }
                try {
                    result.getHardwareBuffer().close();
                } catch (Exception ignored) { /* 关不掉也无妨 */ }
                cb.onShot(bmp, bmp == null ? "截图失败" : null);
            }

            @Override
            public void onFailure(int errorCode) {
                cb.onShot(null, "截图失败（错误码 " + errorCode + "）");
            }
        });
    }

    /** 截一张当前屏幕 */
    public void capture(final ShotCallback cb) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cb.onShot(null, "系统版本太低（需要 Android 11 及以上）");
            return;
        }
        try {
            captureApi30(cb);
        } catch (Exception e) {
            String m = e.getMessage();
            cb.onShot(null, "截图失败：" + (m == null || m.isEmpty() ? e.getClass().getSimpleName() : m));
        }
    }

    /* ---------------- 通知栏 ---------------- */

    /**
     * 收起通知栏。
     *
     * 磁贴只能从「已经展开的通知栏」里点到，所以点磁贴时通知栏一定是展开的，这时候调这个
     * 全局动作就是「收起」。不收起的话：① 通知栏会一直盖在选区界面上面；
     * ② 更糟的是会被一起截进图里（MIUI 上 startActivityAndCollapse 不一定收得掉）。
     */
    public void collapseNotificationShade() {
        try {
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
        } catch (Exception ignored) { }
    }

    /* ---------------- 滑动（长截图用） ---------------- */

    /**
     * 从 (x, y1) 滑到 (x, y2)，durationMs 毫秒；结束后回调 done（主线程）。
     *
     * 手势偶尔会被系统取消（dispatchGesture 的固有情况，比如刚好有别的输入在跑），所以失败会重试。
     *
     * 试过用 `continueStroke()` 在末尾加一段「几乎不动」的收尾来消除惯性滑动，
     * 但系统会在 1ms 内直接取消这种手势（onCancelled），所以放弃了 —— 现在靠「一次只滑一小段」
     * （LongShot 里 step = 屏高的 1/4）来保证相邻两屏有足够重叠区。
     */
    public void swipe(final int x, final int y1, final int y2, final long durationMs, final Runnable done) {
        final Handler h = new Handler(Looper.getMainLooper());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            if (done != null) h.post(done);
            return;
        }
        swipeAttempt(x, y1, y2, durationMs, 1, done, h);
    }

    private static final int MAX_SWIPE_TRIES = 3;

    private void swipeAttempt(final int x, final int y1, final int y2, final long durationMs,
                              final int attempt, final Runnable done, final Handler h) {
        android.util.Log.d("EdgeDiaryShot", "swipe: 第" + attempt + "次派发 " + x + "," + y1 + " -> " + y2
                + " 时长 " + durationMs);
        boolean ok;
        try {
            Path path = new Path();
            path.moveTo(x, y1);
            path.lineTo(x, y2);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, Math.max(80, durationMs));
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            ok = dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    android.util.Log.d("EdgeDiaryShot", "swipe: 第" + attempt + "次完成");
                    if (done != null) h.post(done);
                }

                @Override
                public void onCancelled(GestureDescription g) {
                    android.util.Log.d("EdgeDiaryShot", "swipe: 第" + attempt + "次被系统取消");
                    retryOrFinish(x, y1, y2, durationMs, attempt, done, h);
                }
            }, null);
        } catch (Exception e) {
            android.util.Log.d("EdgeDiaryShot", "swipe: 第" + attempt + "次异常 " + e);
            ok = false;
        }
        if (!ok) {
            android.util.Log.d("EdgeDiaryShot", "swipe: 第" + attempt + "次没派发出去");
            retryOrFinish(x, y1, y2, durationMs, attempt, done, h);
        }
    }

    private void retryOrFinish(final int x, final int y1, final int y2, final long durationMs,
                               final int attempt, final Runnable done, final Handler h) {
        if (attempt < MAX_SWIPE_TRIES) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    swipeAttempt(x, y1, y2, durationMs, attempt + 1, done, h);
                }
            }, 280);
        } else if (done != null) {
            h.post(done);
        }
    }
}
