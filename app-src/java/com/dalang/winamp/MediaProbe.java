package com.dalang.winamp;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 媒体文件探测：曲目信息、视频轨信息、封面图。
 *
 * 支持所有能被 MediaExtractor 打开、且至少有一条音频轨的容器：
 * wav / mp3 / flac / ogg / m4a / **mp4 / mkv / webm / 3gp / ts** ……
 * MP4 这类带画面的容器会被标记 hasVideo，交给 VideoWindowView 渲染画面，
 * 音频仍然走 PcmAudioEngine（这样均衡器和频谱对视频同样有效）。
 */
public final class MediaProbe {

    private static final String TAG = "MediaProbe";

    /** 内置默认歌曲（assets） */
    public static final String DEFAULT_SONG_ASSET = "wenroutaozi_Llama.wav";
    public static final String DEFAULT_SONG_TITLE = "wenroutaozi_Llama";
    public static final String DEFAULT_SONG_ARTIST = "温柔桃子摇滚版";
    /** 内置歌曲版本：换歌或修文件时递增，老用户升级会自动覆盖 files 目录里的旧文件 */
    public static final int DEFAULT_SONG_VERSION = 2;

    private MediaProbe() {}

    /**
     * 探测一个 URI。返回 null 表示这个文件没有音频轨（无法播放）。
     */
    public static Track probe(Context context, Uri uri) {
        Track t = new Track();
        t.uri = uri.toString();
        boolean hasAudio = false;

        MediaExtractor ex = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(context, uri, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("audio/")) {
                    hasAudio = true;
                    if (t.durationMs <= 0 && f.containsKey(MediaFormat.KEY_DURATION)) {
                        long d = f.getLong(MediaFormat.KEY_DURATION) / 1000L;
                        if (d > 0) t.durationMs = d;
                    }
                } else if (mime.startsWith("video/") && !t.hasVideo) {
                    t.hasVideo = true;
                    if (f.containsKey(MediaFormat.KEY_WIDTH)) {
                        t.videoWidth = f.getInteger(MediaFormat.KEY_WIDTH);
                    }
                    if (f.containsKey(MediaFormat.KEY_HEIGHT)) {
                        t.videoHeight = f.getInteger(MediaFormat.KEY_HEIGHT);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "probe tracks failed: " + uri, e);
        } finally {
            if (ex != null) try { ex.release(); } catch (Exception ignored) { }
        }

        if (!hasAudio) return null;

        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, uri);
            t.title = nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            t.artist = nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            if (t.durationMs <= 0) {
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) {
                    try { t.durationMs = Long.parseLong(d); } catch (NumberFormatException ignored) { }
                }
            }
            if (!t.hasVideo) {
                t.hasVideo = "yes".equalsIgnoreCase(
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
            }
        } catch (Exception e) {
            Log.w(TAG, "probe tags failed: " + uri, e);
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }

        if (t.title == null) {
            t.title = displayName(context, uri);
            t.artist = "未知艺术家";
        }
        if (t.artist == null) t.artist = "未知艺术家";
        return t;
    }

    /**
     * 封面图：优先内嵌专辑封面，MP4 这类没有封面的取第一帧画面。
     * 返回的 Bitmap 已经缩到 maxPx 以内，可以直接塞进通知/锁屏。
     */
    public static Bitmap artwork(Context context, Uri uri, int maxPx) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, uri);
            byte[] pic = null;
            try { pic = r.getEmbeddedPicture(); } catch (Exception ignored) { }
            if (pic != null && pic.length > 0) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                o.inSampleSize = sampleSize(o.outWidth, o.outHeight, maxPx);
                o.inJustDecodeBounds = false;
                Bitmap b = BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                if (b != null) return b;
            }
            // 视频：抓一帧当封面
            Bitmap frame = null;
            try {
                frame = r.getFrameAtTime(1_000_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Exception ignored) { }
            if (frame == null) {
                try { frame = r.getFrameAtTime(0); } catch (Exception ignored) { }
            }
            if (frame != null) {
                return scaleDown(frame, maxPx);
            }
        } catch (Exception e) {
            Log.w(TAG, "artwork failed: " + uri, e);
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        return null;
    }

    private static Bitmap scaleDown(Bitmap src, int maxPx) {
        int w = src.getWidth(), h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= maxPx) return src;
        float k = maxPx / (float) max;
        Bitmap out = Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
        if (out != src) src.recycle();
        return out;
    }

    private static int sampleSize(int w, int h, int maxPx) {
        int s = 1;
        int max = Math.max(w, h);
        while (max / s > maxPx * 2) s *= 2;
        return s;
    }

    private static String nz(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    public static String displayName(Context context, Uri uri) {
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

    /** 拷贝 assets 内置歌曲到 files 目录并生成曲目；失败返回 null（静默跳过） */
    public static Track installDefaultSong(Context context) {
        try {
            File dst = new File(context.getFilesDir(), DEFAULT_SONG_ASSET);
            if (dst.exists()) dst.delete(); // 始终覆盖，保证老用户升级时坏文件被替换
            InputStream in = context.getAssets().open(DEFAULT_SONG_ASSET);
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
            Track t = probe(context, Uri.fromFile(dst));
            if (t == null) return null;
            t.title = DEFAULT_SONG_TITLE;
            t.artist = DEFAULT_SONG_ARTIST;
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    /** 内置歌曲标题/艺术家固定显示，不受元数据探测影响（含后续启动从持久化列表恢复时） */
    public static void fixDefaultSongMeta(Track t) {
        if (t != null && t.uri != null && t.uri.endsWith("/" + DEFAULT_SONG_ASSET)) {
            t.title = DEFAULT_SONG_TITLE;
            t.artist = DEFAULT_SONG_ARTIST;
        }
    }
}
