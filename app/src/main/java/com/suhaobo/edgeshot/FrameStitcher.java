package com.suhaobo.edgeshot;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;

/**
 * 长截图的拼接器：一帧一帧喂进来，靠「重叠区比对」算出这一屏相对上一屏滚了多少，
 * 只把新滚出来的那几条像素拼到长图下面。
 *
 * 拼接思路（和旧版一样，不依赖 OpenCV）：
 *   把相邻两屏都降采样成灰度小图，拿「新的一屏中段」的一条横带当模板，
 *   在「上一屏」里从上往下找最像的位置（SAD 差值最小），那就是滚动距离。
 *
 * 和旧版最大的区别：
 *   旧版是**程序自己滚**，匹配不上就认为「到底了」直接收工；
 *   现在是**用户自己滑**，匹配不上可能只是滑太快 / 弹了个键盘 / 拉了下通知栏，
 *   所以匹配不上只丢弃这一帧，继续等下一帧，成败由用户点「完成」决定。
 */
public class FrameStitcher {

    /** 灰度降采样倍数（匹配用） */
    private static final int DOWN = 4;
    /** 平均灰度差超过这个值就认为两张图不是同一个位置的（拼不上） */
    private static final int MATCH_MAX_AVG_DIFF = 16;
    /** 滚动距离小于这么多像素就算「没动」 */
    private static final int MIN_DELTA = 3;
    /** 小窗显示期间，相邻两帧的平均灰度差超过这个值就认为用户又开始滑了 */
    private static final int SCROLL_DIFF = 8;

    private final Bitmap composite;
    private final Canvas canvas;
    private int total;                  // 已经拼好的高度
    private Bitmap last;                // 上一帧（用来比对）
    private int[] lastGray;
    private int[] nextGray;             // 刚算出来的那一帧的灰度（复用，别每帧都 new）
    private int[] pxBuf;                // getPixels 的临时缓冲（复用）
    private int frames;                 // 拼进去的帧数
    private int skipped;                // 连续拼不上的帧数

    public FrameStitcher(int width, int maxHeight) {
        composite = Bitmap.createBitmap(Math.max(1, width), Math.max(1, maxHeight), Bitmap.Config.ARGB_8888);
        canvas = new Canvas(composite);
    }

    public int width() {
        return composite.getWidth();
    }

    public int height() {
        return total;
    }

    public int frames() {
        return frames;
    }

    public int skipped() {
        return skipped;
    }

    public boolean isFull() {
        return total >= composite.getHeight() - 8;
    }

    /**
     * 喂一帧（已经按选区裁好的图）。
     *
     * @return 拼上了返回 true；没动 / 没匹配上返回 false（调用方直接丢掉就行）
     */
    public boolean append(Bitmap frame) {
        if (frame == null || frame.getWidth() != composite.getWidth()) return false;
        int h = frame.getHeight();

        if (last == null) {
            canvas.drawBitmap(frame, 0, 0, null);
            total = Math.min(h, composite.getHeight());
            last = frame;
            lastGray = gray(frame, null);
            frames = 1;
            skipped = 0;
            return true;
        }

        int delta = matchOffset(frame);
        if (delta <= MIN_DELTA) {
            skipped++;
            // 上一帧用不着了（用户可能滑得很快，别拿旧图继续比）
            return false;
        }

        int added = Math.min(delta, h);
        int room = composite.getHeight() - total;
        if (added > room) added = room;
        if (added <= 0) {
            skipped++;
            return false;
        }

        Rect src = new Rect(0, h - added, frame.getWidth(), h);
        Rect dst = new Rect(0, total, frame.getWidth(), total + added);
        canvas.drawBitmap(frame, src, dst, null);
        total += added;

        if (last != frame && !last.isRecycled()) last.recycle();
        last = frame;
        // matchOffset 里已经算过这一帧的灰度了，直接换过来当「上一帧」，一次都别多算
        int[] swap = lastGray;
        lastGray = nextGray;
        nextGray = swap;
        frames++;
        skipped = 0;
        return true;
    }

    /**
     * 只「探一下」这一帧相对上一帧滚了多少，**不拼进去、也不改状态**。
     * 用于小窗显示期间检测「用户是不是又开始滑了」（那时候抓到的帧里有小窗自己，不能拼）。
     */
    public int probe(Bitmap frame) {
        if (frame == null || lastGray == null || last == null) return -1;
        return matchOffset(frame);
    }

    /* ---------------- 小窗显示期间的「用户是不是又滑了」 ---------------- */

    /** 小窗显示期间的基准灰度（同样含小窗，所以能比） */
    private int[] probeGray;
    /** 算新帧灰度用的临时缓冲（不能复用 probeGray，会把基准冲掉） */
    private int[] probeScratch;

    /** 小窗刚显示：清掉基准，下一帧才是基准（免得把「小窗自己出现」当成滑动） */
    public void clearProbe() {
        probeGray = null;
    }

    /**
     * 小窗显示期间判断用户是不是又开始滑了。
     *
     * ⚠ 这里**不能**用 {@link #probe} 的模板匹配：小窗自己就在画面里（右上角一块），
     *   模板横带会被那块黑框带偏，十有八九匹配不上，于是小窗一直不退、
     *   用户滑到哪儿都截不进去。改成最笨也最稳的办法 —— 和上一帧（同样含小窗）
     *   比整体灰度差多少，差得多就是在滑。
     *
     * @param frame 这一帧（含小窗），调用方照常回收即可
     * @return true 表示用户正在滑，调用方该收起小窗继续拼
     */
    public boolean isScrolling(Bitmap frame) {
        if (frame == null || frame.getWidth() != composite.getWidth()) return false;
        probeScratch = gray(frame, probeScratch);
        if (probeGray == null || probeGray.length != probeScratch.length) {
            probeGray = new int[probeScratch.length];
            System.arraycopy(probeScratch, 0, probeGray, 0, probeScratch.length);
            return false;   // 第一帧只当基准
        }
        long score = 0;
        int n = 0;
        for (int i = 0; i < probeGray.length; i += 7) {
            score += Math.abs(probeGray[i] - probeScratch[i]);
            n++;
        }
        System.arraycopy(probeScratch, 0, probeGray, 0, probeGray.length);
        float avg = n == 0 ? 0 : (float) score / n;
        return avg > SCROLL_DIFF;
    }

    /** 收工：把拼好的部分裁出来（composite 里没用到的空白不要） */
    public Bitmap finish() {
        if (total <= 0) return null;
        try {
            int h = Math.min(total, composite.getHeight());
            return Bitmap.createBitmap(composite, 0, 0, composite.getWidth(), h);
        } catch (Exception e) {
            return null;
        }
    }

    public void release() {
        last = null;
        probeGray = null;
        probeScratch = null;
        if (!composite.isRecycled()) composite.recycle();
    }

    /** 缩略图（给右上角的小窗和通知用） */
    public Bitmap thumbnail(int maxW, int maxH) {
        if (total <= 0) return null;
        try {
            float s = Math.min((float) maxW / composite.getWidth(), (float) maxH / Math.max(1, total));
            s = Math.min(s, 1f);
            int w = Math.max(1, Math.round(composite.getWidth() * s));
            int h = Math.max(1, Math.round(total * s));
            Bitmap src = Bitmap.createBitmap(composite, 0, 0, composite.getWidth(),
                    Math.min(total, composite.getHeight()));
            android.util.Log.d("EdgeDiaryShot", "stitch: thumbnail composite="
                    + composite.getWidth() + "x" + composite.getHeight()
                    + " total=" + total + " -> " + w + "x" + h
                    + " src中心#" + Integer.toHexString(src.getPixel(src.getWidth() / 2, src.getHeight() / 2)));
            Bitmap out = Bitmap.createScaledBitmap(src, w, h, true);
            if (out != src && !src.isRecycled()) src.recycle();
            return out;
        } catch (Exception e) {
            android.util.Log.d("EdgeDiaryShot", "stitch: thumbnail 失败 " + e);
            return null;
        }
    }

    /* ---------------- 找重叠 ---------------- */

    /**
     * 降采样 + 灰度（匹配用）。
     *
     * 每帧都要跑，而且一帧就是两百多万个点 —— 缓冲区全部复用，一次都不重新分配，
     * 不然 8 帧/秒地 new 8MB 的 int[]，GC 会把主线程一起卡住。
     *
     * @param out 可以传上次用过的数组来复用（尺寸不够会重新分配）
     */
    private int[] gray(Bitmap b, int[] out) {
        int w = Math.max(1, b.getWidth() / DOWN);
        int h = Math.max(1, b.getHeight() / DOWN);
        int need = b.getWidth() * b.getHeight();
        if (pxBuf == null || pxBuf.length < need) pxBuf = new int[need];
        try {
            b.getPixels(pxBuf, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        } catch (Exception e) {
            return new int[w * h];
        }
        if (out == null || out.length < w * h) out = new int[w * h];
        int bw = b.getWidth();
        for (int y = 0; y < h; y++) {
            int row = (y * DOWN) * bw;
            int orow = y * w;
            for (int x = 0; x < w; x++) {
                int c = pxBuf[row + x * DOWN];
                int lum = ((c >> 16) & 0xff) * 30 + ((c >> 8) & 0xff) * 59 + (c & 0xff) * 11;
                out[orow + x] = lum / 100;
            }
        }
        return out;
    }

    /** 返回这一帧相对上一帧滚了多少（原图像素）；-1 表示没匹配上 */
    private int matchOffset(Bitmap next) {
        if (lastGray == null || last == null) return -1;
        nextGray = gray(next, nextGray);
        int[] prevGray = lastGray;
        int prevW = last.getWidth();
        int prevH = last.getHeight();
        int w = Math.max(1, prevW / DOWN);
        int ph = Math.max(1, prevH / DOWN);
        int nh = Math.max(1, next.getHeight() / DOWN);
        int[] nextG = nextGray;
        if (nextG.length < w * 2 || prevGray.length < w * 2) return -1;

        // 模板取新屏「中段」的一条横带：最顶上那条常常是固定的标题栏，它不跟着滚动，
        // 拿它当模板会永远匹配到 y=0，于是被误判成「没有新内容」。
        int template = Math.min(Math.max(6, nh / 10), Math.max(6, nh / 4));
        int y0 = nh / 3;
        if (y0 + template > nh) y0 = Math.max(0, nh - template);
        int to = ph - template;
        if (to <= 0) return -1;

        int bestY = -1;
        long bestScore = Long.MAX_VALUE;
        // 只往「下滚」的方向找（y >= y0）：整屏搜索会遇到长得像的地方，
        // 给出一个偏小的滚动距离，拼出来就是重复的假内容。
        for (int y = y0; y <= to; y++) {
            long score = 0;
            int n = 0;
            for (int ty = 0; ty < template; ty += 2) {
                int pRow = (y + ty) * w;
                int nRow = (y0 + ty) * w;
                for (int x = 0; x < w; x += 3) {
                    score += Math.abs(prevGray[pRow + x] - nextGray[nRow + x]);
                    n++;
                }
            }
            if (n == 0) return -1;
            long avg = score / n;
            if (avg < bestScore) {
                bestScore = avg;
                bestY = y;
            }
        }
        if (bestY < 0 || bestScore > MATCH_MAX_AVG_DIFF) return -1;
        int delta = (bestY - y0) * DOWN;
        return delta < 0 ? -1 : delta;
    }
}
