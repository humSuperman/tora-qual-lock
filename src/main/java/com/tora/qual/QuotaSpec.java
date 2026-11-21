package com.tora.qual;

import java.util.Objects;

/**
 * 一次资格请求带的规格：资格名 + 总资格 + 有效期规则。
 * 每次请求都要带上：总资格和 Redis 里存的不一样时以这次的为准（已用不变），规则不一样时重新设置过期时间，都会记日志。
 * <pre>{@code
 * QuotaSpec spec = QuotaSpec.of("lottery:prize", 7, ExpireRule.naturalDay());
 * quotaService.consume(spec, userId);
 * }</pre>
 */
public final class QuotaSpec {

    private final String name;
    private final long total;
    private final ExpireRule rule;

    public QuotaSpec(String name, long total, ExpireRule rule) {
        Objects.requireNonNull(name, "资格名不能为空");
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException("资格名不能为空");
        }
        if (total < 0) {
            throw new IllegalArgumentException("总资格不能小于 0");
        }
        this.name = name;
        this.total = total;
        this.rule = rule == null ? ExpireRule.never() : rule;
    }

    public static QuotaSpec of(String name, long total, ExpireRule rule) {
        return new QuotaSpec(name, total, rule);
    }

    /** 永不过期 */
    public static QuotaSpec of(String name, long total) {
        return new QuotaSpec(name, total, ExpireRule.never());
    }

    public String getName() {
        return name;
    }

    public long getTotal() {
        return total;
    }

    public ExpireRule getRule() {
        return rule;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof QuotaSpec)) {
            return false;
        }
        QuotaSpec s = (QuotaSpec) o;
        return s.name.equals(name) && s.total == total && s.rule.equals(rule);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, total, rule);
    }

    @Override
    public String toString() {
        return "QuotaSpec{" + name + ", total=" + total + ", rule=" + rule + "}";
    }
}
