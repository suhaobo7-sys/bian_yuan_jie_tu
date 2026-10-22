package com.suhaobo.edgeshot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 长截图进行中右上角的小窗（照着电脑版微信的长截图来）：
 *   · 实时显示已经拼出来的长图缩略图，滑一点它就长一点
 *   · 底下两个按钮：完成 / 取消
 *
 * ⚠ 这个窗口会被 MediaProjection 一起录进去，所以**只在用户停手的时候才显示**，
 *   显示期间抓到的帧一律丢弃（见 LongShotService 的 tainted 处理）。
 *
 * ⚠⚠ 窗口只 addView 一次，之后显示/隐藏只切 visibility：
 *   实测反复 addView/removeView（长截图里一停手就显示、一滑动就收起，一轮能循环十几次）
 *   会把窗口搞成「HAS_DRAWN 但 surface 是空的」，屏幕上就是一整块纯黑，
 *   缩略图明明是白的也救不回来。visibility 切换不碰 surface，没这个毛病。
 */
public class LongShotOverlay {

    private static final String TAG = "EdgeDiaryShot";

    public interface Listener {
        void onFinish();
        void onCancel();
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Listener listener;
    private final float density;
    /** 窗口操作一律回主线程：addView / removeViewImmediate / setImageBitmap 在抓帧线程上跑，
     *  ViewRootImpl 会建在那个线程上，硬件加速渲染出来是一块纯黑（缩略图明明是白的）。
     *  第二个纯黑原因（反复 add/remove）见类注释，靠「只 add 一次 + 切 visibility」解决。 */
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private ImageView thumb;
    private TextView info;
    private boolean shown;

    public LongShotOverlay(Context c, Listener l) {
        ctx = c.getApplicationContext();
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        listener = l;
        density = ctx.getResources().getDisplayMetrics().density;
    }

    public static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(c);
    }

    /** 更新缩略图和进度文字（调用前先确认 isShown()） */
    public void update(final Bitmap thumbnail, final int heightPx, final int frames) {
        main.post(new Runnable() {
            @Override public void run() { updateOnMain(thumbnail, heightPx, frames); }
        });
    }

    private void updateOnMain(Bitmap thumbnail, int heightPx, int frames) {
        if (!shown || root == null) return;
        if (thumb != null && thumbnail != null) thumb.setImageBitmap(thumbnail);
        if (info != null) {
            info.setText("已截 " + frames + " 屏 · 高 " + heightPx + "px");
        }
    }
    public boolean isShown() {
        return shown;
    }

    public void show(final Bitmap thumbnail) {
        main.post(new Runnable() {
            @Override public void run() { showOnMain(thumbnail); }
        });
    }

    /**
     * 服务一启动就把窗口加好（先不可见），等有内容了再 show。
     *
     * ⚠ 顺序很重要：实测等 MediaProjection 把虚拟屏建起来之后再 addView，
     *   窗口会一直是「HAS_DRAWN 但整块纯黑」（view 侧 attached/测量/显示全正常，
     *   SurfaceFlinger 里 layer 也在、合成位置尺寸也对，就是 buffer 是黑的）。
     *   先把窗口加好再建虚拟屏没这个毛病，所以开工前先预热。
     */
    public void prewarm() {
        main.post(new Runnable() {
            @Override public void run() {
                if (root != null || wm == null || !canDraw(ctx)) return;
                try {
                    root = buildRoot(null);
                    wm.addView(root, windowParams());
                    root.setVisibility(View.GONE);
                    android.util.Log.d(TAG, "小窗预热完成（GONE，等有内容再显示）");
                } catch (Exception e) {
                    android.util.Log.w(TAG, "小窗预热失败 " + e);
                    root = null;
                    thumb = null;
                    info = null;
                }
            }
        });
    }

    private void showOnMain(Bitmap thumbnail) {
        if (wm == null || !canDraw(ctx)) return;
        try {
            if (root == null || !root.isAttachedToWindow()) {
                root = buildRoot(thumbnail);
                wm.addView(root, windowParams());
            } else if (thumbnail != null && thumb != null) {
                thumb.setImageBitmap(thumbnail);
            }
            root.setVisibility(View.VISIBLE);
            shown = true;
        } catch (Exception e) {
            android.util.Log.w(TAG, "小窗显示失败 " + e);
            shown = false;
            // addView 可能加了一半就炸了：试着收干净，下次重新建
            try {
                if (root != null && root.isAttachedToWindow()) wm.removeViewImmediate(root);
            } catch (Exception ignored) { }
            root = null;
            thumb = null;
            info = null;
        }
    }

    /** 建小窗的界面（只在整个生命周期里建一次） */
    private LinearLayout buildRoot(Bitmap thumbnail) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(bubbleBg());
        int pad = dp(6);
        box.setPadding(pad, pad, pad, pad);

        thumb = new ImageView(ctx);
        thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (thumbnail != null) thumb.setImageBitmap(thumbnail);
        // ⚠ 高度必须写死：adjustViewBounds 会让小窗跟着长图的宽高比无限拉高
        //   （725×3426 的图能拉到 1200px+，把整块屏幕都盖住）。
        //   缩略图本身就是按 104×220dp 生成的，写死尺寸再由 FIT_CENTER 居中即可。
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(104), dp(220));
        tp.bottomMargin = dp(4);
        box.addView(thumb, tp);

        info = new TextView(ctx);
        info.setTextColor(0xFFE8E6DF);
        info.setTextSize(10f);
        info.setGravity(Gravity.CENTER);
        info.setText("滑动屏幕开始长截图");
        box.addView(info, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(mkButton("完成", 0xFF9AD3A8, new Runnable() {
            @Override public void run() { if (listener != null) listener.onFinish(); }
        }));
        row.addView(mkButton("取消", 0xFFFF6B6B, new Runnable() {
            @Override public void run() { if (listener != null) listener.onCancel(); }
        }));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(4);
        box.addView(row, rp);
        return box;
    }

    private WindowManager.LayoutParams windowParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(8);
        lp.y = statusBarHeight() + dp(8);
        return lp;
    }

    public void hide() {
        main.post(new Runnable() {
            @Override public void run() { hideOnMain(); }
        });
    }

    /** 只切 visibility，不 removeView —— 理由见类注释 */
    private void hideOnMain() {
        if (root != null) {
            try {
                root.setVisibility(View.GONE);
            } catch (Exception ignored) { }
        }
        shown = false;
    }

    /** 长截图收工：真正把窗口撤掉（此时不再有显示/隐藏循环） */
    public void release() {
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    if (wm != null && root != null && root.isAttachedToWindow()) {
                        wm.removeViewImmediate(root);
                    }
                } catch (Exception ignored) { }
                root = null;
                thumb = null;
                info = null;
                shown = false;
            }
        });
    }

    private TextView mkButton(String text, int color, final Runnable action) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextColor(color);
        b.setTextSize(11f);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(0x33181A23);
        b.setBackground(g);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.rightMargin = dp(3);
        p.leftMargin = dp(3);
        b.setLayoutParams(p);
        b.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                action.run();
            }
        });
        return b;
    }

    private GradientDrawable bubbleBg() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(0xE6181A23);
        g.setStroke(Math.max(1, Math.round(density)), 0x66FFB454);
        return g;
    }

    private int statusBarHeight() {
        try {
            int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return ctx.getResources().getDimensionPixelSize(id);
        } catch (Exception ignored) { }
        return dp(24);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
