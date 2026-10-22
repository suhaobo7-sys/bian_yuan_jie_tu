package com.suhaobo.edgeshot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 截图主界面（全屏透明）：
 *   整屏压暗 → 拖拽选区 → 选区旁的小工具条：保存 / 复制 / 贴图 / 长截图 / 取消
 *
 * 截图本身走无障碍服务（静默），见 {@link ShotAccessibilityService}。
 */
public class ShotActivity extends Activity {

    /** 从快捷设置磁贴进来的：通知栏此刻是展开的，要先等它收起来再截屏 */
    public static final String EXTRA_FROM_TILE = "fromTile";
    /** 长截图之前申请「截取屏幕」授权用的请求码 */
    private static final int REQ_CAPTURE = 4711;
    /** 通知权限（Android 13+ 运行时权限），用来显示长截图进度通知 */
    private static final int REQ_NOTI = 4712;

    private ShotSelectionView view;
    private LinearLayout toolbar;
    private TextView hint;
    private TextView progress;
    private Bitmap shot;
    private boolean longShotting;
    /** 长截图待办：等界面退到后台（onStop）之后再开工 */
    private Rect pendingLong;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    /** 本次是否已经发起过截屏（焦点回调 + 兜底定时器只能截一次） */
    private boolean captureStarted;
    private final long created = System.currentTimeMillis();
    /** 宿主模式下的收工通知接收器（长截图服务结束时发广播叫我们 finish） */
    private BroadcastReceiver doneReceiver;
    private final Runnable hostTimeout = new Runnable() {
        @Override public void run() { finish(); }
    };
    private final Runnable captureOnce = new Runnable() {
        @Override
        public void run() {
            ShotActivity.this.captureOnce();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        goFullscreen();
        setContentView(R.layout.shot_activity);

        view = findViewById(R.id.shot_view);
        toolbar = findViewById(R.id.shot_toolbar);
        hint = findViewById(R.id.shot_hint);
        progress = findViewById(R.id.shot_progress);

        view.setOnSelectionListener(new ShotSelectionView.OnSelectionListener() {
            @Override
            public void onSelectionChanged(RectF rect, boolean has) {
                layoutToolbar(rect, has);
            }
        });

        findViewById(R.id.shot_btn_save).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doSave(); }
        });
        findViewById(R.id.shot_btn_copy).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doCopy(); }
        });
        findViewById(R.id.shot_btn_paste).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doPaste(); }
        });
        findViewById(R.id.shot_btn_pin).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doPin(); }
        });
        findViewById(R.id.shot_btn_long).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doLong(); }
        });
        findViewById(R.id.shot_btn_close).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        progress.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { LongShotService.requestStopFromNotification(); }
        });

        // 截图回来之前屏幕上一个像素都不要画（截屏会把我们的界面也截进去），
        // 提示条和工具条都等 setShot() 之后的回调再显示
        hint.setVisibility(View.GONE);
        toolbar.setVisibility(View.GONE);

        // 顶部提示也往下挪一点，别被状态栏盖住
        try {
            android.view.ViewGroup.MarginLayoutParams mlp =
                    (android.view.ViewGroup.MarginLayoutParams) hint.getLayoutParams();
            mlp.topMargin = systemBarInsets()[0] + Math.round(16 * getResources().getDisplayMetrics().density);
            hint.setLayoutParams(mlp);
        } catch (Exception ignored) { }

        // 截屏时机见 onWindowFocusChanged：通知栏收起、焦点回到我们界面之后再截，
        // 免得把通知栏一起截进图里。这里只是一个兜底（万一焦点回调没来，也要能截）。
        ui.postDelayed(captureOnce, 1500);

        // 长截图的「完成 / 取消」和实时缩略图都挂在通知上，没这个权限用户就收不到
        // （Android 13+ 必须在运行时申请，光在 Manifest 里写没用）。
        // 一次性弹窗，授不授都照样能截，所以这里发了就不再管结果。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTI);
                }
            } catch (Exception ignored) { }
        }
    }

    /** 只截一次 */
    private void captureOnce() {
        if (captureStarted || isFinishing() || isDestroyed()) return;
        captureStarted = true;
        android.util.Log.d("EdgeDiaryShot", "shot: 开始截屏（onCreate 后 " + (System.currentTimeMillis() - created) + "ms）");
        startCapture();
    }

    /**
     * 通知栏展开时焦点在系统栏窗口上；等它收起、焦点回到我们界面，才是干净的一屏。
     * （磁贴是从展开的通知栏点进来的，靠固定延时猜动画长度不可靠。）
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            android.util.Log.d("EdgeDiaryShot", "shot: 拿到焦点（onCreate 后 " + (System.currentTimeMillis() - created) + "ms）");
            // 至少再等一会儿：万一某些 ROM 在通知栏还没收起时就给了我们焦点，
            // 也要给收起动画留出时间，否则会把通知栏截进去
            long elapsed = System.currentTimeMillis() - created;
            ui.postDelayed(captureOnce, Math.max(180, 650 - elapsed));
        }
    }

    /** 铺满整屏（截图里包含状态栏/导航栏，界面也要盖住它们才对得上） */
    private void goFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /* ---------------- 截图 ---------------- */

    private void startCapture() {
        ShotAccessibilityService svc = ShotAccessibilityService.get();
        if (svc == null) {
            askEnableAccessibility();
            return;
        }
        svc.capture(new ShotAccessibilityService.ShotCallback() {
            @Override
            public void onShot(Bitmap bmp, String error) {
                if (bmp == null) {
                    toast(error == null ? getString(R.string.shot_failed) : error);
                    finish();
                    return;
                }
                shot = bmp;
                view.setShot(bmp);
            }
        });
    }

    private void askEnableAccessibility() {
        // Android 13 起，侧载（手动安装）的 App 默认不允许开启无障碍服务，
        // 必须先去「应用信息 → 右上角三点 → 允许受限设置」，所以这里把两条路都给出来。
        boolean restricted = Build.VERSION.SDK_INT >= 33;
        try {
            AlertDialog.Builder b = new AlertDialog.Builder(this)
                    .setMessage(restricted ? R.string.shot_need_a11y_13 : R.string.shot_need_a11y)
                    .setCancelable(false)
                    .setPositiveButton(R.string.shot_open_settings, (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (Exception ignored) { }
                        finish();
                    })
                    .setNegativeButton(R.string.shot_cancel, (d, w) -> finish());
            if (restricted) {
                b.setNeutralButton(R.string.shot_open_appinfo, (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                    finish();
                });
            }
            b.show();
        } catch (Exception e) {
            toast(restricted ? getString(R.string.shot_need_a11y_13) : getString(R.string.shot_need_a11y));
            finish();
        }
    }

    /* ---------------- 工具条定位 ---------------- */

    /** 工具条别压在状态栏/导航栏那一条上，不然点击会被系统栏吃掉 */
    private int[] systemBarInsets() {
        int top = 0, bottom = 0;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsets in = getWindowManager().getCurrentWindowMetrics().getWindowInsets();
                android.graphics.Insets sb = in.getInsets(android.view.WindowInsets.Type.systemBars());
                top = sb.top;
                bottom = sb.bottom;
            } else {
                int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) top = getResources().getDimensionPixelSize(id);
                int nid = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
                if (nid > 0) bottom = getResources().getDimensionPixelSize(nid);
            }
        } catch (Exception ignored) { }
        return new int[] { top, bottom };
    }

    private void layoutToolbar(final RectF r, boolean has) {
        if (shot == null || longShotting) {
            // 截图还没回来（或正在长截图）：连提示条都别显示。
            // 无障碍截屏会把屏幕上所有窗口一起截进去，这时候显示任何东西都会出现在截出来的图里
            // （实测：提示条会变成图里顶上的一条亮带）。
            toolbar.setVisibility(View.GONE);
            hint.setVisibility(View.GONE);
            return;
        }
        if (!has) {
            toolbar.setVisibility(View.GONE);
            hint.setVisibility(View.VISIBLE);
            return;
        }
        hint.setVisibility(View.GONE);
        toolbar.setVisibility(View.VISIBLE);
        toolbar.post(new Runnable() {
            @Override
            public void run() {
                float d = getResources().getDisplayMetrics().density;
                int[] ins = systemBarInsets();
                int tw = toolbar.getWidth();
                int th = toolbar.getHeight();
                int sw = view.getWidth();
                int sh = view.getHeight();
                float minY = ins[0] + 8 * d;
                float maxY = sh - ins[1] - th - 8 * d;
                float x = r.right - tw;                       // 右对齐选区
                float y = r.bottom + 10 * d;                  // 默认贴在选区下面
                if (y > maxY) y = r.top - th - 10 * d;        // 下面放不下就放上面
                x = Math.max(8 * d, Math.min(x, sw - tw - 8 * d));
                y = Math.max(minY, Math.min(y, Math.max(minY, maxY)));
                toolbar.setX(x);
                toolbar.setY(y);
            }
        });
    }

    /* ---------------- 四个动作 ---------------- */

    private void doSave() {
        Bitmap crop = view.crop();
        if (crop == null) { toast(getString(R.string.shot_failed)); return; }
        Uri u = ShotStore.saveToGallery(this, crop, null);
        toast(u == null ? getString(R.string.shot_failed) : getString(R.string.shot_saved));
        finish();
    }

    private void doCopy() {
        Bitmap crop = view.crop();
        if (crop == null) { toast(getString(R.string.shot_failed)); return; }
        boolean ok = ShotStore.copyToClipboard(this, crop);
        toast(ok ? getString(R.string.shot_copied) : getString(R.string.shot_failed));
        finish();
    }

    /** 粘贴：把剪贴板里的图（或文字画成的图）直接贴到屏幕上 */    
    private void doPaste() {
        Bitmap clip = PinView.readClipboard(this);
        if (clip == null) {
            toast("剪贴板里没有图片");
            return;
        }
        if (!PinService.canOverlay(this)) {
            toast("贴图需要「显示在其他应用上层」权限");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
            finish();
            return;
        }
        PinService.pin(this, clip);
        finish();
    }

    private void doPin() {
        Bitmap crop = view.crop();
        if (crop == null) { toast(getString(R.string.shot_failed)); return; }
        if (!PinService.canOverlay(this)) {
            toast("贴图需要「显示在其他应用上层」权限");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
            finish();
            return;
        }
        PinService.pin(this, crop);
        finish();
    }

    private void doLong() {
        if (longShotting) return;
        final Rect sel = view.selectionInBitmap();
        if (sel.width() < 16 || sel.height() < 16) {
            toast("选区太小了");
            return;
        }
        // 状态栏 / 导航栏不跟着页面滚动，留在长图里会拼出重复的假内容，所以从选区里去掉
        final Rect region = new Rect(sel);
        int[] bars = systemBarInsets();
        int fullH = shot == null ? view.getHeight() : shot.getHeight();
        region.top = Math.max(region.top, bars[0]);
        region.bottom = Math.min(region.bottom, fullH - bars[1]);
        if (region.width() < 16 || region.height() < 16) {
            toast("选区太小了");
            return;
        }
        longShotting = true;
        pendingLong = region;

        // 连续抓帧要用到系统的「截取屏幕」授权（MediaProjection）：先问一次。
        // 用户不同意也能用（降级成无障碍截屏，只是要慢慢滑），所以这里不拦。
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm != null) {
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
                return;
            }
        } catch (Exception e) {
            android.util.Log.d("EdgeDiaryShot", "shot: 申请录屏授权失败，直接走降级 " + e);
        }
        startLongShot(null);
    }

    /** 系统「是否允许截取屏幕」的结果：给不给都行，各有各的抓帧办法 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode == RESULT_OK && data != null) startLongShot(data);
        else startLongShot(null);       // 不同意 → 降级成无障碍截屏
    }

    private void startLongShot(Intent captureData) {
        final Rect r = pendingLong;
        pendingLong = null;
        if (r == null) {
            finish();
            return;
        }
        Intent i = new Intent(this, LongShotService.class);
        i.setAction(LongShotService.ACTION_START);
        i.putExtra(LongShotService.EXTRA_LEFT, r.left);
        i.putExtra(LongShotService.EXTRA_TOP, r.top);
        i.putExtra(LongShotService.EXTRA_RIGHT, r.right);
        i.putExtra(LongShotService.EXTRA_BOTTOM, r.bottom);
        if (captureData != null) i.putExtra(LongShotService.EXTRA_DATA, captureData);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            try {
                startService(i);
            } catch (Exception ignored) { }
        }
        // 把自己的界面让出来：我们要截的是底下那个 App，用户得能滑它。
        // 但 Activity 不能 finish —— 见 goHostMode() 里的说明。
        goHostMode();
    }

    /**
     * 长截图期间把自己变成「宿主」：界面清空、窗口不接收触摸、退到后台，但**不 finish**。
     *
     * ⚠ 为什么不 finish：实测 app 进程里一个 Activity 都不剩的时候（截图界面一走，
     *   进程就只剩个 Service），MediaProjection 建起来的虚拟屏会把 app 的渲染拖停
     *   —— 小窗第一帧永远画不完，SurfaceFlinger 里那个 layer 就一直是一块纯黑
     *   （gfxinfo 里每帧 4950ms，view 侧 attached/测量/显示却全是好的）。
     *   进程里留一个 Activity（哪怕停在后台）渲染就恢复正常，小窗也正常了。
     *
     * 界面全设为 GONE 之后窗口就是全透明的（ShotTheme 本身透明、无压暗），
     * 再加上 FLAG_NOT_TOUCHABLE，用户滑的是底下那个 App，我们什么都不拦。
     *
     * ⚠ 退后台的时机：不能立刻退。MediaProjection 的虚拟屏建起来之后，这个 app 的
     *   渲染会被拖停（小窗第一帧永远画不完，整块纯黑），必须在那之后成功画过一帧
     *   才能恢复。所以先等一会儿（等服务把虚拟屏建好），强制自己重绘一帧，再退后台。
     */
    private void goHostMode() {
        try {
            view.setVisibility(View.GONE);
            toolbar.setVisibility(View.GONE);
            hint.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            // 大图已经交给长截图服务了，这块可以放了（长截图可能要好几分钟，别一直攥着）
            if (shot != null && !shot.isRecycled()) shot.recycle();
            shot = null;
        } catch (Exception ignored) { }

        // 等虚拟屏建好 → 强制重绘一帧（把渲染管道踹回来）→ 再退到后台
        ui.removeCallbacks(kickAndBack);
        ui.postDelayed(kickAndBack, 600);

        // 收工通知：长截图结束（完成 / 取消 / 到顶）时叫我们 finish
        if (doneReceiver == null) {
            doneReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { finish(); }
            };
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(doneReceiver, new IntentFilter(LongShotService.ACTION_DONE),
                            Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(doneReceiver, new IntentFilter(LongShotService.ACTION_DONE));
                }
            } catch (Exception ignored) { }
        }
        // 兜底：万一服务悄悄没了没发广播，也别让这个透明界面永远挂在前台
        ui.removeCallbacks(hostTimeout);
        ui.postDelayed(hostTimeout, 5 * 60 * 1000);
    }

    /** 强制自己重绘一帧，然后退到后台，让用户要截的那个 App 回前台 */
    private final Runnable kickAndBack = new Runnable() {
        @Override public void run() {
            try {
                // 换一次窗口背景（还是全透明，看不出变化），逼 decor 走完一整套 draw
                getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
                getWindow().getDecorView().invalidate();
            } catch (Exception ignored) { }
            try {
                moveTaskToBack(true);
            } catch (Exception ignored) { }
        }
    };

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(hostTimeout);
        ui.removeCallbacks(kickAndBack);
        if (doneReceiver != null) {
            try { unregisterReceiver(doneReceiver); } catch (Exception ignored) { }
            doneReceiver = null;
        }
        if (shot != null && !shot.isRecycled()) shot.recycle();
        shot = null;
        super.onDestroy();
    }
}
