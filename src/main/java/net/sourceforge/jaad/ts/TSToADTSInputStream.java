package net.sourceforge.jaad.ts;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

public class TSToADTSInputStream extends InputStream {
    private static final int TS_PACKET_SIZE = 188;
    private static final int SYNC_BYTE = 0x47;
    /**
     * 连续验证 3 个包头
     */
    private static final int CHECK_PACKETS_COUNT = 3;

    private final InputStream in;
    private final byte[] payloadBuffer = new byte[TS_PACKET_SIZE];

    /**
     * -1 表示尚未锁定音频 PID
     */
    private int audioPid = -1;
    private int payloadPos = 0;
    private int payloadLimit = 0;

    // 同步状态标志机
    private boolean isSynced = false;

    public TSToADTSInputStream(InputStream in) {
        this.in = in.markSupported() ? in : new BufferedInputStream(in);
    }

    @Override
    public int read() throws IOException {
        // 如果当前缓存里的有效数据读完了，就去解析下一个 TS 包
        while (payloadPos >= payloadLimit) {
            if (!readNextTSPacket()) {
                // 达到文件末尾或流中断
                return -1;
            }
        }
        return payloadBuffer[payloadPos++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (payloadPos >= payloadLimit) {
            if (!readNextTSPacket()) {
                return -1;
            }
        }
        int available = payloadLimit - payloadPos;
        int toCopy = Math.min(available, len);
        System.arraycopy(payloadBuffer, payloadPos, b, off, toCopy);
        payloadPos += toCopy;
        return toCopy;
    }

    /**
     * 每次读取 188 字节，过滤出音频载荷放进 buffer
     *
     * @return 是否成功读取
     */
    private boolean readNextTSPacket() throws IOException {
        // 如果没有同步（初始状态或发生丢包错位）
        if (!isSynced) {
            // 跳过流开头的 ID3 标签（如果有的话）
            skipID3Tags();

            // 启动严谨的 3 包寻轨逻辑，锁定真正的 0x47
            if (!resync()) {
                return false;
            }

            isSynced = true;
        }

        byte[] packet = new byte[TS_PACKET_SIZE];
        int read = 0;

        // 读取完整的 188 字节
        while (read < TS_PACKET_SIZE) {
            int n = in.read(packet, read, TS_PACKET_SIZE - read);
            if (n == -1) {
                // EOF
                return false;
            }
            read += n;
        }

        // 进行中间读取时，发现头字节突然不是 0x47 了（网络丢包导致的瞬间错位）
        if ((packet[0] & 0xFF) != SYNC_BYTE) {
            // 数据直接作废
            isSynced = false;
            // 返回 true，让外层的 read() 循环再次调用 readNextTSPacket()
            // 从而在下一轮循环中自动触发上面的 skipID3Tags 和 resync 进行重新寻轨
            return true;
        }

        // 解析 TS 头基础信息
        int pid = ((packet[1] & 0x1F) << 8) | (packet[2] & 0xFF);
        // Payload Unit Start Indicator
        boolean pusi = (packet[1] & 0x40) != 0;
        // 控制位
        int adaptation = (packet[3] & 0x30) >> 4;

        if (adaptation == 0 || adaptation == 2) {
            // 仅有自适应区，没有有效载荷，直接跳过
            return true;
        }

        int payloadStart = 4;
        if (adaptation == 3) {
            // 跳过自适应区
            payloadStart += 1 + (packet[4] & 0xFF);
        }

        if (payloadStart >= TS_PACKET_SIZE) {
            // 数据异常，跳过
            return true;
        }

        // 自动侦测音频 PID (通过寻找 PES 头特征：00 00 01 C0~DF)
        if (audioPid == -1 && pusi && (payloadStart + 3 < TS_PACKET_SIZE)) {
            if ((packet[payloadStart] & 0xFF) == 0x00 &&
                (packet[payloadStart + 1] & 0xFF) == 0x00 &&
                (packet[payloadStart + 2] & 0xFF) == 0x01) {
                int streamId = packet[payloadStart + 3] & 0xFF;
                // MPEG 音频流 ID 范围
                if (0xC0 <= streamId && streamId <= 0xDF) {
                    audioPid = pid;
                }
            }
        }

        // 如果不是我们要找的音频流，直接丢弃
        if (pid != audioPid || audioPid == -1) {
            return true;
        }

        // 如果是新的一帧 (PUSI = 1)，需要剥离 PES 头部
        if (pusi) {
            // PES 头结构：起始码(4) + 长度(2) + 标志位(2) + 头数据长度(1) + 头数据
            if (payloadStart + 8 < TS_PACKET_SIZE) {
                int pesHeaderDataLen = packet[payloadStart + 8] & 0xFF;
                payloadStart += 9 + pesHeaderDataLen;
            }
        }

        if (payloadStart >= TS_PACKET_SIZE) {
            return true;
        }

        // 将纯净的 ADTS 数据放入缓冲，等待读取
        int len = TS_PACKET_SIZE - payloadStart;
        System.arraycopy(packet, payloadStart, payloadBuffer, 0, len);
        payloadLimit = len;
        payloadPos = 0;
        return true;
    }

    /**
     * 高效跳过流开头的 ID3v2 标签
     * ID3 头结构：3字节 "ID3" + 2字节版本号 + 1字节标志位 + 4字节长度
     */
    private void skipID3Tags() throws IOException {
        while (true) {
            // 标记当前位置，预读 10 个字节
            in.mark(10);
            byte[] header = new byte[10];
            int readBytes = 0;

            while (readBytes < 10) {
                int n = in.read(header, readBytes, 10 - readBytes);
                if (n == -1) {
                    // 遇到文件末尾，回退并放弃
                    in.reset();
                    return;
                }
                readBytes += n;
            }

            // 检查是否是 ID3 的魔数 "ID3" (0x49, 0x44, 0x33)
            // 6-9 位的高位必须全为 0
            if (header[0] == 0x49 && header[1] == 0x44 && header[2] == 0x33 &&
                (header[6] & 0x80) == 0 && (header[7] & 0x80) == 0 &&
                (header[8] & 0x80) == 0 && (header[9] & 0x80) == 0) {
                // 计算 ID3 载荷长度 (注意：每个字节只有低 7 位有效)
                int size = ((header[6] & 0x7F) << 21) |
                           ((header[7] & 0x7F) << 14) |
                           ((header[8] & 0x7F) << 7) |
                           (header[9] & 0x7F);

                // 跳过巨大的 ID3 载荷数据
                long skipped = 0;
                while (skipped < size) {
                    long s = in.skip(size - skipped);
                    if (s <= 0) {
                        break;
                    }
                    skipped += s;
                }
                // 注意：这里没有 break，而是继续 while 循环！
                // 因为有些极其变态的流会连续拼装两个 ID3 标签
            } else {
                // 如果前三个字节不是 "ID3"，说明可能是我们要找的 0x47 或者其他数据
                // 迅速回退这 10 个字节，把干净的流交还给后续逻辑
                in.reset();
                break;
            }
        }
    }

    /**
     * 严谨寻轨：只有当发现连续 CHECK_PACKETS_COUNT 个包头都是 0x47 时，才确认同步
     */
    private boolean resync() throws IOException {
        while (true) {
            in.mark(TS_PACKET_SIZE * CHECK_PACKETS_COUNT + 1);
            int b = in.read();
            if (b == -1) {
                return false;
            }

            if (b == SYNC_BYTE) {
                if (checkSequence()) {
                    return true;
                } else {
                    // 继续往后找下一个字节
                    in.read();
                }
            }
        }
    }

    /**
     * 检查从当前位置开始，每隔 188 字节是否都是 0x47
     */
    private boolean checkSequence() throws IOException {
        // 使用预分配的 temp 数组来“吃掉”间隔的字节，比 InputStream.skip 更可靠
        byte[] temp = new byte[TS_PACKET_SIZE];
        boolean valid = true;

        for (int i = 1; i < CHECK_PACKETS_COUNT; i++) {
            // 游标当前在 0x47 之后，因此需要跳过 187 字节
            int toSkip = TS_PACKET_SIZE - 1;
            int readBytes = 0;

            while (readBytes < toSkip) {
                int n = in.read(temp, readBytes, toSkip - readBytes);
                if (n == -1) {
                    valid = false;
                    break;
                }
                readBytes += n;
            }

            if (!valid) {
                break;
            }

            // 读取第 188 个字节，期望是 0x47
            int nextByte = in.read();
            if (nextByte == -1) {
                // 刚好在完整的 TS 包边界遇到了 EOF！
                // 说明前面的 1 个或 2 个包是完整的，只是文件没有更多数据来满足 CHECK_PACKETS_COUNT 了。
                // 此时应该认为验证通过，终止后续检查。
                break;
            } else if (nextByte != SYNC_BYTE) {
                // 读到了数据，但不是 0x47，确实是伪造的包头
                valid = false;
                break;
            }
        }

        // 检查完毕后，必须重置回起点
        // 由外部的 resync() 决定是锁定位置还是跳过假包头
        in.reset();
        return valid;
    }
}

