package com.tora.qual.log;

import java.util.List;

/** 资格变更日志的发布：资格操作只负责发出去，写库由监听方处理（默认实现是 Redis Stream） */
public interface QuotaLogPublisher {

    void publish(List<QuotaLog> logs);
}
