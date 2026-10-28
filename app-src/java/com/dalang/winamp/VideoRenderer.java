package com.dalang.winamp;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * 视频轨渲染线程：MediaExtractor → MediaCodec → Surface（TextureView）。
 *
 * 画面不自己计时，而是跟着音频播放头走（{@link Clock}）：
 *   - 帧的 pts 比音频超前就等，落后就直接放（丢帧）；
 *   - 用户拖动进度导致偏差超过 1.5s 时，flush 解码器并按音频位置重新 seek。
 * 这样即使解码器比音频快/慢，画面和声音也不会飘。
 */
public class VideoRenderer {

    private static final String TAG = "VideoRenderer";

    public interface Clock {
        long positionMs();
        boolean isPlaying();
    }

    public interface Listener {
        void onFirstFrame(int width, int height, int rotationDeg);
        void onEnded();
        void onError(String message);
    }

    private static final long MAX_WAIT_MS = 300;
    private static final long AHEAD_US = 40_000;

    private final Context ctx;
    private final String uri;
    private final Surface surface;
    private final Clock clock;
    private final Listener listener;

    private volatile boolean running = true;
    private Thread worker;
    private boolean frameReported = false;

    public VideoRenderer(Context c, String uri, Surface surface, Clock clock, Listener l) {
        this.ctx = c.getApplicationContext();
        this.uri = uri;
        this.surface = surface;
        this.clock = clock;
        this.listener = l;
    }

    public void start() {
        worker = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "video-decoder");
        worker.start();
    }

    /** 必须在 Surface 销毁前调用（内部会 join 解码线程，避免向已释放的 Surface 写帧） */
    public void release() {
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) {
            try { w.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void loop() {
        MediaExtractor ex = null;
        MediaCodec codec = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(ctx, Uri.parse(uri), null);

            int trackIndex = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    trackIndex = i;
                    fmt = f;
                    break;
                }
            }
            if (fmt == null) {
                listener.onError("无视频轨");
                return;
            }
            ex.selectTrack(trackIndex);

            int rotation = 0;
            if (fmt.containsKey(MediaFormat.KEY_ROTATION)) {
                try { rotation = fmt.getInteger(MediaFormat.KEY_ROTATION); } catch (Exception ignored) { }
            }

            String mime = fmt.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, surface, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            long lastAlignAt = 0;
            int curW = fmt.containsKey(MediaFormat.KEY_WIDTH)
                    ? fmt.getInteger(MediaFormat.KEY_WIDTH) : 0;
            int curH = fmt.containsKey(MediaFormat.KEY_HEIGHT)
                    ? fmt.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            int curRot = rotation;

            while (running && !outputDone) {
                if (!clock.isPlaying()) {
                    // 暂停：不解码，画面停在最后一帧
                    sleepQuiet(30);
                    continue;
                }

                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(2000);
                    if (inIdx >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIdx);
                        int size = in == null ? -1 : ex.readSampleData(in, 0);
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

                int outIdx = codec.dequeueOutputBuffer(info, 2000);
                if (outIdx >= 0) {
                    long ptsUs = info.presentationTimeUs;
                    long audioUs = clock.positionMs() * 1000L;

                    // 拖动进度 / 解码器与音频错位太远：重新对齐
                    // （1 秒内只对齐一次，避免关键帧落点不理想时反复 flush 空转）
                    if (Math.abs(ptsUs - audioUs) > 1_500_000L
                            && SystemClock.uptimeMillis() - lastAlignAt > 1000) {
                        boolean wasConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        codec.releaseOutputBuffer(outIdx, false);
                        if (!wasConfig) {
                            lastAlignAt = SystemClock.uptimeMillis();
                            ex.seekTo(audioUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            codec.flush();
                            inputDone = false;
                        }
                        continue;
                    }

                    // 画面超前音频：等一下再送显（最多 300ms，避免卡死）
                    long waited = 0;
                    while (running && clock.isPlaying()
                            && ptsUs > clock.positionMs() * 1000L + AHEAD_US
                            && waited < MAX_WAIT_MS) {
                        sleepQuiet(10);
                        waited += 10;
                    }

                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    codec.releaseOutputBuffer(outIdx, !config);

                    if (!config && !frameReported) {
                        frameReported = true;
                        listener.onFirstFrame(curW, curH, curRot);
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_WIDTH)) {
                        curW = of.getInteger(MediaFormat.KEY_WIDTH);
                    }
                    if (of.containsKey(MediaFormat.KEY_HEIGHT)) {
                        curH = of.getInteger(MediaFormat.KEY_HEIGHT);
                    }
                    final int w = curW, h = curH, r = curRot;
                    if (!frameReported) {
                        frameReported = true;
                        listener.onFirstFrame(w, h, r);
                    }
                }
            }
            listener.onEnded();
        } catch (Exception e) {
            if (running) {
                Log.w(TAG, "video decode failed: " + uri, e);
                listener.onError(e.getClass().getSimpleName());
            }
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                try { codec.release(); } catch (Exception ignored) { }
            }
            if (ex != null) {
                try { ex.release(); } catch (Exception ignored) { }
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
