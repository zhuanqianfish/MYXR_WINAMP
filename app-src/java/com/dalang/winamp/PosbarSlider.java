package com.dalang.winamp;

import android.content.Context;
import android.graphics.Canvas;

/** 播放进度条：posbar.bmp 背景 248x10 + 推子 29x10（拖动时用选中态） */
public class PosbarSlider extends WSlider {

    private static final int W = 248, H = 10, THUMB_W = 29, TRAVEL = W - THUMB_W;

    public PosbarSlider(Context c, int scale) {
        super(c, scale);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(W * S, H * S);
    }

    @Override
    protected void drawSlider(Canvas cv) {
        assets.spriteScaled(cv, "posbar", 0, 0, W, H, 0, 0, S);
        int tx = Math.round(value * TRAVEL);
        // 选中帧在 (278,0)，常态帧在 (248,0)
        assets.spriteScaled(cv, "posbar", dragging ? 278 : 248, 0,
                THUMB_W, H, tx, 0, S);
    }

    @Override
    protected float valueFromTouch(float x, float y) {
        // 用 view 实际宽度映射，保证能拉到头（防御布局尺寸与逻辑尺寸不一致）
        int w = getWidth();
        if (w <= 0) return 0;
        float thumbW = w * THUMB_W / (float) W;
        // 点击即跳到该位置（经典行为），按推子中心对齐
        return (x - thumbW / 2f) / (w - thumbW);
    }
}
