package com.tora.qual.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * 监听 Redis Stream 里的资格日志，组装成 SQL 批量写库。
 * <ul>
 *   <li>多台机器用同一个消费组，每条消息只会交给一台</li>
 *   <li>写库成功后 ACK 并删除消息；失败不 ACK，下一轮先重试自己没处理完的</li>
 *   <li>别的机器挂了留下的未处理消息，超过 claimIdle 没动静就认领过来</li>
 * </ul>
 */
public class QuotaLogStreamListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(QuotaLogStreamListener.class);

    private final StringRedisTemplate redis;
    private final QuotaLogStore store;
    private final String streamKey;
    private final String group;
    private final String consumer;
    private final int batchSize;
    private final Duration pollInterval;
    private final Duration claimIdle;

    private volatile boolean running;
    private Thread worker;
    private long lastClaim;

    public QuotaLogStreamListener(StringRedisTemplate redis, QuotaLogStore store, String streamKey, String group,
                                  int batchSize, Duration pollInterval, Duration claimIdle) {
        this.redis = redis;
        this.store = store;
        this.streamKey = streamKey;
        this.group = group;
        this.consumer = consumerName();
        this.batchSize = Math.max(1, batchSize);
        this.pollInterval = pollInterval;
        this.claimIdle = claimIdle;
    }

    private static String consumerName() {
        String host = hostName(() -> InetAddress.getLocalHost().getHostName());
        // RuntimeMXBean 的名字是「pid@主机名」，Java 8 没有 getPid()
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        return host + "-" + pid + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static String hostName(Callable<String> lookup) {
        try {
            return lookup.call();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public String consumer() {
        return consumer;
    }

    /**
     * 建消费组（Stream 不存在时一起建）；已经有了就跳过。
     * 直接发 XGROUP CREATE ... MKSTREAM，不依赖各版本 Spring Data Redis 的 createGroup 是否带 MKSTREAM。
     */
    void ensureGroup() {
        try {
            redis.execute((RedisCallback<Object>) c ->
                    c.execute("XGROUP", bytes("CREATE"), bytes(streamKey), bytes(group), bytes("0"), bytes("MKSTREAM")));
        } catch (RuntimeException e) {
            if (!String.valueOf(rootMessage(e)).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String rootMessage(Throwable e) {
        while (e.getCause() != null) {
            e = e.getCause();
        }
        return e.getMessage();
    }

    /**
     * 处理一批：先重试自己没 ACK 的，没有再读新的。返回处理了几条（失败返回 -1）。
     */
    public int pollOnce() {
        claimIfDue();
        List<MapRecord<String, Object, Object>> records = read(ReadOffset.from("0"));
        if (records.isEmpty()) {
            records = read(ReadOffset.lastConsumed());
        }
        if (records.isEmpty()) {
            return 0;
        }
        List<QuotaLog> logs = new ArrayList<QuotaLog>(records.size());
        List<RecordId> bad = new ArrayList<RecordId>();
        List<RecordId> ids = new ArrayList<RecordId>(records.size());
        for (MapRecord<String, Object, Object> r : records) {
            try {
                logs.add(QuotaLogCodec.decode(r.getValue()));
                ids.add(r.getId());
            } catch (RuntimeException e) {
                // 格式不对的消息没法写库，记下来直接丢掉，免得一直卡住
                log.error("资格日志消息格式不对，已丢弃：{} {}", r.getId(), r.getValue(), e);
                bad.add(r.getId());
            }
        }
        done(bad);
        try {
            store.saveAll(logs);
        } catch (RuntimeException e) {
            log.error("资格日志写库失败 {} 条，稍后重试", logs.size(), e);
            return -1;
        }
        done(ids);
        return records.size();
    }

    private List<MapRecord<String, Object, Object>> read(ReadOffset offset) {
        List<MapRecord<String, Object, Object>> r = redis.opsForStream().read(Consumer.from(group, consumer),
                StreamReadOptions.empty().count(batchSize), StreamOffset.create(streamKey, offset));
        return r == null ? Collections.<MapRecord<String, Object, Object>>emptyList() : r;
    }

    private void done(List<RecordId> ids) {
        if (ids.isEmpty()) {
            return;
        }
        RecordId[] arr = ids.toArray(new RecordId[0]);
        redis.opsForStream().acknowledge(streamKey, group, arr);
        redis.opsForStream().delete(streamKey, arr);
    }

    /** 认领别的消费者（机器挂了）长时间没 ACK 的消息 */
    void claimIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastClaim < claimIdle.toMillis() / 2) {
            return;
        }
        lastClaim = now;
        PendingMessages pending = redis.opsForStream().pending(streamKey, group, Range.unbounded(), batchSize * 10L);
        if (pending == null) {
            return;
        }
        List<RecordId> stale = new ArrayList<RecordId>();
        for (PendingMessage m : pending) {
            if (!m.getConsumerName().equals(consumer) && m.getElapsedTimeSinceLastDelivery().compareTo(claimIdle) >= 0) {
                stale.add(m.getId());
            }
        }
        if (!stale.isEmpty()) {
            redis.opsForStream().claim(streamKey, group, consumer, claimIdle, stale.toArray(new RecordId[0]));
            log.info("认领了 {} 条其他实例没处理完的资格日志", stale.size());
        }
    }

    private void loop() {
        while (running) {
            long ms = step();
            if (ms > 0) {
                sleep(ms);
            }
        }
    }

    /** 跑一轮，返回接下来要等多少毫秒：处理了消息马上下一轮，没消息等 pollInterval，出错等 5 倍 */
    long step() {
        int n;
        try {
            n = pollOnce();
        } catch (RuntimeException e) {
            n = -1;
            if (String.valueOf(rootMessage(e)).contains("NOGROUP")) {
                // Stream 或消费组被删了（比如 FLUSHDB），重建后继续
                log.warn("资格日志的消费组不存在，重新创建：{} / {}", streamKey, group);
                try {
                    ensureGroup();
                    n = 0;
                } catch (RuntimeException ce) {
                    log.error("重建资格日志消费组失败", ce);
                }
            } else {
                log.error("读取资格日志失败", e);
            }
        }
        if (n > 0) {
            return 0;
        }
        return n < 0 ? pollInterval.toMillis() * 5 : pollInterval.toMillis();
    }

    void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        ensureGroup();
        running = true;
        worker = new Thread(this::loop, "tora-qual-log");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public synchronized void stop() {
        // 不打断线程：写到一半被打断，可能写进库了却没 ACK，下次会重复写；等它这一轮做完
        running = false;
        if (worker != null) {
            try {
                worker.join(pollInterval.multipliedBy(5).toMillis() + 10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
