package com.dalang.winamp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 均衡器窗口：275x116，真皮肤位图。公开 API 与旧版一致。
 * 布局坐标来源：Webamp css/equalizer-window.css + js/skinSprites.ts。
 */
public class EqWindowView extends FrameLayout {

    private static final int LW = 275, LH = 116;
    // 11 根推子逻辑 x：preamp=21，bands=78+18*i
    private static final int[] BAND_X = {21, 78, 96, 114, 132, 150, 168, 186, 204, 222, 240};
    private static final int BAND_Y = 38;

    private final SkinAssets assets;
    private int S;
    private int offsetX = 0;
    private boolean collapsed = false;
    private boolean closePressed = false;

    private final List<BandSlider> sliders = new ArrayList<>();
    private WButton btnOn, btnAuto, btnPresets;
    private final Paint graphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path graphPath = new Path();

    private static class ChildPos {
        View v; int lx, ly;
        ChildPos(View v, int lx, int ly) { this.v = v; this.lx = lx; this.ly = ly; }
    }
    private final List<ChildPos> children = new ArrayList<>();

    public EqWindowView(Context context) {
        super(context);
        assets = SkinAssets.get(context);
        S = SkinAssets.scale(context);
        setWillNotDraw(false);
        graphPaint.setStyle(Paint.Style.STROKE);
        graphPaint.setStrokeWidth(1);
        graphPaint.setColor(0xFF39B510);

        for (int i = 0; i < 11; i++) {
            BandSlider s = new BandSlider(context, S);
            s.setValue(0.5f);
            // 推子变化时重画曲线图（附加监听，不干扰 MainActivity 的主监听）
            s.addOnChangeListener(new WSlider.OnChangeListener() {
                @Override public void onChange(float value) { invalidate(); }
            });
            sliders.add(s);
            addChild(s, BAND_X[i], BAND_Y);
        }

        btnOn = new WButton(context, S, "eqmain",
                new int[]{10, 119, 26, 12}, new int[]{128, 119, 26, 12},
                new int[]{69, 119, 26, 12}, new int[]{187, 119, 26, 12});
        btnOn.setToggle(true);
        btnOn.setActive(true);
        addChild(btnOn, 14, 18);

        btnAuto = new WButton(context, S, "eqmain",
                new int[]{36, 119, 32, 12}, new int[]{154, 119, 32, 12},
                new int[]{95, 119, 32, 12}, new int[]{213, 119, 32, 12});
        btnAuto.setToggle(true);
        addChild(btnAuto, 40, 18);

        btnPresets = new WButton(context, S, "eqmain",
                new int[]{224, 164, 44, 12}, new int[]{224, 176, 44, 12},
                null, null);
        addChild(btnPresets, 217, 18);
    }

    private void addChild(View v, int lx, int ly) {
        addView(v);
        children.add(new ChildPos(v, lx, ly));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (collapsed ? 14 : LH) * S);
        int ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST);
        int hs = MeasureSpec.makeMeasureSpec(LH * S, MeasureSpec.AT_MOST);
        for (ChildPos cp : children) cp.v.measure(ws, hs);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        offsetX = (r - l - LW * S) / 2;
        if (offsetX < 0) offsetX = 0;
        for (ChildPos cp : children) {
            View v = cp.v;
            if (v.getVisibility() == GONE) continue;
            int x = offsetX + cp.lx * S;
            int y = cp.ly * S;
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
        }
    }

    private boolean inRect(float x, float y, int lx, int ly, int w, int h) {
        float bx = offsetX + lx * S, by = ly * S;
        return x >= bx && x <= bx + w * S && y >= by && y <= by + h * S;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                closePressed = inRect(x, y, 264, 3, 9, 9);
                if (closePressed) { invalidate(); return true; }
                break;
            case MotionEvent.ACTION_UP:
                boolean hit = closePressed && inRect(x, y, 264, 3, 9, 9);
                closePressed = false;
                if (hit) {
                    collapsed = !collapsed;
                    for (ChildPos cp : children) {
                        cp.v.setVisibility(collapsed ? GONE : VISIBLE);
                    }
                    requestLayout();
                    invalidate();
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                closePressed = false;
                invalidate();
                break;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas cv) {
        cv.save();
        cv.translate(offsetX, 0);
        cv.scale(S, S);
        // 背景
        assets.sprite(cv, "eqmain", 0, 0, LW, LH, 0, 0);
        // 标题栏
        assets.sprite(cv, "eqmain", 0, 149, 275, 14, 0, 0);
        assets.sprite(cv, "eqmain", 0, closePressed ? 125 : 116, 9, 9, 264, 3);
        if (!collapsed) {
            drawGraph(cv);
        }
        cv.restore();
    }

    /** 均衡曲线图 (86,17,113x19)：11 个推子值连线 */
    private void drawGraph(Canvas cv) {
        graphPath.reset();
        boolean first = true;
        for (int i = 0; i < 11; i++) {
            float v = sliders.get(i).getValue();
            // x 均匀分布在 86..199，y = 17 + (1-v)*19
            float x = 86 + i * (113f / 10);
            float y = 17 + (1 - v) * 18;
            if (first) { graphPath.moveTo(x, y); first = false; }
            else graphPath.lineTo(x, y);
        }
        cv.drawPath(graphPath, graphPaint);
    }

    // ---- 对外 API（MainActivity 用，不可改签名） ----
    public int getBandCount() { return sliders.size(); }
    public WSlider getSlider(int i) { return sliders.get(i); }
    public void setBandValue(int i, float v) { sliders.get(i).setValue(v); }
    public WButton getBtnOn() { return btnOn; }
    public WButton getBtnAuto() { return btnAuto; }
    public WButton getBtnPresets() { return btnPresets; }
}
