package com.dalang.winamp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 入口界面：三窗口（主窗口 / 均衡器 / 播放列表）+ 视频窗口的装配。
 *
 * 播放本身在 {@link PlaybackService} 里，界面只做两件事：
 *   1. 绑定服务、把控件事件转成播放指令；
 *   2. 把服务推来的状态画到皮肤控件上。
 * 这样退出界面、息屏、划掉任务都不会断音，锁屏和通知栏也能控制。
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;
    private static final int REQ_NOTIF = 1002;

    private PlaybackService svc;
    private WinampPlayer player;

    private MainWindowView mainWin;
    private EqWindowView eqWin;
    private PlaylistWindowView plWin;
    private VideoWindowView videoWin;
    private PlaylistAdapter adapter;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean seeking = false;
    private boolean showRemaining = true;
    private boolean bound = false;
    private boolean eqHiddenByVideo = false;

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                long pos = player.getPosition();
                long dur = player.getDuration();
                if (!seeking && dur > 0) {
                    mainWin.getSeekSlider().setValueQuiet((float) pos / dur);
                }
                long shown = seeking ? (long) (mainWin.getSeekSlider().getValue() * dur) : pos;
                updateTimeLabel(shown, dur);
            }
            ui.postDelayed(this, 500);
        }
    };

    /** 服务里的播放器状态 → 界面 */
    private final WinampPlayer.Listener uiListener = new WinampPlayer.Listener() {
        @Override
        public void onTrackChanged(final Track t, final int index) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (isFinishing() || isDestroyed()) return;
                    mainWin.getTickerView().setText((index + 1) + ". " + t.artist + " - " + t.title);
                    adapter.setPlayingIndex(index);
                    plWin.getListView().setItemChecked(index, true);
                    updateMetaLabel();
                    updateVideoWindow(t);
                }
            });
        }

        @Override
        public void onPlayingState(final boolean playing) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (isFinishing() || isDestroyed()) return;
                    mainWin.getBtnPlay().setActive(playing);
                }
            });
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = ((PlaybackService.LocalBinder) binder).getService();
            player = svc.getPlayer();
            player.addListener(uiListener);
            player.setSpectrumView(mainWin.getSpectrumView());
            svc.addPlaylistListener(plListener);
            syncFromService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
            player = null;
        }
    };

    /** 服务里的播放列表变了（首次加载、添加、删除、排序）就刷新界面 */
    private final PlaybackService.PlaylistListener plListener =
            new PlaybackService.PlaylistListener() {
                @Override public void onPlaylistChanged() {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            refreshPlaylist();
                        }
                    });
                }
            };

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        adapter = new PlaylistAdapter(this);
        plWin.getListView().setAdapter(adapter);

        wireMainWindow();
        wireEqWindow();
        wirePlaylist();
        requestNotificationPermission();

        PlaybackService.start(this);
        bound = bindService(new Intent(this, PlaybackService.class), conn,
                Context.BIND_AUTO_CREATE);
        ui.post(progressTick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 息屏/锁屏期间可能在通知栏切了歌，回来时对齐一次
        if (svc != null) syncFromService();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(progressTick);
        if (svc != null) svc.removePlaylistListener(plListener);
        if (player != null) {
            player.removeListener(uiListener);
            player.setSpectrumView(null);   // 别让引擎持有已销毁的 View
        }
        if (videoWin != null) videoWin.clear();
        if (bound) {
            try { unbindService(conn); } catch (Exception ignored) { }
            bound = false;
        }
        super.onDestroy();
    }

    /** 把服务当前状态灌进界面（绑定成功、或从后台回来时调用） */
    private void syncFromService() {
        if (svc == null || player == null) return;
        videoWin.setClock(new VideoRenderer.Clock() {
            @Override public long positionMs() { return player == null ? 0 : player.getPosition(); }
            @Override public boolean isPlaying() { return player != null && player.isPlaying(); }
        });
        mainWin.setVolValue(player.getVolume());
        mainWin.setBalValue((player.getBalance() + 1f) / 2f);
        mainWin.getBtnShuffle().setActive(player.isShuffle());
        mainWin.getBtnRepeat().setActive(player.isRepeat());
        mainWin.getBtnPlay().setActive(player.isPlaying());

        refreshPlaylist();
        Track cur = player.getCurrentTrack();
        if (cur != null) {
            int idx = player.getIndex();
            mainWin.getTickerView().setText((idx + 1) + ". " + cur.artist + " - " + cur.title);
            adapter.setPlayingIndex(idx);
            plWin.getListView().setItemChecked(idx, true);
            updateMetaLabel();
            updateVideoWindow(cur);
        } else {
            mainWin.getTickerView().setText("MYXR WINAMP 2.91");
            updateVideoWindow(null);
        }
    }

    // ---------------- 界面搭建 ----------------

    private void buildUi() {
        // 三窗口统一 275*S 逻辑宽度居中；播放列表 weight=1 吃掉剩余高度。
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

        // 视频窗口：默认隐藏，只有 MP4 这类带画面的曲目才出现
        videoWin = new VideoWindowView(this);
        videoWin.setVisibility(View.GONE);
        LinearLayout.LayoutParams vLp = centeredLp(winW, Skin.dp(this, 6));
        root.addView(videoWin, vLp);

        eqWin = new EqWindowView(this);
        LinearLayout.LayoutParams eqLp = centeredLp(winW, Skin.dp(this, 6));
        root.addView(eqWin, eqLp);

        plWin = new PlaylistWindowView(this);
        LinearLayout.LayoutParams plLp = new LinearLayout.LayoutParams(winW, 0, 1f);
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

    // ---------------- 视频窗口 ----------------

    /**
     * 有画面就显示视频窗口，并把均衡器让出位置（避免一屏塞不下）；
     * 回到纯音频时再还原。
     */
    private void updateVideoWindow(Track t) {
        boolean hasVideo = t != null && t.hasVideo;
        if (hasVideo) {
            if (eqWin.getVisibility() == View.VISIBLE) {
                eqWin.setVisibility(View.GONE);
                mainWin.getBtnEqWin().setActive(false);
                eqHiddenByVideo = true;
            }
            videoWin.setVisibility(View.VISIBLE);
            videoWin.setSource(t.uri);
        } else {
            videoWin.clear();
            videoWin.setVisibility(View.GONE);
            if (eqHiddenByVideo) {
                eqWin.setVisibility(View.VISIBLE);
                mainWin.getBtnEqWin().setActive(true);
                eqHiddenByVideo = false;
            }
        }
    }

    // ---------------- 主窗口接线 ----------------

    private interface Cmd { void run(WinampPlayer p); }

    private void withPlayer(Cmd c) {
        WinampPlayer p = player;
        if (p != null) c.run(p);
    }

    private void wireMainWindow() {
        mainWin.getBtnPrev().setOnClickListener(v -> {
            if (svc != null) svc.prev();
        });
        mainWin.getBtnNext().setOnClickListener(v -> {
            if (svc != null) svc.next();
        });
        mainWin.getBtnPlay().setOnClickListener(v -> {
            if (svc != null) svc.play();
        });
        mainWin.getBtnPause().setOnClickListener(v -> {
            if (svc != null) svc.pause();
        });
        mainWin.getBtnStop().setOnClickListener(v -> {
            if (svc != null) svc.stop();
            mainWin.getSpectrumView().setFft(null);
        });
        mainWin.getBtnEject().setOnClickListener(v -> openPicker());

        mainWin.getBtnShuffle().setOnClickListener(v -> withPlayer(p ->
                p.setShuffle(mainWin.getBtnShuffle().isActive())));
        mainWin.getBtnRepeat().setOnClickListener(v -> withPlayer(p ->
                p.setRepeat(mainWin.getBtnRepeat().isActive())));

        mainWin.setVolValue(0.8f);
        mainWin.setVolChangeListener(v -> withPlayer(p -> p.setVolume(v)));

        mainWin.setBalValue(0.5f);
        mainWin.setBalChangeListener(v -> withPlayer(p -> p.setBalance(v * 2f - 1f)));

        WSlider seek = mainWin.getSeekSlider();
        seek.setOnDragListener(new WSlider.OnDragListener() {
            @Override
            public void onDragStart() { seeking = true; }

            @Override
            public void onDragEnd() {
                seeking = false;
                long dur = player == null ? 0 : player.getDuration();
                if (svc != null) svc.seekTo((long) (seek.getValue() * dur));
            }
        });

        mainWin.getTimeView().setOnClickListener(v -> {
            showRemaining = !showRemaining;
            if (player != null) updateTimeLabel(player.getPosition(), player.getDuration());
        });
    }

    private void updateTimeLabel(long pos, long dur) {
        String s = showRemaining && dur > 0
                ? "-" + Skin.fmtTime(dur - pos)
                : Skin.fmtTime(pos);
        mainWin.getTimeView().setText(s);
    }

    private void updateMetaLabel() {
        if (player == null) return;
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
        eqWin.getSlider(0).setOnChangeListener(v -> withPlayer(p -> p.setPreampDb(v * 24f - 12f)));
        // 1..10 = 10 段
        for (int i = 1; i <= 10; i++) {
            final int band = i - 1;
            eqWin.getSlider(i).setOnChangeListener(v ->
                    withPlayer(p -> p.setEqBandDb(band, v * 24f - 12f)));
        }

        eqWin.getBtnOn().setOnClickListener(v -> withPlayer(p ->
                p.setEqEnabled(eqWin.getBtnOn().isActive())));

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
            final int band = i;
            withPlayer(p -> p.setEqBandDb(band, curve[band] * 24f - 12f));
        }
    }

    // ---------------- 播放列表接线 ----------------

    private void wirePlaylist() {
        plWin.getListView().setOnItemClickListener((parent, view, position, id) -> {
            if (svc != null) svc.playAt(position);
        });

        plWin.getBtnAdd().setOnClickListener(v -> openPicker());

        plWin.getBtnRem().setOnClickListener(v -> {
            int pos = plWin.getListView().getCheckedItemPosition();
            if (svc == null) return;
            if (pos >= 0 && pos < svc.getTracks().size()) {
                svc.removeTrack(pos);
                refreshPlaylist();
            } else {
                toast("先点选一首再删除");
            }
        });

        plWin.getBtnSel().setOnClickListener(v -> {
            if (player == null) return;
            int idx = player.getIndex();
            if (idx >= 0 && idx < plWin.getListView().getCount()) {
                plWin.getListView().setItemChecked(idx, true);
                plWin.getListView().smoothScrollToPosition(idx);
            }
        });

        plWin.getBtnMisc().setOnClickListener(v -> {
            if (svc == null) return;
            svc.sortByTitle();
            refreshPlaylist();
            toast("已按标题排序");
        });
    }

    private void refreshPlaylist() {
        if (svc == null) return;
        List<Track> list = svc.getTracks();
        adapter.setTracks(list);
        adapter.setPlayingIndex(player == null ? -1 : player.getIndex());
        long total = 0;
        for (Track t : list) total += t.durationMs;
        plWin.getStatusView().setText(list.size() + " files  ·  total " + Skin.fmtTime(total));
    }

    // ---------------- 文件选择与元数据 ----------------

    private void openPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // 放开视频容器：MP4 / MKV / WEBM / 3GP 都能进列表（音频走同一条解码链）
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "audio/*", "video/*", "application/ogg", "application/x-ogg"});
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
            final List<Track> added = new ArrayList<>();
            int skipped = 0;
            for (Uri uri : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                Track t = MediaProbe.probe(this, uri);
                if (t != null) added.add(t);
                else skipped++;
            }
            final int skip = skipped;
            runOnUiThread(() -> {
                if (svc == null) return;
                svc.addTracks(added);
                refreshPlaylist();
                if (added.isEmpty()) {
                    toast(skip > 0 ? "这些文件没有音频轨道" : "没有可播放的文件");
                } else {
                    toast("已添加 " + added.size() + " 首"
                            + (skip > 0 ? "，跳过 " + skip + " 个（无音频轨）" : ""));
                }
            });
        }, "probe-tracks").start();
    }

    // ---------------- 通知权限 ----------------

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED) return;
        try {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIF) {
            boolean granted = results.length > 0
                    && results[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                toast("未授予通知权限，锁屏/通知栏不会显示播放控件");
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
