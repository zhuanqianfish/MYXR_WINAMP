package com.dalang.winamp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 视频窗口（MP4 / MKV / WEBM 等带画面的容器）。
 *
 * 275x116 逻辑像素，和其他窗口同宽同高 —— 保持 Winamp 那种"一叠小窗"的观感。
 * 画面用 TextureView + 手算矩阵做等比缩放（含 KEY_ROTATION 旋转，竖屏视频不会歪）。
 * 没有画面时显示等待/无视频提示，音频照常播放。
 */
public class VideoWindowView extends FrameLayout {

    private static final int LW = 275;
    private static final int TITLE_H = 14;
    private static final int LH = 116;
    private static final int AREA_Y = TITLE_H;
    private static final int AREA_H = LH - TITLE_H;   // 102

    private final SkinAssets assets;
    private final int S;
    private final TextureView textureView;
    private final Paint fillPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();

    private VideoRenderer renderer;
    private VideoRenderer.Clock clock;
    private String uri;
    private boolean surfaceReady = false;
    private boolean collapsed = false;
    private boolean closePressed = false;
    private String hint = "等待画面…";
    private int videoW, videoH, rotation;
    private int offsetX = 0;

    public VideoWindowView(Context context) {
        super(context);
        assets = SkinAssets.get(context);
        S = SkinAssets.scale(context);
        setWillNotDraw(false);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1);
        borderPaint.setColor(Skin.BORDER);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextSize(8);
        textPaint.setColor(Skin.GREEN_DIM);

        textureView = new TextureView(context);
        textureView.setOpaque(true);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surfaceReady = true;
                startRenderer();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                applyTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                surfaceReady = false;
                stopRenderer();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        addView(textureView);
    }

    /** 播放器状态来源（音频播放头），由 MainActivity 注入 */
    public void setClock(VideoRenderer.Clock c) { clock = c; }

    // ---------------------------------------------------------------- 源

    /** 切到某个视频文件；传 null 表示关闭画面 */
    public void setSource(String uriStr) {
        if (uriStr != null && uriStr.equals(uri) && renderer != null) return;
        stopRenderer();
        uri = uriStr;
        videoW = videoH = 0;
        rotation = 0;
        hint = "等待画面…";
        applyTransform();
        invalidate();
        if (uri != null) startRenderer();
    }

    public void clear() { setSource(null); }

    public boolean hasSource() { return uri != null; }

    private void startRenderer() {
        if (!surfaceReady || uri == null || renderer != null) return;
        if (textureView.getSurfaceTexture() == null) return;
        final VideoRenderer.Clock c = clock != null ? clock : new VideoRenderer.Clock() {
            @Override public long positionMs() { return 0; }
            @Override public boolean isPlaying() { return true; }
        };
        renderer = new VideoRenderer(getContext(), uri,
                new android.view.Surface(textureView.getSurfaceTexture()), c,
                new VideoRenderer.Listener() {
                    @Override public void onFirstFrame(final int w, final int h, final int rot) {
                        post(new Runnable() {
                            @Override public void run() {
                                videoW = w;
                                videoH = h;
                                rotation = rot;
                                hint = null;
                                applyTransform();
                                invalidate();
                            }
                        });
                    }

                    @Override public void onEnded() { }

                    @Override public void onError(final String message) {
                        post(new Runnable() {
                            @Override public void run() {
                                hint = "无法显示画面：" + message;
                                invalidate();
                            }
                        });
                    }
                });
        renderer.start();
    }

    private void stopRenderer() {
        VideoRenderer r = renderer;
        renderer = null;
        if (r != null) r.release();
    }

    // ---------------------------------------------------------------- 布局

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (collapsed ? TITLE_H : LH) * S);
        textureView.measure(
                MeasureSpec.makeMeasureSpec(LW * S, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AREA_H * S, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        offsetX = Math.max(0, (r - l - LW * S) / 2);
        textureView.layout(offsetX, AREA_Y * S,
                offsetX + LW * S, (AREA_Y + AREA_H) * S);
        textureView.setVisibility(collapsed ? GONE : VISIBLE);
        applyTransform();
    }

    /** 等比缩放 + 旋转，把画面装进 275x102 的框里（多余部分留黑边） */
    private void applyTransform() {
        int vw = textureView.getWidth(), vh = textureView.getHeight();
        if (vw <= 0 || vh <= 0 || videoW <= 0 || videoH <= 0) {
            matrix.reset();
            textureView.setTransform(matrix);
            return;
        }
        int rot = ((rotation % 360) + 360) % 360;
        boolean swapped = (rot == 90 || rot == 270);
        float dispW = swapped ? videoH : videoW;
        float dispH = swapped ? videoW : videoH;
        float k = Math.min(vw / dispW, vh / dispH);
        float dw = dispW * k, dh = dispH * k;

        float sx = swapped ? videoW / dh : videoW / dw;
        float sy = swapped ? videoH / dw : videoH / dh;

        matrix.reset();
        matrix.postTranslate(-vw / 2f, -vh / 2f);
        matrix.postRotate(rot);
        matrix.postScale(sx, sy);
        matrix.postTranslate(vw / 2f, vh / 2f);
        textureView.setTransform(matrix);
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas cv) {
        cv.save();
        cv.translate(offsetX, 0);
        cv.scale(S, S);

        // 窗口底
        fillPaint.setColor(0xFF000000);
        cv.drawRect(0, 0, LW, collapsed ? TITLE_H : LH, fillPaint);
        // 标题栏
        LinearGradient g = new LinearGradient(0, 0, 0, TITLE_H,
                Skin.TITLE_TOP, Skin.TITLE_BOT, Shader.TileMode.CLAMP);
        fillPaint.setShader(g);
        cv.drawRect(0, 0, LW, TITLE_H, fillPaint);
        fillPaint.setShader(null);
        assets.drawText(cv, "MYXR VIDEO", 4, 4);
        assets.sprite(cv, "titlebar", 18, closePressed ? 9 : 0, 9, 9, LW - 11, 3);
        cv.restore();
    }

    /** 边框和提示文字要盖在 TextureView 之上，所以在子 View 之后画 */
    @Override
    protected void dispatchDraw(Canvas cv) {
        super.dispatchDraw(cv);
        if (collapsed) return;
        cv.save();
        cv.translate(offsetX, 0);
        cv.scale(S, S);
        cv.drawRect(0, AREA_Y, LW, LH, borderPaint);
        if (hint != null) {
            float tw = textPaint.measureText(hint);
            cv.drawText(hint, (LW - tw / 2f) / 2f, AREA_Y + AREA_H / 2f, textPaint);
        }
        cv.restore();
    }

    private boolean inClose(float x, float y) {
        float bx = offsetX + (LW - 11) * S, by = 3 * S;
        return x >= bx && x <= bx + 9 * S && y >= by && y <= by + 9 * S;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                closePressed = inClose(e.getX(), e.getY());
                if (closePressed) { invalidate(); return true; }
                break;
            case MotionEvent.ACTION_UP:
                boolean hit = closePressed && inClose(e.getX(), e.getY());
                closePressed = false;
                if (hit) {
                    collapsed = !collapsed;
                    requestLayout();
                    invalidate();
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                closePressed = false;
                invalidate();
                break;
        }
        return super.onTouchEvent(e);
    }

    public boolean isCollapsed() { return collapsed; }

    public void setCollapsed(boolean c) {
        if (collapsed == c) return;
        collapsed = c;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopRenderer();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (uri != null && surfaceReady) startRenderer();
    }

    /** 供外部（MainActivity）判断当前 View 是否可见 */
    public View content() { return textureView; }
}
