package com.tora.qual.log;

import com.tora.qual.ExpectedErrorLogs;
import com.tora.qual.Migrations;
import com.tora.qual.QuotaSpec;
import com.tora.qual.RedisQuotaService;
import com.tora.qual.RedisSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 资格操作 → 发布到 Redis Stream → 监听写库 */
class QuotaLogStreamListenerTest {

    static final String STREAM = "test:qual:log";
    static final String GROUP = "test-group";

    StringRedisTemplate redis;
    JdbcQuotaLogStore store;
    RedisQuotaService quota;

    @BeforeEach
    void setUp() {
        redis = RedisSupport.template();
        store = new JdbcQuotaLogStore(new JdbcTemplate(Migrations.up("MySQL", "mysql")), "tora_qual_log", "mysql");
        quota = new RedisQuotaService(redis, new RedisStreamQuotaLogPublisher(redis, STREAM), store,
                Clock.system(ZoneId.of("Asia/Tokyo")), "test:qual:");
    }

    private QuotaLogStreamListener listener(QuotaLogStore s, Duration claimIdle) {
        QuotaLogStreamListener l = new QuotaLogStreamListener(redis, s, STREAM, GROUP, 100, Duration.ofMillis(50), claimIdle);
        l.ensureGroup();
        return l;
    }

    private long pending() {
        return redis.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages();
    }

    @Test
    void 发布_监听写库_ACK后从Stream删除() {
        QuotaLogStreamListener listener = listener(store, Duration.ofMinutes(5));
        listener.ensureGroup();
        quota.consume(QuotaSpec.of("prize", 7), "u1", 2, "order-1");
        quota.consume(QuotaSpec.of("prize", 10), "u1");
        assertThat(redis.opsForStream().size(STREAM)).isEqualTo(4);

        assertThat(listener.pollOnce()).isEqualTo(4);
        assertThat(redis.opsForStream().size(STREAM)).isZero();
        assertThat(listener.pollOnce()).isZero();

        QuotaLogPage page = quota.logs(new QuotaLogQuery().name("prize").suffix("u1"));
        assertThat(page.getTotal()).isEqualTo(4);
        List<QuotaLogField> fields = new ArrayList<QuotaLogField>();
        for (QuotaLog l : page.getList()) {
            fields.add(l.getField());
        }
        assertThat(fields).containsExactly(QuotaLogField.AVAILABLE, QuotaLogField.TOTAL, QuotaLogField.AVAILABLE, null);
        assertThat(page.getList().get(2).getBizId()).isEqualTo("order-1");
    }

    @Test
    @ExpectedErrorLogs(QuotaLogStreamListener.class)
    void 写库失败不ACK_下一轮重试() {
        final AtomicBoolean down = new AtomicBoolean(true);
        QuotaLogStore flaky = new QuotaLogStore() {
            @Override
            public void saveAll(List<QuotaLog> logs) {
                if (down.get()) {
                    throw new IllegalStateException("数据库挂了");
                }
                store.saveAll(logs);
            }

            @Override
            public QuotaLogPage query(QuotaLogQuery q) {
                return store.query(q);
            }
        };
        QuotaLogStreamListener listener = listener(flaky, Duration.ofMinutes(5));
        quota.consume(QuotaSpec.of("prize", 7), "u1");
        assertThat(listener.pollOnce()).isEqualTo(-1);
        assertThat(redis.opsForStream().size(STREAM)).isEqualTo(2);
        assertThat(pending()).isEqualTo(2);

        down.set(false);
        assertThat(listener.pollOnce()).isEqualTo(2);
        assertThat(redis.opsForStream().size(STREAM)).isZero();
        assertThat(store.query(new QuotaLogQuery().name("prize")).getTotal()).isEqualTo(2);
    }

    @Test
    void 别的实例拿走没处理的消息_超时后认领() throws Exception {
        QuotaLogStreamListener listener = listener(store, Duration.ofMillis(100));
        quota.consume(QuotaSpec.of("prize", 7), "u1");
        // 另一个实例读走了但没 ACK（比如读完就挂了）
        redis.opsForStream().read(Consumer.from(GROUP, "dead-node"), StreamReadOptions.empty().count(10),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        assertThat(listener.pollOnce()).isZero();
        Thread.sleep(150);
        assertThat(listener.pollOnce()).isEqualTo(2);
        assertThat(redis.opsForStream().size(STREAM)).isZero();
        assertThat(store.query(new QuotaLogQuery().name("prize")).getTotal()).isEqualTo(2);
    }

    @Test
    @ExpectedErrorLogs(QuotaLogStreamListener.class)
    void 格式不对的消息丢弃_不卡住后面的() {
        QuotaLogStreamListener listener = listener(store, Duration.ofMinutes(5));
        Map<String, String> bad = new HashMap<String, String>();
        bad.put("name", "x");
        bad.put("type", "WHAT");
        redis.opsForStream().add(StreamRecords.newRecord().in(STREAM).ofMap(bad));
        quota.consume(QuotaSpec.of("prize", 7), "u1");
        assertThat(listener.pollOnce()).isEqualTo(3);
        assertThat(redis.opsForStream().size(STREAM)).isZero();
        assertThat(store.query(new QuotaLogQuery()).getTotal()).isEqualTo(2);
    }

    @Test
    void 启动后台线程自动写库_重复建组不报错() throws Exception {
        QuotaLogStreamListener listener = listener(store, Duration.ofMinutes(5));
        listener.ensureGroup();
        assertThat(listener.consumer()).isNotEmpty();
        listener.start();
        listener.start();
        assertThat(listener.isRunning()).isTrue();
        quota.consume(QuotaSpec.of("prize", 7), "u1", 3, null);
        long deadline = System.currentTimeMillis() + 5000;
        while (store.query(new QuotaLogQuery().name("prize")).getTotal() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        listener.stop();
        assertThat(listener.isRunning()).isFalse();
        assertThat(store.query(new QuotaLogQuery().name("prize")).getTotal()).isEqualTo(2);
    }

    @Test
    @ExpectedErrorLogs(QuotaLogStreamListener.class)
    void Stream被删了_后台线程自动重建消费组() throws Exception {
        QuotaLogStreamListener listener = listener(store, Duration.ofMinutes(5));
        listener.start();
        RedisSupport.flush(redis);
        quota.consume(QuotaSpec.of("prize", 7), "u1");
        long deadline = System.currentTimeMillis() + 5000;
        while (store.query(new QuotaLogQuery().name("prize")).getTotal() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        listener.stop();
        assertThat(store.query(new QuotaLogQuery().name("prize")).getTotal()).isEqualTo(2);
    }
}
