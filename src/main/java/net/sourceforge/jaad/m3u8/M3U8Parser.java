package net.sourceforge.jaad.m3u8;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public class M3U8Parser {
    public static M3U8Playlist fetchPlaylist(HttpClient client, Supplier<HttpRequest> request) throws IOException, InterruptedException {
        HttpRequest httpRequest = request.get();
        HttpResponse<String> response = client.send(httpRequest, BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch M3U8, HTTP code: " + response.statusCode());
        }

        int targetDurationSec = 5;
        long mediaSequence = 0;
        boolean isLive = true;
        boolean isM3U8 = false;

        // 标记下一行是否为子播放列表（Master Playlist 特有），如果是则直接递归解析子列表
        boolean expectChildPlaylist = false;
        List<URI> tsUrls = new ArrayList<>();

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
                URI resolvedUrl = httpRequest.uri().resolve(line);
                if (expectChildPlaylist) {
                    // 如果发现这是个 Master Playlist，直接递归解析真实的子列表
                    return fetchPlaylist(client, request);
                } else {
                    // 普通的 TS 分片
                    tsUrls.add(resolvedUrl);
                }
            }
        }

        if (!isM3U8) {
            throw new IOException("Invalid M3U8 format: Missing #EXTM3U header in " + httpRequest.uri());
        }

        return new M3U8Playlist(targetDurationSec, isLive, mediaSequence, tsUrls);
    }

    public static InputStream fetchSegment(HttpClient client, URI tsUri, Function<URI, HttpRequest> tsSegmentRequest) throws IOException, InterruptedException {
        HttpRequest request = tsSegmentRequest.apply(tsUri);
        // 直接将整个 TS 分片下载为 byte[] 数组
        // 一个 TS 通常只有几百 KB 到 2MB，完全在堆内存可控范围内
        HttpResponse<byte[]> response = client.send(request, BodyHandlers.ofByteArray());
        if (response.statusCode() == 200) {
            return new ByteArrayInputStream(response.body());
        }
        // 抛出异常
        throw new IOException("Failed to fetch TS segment, HTTP code: " + response.statusCode() + ", URI: " + tsUri);
    }
}
