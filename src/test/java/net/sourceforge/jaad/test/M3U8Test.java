package net.sourceforge.jaad.test;

import net.sourceforge.jaad.m3u8.M3U8InputStream;
import net.sourceforge.jaad.spi.javasound.TSAudioFileReader;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;

public class M3U8Test {
    public static void main(String[] args) throws Exception {
        String url = "https://ngcdn001.cnr.cn/live/zgzs/index.m3u8";

        // 获取 M3U8 网络流，并套上 5MB 缓冲 (为了支持格式嗅探)
        BufferedInputStream bis = new BufferedInputStream(new M3U8InputStream(url), 5 * 1024 * 1024);

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
}
