package com.suhaobo.edgeshot;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 截图的落地：存系统相册 / 写一份到缓存目录 / 复制到剪贴板 */
public final class ShotStore {

    /** 相册里的文件夹名 */
    private static final String ALBUM = "边缘截图";

    private ShotStore() { }

    public static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    /** 存进系统相册（Android 10+ 不需要任何权限），返回可分享的 Uri；失败返回 null */
    public static Uri saveToGallery(Context ctx, Bitmap bmp, String baseName) {
        if (bmp == null) return null;
        String fileName = (baseName == null || baseName.isEmpty() ? "Screenshot_" + stamp() : baseName) + ".png";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM);
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                    if (os == null || !bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) return null;
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, done, null, null);
                return uri;
            }

            // Android 9 及以下：直接写公共目录再让系统扫一遍
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM);
            if (!dir.exists() && !dir.mkdirs()) return null;
            File f = new File(dir, fileName);
            try (OutputStream os = new FileOutputStream(f)) {
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) return null;
            }
            ctx.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)));
            return Uri.fromFile(f);
        } catch (Exception e) {
            return null;
        }
    }

    /** 写一份 PNG 到 App 缓存目录，返回 content:// （给剪贴板 / 分享用） */
    public static Uri cacheFile(Context ctx, Bitmap bmp) {
        if (bmp == null) return null;
        try {
            File dir = new File(ctx.getCacheDir(), "shots");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File f = new File(dir, "shot-" + stamp() + ".png");
            try (OutputStream os = new FileOutputStream(f)) {
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) return null;
            }
            return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把图片放进剪贴板（别的 App 也能粘到）。
     *
     * ⚠ 只能由**有焦点的界面**（ShotActivity 这类前台 Activity）调用：Android 10+ 会忽略
     * 后台进程/服务的剪贴板写入。贴图（PinService）的「复制」必须走 ClipboardActivity，
     * 见 ClipboardActivity#copyToClipboard。
     */
    public static boolean copyToClipboard(Context ctx, Bitmap bmp) {
        Uri uri = cacheFile(ctx, bmp);
        if (uri == null) {
            android.util.Log.d("EdgeDiaryShot", "copy: cacheFile 失败");
            return false;
        }
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newUri(ctx.getContentResolver(), "边缘截图", uri));
            android.util.Log.d("EdgeDiaryShot", "copy: 已写入剪贴板 " + uri);
            return true;
        } catch (Exception e) {
            android.util.Log.d("EdgeDiaryShot", "copy: 异常 " + e);
            return false;
        }
    }
}
