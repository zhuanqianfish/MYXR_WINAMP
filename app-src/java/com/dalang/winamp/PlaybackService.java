package com.dalang.winamp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 后台播放服务 + 锁屏/通知栏媒体控制。
 *
 * 职责：
 *   1. 持有 {@link WinampPlayer} 与播放列表 —— 界面退出、息屏、划掉任务都不断音；
 *   2. 维护 {@link MediaSession}：锁屏（Android 5~10 直接读会话，11+ 读媒体通知）
 *      和蓝牙/耳机线控都通过它控制播放；
 *   3. 发媒体通知（Notification.MediaStyle）：封面 + 上一首/播放暂停/下一首，
 *      折叠态三个按钮全显示，展开还能拖进度；
 *   4. 音频焦点（来电/别的 App 播放时自动避让）、拔耳机自动暂停。
 *
 * 只用 framework API（android.media.session.*），不依赖 androidx.media。
 */
public class PlaybackService extends Service implements WinampPlayer.Listener {

    private static final String TAG = "PlaybackService";

    public static final String ACTION_TOGGLE = "com.dalang.winamp.action.TOGGLE";
    public static final String ACTION_NEXT = "com.dalang.winamp.action.NEXT";
    public static final String ACTION_PREV = "com.dalang.winamp.action.PREV";
    public static final String ACTION_STOP = "com.dalang.winamp.action.STOP";

    private static final String CHANNEL_ID = "playback";
    private static final int NOTIF_ID = 1001;
    private static final String PREFS = "winamp";

    private final IBinder binder = new LocalBinder();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private WinampPlayer player;
    private final List<Track> tracks = new ArrayList<>();
    private SharedPreferences prefs;

    private MediaSession session;
    private NotificationManager nm;
    private AudioManager am;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus = false;
    private boolean resumeOnFocusGain = false;

    private boolean foreground = false;
    private Bitmap artwork;
    private String artworkKey;

    /** 播放列表变化通知（界面刷新用；列表本身归服务所有） */
    public interface PlaylistListener {
        void onPlaylistChanged();
    }

    private final List<PlaylistListener> playlistListeners = new CopyOnWriteArrayList<>();

    public void addPlaylistListener(PlaylistListener l) {
        if (l != null && !playlistListeners.contains(l)) playlistListeners.add(l);
    }

    public void removePlaylistListener(PlaylistListener l) {
        playlistListeners.remove(l);
    }

    private void firePlaylistChanged() {
        for (PlaylistListener l : playlistListeners) l.onPlaylistChanged();
    }

    private BroadcastReceiver noisyReceiver;
    private android.os.PowerManager.WakeLock wakeLock;

    /** 播放中每秒刷一次会话状态：进度条、锁屏上的时间、引擎刚起来时的播放态都靠它对齐 */
    private final Runnable stateTick = new Runnable() {
        @Override public void run() {
            updateSessionState();
            ui.postDelayed(this, 1000);
        }
    };

    public class LocalBinder extends Binder {
        public PlaybackService getService() { return PlaybackService.this; }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createChannel();

        player = new WinampPlayer(this, this);
        initSession();
        registerNoisyReceiver();
        loadPlaylistAsync();
        ui.postDelayed(stateTick, 1000);
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action != null) {
            switch (action) {
                case ACTION_TOGGLE:
                    if (player.isPlaying()) pause(); else play();
                    break;
                case ACTION_NEXT: next(); break;
                case ACTION_PREV: prev(); break;
                case ACTION_STOP: stop(); break;
                default: break;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 划掉任务：还在放就继续放（和主流音乐 App 一致），停下就顺手关掉服务
        if (!player.isPlaying()) {
            removeNotification();
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        releaseWake();
        if (noisyReceiver != null) {
            try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) { }
        }
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        if (player != null) player.release();
        super.onDestroy();
    }

    public WinampPlayer getPlayer() { return player; }

    // ---------------------------------------------------------------- 播放列表

    public List<Track> getTracks() {
        synchronized (tracks) {
            return new ArrayList<>(tracks);
        }
    }

    public void addTracks(List<Track> list) {
        if (list == null || list.isEmpty()) return;
        synchronized (tracks) {
            tracks.addAll(list);
        }
        player.setPlaylist(tracks);
        savePlaylist();
        firePlaylistChanged();
        if (player.getIndex() < 0) playAt(0);
    }

    public void removeTrack(int pos) {
        boolean wasPlaying;
        synchronized (tracks) {
            if (pos < 0 || pos >= tracks.size()) return;
            wasPlaying = (pos == player.getIndex());
            tracks.remove(pos);
        }
        if (wasPlaying) player.stop();
        player.setPlaylist(tracks);
        savePlaylist();
        firePlaylistChanged();
    }

    public void sortByTitle() {
        synchronized (tracks) {
            Collections.sort(tracks, new Comparator<Track>() {
                @Override public int compare(Track a, Track b) {
                    return a.title.toLowerCase().compareTo(b.title.toLowerCase());
                }
            });
        }
        player.setPlaylist(tracks);
        savePlaylist();
        firePlaylistChanged();
    }

    private void savePlaylist() {
        StringBuilder sb = new StringBuilder();
        synchronized (tracks) {
            for (Track t : tracks) sb.append(t.uri).append('\n');
        }
        prefs.edit().putString("playlist", sb.toString()).apply();
    }

    private void loadPlaylistAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean firstRun = prefs.getBoolean("first_run", true);
                String saved = prefs.getString("playlist", "");
                final List<Track> loaded = new ArrayList<>();
                boolean autoplay = false;
                if (firstRun) {
                    try {
                        Track def = MediaProbe.installDefaultSong(PlaybackService.this);
                        if (def != null) {
                            loaded.add(def);
                            autoplay = true;
                        }
                    } catch (Exception ignored) {
                    } finally {
                        prefs.edit().putBoolean("first_run", false).apply();
                    }
                } else if (prefs.getInt("default_song_ver", 0) < MediaProbe.DEFAULT_SONG_VERSION) {
                    // 老用户升级：覆盖 files 里的旧内置歌曲（修坏文件头）
                    try {
                        MediaProbe.installDefaultSong(PlaybackService.this);
                    } catch (Exception ignored) {
                    }
                }
                prefs.edit().putInt("default_song_ver", MediaProbe.DEFAULT_SONG_VERSION).apply();

                if (saved != null && !saved.isEmpty()) {
                    for (String line : saved.split("\n")) {
                        if (line.trim().isEmpty()) continue;
                        try {
                            Track t = MediaProbe.probe(PlaybackService.this, Uri.parse(line.trim()));
                            if (t != null) {
                                MediaProbe.fixDefaultSongMeta(t);
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
                ui.post(new Runnable() {
                    @Override public void run() {
                        player.setPlaylist(tracks);
                        savePlaylist();
                        firePlaylistChanged();
                        if (doAutoplay) playAt(0);
                        else updateSessionState();
                    }
                });
            }
        }, "playlist-load").start();
    }

    // ---------------------------------------------------------------- 传输控制

    public void playAt(int i) {
        requestFocus();
        player.playAt(i);
        updateSessionState();
    }

    public void play() {
        if (!requestFocus()) {
            Log.w(TAG, "audio focus denied");
        }
        player.play();
        updateSessionState();
    }

    public void pause() {
        player.pause();
        resumeOnFocusGain = false;
        updateSessionState();
    }

    public void stop() {
        player.stop();
        resumeOnFocusGain = false;
        updateSessionState();
        removeNotification();
        if (hasFocus) {
            abandonFocus();
        }
    }

    public void next() {
        player.next();
        updateSessionState();
    }

    public void prev() {
        player.prev();
        updateSessionState();
    }

    public void seekTo(long ms) {
        player.seekTo(ms);
        updateSessionState();
    }

    public boolean isPlaying() { return player.isPlaying(); }

    // ---------------------------------------------------------------- WinampPlayer.Listener

    @Override
    public void onTrackChanged(Track t, int index) {
        artwork = null;
        artworkKey = null;
        updateMetadata(t);
        updateSessionState();
        ensureForeground();
        loadArtworkAsync(t);
    }

    @Override
    public void onPlayingState(boolean playing) {
        updateSessionState();
        if (playing) {
            acquireWake();
            ensureForeground();
        } else {
            releaseWake();
            if (foreground) {
                nm.notify(NOTIF_ID, buildNotification());
            }
        }
    }

    /**
     * 息屏后解码线程还得按时喂 AudioTrack，没有唤醒锁会被深度睡眠掐住（表现为放着放着断掉）。
     * 只在播放期间持有，暂停/停止立刻释放。
     */
    private void acquireWake() {
        try {
            if (wakeLock == null) {
                android.os.PowerManager pm =
                        (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                        "MYXR_WINAMP:playback");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "acquire wakelock failed", e);
        }
    }

    private void releaseWake() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- MediaSession

    private void initSession() {
        session = new MediaSession(this, "MYXR_WINAMP");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { PlaybackService.this.play(); }
            @Override public void onPause() { PlaybackService.this.pause(); }
            @Override public void onStop() { PlaybackService.this.stop(); }
            @Override public void onSkipToNext() { PlaybackService.this.next(); }
            @Override public void onSkipToPrevious() { PlaybackService.this.prev(); }
            @Override public void onSeekTo(long pos) { PlaybackService.this.seekTo(pos); }
        });
        session.setSessionActivity(contentIntent());
        session.setActive(true);
    }

    private long safePosition() {
        long p = player.getPosition();
        return p < 0 ? 0 : p;
    }

    private long safeDuration() {
        long d = player.getDuration();
        if (d <= 0) {
            Track t = player.getCurrentTrack();
            if (t != null) d = t.durationMs;
        }
        return d < 0 ? 0 : d;
    }

    private void updateSessionState() {
        if (session == null) return;
        boolean playing = player.isPlaying();
        boolean hasTrack = player.getCurrentTrack() != null;
        int state;
        if (playing) state = PlaybackState.STATE_PLAYING;
        else if (hasTrack) state = PlaybackState.STATE_PAUSED;
        else state = PlaybackState.STATE_NONE;

        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO;

        PlaybackState.Builder b = new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, safePosition(), playing ? 1f : 0f)
                .setBufferedPosition(safeDuration());
        session.setPlaybackState(b.build());
    }

    private void updateMetadata(Track t) {
        if (session == null || t == null) return;
        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "MYXR WINAMP")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, t.durationMs))
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, player.getIndex() + 1)
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, player.getCount());
        if (artwork != null) {
            m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
            m.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork);
        }
        session.setMetadata(m.build());
    }

    /** 封面/视频首帧异步加载，拿到后再刷一次元数据与通知 */
    private void loadArtworkAsync(final Track t) {
        final String key = t.uri;
        new Thread(new Runnable() {
            @Override public void run() {
                // 320px 是刻意的上限：MediaMetadata 要跨进程传 Bitmap，
                // 512x512 ARGB 就是 1MB，正好踩到 binder 事务上限。
                final Bitmap b = MediaProbe.artwork(PlaybackService.this, Uri.parse(key), 320);
                ui.post(new Runnable() {
                    @Override public void run() {
                        Track cur = player.getCurrentTrack();
                        if (cur == null || !key.equals(cur.uri)) return;
                        artwork = b;
                        artworkKey = key;
                        updateMetadata(cur);
                        if (foreground) nm.notify(NOTIF_ID, buildNotification());
                    }
                });
            }
        }, "artwork").start();
    }

    // ---------------------------------------------------------------- 通知

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "播放控制",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("锁屏与通知栏的播放控制");
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private PendingIntent contentIntent() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent actionIntent(String action, int reqCode) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        Track t = player.getCurrentTrack();
        boolean playing = player.isPlaying();
        String title = t != null ? t.title : "MYXR WINAMP";
        String text = t != null ? t.artist : "未在播放";

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_winamp)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent())
                .setDeleteIntent(actionIntent(ACTION_STOP, 4))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(playing)
                .setColor(0xFF2BFF2B)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_media_prev),
                        "上一首", actionIntent(ACTION_PREV, 1)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this,
                                playing ? R.drawable.ic_media_pause : R.drawable.ic_media_play),
                        playing ? "暂停" : "播放", actionIntent(ACTION_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_media_next),
                        "下一首", actionIntent(ACTION_NEXT, 3)).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (artwork != null) b.setLargeIcon(artwork);
        if (t != null && t.hasVideo) b.setSubText("视频");
        return b.build();
    }

    /** 有曲目在放就升级为前台服务（息屏/后台不被回收，锁屏才有控件） */
    private void ensureForeground() {
        if (session == null) return;
        Notification n = buildNotification();
        try {
            if (!foreground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIF_ID, n);
                }
                foreground = true;
            } else {
                nm.notify(NOTIF_ID, n);
            }
        } catch (Exception e) {
            // Android 12+ 在后台启动前台服务会抛 ForegroundServiceStartNotAllowedException
            Log.w(TAG, "startForeground failed", e);
        }
    }

    private void removeNotification() {
        if (foreground) {
            foreground = false;
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } catch (Exception ignored) { }
        }
        try { nm.cancel(NOTIF_ID); } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- 音频焦点 / 耳机

    private boolean requestFocus() {
        if (hasFocus) return true;
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override public void onAudioFocusChange(int change) {
                            ui.post(new Runnable() {
                                @Override public void run() { handleFocusChange(change); }
                            });
                        }
                    })
                    .build();
        }
        int r = am.requestAudioFocus(focusRequest);
        hasFocus = (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        return hasFocus;
    }

    private void abandonFocus() {
        if (focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
        hasFocus = false;
    }

    private void handleFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                if (player.isPlaying()) {
                    resumeOnFocusGain = false;
                    player.pause();
                    updateSessionState();
                }
                hasFocus = false;
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (player.isPlaying()) {
                    resumeOnFocusGain = true;
                    player.pause();
                    updateSessionState();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // 不压低音量：Winamp 的听感优先，短暂让出即可
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false;
                    play();
                }
                break;
            default:
                break;
        }
    }

    private void registerNoisyReceiver() {
        noisyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                    if (player.isPlaying()) pause();
                }
            }
        };
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(noisyReceiver, f);
    }

    /** 从界面手动开播时用：把服务拉成"已启动"状态，界面退出后继续放 */
    public static void start(Context c) {
        Intent i = new Intent(c, PlaybackService.class);
        try { c.startService(i); } catch (Exception ignored) { }
    }
}
