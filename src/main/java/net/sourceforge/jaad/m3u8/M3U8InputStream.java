package net.sourceforge.jaad.m3u8;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class M3U8InputStream extends InputStream {
    /**
     * 默认提供的 HttpClient，但还是建议自行自定义一个，方便设置各种参数
     */
    private static final HttpClient DEFAULT_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * 主链接，必须是 m3u8 文件的 URL，后续会从这个链接解析出 TS 分片链接并下载数据流
     */
    private final String masterUrl;
    /**
     * 阻塞队列用于存储下载好的 TS 分片数据流，容量限制为 5 个分片，防止内存占用过高
     */
    private final BlockingQueue<InputStream> segmentQueue = new LinkedBlockingQueue<>(5);
    /**
     * 记录已经下载过的 TS 链接，防止重复下载
     */
    private final Set<String> processedUrls = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    // 仅保留最近的 50 个切片记录，防止 OOM
                    return size() > 50;
                }
            })
    );
    /**
     * HTTP 客户端，用来下载 m3u8 文件和 ts 分片数据流
     *
     */
    private final HttpClient httpClient;
    /**
     * 当前正在读取的 TS 分片数据流，读完后会自动切换到下一个分片，保持连续播放
     */
    private InputStream currentSegmentStream = null;
    /**
     * 调度 M3U8 和 TS 分片下载的线程调度器
     */
    private ScheduledExecutorService scheduler;
    /**
     * 标记流是否已经关闭，关闭后所有线程任务都会停止，资源会被清理
     */
    private volatile boolean isClosed = false;
    /**
     * 标记是否为点播（VOD）且已经读到结尾，用于平滑退出
     */
    private volatile boolean noMoreSegments = false;

    public M3U8InputStream(HttpClient httpClient, String m3u8Url) {
        this.masterUrl = m3u8Url;
        this.httpClient = httpClient;

        // 初始化线程池
        this.initScheduler();
    }

    public M3U8InputStream(String m3u8Url) {
        this(DEFAULT_CLIENT, m3u8Url);
    }

    private void initScheduler() {
        this.scheduler = Executors.newScheduledThreadPool(1, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "M3U8-Worker-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });

        // 立即启动第一次任务
        this.scheduler.execute(this::refreshAndDownloadTask);
    }

    private void refreshAndDownloadTask() {
        if (isClosed || noMoreSegments) {
            return;
        }

        try {
            M3U8Playlist playlist = M3U8Parser.fetchPlaylist(httpClient, masterUrl);
            for (String url : playlist.getNewTsUrls()) {
                if (isClosed) {
                    break;
                }
                // 仅新增的 ts 切片可以放入
                if (!processedUrls.contains(url)) {
                    InputStream tsData = M3U8Parser.fetchSegment(httpClient, url);
                    segmentQueue.put(tsData);
                    processedUrls.add(url);
                }
            }

            // 如果是点播（VOD）且已经没有新内容了，标记结束，不再轮询
            if (!playlist.isLive()) {
                noMoreSegments = true;
                return;
            }

            // 如果还没有关闭且可能还有新分片，继续调度下一次刷新和下载任务
            if (!isClosed && !noMoreSegments) {
                // 根据 m3u8 文件中的 TARGET DURATION 动态调整刷新频率
                long nextRefreshDelayMs = playlist.getTargetDurationMs();
                scheduler.schedule(this::refreshAndDownloadTask, nextRefreshDelayMs, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable e) {
            // 任何异常，立即关闭
            try {
                this.close();
            } catch (Throwable ignore) {
            }
        }
    }

    @Override
    public int read() throws IOException {
        while (true) {
            if (isClosed) {
                return -1;
            }

            if (currentSegmentStream == null) {
                // 如果队列空了且确认没有更多分片了，说明彻底播完了
                if (noMoreSegments && segmentQueue.isEmpty()) {
                    return -1;
                }
                try {
                    currentSegmentStream = segmentQueue.poll(5, TimeUnit.SECONDS);
                    if (currentSegmentStream == null) {
                        // 等待超时，防止游戏线程死锁
                        return -1;
                    }
                } catch (InterruptedException e) {
                    return -1;
                }
            }

            int data = currentSegmentStream.read();
            if (data != -1) {
                return data;
            }

            // 当前分片读完，关闭它并继续循环读取下一个
            currentSegmentStream.close();
            currentSegmentStream = null;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        while (true) {
            if (isClosed) {
                return -1;
            }

            if (currentSegmentStream == null) {
                if (noMoreSegments && segmentQueue.isEmpty()) {
                    return -1;
                }
                try {
                    currentSegmentStream = segmentQueue.poll(5, TimeUnit.SECONDS);
                    if (currentSegmentStream == null) {
                        // 等待超时，防止游戏线程死锁
                        return -1;
                    }
                } catch (InterruptedException e) {
                    return -1;
                }
            }

            int bytesRead = currentSegmentStream.read(b, off, len);
            if (bytesRead != -1) {
                return bytesRead;
            }

            // 当前分片读完，关闭它并继续循环读取下一个
            currentSegmentStream.close();
            currentSegmentStream = null;
        }
    }

    @Override
    public void close() throws IOException {
        if (isClosed) {
            return;
        }
        // 通知下载线程，立即停止下载和刷新任务，防止资源泄露
        isClosed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (currentSegmentStream != null) {
            currentSegmentStream.close();
        }
        // 关闭等待读取的所有的 ts 流
        InputStream queuedStream;
        while ((queuedStream = segmentQueue.poll()) != null) {
            queuedStream.close();
        }
        processedUrls.clear();
        super.close();
    }
}