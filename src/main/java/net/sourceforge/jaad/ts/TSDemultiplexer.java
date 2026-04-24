package net.sourceforge.jaad.ts;

import net.sourceforge.jaad.aac.AudioDecoderInfo;
import net.sourceforge.jaad.adts.ADTSDemultiplexer;

import java.io.IOException;
import java.io.InputStream;

/**
 * TS 解封装器。
 * 通过剥离 TS 容器的 Header 和 PES Header，提取出纯净的 ADTS 字节流，
 * 然后复用 JAAD 原生的 ADTSDemultiplexer 进行帧解析。
 */
public class TSDemultiplexer {
    private final ADTSDemultiplexer internalAdts;

    public TSDemultiplexer(InputStream in) throws IOException {
        TSToADTSInputStream tsAudioStream = new TSToADTSInputStream(in);
        internalAdts = new ADTSDemultiplexer(tsAudioStream);
    }

    public byte[] readNextFrame() throws IOException {
        return internalAdts.readNextFrame();
    }

    public int skipNextFrame() throws IOException {
        return internalAdts.skipNextFrame();
    }

    public int getSampleFrequency() {
        return internalAdts.getSampleFrequency();
    }

    public int getChannelCount() {
        return internalAdts.getChannelCount();
    }

    public AudioDecoderInfo getDecoderInfo() {
        return internalAdts.getDecoderInfo();
    }
}