package com.tora.qual.log;

import java.util.Collections;
import java.util.List;

/** 日志分页结果，list 新的在前 */
public final class QuotaLogPage {

    private final List<QuotaLog> list;
    private final long total;
    private final int pageNum;
    private final int pageSize;

    public QuotaLogPage(List<QuotaLog> list, long total, int pageNum, int pageSize) {
        this.list = list;
        this.total = total;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
    }

    public static QuotaLogPage empty(QuotaLogQuery q) {
        return new QuotaLogPage(Collections.<QuotaLog>emptyList(), 0, q.getPageNum(), q.getPageSize());
    }

    public List<QuotaLog> getList() {
        return list;
    }

    public long getTotal() {
        return total;
    }

    public int getPageNum() {
        return pageNum;
    }

    public int getPageSize() {
        return pageSize;
    }
}
