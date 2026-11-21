package com.tora.qual.log;

import com.tora.qual.ExpectedErrorLogs;
import com.tora.qual.RedisSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.UnknownHostException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 监听任务的异常分支：Redis 返回 null、建组失败、循环出错、启停 */
class QuotaLogStreamListenerEdgeTest {

    static final String STREAM = "edge:qual:log";
    static final String GROUP = "edge-group";
    static final Duration POLL = Duration.ofMillis(20);

    StringRedisTemplate redis;
    QuotaLogStore store;

    @BeforeEach
    void setUp() {
        redis = RedisSupport.template();
        store = mock(QuotaLogStore.class);
    }

    private QuotaLogStreamListener listener(StringRedisTemplate t, QuotaLogStore s, Duration claimIdle) {
        return new QuotaLogStreamListener(t, s, STREAM, GROUP, 10, POLL, claimIdle);
    }

    private void publishOne() {
        QuotaLog l = new QuotaLog(null, "n", null, QuotaLogType.CREATE, null, 1, 0, 1, 0, 0, null, null, null, null,
                LocalDateTime.now());
        redis.opsForStream().add(StreamRecords.mapBacked(QuotaLogCodec.encode(l)).withStreamKey(STREAM));
    }

    @Test
    void 取不到主机名用unknown() {
        assertThat(QuotaLogStreamListener.hostName(() -> "host-a")).isEqualTo("host-a");
        assertThat(QuotaLogStreamListener.hostName(() -> {
            throw new UnknownHostException("no dns");
        })).isEqualTo("unknown");
    }

    @Test
    void 建组遇到BUSYGROUP以外的错误照常抛() {
        redis.opsForValue().set(STREAM, "不是 Stream");
        assertThatThrownBy(() -> listener(redis, store, Duration.ofMinutes(5)).ensureGroup())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void Redis返回null当没有消息() {
        StringRedisTemplate t = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> ops = mock(StreamOperations.class, inv -> null);
        doReturn(ops).when(t).opsForStream();
        assertThat(listener(t, store, Duration.ofMinutes(5)).pollOnce()).isZero();
    }

    @Test
    @ExpectedErrorLogs(QuotaLogStreamListener.class)
    @SuppressWarnings("unchecked")
    void 一轮出错等5倍_消费组没了但重建失败也等5倍() {
        StringRedisTemplate t = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> nogroup = mock(StreamOperations.class, inv -> {
            throw new IllegalStateException("读失败", new RuntimeException("NOGROUP No such key"));
        });
        doReturn(nogroup).when(t).opsForStream();
        when(t.execute(any(RedisCallback.class))).thenThrow(new IllegalStateException("Redis 连不上"));
        QuotaLogStreamListener l = listener(t, store, Duration.ofMinutes(5));
        assertThat(l.step()).isEqualTo(POLL.toMillis() * 5);

        StreamOperations<String, Object, Object> broken = mock(StreamOperations.class, inv -> {
            throw new IllegalStateException("别的错误");
        });
        doReturn(broken).when(t).opsForStream();
        assertThat(l.step()).isEqualTo(POLL.toMillis() * 5);
    }

    @Test
    void 一轮处理了消息马上下一轮_没消息等一个间隔() {
        QuotaLogStreamListener l = listener(redis, store, Duration.ofMinutes(5));
        l.ensureGroup();
        publishOne();
        assertThat(l.step()).isZero();
        assertThat(l.step()).isEqualTo(POLL.toMillis());
    }

    @Test
    @ExpectedErrorLogs(QuotaLogStreamListener.class)
    void 别人的消息没超过空闲时间不认领() {
        QuotaLogStore down = mock(QuotaLogStore.class);
        doThrow(new IllegalStateException("库挂了")).when(down).saveAll(anyList());
        QuotaLogStreamListener a = listener(redis, down, Duration.ofHours(1));
        a.ensureGroup();
        publishOne();
        assertThat(a.pollOnce()).isEqualTo(-1);

        QuotaLogStreamListener b = listener(redis, store, Duration.ofHours(1));
        b.claimIfDue();
        PendingMessages p = redis.opsForStream().pending(STREAM, GROUP, org.springframework.data.domain.Range.unbounded(), 10);
        assertThat(p).hasSize(1);
        assertThat(p.get(0).getConsumerName()).isEqualTo(a.consumer());
    }

    @Test
    @ExpectedErrorLogs(QuotaLogStreamListener.class)
    void 自己没确认的消息不认领() {
        QuotaLogStore down = mock(QuotaLogStore.class);
        doThrow(new IllegalStateException("库挂了")).when(down).saveAll(anyList());
        QuotaLogStreamListener a = listener(redis, down, Duration.ZERO);
        a.ensureGroup();
        publishOne();
        assertThat(a.pollOnce()).isEqualTo(-1);
        a.claimIfDue();
        PendingMessages p = redis.opsForStream().pending(STREAM, GROUP, org.springframework.data.domain.Range.unbounded(), 10);
        assertThat(p.get(0).getConsumerName()).isEqualTo(a.consumer());
    }

    @Test
    void 没启动也能停_重复启动忽略() {
        QuotaLogStreamListener l = listener(redis, store, Duration.ofMinutes(5));
        l.stop();
        l.start();
        l.start();
        assertThat(l.isRunning()).isTrue();
        l.stop();
        assertThat(l.isRunning()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 停的时候被打断_不再等后台线程() throws Exception {
        // 让后台线程卡在读 Redis 上，保证 stop 时它一定还活着
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        StringRedisTemplate t = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> ops = mock(StreamOperations.class, inv -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        });
        doReturn(ops).when(t).opsForStream();
        QuotaLogStreamListener l = listener(t, store, Duration.ofMinutes(5));
        l.start();
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        Thread.currentThread().interrupt();
        l.stop();
        assertThat(Thread.interrupted()).isTrue();
        assertThat(l.isRunning()).isFalse();
        release.countDown();
    }

    @Test
    void 等待时被打断就停下() {
        QuotaLogStreamListener l = listener(redis, store, Duration.ofMinutes(5));
        l.start();
        Thread.currentThread().interrupt();
        l.sleep(10_000);
        assertThat(Thread.interrupted()).isTrue();
        assertThat(l.isRunning()).isFalse();
        l.stop();
    }
}
