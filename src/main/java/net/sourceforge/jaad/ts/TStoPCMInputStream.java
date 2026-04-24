package net.sourceforge.jaad.ts;

import net.sourceforge.jaad.SampleBuffer;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.adts.ADTSDemultiplexer;

import java.io.IOException;
import java.io.InputStream;

public class TStoPCMInputStream extends InputStream {
    private final ADTSDemultiplexer demux;
    private final Decoder decoder;
    private final SampleBuffer buf;

    // 用来缓存解码出的一帧 PCM 数据
    private byte[] currentPcmData;
    private int pcmPosition = 0;
    private int pcmLimit = 0;

    public TStoPCMInputStream(InputStream in) throws IOException {
        this.demux = new ADTSDemultiplexer(new TSToADTSInputStream(in));
        this.decoder = Decoder.create(demux.getDecoderInfo());
        this.buf = new SampleBuffer();

        // 预读第一帧以获取格式信息
        decodeNextFrame();
    }

    // 核心逻辑：如果缓存空了，就解码下一帧；否则从缓存取数据
    private boolean decodeNextFrame() throws IOException {
        try {
            byte[] aacFrame = demux.readNextFrame();
            decoder.decodeFrame(aacFrame, buf);
            currentPcmData = buf.getData();
            pcmLimit = currentPcmData.length;
            pcmPosition = 0;
            return true;
        } catch (Exception e) {
            return false; // 文件末尾或解码出错
        }
    }

    @Override
    public int read() throws IOException {
        if (pcmPosition >= pcmLimit) {
            if (!decodeNextFrame()) {
                return -1;
            }
        }
        return currentPcmData[pcmPosition++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (pcmPosition >= pcmLimit) {
            if (!decodeNextFrame()) {
                return -1;
            }
        }
        int available = pcmLimit - pcmPosition;
        int toCopy = Math.min(available, len);
        System.arraycopy(currentPcmData, pcmPosition, b, off, toCopy);
        pcmPosition += toCopy;
        return toCopy;
    }

    // 提供给上层获取音频格式
    public SampleBuffer getSampleBuffer() {
        return buf;
    }
}
