package com.dalang.winamp;

/*
 * Winamp 2.x 经典 base skin 位图资源管理。
 *
 * skin art (c) Nullsoft —— 取自 Webamp 项目
 * (https://github.com/captbaritone/webamp, MIT (c) Jordan Eldredge) 仓库内
 * 自带的 base-2.91 皮肤位图，仅作个人致敬用途。
 * 精灵坐标与字体映射同样参考 Webamp 的 skinSprites.ts。
 *
 * 绘制约定：所有坐标均为 Winamp 逻辑像素（主窗口 275x116），调用方先
 * canvas.scale(S, S) 再以逻辑坐标绘制；位图全部关闭双线性过滤以保持
 * 像素锐利。
 */

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class SkinAssets {

    private static final String TAG = "SkinAssets";
    private static final String DIR = "skin/";

    private static SkinAssets instance;

    public static synchronized SkinAssets get(Context c) {
        if (instance == null) instance = new SkinAssets(c.getApplicationContext());
        return instance;
    }

    // ---- 位图 ----
    private final Map<String, Bitmap> sheets = new HashMap<>();
    private final Paint bmpPaint = new Paint();

    // text.bmp 字体：字符 -> [列, 行]，每字 5x6
    private static final int[][] FONT = new int[128][];
    static {
        String rows012 = "abcdefghijklmnopqrstuvwxyz\"@";
        for (int i = 0; i < rows012.length(); i++) FONT[rows012.charAt(i)] = new int[]{i, 0};
        FONT[' '] = new int[]{30, 0};
        // 注意：base skin 的 text.bmp 里反斜杠在前、正斜杠在后（第 20/21 格），
        // 这里必须按位图顺序写 '\\' 再 '/'，否则时间里的 "0:00/12:51" 会画成反斜杠。
        String r1 = "0123456789….:()-'!_+\\/[]^&%,=$#";
        // 注意：'\\' 在 Java 源码里写作 '\\\\'
        for (int i = 0; i < r1.length(); i++) {
            char ch = r1.charAt(i);
            if (ch < 128) FONT[ch] = new int[]{i, 1};
        }
        FONT['?'] = new int[]{3, 2};
        FONT['*'] = new int[]{4, 2};
        FONT['<'] = new int[]{22, 1};
        FONT['>'] = new int[]{23, 1};
        FONT['{'] = new int[]{22, 1};
        FONT['}'] = new int[]{23, 1};
    }

    // 分析仪 24 色（取自 base skin VISCOLOR.TXT）
    public static final int[] VIS_COLORS = {
            0xFF000000, 0xFF182129, 0xFFEF3110, 0xFFCE2910, 0xFFD65A00,
            0xFFD66600, 0xFFD67300, 0xFFC67B08, 0xFFDEA518, 0xFFD6B521,
            0xFFBDDE29, 0xFF94DE21, 0xFF29CE10, 0xFF32BE10, 0xFF39B510,
            0xFF319C08, 0xFF299400, 0xFF188408, 0xFFFFFFFF, 0xFFD6D6DE,
            0xFFB5BDBD, 0xFFA0AAAF, 0xFF949CA5, 0xFF969696,
    };

    private SkinAssets(Context c) {
        bmpPaint.setFilterBitmap(false);
        bmpPaint.setAntiAlias(false);
        AssetManager am = c.getAssets();
        String[] names = {"main", "titlebar", "cbuttons", "text", "numbers",
                "volume", "balance", "posbar", "playpaus", "shufrep",
                "monoster", "eqmain", "eq_ex", "pledit"};
        for (String n : names) {
            InputStream in = null;
            try {
                in = am.open(DIR + n + ".bmp");
                Bitmap b = BitmapFactory.decodeStream(in);
                if (b != null) sheets.put(n, b);
                else Log.w(TAG, "decode null: " + n);
            } catch (IOException e) {
                Log.w(TAG, "missing skin bmp: " + n, e);
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) {}
            }
        }
        Log.i(TAG, "loaded sheets: " + sheets.size());
    }

    public Bitmap sheet(String name) { return sheets.get(name); }

    /** 按逻辑坐标绘制精灵（调用方已做 canvas.scale(S,S)） */
    public void sprite(Canvas cv, String sheet, int sx, int sy, int sw, int sh,
                       float dx, float dy) {
        Bitmap b = sheets.get(sheet);
        if (b == null || sw <= 0 || sh <= 0) return;
        Rect src = new Rect(sx, sy, sx + sw, sy + sh);
        Rect dst = new Rect(Math.round(dx), Math.round(dy),
                Math.round(dx + sw), Math.round(dy + sh));
        cv.drawBitmap(b, src, dst, bmpPaint);
    }

    /**
     * 直接按整数倍缩放绘制（子 View 自绘时用，调用方不做 canvas.scale）。
     * dx/dy 为逻辑坐标，内部统一乘以 s（之前漏乘导致非零坐标绘制错位）。
     */
    public void spriteScaled(Canvas cv, String sheet, int sx, int sy, int sw, int sh,
                             float dx, float dy, int s) {
        Bitmap b = sheets.get(sheet);
        if (b == null || sw <= 0 || sh <= 0 || s <= 0) return;
        Rect src = new Rect(sx, sy, sx + sw, sy + sh);
        Rect dst = new Rect(Math.round(dx * s), Math.round(dy * s),
                Math.round((dx + sw) * s), Math.round((dy + sh) * s));
        cv.drawBitmap(b, src, dst, bmpPaint);
    }

    /** text.bmp 字体绘制，返回绘制宽度（逻辑像素） */
    public float drawText(Canvas cv, String text, float x, float y) {
        if (text == null) return 0;
        float cx = x;
        for (int i = 0; i < text.length(); i++) {
            char ch = Character.toLowerCase(text.charAt(i));
            if (ch == '…') ch = '.'; // 省略号不在 128 映射内，退化为句点
            int[] pos = ch < 128 ? FONT[ch] : null;
            if (pos != null) {
                sprite(cv, "text", pos[0] * 5, pos[1] * 6, 5, 6, cx, y);
            }
            cx += 5;
        }
        return cx - x;
    }

    public float textWidth(String text) {
        return text == null ? 0 : text.length() * 5f;
    }

    /**
     * numbers.bmp 时间绘制：接受 "-M:SS" / "M:SS"。
     * 位置严格对齐 Webamp（css/main-window.css #time）：
     * minus 在 x-1，分钟两位在 x+9/x+21，秒两位在 x+39/x+51。
     * base skin 没有冒号精灵（Webamp 同样不画），分秒之间留空隙。
     * @param x #time 左上角逻辑 x（Webamp 中为 39）
     */
    public void drawTime(Canvas cv, String time, float x, float y) {
        if (time == null) return;
        int i = 0;
        if (time.startsWith("-")) {
            sprite(cv, "numbers", 20, 6, 5, 1, x - 1, y + 6);
            i = 1;
        }
        String rest = time.substring(i);
        String[] parts = rest.split(":");
        String mm = parts.length > 0 ? parts[0] : "0";
        String ss = parts.length > 1 ? parts[1] : "00";
        if (mm.length() < 2) mm = "0" + mm;
        if (mm.length() > 2) mm = mm.substring(mm.length() - 2);
        if (ss.length() < 2) ss = "0" + ss;
        if (ss.length() > 2) ss = ss.substring(0, 2);
        drawDigit(cv, mm.charAt(0), x + 9, y);
        drawDigit(cv, mm.charAt(1), x + 21, y);
        drawDigit(cv, ss.charAt(0), x + 39, y);
        drawDigit(cv, ss.charAt(1), x + 51, y);
    }

    private void drawDigit(Canvas cv, char d, float x, float y) {
        if (d < '0' || d > '9') d = '0';
        sprite(cv, "numbers", (d - '0') * 9, 0, 9, 13, x, y);
    }

    /** 由屏宽算整数缩放：S = max(2, round(w/275))，并保证 275*S 不超出 */
    public static int computeScale(Context c, int availPx) {
        int s = Math.max(2, Math.round(availPx / 275f));
        while (s > 2 && 275 * s > availPx) s--;
        return s;
    }

    /**
     * 全局统一缩放：第一次调用时按屏宽算出并缓存，之后所有窗口/控件都用同一个 S。
     * 避免各 view 在不同时机用不同 DisplayMetrics 算出不一致的 S 导致布局错位。
     */
    private static int cachedScale = 0;
    public static synchronized int scale(Context c) {
        if (cachedScale <= 0) {
            cachedScale = computeScale(c,
                    c.getResources().getDisplayMetrics().widthPixels);
        }
        return cachedScale;
    }
}
