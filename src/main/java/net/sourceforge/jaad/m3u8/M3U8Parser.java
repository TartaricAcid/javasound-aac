package net.sourceforge.jaad.m3u8;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class M3U8Parser {
    private static final Duration M3U8_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TS_TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT = """
            Mozilla/5.0 (Windows NT 10.0; Win64; x64) \
            AppleWebKit/537.36 (KHTML, like Gecko) \
            Chrome/147.0.0.0 Safari/537.36
            """.trim();

    public static M3U8Playlist fetchPlaylist(HttpClient client, String urlString) throws IOException, InterruptedException {
        URI baseUri = URI.create(urlString);
        HttpRequest request = HttpRequest.newBuilder(baseUri)
                .timeout(M3U8_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET().build();

        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch M3U8, HTTP code: " + response.statusCode());
        }

        int targetDurationSec = 5;
        long mediaSequence = 0;
        boolean isLive = true;
        boolean isM3U8 = false;

        // 标记下一行是否为子播放列表（Master Playlist 特有），如果是则直接递归解析子列表
        boolean expectChildPlaylist = false;
        List<String> tsUrls = new ArrayList<>();

        // 一次性拿取所有行
        List<String> lines = response.body().lines().toList();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("#EXTM3U")) {
                isM3U8 = true;
            } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                targetDurationSec = Integer.parseInt(line.split(":")[1].trim());
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = Long.parseLong(line.split(":")[1].trim());
            } else if (line.startsWith("#EXT-X-ENDLIST")) {
                isLive = false;
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                // 遇到多码率主列表标签，说明下一行是非 # 开头的 m3u8 地址
                expectChildPlaylist = true;
            } else if (!line.startsWith("#")) {
                // 处理相对路径，将其转为绝对路径
                String resolvedUrl = baseUri.resolve(line).toString();
                if (expectChildPlaylist) {
                    // 如果发现这是个 Master Playlist，直接递归解析真实的子列表
                    return fetchPlaylist(client, resolvedUrl);
                } else {
                    // 普通的 TS 分片
                    tsUrls.add(resolvedUrl);
                }
            }
        }

        if (!isM3U8) {
            throw new IOException("Invalid M3U8 format: Missing #EXTM3U header in " + urlString);
        }

        return new M3U8Playlist(targetDurationSec, isLive, mediaSequence, tsUrls);
    }

    public static InputStream fetchSegment(HttpClient client, String tsUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(tsUrl))
                .timeout(TS_TIMEOUT).header("User-Agent", USER_AGENT)
                .GET().build();
        // 直接将整个 TS 分片下载为 byte[] 数组
        // 一个 TS 通常只有几百 KB 到 2MB，完全在堆内存可控范围内
        HttpResponse<byte[]> response = client.send(request, BodyHandlers.ofByteArray());
        if (response.statusCode() == 200) {
            return new ByteArrayInputStream(response.body());
        }
        // 抛出异常
        throw new IOException("Failed to fetch TS segment, HTTP code: " + response.statusCode() + ", URL: " + tsUrl);
    }
}
