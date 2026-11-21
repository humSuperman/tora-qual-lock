package com.tora.qual;

import com.tora.qual.log.QuotaLog;
import com.tora.qual.log.QuotaLogPublisher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 测试用：发布的日志记在内存里 */
public class MemoryPublisher implements QuotaLogPublisher {

    public final List<QuotaLog> logs = Collections.synchronizedList(new ArrayList<QuotaLog>());

    @Override
    public void publish(List<QuotaLog> list) {
        logs.addAll(list);
    }

    public List<QuotaLog> of(String name, String suffix) {
        List<QuotaLog> out = new ArrayList<QuotaLog>();
        synchronized (logs) {
            for (QuotaLog l : logs) {
                if (l.getName().equals(name) && (suffix == null ? "" : suffix).equals(l.getSuffix())) {
                    out.add(l);
                }
            }
        }
        return out;
    }
}
