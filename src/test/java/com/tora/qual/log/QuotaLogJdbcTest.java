package com.tora.qual.log;

import com.tora.qual.Migrations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 每种数据库的迁移脚本和分页写法，在 H2 对应的兼容模式下执行。
 * H2 只是模拟，正式上线前请在真实数据库上 migrate up 一次确认。
 */
class QuotaLogJdbcTest {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 21, 10, 0, 0, 123000000);

    static QuotaLog log(String name, String suffix, QuotaLogType type, QuotaLogField field, long amount, String biz,
                        LocalDateTime at) {
        return new QuotaLog(null, name, suffix, type, field, amount, 7, 7, 0, 1, null, at.plusDays(1), biz, "备注", at);
    }

    /** 30 条：prize 共用 10 条、prize:u1 15 条（每小时一条）、coupon:u1 5 条 */
    static List<QuotaLog> sample() {
        List<QuotaLog> l = new ArrayList<QuotaLog>();
        for (int i = 0; i < 10; i++) {
            l.add(log("prize", null, i == 0 ? QuotaLogType.CREATE : QuotaLogType.UPDATE,
                    i == 0 ? null : QuotaLogField.AVAILABLE, -1, "s" + i, T0.plusMinutes(i)));
        }
        for (int i = 0; i < 15; i++) {
            QuotaLogField f = i == 0 ? null : (i % 5 == 0 ? QuotaLogField.TOTAL : QuotaLogField.AVAILABLE);
            l.add(log("prize", "u1", i == 0 ? QuotaLogType.CREATE : QuotaLogType.UPDATE, f, -1, "o" + i, T0.plusHours(i)));
        }
        for (int i = 0; i < 4; i++) {
            l.add(log("coupon", "u1", QuotaLogType.UPDATE, QuotaLogField.EXPIRE, 0, "", T0.plusMinutes(i)));
        }
        l.add(log("coupon", "u1", QuotaLogType.DELETE, null, -3, null, T0.plusMinutes(9)));
        return l;
    }

    static List<String> bizIds(List<QuotaLog> l) {
        List<String> out = new ArrayList<String>();
        for (QuotaLog x : l) {
            out.add(x.getBizId());
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"mysql,MySQL", "mariadb,MariaDB", "postgresql,PostgreSQL", "oracle,Oracle", "sqlserver,MSSQLServer", "h2,''"})
    void 各数据库_迁移建表_写入_条件分页查询(String platform, String mode) {
        DataSource ds = Migrations.up(mode, platform);
        JdbcQuotaLogStore store = new JdbcQuotaLogStore(new JdbcTemplate(ds), "tora_qual_log", platform);
        store.saveAll(sample());
        store.saveAll(Collections.<QuotaLog>emptyList());

        // 文本列没有值存 ''，不存 NULL；Oracle 会把 '' 当成 NULL，单独看
        JdbcTemplate jt = new JdbcTemplate(ds);
        String nulls = "SELECT COUNT(*) FROM tora_qual_log WHERE suffix IS NULL OR log_field IS NULL OR biz_id IS NULL"
                + " OR remark IS NULL";
        String blanks = "SELECT COUNT(*) FROM tora_qual_log WHERE suffix = '' AND log_field = ''";
        if ("oracle".equals(platform)) {
            assertThat(jt.queryForObject(nulls, Long.class)).isEqualTo(16);
        } else {
            assertThat(jt.queryForObject(nulls, Long.class)).isZero();
            // 共用资格的 CREATE 那条：后缀、更新内容都是 ''
            assertThat(jt.queryForObject(blanks, Long.class)).isEqualTo(1);
            // 建表默认值是 ''：只给必填列也能插入
            jt.update("INSERT INTO tora_qual_log (quota_name, log_type, created_at) VALUES ('d', 'CREATE', ?)",
                    java.sql.Timestamp.valueOf(T0));
            assertThat(jt.queryForObject(nulls, Long.class)).isZero();
            jt.update("DELETE FROM tora_qual_log WHERE quota_name = 'd'");
        }

        QuotaLogPage p1 = store.query(new QuotaLogQuery().name("prize").suffix("u1").page(1, 4));
        assertThat(p1.getTotal()).isEqualTo(15);
        assertThat(bizIds(p1.getList())).containsExactly("o14", "o13", "o12", "o11");
        assertThat(bizIds(store.query(new QuotaLogQuery().name("prize").suffix("u1").page(4, 4)).getList()))
                .containsExactly("o2", "o1", "o0");
        assertThat(store.query(new QuotaLogQuery().name("prize").suffix("u1").page(5, 4)).getList()).isEmpty();

        QuotaLogPage shared = store.query(new QuotaLogQuery().name("prize").sharedOnly().page(1, 100));
        assertThat(shared.getTotal()).isEqualTo(10);
        for (QuotaLog l : shared.getList()) {
            assertThat(l.getSuffix()).isEmpty();
        }

        QuotaLogPage range = store.query(new QuotaLogQuery().name("prize").suffix("u1")
                .from(T0.plusHours(2)).to(T0.plusHours(5)).types(QuotaLogType.UPDATE));
        assertThat(bizIds(range.getList())).containsExactly("o5", "o4", "o3", "o2");
        assertThat(store.query(new QuotaLogQuery().name("prize").suffix("u1").types(QuotaLogType.CREATE)).getTotal())
                .isEqualTo(1);
        assertThat(store.query(new QuotaLogQuery().suffix("u1").types(QuotaLogType.CREATE, QuotaLogType.DELETE))
                .getTotal()).isEqualTo(2);
        assertThat(store.query(new QuotaLogQuery().name("prize").fields(QuotaLogField.TOTAL)).getTotal()).isEqualTo(2);
        assertThat(store.query(new QuotaLogQuery().fields(QuotaLogField.TOTAL, QuotaLogField.EXPIRE)).getTotal())
                .isEqualTo(6);

        List<QuotaLog> biz = store.query(new QuotaLogQuery().bizId("o7")).getList();
        assertThat(biz).hasSize(1);
        QuotaLog l = biz.get(0);
        assertThat(l.getId()).isNotNull();
        assertThat(l.getName()).isEqualTo("prize");
        assertThat(l.getSuffix()).isEqualTo("u1");
        assertThat(l.getField()).isEqualTo(QuotaLogField.AVAILABLE);
        assertThat(l.getCreatedAt()).isEqualTo(T0.plusHours(7));
        assertThat(l.getExpireAfter()).isEqualTo(T0.plusHours(7).plusDays(1));
        assertThat(l.getExpireBefore()).isNull();
        assertThat(l.getRemark()).isEqualTo("备注");
        assertThat(store.query(new QuotaLogQuery()).getTotal()).isEqualTo(30);
        assertThat(store.query(new QuotaLogQuery().name("coupon").suffix("u1").types(QuotaLogType.DELETE)).getList()
                .get(0).getBizId()).isEmpty();
        assertThat(store.query(new QuotaLogQuery().name("none")).getList()).isEmpty();
    }

    @Test
    void 自动识别数据库类型_分页写法() throws Exception {
        DataSource ds = Migrations.up("", "h2");
        assertThat(QuotaDatabase.detect(ds)).isEqualTo("h2");
        JdbcQuotaLogStore store = new JdbcQuotaLogStore(new JdbcTemplate(ds), "tora_qual_log", null);
        store.saveAll(sample());
        assertThat(store.query(new QuotaLogQuery().page(3, 10)).getList()).hasSize(10);

        assertThat(QuotaDatabase.pageClause("mysql")).isEqualTo(" LIMIT ? OFFSET ?");
        assertThat(QuotaDatabase.pageParams("mysql", 20, 10)).containsExactly(10, 20L);
        assertThat(QuotaDatabase.pageClause("oracle")).isEqualTo(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        assertThat(QuotaDatabase.pageParams("sqlserver", 20, 10)).containsExactly(20L, 10);
        assertThat(QuotaDatabase.normalize(" MySQL ")).isEqualTo("mysql");
        assertThat(QuotaDatabase.normalize("")).isNull();
    }

    @Test
    void 迁移脚本有回滚_表名一致() throws Exception {
        for (String db : new String[]{"mysql", "mariadb", "postgresql", "oracle", "sqlserver", "h2"}) {
            String s = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("migrations", db,
                    "20261121000000_create_tora_qual_log.sql")), "UTF-8");
            assertThat(s).as(db).contains("-- //@UNDO").contains("DROP TABLE tora_qual_log").contains("CREATE TABLE tora_qual_log");
        }
        assertThat(Migrations.statements("-- 注释\nCREATE TABLE a (x INT);\n\nCREATE INDEX i ON a (x);\n-- //@UNDO\nDROP TABLE a;\n"))
                .containsExactly("CREATE TABLE a (x INT)", "CREATE INDEX i ON a (x)");
    }

    @Test
    void 参数校验() {
        DataSource ds = Migrations.h2("");
        assertThatThrownBy(() -> new JdbcQuotaLogStore(new JdbcTemplate(ds), "x; drop table y", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaLogQuery().page(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaLogQuery().page(1, 1001)).isInstanceOf(IllegalArgumentException.class);
        QuotaLogQuery q = new QuotaLogQuery().suffix("u1").sharedOnly();
        assertThat(q.getSuffix()).isNull();
        assertThat(q.isSharedOnly()).isTrue();
        assertThat(q.suffix("u2").isSharedOnly()).isFalse();
        assertThat(q.types().getTypes()).isEmpty();
        assertThat(q.fields().getFields()).isEmpty();
        assertThat(QuotaLogPage.empty(q).getTotal()).isZero();
    }

    @Test
    void 消息编码来回转换() {
        QuotaLog l = log("prize", "u1", QuotaLogType.UPDATE, QuotaLogField.TOTAL, 3, "o1", T0);
        assertThat(QuotaLogCodec.decode(QuotaLogCodec.encode(l))).isEqualTo(l);
        QuotaLog empty = new QuotaLog(null, "prize", null, QuotaLogType.CREATE, null, 7, 0, 7, 0, 0, null, null, null, null, T0);
        assertThat(QuotaLogCodec.encode(empty)).doesNotContainKeys("suffix", "field", "bizId", "remark", "expireBefore");
        assertThat(QuotaLogCodec.decode(QuotaLogCodec.encode(empty))).isEqualTo(empty).hasSameHashCodeAs(empty);
        assertThat(empty).isNotEqualTo("x");
    }

    @Test
    void 空白条件不参与筛选_页码超出返回空() {
        JdbcQuotaLogStore store = new JdbcQuotaLogStore(new JdbcTemplate(Migrations.up("MySQL", "mysql")),
                "tora_qual_log", "mysql");
        store.saveAll(sample());
        assertThat(store.query(new QuotaLogQuery().name("  ").suffix("").bizId("")).getTotal()).isEqualTo(30);
        QuotaLogPage beyond = store.query(new QuotaLogQuery().page(9, 10));
        assertThat(beyond.getTotal()).isEqualTo(30);
        assertThat(beyond.getList()).isEmpty();
    }
}
