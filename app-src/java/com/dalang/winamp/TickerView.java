package com.dalang.winamp;

/**
 * 跑马灯文本持有类（绘制由 MainWindowView 用 text.bmp 字体完成）。
 */
public class TickerView {

    private String text = "*** winamp ***";
    private float offset = 0;
    private Runnable invalidator;

    public void setInvalidator(Runnable r) { invalidator = r; }

    public void setText(String t) {
        text = "   " + (t == null ? "" : t) + "   ***   ";
        offset = 0;
        fire();
    }

    public String getText() { return text; }
    public float getOffset() { return offset; }

    /** 每帧推进，由 MainWindowView 的 handler 驱动 */
    public void tick(float dx, float textWidth, float viewWidth) {
        offset += dx;
        // 文字完全滚出左边界后回到开头，避免 -viewWidth 带来的数秒空白期
        if (textWidth > 0 && offset > textWidth) offset = 0;
        fire();
    }

    private void fire() {
        Runnable r = invalidator;
        if (r != null) r.run();
    }
}
