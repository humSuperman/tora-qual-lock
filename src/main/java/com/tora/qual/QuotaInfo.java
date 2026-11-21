package com.tora.qual;

import java.time.LocalDateTime;

/** 资格的当前状态 */
public final class QuotaInfo {

    private final String name;
    private final String suffix;
    private final boolean exists;
    private final long total;
    private final long used;
    private final ExpireRule rule;
    private final LocalDateTime expireAt;

    public QuotaInfo(String name, String suffix, boolean exists, long total, long used, ExpireRule rule,
                     LocalDateTime expireAt) {
        this.name = name;
        this.suffix = suffix == null ? "" : suffix;
        this.exists = exists;
        this.total = total;
        this.used = used;
        this.rule = rule;
        this.expireAt = expireAt;
    }

    /** 资格名 */
    public String getName() {
        return name;
    }

    /** 后缀（用户 id 等）；空字符串表示所有人共用的资格，不会是 null */
    public String getSuffix() {
        return suffix;
    }

    /** Redis 里是否已经有这条资格（还没用过的，查出来是 false，总资格是请求带的值、已用 0） */
    public boolean isExists() {
        return exists;
    }

    /** 总资格 */
    public long getTotal() {
        return total;
    }

    /** 已用 */
    public long getUsed() {
        return used;
    }

    /** 可用 = 总资格 - 已用。总资格调小后可能是负数，怎么处理由业务决定 */
    public long getAvailable() {
        return total - used;
    }

    /** 已用超过总资格（总资格调小了） */
    public boolean isOverused() {
        return used > total;
    }

    /** 有效期规则 */
    public ExpireRule getRule() {
        return rule;
    }

    /** 到期时间（来自 key 的 TTL）；null 表示永不过期 */
    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    @Override
    public String toString() {
        return "QuotaInfo{" + name + (suffix.isEmpty() ? "" : ":" + suffix) + ", total=" + total + ", used=" + used
                + ", rule=" + rule + ", expireAt=" + expireAt + ", exists=" + exists + "}";
    }
}
