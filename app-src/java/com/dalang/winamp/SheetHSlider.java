package com.dalang.winamp;

import android.content.Context;
import android.graphics.Canvas;

/**
 * 横向精灵表滑杆（音量/平衡）：背景表含 28 帧（每帧 fw x fh），
 * frame = round(value * 27)，直接画对应帧，无需单独推子。
 */
public class SheetHSlider extends WSlider {

    private final String sheet;
    private final int sx, sy, fw, fh;
    private static final int FRAMES = 28;

    public SheetHSlider(Context c, int scale, String sheet,
                        int sx, int sy, int fw, int fh) {
        super(c, scale);
        this.sheet = sheet;
        this.sx = sx;
        this.sy = sy;
        this.fw = fw;
        this.fh = fh;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(fw * S, fh * S);
    }

    @Override
    protected void drawSlider(Canvas cv) {
        int frame = Math.round(value * (FRAMES - 1));
        if (frame < 0) frame = 0;
        if (frame > FRAMES - 1) frame = FRAMES - 1;
        assets.spriteScaled(cv, sheet, sx, sy + frame * fh, fw, fh, 0, 0, S);
    }

    @Override
    protected float valueFromTouch(float x, float y) {
        return x / (fw * S);
    }
}
