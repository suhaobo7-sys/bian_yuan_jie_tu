package com.suhaobo.edgeshot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 一张「贴图」：钉在屏幕上的图片窗口。
 *
 * 交互（照着 Snipaste 的习惯来）：
 *   · 拖动 → 挪位置
 *   · 双指捏合 → 缩放
 *   · 双击 → 关掉
 *   · 单击 → 弹出小工具条：替换（用剪贴板内容）/ 复制 / 保存 / 关闭
 */
public class PinView extends FrameLayout {

    public interface Host {
        void onClose(PinView v);
        void onReplaceFromClipboard(PinView v);
        void onCopy(PinView v);
        void onSave(PinView v);
        /** 把这张贴图挪到最上层（有多张贴图时，点哪张哪张在最上面） */
        void onBringToFront(PinView v);
    }

    private final ImageView image;
    private final LinearLayout bar;
    private final ImageView handle;   // 右下角的缩放把手（比双指捏合好找）
    private Bitmap bitmap;
    private final Host host;
    private final int touchSlop;
    private final float density;

    private float downRawX, downRawY;
    private int startX, startY;
    private boolean dragging;
    private boolean moved;
    private long downTime;
    private long lastTapTime;

    private final ScaleGestureDetector scaleDetector;
    private float scaleStartW, scaleStartH;

    /** 拖把手缩放：按下时的窗口尺寸和按下的屏幕坐标 */
    private boolean resizing;
    private int resizeStartW, resizeStartH;
    private float resizeDownX, resizeDownY;

    public PinView(Context ctx, Bitmap bmp, Host host) {
        super(ctx);
        this.host = host;
        this.bitmap = bmp;
        density = ctx.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();

        setBackgroundColor(0x00000000);
        setClipToPadding(false);
        setPadding(dp(3), dp(3), dp(3), dp(3));

        image = new ImageView(ctx);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(bmp);
        LayoutParams ip = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(image, ip);

        bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundResource(R.drawable.shot_toolbar_bg);
        bar.setPadding(dp(2), dp(2), dp(2), dp(2));
        bar.setVisibility(GONE);
        LayoutParams bp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.TOP | Gravity.END;
        bp.topMargin = dp(6);
        bp.rightMargin = dp(6);
        addView(bar, bp);

        // 右下角把手：一眼就能看出「这里能拖大拖小」
        handle = new ImageView(ctx);
        handle.setImageResource(R.drawable.pin_resize_handle);
        handle.setAlpha(0.9f);
        LayoutParams hp = new LayoutParams(dp(26), dp(26));
        hp.gravity = Gravity.BOTTOM | Gravity.END;
        addView(handle, hp);

        addBarButton(R.drawable.ic_shot_paste, "替换", new OnClickListener() {
            @Override public void onClick(View v) { hideBar(); host.onReplaceFromClipboard(PinView.this); }
        });
        addBarButton(R.drawable.ic_shot_copy, "复制", new OnClickListener() {
            @Override public void onClick(View v) { hideBar(); host.onCopy(PinView.this); }
        });
        addBarButton(R.drawable.ic_shot_save, "保存", new OnClickListener() {
            @Override public void onClick(View v) { hideBar(); host.onSave(PinView.this); }
        });
        addBarButton(R.drawable.ic_shot_close, "关闭", new OnClickListener() {
            @Override public void onClick(View v) { host.onClose(PinView.this); }
        });

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                scaleStartW = getWidth();
                scaleStartH = getHeight();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float f = d.getScaleFactor();
                float w = clampSize(scaleStartW * f);
                float h = w * ((float) getHeight() / Math.max(1, getWidth()));
                setSize((int) w, (int) h);
                return true;
            }
        });
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    /** 换掉内容（例如用剪贴板里的东西替换） */
    public void setBitmap(Bitmap bmp) {
        if (bmp == null) return;
        bitmap = bmp;
        image.setImageBitmap(bmp);
        // 按新内容的比例重排窗口大小
        int w = getWidth(), h = getHeight();
        if (w > 0 && h > 0) {
            float ratio = (float) bmp.getHeight() / Math.max(1, bmp.getWidth());
            setSize(w, (int) clampSize(w * ratio));
        }
        requestLayout();
    }

    private float clampSize(float v) {
        float max = getResources().getDisplayMetrics().widthPixels * 1.6f;
        return Math.max(dp(80), Math.min(v, max));
    }

    private void setSize(int w, int h) {
        WindowManager.LayoutParams lp = wmParams();
        if (lp == null) return;
        lp.width = w;
        lp.height = h;
        try {
            wm().updateViewLayout(this, lp);
        } catch (Exception ignored) { /* 窗口没了就算了 */ }
    }

    private WindowManager wm() {
        return (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
    }

    private WindowManager.LayoutParams wmParams() {
        android.view.ViewGroup.LayoutParams lp = getLayoutParams();
        return (lp instanceof WindowManager.LayoutParams) ? (WindowManager.LayoutParams) lp : null;
    }

    private void addBarButton(int icon, String desc, OnClickListener l) {
        ImageView b = new ImageView(getContext());
        b.setImageResource(icon);
        b.setColorFilter(Color.parseColor("#E8E6DF"));
        b.setContentDescription(desc);
        b.setBackgroundResource(R.drawable.shot_btn_bg);
        b.setPadding(dp(9), dp(9), dp(9), dp(9));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(40), dp(40));
        bar.addView(b, p);
    }

    private void showBar() {
        bar.setVisibility(VISIBLE);
        bar.bringToFront();
    }

    private void hideBar() {
        bar.setVisibility(GONE);
    }

    public boolean isBarShown() {
        return bar.getVisibility() == VISIBLE;
    }

    /** 右下角把手的热区（比图标本身大一圈，手指好按） */
    private boolean hitHandle(float x, float y) {
        if (handle == null || getWidth() <= 0) return false;
        float s = dp(40);
        return x >= getWidth() - s && y >= getHeight() - s;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /* ---------------- 触摸：拖动 / 缩放 / 双击关闭 / 单击弹工具条 ---------------- */

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                WindowManager.LayoutParams lp0 = wmParams();
                startX = lp0 == null ? getLeft() : lp0.x;
                startY = lp0 == null ? getTop() : lp0.y;
                dragging = false;
                moved = false;
                downTime = System.currentTimeMillis();
                // 按在右下角把手上 → 拖着改大小
                resizing = hitHandle(e.getX(), e.getY());
                if (resizing) {
                    resizeDownX = e.getRawX();
                    resizeDownY = e.getRawY();
                    resizeStartW = getWidth();
                    resizeStartH = getHeight();
                } else if (host != null) {
                    host.onBringToFront(this);   // 点哪张哪张就在最上面
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (scaleDetector.isInProgress()) return true;
                if (resizing) {
                    float ratio = (float) resizeStartH / Math.max(1, resizeStartW);
                    // 取对角线方向的变化量，往右下拖就是变大
                    float d = ((e.getRawX() - resizeDownX) + (e.getRawY() - resizeDownY)) / 2f;
                    int w = Math.round(clampSize(resizeStartW + d));
                    setSize(w, Math.round(w * ratio));
                    moved = true;
                    return true;
                }
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) dragging = true;
                if (dragging) {
                    moved = true;
                    WindowManager.LayoutParams lp2 = wmParams();
                    if (lp2 != null) {
                        lp2.x = startX + (int) dx;
                        lp2.y = startY + (int) dy;
                        try {
                            wm().updateViewLayout(this, lp2);
                        } catch (Exception ignored) { }
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                resizing = false;
                if (!moved && !scaleDetector.isInProgress()) {
                    long now = System.currentTimeMillis();
                    if (now - lastTapTime < 280) {      // 双击：关掉
                        lastTapTime = 0;
                        host.onClose(this);
                    } else {                            // 单击：工具条开关
                        lastTapTime = now;
                        if (isBarShown()) hideBar(); else showBar();
                    }
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    /* ---------------- 把剪贴板里的文字画成一张图（Snipaste 的「文字也能贴」） ---------------- */

    /** 用文字生成一张贴图（浅底深字，像一张纸条；用 App 的像素字体） */
    public static Bitmap renderText(Context ctx, String text) {
        int maxW = 900;
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFF23252F);
        tp.setTextSize(34f);
        Typeface tf = null;
        try {
            tf = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.zpix);
        } catch (Exception ignored) { /* 拿不到就用等宽字体 */ }
        tp.setTypeface(tf == null ? Typeface.MONOSPACE : tf);
        StaticLayout layout = StaticLayout.Builder
                .obtain(text, 0, text.length(), tp, maxW)
                .setLineSpacing(6f, 1f)
                .build();
        int pad = 28;
        int w = maxW + pad * 2;
        int h = layout.getHeight() + pad * 2;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFFF5F3EC);
        Paint border = new Paint();
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(3f);
        border.setColor(0xFFB9B6AC);
        c.drawRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, border);
        c.save();
        c.translate(pad, pad);
        layout.draw(c);
        c.restore();
        return bmp;
    }

    /** 保存一张 bitmap 到缓存文件，返回路径（跨进程传图用） */
    public static String saveCache(Context ctx, Bitmap bmp) {
        try {
            File dir = new File(ctx.getCacheDir(), "shots");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File f = new File(dir, "pin-" + ShotStore.stamp() + "-" + System.nanoTime() % 1000 + ".png");
            try (OutputStream os = new FileOutputStream(f)) {
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) return null;
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    public static Bitmap loadFile(String path) {
        try {
            return BitmapFactory.decodeFile(path);
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------------- 读剪贴板（「粘贴」用） ---------------- */

    /**
     * 读剪贴板里的内容：有图片就返回图片，只有文字就把文字画成一张图。
     * ⚠ 必须在前台调用（Android 10+ 限制后台 App 读剪贴板）。
     */
    public static Bitmap readClipboard(android.content.Context ctx) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            android.content.ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            android.content.ClipData.Item item = clip.getItemAt(0);
            if (item == null) return null;

            android.net.Uri uri = item.getUri();
            if (uri != null) {
                Bitmap bmp = decodeUri(ctx, uri);
                if (bmp != null) return bmp;
            }
            CharSequence text = null;
            try {
                text = item.coerceToText(ctx);
            } catch (Exception ignored) { }
            if (text != null) {
                String s = text.toString().trim();
                if (!s.isEmpty()) return renderText(ctx, s);
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 从 content:// 解码图片，太大就先采样，避免 OOM */
    private static Bitmap decodeUri(android.content.Context ctx, android.net.Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;

            int max = 2000;
            int sample = 1;
            while (o.outWidth / sample > max || o.outHeight / sample > max) sample *= 2;

            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                return BitmapFactory.decodeStream(in, null, o2);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
