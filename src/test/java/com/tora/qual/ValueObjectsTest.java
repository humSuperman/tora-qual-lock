package com.tora.qual;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** QuotaSpec、QuotaInfo、ExpireRule 的 equals / toString */
class ValueObjectsTest {

    @Test
    void 资格规格相等() {
        QuotaSpec a = QuotaSpec.of("prize", 7, ExpireRule.naturalDay());
        assertThat(a).isEqualTo(QuotaSpec.of("prize", 7, ExpireRule.naturalDay()))
                .hasSameHashCodeAs(QuotaSpec.of("prize", 7, ExpireRule.naturalDay()))
                .isNotEqualTo(QuotaSpec.of("other", 7, ExpireRule.naturalDay()))
                .isNotEqualTo(QuotaSpec.of("prize", 8, ExpireRule.naturalDay()))
                .isNotEqualTo(QuotaSpec.of("prize", 7, ExpireRule.naturalWeek()))
                .isNotEqualTo("prize");
    }

    @Test
    void 有效期规则相等() {
        assertThat(ExpireRule.hours(2)).isEqualTo(ExpireRule.parse("HOURS:2"))
                .isNotEqualTo(ExpireRule.hours(3))
                .isNotEqualTo(ExpireRule.days(2))
                .isNotEqualTo("HOURS:2");
    }

    @Test
    void 资格信息toString() {
        assertThat(new QuotaInfo("prize", null, true, 7, 1, ExpireRule.never(), null).toString())
                .startsWith("QuotaInfo{prize, total=7, used=1");
        assertThat(new QuotaInfo("prize", "u1", true, 7, 1, ExpireRule.never(), null).toString())
                .startsWith("QuotaInfo{prize:u1, total=7");
    }
}
