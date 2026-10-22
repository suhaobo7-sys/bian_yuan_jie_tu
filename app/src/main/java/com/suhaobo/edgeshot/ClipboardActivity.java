package com.suhaobo.edgeshot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.InputStream;

/**
 * 借前台界面读/写剪贴板的小透明界面。
 *
 * Android 10 起，只有「当前有焦点的 App」才能读**和写**剪贴板；贴图是悬浮窗、属于后台服务
 * （拿不到焦点），所以两个方向都借这个透明 Activity 闪一下：
 *   · 读（替换）：带 {@code EXTRA_PIN_ID} 进来 → 读出剪贴板内容 → 交回 PinService 换掉那张贴图；
 *   · 写（复制）：带 {@code EXTRA_CLIP_URI} 进来 → 把这张图写进剪贴板 → 自己结束。
 */
public class ClipboardActivity extends Activity {

    /** 复制模式：要写进剪贴板的图片（content:// 字符串） */
    public static final String EXTRA_CLIP_URI = "clipUri";

    private boolean handled;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 界面本身什么都不画（主题是透明的），进来读一次就走
    }

    /** 必须等窗口真的拿到焦点再读：Android 10+ 只有「有焦点」的 App 才读得到剪贴板 */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) handle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 兜底：个别机型窗口焦点回调来得晚，稍等一下再试
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() { handle(); }
        }, 350);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleInstance + noHistory：正常都会是新实例，这里只是兜底（例如上一条还没结束又来一条）
        setIntent(intent);
        handled = false;
        handle();
    }

    private void handle() {
        if (handled) return;
        handled = true;

        String clipUri = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_CLIP_URI);
        if (clipUri != null) {
            copyToClipboard(clipUri);
            finish();
            return;
        }

        int pinId = getIntent().getIntExtra(PinService.EXTRA_PIN_ID, -1);
        Bitmap bmp = null;
        try {
            bmp = readClipboard();
        } catch (Exception ignored) { /* 下面统一提示 */ }

        if (bmp != null && pinId > 0) {
            PinService.replace(this, pinId, bmp);
            toast("已用剪贴板内容替换");
        } else {
            toast("剪贴板里没有可用的图片或文字");
        }
        finish();
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    /**
     * 写剪贴板（复制模式）。
     *
     * 放在这个有焦点的 Activity 里做：Android 10+ 会忽略后台 App 对剪贴板的写入，
     * 之前在 PinService 里直接 setPrimaryClip 就是这样被系统吞掉的（日志看着成功、其实没生效）。
     */
    private void copyToClipboard(String uriString) {
        try {
            Uri uri = Uri.parse(uriString);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                toast("复制失败");
                return;
            }
            cm.setPrimaryClip(ClipData.newUri(getContentResolver(), "边缘截图", uri));
            android.util.Log.d("EdgeDiaryShot", "copy: 前台界面已写入剪贴板 " + uri);
            logClip("copy: 回读", cm);
            toast("已复制到剪贴板");
        } catch (Exception e) {
            android.util.Log.d("EdgeDiaryShot", "copy: 异常 " + e);
            toast("复制失败");
        }
    }

    /** 诊断用：把剪贴板里真正的内容打进日志（标签、MIME、URI/文本） */
    private void logClip(String where, ClipboardManager cm) {
        try {
            if (cm == null || !cm.hasPrimaryClip()) {
                android.util.Log.d("EdgeDiaryShot", where + " 空：hasPrimaryClip=false");
                return;
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                android.util.Log.d("EdgeDiaryShot", where + " 空：clip/itemCount=0");
                return;
            }
            ClipData.Item it = clip.getItemAt(0);
            String mime = clip.getDescription().getMimeTypeCount() > 0
                    ? clip.getDescription().getMimeType(0) : "-";
            android.util.Log.d("EdgeDiaryShot", where + " label=" + clip.getDescription().getLabel()
                    + " mime=" + mime + " count=" + clip.getItemCount()
                    + " uri=" + (it == null ? "noitem" : it.getUri())
                    + " text=" + (it == null ? "" : it.getText()));
        } catch (Exception e) {
            android.util.Log.d("EdgeDiaryShot", where + " 异常 " + e);
        }
    }

    /** 读剪贴板：优先图片，其次文字（文字会画成一张图） */
    private Bitmap readClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            logClip("paste: 剪贴板是空的", cm);
            return null;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            logClip("paste: clip 为空", cm);
            return null;
        }
        ClipData.Item item = clip.getItemAt(0);
        if (item == null) return null;
        logClip("paste:", cm);

        Uri uri = item.getUri();
        if (uri != null) {
            Bitmap bmp = decodeUri(uri);
            if (bmp != null) return bmp;
        }

        CharSequence text = null;
        try {
            text = item.coerceToText(this);
        } catch (Exception ignored) { }
        if (text != null) {
            String s = text.toString().trim();
            if (!s.isEmpty()) return PinView.renderText(this, s);
        }
        return null;
    }

    /** 从 content:// 里解码图片，太大就先采样，避免 OOM */
    private Bitmap decodeUri(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;

            int max = 2000;
            int sample = 1;
            while (o.outWidth / sample > max || o.outHeight / sample > max) sample *= 2;

            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                return BitmapFactory.decodeStream(in, null, o2);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
