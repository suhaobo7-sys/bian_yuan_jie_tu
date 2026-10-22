package com.suhaobo.edgeshot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 截图选区：整屏压暗 + 拖拽选框 + 边角调整 + 尺寸标签。
 *
 * 界面尽量简单（像 Snipaste 那样只有选框和几个数字），所以没有放大镜之类的装饰；
 * 选区用「视图坐标」保存，换算成原图像素时按比例还原。
 */
public class ShotSelectionView extends View {

    public interface OnSelectionListener {
        /** 选区变化（rect 为视图坐标；has=false 表示当前没有选区） */
        void onSelectionChanged(RectF rect, boolean has);
    }

    private static final int NONE = 0;
    private static final int CREATE = 1;
    private static final int MOVE = 2;
    private static final int TL = 3, T = 4, TR = 5, R = 6, BR = 7, B = 8, BL = 9, L = 10;

    private Bitmap shot;
    private final RectF sel = new RectF();
    private final RectF startSel = new RectF();
    private boolean hasSel = false;
    private int mode = NONE;
    private float downX, downY;

    private final Paint dimPaint = new Paint();
    private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float density;
    private float handleHalf;   // 手柄半径
    private float touchSlop;    // 手柄的触摸容差
    private float labelHeight;

    private OnSelectionListener listener;

    public ShotSelectionView(Context c) {
        super(c);
        init(c);
    }

    public ShotSelectionView(Context c, AttributeSet a) {
        super(c, a);
        init(c);
    }

    public ShotSelectionView(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        init(c);
    }

    private void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        handleHalf = 5f * density;
        touchSlop = 24f * density;
        labelHeight = 20f * density;

        dimPaint.setColor(0xB3000000);
        whitePaint.setStyle(Paint.Style.STROKE);
        whitePaint.setStrokeWidth(1f);
        whitePaint.setColor(0xCCFFFFFF);
        accentPaint.setStyle(Paint.Style.STROKE);
        accentPaint.setStrokeWidth(Math.max(1f, density));
        accentPaint.setColor(0xFFFFB454);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(0xFFFFB454);
        handleLinePaint.setStyle(Paint.Style.STROKE);
        handleLinePaint.setStrokeWidth(1f);
        handleLinePaint.setColor(0xCC000000);
        textPaint.setColor(0xFFE8E6DF);
        textPaint.setTextSize(11f * density);
        textBgPaint.setColor(0xE6181A23);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setOnSelectionListener(OnSelectionListener l) {
        listener = l;
    }

    /** 放进刚截下来的整屏图（默认**不选**：等用户自己拖出范围） */
    public void setShot(Bitmap bmp) {
        shot = bmp;
        hasSel = false;
        sel.setEmpty();
        notifyChanged();
        invalidate();
    }

    public Bitmap getShot() {
        return shot;
    }

    public boolean hasSelection() {
        return hasSel && sel.width() > 1 && sel.height() > 1;
    }

    public RectF getSelection() {
        return new RectF(sel);
    }

    /** 选区换算成原图像素（裁剪时用） */
    public Rect selectionInBitmap() {
        if (shot == null || getWidth() <= 0 || getHeight() <= 0) return new Rect(0, 0, 0, 0);
        float sx = (float) shot.getWidth() / getWidth();
        float sy = (float) shot.getHeight() / getHeight();
        int l = Math.max(0, Math.round(sel.left * sx));
        int t = Math.max(0, Math.round(sel.top * sy));
        int r = Math.min(shot.getWidth(), Math.round(sel.right * sx));
        int b = Math.min(shot.getHeight(), Math.round(sel.bottom * sy));
        if (r - l < 1) r = Math.min(shot.getWidth(), l + 1);
        if (b - t < 1) b = Math.min(shot.getHeight(), t + 1);
        return new Rect(l, t, r, b);
    }

    /** 裁出当前选区（原图分辨率） */
    public Bitmap crop() {
        if (shot == null) return null;
        Rect r = selectionInBitmap();
        if (r.width() < 1 || r.height() < 1) return null;
        return Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height());
    }

    private void notifyChanged() {
        if (listener != null) listener.onSelectionChanged(new RectF(sel), hasSel);
    }

    /* ---------------- 画 ---------------- */

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();

        if (shot == null) {
            // 还没截到图：一个字都别画。
            // 无障碍截屏（takeScreenshot）会把屏幕上所有窗口一起截进去，之前这里画的是整屏黑底，
            // 结果延迟截屏时截出来的就是「我们自己的一块黑」（实测过），必须保持全透明。
            return;
        }
        canvas.drawBitmap(shot, null, new RectF(0, 0, w, h), null);

        if (!hasSel) {
            canvas.drawRect(0, 0, w, h, dimPaint);
            return;
        }

        // 选区外面压暗（用四块矩形，兼容低版本）
        float l = Math.max(0, sel.left), t = Math.max(0, sel.top);
        float r = Math.min(w, sel.right), b = Math.min(h, sel.bottom);
        canvas.drawRect(0, 0, w, t, dimPaint);
        canvas.drawRect(0, b, w, h, dimPaint);
        canvas.drawRect(0, t, l, b, dimPaint);
        canvas.drawRect(r, t, w, b, dimPaint);

        // 边框：外面一圈白 + 里面一圈主题橙，深浅背景都看得清
        RectF outer = new RectF(l, t, r, b);
        canvas.drawRect(outer, whitePaint);
        RectF inner = new RectF(l + 1, t + 1, r - 1, b - 1);
        canvas.drawRect(inner, accentPaint);

        // 8 个手柄
        float cx = (l + r) / 2f, cy = (t + b) / 2f;
        float[][] pts = {
                {l, t}, {cx, t}, {r, t},
                {r, cy},
                {r, b}, {cx, b}, {l, b},
                {l, cy},
        };
        for (float[] p : pts) {
            canvas.drawRect(p[0] - handleHalf, p[1] - handleHalf, p[0] + handleHalf, p[1] + handleHalf, handlePaint);
            canvas.drawRect(p[0] - handleHalf, p[1] - handleHalf, p[0] + handleHalf, p[1] + handleHalf, handleLinePaint);
        }

        // 尺寸标签（原图像素）
        Rect bm = selectionInBitmap();
        String label = bm.width() + " × " + bm.height();
        float textW = textPaint.measureText(label);
        float pad = 6f * density;
        float boxW = textW + pad * 2;
        float boxH = labelHeight;
        float boxX = Math.min(Math.max(l, 0), Math.max(0, w - boxW));
        float boxY = t - boxH - 4f * density;
        if (boxY < 0) boxY = Math.min(h - boxH, b + 4f * density);
        canvas.drawRect(boxX, boxY, boxX + boxW, boxY + boxH, textBgPaint);
        canvas.drawText(label, boxX + pad, boxY + boxH - 6f * density, textPaint);
    }

    /* ---------------- 触摸 ---------------- */

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                startSel.set(sel);
                mode = hitTest(x, y);
                if (mode == NONE) {
                    mode = CREATE;
                    hasSel = true;
                    sel.set(x, y, x, y);
                }
                notifyChanged();
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (mode == NONE) return true;
                float dx = x - downX, dy = y - downY;
                if (mode == CREATE) {
                    sel.set(Math.min(downX, x), Math.min(downY, y), Math.max(downX, x), Math.max(downY, y));
                } else if (mode == MOVE) {
                    sel.set(startSel);
                    sel.offset(dx, dy);
                    clampInside();
                } else {
                    sel.set(startSel);
                    if (mode == TL) { sel.left = x; sel.top = y; }
                    else if (mode == T) { sel.top = y; }
                    else if (mode == TR) { sel.right = x; sel.top = y; }
                    else if (mode == R) { sel.right = x; }
                    else if (mode == BR) { sel.right = x; sel.bottom = y; }
                    else if (mode == B) { sel.bottom = y; }
                    else if (mode == BL) { sel.left = x; sel.bottom = y; }
                    else if (mode == L) { sel.left = x; }
                    normalize();
                }
                notifyChanged();
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mode == CREATE && (sel.width() < 12 * density || sel.height() < 12 * density)) {
                    // 只是点了一下（没拖出范围）：当作取消选区，回到「没选」的状态，
                    // 不再自作主张替用户选整屏 —— 用户要整屏自己拖一下就行
                    hasSel = false;
                    sel.setEmpty();
                }
                mode = NONE;
                notifyChanged();
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    /** 命中测试：先看 8 个手柄，再看是不是在选区里 */
    private int hitTest(float x, float y) {
        if (!hasSel) return NONE;
        float l = sel.left, t = sel.top, r = sel.right, b = sel.bottom;
        float cx = (l + r) / 2f, cy = (t + b) / 2f;
        float s = touchSlop;
        if (near(x, y, l, t, s)) return TL;
        if (near(x, y, cx, t, s)) return T;
        if (near(x, y, r, t, s)) return TR;
        if (near(x, y, r, cy, s)) return R;
        if (near(x, y, r, b, s)) return BR;
        if (near(x, y, cx, b, s)) return B;
        if (near(x, y, l, b, s)) return BL;
        if (near(x, y, l, cy, s)) return L;
        if (x > l && x < r && y > t && y < b) return MOVE;
        return NONE;
    }

    private boolean near(float x, float y, float px, float py, float s) {
        return Math.abs(x - px) <= s && Math.abs(y - py) <= s;
    }

    /** 拖拽手柄时把矩形摆正 */
    private void normalize() {
        if (sel.left > sel.right) { float tmp = sel.left; sel.left = sel.right; sel.right = tmp; }
        if (sel.top > sel.bottom) { float tmp = sel.top; sel.top = sel.bottom; sel.bottom = tmp; }
        clampInside();
    }

    private void clampInside() {
        float w = getWidth(), h = getHeight();
        if (sel.left < 0) sel.offset(-sel.left, 0);
        if (sel.top < 0) sel.offset(0, -sel.top);
        if (sel.right > w) sel.offset(w - sel.right, 0);
        if (sel.bottom > h) sel.offset(0, h - sel.bottom);
        sel.left = Math.max(0, sel.left);
        sel.top = Math.max(0, sel.top);
        sel.right = Math.min(w, sel.right);
        sel.bottom = Math.min(h, sel.bottom);
    }
}
