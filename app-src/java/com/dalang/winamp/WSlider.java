package com.dalang.winamp;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 滑杆基类：值域 0..1，触摸拖动。具体绘制与触摸映射由子类实现
 * （皮肤精灵图方式）。MainActivity 只依赖本类的公开 API。
 */
public abstract class WSlider extends View {

    public interface OnChangeListener {
        void onChange(float value);
    }

    public interface OnDragListener {
        void onDragStart();
        void onDragEnd();
    }

    protected float value = 0.5f;
    protected final int S;
    protected final SkinAssets assets;
    private OnChangeListener changeListener;
    private final List<OnChangeListener> extraListeners = new ArrayList<>();
    private OnDragListener dragListener;
    protected boolean dragging = false;

    public WSlider(Context c, int scale) {
        super(c);
        S = scale;
        assets = SkinAssets.get(c);
    }

    public void setOnChangeListener(OnChangeListener l) { changeListener = l; }

    /** 附加监听（不覆盖主监听，供视图内部联动用） */
    public void addOnChangeListener(OnChangeListener l) { extraListeners.add(l); }

    public void setOnDragListener(OnDragListener l) { dragListener = l; }

    public float getValue() { return value; }

    public void setValue(float v) {
        float nv = clamp(v);
        if (nv != value) {
            value = nv;
            invalidate();
            fireChange();
        }
    }

    /** 设置值但不触发回调（用于进度刷新） */
    public void setValueQuiet(float v) {
        value = clamp(v);
        invalidate();
    }

    protected float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    protected void fireChange() {
        if (changeListener != null) changeListener.onChange(value);
        for (OnChangeListener l : extraListeners) l.onChange(value);
    }

    /** 子类：绘制滑杆 */
    protected abstract void drawSlider(Canvas cv);

    /** 子类：触摸点 -> 值 */
    protected abstract float valueFromTouch(float x, float y);

    @Override
    protected void onDraw(Canvas cv) {
        drawSlider(cv);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (dragListener != null) dragListener.onDragStart();
                updateFromTouch(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateFromTouch(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                updateFromTouch(e.getX(), e.getY());
                dragging = false;
                invalidate();
                if (dragListener != null) dragListener.onDragEnd();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void updateFromTouch(float x, float y) {
        float v = clamp(valueFromTouch(x, y));
        if (v != value) {
            value = v;
            invalidate();
            fireChange();
        }
    }
}
