package com.dalang.winamp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/**
 * 播放列表窗口：标题栏 + 列表（填充剩余高度）+ 底部操作区。
 * 底部对照原版 Winamp / Webamp playlist-window.css：
 *  - 第1行：ADD/REM/SEL/MISC 左对齐（单行小字）+ 右侧时间 "0:00/总时长"
 *  - 第2行：一小排 10x10 播放小按钮（仅显示）+ 右下角 LIST OPTS（toast）
 * 列表高度由父容器 weight 分配，不再固定 200dp。
 */
public class PlaylistWindowView extends LinearLayout {

    private final ListView listView;
    private final TextView timeView;
    private final TextBtn btnAdd, btnRem, btnSel, btnMisc;
    private final TextView statusProxy;
    private String totalTime = "0:00";

    public PlaylistWindowView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBackground(Skin.windowBackground(context));
        int s = SkinAssets.scale(context);

        TitleBarView title = new TitleBarView(context);
        title.setTitle("WINAMP PLAYLIST");
        addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // 列表：weight=1 填充标题栏与底部之间的全部空间
        listView = new ListView(context);
        listView.setBackgroundColor(Skin.BG_BLACK);
        listView.setDividerHeight(Skin.dp(context, 1));
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        LayoutParams listLp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.topMargin = Skin.dp(context, 2);
        addView(listView, listLp);
        title.setOnShadeListener(collapsed ->
                listView.setVisibility(collapsed ? View.GONE : View.VISIBLE));

        // 底部操作区（pledit 底部 38px 高，用 tile 平铺做底）
        BottomBg bottomBg = new BottomBg(context, s);
        bottomBg.setOrientation(VERTICAL);
        int bpad = Skin.dp(context, 3);
        bottomBg.setPadding(bpad, Skin.dp(context, 2), bpad, Skin.dp(context, 2));
        addView(bottomBg, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));

        // 第1行：四按钮左对齐 + 右侧时间
        LinearLayout row1 = new LinearLayout(context);
        row1.setOrientation(HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        bottomBg.addView(row1, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));

        // 四按钮：单行小字 ADD/REM/SEL/MISC（base skin 位图里只有两行版，按用户要求改单行文字）
        btnAdd = new TextBtn(context, s, "ADD");
        btnRem = new TextBtn(context, s, "REM");
        btnSel = new TextBtn(context, s, "SEL");
        btnMisc = new TextBtn(context, s, "MISC");
        for (TextBtn b : new TextBtn[]{btnAdd, btnRem, btnSel, btnMisc}) {
            LayoutParams lp = new LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, Skin.dp(context, 3), 0);
            row1.addView(b, lp);
        }
        // 右侧时间：原版格式 "已选/总时长"，已选暂按 0:00
        timeView = new TextView(context);
        timeView.setTypeface(Skin.mono());
        timeView.setTextSize(10);
        timeView.setTextColor(Skin.GREEN);
        timeView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        timeView.setSingleLine(true);
        LayoutParams timeLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        row1.addView(timeView, timeLp);
        updateTimeDisplay();

        // 第2行（小按钮排 + LIST OPTS）已按用户要求去掉：未实现的功能不显示

        // 状态代理：MainActivity 调 getStatusView().setText("3 files · total 12:51")，
        // 此处解析出总时长更新右侧时间显示（保持 API 兼容，不改 MainActivity）
        statusProxy = new TextView(context) {
            @Override
            public void setText(CharSequence text, BufferType type) {
                super.setText(text, type);
                if (text != null) {
                    String st = text.toString().trim();
                    int sp = st.lastIndexOf(' ');
                    if (sp >= 0 && sp + 1 < st.length()) {
                        totalTime = st.substring(sp + 1);
                        updateTimeDisplay();
                    }
                }
            }
        };
    }

    private void updateTimeDisplay() {
        if (timeView != null) timeView.setText("0:00/" + totalTime);
    }

    /** 底部背景：pledit BOTTOM_TILE (179,0,25,38) 横向平铺 */
    private static class BottomBg extends LinearLayout {
        private final SkinAssets assets;
        private final int S;
        BottomBg(Context c, int s) {
            super(c);
            S = s;
            assets = SkinAssets.get(c);
            setWillNotDraw(false);
        }
        @Override
        protected void onDraw(Canvas cv) {
            // 先画底色，再在逻辑坐标系下平铺 tile
            cv.drawColor(0xFF1a1c22);
            cv.save();
            cv.scale(S, S);
            int w = getWidth() / S; // 逻辑宽度
            int x = 0;
            while (x < w) {
                int tw = Math.min(25, w - x);
                assets.sprite(cv, "pledit", 179, 0, tw, 38, x, 0);
                x += tw;
            }
            cv.restore();
            super.onDraw(cv);
        }
    }

    /** 单行文字按钮（ADD/REM/SEL/MISC）：皮肤字体的单行小字 + 简易凸起边框 */
    public static class TextBtn extends View {
        private final SkinAssets assets;
        private final int S;
        private final String label;
        private final Paint bgPaint = new Paint();
        private final Paint ltPaint = new Paint();
        private final Paint dkPaint = new Paint();
        private OnClickListener clickListener;

        TextBtn(Context c, int s, String label) {
            super(c);
            S = s;
            this.label = label;
            assets = SkinAssets.get(c);
            bgPaint.setColor(0xFFbdbdbd);
            ltPaint.setColor(0xFFe0e0e0);
            dkPaint.setColor(0xFF7a7a7a);
            setClickable(true);
        }

        @Override
        public void setOnClickListener(OnClickListener l) {
            clickListener = l;
            super.setOnClickListener(l);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            // 文字宽度 + 边距
            int tw = label.length() * 5 + 8;
            if (tw < 30) tw = 30;
            setMeasuredDimension(tw * S, 16 * S);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int W = getWidth(), H = getHeight();
            // 底 + 凸起边框（左上亮、右下暗）
            cv.drawRect(0, 0, W, H, bgPaint);
            cv.drawRect(0, 0, W, S, ltPaint);
            cv.drawRect(0, 0, S, H, ltPaint);
            cv.drawRect(0, H - S, W, H, dkPaint);
            cv.drawRect(W - S, 0, W, H, dkPaint);
            // 文字居中（逻辑坐标系）
            cv.save();
            cv.scale(S, S);
            float tw = label.length() * 5f;
            float th = 6f;
            float lw = getWidth() / (float) S;
            float lh = getHeight() / (float) S;
            assets.drawText(cv, label, (lw - tw) / 2f, (lh - th) / 2f);
            cv.restore();
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                if (clickListener != null) clickListener.onClick(this);
                return true;
            }
            return super.onTouchEvent(e);
        }
    }

    public ListView getListView() { return listView; }
    /** 返回状态代理 TextView（MainActivity 行为不变，文字用于解析总时长） */
    public TextView getStatusView() { return statusProxy; }
    public TextBtn getBtnAdd() { return btnAdd; }
    public TextBtn getBtnRem() { return btnRem; }
    public TextBtn getBtnSel() { return btnSel; }
    public TextBtn getBtnMisc() { return btnMisc; }
}
