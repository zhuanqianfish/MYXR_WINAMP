package com.dalang.winamp;

/** 播放列表中的一首曲目 */
public class Track {
    public String uri;        // SAF uri 字符串
    public String title;      // 标题
    public String artist;     // 艺术家
    public long durationMs;   // 时长毫秒

    public Track() {}

    public Track(String uri, String title, String artist, long durationMs) {
        this.uri = uri;
        this.title = title;
        this.artist = artist;
        this.durationMs = durationMs;
    }
}
