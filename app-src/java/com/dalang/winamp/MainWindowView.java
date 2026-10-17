package com.dalang.winamp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 主窗口：275x116 逻辑像素，真 Winamp base skin 位图 1:1 还原。
 * 公开 API 与旧版完全一致（MainActivity 无需改动）。
 *
 * 布局坐标来源：Webamp css/main-window.css + js/skinSprites.ts。
 */
public class MainWindowView extends FrameLayout {

    private static final int LW = 275, LH = 116;

    private final SkinAssets assets;
    private int S = 3;
    private int offsetX = 0;
    private boolean collapsed = false;

    // 标题栏按钮按下态
    private boolean shadePressed = false;
    private boolean closePressed = false;

    // 播放指示器
    private static final int IND_STOPPED = 0, IND_PLAYING = 1, IND_PAUSED = 2;
    private int playState = IND_STOPPED;

    // 频谱
    private final SpectrumView spectrumView = new SpectrumView();
    private final float[] peaks = new float[19];
    private final Paint barPaint = new Paint();
    private final Paint peakPaint = new Paint();
    private final Path wavePath = new Path();

    // 跑马灯
    private final TickerView tickerView = new TickerView();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean tickerRunning = false;
    private final Runnable tickerTick = new Runnable() {
        @Override public void run() {
            if (!tickerRunning) return;
            tickerView.tick(1.6f, assets.textWidth(tickerView.getText()), 154);
            invalidate();
            ui.postDelayed(this, 40);
        }
    };

    // kbps/khz/声道
    private final MetaCtl metaCtl = new MetaCtl();

    // 音量/平衡滑杆几何（逻辑像素，坐标取自 Webamp css/main-window.css）
    // 背景：volume.bmp / balance.bmp 的 28 帧 68x15 精灵表（frame = round(v*27)）；
    // 推子：同一张图尾部 14x11（y=422）：x=15 为常态，x=0 为按下态。
    private static final int VOL_X = 107, VOL_Y = 57, VOL_W = 68;
    private static final int BAL_X = 177, BAL_Y = 57, BAL_SRC_X = 9, BAL_W = 38;
    private static final int SLIDER_H = 15;
    private static final int THUMB_W = 14, THUMB_H = 11, THUMB_Y = 422;
    private static final int THUMB_X_NORMAL = 15, THUMB_X_PRESSED = 0;
    /** 推子行程：音量滑槽 65px（Webamp 的 range 输入比 68px 背景窄 3px），平衡 38px */
    private static final int VOL_TRAVEL = VOL_W - 3 - THUMB_W; // 51
    private static final int BAL_TRAVEL = BAL_W - THUMB_W;     // 24

    // 子控件
    private SkinTimeView timeView;
    private PosbarSlider seekSlider;
    // 音量/平衡：直接在 onDraw 绘制（SheetHSlider View 方案在部分机型上不显示），
    // 触摸在 onTouchEvent 处理
    private float volValue = 0.8f;
    private float balValue = 0.5f;
    private WSlider.OnChangeListener volListener;
    private WSlider.OnChangeListener balListener;
    private boolean volDragging = false;
    private boolean balDragging = false;
    private WButton btnPrev, btnPlay, btnPause, btnStop, btnNext, btnEject;
    private WButton btnShuffle, btnRepeat;
    private WButton btnEqWin, btnPlWin;

    private static class ChildPos {
        View v; int lx, ly;
        ChildPos(View v, int lx, int ly) { this.v = v; this.lx = lx; this.ly = ly; }
    }
    private final List<ChildPos> children = new ArrayList<>();

    /** 时间显示：numbers.bmp 数字，MainActivity 调用 setText/setOnClickListener */
    public class SkinTimeView extends View {
        private String text = "-0:00";
        public SkinTimeView(Context c) { super(c); setClickable(true); }
        public void setText(String t) { text = t == null ? "" : t; invalidate(); }
        @Override protected void onMeasure(int w, int h) {
            // 宽度 70（drawTime 最右到 x+60），向右扩展避免末位被裁
            setMeasuredDimension(70 * S, 13 * S);
        }
        @Override protected void onDraw(Canvas cv) {
            cv.save();
            cv.scale(S, S);
            // drawTime 的 x 为 #time 相对坐标（0），内部按 Webamp 偏移排布数字
            assets.drawTime(cv, text, 0, 0);
            cv.restore();
        }
    }

    /** kbps/khz/声道：MainActivity 调用 setText("192 kbps 44.1 kHz stereo") */
    public class MetaCtl {
        private String kbps = "";
        private String khz = "";
        private int channels = 0;
        public void setText(String t) {
            kbps = ""; khz = ""; channels = 0;
            if (t != null) {
                String[] p = t.split(" ");
                if (p.length > 0 && !p[0].startsWith("-")) kbps = p[0];
                if (p.length > 2) khz = p[2];
                if (p.length > 4) {
                    if (p[4].startsWith("mono")) channels = 1;
                    else if (p[4].startsWith("stereo")) channels = 2;
                }
            }
            invalidate();
        }
    }

    public MainWindowView(Context context) {
        super(context);
        assets = SkinAssets.get(context);
        // S 在构造时一次确定（子控件尺寸依赖它）
        S = SkinAssets.scale(context);
        setWillNotDraw(false);
        barPaint.setStyle(Paint.Style.FILL);
        peakPaint.setStyle(Paint.Style.FILL);
        peakPaint.setColor(SkinAssets.VIS_COLORS[23]);

        spectrumView.setInvalidator(new Runnable() {
            @Override public void run() { postInvalidate(); }
        });
        tickerView.setInvalidator(new Runnable() {
            @Override public void run() { postInvalidate(); }
        });

        int s = S;
        // 时间
        timeView = new SkinTimeView(context);
        addChild(timeView, 39, 26);
        // 音量 / 平衡改为直接绘制（见 onDraw/drawVolBal），不再用子 View
        // 进度
        seekSlider = new PosbarSlider(context, s);
        addChild(seekSlider, 16, 72);
        // 控制键
        btnPrev = mkBtn(context, s, "cbuttons",
                new int[]{0, 0, 23, 18}, new int[]{0, 18, 23, 18}, 16, 88);
        btnPlay = mkBtn(context, s, "cbuttons",
                new int[]{23, 0, 23, 18}, new int[]{23, 18, 23, 18}, 39, 88);
        btnPause = mkBtn(context, s, "cbuttons",
                new int[]{46, 0, 23, 18}, new int[]{46, 18, 23, 18}, 62, 88);
        btnStop = mkBtn(context, s, "cbuttons",
                new int[]{69, 0, 23, 18}, new int[]{69, 18, 23, 18}, 85, 88);
        btnNext = mkBtn(context, s, "cbuttons",
                new int[]{92, 0, 22, 18}, new int[]{92, 18, 22, 18}, 108, 88);
        btnEject = mkBtn(context, s, "cbuttons",
                new int[]{114, 0, 22, 16}, new int[]{114, 16, 22, 16}, 136, 89);
        // 指示器联动
        btnPlay.setChainClick(new Runnable() {
            @Override public void run() { playState = IND_PLAYING; invalidate(); }
        });
        btnPause.setChainClick(new Runnable() {
            @Override public void run() { playState = IND_PAUSED; invalidate(); }
        });
        btnStop.setChainClick(new Runnable() {
            @Override public void run() { playState = IND_STOPPED; invalidate(); }
        });
        // shuffle / repeat（toggle）
        btnShuffle = mkBtn(context, s, "shufrep",
                new int[]{28, 0, 47, 15}, new int[]{28, 15, 47, 15},
                new int[]{28, 30, 47, 15}, new int[]{28, 45, 47, 15}, 164, 89);
        btnShuffle.setToggle(true);
        btnRepeat = mkBtn(context, s, "shufrep",
                new int[]{0, 0, 28, 15}, new int[]{0, 15, 28, 15},
                new int[]{0, 30, 28, 15}, new int[]{0, 45, 28, 15}, 210, 89);
        btnRepeat.setToggle(true);
        // EQ / 播放列表窗口开关
        btnEqWin = mkBtn(context, s, "shufrep",
                new int[]{0, 61, 23, 12}, new int[]{46, 61, 23, 12},
                new int[]{0, 73, 23, 12}, new int[]{46, 73, 23, 12}, 219, 58);
        // EQ / PL 是窗口开关：selected 帧左上角的圆点会亮绿（和 SHUFFLE 同一套逻辑）。
        // 之前没开 toggle、也没同步 active，所以永远停在常态帧 → 一直是深绿。
        btnEqWin.setToggle(true);
        btnEqWin.setActive(true);   // 两个窗口启动时都是打开的
        btnEqWin.setChainClick(new Runnable() {
            @Override public void run() { toggleSibling(EqWindowView.class, btnEqWin); }
        });
        btnPlWin = mkBtn(context, s, "shufrep",
                new int[]{23, 61, 23, 12}, new int[]{69, 61, 23, 12},
                new int[]{23, 73, 23, 12}, new int[]{69, 73, 23, 12}, 242, 58);
        btnPlWin.setToggle(true);
        btnPlWin.setActive(true);
        btnPlWin.setChainClick(new Runnable() {
            @Override public void run() { toggleSibling(PlaylistWindowView.class, btnPlWin); }
        });
    }

    private WButton mkBtn(Context c, int s, String sheet,
                          int[] n, int[] p, int lx, int ly) {
        return mkBtn(c, s, sheet, n, p, null, null, lx, ly);
    }

    private WButton mkBtn(Context c, int s, String sheet,
                          int[] n, int[] p, int[] sel, int[] selp,
                          int lx, int ly) {
        WButton b = new WButton(c, s, sheet, n, p, sel, selp);
        addChild(b, lx, ly);
        return b;
    }

    private void addChild(View v, int lx, int ly) {
        addView(v);
        children.add(new ChildPos(v, lx, ly));
    }

    /** 切换 EQ / 播放列表窗口的显示，并让按钮的绿点跟着窗口状态亮/灭 */
    private void toggleSibling(Class<?> cls, WButton btn) {
        ViewParent p = getParent();
        if (!(p instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) p;
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (cls.isInstance(v)) {
                boolean show = v.getVisibility() != VISIBLE;
                v.setVisibility(show ? VISIBLE : GONE);
                if (btn != null) btn.setActive(show);
                break;
            }
        }
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

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        tickerRunning = true;
        ui.post(tickerTick);
    }

    @Override
    protected void onDetachedFromWindow() {
        tickerRunning = false;
        ui.removeCallbacks(tickerTick);
        super.onDetachedFromWindow();
    }

    // ---- 触摸：标题栏按钮 + 分析仪切换 ----
    private boolean inRect(float x, float y, int lx, int ly, int w, int h) {
        float bx = (offsetX + lx * S), by = ly * S;
        return x >= bx && x <= bx + w * S && y >= by && y <= by + h * S;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                shadePressed = inRect(x, y, 254, 3, 9, 9);
                closePressed = !shadePressed && inRect(x, y, 264, 3, 9, 9);
                if (shadePressed || closePressed) { invalidate(); return true; }
                // 音量 / 平衡推子拖动（按推子中心对齐手指，行程 = 推子可走距离）
                if (!collapsed && inVol(x, y)) {
                    volDragging = true;
                    setVolValue(volValueAt(x));
                    return true;
                }
                if (!collapsed && inBal(x, y)) {
                    balDragging = true;
                    setBalValue(balValueAt(x));
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (volDragging) {
                    setVolValue(volValueAt(x));
                    return true;
                }
                if (balDragging) {
                    setBalValue(balValueAt(x));
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
                boolean slid = volDragging || balDragging;
                volDragging = false;
                balDragging = false;
                if (slid) invalidate(); // 松手后推子回到常态帧
                boolean shade = shadePressed && inRect(x, y, 254, 3, 9, 9);
                boolean close = closePressed && inRect(x, y, 264, 3, 9, 9);
                shadePressed = false;
                closePressed = false;
                if (shade || close) {
                    setCollapsed(!collapsed);
                    invalidate();
                    return true;
                }
                // 分析仪点击切换频谱/示波器
                if (inRect(x, y, 24, 43, 76, 16)) {
                    spectrumView.toggleMode();
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                volDragging = false;
                balDragging = false;
                shadePressed = false;
                closePressed = false;
                invalidate();
                break;
        }
        return super.onTouchEvent(e);
    }

    private void setCollapsed(boolean c) {
        collapsed = c;
        for (ChildPos cp : children) {
            cp.v.setVisibility(c ? GONE : VISIBLE);
        }
        requestLayout();
    }

    // ---- 绘制 ----
    @Override
    protected void onDraw(Canvas cv) {
        cv.save();
        cv.translate(offsetX, 0);
        cv.scale(S, S);

        // 背景
        assets.sprite(cv, "main", 0, 0, LW, LH, 0, 0);
        // 标题栏
        assets.sprite(cv, "titlebar", 27, 0, 275, 14, 0, 0);
        assets.sprite(cv, "titlebar", 0, 0, 9, 9, 6, 3);    // option
        assets.sprite(cv, "titlebar", 9, 0, 9, 9, 244, 3);  // minimize
        assets.sprite(cv, "titlebar", shadePressed ? 9 : 0, 18, 9, 9, 254, 3);
        assets.sprite(cv, "titlebar", 18, closePressed ? 9 : 0, 9, 9, 264, 3);
        // clutter bar
        assets.sprite(cv, "titlebar", 304, 0, 8, 43, 10, 22);
        // 播放指示器
        int ix = playState == IND_PLAYING ? 0 : playState == IND_PAUSED ? 9 : 18;
        assets.sprite(cv, "playpaus", ix, 0, 9, 9, 26, 28);

        if (!collapsed) {
            drawAnalyzer(cv);
            drawMarquee(cv);
            // kbps / khz（含单位标签，如原版 "192 kbps" / "44.1 kHz"）
            float kw = assets.drawText(cv, metaCtl.kbps, 111, 43);
            if (kw > 0) assets.drawText(cv, " kbps", 111 + kw, 43);
            float hw = assets.drawText(cv, metaCtl.khz, 156, 43);
            if (hw > 0) assets.drawText(cv, " kHz", 156 + hw, 43);
            // mono / stereo
            int ch = metaCtl.channels;
            assets.sprite(cv, "monoster", 29, ch == 1 ? 0 : 12, 27, 12, 212, 41);
            assets.sprite(cv, "monoster", 0, ch == 2 ? 0 : 12, 29, 12, 239, 41);
            // 音量 / 平衡推子（28 帧精灵，直接绘制）
            drawVolBal(cv);
        }
        cv.restore();
    }

    /**
     * 音量 (107,57,68x15) / 平衡 (177,57,38x15)：28 帧背景，frame=round(v*27)；
     * 再叠上 14x11 推子（滑块）——皮肤把推子画在精灵表尾部 y=422 处，
     * 旧代码只画了背景帧、没画推子，所以"按钮"不见了。
     */
    private void drawVolBal(Canvas cv) {
        int vf = Math.round(volValue * 27);
        if (vf < 0) vf = 0;
        if (vf > 27) vf = 27;
        assets.sprite(cv, "volume", 0, vf * 15, VOL_W, SLIDER_H, VOL_X, VOL_Y);
        assets.sprite(cv, "volume", volDragging ? THUMB_X_PRESSED : THUMB_X_NORMAL, THUMB_Y,
                THUMB_W, THUMB_H, VOL_X + Math.round(volValue * VOL_TRAVEL), VOL_Y + 1);

        int bf = Math.round(balValue * 27);
        if (bf < 0) bf = 0;
        if (bf > 27) bf = 27;
        assets.sprite(cv, "balance", BAL_SRC_X, bf * 15, BAL_W, SLIDER_H, BAL_X, BAL_Y);
        assets.sprite(cv, "balance", balDragging ? THUMB_X_PRESSED : THUMB_X_NORMAL, THUMB_Y,
                THUMB_W, THUMB_H, BAL_X + Math.round(balValue * BAL_TRAVEL), BAL_Y + 1);
    }

    /** 音量/平衡触摸区域（像素坐标） */
    private boolean inVol(float x, float y) {
        float bx = offsetX + VOL_X * S, by = VOL_Y * S;
        return x >= bx && x <= bx + VOL_W * S && y >= by && y <= by + SLIDER_H * S;
    }

    private boolean inBal(float x, float y) {
        float bx = offsetX + BAL_X * S, by = BAL_Y * S;
        return x >= bx && x <= bx + BAL_W * S && y >= by && y <= by + SLIDER_H * S;
    }

    /** 触摸点 -> 音量 0..1：让推子中心跟手（行程为推子可移动距离） */
    private float volValueAt(float x) {
        return (x - offsetX - (VOL_X + THUMB_W / 2f) * S) / (VOL_TRAVEL * (float) S);
    }

    private float balValueAt(float x) {
        return (x - offsetX - (BAL_X + THUMB_W / 2f) * S) / (BAL_TRAVEL * (float) S);
    }

    private float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    public void setVolValue(float v) {
        float nv = clamp01(v);
        if (nv != volValue) {
            volValue = nv;
            invalidate();
            if (volListener != null) volListener.onChange(nv);
        }
    }

    public void setBalValue(float v) {
        float nv = clamp01(v);
        if (nv != balValue) {
            balValue = nv;
            invalidate();
            if (balListener != null) balListener.onChange(nv);
        }
    }

    public float getVolValue() { return volValue; }
    public float getBalValue() { return balValue; }
    public void setVolChangeListener(WSlider.OnChangeListener l) { volListener = l; }
    public void setBalChangeListener(WSlider.OnChangeListener l) { balListener = l; }

    private void drawMarquee(Canvas cv) {
        cv.save();
        cv.clipRect(111, 24, 111 + 154, 24 + 12);
        assets.drawText(cv, tickerView.getText(), 111 - tickerView.getOffset(), 27);
        cv.restore();
    }

    private void drawAnalyzer(Canvas cv) {
        final int ax = 24, ay = 43, aw = 76, ah = 16;
        // 底
        barPaint.setColor(0xFF000000);
        cv.drawRect(ax, ay, ax + aw, ay + ah, barPaint);
        // 关闭模式：空白黑框，不画任何内容
        if (spectrumView.isOff()) return;
        byte[] fft = spectrumView.getFft();
        byte[] wave = spectrumView.getWave();
        if (spectrumView.isOscilloscope() && wave != null && wave.length > 1) {
            wavePath.reset();
            for (int i = 0; i < aw; i++) {
                int idx = i * wave.length / aw;
                float v = (wave[idx] & 0xFF) / 128f - 1f;
                float yy = ay + ah / 2f - v * (ah / 2f - 1);
                if (i == 0) wavePath.moveTo(ax + i, yy);
                else wavePath.lineTo(ax + i, yy);
            }
            barPaint.setStyle(Paint.Style.STROKE);
            barPaint.setStrokeWidth(1);
            barPaint.setColor(SkinAssets.VIS_COLORS[18]);
            cv.drawPath(wavePath, barPaint);
            barPaint.setStyle(Paint.Style.FILL);
            return;
        }
        if (fft == null || fft.length < 4) {
            // 静默基准线
            barPaint.setColor(SkinAssets.VIS_COLORS[1]);
            cv.drawRect(ax, ay + ah / 2f, ax + aw, ay + ah / 2f + 1, barPaint);
            return;
        }
        // 经典频谱柱：19 柱，每柱 4px（3px 柱 + 1px 间隔），按高度取 viscolor 渐变
        int bars = 19;
        int bins = fft.length / 2;
        for (int i = 0; i < bars; i++) {
            int bin0 = 1 + (int) (Math.pow((double) i / bars, 2.0) * (bins - 2));
            int bin1 = 1 + (int) (Math.pow((double) (i + 1) / bars, 2.0) * (bins - 2));
            if (bin1 < bin0) bin1 = bin0;
            float mag = 0;
            for (int k = bin0; k <= bin1 && 2 * k + 1 < fft.length; k++) {
                float m = (float) Math.hypot(fft[2 * k], fft[2 * k + 1]);
                if (m > mag) mag = m;
            }
            float db = (float) (20 * Math.log10(mag + 1));
            float v = Math.max(0f, Math.min(1f, db / 42f));
            if (v > peaks[i]) peaks[i] = v;
            else peaks[i] = Math.max(0f, peaks[i] - 0.03f);

            int x0 = ax + i * 4;
            int bh = Math.round(v * ah);
            // 按行取色：顶部红 -> 底部绿（VIS_COLORS[2..17]）
            for (int yy = 0; yy < bh; yy++) {
                int ci = 2 + (int) ((1f - (float) yy / ah) * 15);
                if (ci < 2) ci = 2;
                if (ci > 17) ci = 17;
                barPaint.setColor(SkinAssets.VIS_COLORS[ci]);
                cv.drawRect(x0, ay + ah - yy - 1, x0 + 3, ay + ah - yy, barPaint);
            }
            // 峰值点
            int py = ay + ah - Math.round(peaks[i] * ah) - 1;
            peakPaint.setColor(SkinAssets.VIS_COLORS[23]);
            cv.drawRect(x0, py, x0 + 3, py + 1, peakPaint);
        }
    }

    // ---- 对外 API（MainActivity 用，不可改签名） ----
    public SpectrumView getSpectrumView() { return spectrumView; }
    public TickerView getTickerView() { return tickerView; }
    public SkinTimeView getTimeView() { return timeView; }
    public MetaCtl getMetaView() { return metaCtl; }
    public WSlider getSeekSlider() { return seekSlider; }
    public WButton getBtnPrev() { return btnPrev; }
    public WButton getBtnPlay() { return btnPlay; }
    public WButton getBtnPause() { return btnPause; }
    public WButton getBtnStop() { return btnStop; }
    public WButton getBtnNext() { return btnNext; }
    public WButton getBtnEject() { return btnEject; }
    public WButton getBtnShuffle() { return btnShuffle; }
    public WButton getBtnRepeat() { return btnRepeat; }
}
