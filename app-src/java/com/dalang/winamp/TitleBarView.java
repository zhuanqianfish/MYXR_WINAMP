package com.dalang.winamp;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

/**
 * 播放列表标题栏：pledit.bmp 平铺（左角 25x20 + 中段 100x20 平铺 + 右角 25x20），
 * 右侧关闭/折叠键（9x9），点击切换折叠（保持原有行为）。
 */
public class TitleBarView extends View {

    public interface OnShadeListener {
        void onShade(boolean collapsed);
    }

    private final SkinAssets assets;
    private final int S;
    private boolean collapsed = false;
    private boolean closePressed = false;
    private boolean shadePressed = false;
    private OnShadeListener shadeListener;

    public TitleBarView(Context c) {
        super(c);
        S = SkinAssets.scale(c);
        assets = SkinAssets.get(c);
        setClickable(true);
    }

    public void setTitle(String t) { /* 皮肤标题栏无文字 */ }

    public void setOnShadeListener(OnShadeListener l) { shadeListener = l; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), 20 * S);
    }

    private int btnX(boolean close) {
        // 逻辑坐标：close 在右起 11，collapse 在右起 22
        return (getWidth() / S) - (close ? 11 : 22);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth() / S; // 逻辑宽度
        // 左角
        assets.spriteScaled(cv, "pledit", 0, 21, 25, 20, 0, 0, S);
        // 中段：Webamp 布局（css/playlist-window.css）——
        // "WINAMP PLAYLIST" 烘焙在 TITLE 精灵 (26,21,100,20) 里，只画一次并居中；
        // 两侧用素面 TILE (127,21,25,20) 填充，绝不平铺 TITLE。
        int midW = w - 25 - 25;          // 左右角之间的宽度
        int titleX = 25 + Math.max(0, (midW - 100) / 2);
        int x = 25;
        while (x < titleX) {
            int tw = Math.min(25, titleX - x);
            assets.spriteScaled(cv, "pledit", 127, 21, tw, 20, x, 0, S);
            x += tw;
        }
        assets.spriteScaled(cv, "pledit", 26, 21, 100, 20, titleX, 0, S);
        x = titleX + 100;
        int rightStart = w - 25;
        while (x < rightStart) {
            int tw = Math.min(25, rightStart - x);
            assets.spriteScaled(cv, "pledit", 127, 21, tw, 20, x, 0, S);
            x += tw;
        }
        // 右角
        assets.spriteScaled(cv, "pledit", 153, 21, 25, 20, w - 25, 0, S);
        // 按钮（按下时下沉 1px，经典按压效果）
        int cy = 5;
        assets.spriteScaled(cv, "pledit", 52, 42, 9, 9,
                btnX(true) + (closePressed ? 1 : 0), cy + (closePressed ? 1 : 0), S);
        assets.spriteScaled(cv, "pledit", 62, 42, 9, 9,
                btnX(false) + (shadePressed ? 1 : 0), cy + (shadePressed ? 1 : 0), S);
    }

    private boolean inBtn(float x, float y, boolean close) {
        float bx = btnX(close) * S, by = 5 * S, bs = 9 * S;
        return x >= bx && x <= bx + bs && y >= by && y <= by + bs;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                closePressed = inBtn(x, y, true);
                shadePressed = !closePressed && inBtn(x, y, false);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                boolean wasClose = closePressed && inBtn(x, y, true);
                boolean wasShade = shadePressed && inBtn(x, y, false);
                closePressed = false;
                shadePressed = false;
                invalidate();
                if (wasClose || wasShade) {
                    collapsed = !collapsed;
                    if (shadeListener != null) shadeListener.onShade(collapsed);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                closePressed = false;
                shadePressed = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }
}
