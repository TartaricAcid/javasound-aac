package net.sourceforge.jaad.m3u8;

import java.util.List;

public class M3U8Playlist {
    /**
     * 目标刷新时间 (毫秒)
     * <p>
     * HLS 协议里是 TARGET DURATION 的值，表示每个 TS 分片的最大时长 <br>
     * 播放器可以根据这个值来决定多久刷新一次 M3U8 播放列表。
     */
    private final int targetDurationMs;
    /**
     * 是否为直播流 (没有 #EXT-X-ENDLIST)
     */
    private final boolean isLive;
    /**
     * 媒体序列号 (直播流防重排用)
     */
    private final long mediaSequence;
    /**
     * 提取出来的 TS 分片真实下载地址
     */
    private final List<String> tsUrls;

    public M3U8Playlist(int targetDurationSec, boolean isLive, long mediaSequence, List<String> tsUrls) {
        // HLS 协议里的 TARGET DURATION 是秒，我们转成毫秒方便线程 sleep
        this.targetDurationMs = targetDurationSec > 0 ? targetDurationSec * 1000 : 5000;
        this.isLive = isLive;
        this.mediaSequence = mediaSequence;
        this.tsUrls = (tsUrls == null) ? List.of() : List.copyOf(tsUrls);
    }

    public int getTargetDurationMs() {
        return targetDurationMs;
    }

    public boolean isLive() {
        return isLive;
    }

    public long getMediaSequence() {
        return mediaSequence;
    }

    public List<String> getNewTsUrls() {
        return tsUrls;
    }
}
