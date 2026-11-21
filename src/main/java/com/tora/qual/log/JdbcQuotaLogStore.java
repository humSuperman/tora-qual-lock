package com.tora.qual.log;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 资格日志存数据库：批量写入、按条件分页查询，各数据库通用 */
public class JdbcQuotaLogStore implements QuotaLogStore {

    private static final String COLUMNS = "id, quota_name, suffix, log_type, log_field, amount, total_before, total_after, "
            + "used_before, used_after, expire_before, expire_after, biz_id, remark, created_at";

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final String table;
    private volatile String platform;

    /**
     * @param jdbc     用宿主项目的数据源建的 JdbcTemplate
     * @param platform 数据库类型，null 时第一次查询自动识别
     */
    public JdbcQuotaLogStore(JdbcTemplate jdbc, String table, String platform) {
        if (!table.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("日志表名不合法：" + table);
        }
        this.jdbc = jdbc;
        this.dataSource = jdbc.getDataSource();
        this.table = table;
        this.platform = QuotaDatabase.normalize(platform);
    }

    @Override
    public void saveAll(List<QuotaLog> logs) {
        if (logs.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO " + table + " (quota_name, suffix, log_type, log_field, amount, total_before, "
                + "total_after, used_before, used_after, expire_before, expire_after, biz_id, remark, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", logs, logs.size(), (ps, l) -> {
            // 文本列不存 NULL，没有值存空字符串（共用资格的后缀也是 ''），避免 NULL 影响索引。
            // Oracle 会把 '' 当成 NULL，所以 Oracle 的这几列建表时允许 NULL，查共用资格时用 IS NULL
            ps.setString(1, l.getName());
            ps.setString(2, l.getSuffix());
            ps.setString(3, l.getType().name());
            ps.setString(4, l.getField() == null ? "" : l.getField().name());
            ps.setLong(5, l.getAmount());
            ps.setLong(6, l.getTotalBefore());
            ps.setLong(7, l.getTotalAfter());
            ps.setLong(8, l.getUsedBefore());
            ps.setLong(9, l.getUsedAfter());
            ps.setTimestamp(10, ts(l.getExpireBefore()));
            ps.setTimestamp(11, ts(l.getExpireAfter()));
            ps.setString(12, nullToBlank(l.getBizId()));
            ps.setString(13, nullToBlank(l.getRemark()));
            ps.setTimestamp(14, ts(l.getCreatedAt()));
        });
    }

    @Override
    public QuotaLogPage query(QuotaLogQuery q) {
        String p = platform();
        List<Object> params = new ArrayList<Object>();
        String where = where(q, p, params);
        // COUNT(*) 一定有值
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + where, Long.class, params.toArray());
        if (q.offset() >= total) {
            return new QuotaLogPage(Collections.<QuotaLog>emptyList(), total, q.getPageNum(), q.getPageSize());
        }
        for (Object o : QuotaDatabase.pageParams(p, q.offset(), q.getPageSize())) {
            params.add(o);
        }
        List<QuotaLog> list = jdbc.query("SELECT " + COLUMNS + " FROM " + table + where + " ORDER BY id DESC"
                + QuotaDatabase.pageClause(p), ROW, params.toArray());
        return new QuotaLogPage(list, total, q.getPageNum(), q.getPageSize());
    }

    /** 查询条件：没设置的不加 */
    private static String where(QuotaLogQuery q, String platform, List<Object> params) {
        List<String> list = new ArrayList<String>();
        if (null != q.getName() && !q.getName().trim().isEmpty()) {
            list.add("quota_name = ?");
            params.add(q.getName());
        }
        if (q.isSharedOnly()) {
            // 共用资格的后缀存的是 ''，Oracle 里 '' 就是 NULL
            list.add("oracle".equals(platform) ? "suffix IS NULL" : "suffix = ''");
        } else if (null != q.getSuffix() && !q.getSuffix().isEmpty()) {
            list.add("suffix = ?");
            params.add(q.getSuffix());
        }
        if (null != q.getFrom()) {
            list.add("created_at >= ?");
            params.add(Timestamp.valueOf(q.getFrom()));
        }
        if (null != q.getTo()) {
            list.add("created_at <= ?");
            params.add(Timestamp.valueOf(q.getTo()));
        }
        if (!q.getTypes().isEmpty()) {
            list.add("log_type IN (" + marks(q.getTypes().size()) + ")");
            for (QuotaLogType t : q.getTypes()) {
                params.add(t.name());
            }
        }
        if (!q.getFields().isEmpty()) {
            list.add("log_field IN (" + marks(q.getFields().size()) + ")");
            for (QuotaLogField f : q.getFields()) {
                params.add(f.name());
            }
        }
        if (null != q.getBizId() && !q.getBizId().isEmpty()) {
            list.add("biz_id = ?");
            params.add(q.getBizId());
        }
        return list.isEmpty() ? "" : " WHERE " + String.join(" AND ", list);
    }

    private static String marks(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }

    private String platform() {
        if (platform == null) {
            platform = QuotaDatabase.detect(dataSource);
        }
        return platform;
    }

    private static final RowMapper<QuotaLog> ROW = (rs, i) -> new QuotaLog(rs.getLong("id"), rs.getString("quota_name"),
            rs.getString("suffix"), QuotaLogType.valueOf(rs.getString("log_type")),
            field(rs.getString("log_field")), rs.getLong("amount"),
            rs.getLong("total_before"), rs.getLong("total_after"), rs.getLong("used_before"), rs.getLong("used_after"),
            time(rs.getTimestamp("expire_before")), time(rs.getTimestamp("expire_after")), nullToBlank(rs.getString("biz_id")),
            nullToBlank(rs.getString("remark")), time(rs.getTimestamp("created_at")));

    private static QuotaLogField field(String s) {
        return s == null || s.isEmpty() ? null : QuotaLogField.valueOf(s);
    }

    private static String nullToBlank(String s) {
        return s == null ? "" : s;
    }

    private static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }

    private static LocalDateTime time(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
