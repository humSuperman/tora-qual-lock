package com.tora.qual;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/**
 * 资格的有效期规则，决定 Redis key 的 TTL。
 * <p>
 * 滚动时长（从创建时间往后算）：{@link #minutes(int)}、{@link #hours(int)}、{@link #days(int)}、{@link #weeks(int)}、
 * {@link #months(int)}，例如 hours(24) 是创建后 24 小时。
 * <p>
 * 自然周期（按日历整点切换）：{@link #naturalHour()} 下一个整点（14:35 创建 15:00 到期）、{@link #naturalDay()} 0 点、
 * {@link #naturalWeek()} 周一 0 点、{@link #naturalMonth()} 1 号 0 点。
 * <p>
 * 到期后 Redis 删掉 key，下一次请求重新创建，相当于开新一期。
 * {@link #never()} 永不过期；{@link #at(LocalDateTime)} 到指定时间过期，过了时间请求报 EXPIRED。
 */
public final class ExpireRule {

    public enum Type {
        /** 永不过期 */
        NEVER {
            @Override
            long expireAt(ZonedDateTime t, long value) {
                return 0;
            }
        },
        /** 指定时间过期 */
        AT {
            @Override
            long expireAt(ZonedDateTime t, long value) {
                return value;
            }
        },
        /** N 分钟 */
        MINUTES {
            @Override
            long expireAt(ZonedDateTime t, long value) {
                return millis(t.plusMinutes(value));
            }
        },
        /** N 小时 */
        HOURS {
            @Override
            long expireAt(ZonedDateTime t, long value) {
                return millis(t.plusHours(value));
            }
        },
        /** N 天 */
        DAYS {
            @Override
            long expireAt(ZonedDateTime t, long value) {
                return millis(t.plusDays(value));
            }
        },
        /** N 周 */
        WEEKS {
            @Override
            long expireAt(ZonedDateTime t, long value) {
                return millis(t.plusWeeks(value));
            }
        },
        /** N 个月 */
        MONTHS {
            @Override
            long expireAt(ZonedDateTime t, long value) {
                return millis(t.plusMonths(value));
            }
        },
        /** 自然小时：下一个整点 */
        NATURAL_HOUR {
            @Override
            long expireAt(ZonedDateTime t, long value) {
                return millis(t.truncatedTo(ChronoUnit.HOURS).plusHours(1));
            }
        },
        /** 自然天：第二天 0 点 */
        NATURAL_DAY {
            @Override
            long expireAt(ZonedDateTime t, long value) {
                return millis(t.toLocalDate().plusDays(1).atStartOfDay(t.getZone()));
            }
        },
        /** 自然周：下周一 0 点 */
        NATURAL_WEEK {
            @Override
            long expireAt(ZonedDateTime t, long value) {
                return millis(t.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(t.getZone()));
            }
        },
        /** 自然月：下月 1 号 0 点 */
        NATURAL_MONTH {
            @Override
            long expireAt(ZonedDateTime t, long value) {
                return millis(t.toLocalDate().with(TemporalAdjusters.firstDayOfNextMonth()).atStartOfDay(t.getZone()));
            }
        };

        /** 从 t 开始算的到期时间（毫秒），0 表示永不过期 */
        abstract long expireAt(ZonedDateTime t, long value);

        private static long millis(ZonedDateTime t) {
            return t.toInstant().toEpochMilli();
        }
    }

    private final Type type;
    /** AT：到期时间（毫秒）；滚动时长：数量；其他为 0 */
    private final long value;

    private ExpireRule(Type type, long value) {
        this.type = type;
        this.value = value;
    }

    public static ExpireRule never() {
        return new ExpireRule(Type.NEVER, 0);
    }

    public static ExpireRule at(LocalDateTime time) {
        return at(time, ZoneId.systemDefault());
    }

    public static ExpireRule at(LocalDateTime time, ZoneId zone) {
        return new ExpireRule(Type.AT, time.atZone(zone).toInstant().toEpochMilli());
    }

    public static ExpireRule minutes(int n) {
        return rolling(Type.MINUTES, n);
    }

    public static ExpireRule hours(int n) {
        return rolling(Type.HOURS, n);
    }

    public static ExpireRule days(int n) {
        return rolling(Type.DAYS, n);
    }

    public static ExpireRule weeks(int n) {
        return rolling(Type.WEEKS, n);
    }

    public static ExpireRule months(int n) {
        return rolling(Type.MONTHS, n);
    }

    public static ExpireRule naturalHour() {
        return new ExpireRule(Type.NATURAL_HOUR, 0);
    }

    public static ExpireRule naturalDay() {
        return new ExpireRule(Type.NATURAL_DAY, 0);
    }

    public static ExpireRule naturalWeek() {
        return new ExpireRule(Type.NATURAL_WEEK, 0);
    }

    public static ExpireRule naturalMonth() {
        return new ExpireRule(Type.NATURAL_MONTH, 0);
    }

    private static ExpireRule rolling(Type type, int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("时长要大于 0");
        }
        return new ExpireRule(type, n);
    }

    public Type getType() {
        return type;
    }

    /** 假如从 now 开始，什么时候到期（毫秒）；永不过期返回 0 */
    public long expireAt(Instant now, ZoneId zone) {
        return type.expireAt(now.atZone(zone), value);
    }

    /** 存进 Redis、写在注解里的文本：NEVER、AT:毫秒、HOURS:24、NATURAL_DAY …… */
    public String format() {
        return value == 0 ? type.name() : type.name() + ":" + value;
    }

    public static ExpireRule parse(String s) {
        if (s == null || s.trim().isEmpty()) {
            return never();
        }
        String[] p = s.trim().split(":", 2);
        return new ExpireRule(Type.valueOf(p[0]), p.length > 1 ? Long.parseLong(p[1]) : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ExpireRule)) {
            return false;
        }
        ExpireRule r = (ExpireRule) o;
        return r.type == type && r.value == value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value);
    }

    @Override
    public String toString() {
        return format();
    }
}
