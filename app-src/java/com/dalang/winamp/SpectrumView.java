package com.dalang.winamp;

/**
 * 频谱数据持有类（不再是 View，绘制由 MainWindowView 在分析仪矩形内完成）。
 * WinampPlayer 的 Visualizer 回调线程调用 setFft/setWave。
 */
public class SpectrumView {

    /** 显示模式：柱状频谱 → 折线示波器 → 关闭（空白）→ 循环 */
    public static final int MODE_SPECTRUM = 0;
    public static final int MODE_OSCILLOSCOPE = 1;
    public static final int MODE_OFF = 2;

    private volatile byte[] fft;
    private volatile byte[] wave;
    private volatile int mode = MODE_SPECTRUM;
    private Runnable invalidator;

    public void setInvalidator(Runnable r) { invalidator = r; }

    public void setFft(byte[] data) {
        fft = data;
        fire();
    }

    public void setWave(byte[] data) {
        wave = data;
        if (mode == MODE_OSCILLOSCOPE) fire();
    }

    private void fire() {
        Runnable r = invalidator;
        if (r != null) r.run();
    }

    public byte[] getFft() { return fft; }
    public byte[] getWave() { return wave; }

    public boolean isOscilloscope() { return mode == MODE_OSCILLOSCOPE; }
    public boolean isOff() { return mode == MODE_OFF; }
    public int getMode() { return mode; }
    public void setOscilloscope(boolean o) {
        mode = o ? MODE_OSCILLOSCOPE : MODE_SPECTRUM;
        fire();
    }
    /** 点击分析仪：柱状 → 折线 → 关闭 → 柱状，循环切换 */
    public void toggleMode() {
        mode = (mode + 1) % 3;
        fire();
    }
}
