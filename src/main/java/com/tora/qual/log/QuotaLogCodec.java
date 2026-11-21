package com.tora.qual.log;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 日志和 Redis Stream 消息字段之间的转换。空值不放进消息 */
public final class QuotaLogCodec {

    private QuotaLogCodec() {
    }

    public static Map<String, String> encode(QuotaLog l) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", l.getName());
        put(m, "suffix", l.getSuffix());
        m.put("type", l.getType().name());
        put(m, "field", l.getField() == null ? null : l.getField().name());
        m.put("amount", String.valueOf(l.getAmount()));
        m.put("totalBefore", String.valueOf(l.getTotalBefore()));
        m.put("totalAfter", String.valueOf(l.getTotalAfter()));
        m.put("usedBefore", String.valueOf(l.getUsedBefore()));
        m.put("usedAfter", String.valueOf(l.getUsedAfter()));
        put(m, "expireBefore", l.getExpireBefore() == null ? null : l.getExpireBefore().toString());
        put(m, "expireAfter", l.getExpireAfter() == null ? null : l.getExpireAfter().toString());
        put(m, "bizId", l.getBizId());
        put(m, "remark", l.getRemark());
        m.put("createdAt", l.getCreatedAt().toString());
        return m;
    }

    public static QuotaLog decode(Map<?, ?> m) {
        return new QuotaLog(null, str(m, "name"), str(m, "suffix"), QuotaLogType.valueOf(str(m, "type")),
                str(m, "field") == null ? null : QuotaLogField.valueOf(str(m, "field")), num(m, "amount"),
                num(m, "totalBefore"), num(m, "totalAfter"), num(m, "usedBefore"), num(m, "usedAfter"),
                time(m, "expireBefore"), time(m, "expireAfter"), str(m, "bizId"), str(m, "remark"), time(m, "createdAt"));
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isEmpty()) {
            m.put(k, v);
        }
    }

    private static String str(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static long num(Map<?, ?> m, String k) {
        String v = str(m, k);
        return v == null ? 0 : Long.parseLong(v);
    }

    private static LocalDateTime time(Map<?, ?> m, String k) {
        String v = str(m, k);
        return v == null ? null : LocalDateTime.parse(v);
    }
}
