package com.suhaobo.edgeshot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 长截图结果：先给你看一眼（可以上下滚），再决定保存 / 复制 / 贴图。
 *
 * 预览用的是一张缩小过的图（大图直接铺进 View 会超出 GPU 纹理上限），
 * 保存/复制用的还是原始分辨率那张。
 */
public class LongShotPreviewActivity extends Activity {

    /** 预览图最大高度（像素），避免超过纹理上限画不出来 */
    private static final int PREVIEW_MAX_H = 3000;

    private Bitmap full;
    private Bitmap preview;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.shot_preview);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        String path = getIntent().getStringExtra(PinService.EXTRA_PATH);
        full = PinView.loadFile(path);
        if (full == null) {
            toast("读取长截图失败");
            finish();
            return;
        }

        preview = full;
        try {
            if (full.getHeight() > PREVIEW_MAX_H) {
                float s = (float) PREVIEW_MAX_H / full.getHeight();
                preview = Bitmap.createScaledBitmap(full,
                        Math.max(1, Math.round(full.getWidth() * s)), PREVIEW_MAX_H, true);
            }
        } catch (Exception | OutOfMemoryError e) {
            preview = full;
        }

        ImageView img = findViewById(R.id.shot_preview_image);
        img.setImageBitmap(preview);

        setupThumbnail((android.widget.ScrollView) findViewById(R.id.shot_preview_scroll), img);

        findViewById(R.id.shot_preview_save).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Uri u = ShotStore.saveToGallery(LongShotPreviewActivity.this, full, null);
                toast(u == null ? "保存失败" : "已保存到相册");
                finish();
            }
        });
        findViewById(R.id.shot_preview_copy).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean ok = ShotStore.copyToClipboard(LongShotPreviewActivity.this, full);
                toast(ok ? "已复制" : "复制失败");
                finish();
            }
        });
        findViewById(R.id.shot_preview_pin).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!PinService.canOverlay(LongShotPreviewActivity.this)) {
                    toast("贴图需要「显示在其他应用上层」权限");
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                    finish();
                    return;
                }
                PinService.pin(LongShotPreviewActivity.this, full);
                finish();
            }
        });
        findViewById(R.id.shot_preview_close).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    /* ---------------- 整图缩略条 ---------------- */

    /**
     * 右上角给一条整图缩略条：一眼看到长图全貌，上面的小框是当前看到的位置，
     * 手指在缩略条上点/拖一下就跳到长图的对应位置。图不够长就不显示。
     */
    private void setupThumbnail(final android.widget.ScrollView scroll, final ImageView img) {
        final LinearLayout box = findViewById(R.id.shot_preview_thumbbox);
        final ImageView thumb = findViewById(R.id.shot_preview_thumb);
        final View win = findViewById(R.id.shot_preview_thumb_win);
        final TextView sizeText = findViewById(R.id.shot_preview_size);
        if (box == null || thumb == null || win == null || scroll == null) return;
        if (full == null || full.getHeight() < 1400) return;   // 一屏都不到就没什么可导航的

        final Bitmap thumbBmp;
        try {
            float s = Math.min(dp(68) / (float) full.getWidth(), dp(210) / (float) full.getHeight());
            thumbBmp = Bitmap.createScaledBitmap(full,
                    Math.max(1, Math.round(full.getWidth() * s)),
                    Math.max(1, Math.round(full.getHeight() * s)), true);
        } catch (Exception | OutOfMemoryError e) {
            return;
        }
        thumb.setImageBitmap(thumbBmp);
        box.setVisibility(View.VISIBLE);
        if (sizeText != null) sizeText.setText(full.getWidth() + " × " + full.getHeight());

        final Runnable sync = new Runnable() {
            @Override public void run() { syncWindow(scroll, img, thumb, win); }
        };
        thumb.post(sync);
        scroll.getViewTreeObserver().addOnScrollChangedListener(
                new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    @Override public void onScrollChanged() { sync.run(); }
                });

        // 在缩略条上点 / 拖 = 跳到长图的对应位置
        thumb.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                int a = e.getActionMasked();
                if (a != android.view.MotionEvent.ACTION_DOWN
                        && a != android.view.MotionEvent.ACTION_MOVE) return true;
                int th = thumb.getHeight();
                if (th <= 0) return true;
                float ratio = e.getY() / (float) th;
                ratio = Math.max(0f, Math.min(1f, ratio));
                int maxScroll = Math.max(0, img.getHeight() - scroll.getHeight());
                scroll.scrollTo(0, Math.round(ratio * maxScroll));
                return true;
            }
        });
    }

    /** 把「当前看到哪儿」的小框挪到缩略条上的对应位置 */
    private void syncWindow(android.widget.ScrollView scroll, ImageView img, ImageView thumb, View win) {
        int thumbH = thumb.getHeight();
        if (thumbH <= 0) return;
        int contentH = img.getHeight();
        int viewH = scroll.getHeight();
        if (contentH <= 0) return;
        float visible = contentH <= viewH ? 1f : (float) viewH / (float) contentH;
        int winH = Math.max(dp(12), Math.round(thumbH * visible));
        android.view.ViewGroup.LayoutParams lp = win.getLayoutParams();
        if (lp == null) lp = new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, winH);
        lp.height = winH;
        win.setLayoutParams(lp);
        int maxScroll = Math.max(1, contentH - viewH);
        float ratio = Math.max(0f, Math.min(1f, scroll.getScrollY() / (float) maxScroll));
        win.setTranslationY((thumbH - winH) * ratio);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    @Override
    protected void onDestroy() {
        if (preview != null && preview != full && !preview.isRecycled()) preview.recycle();
        if (full != null && !full.isRecycled()) full.recycle();
        super.onDestroy();
    }
}
