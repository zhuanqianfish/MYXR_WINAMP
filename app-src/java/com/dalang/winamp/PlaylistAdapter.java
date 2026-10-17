package com.dalang.winamp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.BaseAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放列表适配器。
 *
 * 配色取自 PLEDIT.TXT（base skin）：
 *   Normal=#00FF00  Current=#FFFFFF  NormalBG=#000000  SelectedBG=#0000C6
 * 行高 20、字号 13（逻辑像素），与原版 Winamp 播放列表一致；
 * 选中行整行铺蓝底、文字转白，正在播放的曲目也用 Current 色。
 */
public class PlaylistAdapter extends BaseAdapter {

    /** 逻辑行高（滚动条按它计算滚动位置，必须是常量） */
    public static final int ROW_H = 20;
    private static final int FONT_PX = 13;
    private static final int TEXT_X = 3;

    private static final int NORMAL = 0xFF00FF00;
    private static final int CURRENT = 0xFFFFFFFF;
    private static final int SELECTED_BG = 0xFF0000C6;
    private static final int NORMAL_BG = 0xFF000000;

    private final Context context;
    private final int S;
    private final List<Track> tracks = new ArrayList<>();
    private int playingIndex = -1;
    private final Paint bgPaint = new Paint();
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    public PlaylistAdapter(Context context) {
        this.context = context;
        this.S = SkinAssets.scale(context);
        bgPaint.setStyle(Paint.Style.FILL);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        textPaint.setTextSize(FONT_PX);
    }

    public void setTracks(List<Track> list) {
        tracks.clear();
        tracks.addAll(list);
        notifyDataSetChanged();
    }

    public void setPlayingIndex(int i) {
        playingIndex = i;
        notifyDataSetChanged();
    }

    public int getPlayingIndex() { return playingIndex; }

    @Override
    public int getCount() { return tracks.size(); }

    @Override
    public Track getItem(int position) { return tracks.get(position); }

    @Override
    public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Row row = (convertView instanceof Row) ? (Row) convertView : new Row(context);
        row.bind(getItem(position), position, position == playingIndex);
        return row;
    }

    /** 单行：黑/蓝底 + 一行曲目信息 + 右对齐时长 */
    private class Row extends View {
        private Track track;
        private int position;
        private boolean playing;

        Row(Context c) { super(c); }

        void bind(Track t, int pos, boolean isPlaying) {
            track = t;
            position = pos;
            playing = isPlaying;
            invalidate();
        }

        private int checkedPosition() {
            ViewParent p = getParent();
            if (p instanceof ListView) return ((ListView) p).getCheckedItemPosition();
            return -1;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(MeasureSpec.getSize(wSpec), ROW_H * S);
        }

        @Override
        protected void onDraw(Canvas cv) {
            boolean selected = checkedPosition() == position;
            cv.drawColor(selected ? SELECTED_BG : NORMAL_BG);
            Track t = track;
            if (t == null) return;
            cv.save();
            cv.scale(S, S);
            int w = getWidth() / S;
            textPaint.setColor(playing ? CURRENT : NORMAL);

            String dur = Skin.fmtTime(t.durationMs);
            float dw = textPaint.measureText(dur);
            float avail = w - TEXT_X - dw - 6;

            String title = (position + 1) + ". " + t.artist + " - " + t.title;
            if (textPaint.measureText(title) > avail) title = ellipsize(title, avail);

            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = (ROW_H - (fm.descent - fm.ascent)) / 2f - fm.ascent;
            cv.drawText(title, TEXT_X, baseline, textPaint);
            cv.drawText(dur, w - dw - 3, baseline, textPaint);
            cv.restore();
        }
    }

    /** 超出宽度时按 "…" 截断（原版同样是尾部省略号） */
    private String ellipsize(String s, float maxW) {
        final String ell = "...";
        float ew = textPaint.measureText(ell);
        if (maxW <= ew) return "";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (textPaint.measureText(s.substring(0, mid)) + ew <= maxW) lo = mid;
            else hi = mid - 1;
        }
        return lo <= 0 ? ell : s.substring(0, lo) + ell;
    }
}
