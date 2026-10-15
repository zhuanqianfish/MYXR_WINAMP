package com.dalang.winamp;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 播放核心：MediaPlayer + Visualizer（频谱）+ Equalizer（均衡器）。
 * Visualizer/Equalizer 挂在 MediaPlayer 的 audio session 上，reset() 后 session 不变，
 * 因此只需创建一次，换歌后继续生效。
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
    private final Listener listener;
    private final MediaPlayer mp;
    private Visualizer vis;
    private Equalizer eq;
    private SpectrumView spectrumView;
    private final List<Track> playlist = new ArrayList<>();
    private int index = -1;
    private boolean shuffle = false;
    private boolean repeat = false;
    private boolean prepared = false;
    private boolean eqEnabled = true;
    private float vol = 0.8f;   // 0..1
    private float bal = 0f;     // -1..1
    private float preampDb = 0f;
    private final Random random = new Random();
    private int consecutiveErrors = 0;

    // ---- Visualizer 看门狗：player session 3 秒无 FFT 数据则切到系统总线（session 0）----
    private long lastFftTimeMs = 0; // 只有真正收到 FFT 回调才更新
    private long visSessionStartMs = 0;
    private boolean visOnMix = false;
    private final Handler fxHandler = new Handler(Looper.getMainLooper());
    // ---- 本地 PCM 频谱：不经过系统 Visualizer，解码算 FFT，设备无关 ----
    private final PcmSpectrumProvider pcmSpec = new PcmSpectrumProvider();
    private final float[] barVals = new float[PcmSpectrumProvider.BARS];
    private final byte[] synthFft = new byte[1024];
    private final byte[] synthWave = new byte[1024];
    private boolean pumpRunning = false;
    private final Visualizer.OnDataCaptureListener captureListener =
            new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int rate) {
                    if (spectrumView != null) spectrumView.setWave(waveform);
                }

                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int rate) {
                    lastFftTimeMs = SystemClock.uptimeMillis();
                    if (spectrumView != null) spectrumView.setFft(fft);
                }
            };
    private final Runnable visWatchdog = new Runnable() {
        @Override
        public void run() {
            if (visOnMix) return;
            try {
                long now = SystemClock.uptimeMillis();
                if (prepared && mp.isPlaying()
                        && now - visSessionStartMs > 3000
                        && now - lastFftTimeMs > 3000) {
                    switchVisToMix();
                }
            } catch (Exception e) {
                Log.w(TAG, "visWatchdog", e);
            }
        }
    };

    /**
     * PCM 频谱泵：每 120ms 按播放进度算一次 FFT。只在系统 Visualizer
     * 超过 1.5 秒没给数据时才接管（避免两路打架）。
     */
    private final Runnable spectrumPump = new Runnable() {
        @Override
        public void run() {
            if (!pumpRunning) return;
            try {
                if (prepared && pcmSpec.isReady()
                        && SystemClock.uptimeMillis() - lastFftTimeMs > 1500) {
                    long pos = mp.getCurrentPosition();
                    if (pcmSpec.getBarValues(pos, barVals)) {
                        synthFftFromBars(barVals, synthFft);
                        if (spectrumView != null) spectrumView.setFft(synthFft);
                    }
                    if (pcmSpec.getWaveBytes(pos, synthWave)) {
                        if (spectrumView != null) spectrumView.setWave(synthWave);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "spectrumPump", e);
            }
            if (pumpRunning) fxHandler.postDelayed(this, 120);
        }
    };

    private void startPump() {
        if (!pumpRunning) {
            pumpRunning = true;
            fxHandler.post(spectrumPump);
        }
    }

    private void stopPump() {
        pumpRunning = false;
        fxHandler.removeCallbacks(spectrumPump);
    }

    /** 把 28 段 0..1 反推成 Visualizer FFT 字节格式，复用 SpectrumView 现有绘制 */
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

    // 最近一次 probe 到的音频格式
    private int lastSampleRate = 0;
    private int lastBitrate = 0;
    private int lastChannels = 0;

    public WinampPlayer(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        mp = new MediaPlayer();
        mp.setOnCompletionListener(m -> onCompletion());
        mp.setOnErrorListener((m, what, extra) -> {
            Log.w(TAG, "player error " + what + "," + extra);
            handleTrackError();
            return true;
        });
    }

    public void setSpectrumView(SpectrumView v) { spectrumView = v; }

    public void setPlaylist(List<Track> list) {
        playlist.clear();
        playlist.addAll(list);
        if (index >= playlist.size()) index = -1;
    }

    public int getIndex() { return index; }
    public int getCount() { return playlist.size(); }

    public void setShuffle(boolean s) { shuffle = s; }
    public void setRepeat(boolean r) { repeat = r; }

    // ---------------- 播放控制 ----------------

    public void playAt(int i) {
        if (playlist.isEmpty() || i < 0 || i >= playlist.size()) return;
        index = i;
        Track t = playlist.get(i);
        try {
            prepared = false;
            mp.reset();
            mp.setDataSource(appContext, Uri.parse(t.uri));
            mp.prepare();
            prepared = true;
            attachFx();
            applyVolumes();
            probeFormat(t.uri);
            mp.start();
            ensureVisEnabled();
            armVisWatchdog();
            pcmSpec.load(appContext, t.uri, t.durationMs);
            startPump();
            consecutiveErrors = 0;
            listener.onTrackChanged(t, index);
            listener.onPlayingState(true);
        } catch (Exception e) {
            Log.w(TAG, "playAt failed: " + t.uri, e);
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
            try {
                mp.reset();
            } catch (Exception ignored) {
            }
            listener.onPlayingState(false);
            return;
        }
        next();
    }

    public void play() {
        if (!prepared) {
            if (!playlist.isEmpty()) playAt(index >= 0 ? index : 0);
            return;
        }
        try {
            mp.start();
            ensureVisEnabled();
            armVisWatchdog();
            startPump();
            listener.onPlayingState(true);
        } catch (Exception e) {
            Log.w(TAG, "play failed", e);
        }
    }

    public void pause() {
        try {
            fxHandler.removeCallbacks(visWatchdog);
            stopPump();
            if (mp.isPlaying()) mp.pause();
            listener.onPlayingState(false);
        } catch (Exception e) {
            Log.w(TAG, "pause failed", e);
        }
    }

    public void stop() {
        try {
            fxHandler.removeCallbacks(visWatchdog);
            stopPump();
            if (prepared) {
                if (mp.isPlaying()) mp.pause();
                mp.seekTo(0);
            }
            listener.onPlayingState(false);
        } catch (Exception e) {
            Log.w(TAG, "stop failed", e);
        }
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
            stop();
        }
    }

    public void seekTo(long ms) {
        try {
            if (prepared) mp.seekTo((int) ms);
        } catch (Exception e) {
            Log.w(TAG, "seek failed", e);
        }
    }

    public long getPosition() {
        try {
            return prepared ? mp.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public long getDuration() {
        try {
            return prepared ? mp.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean isPlaying() {
        try {
            return prepared && mp.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- 音量 / 声道 ----------------

    public void setVolume(float v) {
        vol = Math.max(0f, Math.min(1f, v));
        applyVolumes();
    }

    public void setBalance(float b) {
        bal = Math.max(-1f, Math.min(1f, b));
        applyVolumes();
    }

    public void setPreampDb(float db) {
        preampDb = db;
        applyVolumes();
    }

    private void applyVolumes() {
        float gain = vol * (float) Math.pow(10, preampDb / 20.0);
        gain = Math.max(0f, Math.min(1f, gain));
        float left = gain * (bal < 0 ? 1f + bal : 1f);
        float right = gain * (bal > 0 ? 1f - bal : 1f);
        try {
            mp.setVolume(left, right);
        } catch (Exception e) {
            Log.w(TAG, "setVolume failed", e);
        }
    }

    // ---------------- Visualizer / Equalizer ----------------

    private void attachFx() {
        int session = mp.getAudioSessionId();
        if (session == 0) return;
        if (vis == null) {
            try {
                vis = new Visualizer(session);
                vis.setCaptureSize(1024);
                vis.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
                vis.setDataCaptureListener(captureListener,
                        Visualizer.getMaxCaptureRate() / 2, true, true);
                // 启用失败则丢弃，下次播放时重试（部分手机首次 attach 会静默失败）
                if (vis.setEnabled(true) != Visualizer.SUCCESS) {
                    try {
                        vis.release();
                    } catch (Exception ignored) {
                    }
                    vis = null;
                    Log.w(TAG, "Visualizer enable failed, will retry next play");
                }
            } catch (Exception e) {
                Log.w(TAG, "Visualizer unavailable", e);
                vis = null;
            }
        }
        if (eq == null) {
            try {
                eq = new Equalizer(0, session);
                eq.setEnabled(eqEnabled);
            } catch (Exception e) {
                Log.w(TAG, "Equalizer unavailable", e);
                eq = null;
            }
        }
    }

    /** 每次真正开始播放后都确保 Visualizer 处于启用态 */
    private void ensureVisEnabled() {
        if (vis == null) {
            attachFx();
            return;
        }
        try {
            if (!vis.getEnabled() && vis.setEnabled(true) != Visualizer.SUCCESS) {
                try {
                    vis.release();
                } catch (Exception ignored) {
                }
                vis = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "ensureVisEnabled failed", e);
        }
    }

    /**
     * 看门狗触发：player session 拿不到数据（offload 硬解/系统音效占用），
     * 改从系统输出总线（session 0）抓，能绕开上述占用。
     */
    private void switchVisToMix() {
        try {
            releaseVis();
            vis = new Visualizer(0);
            vis.setCaptureSize(1024);
            vis.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            vis.setDataCaptureListener(captureListener,
                    Visualizer.getMaxCaptureRate() / 2, true, true);
            if (vis.setEnabled(true) == Visualizer.SUCCESS) {
                visOnMix = true;
                Log.i(TAG, "Visualizer switched to output mix (session 0)");
            } else {
                releaseVis();
            }
        } catch (Exception e) {
            Log.w(TAG, "switchVisToMix failed", e);
            releaseVis();
        }
    }

    private void releaseVis() {
        if (vis == null) return;
        try {
            vis.setEnabled(false);
        } catch (Exception ignored) {
        }
        try {
            vis.release();
        } catch (Exception ignored) {
        }
        vis = null;
    }

    private void armVisWatchdog() {
        visSessionStartMs = SystemClock.uptimeMillis();
        fxHandler.removeCallbacks(visWatchdog);
        fxHandler.postDelayed(visWatchdog, 3500);
    }

    public int getEqBandCount() {
        try {
            return eq == null ? 0 : eq.getNumberOfBands();
        } catch (Exception e) {
            return 0;
        }
    }

    /** sliderIndex: 0..9 对应 10 段推子；db: -12..+12 */
    public void setEqBandDb(int sliderIndex, float db) {
        if (eq == null) return;
        try {
            short bands = eq.getNumberOfBands();
            if (bands <= 0) return;
            short band = (short) (sliderIndex * bands / 10);
            if (band >= bands) band = (short) (bands - 1);
            short[] range = eq.getBandLevelRange();
            short mb = (short) Math.round(db * 100);
            if (mb < range[0]) mb = range[0];
            if (mb > range[1]) mb = range[1];
            eq.setBandLevel(band, mb);
        } catch (Exception e) {
            Log.w(TAG, "setBandLevel failed", e);
        }
    }

    public void setEqEnabled(boolean on) {
        eqEnabled = on;
        try {
            if (eq != null) eq.setEnabled(on);
        } catch (Exception e) {
            Log.w(TAG, "eq enable failed", e);
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
        fxHandler.removeCallbacks(visWatchdog);
        stopPump();
        pcmSpec.release();
        try {
            if (vis != null) {
                vis.setEnabled(false);
                vis.release();
                vis = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (eq != null) {
                eq.release();
                eq = null;
            }
        } catch (Exception ignored) {
        }
        try {
            mp.release();
        } catch (Exception ignored) {
        }
    }
}
