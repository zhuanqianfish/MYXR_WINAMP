package com.dalang.winamp;

import android.content.Context;
import android.graphics.Canvas;

/**
 * 均衡器竖推子：eqmain.bmp 的 EQ_SLIDER_BACKGROUND 精灵表，
 * 28 帧（14 列 x 2 行，每帧 15x65），frame = round(value * 27)。
 */
public class BandSlider extends WSlider {

    private static final int FW = 15, FH = 65, COLS = 14, FRAMES = 28;
    private static final int SHEET_X = 13, SHEET_Y = 164;

    public BandSlider(Context c, int scale) {
        super(c, scale);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(FW * S, FH * S);
    }

    @Override
    protected void drawSlider(Canvas cv) {
        int frame = Math.round(value * (FRAMES - 1));
        if (frame < 0) frame = 0;
        if (frame > FRAMES - 1) frame = FRAMES - 1;
        int fx = frame % COLS, fy = frame / COLS;
        assets.spriteScaled(cv, "eqmain", SHEET_X + fx * FW, SHEET_Y + fy * FH,
                FW, FH, 0, 0, S);
        // 11x11 旋钮（Webamp .band .slider-handle）：细轨道上的小滑块，
        // y = (1-v)*(65-11)，x 居中；拖动时用 SELECTED 帧
        int thumbY = dragging ? 176 : 164;
        float ty = (1f - value) * (FH - 11);
        float tx = (FW - 11) / 2f;
        assets.spriteScaled(cv, "eqmain", 0, thumbY, 11, 11, tx, ty, S);
    }

    @Override
    protected float valueFromTouch(float x, float y) {
        // 用 view 实际高度映射，保证能拉到头（防御布局尺寸与逻辑尺寸不一致）
        int h = getHeight();
        if (h <= 0) return 0;
        return 1f - y / h;
    }
}
