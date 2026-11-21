package com.tora.qual;

import com.tora.qual.QuotaException.Reason;
import com.tora.qual.log.QuotaLog;
import com.tora.qual.log.QuotaLogField;
import com.tora.qual.log.QuotaLogPublisher;
import com.tora.qual.log.QuotaLogQuery;
import com.tora.qual.log.QuotaLogType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** 对着真实 Redis 测：Lua 原子性、每次带总资格的同步、TTL 过期、日志。时钟用真实时间，TTL 才对得上 */
class RedisQuotaServiceTest {

    static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
    static final QuotaSpec PRIZE = QuotaSpec.of("prize", 7);

    StringRedisTemplate redis;
    MemoryPublisher logs;
    RedisQuotaService quota;

    @BeforeEach
    void setUp() {
        redis = RedisSupport.template();
        logs = new MemoryPublisher();
        quota = new RedisQuotaService(redis, logs, null, Clock.system(ZONE), "test:qual:");
    }

    private static QuotaException error(Executable r) {
        try {
            r.execute();
        } catch (QuotaException e) {
            return e;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
        throw new AssertionError("应该报 QuotaException");
    }

    private static Reason reason(Executable r) {
        return error(r).getReason();
    }

    private static List<QuotaLogField> fields(List<QuotaLog> l) {
        List<QuotaLogField> out = new ArrayList<QuotaLogField>();
        for (QuotaLog x : l) {
            out.add(x.getField());
        }
        return out;
    }

    private static List<QuotaLogType> types(List<QuotaLog> l) {
        List<QuotaLogType> out = new ArrayList<QuotaLogType>();
        for (QuotaLog x : l) {
            out.add(x.getType());
        }
        return out;
    }

    private static LocalDateTime tomorrow() {
        return LocalDate.now(ZONE).plusDays(1).atStartOfDay();
    }

    @Test
    void get_规格平铺传入_没用过也返回_不创建() {
        QuotaInfo q = quota.get("sign-in", 1, ExpireRule.minutes(5), "u1");
        assertThat(q.isExists()).isFalse();
        assertThat(q.getTotal()).isEqualTo(1);
        assertThat(q.getUsed()).isZero();
        assertThat(q.getAvailable()).isEqualTo(1);
        assertThat(quota.peek("sign-in", "u1")).isNull();

        quota.consume(QuotaSpec.of("sign-in", 1, ExpireRule.minutes(5)), "u1");
        assertThat(quota.get("sign-in", 1, ExpireRule.minutes(5), "u1").getAvailable()).isZero();
    }

    @Test
    void 总资格7_领完再领报错_请求带10后可用加3_已用不变() {
        for (int i = 1; i <= 7; i++) {
            QuotaInfo q = quota.consume(PRIZE, "u1");
            assertThat(q.getUsed()).isEqualTo(i);
            assertThat(q.getAvailable()).isEqualTo(7 - i);
        }
        QuotaException e = error(() -> quota.consume(PRIZE, "u1"));
        assertThat(e.getReason()).isEqualTo(Reason.NOT_ENOUGH);
        assertThat(e.getMessage()).isEqualTo("资格「prize:u1」不足：可用 0，需要 1");

        QuotaInfo q = quota.get(QuotaSpec.of("prize", 10), "u1");
        assertThat(q.getTotal()).isEqualTo(10);
        assertThat(q.getUsed()).isEqualTo(7);
        assertThat(q.getAvailable()).isEqualTo(3);
        List<QuotaLog> l = logs.of("prize", "u1");
        QuotaLog total = l.get(l.size() - 1);
        assertThat(total.getType()).isEqualTo(QuotaLogType.UPDATE);
        assertThat(total.getField()).isEqualTo(QuotaLogField.TOTAL);
        assertThat(total.getTotalBefore()).isEqualTo(7);
        assertThat(total.getTotalAfter()).isEqualTo(10);
        assertThat(total.getAmount()).isEqualTo(3);
        assertThat(total.getUsedBefore()).isEqualTo(7).isEqualTo(total.getUsedAfter());

        // 同样的总资格再请求不会重复记日志
        int before = logs.logs.size();
        quota.get(QuotaSpec.of("prize", 10), "u1");
        assertThat(logs.logs).hasSize(before);
    }

    @Test
    void 总资格调小到已用以下_扣减报不足_查询照常返回负的可用() {
        quota.consume(PRIZE, "u1", 5, null);
        final QuotaSpec smaller = QuotaSpec.of("prize", 3);
        QuotaException e = error(() -> quota.consume(smaller, "u1"));
        assertThat(e.getReason()).isEqualTo(Reason.NOT_ENOUGH);
        assertThat(e.getInfo().getAvailable()).isEqualTo(-2);
        // 扣减失败，但总资格已经同步并记了日志
        assertThat(fields(logs.of("prize", "u1"))).containsExactly(null, QuotaLogField.AVAILABLE, QuotaLogField.TOTAL);

        QuotaInfo q = quota.get(smaller, "u1");
        assertThat(q.getTotal()).isEqualTo(3);
        assertThat(q.getUsed()).isEqualTo(5);
        assertThat(q.getAvailable()).isEqualTo(-2);
        assertThat(q.isOverused()).isTrue();
        assertThat(quota.restore(smaller, "u1", 2, null).getAvailable()).isZero();
        assertThat(quota.tryConsume(smaller, "u1", 1, null)).isFalse();
    }

    @Test
    void 共用资格和独享资格互不影响() {
        quota.consume(PRIZE, null);
        quota.consume(PRIZE, "");
        quota.consume(PRIZE, "u1");
        assertThat(quota.get(PRIZE, null).getUsed()).isEqualTo(2);
        assertThat(quota.get(PRIZE, null).getSuffix()).isEmpty();
        assertThat(quota.get(PRIZE, "").getSuffix()).isEmpty();
        assertThat(quota.get(PRIZE, "u1").getUsed()).isEqualTo(1);
        assertThat(quota.get(PRIZE, "u2").getUsed()).isZero();
        assertThat(quota.key("prize", null)).isEqualTo("test:qual:prize");
        assertThat(quota.key("prize", "u1")).isEqualTo("test:qual:prize:u1");
        assertThat(redis.opsForHash().entries("test:qual:prize")).containsEntry("total", "7").containsEntry("used", "2")
                .containsEntry("rule", "NEVER");
    }

    @Test
    void 查询不存在的不创建不记日志_peek只读() {
        QuotaSpec daily = QuotaSpec.of("daily", 3, ExpireRule.naturalDay());
        QuotaInfo q = quota.get(daily, "new");
        assertThat(q.isExists()).isFalse();
        assertThat(q.getTotal()).isEqualTo(3);
        assertThat(q.getAvailable()).isEqualTo(3);
        assertThat(q.getExpireAt()).isEqualTo(tomorrow());
        assertThat(redis.hasKey("test:qual:daily:new")).isFalse();
        assertThat(logs.logs).isEmpty();
        assertThat(quota.peek("daily", "new")).isNull();

        quota.consume(daily, "new");
        assertThat(quota.get(daily, "new").isExists()).isTrue();
        QuotaInfo p = quota.peek("daily", "new");
        assertThat(p.isExists()).isTrue();
        assertThat(p.getUsed()).isEqualTo(1);
        assertThat(p.getTotal()).isEqualTo(3);
        assertThat(p.getRule()).isEqualTo(ExpireRule.naturalDay());
        assertThat(p.getExpireAt()).isCloseTo(tomorrow(), within(2, ChronoUnit.SECONDS));
        assertThat(p.toString()).contains("daily:new");
        // peek 不同步总资格
        int before = logs.logs.size();
        assertThat(quota.peek("daily", "new").getTotal()).isEqualTo(3);
        assertThat(logs.logs).hasSize(before);
    }

    @Test
    void 过期交给TTL_过期后下次请求重新创建() throws Exception {
        QuotaSpec daily = QuotaSpec.of("daily", 3, ExpireRule.naturalDay());
        QuotaInfo q = quota.consume(daily, "u1", 3, null);
        assertThat(q.getExpireAt()).isCloseTo(tomorrow(), within(2, ChronoUnit.SECONDS));
        assertThat(redis.getExpire("test:qual:daily:u1", TimeUnit.MILLISECONDS)).isPositive();

        // 模拟到期：让 Redis 删掉 key
        redis.expire("test:qual:daily:u1", 30, TimeUnit.MILLISECONDS);
        Thread.sleep(80);
        q = quota.consume(daily, "u1");
        assertThat(q.getUsed()).isEqualTo(1);
        List<QuotaLog> l = logs.of("daily", "u1");
        assertThat(types(l)).containsExactly(QuotaLogType.CREATE, QuotaLogType.UPDATE, QuotaLogType.CREATE,
                QuotaLogType.UPDATE);
        assertThat(l.get(0).getRemark()).isEqualTo("首次请求创建（NATURAL_DAY）");
        assertThat(l.get(0).getExpireAfter()).isCloseTo(tomorrow(), within(2, ChronoUnit.SECONDS));
    }

    @Test
    void 滚动时长_自然小时的TTL() {
        QuotaInfo q = quota.consume(QuotaSpec.of("try", 1, ExpireRule.minutes(10)), "u1");
        assertThat(q.getExpireAt()).isCloseTo(LocalDateTime.now(ZONE).plusMinutes(10), within(3, ChronoUnit.SECONDS));
        q = quota.consume(QuotaSpec.of("hourly", 1, ExpireRule.naturalHour()), "u1");
        LocalDateTime nextHour = LocalDateTime.now(ZONE).withMinute(0).withSecond(0).withNano(0).plusHours(1);
        assertThat(q.getExpireAt()).isCloseTo(nextHour, within(2, ChronoUnit.SECONDS));
    }

    @Test
    void 规则变了重设TTL并记日志() {
        quota.consume(QuotaSpec.of("vip", 5, ExpireRule.naturalDay()), "u1");
        QuotaInfo q = quota.consume(QuotaSpec.of("vip", 5, ExpireRule.hours(2)), "u1");
        assertThat(q.getUsed()).isEqualTo(2);
        assertThat(q.getExpireAt()).isCloseTo(LocalDateTime.now(ZONE).plusHours(2), within(3, ChronoUnit.SECONDS));
        QuotaLog change = logs.of("vip", "u1").get(2);
        assertThat(change.getField()).isEqualTo(QuotaLogField.EXPIRE);
        assertThat(change.getRemark()).isEqualTo("有效期 NATURAL_DAY → HOURS:2");
        assertThat(change.getExpireBefore()).isCloseTo(tomorrow(), within(2, ChronoUnit.SECONDS));
        assertThat(change.getExpireAfter()).isEqualTo(q.getExpireAt());

        // 改成永不过期：去掉 TTL
        q = quota.get(QuotaSpec.of("vip", 5), "u1");
        assertThat(q.getExpireAt()).isNull();
        assertThat(redis.getExpire("test:qual:vip:u1")).isEqualTo(-1L);
        assertThat(logs.of("vip", "u1").get(4).getRemark()).isEqualTo("有效期 HOURS:2 → NEVER");
    }

    @Test
    void 指定时间过期_过了时间不能用() throws Exception {
        LocalDateTime soon = LocalDateTime.now(ZONE).plusSeconds(1);
        final QuotaSpec spec = QuotaSpec.of("event", 5, ExpireRule.at(soon, ZONE));
        quota.consume(spec, "u1");
        Thread.sleep(Duration.between(LocalDateTime.now(ZONE), soon).toMillis() + 150);
        assertThat(redis.hasKey("test:qual:event:u1")).isFalse();
        assertThat(reason(() -> quota.consume(spec, "u1"))).isEqualTo(Reason.EXPIRED);
        assertThat(quota.tryConsume(spec, "u1", 1, null)).isFalse();
        assertThat(redis.hasKey("test:qual:event:u1")).isFalse();

        // 已有的资格把规则改成已经过去的时间：直接过期删除
        quota.consume(PRIZE, "u9");
        final QuotaSpec past = QuotaSpec.of("prize", 7, ExpireRule.at(LocalDateTime.now(ZONE).minusDays(1), ZONE));
        QuotaException e = error(() -> quota.consume(past, "u9"));
        assertThat(e.getReason()).isEqualTo(Reason.EXPIRED);
        assertThat(e.getMessage()).contains("已过期");
        assertThat(redis.hasKey("test:qual:prize:u9")).isFalse();
        assertThat(logs.of("prize", "u9").get(2).getField()).isEqualTo(QuotaLogField.EXPIRE);
        assertThat(logs.of("prize", "u9").get(2).getExpireAfter()).isNull();
    }

    @Test
    void 退回_直接设可用_参数不对() {
        assertThat(reason(() -> quota.restore(PRIZE, "u1", 1, null))).isEqualTo(Reason.NOT_FOUND);
        assertThat(redis.hasKey("test:qual:prize:u1")).isFalse();
        quota.consume(PRIZE, "u1", 5, "order-1");
        assertThat(quota.restore(PRIZE, "u1", 2, "order-1").getUsed()).isEqualTo(3);
        assertThat(reason(() -> quota.restore(PRIZE, "u1", 4, null))).isEqualTo(Reason.INVALID);
        assertThat(reason(() -> quota.restore(PRIZE, "u1", 0, null))).isEqualTo(Reason.INVALID);
        assertThat(reason(() -> quota.consume(PRIZE, "u1", 0, null))).isEqualTo(Reason.INVALID);

        QuotaInfo q = quota.updateAvailable(PRIZE, "u1", 6, "客服调整");
        assertThat(q.getUsed()).isEqualTo(1);
        assertThat(q.getAvailable()).isEqualTo(6);
        assertThat(reason(() -> quota.updateAvailable(PRIZE, "u1", 8, null))).isEqualTo(Reason.INVALID);
        assertThat(quota.updateAvailable(PRIZE, "u2", 0, null).getUsed()).isEqualTo(7);
    }

    @Test
    void 创建_更新_删除都有日志() {
        quota.consume(PRIZE, "u1", 2, "order-1");
        quota.restore(PRIZE, "u1", 1, "order-1");
        quota.updateAvailable(PRIZE, "u1", 7, "清零");
        quota.consume(QuotaSpec.of("prize", 8), "u1");
        assertThat(quota.delete("prize", "u1", "注销")).isTrue();
        assertThat(quota.delete("prize", "u1", "注销")).isFalse();

        List<QuotaLog> l = logs.of("prize", "u1");
        assertThat(types(l)).containsExactly(QuotaLogType.CREATE, QuotaLogType.UPDATE, QuotaLogType.UPDATE,
                QuotaLogType.UPDATE, QuotaLogType.UPDATE, QuotaLogType.UPDATE, QuotaLogType.DELETE);
        assertThat(fields(l)).containsExactly(null, QuotaLogField.AVAILABLE, QuotaLogField.AVAILABLE,
                QuotaLogField.AVAILABLE, QuotaLogField.TOTAL, QuotaLogField.AVAILABLE, null);
        List<Long> amounts = new ArrayList<Long>();
        for (QuotaLog x : l) {
            amounts.add(x.getAmount());
        }
        assertThat(amounts).containsExactly(7L, -2L, 1L, 1L, 1L, -1L, -7L);
        QuotaLog consume = l.get(1);
        assertThat(consume.getBizId()).isEqualTo("order-1");
        assertThat(consume.getAvailableBefore()).isEqualTo(7);
        assertThat(consume.getAvailableAfter()).isEqualTo(5);
        assertThat(consume.getRemark()).isEqualTo("扣减");
        QuotaLog del = l.get(6);
        assertThat(del.getTotalBefore()).isEqualTo(8);
        assertThat(del.getUsedBefore()).isEqualTo(1);
        assertThat(del.getTotalAfter()).isZero();
        assertThat(del.getRemark()).isEqualTo("注销");
        assertThat(del.toString()).contains("DELETE");
        for (QuotaLog x : l) {
            assertThat(x.getCreatedAt()).isNotNull();
        }
    }

    @Test
    void 并发抢共用资格_不会超发() throws Exception {
        final QuotaSpec flash = QuotaSpec.of("flash", 50);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> fs = new ArrayList<Future<Boolean>>();
        for (int i = 0; i < 200; i++) {
            final String biz = "o" + i;
            fs.add(pool.submit(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    start.await();
                    return quota.tryConsume(flash, null, 1, biz);
                }
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Boolean> f : fs) {
            ok += f.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(50);
        assertThat(quota.get(flash, null).getUsed()).isEqualTo(50);
        assertThat(logs.logs).hasSize(51);
    }

    @Test
    @ExpectedErrorLogs(RedisQuotaService.class)
    void 日志发布失败不影响业务_没有数据库时查不到日志() {
        RedisQuotaService q = new RedisQuotaService(redis, new QuotaLogPublisher() {
            @Override
            public void publish(List<QuotaLog> l) {
                throw new IllegalStateException("Redis Stream 挂了");
            }
        }, null, Clock.system(ZONE), "test:qual:");
        assertThat(q.consume(PRIZE, "u1").getUsed()).isEqualTo(1);
        assertThat(q.delete("prize", "u1", null)).isTrue();
        assertThat(q.logs(new QuotaLogQuery().page(2, 10)).getList()).isEmpty();
        assertThat(q.logs(new QuotaLogQuery().page(2, 10)).getPageNum()).isEqualTo(2);
    }

    @Test
    void 规格校验() {
        assertThatThrownBy(() -> QuotaSpec.of(" ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuotaSpec.of("a", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new QuotaSpec("a", 1, null).getRule()).isEqualTo(ExpireRule.never());
        assertThat(QuotaSpec.of("a", 1)).isEqualTo(QuotaSpec.of("a", 1, ExpireRule.never()))
                .hasSameHashCodeAs(QuotaSpec.of("a", 1)).isNotEqualTo("a");
        assertThat(QuotaSpec.of("a", 1).toString()).contains("a");
    }

    @Test
    void tryConsume_过期返回false_其他错误照常抛() {
        QuotaSpec gone = QuotaSpec.of("gone", 3, ExpireRule.at(LocalDateTime.now(ZONE).minusMinutes(1), ZONE));
        assertThat(quota.tryConsume(gone, "u1", 1, null)).isFalse();
        assertThat(reason(() -> quota.tryConsume(PRIZE, "u1", 0, null))).isEqualTo(Reason.INVALID);
    }

    @Test
    void 共用资格的报错信息不带后缀() {
        assertThat(error(() -> quota.consume(PRIZE, null, 8, null)).getMessage()).startsWith("资格「prize」不足");
        assertThat(error(() -> quota.consume(PRIZE, "u1", 8, null)).getMessage()).startsWith("资格「prize:u1」不足");
    }

    @Test
    void 总资格和规则同时变了_记两条日志() {
        quota.consume(PRIZE, "u1");
        logs.logs.clear();
        QuotaInfo i = quota.get(QuotaSpec.of("prize", 9, ExpireRule.naturalDay()), "u1");
        assertThat(fields(logs.logs)).containsExactly(QuotaLogField.TOTAL, QuotaLogField.EXPIRE);
        // 总资格那条记在规则变之前：到期时间还是原来的（永不过期）
        assertThat(logs.logs.get(0).getExpireAfter()).isNull();
        assertThat(logs.logs.get(1).getExpireAfter()).isEqualTo(i.getExpireAt());
    }

    @Test
    void Lua返回未知结果码() {
        final RedisQuotaService.Result r = new RedisQuotaService.Result();
        r.code = 99;
        r.info = new QuotaInfo("x", null, true, 1, 0, ExpireRule.never(), null);
        assertThatThrownBy(() -> RedisQuotaService.check(r, 1)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未知结果 99");
    }
}
