package com.tora.qual;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpireRuleTest {

    static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
    /** 2026-11-21 周六 14:35:20 */
    static final Instant NOW = LocalDateTime.of(2026, 11, 21, 14, 35, 20).atZone(ZONE).toInstant();

    private static LocalDateTime exp(ExpireRule r, Instant now) {
        long ms = r.expireAt(now, ZONE);
        return ms == 0 ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZONE);
    }

    private static LocalDateTime exp(ExpireRule r) {
        return exp(r, NOW);
    }

    @Test
    void 滚动时长从创建时间往后算() {
        assertThat(exp(ExpireRule.minutes(30))).isEqualTo(LocalDateTime.of(2026, 11, 21, 15, 5, 20));
        assertThat(exp(ExpireRule.hours(24))).isEqualTo(LocalDateTime.of(2026, 11, 22, 14, 35, 20));
        assertThat(exp(ExpireRule.days(7))).isEqualTo(LocalDateTime.of(2026, 11, 28, 14, 35, 20));
        assertThat(exp(ExpireRule.weeks(2))).isEqualTo(LocalDateTime.of(2026, 12, 5, 14, 35, 20));
        assertThat(exp(ExpireRule.months(1))).isEqualTo(LocalDateTime.of(2026, 12, 21, 14, 35, 20));
    }

    @Test
    void 自然周期按日历整点切换() {
        assertThat(exp(ExpireRule.naturalHour())).isEqualTo(LocalDateTime.of(2026, 11, 21, 15, 0));
        assertThat(exp(ExpireRule.naturalDay())).isEqualTo(LocalDateTime.of(2026, 11, 22, 0, 0));
        assertThat(exp(ExpireRule.naturalWeek())).isEqualTo(LocalDateTime.of(2026, 11, 23, 0, 0));
        assertThat(exp(ExpireRule.naturalMonth())).isEqualTo(LocalDateTime.of(2026, 12, 1, 0, 0));
    }

    @Test
    void 月底加一个月_周一当天的自然周() {
        Instant jan31 = LocalDateTime.of(2027, 1, 31, 10, 0).atZone(ZONE).toInstant();
        assertThat(exp(ExpireRule.months(1), jan31)).isEqualTo(LocalDateTime.of(2027, 2, 28, 10, 0));
        Instant monday = LocalDateTime.of(2026, 11, 23, 0, 0).atZone(ZONE).toInstant();
        assertThat(exp(ExpireRule.naturalWeek(), monday)).isEqualTo(LocalDateTime.of(2026, 11, 30, 0, 0));
    }

    @Test
    void 永不过期和指定时间() {
        assertThat(exp(ExpireRule.never())).isNull();
        LocalDateTime at = LocalDateTime.of(2026, 12, 31, 23, 59, 59);
        assertThat(exp(ExpireRule.at(at, ZONE))).isEqualTo(at);
        assertThat(ExpireRule.days(1).getType()).isEqualTo(ExpireRule.Type.DAYS);
    }

    @Test
    void 文本格式来回转换() {
        ExpireRule[] rules = {ExpireRule.never(), ExpireRule.at(LocalDateTime.of(2027, 1, 1, 0, 0), ZONE),
                ExpireRule.at(LocalDateTime.of(2027, 1, 1, 0, 0)), ExpireRule.minutes(5), ExpireRule.hours(24),
                ExpireRule.days(3), ExpireRule.weeks(1), ExpireRule.months(6), ExpireRule.naturalHour(),
                ExpireRule.naturalDay(), ExpireRule.naturalWeek(), ExpireRule.naturalMonth()};
        for (ExpireRule r : rules) {
            assertThat(ExpireRule.parse(r.format())).isEqualTo(r).hasSameHashCodeAs(r);
            assertThat(r.toString()).isEqualTo(r.format());
        }
        assertThat(ExpireRule.hours(24).format()).isEqualTo("HOURS:24");
        assertThat(ExpireRule.parse(" NATURAL_DAY ")).isEqualTo(ExpireRule.naturalDay());
        assertThat(ExpireRule.parse(null)).isEqualTo(ExpireRule.never());
        assertThat(ExpireRule.parse(" ")).isEqualTo(ExpireRule.never());
        assertThat(ExpireRule.never()).isNotEqualTo("NEVER");
    }

    @Test
    void 时长要大于0() {
        assertThatThrownBy(() -> ExpireRule.minutes(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpireRule.months(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
