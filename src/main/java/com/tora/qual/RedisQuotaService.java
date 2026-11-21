package com.tora.qual;

import com.tora.qual.QuotaException.Reason;
import com.tora.qual.log.QuotaLog;
import com.tora.qual.log.QuotaLogField;
import com.tora.qual.log.QuotaLogPage;
import com.tora.qual.log.QuotaLogPublisher;
import com.tora.qual.log.QuotaLogQuery;
import com.tora.qual.log.QuotaLogStore;
import com.tora.qual.log.QuotaLogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 资格存在 Redis Hash 里，所有读改写都在 Lua 脚本里一次完成，不用加锁 */
public class RedisQuotaService implements QuotaService {

    private static final Logger log = LoggerFactory.getLogger(RedisQuotaService.class);

    @SuppressWarnings("rawtypes")
    static final RedisScript<List> SCRIPT = loadScript();

    // Lua 脚本的结果码，和 quota.lua 顶部的说明一致
    static final int OK = 0;
    static final int NOT_ENOUGH = 1;
    static final int EXPIRED = 2;
    static final int NOT_FOUND = 3;
    static final int INVALID = 6;

    // Lua 脚本的操作名（ARGV[1]）
    private static final String OP_GET = "get";
    private static final String OP_PEEK = "peek";
    private static final String OP_CONSUME = "consume";
    private static final String OP_RESTORE = "restore";
    private static final String OP_SET_AVAILABLE = "setAvailable";
    private static final String OP_DELETE = "delete";

    private final StringRedisTemplate redis;
    private final QuotaLogPublisher publisher;
    private final QuotaLogStore store;
    private final Clock clock;
    private final String keyPrefix;

    /** @param store 查日志用；没有数据库时传 null，查出来是空的 */
    public RedisQuotaService(StringRedisTemplate redis, QuotaLogPublisher publisher, QuotaLogStore store, Clock clock,
                             String keyPrefix) {
        this.redis = redis;
        this.publisher = publisher;
        this.store = store;
        this.clock = clock;
        this.keyPrefix = keyPrefix;
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> loadScript() {
        DefaultRedisScript<List> s = new DefaultRedisScript<List>();
        s.setScriptSource(new ResourceScriptSource(new ClassPathResource("tora-qual/quota.lua")));
        s.setResultType(List.class);
        return s;
    }

    /** Redis key：前缀 + 资格名，有后缀再加「:后缀」 */
    public String key(String name, String suffix) {
        return keyPrefix + name + (isShared(suffix) ? "" : ":" + suffix);
    }

    private static boolean isShared(String suffix) {
        return suffix == null || suffix.isEmpty();
    }

    // ---------------- 查询 ----------------

    @Override
    public QuotaInfo get(QuotaSpec spec, String suffix) {
        Result r = run(spec, suffix, OP_GET, 0);
        publish(r, null, 0, null, null);
        check(r, 0);
        return r.info;
    }

    @Override
    public QuotaInfo peek(String name, String suffix) {
        Result r = run(QuotaSpec.of(name, 0), suffix, OP_PEEK, 0);
        if (r.code == NOT_FOUND) {
            return null;
        }
        QuotaInfo i = r.info;
        return new QuotaInfo(i.getName(), i.getSuffix(), true, i.getTotal(), i.getUsed(), ExpireRule.parse(r.ruleBefore),
                i.getExpireAt());
    }

    @Override
    public QuotaLogPage logs(QuotaLogQuery query) {
        return store == null ? QuotaLogPage.empty(query) : store.query(query);
    }

    // ---------------- 更新可用资格 ----------------

    @Override
    public QuotaInfo consume(QuotaSpec spec, String suffix) {
        return consume(spec, suffix, 1, null);
    }

    @Override
    public QuotaInfo consume(QuotaSpec spec, String suffix, long amount, String bizId) {
        Result r = run(spec, suffix, OP_CONSUME, amount);
        publish(r, QuotaLogField.AVAILABLE, -amount, bizId, "扣减");
        check(r, amount);
        return r.info;
    }

    @Override
    public boolean tryConsume(QuotaSpec spec, String suffix, long amount, String bizId) {
        try {
            consume(spec, suffix, amount, bizId);
            return true;
        } catch (QuotaException e) {
            if (e.getReason() == Reason.NOT_ENOUGH || e.getReason() == Reason.EXPIRED) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public QuotaInfo restore(QuotaSpec spec, String suffix, long amount, String bizId) {
        Result r = run(spec, suffix, OP_RESTORE, amount);
        publish(r, QuotaLogField.AVAILABLE, amount, bizId, "退回");
        check(r, amount);
        return r.info;
    }

    @Override
    public QuotaInfo updateAvailable(QuotaSpec spec, String suffix, long available, String remark) {
        Result r = run(spec, suffix, OP_SET_AVAILABLE, available);
        publish(r, QuotaLogField.AVAILABLE, r.usedBefore - r.info.getUsed(), null, remark);
        check(r, available);
        return r.info;
    }

    // ---------------- 删除 ----------------

    @Override
    public boolean delete(String name, String suffix, String remark) {
        Result r = run(QuotaSpec.of(name, 0), suffix, OP_DELETE, 0);
        if (r.code == NOT_FOUND) {
            return false;
        }
        doPublish(Collections.singletonList(log(r, QuotaLogType.DELETE, null, -(r.totalBefore - r.usedBefore),
                r.totalBefore, 0, r.usedBefore, 0, r.expireBefore, null, "", remark)));
        return true;
    }

    // ---------------- 执行脚本 ----------------

    /** Lua 脚本的结果 */
    static final class Result {
        int code;
        boolean created;
        boolean totalChanged;
        boolean ruleChanged;
        QuotaInfo info;
        /** 同步前的总资格 */
        long totalBefore;
        /** 操作前的已用 */
        long usedBefore;
        /** 同步前的到期时间 */
        LocalDateTime expireBefore;
        String ruleBefore;
        LocalDateTime now;
    }

    @SuppressWarnings("unchecked")
    private Result run(QuotaSpec spec, String suffix, String op, long amount) {
        long nowMs = clock.millis();
        Instant now = Instant.ofEpochMilli(nowMs);
        ExpireRule rule = spec.getRule();
        List<Object> v = redis.execute(SCRIPT, Collections.singletonList(key(spec.getName(), suffix)), op,
                String.valueOf(amount), String.valueOf(spec.getTotal()), rule.format(),
                String.valueOf(rule.expireAt(now, clock.getZone())), String.valueOf(nowMs));
        Result r = new Result();
        r.code = (int) num(v, 0);
        r.created = num(v, 1) == 1;
        long total = num(v, 2);
        long used = num(v, 3);
        long pttl = num(v, 4);
        r.totalBefore = num(v, 5);
        r.usedBefore = num(v, 6);
        r.totalChanged = num(v, 7) == 1;
        r.ruleChanged = num(v, 8) == 1;
        long oldPttl = num(v, 9);
        r.expireBefore = expireAt(nowMs, oldPttl);
        r.ruleBefore = String.valueOf(v.get(10));
        r.now = LocalDateTime.ofInstant(now, clock.getZone());
        // 查询不存在的资格时 Lua 不创建，返回的是按请求算出来的值
        boolean exists = pttl != -2 && !(OP_GET.equals(op) && oldPttl == -2);
        r.info = new QuotaInfo(spec.getName(), suffix, exists, total, used, rule,
                expireAt(nowMs, pttl));
        return r;
    }

    /** Lua 返回的数字都是整数（Long） */
    private static long num(List<Object> v, int i) {
        return ((Number) v.get(i)).longValue();
    }

    /** 剩余毫秒 → 到期时间；-1 永不过期、-2 不存在都返回 null */
    private LocalDateTime expireAt(long nowMs, long pttl) {
        return pttl < 0 ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs + pttl), clock.getZone());
    }

    static void check(Result r, long amount) {
        QuotaInfo i = r.info;
        String who = "资格「" + i.getName() + (i.getSuffix().isEmpty() ? "" : ":" + i.getSuffix()) + "」";
        switch (r.code) {
            case OK:
                return;
            case NOT_ENOUGH:
                throw new QuotaException(Reason.NOT_ENOUGH, who + "不足：可用 " + i.getAvailable() + "，需要 " + amount, i);
            case EXPIRED:
                throw new QuotaException(Reason.EXPIRED, who + "已过期（" + i.getRule() + "）", i);
            case NOT_FOUND:
                throw new QuotaException(Reason.NOT_FOUND, who + "不存在", null);
            case INVALID:
                throw new QuotaException(Reason.INVALID, who + "数量不正确：" + amount + "（总资格 " + i.getTotal()
                        + "，已用 " + i.getUsed() + "）", i);
            default:
                throw new IllegalStateException(who + "未知结果 " + r.code);
        }
    }

    // ---------------- 日志 ----------------

    /**
     * 按发生顺序组装日志并发布：创建 → 总资格变了 → 有效期变了 → 本次操作（成功时）。
     * 创建、同步总资格和有效期在操作失败（比如可用不足）时也已经生效，照样记日志。
     *
     * @param field 本次操作改了什么；查询传 null
     */
    private void publish(Result r, QuotaLogField field, long amount, String bizId, String remark) {
        QuotaInfo i = r.info;
        List<QuotaLog> logs = new ArrayList<QuotaLog>(3);
        LocalDateTime expireNow = i.getExpireAt();
        if (r.created) {
            logs.add(log(r, QuotaLogType.CREATE, null, i.getTotal(), 0, i.getTotal(), 0, 0, null, expireNow, "",
                    "首次请求创建（" + i.getRule() + "）"));
        }
        if (r.totalChanged) {
            logs.add(log(r, QuotaLogType.UPDATE, QuotaLogField.TOTAL, i.getTotal() - r.totalBefore, r.totalBefore,
                    i.getTotal(), r.usedBefore, r.usedBefore, r.expireBefore, r.ruleChanged ? r.expireBefore : expireNow,
                    "", "总资格 " + r.totalBefore + " → " + i.getTotal()));
        }
        if (r.ruleChanged) {
            logs.add(log(r, QuotaLogType.UPDATE, QuotaLogField.EXPIRE, 0, i.getTotal(), i.getTotal(), r.usedBefore,
                    r.usedBefore, r.expireBefore, r.code == EXPIRED ? null : expireNow, "",
                    "有效期 " + r.ruleBefore + " → " + i.getRule()));
        }
        if (field != null && r.code == OK) {
            logs.add(log(r, QuotaLogType.UPDATE, field, amount, i.getTotal(), i.getTotal(), r.usedBefore, i.getUsed(),
                    expireNow, expireNow, bizId, remark));
        }
        doPublish(logs);
    }

    private void doPublish(List<QuotaLog> logs) {
        if (logs.isEmpty()) {
            return;
        }
        try {
            publisher.publish(logs);
        } catch (RuntimeException e) {
            // 资格已经改了，日志发不出去也不能让业务失败；内容打出来方便补录
            log.error("资格日志发布失败：{}", logs, e);
        }
    }

    private static QuotaLog log(Result r, QuotaLogType type, QuotaLogField field, long amount, long totalBefore,
                                long totalAfter, long usedBefore, long usedAfter, LocalDateTime expireBefore,
                                LocalDateTime expireAfter, String bizId, String remark) {
        return new QuotaLog(null, r.info.getName(), r.info.getSuffix(), type, field, amount, totalBefore, totalAfter,
                usedBefore, usedAfter, expireBefore, expireAfter, nvl(bizId), nvl(remark), r.now);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
