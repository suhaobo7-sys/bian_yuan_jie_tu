package com.suhaobo.edgeshot;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * 长截图（v2）：**用户自己滑**，我们只负责不停地抓帧、拼起来。
 *
 * 为什么不自己滚了：
 *   程序注入滑动手势（无障碍 GestureDescription）在很多 App 里不生效 —— WebView、游戏、
 *   带惯性吸附的列表都会「点了没反应」，于是截出来永远只有一屏。改成用户自己滑之后，
 *   能不能滚、滚多快都由用户控制，兼容性只取决于「能不能抓到屏幕」，这才是能做到的最强。
 *
 * 抓帧两种办法（自动选）：
 *   1. MediaProjection + ImageReader（首选）：连续十几帧/秒，滑动多快都跟得上，
 *      代价是每次要同意一次系统的「截取屏幕」授权。
 *   2. 无障碍 takeScreenshot（降级）：不用授权，但系统限流约 1 秒一次，
 *      只能慢慢滑，通知里会提示。
 *
 * 拼接见 {@link FrameStitcher}；右上角的小窗见 {@link LongShotOverlay}。
 */
public class LongShotService extends Service implements LongShotOverlay.Listener {

    public static final String ACTION_START = "com.suhaobo.edgeshot.action.LONGSHOT_START";
    /** 长截图结束（完成 / 取消 / 到顶）：发给宿主 Activity 叫它收工 */
    public static final String ACTION_DONE = "com.suhaobo.edgeshot.action.LONGSHOT_DONE";
    public static final String ACTION_FINISH = "com.suhaobo.edgeshot.action.LONGSHOT_FINISH";
    public static final String ACTION_CANCEL = "com.suhaobo.edgeshot.action.LONGSHOT_CANCEL";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_LEFT = "left";
    public static final String EXTRA_TOP = "top";
    public static final String EXTRA_RIGHT = "right";
    public static final String EXTRA_BOTTOM = "bottom";

    private static final String CHANNEL = "edge_diary_longshot";
    private static final int NOTI_ID = 4212;
    private static final int MAX_HEIGHT = 14000;
    /** MediaProjection 抓帧间隔（约 8 帧/秒，够快又不吃 CPU） */
    private static final long FRAME_MS = 120;
    /** 降级模式的抓帧间隔（无障碍截屏有系统限流，太快会失败） */
    private static final long FALLBACK_MS = 1300;
    /** 停手多久就把右上角的小窗显示出来（显示期间抓到的帧不能用） */
    private static final long IDLE_SHOW_MS = 400;
    /** 小窗显示时检测到滑动之后，要再丢掉几帧（窗口消失动画可能还没完） */
    private static final int DISCARD_AFTER_RESUME = 2;
    /** 小窗被隐藏之后，冷却多久才让 watchIdle 再次尝试显示。
     *  降级模式 tick 间隔 1300ms，冷却要比它长（1600ms），否则下一帧还没来抓又被亮出来；
     *  MediaProjection 模式 120ms 一帧，900ms 足够让用户连续滑好几帧。 */
    private long overlayCooldown() {
        return fallbackMode ? 1600 : 900;
    }
    /**
     * 开工前先等这么久再开始抓帧。
     *
     * ⚠ 以前这里是「丢掉前 6 帧」，结果是灾难：MediaProjection 的虚拟屏只在画面
     * **发生变化**时才产帧，屏幕静止时一帧都不来。用户点完「开始」后界面转场
     * （授权框消失 + 我们的选区界面退下去）正好产出那么几帧，全被这 6 帧额度吃光，
     * 之后屏幕静止就再没有帧了 —— 长图开头那一屏永远抓不到。
     * 改成「等转场放完再抓」：ImageReader 里积压的旧帧会被 acquireLatestImage 直接
     * 跳过，拿到的就是转场结束后稳定下来的那一屏。
     */
    private static final long WARMUP_MS = 900;

    private static volatile LongShotService instance;

    private Rect region;
    private FrameStitcher stitcher;
    private LongShotOverlay overlay;
    private boolean overlayShown;
    private long overlayHiddenAt;
    private int discardFrames;
    private long lastAppendAt;
    private volatile boolean running;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private boolean fallbackMode;
    private int screenW, screenH;

    /* ---------------- 生命周期 ---------------- */

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_FINISH.equals(action)) {
            finish(true);
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(action)) {
            finish(false);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        region = new Rect(
                intent.getIntExtra(EXTRA_LEFT, 0),
                intent.getIntExtra(EXTRA_TOP, 0),
                intent.getIntExtra(EXTRA_RIGHT, 0),
                intent.getIntExtra(EXTRA_BOTTOM, 0));
        Intent data = null;
        try {
            data = intent.getParcelableExtra(EXTRA_DATA);
        } catch (Exception ignored) { }
        fallbackMode = (data == null);

        measureScreen();
        if (region.width() < 16 || region.height() < 16) {
            toast("选区太小了");
            stopSelf();
            return START_NOT_STICKY;
        }
        region.right = Math.min(region.right, screenW);
        region.bottom = Math.min(region.bottom, screenH);

        startForegroundWithType();
        startCapture(data);
        return START_NOT_STICKY;
    }

    private void measureScreen() {
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
                screenW = b.width();
                screenH = b.height();
            } else {
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                screenW = dm.widthPixels;
                screenH = dm.heightPixels;
            }
        } catch (Exception ignored) { }
    }

    private void startForegroundWithType() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                    NotificationChannel ch = new NotificationChannel(CHANNEL, "长截图", NotificationManager.IMPORTANCE_LOW);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
                int type = fallbackMode
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                startForeground(NOTI_ID, buildNotification(0, 0), type);
            } else {
                startForeground(NOTI_ID, buildNotification(0, 0));
            }
        } catch (Exception e) {
            android.util.Log.d("EdgeDiaryShot", "longshot: 前台通知失败 " + e);
        }
    }

    /* ---------------- 抓帧 ---------------- */

    private void startCapture(Intent data) {
        int w = region.width();
        int maxH = Math.min(MAX_HEIGHT, Math.max(2000, (int) (Runtime.getRuntime().maxMemory() * 0.35 / 4 / w)));
        try {
            stitcher = new FrameStitcher(w, maxH);
        } catch (OutOfMemoryError e) {
            toast("内存不够，选小一点的区域再试");
            stopSelf();
            return;
        }

        overlay = new LongShotOverlay(this, this);
        // ⚠ 先把小窗窗口加好（不可见）再建虚拟屏：反序的话窗口会整块纯黑，详见 prewarm 注释
        overlay.prewarm();
        running = true;
        lastAppendAt = System.currentTimeMillis();

        thread = new HandlerThread("longshot");
        thread.start();
        handler = new Handler(thread.getLooper());

        if (fallbackMode) {
            android.util.Log.d("EdgeDiaryShot", "longshot: 降级模式（无障碍截屏），region=" + region);
            handler.post(fallbackTick);
            return;
        }

        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(Activity.RESULT_OK, data);
            // ⚠ Android 14 起强制：必须先注册 callback 再 createVirtualDisplay，
            //   否则直接 IllegalStateException（Must register a callback before starting capture）
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    // 用户从系统里撤掉了授权
                    finish(true);
                }
            }, handler);
            reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 4);
            // ⚠ PUBLIC 不能省：只给 AUTO_MIRROR 建出来的是「私有屏」，
            //   Chrome / WebView 这类用 SurfaceView 的 App 在私有屏上抓下来是纯黑
            //   （普通 View 界面的 App 反而看不出来，坑了很久）。
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
            virtualDisplay = projection.createVirtualDisplay("edge-diary-longshot",
                    screenW, screenH, getResources().getDisplayMetrics().densityDpi,
                    flags,
                    reader.getSurface(), null, handler);
            android.util.Log.d("EdgeDiaryShot", "longshot: MediaProjection 已启动 region=" + region + " maxH=" + maxH);
            // 等转场放完再抓（详见 WARMUP_MS 的说明）：晚这一点点，
            // 换来的是长图开头那一屏不会丢。
            handler.postDelayed(projectionTick, WARMUP_MS);
        } catch (Exception e) {
            android.util.Log.d("EdgeDiaryShot", "longshot: MediaProjection 起不来，转降级 " + e);
            fallbackMode = true;
            handler.post(fallbackTick);
        }
    }

    private final Runnable projectionTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            Bitmap frame = grabFrame();
            if (frame != null) handleFrame(frame);
            watchIdle();
            if (running) handler.postDelayed(this, FRAME_MS);
        }
    };

    /**
     * 小窗不能只靠「来了帧但拼不上」触发：屏幕完全静止时 ImageReader 根本不来新帧，
     * 所以按时钟兜底 —— 只要拼过至少一屏、又停手超时，就把小窗亮出来。
     */
    private void watchIdle() {
        if (overlayShown || stitcher == null || !running) return;
        if (stitcher.frames() == 0) {
            long sinceStart = System.currentTimeMillis() - lastAppendAt;
            if (sinceStart > 4000) {
                android.util.Log.d("EdgeDiaryShot", "longshot: 4 秒了还一帧都没拼上（屏幕没变化？）");
                lastAppendAt = System.currentTimeMillis();   // 别刷屏日志
            }
            return;
        }
        long idle = System.currentTimeMillis() - lastAppendAt;
        long sinceHide = System.currentTimeMillis() - overlayHiddenAt;
        if (idle > IDLE_SHOW_MS && sinceHide > overlayCooldown()) showOverlay();
    }

    private final Runnable fallbackTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            ShotAccessibilityService svc = ShotAccessibilityService.get();
            if (svc == null) {
                toast("长截图需要无障碍服务");
                finish(false);
                return;
            }
            svc.capture(new ShotAccessibilityService.ShotCallback() {
                @Override
                public void onShot(Bitmap bmp, String error) {
                    if (bmp != null) {
                        Bitmap crop = cropToRegion(bmp);
                        if (crop != null && crop != bmp) bmp.recycle();
                        if (crop != null) handleFrame(crop);
                        else if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                    }
                    watchIdle();
                    if (running) handler.postDelayed(fallbackTick, FALLBACK_MS);
                }
            });
        }
    };

    /** 从 ImageReader 取最新一帧，并按选区裁好 */
    private Bitmap grabFrame() {
        if (reader == null) return null;
        Image image = null;
        try {
            image = reader.acquireLatestImage();
        } catch (Exception e) {
            android.util.Log.d("EdgeDiaryShot", "longshot: acquireLatestImage 异常 " + e);
            return null;
        }
        if (image == null) {
            return null;
        }
        try {
            Bitmap out = imageToBitmap(image);
            if (out == null) {
                android.util.Log.d("EdgeDiaryShot", "longshot: imageToBitmap 返回 null");
            }
            return out;
        } catch (Exception e) {
            android.util.Log.d("EdgeDiaryShot", "longshot: imageToBitmap 异常 " + e);
            return null;
        } finally {
            image.close();
        }
    }

    /** 每帧转换用的临时缓冲（复用，避免每帧都 new 出几 MB） */
    private byte[] rowBytes;
    private int[] rowInts;

    /**
     * Image（RGBA）→ Bitmap，顺手只取选区那块，省内存也省时间。
     *
     * ⚠ 不要逐像素去 get：一屏两百多万个点，每秒抓 8 次就是一千多万次调用，
     *   光转换就把 CPU 吃满了，滑动会明显卡顿。这里是整行整行地拷（bulk get），
     *   再在数组里做 RGBA→ARGB 的顺序调整，快一个数量级。
     */
    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int w = region.width();
        int h = region.height();
        int imgW = image.getWidth();
        int imgH = image.getHeight();
        if (region.left < 0 || region.top < 0 || region.right > imgW || region.bottom > imgH) {
            android.util.Log.d("EdgeDiaryShot", "longshot: 选区越界 img=" + imgW + "x" + imgH + " region=" + region);
            return null;
        }
        if (pixelStride != 4) {
            android.util.Log.d("EdgeDiaryShot", "longshot: pixelStride=" + pixelStride + " 不是 RGBA");
            return null;   // 极端情况下的怪格式，宁可丢这一帧也别截错色
        }

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int need = w * 4;
        if (rowBytes == null || rowBytes.length < need) rowBytes = new byte[need];
        if (rowInts == null || rowInts.length < w) rowInts = new int[w];
        try {
            for (int y = 0; y < h; y++) {
                int base = (region.top + y) * rowStride + region.left * 4;
                buf.position(base);
                buf.get(rowBytes, 0, need);
                for (int x = 0; x < w; x++) {
                    int o = x * 4;
                    rowInts[x] = ((rowBytes[o + 3] & 0xff) << 24)
                            | ((rowBytes[o] & 0xff) << 16)
                            | ((rowBytes[o + 1] & 0xff) << 8)
                            | (rowBytes[o + 2] & 0xff);
                }
                bmp.setPixels(rowInts, 0, w, 0, y, w, 1);
            }
        } catch (Exception e) {
            if (!bmp.isRecycled()) bmp.recycle();
            return null;
        }
        return bmp;
    }

    private Bitmap cropToRegion(Bitmap bmp) {
        try {
            int l = Math.max(0, Math.min(region.left, bmp.getWidth() - 1));
            int t = Math.max(0, Math.min(region.top, bmp.getHeight() - 1));
            int r = Math.min(bmp.getWidth(), region.right);
            int b = Math.min(bmp.getHeight(), region.bottom);
            if (r - l < 1 || b - t < 1) return null;
            return Bitmap.createBitmap(bmp, l, t, r - l, b - t);
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------------- 每帧怎么处理 ---------------- */

    private void handleFrame(Bitmap frame) {
        if (stitcher == null || !running) {
            if (!frame.isRecycled()) frame.recycle();
            return;
        }

        // 右上角的小窗正显示着：这时候抓到的帧里有小窗自己，一律不能用。
        // 只拿它来判断「用户是不是又开始滑了」—— 注意不能做模板匹配（小窗会带偏匹配），
        // 只能比相邻两帧的整体差异，见 FrameStitcher.isScrolling。
        if (overlayShown) {
            boolean moving = stitcher.isScrolling(frame);
            if (!frame.isRecycled()) frame.recycle();
            if (moving) {
                hideOverlay();
                discardFrames = DISCARD_AFTER_RESUME;   // 窗口消失还要一两帧
            }
            return;
        }

        if (discardFrames > 0) {
            discardFrames--;
            if (!frame.isRecycled()) frame.recycle();
            return;
        }

        // 排查用：前几帧打一下中心像素，抓到全黑一眼就能看出来
        if (stitcher.frames() < 3) {
            try {
                int c = frame.getPixel(frame.getWidth() / 2, frame.getHeight() / 2);
                android.util.Log.d("EdgeDiaryShot", "longshot: 帧中心像素 #" + Integer.toHexString(c)
                        + " 尺寸 " + frame.getWidth() + "x" + frame.getHeight());
            } catch (Exception ignored) { }
        }

        boolean added = stitcher.append(frame);
        if (added) {
            // 拼上了：这一帧被拼接器留着当「上一帧」用，不能回收
            lastAppendAt = System.currentTimeMillis();
            notifyProgress();
            if (stitcher.frames() <= 3 || stitcher.frames() % 10 == 0) {
                android.util.Log.d("EdgeDiaryShot", "stitch: 已拼 " + stitcher.frames()
                        + " 屏，总高 " + stitcher.height() + "px");
            }
            if (stitcher.isFull()) {
                toast("已经到最大长度了");
                finish(true);
                return;
            }
        } else {
            if (!frame.isRecycled()) frame.recycle();
            long idle = System.currentTimeMillis() - lastAppendAt;
            if (idle > IDLE_SHOW_MS && overlay != null) {
                showOverlay();
            }
        }
    }

    private void showOverlay() {
        if (overlay == null) return;
        if (!LongShotOverlay.canDraw(this)) return;      // 没悬浮窗权限就只用通知
        if (stitcher != null) stitcher.clearProbe();     // 小窗刚出现，下一帧才是滑动判断的基准
        Bitmap t = stitcher == null ? null : stitcher.thumbnail(dp(104), dp(220));
        if (t != null) {
            try {
                android.util.Log.d("EdgeDiaryShot", "longshot: 小窗缩略图 " + t.getWidth() + "x" + t.getHeight()
                        + " 中心#" + Integer.toHexString(t.getPixel(t.getWidth() / 2, t.getHeight() / 2))
                        + " cfg=" + t.getConfig() + " alpha=" + t.hasAlpha());
            } catch (Exception e) {
                android.util.Log.d("EdgeDiaryShot", "longshot: 小窗缩略图取像素失败 " + e);
            }
        } else {
            android.util.Log.d("EdgeDiaryShot", "longshot: 小窗缩略图是 null");
        }
        overlay.show(t);
        overlay.update(null, stitcher == null ? 0 : stitcher.height(), stitcher == null ? 0 : stitcher.frames());
        overlayShown = true;
    }

    private void hideOverlay() {
        if (overlay != null) overlay.hide();
        overlayShown = false;
        overlayHiddenAt = System.currentTimeMillis();
    }

    @Override
    public void onFinish() {
        finish(true);
    }

    @Override
    public void onCancel() {
        finish(false);
    }

    /* ---------------- 收工 ---------------- */

    private void finish(boolean save) {
        running = false;
        if (handler != null) {
            handler.removeCallbacks(projectionTick);
            handler.removeCallbacks(fallbackTick);
        }
        hideOverlay();
        if (overlay != null) {
            overlay.release();   // 整个生命周期只 removeView 这一次
            overlay = null;
        }
        // 叫宿主 Activity（长截图期间退在后台那个透明界面）收工
        try {
            sendBroadcast(new Intent(ACTION_DONE));
        } catch (Exception ignored) { }

        Bitmap out = null;
        if (save && stitcher != null) out = stitcher.finish();

        releaseCapture();
        if (stitcher != null) {
            stitcher.release();
            stitcher = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }

        if (out == null) {
            toast(save ? "没截到内容" : "长截图已取消");
            stopForeground(true);
            stopSelf();
            return;
        }

        String path = PinView.saveCache(this, out);
        out.recycle();
        if (path == null) {
            toast("保存失败");
            stopForeground(true);
            stopSelf();
            return;
        }
        showResultNotification(path);
        // 长截图期间我们在后台，只能用 NEW_TASK 拉起来（本 App 有无障碍服务，一般能起来；
        // 起不来也没关系，通知里还能点进去看）
        try {
            Intent i = new Intent(this, LongShotPreviewActivity.class);
            i.putExtra(PinService.EXTRA_PATH, path);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Exception e) {
            android.util.Log.d("EdgeDiaryShot", "longshot: 直接开预览失败，留在通知里 " + e);
        }
        stopForeground(true);
        stopSelf();
    }

    private void releaseCapture() {
        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Exception ignored) { }
        virtualDisplay = null;
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) { }
        reader = null;
        try {
            if (projection != null) projection.stop();
        } catch (Exception ignored) { }
        projection = null;
    }

    @Override
    public void onDestroy() {
        instance = null;
        running = false;
        hideOverlay();
        if (overlay != null) {
            overlay.release();
            overlay = null;
        }
        // 服务没了（被系统回收之类），宿主 Activity 也不能永远挂在那儿
        try {
            sendBroadcast(new Intent(ACTION_DONE));
        } catch (Exception ignored) { }
        releaseCapture();
        if (stitcher != null) {
            stitcher.release();
            stitcher = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        super.onDestroy();
    }

    /* ---------------- 通知 ---------------- */

    private long lastNotifyAt;

    private void notifyProgress() {
        long now = System.currentTimeMillis();
        if (now - lastNotifyAt < 700) return;
        lastNotifyAt = now;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && stitcher != null) nm.notify(NOTI_ID, buildNotification(stitcher.frames(), stitcher.height()));
        } catch (Exception ignored) { }
    }

    private Notification buildNotification(int frames, int height) {
        Intent finishI = new Intent(this, LongShotService.class).setAction(ACTION_FINISH);
        Intent cancelI = new Intent(this, LongShotService.class).setAction(ACTION_CANCEL);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent piFinish = PendingIntent.getService(this, 21, finishI, flags);
        PendingIntent piCancel = PendingIntent.getService(this, 22, cancelI, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        String tail = fallbackMode ? "（慢慢滑，系统会限流）" : "";
        b.setContentTitle("正在长截图…")
                .setContentText("自己滑动屏幕，好了点「完成」" + tail
                        + (frames > 0 ? " · 已截 " + frames + " 屏 / " + height + "px" : ""))
                .setSmallIcon(R.drawable.ic_shot_long)
                .setContentIntent(PendingIntent.getService(this, 23, finishI, flags))
                .setOngoing(true)
                .addAction(R.drawable.ic_shot_save, "完成", piFinish)
                .addAction(R.drawable.ic_shot_close, "取消", piCancel);
        // 通知展开后直接看到已经拼出来的长图（缩略图，不是全尺寸，省内存）
        if (frames > 0 && stitcher != null) {
            Bitmap t = stitcher.thumbnail(dp(180), dp(360));
            if (t != null) {
                b.setStyle(new Notification.BigPictureStyle()
                        .bigPicture(t)
                        .setSummaryText("已截 " + frames + " 屏 / " + height + "px"));
            }
        }
        return b.build();
    }

    private void showResultNotification(String path) {
        try {
            Intent i = new Intent(this, LongShotPreviewActivity.class);
            i.putExtra(PinService.EXTRA_PATH, path);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 24, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL)
                    : new Notification.Builder(this);
            b.setContentTitle("长截图好了")
                    .setContentText("点这里查看 / 保存")
                    .setSmallIcon(R.drawable.ic_shot_long)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID + 1, b.build());
        } catch (Exception ignored) { }
    }

    /* ---------------- 杂项 ---------------- */

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(final String s) {
        try {
            new Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    android.widget.Toast.makeText(getApplicationContext(), s, android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception ignored) { }
    }

    /** 通知里的「停止」走这里（见 LongShotStopReceiver） */
    public static void requestStopFromNotification() {
        LongShotService s = instance;
        if (s != null) s.finish(true);
    }
}
