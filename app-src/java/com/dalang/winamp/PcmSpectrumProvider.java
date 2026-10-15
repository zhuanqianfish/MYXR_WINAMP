package com.dalang.winamp;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * 本地 PCM 频谱源：后台线程把整首歌解码成单声道 16-bit PCM，
 * 按播放进度做 2048 点 FFT，给频谱柱和示波器供数。
 * 完全不经过系统 Visualizer，在任何手机上都有数据。
 */
public class PcmSpectrumProvider {
    private static final String TAG = "PcmSpectrum";
    public static final int BARS = 28;
    private static final int FFT_N = 2048;

    private volatile short[] pcm; // mono 16-bit
    private volatile int sampleRate;
    private volatile boolean ready = false;
    private volatile boolean cancelled = false;
    private Thread decodeThread;

    // FFT 工作区（只在调用线程用，泵在主线程）
    private final float[] fftRe = new float[FFT_N];
    private final float[] fftIm = new float[FFT_N];
    private final float[] hann = new float[FFT_N];

    public PcmSpectrumProvider() {
        for (int i = 0; i < FFT_N; i++) {
            hann[i] = (float) (0.5 * (1 - Math.cos(2 * Math.PI * i / FFT_N)));
        }
    }

    public boolean isReady() {
        return ready;
    }

    public void load(Context ctx, String uriStr, long durationMs) {
        cancel();
        cancelled = false;
        final Context appCtx = ctx.getApplicationContext();
        decodeThread = new Thread(() -> decode(appCtx, uriStr, durationMs), "pcm-decode");
        decodeThread.start();
    }

    public void cancel() {
        cancelled = true;
        ready = false;
        if (decodeThread != null) {
            try {
                decodeThread.join(600);
            } catch (InterruptedException ignored) {
            }
            decodeThread = null;
        }
        pcm = null;
    }

    public void release() {
        cancel();
    }

    private void decode(Context ctx, String uriStr, long durationMs) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(ctx, Uri.parse(uriStr), null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0 || fmt == null) return;
            ex.selectTrack(track);
            int inCh = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int inSr = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime == null) return;

            int cap = (int) Math.min(Integer.MAX_VALUE - 8,
                    Math.max((long) inSr * 30, durationMs * inSr / 1000 + inSr));
            short[] out = new short[cap];
            int pos = 0;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();
            int outCh = inCh;
            int pcmEnc = 2; // 2 = PCM_16BIT, 4 = PCM_FLOAT

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean eos = false;
            while (!cancelled && !eos) {
                int inIdx = codec.dequeueInputBuffer(8000);
                if (inIdx >= 0) {
                    ByteBuffer in = codec.getInputBuffer(inIdx);
                    if (in != null) {
                        in.clear();
                        int n = ex.readSampleData(in, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 8000);
                if (outIdx >= 0) {
                    ByteBuffer b = codec.getOutputBuffer(outIdx);
                    if (b != null && info.size > 0) {
                        b.order(ByteOrder.nativeOrder());
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        int bytesPerSample = (pcmEnc == 4 ? 4 : 2);
                        int frames = info.size / (outCh * bytesPerSample);
                        for (int f = 0; f < frames; f++) {
                            if (pos >= out.length) {
                                out = Arrays.copyOf(out, out.length * 2);
                            }
                            if (pcmEnc == 4) {
                                float s = 0;
                                for (int c = 0; c < outCh; c++) s += b.getFloat();
                                s /= outCh;
                                s = Math.max(-1f, Math.min(1f, s));
                                out[pos++] = (short) (s * 32767);
                            } else {
                                int acc = 0;
                                for (int c = 0; c < outCh; c++) acc += b.getShort();
                                out[pos++] = (short) (acc / outCh);
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        outCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        inSr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEnc = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                }
            }
            if (!cancelled && pos > FFT_N) {
                sampleRate = inSr;
                pcm = out;
                ready = true;
                Log.i(TAG, "decoded " + pos + " samples @" + inSr + "Hz");
            }
        } catch (Exception e) {
            Log.w(TAG, "decode failed: " + uriStr, e);
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {
            }
            try {
                ex.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** positionMs 处取 28 段频谱（0..1）；无数据返回 false */
    public boolean getBarValues(long positionMs, float[] out) {
        short[] data = pcm;
        int sr = sampleRate;
        if (!ready || data == null || sr <= 0 || out.length != BARS) return false;
        int center = (int) (positionMs * sr / 1000L);
        int start = center - FFT_N / 2;
        for (int i = 0; i < FFT_N; i++) {
            int idx = start + i;
            float s = (idx >= 0 && idx < data.length) ? data[idx] / 32768f : 0f;
            fftRe[i] = s * hann[i];
            fftIm[i] = 0;
        }
        fft(fftRe, fftIm);
        int bins = FFT_N / 2;
        for (int b = 0; b < BARS; b++) {
            int bin0 = 1 + (int) (Math.pow((double) b / BARS, 2.0) * (bins - 2));
            int bin1 = 1 + (int) (Math.pow((double) (b + 1) / BARS, 2.0) * (bins - 2));
            if (bin1 < bin0) bin1 = bin0;
            float mag = 0;
            for (int i = bin0; i <= bin1; i++) {
                float m = (float) Math.hypot(fftRe[i], fftIm[i]);
                if (m > mag) mag = m;
            }
            float db = (float) (20 * Math.log10(mag + 1));
            out[b] = Math.max(0f, Math.min(1f, (db - 10) / 32));
        }
        return true;
    }

    /** positionMs 处取 1024 点波形（Visualizer 格式：0..255，128 居中） */
    public boolean getWaveBytes(long positionMs, byte[] out) {
        short[] data = pcm;
        int sr = sampleRate;
        if (!ready || data == null || sr <= 0 || out.length != 1024) return false;
        int center = (int) (positionMs * sr / 1000L);
        int start = center - 512;
        for (int i = 0; i < 1024; i++) {
            int idx = start + i;
            short s = (idx >= 0 && idx < data.length) ? data[idx] : 0;
            out[i] = (byte) (((s >> 8) + 128) & 0xFF);
        }
        return true;
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
}
