package com.dalang.winamp;

/** 播放列表中的一首曲目（音频或视频容器，MP4/MKV/WEBM 等均可） */
public class Track {
    public String uri;        // SAF uri 字符串
    public String title;      // 标题
    public String artist;     // 艺术家
    public long durationMs;   // 时长毫秒

    /** 容器里是否有视频轨（MP4/MKV/WEBM 可能带画面） */
    public boolean hasVideo;
    public int videoWidth;
    public int videoHeight;

    public Track() {}

    public Track(String uri, String title, String artist, long durationMs) {
        this.uri = uri;
        this.title = title;
        this.artist = artist;
        this.durationMs = durationMs;
    }

    /** 播放列表 / 通知栏里显示的一行标题 */
    public String display() {
        return artist + " - " + title;
    }
}
