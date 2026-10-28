package com.dalang.winamp;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 播放核心：自研 PCM 引擎（{@link PcmAudioEngine}）+ 真正的 10 段均衡器。
 *
 * 不再使用 MediaPlayer：解码 → 前级 → 10 段 biquad → 音量/平衡 → AudioTrack。
 * 均衡器的频点固定为 Winamp 的 60/170/310/600/1k/3k/6k/12k/14k/16k，
 * 与设备有几段、是否走 offload 无关；频谱也从同一份 PCM 直接算出来。
 *
 * 对外 API 与旧版完全一致，MainActivity 不需要改。
 */
public class WinampPlayer {

    private static final String TAG = "WinampPlayer";

    public interface Listener {
        void onTrackChanged(Track t, int index);
        void onPlayingState(boolean playing);
    }

    public static final String[] PRESET_NAMES =
            {"Flat", "Rock", "Pop", "Jazz", "Classical", "Dance"};
    /** 10 段预设值，0..1（0.5 = 0dB） */
    public static final float[][] PRESETS = {
            {0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f}, // Flat
            {0.72f, 0.66f, 0.58f, 0.5f, 0.44f, 0.44f, 0.5f, 0.58f, 0.66f, 0.72f}, // Rock
            {0.6f, 0.66f, 0.7f, 0.62f, 0.5f, 0.5f, 0.55f, 0.6f, 0.62f, 0.6f}, // Pop
            {0.6f, 0.58f, 0.55f, 0.55f, 0.5f, 0.55f, 0.62f, 0.66f, 0.68f, 0.66f}, // Jazz
            {0.62f, 0.58f, 0.55f, 0.5f, 0.5f, 0.5f, 0.55f, 0.62f, 0.68f, 0.7f}, // Classical
            {0.7f, 0.66f, 0.58f, 0.5f, 0.46f, 0.52f, 0.6f, 0.66f, 0.68f, 0.64f}, // Dance
    };
    public static final float[] AUTO_CURVE =
            {0.58f, 0.56f, 0.52f, 0.5f, 0.5f, 0.52f, 0.58f, 0.62f, 0.6f, 0.56f};

    private final Context appContext;
    /**
     * 监听者可以有多个：PlaybackService 用它更新媒体会话/通知，
     * MainActivity 用它刷新界面。互不覆盖。
     */
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private PcmAudioEngine engine;
    private SpectrumView spectrumView;

    private final List<Track> playlist = new ArrayList<>();
    private int index = -1;
    private boolean shuffle = false;
    private boolean repeat = false;
    private boolean prepared = false;
    private int consecutiveErrors = 0;

    // EQ / 音量状态（换歌后要重新灌进新引擎）
    private boolean eqEnabled = true;
    private float vol = 0.8f;      // 0..1
    private float bal = 0f;        // -1..1
    private float preampDb = 0f;   // dB
    private final float[] bandDb = new float[PcmAudioEngine.BANDS];

    // 最近一次 probe 到的音频格式
    private int lastSampleRate = 0;
    private int lastBitrate = 0;
    private int lastChannels = 0;

    // 引擎回调代数：换歌后旧引擎的迟到回调直接丢弃
    private int generation = 0;

    private final byte[] synthFft = new byte[1024];
    private final Random random = new Random();

    public WinampPlayer(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        if (listener != null) listeners.add(listener);
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fireTrackChanged(Track t, int i) {
        for (Listener l : listeners) l.onTrackChanged(t, i);
    }

    private void firePlayingState(boolean playing) {
        for (Listener l : listeners) l.onPlayingState(playing);
    }

    public void setSpectrumView(SpectrumView v) { spectrumView = v; }

    public void setPlaylist(List<Track> list) {
        playlist.clear();
        playlist.addAll(list);
        if (index >= playlist.size()) index = -1;
    }

    public int getIndex() { return index; }
    public int getCount() { return playlist.size(); }

    /** 当前曲目（未播放时为 null） */
    public Track getCurrentTrack() {
        int i = index;
        return (i >= 0 && i < playlist.size()) ? playlist.get(i) : null;
    }

    public void setShuffle(boolean s) { shuffle = s; }
    public void setRepeat(boolean r) { repeat = r; }
    public boolean isShuffle() { return shuffle; }
    public boolean isRepeat() { return repeat; }

    // ---------------- 播放控制 ----------------

    public void playAt(int i) {
        if (playlist.isEmpty() || i < 0 || i >= playlist.size()) return;
        index = i;
        Track t = playlist.get(i);
        try {
            releaseEngine();
            generation++;
            final int gen = generation;

            PcmAudioEngine e = new PcmAudioEngine(appContext, t.uri,
                    new PcmAudioEngine.Listener() {
                        @Override
                        public void onEnded() {
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    if (gen != generation) return;
                                    onCompletion();
                                }
                            });
                        }

                        @Override
                        public void onError(final String message) {
                            Log.w(TAG, "engine: " + message);
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    if (gen != generation) return;
                                    prepared = false;
                                    handleTrackError();
                                }
                            });
                        }

                        @Override
                        public void onSpectrum(float[] bars, byte[] wave) {
                            SpectrumView sv = spectrumView;
                            if (sv == null) return;
                            synthFftFromBars(bars, synthFft);
                            sv.setFft(synthFft);
                            sv.setWave(wave);
                        }
                    });

            engine = e;
            e.setDurationHint(t.durationMs);
            applyEngineParams(e);
            e.start();
            prepared = true;
            probeFormat(t.uri);
            consecutiveErrors = 0;
            fireTrackChanged(t, index);
            firePlayingState(true);
        } catch (Exception ex) {
            Log.w(TAG, "playAt failed: " + t.uri, ex);
            prepared = false;
            handleTrackError();
        }
    }

    /** 切歌失败/播放出错时调用：连续失败超过列表长度则停下，避免无限递归 */
    private void handleTrackError() {
        consecutiveErrors++;
        if (!playlist.isEmpty() && consecutiveErrors >= playlist.size()) {
            consecutiveErrors = 0;
            prepared = false;
            releaseEngine();
            firePlayingState(false);
            return;
        }
        next();
    }

    public void play() {
        PcmAudioEngine e = engine;
        if (e == null || !prepared || !e.isAlive()) {
            if (!playlist.isEmpty()) playAt(index >= 0 ? index : 0);
            return;
        }
        e.play();
        firePlayingState(true);
    }

    public void pause() {
        PcmAudioEngine e = engine;
        if (e != null) e.pause();
        firePlayingState(false);
    }

    public void stop() {
        PcmAudioEngine e = engine;
        if (e != null) {
            e.seekTo(0);
            e.pause();
        }
        firePlayingState(false);
    }

    public void next() {
        if (playlist.isEmpty()) return;
        int n;
        if (shuffle && playlist.size() > 1) {
            do {
                n = random.nextInt(playlist.size());
            } while (n == index);
        } else {
            n = (index + 1) % playlist.size();
        }
        playAt(n);
    }

    public void prev() {
        if (playlist.isEmpty()) return;
        int n;
        if (shuffle && playlist.size() > 1) {
            do {
                n = random.nextInt(playlist.size());
            } while (n == index);
        } else {
            n = (index - 1 + playlist.size()) % playlist.size();
        }
        playAt(n);
    }

    private void onCompletion() {
        if (playlist.isEmpty()) return;
        if (repeat) {
            playAt(index);
        } else if (shuffle || index < playlist.size() - 1) {
            next();
        } else {
            // 放完最后一首：引擎线程已经结束，必须释放掉，否则 play() 会拨不动
            prepared = false;
            releaseEngine();
            firePlayingState(false);
        }
    }

    public void seekTo(long ms) {
        PcmAudioEngine e = engine;
        if (e != null) e.seekTo(ms);
    }

    public long getPosition() {
        PcmAudioEngine e = engine;
        return e == null ? 0 : e.getPositionMs();
    }

    public long getDuration() {
        PcmAudioEngine e = engine;
        return e == null ? 0 : e.getDurationMs();
    }

    public boolean isPlaying() {
        PcmAudioEngine e = engine;
        return prepared && e != null && e.isPlaying();
    }

    // ---------------- 音量 / 声道 ----------------

    public void setVolume(float v) {
        vol = Math.max(0f, Math.min(1f, v));
        PcmAudioEngine e = engine;
        if (e != null) e.setVolume(vol);
    }

    public float getVolume() { return vol; }

    public void setBalance(float b) {
        bal = Math.max(-1f, Math.min(1f, b));
        PcmAudioEngine e = engine;
        if (e != null) e.setBalance(bal);
    }

    public float getBalance() { return bal; }

    /** 真前级：在 10 段滤波器之前乘增益（不再是 MediaPlayer 音量那种会被 1.0 截顶的假前级） */
    public void setPreampDb(float db) {
        preampDb = db;
        PcmAudioEngine e = engine;
        if (e != null) e.setPreampDb(preampDb);
    }

    // ---------------- 均衡器 ----------------

    public int getEqBandCount() { return PcmAudioEngine.BANDS; }

    /** sliderIndex: 0..9，对应 60/170/310/600/1k/3k/6k/12k/14k/16k；db: 增益 */
    public void setEqBandDb(int sliderIndex, float db) {
        if (sliderIndex < 0 || sliderIndex >= PcmAudioEngine.BANDS) return;
        bandDb[sliderIndex] = db;
        PcmAudioEngine e = engine;
        if (e != null) e.setBandDb(sliderIndex, db);
    }

    public void setEqEnabled(boolean on) {
        eqEnabled = on;
        PcmAudioEngine e = engine;
        if (e != null) e.setEqEnabled(on);
    }

    private void applyEngineParams(PcmAudioEngine e) {
        e.setVolume(vol);
        e.setBalance(bal);
        e.setPreampDb(preampDb);
        e.setEqEnabled(eqEnabled);
        for (int b = 0; b < PcmAudioEngine.BANDS; b++) {
            e.setBandDb(b, bandDb[b]);
        }
    }

    private void releaseEngine() {
        PcmAudioEngine e = engine;
        engine = null;
        if (e != null) {
            try {
                e.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** 把 28 段 0..1 反推成 Visualizer FFT 字节格式，复用 SpectrumView 的绘制 */
    private static void synthFftFromBars(float[] bars, byte[] fft) {
        java.util.Arrays.fill(fft, (byte) 0);
        int bins = fft.length / 2; // 512
        for (int b = 0; b < bars.length; b++) {
            float v = Math.max(0f, Math.min(1f, bars[b]));
            int mag = (int) (Math.pow(10, v * 42 / 20.0) - 1);
            if (mag > 127) mag = 127;
            if (mag < 0) mag = 0;
            int bin0 = 1 + (int) (Math.pow((double) b / bars.length, 2.0) * (bins - 2));
            int bin1 = 1 + (int) (Math.pow((double) (b + 1) / bars.length, 2.0) * (bins - 2));
            if (bin1 < bin0) bin1 = bin0;
            for (int i = bin0; i <= bin1 && 2 * i + 1 < fft.length; i++) {
                fft[2 * i] = (byte) mag;
                fft[2 * i + 1] = 0;
            }
        }
    }

    // ---------------- 音频格式探测 ----------------

    private void probeFormat(String uriStr) {
        lastSampleRate = 0;
        lastBitrate = 0;
        lastChannels = 0;
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(appContext, Uri.parse(uriStr), null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        lastSampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (f.containsKey(MediaFormat.KEY_BIT_RATE))
                        lastBitrate = f.getInteger(MediaFormat.KEY_BIT_RATE);
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        lastChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "probeFormat failed", e);
        } finally {
            try {
                ex.release();
            } catch (Exception ignored) {
            }
        }
    }

    public int getLastSampleRate() { return lastSampleRate; }
    public int getLastBitrate() { return lastBitrate; }
    public int getLastChannels() { return lastChannels; }

    public void release() {
        generation++;
        releaseEngine();
    }
}
