package net.sourceforge.jaad.spi.javasound;

import net.sourceforge.jaad.SampleBuffer;
import net.sourceforge.jaad.ts.TStoPCMInputStream;

import javax.sound.sampled.*;
import javax.sound.sampled.spi.AudioFileReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;

public class TSAudioFileReader extends AudioFileReader {
    public static final AudioFileFormat.Type TS = new AudioFileFormat.Type("MPEG-TS", "ts");

    private static final int TS_PACKET_SIZE = 188;
    private static final int SYNC_BYTE = 0x47;
    private static final int MIN_SYNC_COUNT = 3;

    // 设置 5MB 的探测缓冲区（绝大多数带封面图的 ID3 不会超过这个大小）
    private static final int PROBE_LIMIT = 5 * 1024 * 1024;

    @Override
    public AudioFileFormat getAudioFileFormat(InputStream stream) throws UnsupportedAudioFileException, IOException {
        if (!stream.markSupported()) {
            stream = new BufferedInputStream(stream);
        }

        // 1. 严格嗅探文件头特征
        if (!isMpegTs(stream)) {
            throw new UnsupportedAudioFileException("Not a valid MPEG-TS stream.");
        }

        // 2. 嗅探通过，尝试读取第一帧以获取音频采样率和通道数
        // 留出 100KB 的 mark 空间，保证能剥离出第一个 AAC 帧
        stream.mark(100 * 1024);
        try {
            TStoPCMInputStream pcmStream = new TStoPCMInputStream(stream);
            SampleBuffer buf = pcmStream.getSampleBuffer();

            AudioFormat format = new AudioFormat(
                    buf.getSampleRate(), buf.getBitsPerSample(), buf.getChannels(),
                    true, buf.isBigEndian()
            );

            // 返回完整的格式信息
            return new AudioFileFormat(TS, format, AudioSystem.NOT_SPECIFIED);
        } catch (Exception e) {
            throw new UnsupportedAudioFileException("Found TS stream, but no valid AAC audio could be decoded.");
        } finally {
            // 解析完第一帧特征后，必须把指针还给系统，保证后续能从头播放
            stream.reset();
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(InputStream stream) throws UnsupportedAudioFileException, IOException {
        if (!stream.markSupported()) {
            stream = new BufferedInputStream(stream);
        }

        // 委托给 getAudioFileFormat 执行探测
        AudioFileFormat format = getAudioFileFormat(stream);

        // 如果上面没抛出异常，说明确认是合法的 TS 且可解码，直接封装并返回
        TStoPCMInputStream pcmStream = new TStoPCMInputStream(stream);
        return new AudioInputStream(pcmStream, format.getFormat(), AudioSystem.NOT_SPECIFIED);
    }

    /**
     * 核心特征值严格嗅探：
     * 只跳过开头的 ID3，然后严格验证紧接着的 3 个包头是否为 0x47。
     */
    private boolean isMpegTs(InputStream stream) throws IOException {
        stream.mark(PROBE_LIMIT);
        try {
            long totalId3Size = 0;

            // 1. 只算不跳：计算出所有相连的 ID3 标签总大小
            while (true) {
                byte[] header = new byte[10];
                int readBytes = 0;
                while (readBytes < 10) {
                    int n = stream.read(header, readBytes, 10 - readBytes);
                    if (n == -1) {
                        break;
                    }
                    readBytes += n;
                }

                // 验证 ID3 魔数及 Synchsafe Integer 规范
                if (readBytes == 10 &&
                    header[0] == 0x49 && header[1] == 0x44 && header[2] == 0x33 &&
                    (header[6] & 0x80) == 0 && (header[7] & 0x80) == 0 &&
                    (header[8] & 0x80) == 0 && (header[9] & 0x80) == 0) {

                    int payloadSize = ((header[6] & 0x7F) << 21) |
                                      ((header[7] & 0x7F) << 14) |
                                      ((header[8] & 0x7F) << 7) |
                                      (header[9] & 0x7F);

                    long skipped = 0;
                    while (skipped < payloadSize) {
                        long s = stream.skip(payloadSize - skipped);
                        if (s <= 0) {
                            break;
                        }
                        skipped += s;
                    }
                    totalId3Size += (10 + payloadSize);
                } else {
                    break; // 没有更多 ID3 了
                }
            }

            // 2. 关键点：统一回退到文件最开头
            stream.reset();

            // 3. 一次性跳过上面计算出的 ID3 总长度
            long id3Skipped = 0;
            while (id3Skipped < totalId3Size) {
                long s = stream.skip(totalId3Size - id3Skipped);
                if (s <= 0) {
                    return false;
                }
                id3Skipped += s;
            }

            // 4. 严格验证：此刻游标应该正正好好踩在第一个 TS 包的 0x47 上
            for (int i = 0; i < MIN_SYNC_COUNT; i++) {
                int b = stream.read();
                if (b != SYNC_BYTE) {
                    // 如果跨过 ID3 后的第一个字节不是 0x47，直接判为非法格式
                    return false;
                }

                // 如果不是最后一次验证，跳过包体剩下的 187 字节
                if (i < MIN_SYNC_COUNT - 1) {
                    long packetSkipped = 0;
                    int toSkip = TS_PACKET_SIZE - 1;
                    while (packetSkipped < toSkip) {
                        long s = stream.skip(toSkip - packetSkipped);
                        if (s <= 0) {
                            // 遇到意外截断
                            return false;
                        }
                        packetSkipped += s;
                    }
                }
            }

            // 完美匹配！
            return true;
        } finally {
            // 无论探测成功与否，SPI 插件都必须把 InputStream 恢复原状
            stream.reset();
        }
    }

    @Override
    public AudioFileFormat getAudioFileFormat(File file) throws UnsupportedAudioFileException, IOException {
        try (InputStream is = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            return getAudioFileFormat(is);
        }
    }

    @Override
    public AudioFileFormat getAudioFileFormat(URL url) throws UnsupportedAudioFileException, IOException {
        try (InputStream is = new BufferedInputStream(url.openStream())) {
            return getAudioFileFormat(is);
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(File file) throws UnsupportedAudioFileException, IOException {
        return getAudioInputStream(new BufferedInputStream(Files.newInputStream(file.toPath())));
    }

    @Override
    public AudioInputStream getAudioInputStream(URL url) throws UnsupportedAudioFileException, IOException {
        return getAudioInputStream(new BufferedInputStream(url.openStream()));
    }
}