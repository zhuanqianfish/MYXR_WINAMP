package com.dalang.winamp;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;

/** Winamp 经典皮肤配色与工具 */
public final class Skin {
    private Skin() {}

    public static final int BG_WINDOW = 0xFF1E1E1E;
    public static final int BG_PANEL  = 0xFF141414;
    public static final int BG_BLACK  = 0xFF000000;
    public static final int BORDER    = 0xFF3A3A3A;
    public static final int BORDER_LT = 0xFF5A5A5A;

    public static final int GREEN     = 0xFF2BFF2B;
    public static final int GREEN_DIM = 0xFF1E8C1E;
    public static final int TICKER    = 0xFFD8FFD8;
    public static final int AMBER     = 0xFFFFB300;
    public static final int WHITE     = 0xFFEDEDED;
    public static final int GRAY      = 0xFF9A9A9A;
    public static final int RED       = 0xFFFF5252;

    public static final int TITLE_TOP  = 0xFF3D3D4D;
    public static final int TITLE_BOT  = 0xFF191920;
    public static final int TITLE_TEXT = 0xFFD8D8E8;

    public static Typeface mono() { return Typeface.MONOSPACE; }

    public static int dp(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    public static int sp(Context c, float sp) {
        return Math.round(sp * c.getResources().getDisplayMetrics().scaledDensity);
    }

    public static String fmtTime(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + ":" + pad(m) + ":" + pad(sec);
        return m + ":" + pad(sec);
    }

    private static String pad(long v) { return (v < 10 ? "0" : "") + v; }

    /** 窗口背景：深灰细腻纵向渐变 + 细边框，还原经典皮肤质感 */
    public static GradientDrawable windowBackground(Context c) {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF262626, 0xFF1B1B1B});
        bg.setStroke(dp(c, 1), BORDER);
        return bg;
    }
}
