package com.dalang.winamp;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

/**
 * 皮肤精灵按钮：normal / pressed / selected / selected+pressed 四态。
 * 无对应态时可传 null（回退到 normal）。MainActivity 只依赖
 * setToggle/setActive/isActive/setOnClickListener。
 */
public class WButton extends View {

    private final SkinAssets assets;
    private final String sheet;
    private final int[] normal, pressed, selected, selPressed;
    private final int S;
    private final int lw, lh; // 逻辑宽高
    private boolean isPressed = false;
    private boolean active = false;
    private boolean toggle = false;
    private Runnable chainClick;

    /**
     * @param n  normal {x,y,w,h}，p/s/sp 可为 null（回退 normal）
     */
    public WButton(Context c, int scale, String sheet,
                   int[] n, int[] p, int[] s, int[] sp) {
        super(c);
        S = scale;
        assets = SkinAssets.get(c);
        this.sheet = sheet;
        normal = n;
        pressed = p != null ? p : n;
        selected = s != null ? s : n;
        selPressed = sp != null ? sp : pressed;
        lw = n[2];
        lh = n[3];
        setClickable(true);
        setFocusable(true);
    }

    /** 内部链式点击（先执行内部逻辑，再触发外部 OnClickListener） */
    public void setChainClick(Runnable r) { chainClick = r; }

    public void setToggle(boolean t) { toggle = t; invalidate(); }

    public void setActive(boolean a) {
        if (active != a) {
            active = a;
            invalidate();
        }
    }

    public boolean isActive() { return active; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(lw * S, lh * S);
    }

    @Override
    protected void onDraw(Canvas cv) {
        // 非 toggle 按钮（如 PLAY）的 active 态也显示 pressed 帧，
        // 用于表示播放中按下态；toggle 按钮的 active 显示 selected 帧。
        int[] sp;
        if (isPressed) {
            sp = (active && toggle) ? selPressed : pressed;
        } else if (active) {
            sp = toggle ? selected : pressed;
        } else {
            sp = normal;
        }
        assets.spriteScaled(cv, sheet, sp[0], sp[1], sp[2], sp[3], 0, 0, S);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isPressed = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                isPressed = false;
                invalidate();
                if (e.getX() >= 0 && e.getX() <= getWidth()
                        && e.getY() >= 0 && e.getY() <= getHeight()) {
                    if (toggle) {
                        active = !active;
                        invalidate();
                    }
                    if (chainClick != null) chainClick.run();
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                isPressed = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
