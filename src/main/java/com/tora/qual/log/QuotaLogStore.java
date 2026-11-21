package com.tora.qual.log;

import java.util.List;

/** 资格变更日志的存储（数据库） */
public interface QuotaLogStore {

    void saveAll(List<QuotaLog> logs);

    QuotaLogPage query(QuotaLogQuery query);
}
