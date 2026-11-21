package com.tora.qual.log;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * 日志查询条件，不设置的条件不加。
 * <pre>{@code
 * QuotaLogQuery q = new QuotaLogQuery().name("lottery:prize").suffix("1001")
 *         .from(LocalDateTime.of(2026, 11, 1, 0, 0)).to(LocalDateTime.now())
 *         .types(QuotaLogType.UPDATE).page(1, 20);
 * }</pre>
 */
public class QuotaLogQuery {

    public static final int MAX_PAGE_SIZE = 1000;

    private String name;
    private String suffix;
    private boolean sharedOnly;
    private LocalDateTime from;
    private LocalDateTime to;
    private Set<QuotaLogType> types = EnumSet.noneOf(QuotaLogType.class);
    private Set<QuotaLogField> fields = EnumSet.noneOf(QuotaLogField.class);
    private String bizId;
    private int pageNum = 1;
    private int pageSize = 20;

    /** 资格名 */
    public QuotaLogQuery name(String name) {
        this.name = name;
        return this;
    }

    /** 后缀（用户 id 等） */
    public QuotaLogQuery suffix(String suffix) {
        this.suffix = suffix;
        this.sharedOnly = false;
        return this;
    }

    /** 只查共用资格（没有后缀的） */
    public QuotaLogQuery sharedOnly() {
        this.sharedOnly = true;
        this.suffix = null;
        return this;
    }

    /** 记录时间 >= from */
    public QuotaLogQuery from(LocalDateTime from) {
        this.from = from;
        return this;
    }

    /** 记录时间 <= to */
    public QuotaLogQuery to(LocalDateTime to) {
        this.to = to;
        return this;
    }

    /** 类型：创建 / 更新 / 删除，可以多选 */
    public QuotaLogQuery types(QuotaLogType... types) {
        this.types = types.length == 0 ? EnumSet.noneOf(QuotaLogType.class) : EnumSet.of(types[0], types);
        return this;
    }

    /** 更新了什么：总资格 / 可用资格 / 有效期，可以多选 */
    public QuotaLogQuery fields(QuotaLogField... fields) {
        this.fields = fields.length == 0 ? EnumSet.noneOf(QuotaLogField.class) : EnumSet.of(fields[0], fields);
        return this;
    }

    /** 业务单号 */
    public QuotaLogQuery bizId(String bizId) {
        this.bizId = bizId;
        return this;
    }

    /** 页码从 1 开始；每页最多 1000 条 */
    public QuotaLogQuery page(int pageNum, int pageSize) {
        if (pageNum < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("页码从 1 开始，每页 1 ~ " + MAX_PAGE_SIZE + " 条");
        }
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        return this;
    }

    public String getName() {
        return name;
    }

    public String getSuffix() {
        return suffix;
    }

    public boolean isSharedOnly() {
        return sharedOnly;
    }

    public LocalDateTime getFrom() {
        return from;
    }

    public LocalDateTime getTo() {
        return to;
    }

    public Set<QuotaLogType> getTypes() {
        return types;
    }

    public Set<QuotaLogField> getFields() {
        return fields;
    }

    public String getBizId() {
        return bizId;
    }

    public int getPageNum() {
        return pageNum;
    }

    public int getPageSize() {
        return pageSize;
    }

    public long offset() {
        return (long) (pageNum - 1) * pageSize;
    }
}
