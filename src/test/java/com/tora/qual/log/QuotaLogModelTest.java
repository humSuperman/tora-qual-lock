package com.tora.qual.log;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 日志对象、编解码、查询条件、数据库识别 */
class QuotaLogModelTest {

    static final LocalDateTime T = LocalDateTime.of(2026, 11, 21, 10, 0);
    static final LocalDateTime T2 = T.plusHours(1);

    private static QuotaLog log(Long id, String name, String suffix, QuotaLogType type, QuotaLogField field,
                                long amount, long tb, long ta, long ub, long ua, LocalDateTime eb, LocalDateTime ea,
                                String biz, String remark, LocalDateTime at) {
        return new QuotaLog(id, name, suffix, type, field, amount, tb, ta, ub, ua, eb, ea, biz, remark, at);
    }

    @Test
    void 日志相等不看主键_任一字段不同就不相等() {
        QuotaLog a = log(1L, "n", "s", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 1, 2, 3, 4, 5, T, T, "b", "r", T);
        assertThat(a).isEqualTo(log(2L, "n", "s", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 1, 2, 3, 4, 5, T, T, "b", "r", T))
                .hasSameHashCodeAs(log(null, "n", "s", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 1, 2, 3, 4, 5, T, T, "b", "r", T))
                .isNotEqualTo("n");
        List<QuotaLog> diffs = Arrays.asList(
                log(1L, "x", "s", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 1, 2, 3, 4, 5, T, T, "b", "r", T),
                log(1L, "n", "x", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 1, 2, 3, 4, 5, T, T, "b", "r", T),
                log(1L, "n", "s", QuotaLogType.CREATE, QuotaLogField.TOTAL, 1, 2, 3, 4, 5, T, T, "b", "r", T),
                log(1L, "n", "s", QuotaLogType.UPDATE, QuotaLogField.EXPIRE, 1, 2, 3, 4, 5, T, T, "b", "r", T),
                log(1L, "n", "s", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 9, 2, 3, 4, 5, T, T, "b", "r", T),
                log(1L, "n", "s", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 1, 9, 3, 4, 5, T, T, "b", "r", T),
                log(1L, "n", "s", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 1, 2, 9, 4, 5, T, T, "b", "r", T),
                log(1L, "n", "s", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 1, 2, 3, 9, 5, T, T, "b", "r", T),
                log(1L, "n", "s", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 1, 2, 3, 4, 9, T, T, "b", "r", T),
                log(1L, "n", "s", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 1, 2, 3, 4, 5, T2, T, "b", "r", T),
                log(1L, "n", "s", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 1, 2, 3, 4, 5, T, T2, "b", "r", T),
                log(1L, "n", "s", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 1, 2, 3, 4, 5, T, T, "x", "r", T),
                log(1L, "n", "s", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 1, 2, 3, 4, 5, T, T, "b", "x", T),
                log(1L, "n", "s", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 1, 2, 3, 4, 5, T, T, "b", "r", T2));
        for (QuotaLog d : diffs) {
            assertThat(a).isNotEqualTo(d);
        }
        assertThat(a.toString()).startsWith("QuotaLog{n:s UPDATE/TOTAL amount=1");
        assertThat(log(null, "n", null, QuotaLogType.CREATE, null, 7, 0, 7, 0, 0, null, null, null, null, T).toString())
                .startsWith("QuotaLog{n CREATE amount=7");
    }

    @Test
    void 解码时缺少数字字段按0算() {
        Map<String, String> m = new HashMap<String, String>();
        m.put("name", "n");
        m.put("type", "CREATE");
        m.put("createdAt", T.toString());
        QuotaLog l = QuotaLogCodec.decode(m);
        assertThat(l.getAmount()).isZero();
        assertThat(l.getTotalAfter()).isZero();
        assertThat(l.getField()).isNull();
    }

    @Test
    void 分页参数校验() {
        assertThat(new QuotaLogQuery().page(2, 1000).offset()).isEqualTo(1000);
        assertThatThrownBy(() -> new QuotaLogQuery().page(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaLogQuery().page(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaLogQuery().page(1, 1001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 识别数据库_产品名不认识就看URL_都不认识报错() throws SQLException {
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("MySQL");
        assertThat(QuotaDatabase.detect(md)).isEqualTo("mysql");

        when(md.getDatabaseProductName()).thenReturn("SomeFork");
        when(md.getURL()).thenReturn("jdbc:postgresql://db/x");
        assertThat(QuotaDatabase.detect(md)).isEqualTo("postgresql");

        when(md.getURL()).thenReturn("jdbc:whatever://db/x");
        assertThatThrownBy(() -> QuotaDatabase.detect(md)).hasMessageContaining("tora.qual.log.platform");
    }

    @Test
    void 识别数据库时连不上() throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("连不上"));
        assertThatThrownBy(() -> QuotaDatabase.detect(ds)).hasMessage("识别数据库类型失败").hasRootCauseMessage("连不上");

        Connection con = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("H2");
        when(con.getMetaData()).thenReturn(md);
        DataSource ok = mock(DataSource.class);
        when(ok.getConnection()).thenReturn(con);
        assertThat(QuotaDatabase.detect(ok)).isEqualTo("h2");
    }
}
