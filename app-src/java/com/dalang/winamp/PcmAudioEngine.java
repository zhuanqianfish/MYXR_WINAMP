package com.dalang.winamp;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * PCM 播放引擎（自研，不经过 MediaPlayer）：
 *
 *   MediaExtractor → MediaCodec 解码 → float PCM
 *     → PREAMP 增益 → 10 段 peaking biquad → 音量/平衡 → 削顶 → AudioTrack
 *
 * 这样均衡器是真的 10 段、频点固定为 Winamp 的 60/170/310/600/1k/3k/6k/12k/14k/16k，
 * 不受机型 Equalizer 只有 5 段、也不受硬件 offload 让 AudioEffect 失效的影响。
 * 频谱同时从同一份 PCM 算（不再依赖系统 Visualizer）。
 */
public class PcmAudioEngine {

    private static final String TAG = "PcmEngine";
    private static final int TIMEOUT_US = 2000;

    public static final int BANDS = 10;
    /** Winamp 经典 10 段 EQ 的中心频率 */
    public static final float[] FREQS = {60f, 170f, 310f, 600f, 1000f,
            3000f, 6000f, 12000f, 14000f, 16000f};
    /** 每段带宽（Q）。1.1 接近原版那种"一格一格"的听感，又不会振铃 */
    private static final float BAND_Q = 1.1f;

    public static final int BARS = 28;
    private static final int FFT_N = 1024;

    public interface Listener {
        void onEnded();
        void onError(String message);
        void onSpectrum(float[] bars, byte[] wave);
    }

    // ---------------------------------------------------------------- biquad

    /** 转置直接 II 型双二阶（每声道一份状态，系数共用） */
    static final class Biquad {
        float b0 = 1f, b1, b2, a1, a2;
        float s1, s2;

        void set(float[] c) {
            b0 = c[0];
            b1 = c[1];
            b2 = c[2];
            a1 = c[3];
            a2 = c[4];
        }

        void reset() { s1 = 0; s2 = 0; }

        float process(float x) {
            float y = b0 * x + s1;
            s1 = b1 * x - a1 * y + s2;
            s2 = b2 * x - a2 * y;
            return y;
        }
    }

    /** RBJ cookbook peaking EQ，归一化到 a0=1 */
    private static void peakingCoeffs(double fs, double f0, double q, double gainDb, float[] out) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * f0 / fs;
        double alpha = Math.sin(w0) / (2.0 * q);
        double cosw = Math.cos(w0);
        double a0 = 1.0 + alpha / A;
        out[0] = (float) ((1.0 + alpha * A) / a0);
        out[1] = (float) (-2.0 * cosw / a0);
        out[2] = (float) ((1.0 - alpha * A) / a0);
        out[3] = (float) (-2.0 * cosw / a0);
        out[4] = (float) ((1.0 - alpha / A) / a0);
    }

    // ---------------------------------------------------------------- 状态

    private final Context appContext;
    private final String uriStr;
    private final Listener listener;

    private Thread worker;
    private volatile boolean running = false;
    private volatile boolean paused = false;
    private volatile long seekRequestMs = -1;

    private volatile long durationMs = 0;
    private volatile int sampleRate = 44100;
    /** 解码输出的声道数（输入格式给初值，输出格式变了会更新） */
    private volatile int outChannels = 2;
    /** AudioTrack 的实际采样率，变了要重建 */
    private int trackSampleRate = 0;

    private volatile float volume = 1f;
    private volatile float balance = 0f;
    private volatile float preampGain = 1f;
    private volatile boolean eqEnabled = true;
    private volatile boolean coeffsDirty = true;

    private final float[] bandDb = new float[BANDS];
    private final boolean[] bandActive = new boolean[BANDS];
    private final float[] coeffBuf = new float[5];
    private final Biquad[] eqL = new Biquad[BANDS];
    private final Biquad[] eqR = new Biquad[BANDS];

    /** 播放头基准：seek 后 headPosition 归零，位置 = basePositionMs + head 帧数换算 */
    private volatile long basePositionMs = 0;
    private volatile long writtenFrames = 0;
    private volatile AudioTrack track;
    private volatile boolean trackPlaying = false;
    private volatile boolean ended = false;

    // 频谱
    private final float[] specRing = new float[FFT_N];
    private final float[] fftRe = new float[FFT_N];
    private final float[] fftIm = new float[FFT_N];
    private final float[] hann = new float[FFT_N];
    private final float[] bars = new float[BARS];
    private final byte[] wave = new byte[1024];
    private int specPos = 0;
    private long lastSpecMs = 0;

    public PcmAudioEngine(Context ctx, String uri, Listener l) {
        this.appContext = ctx.getApplicationContext();
        this.uriStr = uri;
        this.listener = l;
        for (int i = 0; i < BANDS; i++) {
            eqL[i] = new Biquad();
            eqR[i] = new Biquad();
        }
        for (int i = 0; i < FFT_N; i++) {
            hann[i] = (float) (0.5 * (1 - Math.cos(2 * Math.PI * i / FFT_N)));
        }
    }

    // ---------------------------------------------------------------- 对外控制

    public void start() {
        if (running) return;
        running = true;
        paused = false;
        ended = false;
        worker = new Thread(new Runnable() {
            @Override public void run() { runLoop(); }
        }, "pcm-engine");
        worker.start();
    }

    public void play() {
        paused = false;
        AudioTrack t = track;
        if (t != null) {
            try { t.play(); trackPlaying = true; } catch (Exception ignored) { }
        }
    }

    public void pause() {
        paused = true;
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); trackPlaying = false; } catch (Exception ignored) { }
        }
    }

    public void stop() {
        running = false;
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); } catch (Exception ignored) { }
        }
        Thread w = worker;
        worker = null;
        if (w != null) {
            try { w.join(600); } catch (InterruptedException ignored) { }
        }
    }

    public void release() {
        stop();
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try { t.flush(); } catch (Exception ignored) { }
            try { t.release(); } catch (Exception ignored) { }
        }
    }

    public void seekTo(long ms) {
        seekRequestMs = Math.max(0, ms);
    }

    public boolean isPlaying() {
        return running && !paused && trackPlaying;
    }

    /** 解码线程是否还活着（播完后为 false，需要重新 open） */
    public boolean isAlive() { return running; }

    public long getPositionMs() {
        AudioTrack t = track;
        if (t == null) return basePositionMs;
        long head;
        try {
            head = ((long) t.getPlaybackHeadPosition()) & 0xFFFFFFFFL;
        } catch (Exception e) {
            return basePositionMs;
        }
        if (trackPlaying || head > 0) {
            return basePositionMs + head * 1000L / Math.max(1, sampleRate);
        }
        return basePositionMs;
    }

    public long getDurationMs() { return durationMs; }
    public int getSampleRate() { return sampleRate; }

    /** 曲目元数据里的时长兜底（解码器不报 KEY_DURATION 时用） */
    public void setDurationHint(long ms) {
        if (ms > 0 && durationMs <= 0) durationMs = ms;
    }

    // ---------------------------------------------------------------- 参数

    public void setVolume(float v) { volume = clamp01(v); }

    public void setBalance(float b) { balance = Math.max(-1f, Math.min(1f, b)); }

    /** PREAMP，单位 dB（-12..+12）。这是真的前级：在滤波器之前乘增益。 */
    public void setPreampDb(float db) {
        preampGain = (float) Math.pow(10.0, db / 20.0);
    }

    public void setEqEnabled(boolean on) {
        if (eqEnabled != on) {
            eqEnabled = on;
            coeffsDirty = true;
        }
    }

    /** 0..9 段增益，单位 dB */
    public void setBandDb(int index, float db) {
        if (index < 0 || index >= BANDS) return;
        bandDb[index] = db;
        coeffsDirty = true;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /** 在音频线程重建滤波器系数（系数变了不清状态，避免爆音） */
    private void rebuildFilters() {
        for (int i = 0; i < BANDS; i++) {
            // 超过 0.45*fs 的频点在 Nyquist 附近，biquad 会退化甚至发散，直接跳过
            boolean active = eqEnabled
                    && Math.abs(bandDb[i]) > 0.01f
                    && FREQS[i] < sampleRate * 0.45f;
            bandActive[i] = active;
            if (!active) continue;
            peakingCoeffs(sampleRate, FREQS[i], BAND_Q, bandDb[i], coeffBuf);
            eqL[i].set(coeffBuf);
            eqR[i].set(coeffBuf);
        }
        coeffsDirty = false;
    }

    // ---------------------------------------------------------------- 解码线程

    private void runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        MediaExtractor ex = null;
        MediaCodec codec = null;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        short[] out = new short[65536];
        int pendingOff = 0, pendingLen = 0;
        int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
        boolean inputDone = false, outputDone = false;

        try {
            ex = new MediaExtractor();
            ex.setDataSource(appContext, Uri.parse(uriStr), null);

            int trackIndex = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    fmt = f;
                    break;
                }
            }
            if (fmt == null) {
                fail("没有音频轨道");
                return;
            }
            ex.selectTrack(trackIndex);

            if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            }
            if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                long d = fmt.getLong(MediaFormat.KEY_DURATION) / 1000L;
                if (d > 0) durationMs = d;
            }

            final int inChannels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            outChannels = Math.max(1, inChannels);

            String mime = fmt.getString(MediaFormat.KEY_MIME);

            // 先按正常解码器走；audio/raw（WAV/PCM）如果设备没有 raw 解码器，就直读兜底
            boolean usePassthrough = false;
            try {
                codec = MediaCodec.createDecoderByType(mime);
                try {
                    // 统一要 16bit 交错 PCM；解不出来再按输出格式处理
                    fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                } catch (Exception ignored) {
                }
                codec.configure(fmt, null, null, 0);
                codec.start();
            } catch (Exception e) {
                Log.w(TAG, "decoder unavailable: " + mime, e);
                if (codec != null) {
                    try { codec.stop(); } catch (Exception ignored) { }
                    try { codec.release(); } catch (Exception ignored) { }
                    codec = null;
                }
                if (!MediaFormat.MIMETYPE_AUDIO_RAW.equals(mime)) throw e;
                usePassthrough = true;
            }
            if (usePassthrough) {
                runPcmPassthrough(ex, fmt);
                return;
            }

            ensureTrack(sampleRate);
            rebuildFilters();

            while (running) {
                // ---- seek ----
                long seekMs = seekRequestMs;
                if (seekMs >= 0) {
                    seekRequestMs = -1;
                    AudioTrack t = track;
                    if (t != null) {
                        try { t.pause(); t.flush(); } catch (Exception ignored) { }
                    }
                    ex.seekTo(seekMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    codec.flush();
                    long st = ex.getSampleTime();
                    basePositionMs = st > 0 ? st / 1000L : seekMs;
                    writtenFrames = 0;
                    pendingOff = 0;
                    pendingLen = 0;
                    inputDone = false;
                    outputDone = false;
                    trackPlaying = false;
                    continue;
                }

                if (paused) {
                    sleepQuiet(10);
                    continue;
                }

                AudioTrack t = track;
                if (t != null && !trackPlaying) {
                    try { t.play(); trackPlaying = true; } catch (Exception ignored) { }
                }

                // ---- 把上一块没写完的数据写完 ----
                if (pendingLen > 0 && t != null) {
                    int n = t.write(out, pendingOff, pendingLen, AudioTrack.WRITE_NON_BLOCKING);
                    if (n > 0) {
                        pendingOff += n;
                        pendingLen -= n;
                        writtenFrames += n / 2;   // 输出固定双声道
                    } else if (n == 0) {
                        sleepQuiet(2);
                    } else {
                        fail("AudioTrack.write=" + n);
                        return;
                    }
                    if (pendingLen > 0) continue;
                }

                if (outputDone) {
                    drainRemaining();
                    return;
                }

                // ---- 喂输入 ----
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(0);
                    if (inIdx >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIdx);
                        if (in == null) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, 0);
                        } else {
                            int size = ex.readSampleData(in, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                                ex.advance();
                            }
                        }
                    }
                }

                // ---- 取输出 ----
                int outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIdx >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0) {
                        int n = process(ob, info, out, pcmEncoding);
                        pendingOff = 0;
                        pendingLen = n;
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        int sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (sr > 0 && sr != sampleRate) {
                            sampleRate = sr;
                            ensureTrack(sr);
                            rebuildFilters();
                        }
                    }
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        int ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (ch > 0) outChannels = ch;
                    }
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        Integer enc = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                        if (enc != null) pcmEncoding = enc;
                    }
                }
            }
        } catch (Exception e) {
            if (running) {
                Log.w(TAG, "engine failed: " + uriStr, e);
                fail("解码失败: " + e.getClass().getSimpleName());
            }
        } finally {
            releaseTrack();
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                try { codec.release(); } catch (Exception ignored) { }
            }
            if (ex != null) {
                try { ex.release(); } catch (Exception ignored) { }
            }
            running = false;
        }
    }

    /**
     * WAV / PCM 直读兜底：设备没有 raw 解码器时，MediaExtractor 的样本本身就是 PCM，
     * 直接送 DSP，不经过 MediaCodec。
     */
    private void runPcmPassthrough(MediaExtractor ex, MediaFormat fmt) {
        ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        short[] out = new short[65536];
        int enc = AudioFormat.ENCODING_PCM_16BIT;
        if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            Integer e = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING);
            if (e != null) enc = e;
        }
        ensureTrack(sampleRate);
        rebuildFilters();

        int pendingOff = 0, pendingLen = 0;
        try {
            while (running) {
                long seekMs = seekRequestMs;
                if (seekMs >= 0) {
                    seekRequestMs = -1;
                    AudioTrack t = track;
                    if (t != null) {
                        try { t.pause(); t.flush(); } catch (Exception ignored) { }
                    }
                    ex.seekTo(seekMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    long st = ex.getSampleTime();
                    basePositionMs = st > 0 ? st / 1000L : seekMs;
                    writtenFrames = 0;
                    pendingOff = 0;
                    pendingLen = 0;
                    trackPlaying = false;
                    continue;
                }
                if (paused) {
                    sleepQuiet(10);
                    continue;
                }

                AudioTrack t = track;
                if (t != null && !trackPlaying) {
                    try { t.play(); trackPlaying = true; } catch (Exception ignored) { }
                }

                if (pendingLen > 0 && t != null) {
                    int n = t.write(out, pendingOff, pendingLen, AudioTrack.WRITE_NON_BLOCKING);
                    if (n > 0) {
                        pendingOff += n;
                        pendingLen -= n;
                        writtenFrames += n / 2;
                    } else if (n == 0) {
                        sleepQuiet(2);
                    } else {
                        fail("AudioTrack.write=" + n);
                        return;
                    }
                    if (pendingLen > 0) continue;
                }

                int size = ex.readSampleData(buf, 0);
                if (size < 0) {
                    drainRemaining();
                    return;
                }
                info.offset = 0;
                info.size = size;
                info.flags = 0;
                pendingOff = 0;
                pendingLen = process(buf, info, out, enc);
                ex.advance();
            }
        } catch (Exception e) {
            if (running) {
                Log.w(TAG, "passthrough failed", e);
                fail("PCM 播放失败");
            }
        }
    }

    private void fail(String msg) {
        if (!ended) {
            ended = true;
            listener.onError(msg);
        }
    }

    /** EOS：等缓冲区放完再回调 onEnded（最多 8 秒，防止播放头不动时卡死） */
    private void drainRemaining() {
        long deadline = SystemClock.uptimeMillis() + 8000;
        AudioTrack t = track;
        while (running && t != null
                && (((long) t.getPlaybackHeadPosition()) & 0xFFFFFFFFL) < writtenFrames
                && SystemClock.uptimeMillis() < deadline) {
            sleepQuiet(20);
        }
        if (running && !ended) {
            ended = true;
            listener.onEnded();
        }
    }

    private AudioTrack ensureTrack(int sr) {
        AudioTrack t = track;
        if (t != null && trackSampleRate == sr) return t;
        if (t != null) {
            // 解码器报告了新的采样率：还没写过数据，直接换一个 AudioTrack
            releaseTrack();
        }
        int minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf > 0 ? minBuf * 2 : 0, sr / 5 * 4); // ≈200ms
        try {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track = t;
            trackSampleRate = sr;
            t.play();
            trackPlaying = true;
        } catch (Exception e) {
            Log.w(TAG, "AudioTrack create failed @" + sr, e);
            track = null;
            trackSampleRate = 0;
            throw new IllegalStateException("AudioTrack 初始化失败");
        }
        return t;
    }

    private void releaseTrack() {
        AudioTrack t = track;
        track = null;
        trackPlaying = false;
        trackSampleRate = 0;
        if (t != null) {
            try { t.pause(); } catch (Exception ignored) { }
            try { t.flush(); } catch (Exception ignored) { }
            try { t.stop(); } catch (Exception ignored) { }
            try { t.release(); } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- DSP

    /**
     * 把一个解码输出缓冲处理成 16bit 交错立体声，返回写入 out 的 sample 数（=帧数*2）。
     */
    private int process(ByteBuffer ob, MediaCodec.BufferInfo info, short[] out, int encoding) {
        ob.order(ByteOrder.nativeOrder());
        ob.position(info.offset);
        ob.limit(info.offset + info.size);

        if (coeffsDirty) rebuildFilters();

        final boolean isFloat = (encoding == AudioFormat.ENCODING_PCM_FLOAT);
        final int bps = isFloat ? 4 : 2;
        final int ch = Math.max(1, outChannels);
        int frames = info.size / (ch * bps);
        if (frames <= 0) return 0;
        if (frames * 2 > out.length) frames = out.length / 2;

        final float preamp = preampGain;
        final float vol = volume;
        final float bal = balance;
        final float lg = vol * (bal < 0 ? 1f + bal : 1f);
        final float rg = vol * (bal > 0 ? 1f - bal : 1f);
        final boolean doEq = eqEnabled && anyBandActive();
        final Biquad[] L = eqL, R = eqR;
        final boolean[] act = bandActive;

        int o = 0;
        for (int f = 0; f < frames; f++) {
            float l, r;
            if (ch == 1) {
                l = r = readSample(ob, isFloat);
            } else {
                l = readSample(ob, isFloat);
                r = readSample(ob, isFloat);
                for (int c = 2; c < ch; c++) {
                    float extra = readSample(ob, isFloat);
                    // 多声道：剩下的并到左右（简单降混）
                    l += extra * 0.5f;
                    r += extra * 0.5f;
                }
            }
            l *= preamp;
            r *= preamp;
            if (doEq) {
                for (int b = 0; b < BANDS; b++) {
                    if (!act[b]) continue;
                    l = L[b].process(l);
                    r = R[b].process(r);
                }
            }
            out[o++] = clip(l * lg);
            out[o++] = clip(r * rg);

            specRing[specPos & (FFT_N - 1)] = (l + r) * 0.5f;
            specPos++;
        }

        long now = SystemClock.uptimeMillis();
        if (specPos >= FFT_N && now - lastSpecMs >= 40) {
            lastSpecMs = now;
            computeSpectrum();
        }
        return o;
    }

    private boolean anyBandActive() {
        for (int i = 0; i < BANDS; i++) if (bandActive[i]) return true;
        return false;
    }

    private static float readSample(ByteBuffer b, boolean isFloat) {
        return isFloat ? b.getFloat() : (b.getShort() / 32768f);
    }

    private static short clip(float v) {
        if (v > 1f) v = 1f;
        else if (v < -1f) v = -1f;
        return (short) (v * 32767f);
    }

    // ---------------------------------------------------------------- 频谱

    private void computeSpectrum() {
        int mask = FFT_N - 1;
        for (int i = 0; i < FFT_N; i++) {
            int idx = (specPos - FFT_N + i) & mask;
            fftRe[i] = specRing[idx & mask] * hann[i];
            fftIm[i] = 0f;
        }
        // 波形（最近 1024 点，Visualizer 格式：0..255，128 居中）
        for (int i = 0; i < 1024; i++) {
            int idx = (specPos - 1024 + i) & mask;
            float s = specRing[idx & mask];
            int v = (int) (s * 127f) + 128;
            if (v < 0) v = 0;
            if (v > 255) v = 255;
            wave[i] = (byte) v;
        }

        fft(fftRe, fftIm);
        int bins = FFT_N / 2;
        for (int b = 0; b < BARS; b++) {
            int bin0 = 1 + (int) (Math.pow((double) b / BARS, 2.0) * (bins - 2));
            int bin1 = 1 + (int) (Math.pow((double) (b + 1) / BARS, 2.0) * (bins - 2));
            if (bin1 < bin0) bin1 = bin0;
            float mag = 0f;
            for (int i = bin0; i <= bin1; i++) {
                float m = (float) Math.hypot(fftRe[i], fftIm[i]);
                if (m > mag) mag = m;
            }
            float db = (float) (20 * Math.log10(mag + 1));
            float v = (db - 10f) / 32f;
            bars[b] = v < 0 ? 0 : (v > 1 ? 1 : v);
        }
        listener.onSpectrum(bars, wave);
    }

    /** 基 2 原位 FFT */
    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cwr = 1, cwi = 0;
                for (int k = 0; k < len / 2; k++) {
                    float tr = cwr * re[i + k + len / 2] - cwi * im[i + k + len / 2];
                    float ti = cwr * im[i + k + len / 2] + cwi * re[i + k + len / 2];
                    re[i + k + len / 2] = re[i + k] - tr;
                    im[i + k + len / 2] = im[i + k] - ti;
                    re[i + k] += tr;
                    im[i + k] += ti;
                    float nwr = cwr * wr - cwi * wi;
                    cwi = cwr * wi + cwi * wr;
                    cwr = nwr;
                }
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
