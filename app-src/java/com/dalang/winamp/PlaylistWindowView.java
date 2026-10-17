package com.dalang.winamp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 播放列表窗口：固定 275 逻辑宽，高度由父容器分配（weight=1）。
 *
 * 全部按 pledit.bmp 还原，坐标取自 Webamp 的 js/skinSprites.ts + css/playlist-window.css，
 * 并与原版 Winamp 截图逐像素核对过：
 *   顶栏   20px  TitleBarView（左角 25 + TILE 平铺 + TITLE 100 居中 + 右角 25）
 *   中段   左右边框 12 / 20，用 29px 一段纵向平铺；列表区 12..255
 *   滚动条 右栏内 x+5，手柄 8x18（常态 52,53 / 按下 61,53）
 *   底部   38px：左角 125 + 右角 150（ADD/REM/SEL/MISC、走带键、LIST OPTS、
 *          时间框、右下缩放手柄全部烘焙在位图里），时间文字画在 (132, H-38+10)
 */
public class PlaylistWindowView extends FrameLayout {

    private static final int LW = 275;
    private static final int TOP_H = 20;
    private static final int BOTTOM_H = 38;
    private static final int LEFT_W = 12;
    private static final int RIGHT_W = 20;
    private static final int LIST_PAD = 3;
    private static final int SCROLL_X = 5, SCROLL_W = 8, HANDLE_H = 18;
    private static final int BTN_W = 22, BTN_H = 18;
    /** 底部四个按钮 + LIST OPTS 的逻辑 x（Webamp: left 14/43/72/101，LIST OPTS right 22） */
    private static final int[] BTN_X = {14, 43, 72, 101, 275 - 22 - 22};
    private static final int BTN_Y = BOTTOM_H - 12 - BTN_H; // 8

    private final SkinAssets assets;
    private final int S;
    private final TitleBarView title;
    private final ListView listView;
    private final Scrollbar scrollbar;
    private final View[] hot = new View[5];
    private final TextView statusProxy;
    private final Paint paint = new Paint();
    private int offsetX = 0;
    private boolean collapsed = false;
    private String totalTime = "0:00";

    public PlaylistWindowView(Context context) {
        super(context);
        assets = SkinAssets.get(context);
        S = SkinAssets.scale(context);
        setWillNotDraw(false);

        // ---- 标题栏 ----
        title = new TitleBarView(context);
        addView(title);
        title.setOnShadeListener(new TitleBarView.OnShadeListener() {
            @Override
            public void onShade(boolean shaded) { setCollapsed(shaded); }
        });

        // ---- 滚动条（先建，列表的滚动回调要用）----
        scrollbar = new Scrollbar(context);
        addView(scrollbar);

        // ---- 列表 ----
        listView = new ListView(context);
        listView.setBackgroundColor(0xFF000000);
        listView.setCacheColorHint(0x00000000);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setPadding(0, 0, 0, 0);
        listView.setVerticalScrollBarEnabled(false);
        listView.setHorizontalScrollBarEnabled(false);
        listView.setOverScrollMode(OVER_SCROLL_NEVER);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView v, int state) { }

            @Override
            public void onScroll(AbsListView v, int first, int visible, int total) {
                scrollbar.invalidate();
            }
        });
        addView(listView);

        // ---- 底部按钮热区（按钮图案烘焙在 pledit 底栏里，这里只做点击）----
        for (int i = 0; i < hot.length; i++) {
            hot[i] = new Hotspot(context);
            addView(hot[i]);
        }
        hot[4].setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(getContext(), "LIST OPTS 暂未实现", Toast.LENGTH_SHORT).show();
            }
        });

        // 状态代理：MainActivity 调 getStatusView().setText("3 files · total 12:51")
        statusProxy = new TextView(context) {
            @Override
            public void setText(CharSequence text, BufferType type) {
                super.setText(text, type);
                if (text == null) return;
                String st = text.toString().trim();
                int sp = st.lastIndexOf(' ');
                if (sp >= 0 && sp + 1 < st.length()) {
                    totalTime = st.substring(sp + 1);
                    invalidate();
                }
            }
        };
    }

    // ---------------------------------------------------------------- 尺寸

    private static int exactly(int n) {
        return MeasureSpec.makeMeasureSpec(Math.max(0, n), MeasureSpec.EXACTLY);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        if (MeasureSpec.getMode(hSpec) != MeasureSpec.EXACTLY) h = Math.max(h, 200 * S);
        setMeasuredDimension(w, h);

        title.measure(exactly(LW * S), exactly(TOP_H * S));
        int listH = h - (TOP_H + BOTTOM_H + 2 * LIST_PAD) * S;
        listView.measure(exactly(w - (LEFT_W + RIGHT_W) * S), exactly(listH));
        scrollbar.measure(exactly(SCROLL_W * S),
                exactly(h - (TOP_H + BOTTOM_H) * S));
        for (View v : hot) v.measure(exactly(BTN_W * S), exactly(BTN_H * S));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        offsetX = Math.max(0, (w - LW * S) / 2);

        title.layout(offsetX, 0, offsetX + LW * S, TOP_H * S);
        if (collapsed) return;

        int lx = offsetX + LEFT_W * S;
        int rx = offsetX + (LW - RIGHT_W) * S;
        int ly = (TOP_H + LIST_PAD) * S;
        int by = h - (BOTTOM_H + LIST_PAD) * S;
        if (by < ly) by = ly;
        listView.layout(lx, ly, rx, by);

        int sx = offsetX + (LW - RIGHT_W + SCROLL_X) * S;
        scrollbar.layout(sx, TOP_H * S, sx + SCROLL_W * S, h - BOTTOM_H * S);

        int btnY = h - (BOTTOM_H - BTN_Y) * S;
        for (int i = 0; i < hot.length; i++) {
            int x = offsetX + BTN_X[i] * S;
            hot[i].layout(x, btnY, x + BTN_W * S, btnY + BTN_H * S);
        }
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas cv) {
        if (collapsed) return;
        int h = getHeight() / S;
        int bottomY = h - BOTTOM_H;
        if (bottomY <= TOP_H) return;

        cv.save();
        cv.translate(offsetX, 0);
        cv.scale(S, S);

        // 中段底色（PLEDIT NormalBG）
        paint.setColor(0xFF000000);
        cv.drawRect(0, TOP_H, LW, bottomY, paint);

        // 左右边框 / 滚动条槽：29px 一段纵向平铺
        for (int y = TOP_H; y < bottomY; y += 29) {
            int hh = Math.min(29, bottomY - y);
            assets.sprite(cv, "pledit", 0, 42, LEFT_W, hh, 0, y);
            assets.sprite(cv, "pledit", 31, 42, RIGHT_W, hh, LW - RIGHT_W, y);
        }

        // 底部：左角 125 + 右角 150
        assets.sprite(cv, "pledit", 0, 72, 125, BOTTOM_H, 0, bottomY);
        assets.sprite(cv, "pledit", 126, 72, 150, BOTTOM_H, 125, bottomY);

        // 时间显示：原版固定画在 (132, 底栏+10)，用 text.bmp 的像素字体
        String t = "0:00/" + totalTime;
        assets.drawText(cv, t, 132, bottomY + 10);

        cv.restore();
    }

    // ---------------------------------------------------------------- 折叠

    private void setCollapsed(boolean c) {
        collapsed = c;
        int vis = c ? GONE : VISIBLE;
        listView.setVisibility(vis);
        scrollbar.setVisibility(vis);
        for (View v : hot) v.setVisibility(vis);

        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) lp;
            p.weight = c ? 0f : 1f;
            p.height = c ? TOP_H * S : 0;
            setLayoutParams(p);
        }
        requestLayout();
        invalidate();
    }

    // ---------------------------------------------------------------- 滚动条

    /** 右栏滚动手柄：8x18 精灵，位置由 ListView 的滚动量换算 */
    private class Scrollbar extends View {
        private boolean dragging = false;
        private float dragDY = 0;

        Scrollbar(Context c) { super(c); }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(SCROLL_W * S, MeasureSpec.getSize(hSpec));
        }

        private int maxScrollPx() {
            int rowH = PlaylistAdapter.ROW_H * S;
            int viewH = listView.getHeight();
            if (viewH <= 0) return 0;
            return Math.max(0, listView.getCount() * rowH - viewH);
        }

        private float fraction() {
            int max = maxScrollPx();
            if (max <= 0) return 0f;
            int rowH = PlaylistAdapter.ROW_H * S;
            int first = listView.getFirstVisiblePosition();
            View c0 = listView.getChildAt(0);
            int scrolled = first * rowH + (c0 == null ? 0 : -c0.getTop());
            return Math.max(0f, Math.min(1f, scrolled / (float) max));
        }

        @Override
        protected void onDraw(Canvas cv) {
            int track = getHeight() / S - HANDLE_H;   // 逻辑像素
            if (track < 0) return;
            int y = Math.round(fraction() * track);
            assets.spriteScaled(cv, "pledit", dragging ? 61 : 52, 53,
                    SCROLL_W, HANDLE_H, 0, y, S);
        }

        private void scrollToTouch(float y, float dy) {
            int track = getHeight() - HANDLE_H * S;
            if (track <= 0) return;
            float f = (y - dy) / (float) track;
            f = Math.max(0f, Math.min(1f, f));
            int max = maxScrollPx();
            int rowH = PlaylistAdapter.ROW_H * S;
            int px = Math.round(f * max);
            listView.setSelectionFromTop(px / rowH, -(px % rowH));
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    dragging = true;
                    float hy = fraction() * (getHeight() - HANDLE_H * S);
                    dragDY = e.getY() - hy;
                    if (dragDY < 0 || dragDY > HANDLE_H * S) {
                        // 点在滑槽上：手柄中心直接跳过来
                        dragDY = HANDLE_H * S / 2f;
                    }
                    scrollToTouch(e.getY(), dragDY);
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    if (dragging) scrollToTouch(e.getY(), dragDY);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }

    /** 底部按钮的透明点击热区（图案已经烘焙在底栏位图里） */
    private static class Hotspot extends View {
        Hotspot(Context c) {
            super(c);
            setClickable(true);
        }
    }

    // ---------------------------------------------------------------- 对外 API

    public ListView getListView() { return listView; }
    public TextView getStatusView() { return statusProxy; }
    public View getBtnAdd() { return hot[0]; }
    public View getBtnRem() { return hot[1]; }
    public View getBtnSel() { return hot[2]; }
    public View getBtnMisc() { return hot[3]; }
}
