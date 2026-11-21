package com.tora.qual;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 测试建表：直接执行 migrations/{数据库}/ 下的迁移脚本（@UNDO 之前的部分），和正式环境 migrate up 用同一份 SQL。
 */
public final class Migrations {

    private Migrations() {
    }

    /** H2 内存库；mode 是 H2 的兼容模式（MySQL、Oracle……），空串是 H2 自己 */
    public static DataSource h2(String mode) {
        String url = "jdbc:h2:mem:q" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1"
                + (mode.isEmpty() ? "" : ";MODE=" + mode) + ";DATABASE_TO_LOWER=" + !"Oracle".equals(mode);
        return new DriverManagerDataSource(url, "sa", "");
    }

    public static DataSource up(String mode, String platform) {
        DataSource ds = h2(mode);
        up(ds, platform);
        return ds;
    }

    public static void up(DataSource ds, String platform) {
        try {
            File dir = new File("migrations", platform);
            File[] files = dir.listFiles();
            if (files == null) {
                throw new IllegalStateException("找不到迁移脚本目录 " + dir.getAbsolutePath());
            }
            Arrays.sort(files);
            Connection c = ds.getConnection();
            try {
                Statement st = c.createStatement();
                for (File f : files) {
                    for (String sql : statements(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))) {
                        st.execute(sql);
                    }
                }
            } finally {
                c.close();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** @UNDO 之前的部分，去掉注释行，按行尾分号拆开 */
    public static List<String> statements(String script) {
        String up = script.split("--\\s*//@UNDO")[0];
        StringBuilder sb = new StringBuilder();
        for (String line : up.split("\\r?\\n")) {
            if (!line.trim().startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<String>();
        for (String s : sb.toString().split(";\\s*(\\n|$)")) {
            if (!s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return out;
    }
}
