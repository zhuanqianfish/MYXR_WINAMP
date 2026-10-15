package com.dalang.winamp;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 播放列表适配器：经典绿字黑底 */
public class PlaylistAdapter extends BaseAdapter {

    private final Context context;
    private final List<Track> tracks = new ArrayList<>();
    private int playingIndex = -1;

    public PlaylistAdapter(Context context) {
        this.context = context;
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

    @Override
    public int getCount() { return tracks.size(); }

    @Override
    public Track getItem(int position) { return tracks.get(position); }

    @Override
    public long getItemId(int position) { return position; }

    private static class Holder {
        TextView main;
        TextView dur;
        LinearLayout row;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            h = new Holder();
            h.row = new LinearLayout(context);
            h.row.setOrientation(LinearLayout.HORIZONTAL);
            h.row.setGravity(Gravity.CENTER_VERTICAL);
            h.row.setPadding(Skin.dp(context, 4), Skin.dp(context, 3),
                    Skin.dp(context, 4), Skin.dp(context, 3));
            h.main = new TextView(context);
            h.main.setTypeface(Skin.mono());
            h.main.setTextSize(10);
            h.main.setSingleLine(true);
            h.dur = new TextView(context);
            h.dur.setTypeface(Skin.mono());
            h.dur.setTextSize(10);
            h.dur.setSingleLine(true);
            h.row.addView(h.main, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            h.row.addView(h.dur);
            convertView = h.row;
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }
        Track t = getItem(position);
        boolean playing = (position == playingIndex);
        h.main.setText((position + 1) + ". " + t.artist + " - " + t.title);
        h.main.setTextColor(playing ? Skin.WHITE : Skin.GREEN);
        h.main.setPaintFlags(playing
                ? h.main.getPaintFlags() | android.graphics.Paint.FAKE_BOLD_TEXT_FLAG
                : h.main.getPaintFlags() & ~android.graphics.Paint.FAKE_BOLD_TEXT_FLAG);
        h.dur.setText(Skin.fmtTime(t.durationMs));
        h.dur.setTextColor(playing ? Skin.AMBER : Skin.GREEN_DIM);
        h.row.setBackgroundColor(playing ? 0xFF1E2E1E : Skin.BG_BLACK);
        return convertView;
    }
}
