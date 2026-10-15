package com.dalang.winamp;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;

    private WinampPlayer player;
    private MainWindowView mainWin;
    private EqWindowView eqWin;
    private PlaylistWindowView plWin;
    private PlaylistAdapter adapter;
    private final List<Track> tracks = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean seeking = false;
    private boolean showRemaining = true;

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            long pos = player.getPosition();
            long dur = player.getDuration();
            if (!seeking && dur > 0) {
                mainWin.getSeekSlider().setValueQuiet((float) pos / dur);
            }
            long shown = seeking ? (long) (mainWin.getSeekSlider().getValue() * dur) : pos;
            updateTimeLabel(shown, dur);
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        player = new WinampPlayer(this, new WinampPlayer.Listener() {
            @Override
            public void onTrackChanged(Track t, int index) {
                runOnUiThread(() -> {
                    mainWin.getTickerView().setText((index + 1) + ". " + t.artist + " - " + t.title);
                    adapter.setPlayingIndex(index);
                    plWin.getListView().setItemChecked(index, true);
                    updateMetaLabel();
                });
            }

            @Override
            public void onPlayingState(boolean playing) {
                runOnUiThread(() -> mainWin.getBtnPlay().setActive(playing));
            }
        });
        player.setSpectrumView(mainWin.getSpectrumView());
        player.setPlaylist(tracks);

        wireMainWindow();
        wireEqWindow();
        wirePlaylist();

        adapter = new PlaylistAdapter(this);
        plWin.getListView().setAdapter(adapter);

        loadPlaylistAsync();
        ui.post(progressTick);
    }

    // ---------------- 界面搭建 ----------------

    private void buildUi() {
        // v2.1：去掉 ScrollView，三窗口统一 275*S 逻辑宽度居中；
        // 播放列表 weight=1 占据主窗口下方整个剩余屏幕高度。
        int s = SkinAssets.scale(this);
        int winW = 275 * s;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Skin.BG_BLACK);
        root.setFitsSystemWindows(true);
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        int pad = Skin.dp(this, 4);
        root.setPadding(0, pad, 0, pad);

        mainWin = new MainWindowView(this);
        root.addView(mainWin, centeredLp(winW, 0));
        eqWin = new EqWindowView(this);
        LinearLayout.LayoutParams eqLp = centeredLp(winW, Skin.dp(this, 6));
        root.addView(eqWin, eqLp);
        plWin = new PlaylistWindowView(this);
        LinearLayout.LayoutParams plLp = new LinearLayout.LayoutParams(
                winW, 0, 1f);
        plLp.topMargin = Skin.dp(this, 6);
        plLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        root.addView(plWin, plLp);

        setContentView(root);
    }

    private LinearLayout.LayoutParams centeredLp(int w, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                w, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    // ---------------- 主窗口接线 ----------------

    private void wireMainWindow() {
        mainWin.getBtnPrev().setOnClickListener(v -> player.prev());
        mainWin.getBtnNext().setOnClickListener(v -> player.next());
        mainWin.getBtnPlay().setOnClickListener(v -> player.play());
        mainWin.getBtnPause().setOnClickListener(v -> player.pause());
        mainWin.getBtnStop().setOnClickListener(v -> {
            player.stop();
            mainWin.getSpectrumView().setFft(null);
        });
        mainWin.getBtnEject().setOnClickListener(v -> openPicker());

        mainWin.getBtnShuffle().setOnClickListener(v ->
                player.setShuffle(mainWin.getBtnShuffle().isActive()));
        mainWin.getBtnRepeat().setOnClickListener(v ->
                player.setRepeat(mainWin.getBtnRepeat().isActive()));

        mainWin.setVolValue(0.8f);
        mainWin.setVolChangeListener(player::setVolume);

        mainWin.setBalValue(0.5f);
        mainWin.setBalChangeListener(v -> player.setBalance(v * 2f - 1f));

        WSlider seek = mainWin.getSeekSlider();
        seek.setOnDragListener(new WSlider.OnDragListener() {
            @Override
            public void onDragStart() { seeking = true; }

            @Override
            public void onDragEnd() {
                seeking = false;
                player.seekTo((long) (seek.getValue() * player.getDuration()));
            }
        });

        mainWin.getTimeView().setOnClickListener(v -> {
            showRemaining = !showRemaining;
            updateTimeLabel(player.getPosition(), player.getDuration());
        });
    }

    private void updateTimeLabel(long pos, long dur) {
        String s = showRemaining && dur > 0
                ? "-" + Skin.fmtTime(dur - pos)
                : Skin.fmtTime(pos);
        mainWin.getTimeView().setText(s);
    }

    private void updateMetaLabel() {
        String kbps = player.getLastBitrate() > 0
                ? (player.getLastBitrate() / 1000) + " kbps" : "-- kbps";
        String khz = player.getLastSampleRate() > 0
                ? String.format("%.1f kHz", player.getLastSampleRate() / 1000f) : "-- kHz";
        String ch = player.getLastChannels() == 1 ? "mono"
                : player.getLastChannels() > 1 ? "stereo" : "";
        mainWin.getMetaView().setText(kbps + " " + khz + (ch.isEmpty() ? "" : " " + ch));
    }

    // ---------------- 均衡器接线 ----------------

    private void wireEqWindow() {
        // 0 = PREAMP
        eqWin.getSlider(0).setOnChangeListener(v -> player.setPreampDb(v * 24f - 12f));
        // 1..10 = 10 段
        for (int i = 1; i <= 10; i++) {
            final int band = i - 1;
            eqWin.getSlider(i).setOnChangeListener(v -> player.setEqBandDb(band, v * 24f - 12f));
        }

        eqWin.getBtnOn().setOnClickListener(v ->
                player.setEqEnabled(eqWin.getBtnOn().isActive()));

        eqWin.getBtnAuto().setOnClickListener(v -> {
            if (eqWin.getBtnAuto().isActive()) {
                applyCurve(WinampPlayer.AUTO_CURVE);
            } else {
                applyCurve(WinampPlayer.PRESETS[0]); // Flat
            }
        });

        eqWin.getBtnPresets().setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, eqWin.getBtnPresets());
            for (int i = 0; i < WinampPlayer.PRESET_NAMES.length; i++) {
                menu.getMenu().add(0, i, i, WinampPlayer.PRESET_NAMES[i]);
            }
            menu.setOnMenuItemClickListener(item -> {
                applyCurve(WinampPlayer.PRESETS[item.getItemId()]);
                toast("预设: " + WinampPlayer.PRESET_NAMES[item.getItemId()]);
                return true;
            });
            menu.show();
        });
    }

    private void applyCurve(float[] curve) {
        // curve[0..9] 对应 10 段推子（slider 1..10）；PREAMP 不动
        for (int i = 0; i < 10; i++) {
            eqWin.setBandValue(i + 1, curve[i]);
            player.setEqBandDb(i, curve[i] * 24f - 12f);
        }
    }

    // ---------------- 播放列表接线 ----------------

    private void wirePlaylist() {
        plWin.getListView().setOnItemClickListener((parent, view, position, id) ->
                player.playAt(position));

        plWin.getBtnAdd().setOnClickListener(v -> openPicker());

        plWin.getBtnRem().setOnClickListener(v -> {
            int pos = plWin.getListView().getCheckedItemPosition();
            if (pos >= 0 && pos < tracks.size()) {
                boolean wasPlaying = (pos == player.getIndex());
                tracks.remove(pos);
                if (wasPlaying) player.stop();
                player.setPlaylist(tracks);
                refreshPlaylist();
                savePlaylist();
            } else {
                toast("先点选一首再删除");
            }
        });

        plWin.getBtnSel().setOnClickListener(v -> {
            int idx = player.getIndex();
            if (idx >= 0 && idx < tracks.size()) {
                plWin.getListView().setItemChecked(idx, true);
                plWin.getListView().smoothScrollToPosition(idx);
            }
        });

        plWin.getBtnMisc().setOnClickListener(v -> {
            Collections.sort(tracks, Comparator.comparing(t -> t.title.toLowerCase()));
            player.setPlaylist(tracks);
            refreshPlaylist();
            savePlaylist();
            toast("已按标题排序");
        });
    }

    private void refreshPlaylist() {
        adapter.setTracks(tracks);
        adapter.setPlayingIndex(player.getIndex());
        long total = 0;
        for (Track t : tracks) total += t.durationMs;
        plWin.getStatusView().setText(tracks.size() + " files  ·  total " + Skin.fmtTime(total));
    }

    // ---------------- 文件选择与元数据 ----------------

    private void openPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQ_PICK);
        } catch (Exception e) {
            toast("无法打开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        new Thread(() -> {
            int added = 0;
            for (Uri uri : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                Track t = probeTrack(this, uri);
                if (t != null) {
                    synchronized (tracks) {
                        tracks.add(t);
                    }
                    added++;
                }
            }
            final int n = added;
            runOnUiThread(() -> {
                player.setPlaylist(tracks);
                refreshPlaylist();
                savePlaylist();
                toast("已添加 " + n + " 首");
                if (player.getIndex() < 0 && !tracks.isEmpty()) player.playAt(0);
            });
        }).start();
    }

    private static Track probeTrack(Context context, Uri uri) {
        Track t = new Track();
        t.uri = uri.toString();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, uri);
            t.title = nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            t.artist = nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            t.durationMs = d == null ? 0 : Long.parseLong(d);
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        if (t.title == null) {
            t.title = displayName(context, uri);
            t.artist = "未知艺术家";
        }
        if (t.artist == null) t.artist = "未知艺术家";
        return t;
    }

    private static String nz(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null) {
                    int dot = name.lastIndexOf('.');
                    return dot > 0 ? name.substring(0, dot) : name;
                }
            }
        } catch (Exception ignored) {
        }
        return "未知标题";
    }

    // ---------------- 播放列表持久化 ----------------

    private void savePlaylist() {
        StringBuilder sb = new StringBuilder();
        synchronized (tracks) {
            for (Track t : tracks) sb.append(t.uri).append('\n');
        }
        getSharedPreferences("winamp", MODE_PRIVATE).edit()
                .putString("playlist", sb.toString()).apply();
    }

    /** 内置默认歌曲（assets），首次启动拷贝到 files 目录并自动播放 */
    private static final String DEFAULT_SONG_ASSET = "wenroutaozi_Llama.wav";
    private static final String DEFAULT_SONG_TITLE = "wenroutaozi_Llama";
    private static final String DEFAULT_SONG_ARTIST = "温柔桃子摇滚版";
    /** 内置歌曲版本：换歌或修文件时递增，老用户升级会自动覆盖 files 目录里的旧文件 */
    private static final int DEFAULT_SONG_VERSION = 2;

    private void loadPlaylistAsync() {
        new Thread(() -> {
            SharedPreferences sp = getSharedPreferences("winamp", MODE_PRIVATE);
            boolean firstRun = sp.getBoolean("first_run", true);
            String saved = sp.getString("playlist", "");
            List<Track> loaded = new ArrayList<>();
            boolean autoplay = false;
            if (firstRun) {
                try {
                    Track def = installDefaultSong();
                    if (def != null) {
                        loaded.add(def);
                        autoplay = true;
                    }
                } catch (Exception ignored) {
                } finally {
                    sp.edit().putBoolean("first_run", false).apply();
                }
            } else if (sp.getInt("default_song_ver", 0) < DEFAULT_SONG_VERSION) {
                // 老用户升级：覆盖 files 里的旧内置歌曲（修坏文件头）
                try {
                    installDefaultSong();
                } catch (Exception ignored) {
                }
            }
            sp.edit().putInt("default_song_ver", DEFAULT_SONG_VERSION).apply();
            if (saved != null && !saved.isEmpty()) {
                for (String line : saved.split("\n")) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        Track t = probeTrack(this, Uri.parse(line.trim()));
                        if (t != null) {
                            fixDefaultSongMeta(t);
                            loaded.add(t);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            synchronized (tracks) {
                tracks.clear();
                tracks.addAll(loaded);
            }
            final boolean doAutoplay = autoplay && !loaded.isEmpty();
            runOnUiThread(() -> {
                player.setPlaylist(tracks);
                refreshPlaylist();
                savePlaylist();
                if (doAutoplay) player.playAt(0);
            });
        }).start();
    }

    /** 拷贝 assets 内置歌曲到 files 目录并生成曲目；失败返回 null（静默跳过） */
    private Track installDefaultSong() {
        try {
            File dst = new File(getFilesDir(), DEFAULT_SONG_ASSET);
            if (dst.exists()) dst.delete(); // 始终覆盖，保证老用户升级时坏文件被替换
            InputStream in = getAssets().open(DEFAULT_SONG_ASSET);
            try {
                OutputStream out = new FileOutputStream(dst);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            Track t = probeTrack(this, Uri.fromFile(dst));
            if (t == null) return null;
            t.title = DEFAULT_SONG_TITLE;
            t.artist = DEFAULT_SONG_ARTIST;
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    /** 内置歌曲标题/艺术家固定显示，不受元数据探测影响（含后续启动从持久化列表恢复时） */
    private static void fixDefaultSongMeta(Track t) {
        if (t != null && t.uri != null && t.uri.endsWith("/" + DEFAULT_SONG_ASSET)) {
            t.title = DEFAULT_SONG_TITLE;
            t.artist = DEFAULT_SONG_ARTIST;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(progressTick);
        if (player != null) player.release();
        super.onDestroy();
    }
}
