package com.tora.qual.log;

import org.springframework.boot.jdbc.DatabaseDriver;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 数据库类型相关：识别类型、分页写法 */
public final class QuotaDatabase {

    /** 支持 LIMIT ? OFFSET ? 的；其余用标准的 OFFSET ? ROWS FETCH NEXT ? ROWS ONLY（Oracle 12c+、SQL Server 2012+、DB2……） */
    private static final Set<String> LIMIT_OFFSET = new HashSet<String>(
            Arrays.asList("mysql", "mariadb", "postgresql", "h2", "hsqldb", "sqlite"));

    private QuotaDatabase() {
    }

    /** 数据库类型：mysql、mariadb、postgresql、oracle、sqlserver、h2 …… */
    public static String detect(DatabaseMetaData md) throws SQLException {
        DatabaseDriver driver = DatabaseDriver.fromProductName(md.getDatabaseProductName());
        if (driver == DatabaseDriver.UNKNOWN) {
            driver = DatabaseDriver.fromJdbcUrl(md.getURL());
        }
        if (driver == DatabaseDriver.UNKNOWN) {
            throw new IllegalStateException("识别不出数据库类型（" + md.getDatabaseProductName()
                    + "），请用 tora.qual.log.platform 指定");
        }
        return driver.getId();
    }

    public static String detect(DataSource ds) {
        try (Connection con = ds.getConnection()) {
            return detect(con.getMetaData());
        } catch (SQLException e) {
            throw new IllegalStateException("识别数据库类型失败", e);
        }
    }

    public static String normalize(String platform) {
        return platform == null || platform.trim().isEmpty() ? null : platform.trim().toLowerCase(Locale.ROOT);
    }

    /** 分页子句，参数顺序 [offset, size]（LIMIT 写法是 [size, offset]，由 {@link #pageParams} 排好） */
    public static String pageClause(String platform) {
        return LIMIT_OFFSET.contains(platform) ? " LIMIT ? OFFSET ?" : " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
    }

    public static Object[] pageParams(String platform, long offset, int size) {
        return LIMIT_OFFSET.contains(platform) ? new Object[]{size, offset} : new Object[]{offset, size};
    }
}
