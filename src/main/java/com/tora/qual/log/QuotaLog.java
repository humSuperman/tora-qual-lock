package com.tora.qual.log;

import java.time.LocalDateTime;
import java.util.Objects;

/** 资格变更日志 */
public final class QuotaLog {

    private final Long id;
    private final String name;
    private final String suffix;
    private final QuotaLogType type;
    private final QuotaLogField field;
    private final long amount;
    private final long totalBefore;
    private final long totalAfter;
    private final long usedBefore;
    private final long usedAfter;
    private final LocalDateTime expireBefore;
    private final LocalDateTime expireAfter;
    private final String bizId;
    private final String remark;
    private final LocalDateTime createdAt;

    public QuotaLog(Long id, String name, String suffix, QuotaLogType type, QuotaLogField field, long amount,
                    long totalBefore, long totalAfter, long usedBefore, long usedAfter, LocalDateTime expireBefore,
                    LocalDateTime expireAfter, String bizId, String remark, LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.suffix = suffix == null ? "" : suffix;
        this.type = type;
        this.field = field;
        this.amount = amount;
        this.totalBefore = totalBefore;
        this.totalAfter = totalAfter;
        this.usedBefore = usedBefore;
        this.usedAfter = usedAfter;
        this.expireBefore = expireBefore;
        this.expireAfter = expireAfter;
        this.bizId = bizId;
        this.remark = remark;
        this.createdAt = createdAt;
    }

    /** 主键（查出来的才有） */
    public Long getId() {
        return id;
    }

    /** 资格名 */
    public String getName() {
        return name;
    }

    /** 后缀；空字符串是共用资格，不会是 null */
    public String getSuffix() {
        return suffix;
    }

    /** 创建 / 更新 / 删除 */
    public QuotaLogType getType() {
        return type;
    }

    /** 更新了什么：总资格 / 可用资格 / 有效期；创建、删除为 null */
    public QuotaLogField getField() {
        return field;
    }

    /** 变化量：创建是总资格，扣减为负、退回为正，改总资格是差值，删除是删除前的可用取负 */
    public long getAmount() {
        return amount;
    }

    public long getTotalBefore() {
        return totalBefore;
    }

    public long getTotalAfter() {
        return totalAfter;
    }

    public long getUsedBefore() {
        return usedBefore;
    }

    public long getUsedAfter() {
        return usedAfter;
    }

    public long getAvailableBefore() {
        return totalBefore - usedBefore;
    }

    public long getAvailableAfter() {
        return totalAfter - usedAfter;
    }

    /** 变更前到期时间；null 永不过期或原来不存在 */
    public LocalDateTime getExpireBefore() {
        return expireBefore;
    }

    /** 变更后到期时间；null 永不过期或已删除 */
    public LocalDateTime getExpireAfter() {
        return expireAfter;
    }

    /** 业务单号 */
    public String getBizId() {
        return bizId;
    }

    public String getRemark() {
        return remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** 除主键外内容一样（测试、去重用） */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof QuotaLog)) {
            return false;
        }
        QuotaLog l = (QuotaLog) o;
        return Objects.equals(name, l.name) && Objects.equals(suffix, l.suffix) && type == l.type && field == l.field
                && amount == l.amount && totalBefore == l.totalBefore && totalAfter == l.totalAfter
                && usedBefore == l.usedBefore && usedAfter == l.usedAfter && Objects.equals(expireBefore, l.expireBefore)
                && Objects.equals(expireAfter, l.expireAfter) && Objects.equals(bizId, l.bizId)
                && Objects.equals(remark, l.remark) && Objects.equals(createdAt, l.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, suffix, type, field, amount, createdAt);
    }

    @Override
    public String toString() {
        return "QuotaLog{" + name + (suffix.isEmpty() ? "" : ":" + suffix) + " " + type + (field == null ? "" : "/" + field)
                + " amount=" + amount + " total " + totalBefore + "→" + totalAfter + " used " + usedBefore + "→"
                + usedAfter + " expire " + expireBefore + "→" + expireAfter + " biz=" + bizId + " remark=" + remark
                + " at " + createdAt + "}";
    }
}
