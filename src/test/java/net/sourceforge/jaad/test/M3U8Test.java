package net.sourceforge.jaad.test;

import net.sourceforge.jaad.m3u8.M3U8InputStream;
import net.sourceforge.jaad.spi.javasound.TSAudioFileReader;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

public class M3U8Test {
    private static final Duration M3U8_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TS_TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT = """
            Mozilla/5.0 (Windows NT 10.0; Win64; x64) \
            AppleWebKit/537.36 (KHTML, like Gecko) \
            Chrome/147.0.0.0 Safari/537.36
            """.trim();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5)).build();

    public static void main(String[] args) throws Exception {
        URI baseUri = URI.create("https://ngcdn001.cnr.cn/live/zgzs/index.m3u8");
        BufferedInputStream bis = getBufferedInputStream(baseUri);

        // 获取标准音频流
        AudioInputStream ais = new TSAudioFileReader().getAudioInputStream(bis);
        AudioFormat format = ais.getFormat();

        // 申请系统声卡并打开
        SourceDataLine line = AudioSystem.getSourceDataLine(format);
        line.open(format);
        line.start();

        // 核心流式播放 (边下、边解、边播)
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = ais.read(buffer)) != -1) {
            // 这行代码自带阻塞和流控机制
            line.write(buffer, 0, bytesRead);
        }

        // 扫尾清理
        line.drain();
        line.close();
        ais.close();
    }

    private static BufferedInputStream getBufferedInputStream(URI baseUri) {
        Supplier<HttpRequest> playlistRequest = () -> HttpRequest.newBuilder(baseUri)
                .timeout(M3U8_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET().build();

        Function<URI, HttpRequest> tsSegmentRequest = tsUri -> HttpRequest.newBuilder(tsUri)
                .timeout(TS_TIMEOUT).header("User-Agent", USER_AGENT)
                .GET().build();

        // 获取 M3U8 网络流，并套上 5MB 缓冲 (为了支持格式嗅探)
        M3U8InputStream inputStream = new M3U8InputStream(CLIENT, playlistRequest, tsSegmentRequest);
        return new BufferedInputStream(inputStream, 5 * 1024 * 1024);
    }
}
