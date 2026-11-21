package com.tora.qual.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 资格插件的配置，前缀 tora.qual。
 * 数据库、Redis 的连接信息都沿用项目 Spring 的配置（spring.datasource、spring.redis / spring.data.redis），这里不再配一遍。
 */
@ConfigurationProperties(prefix = "tora.qual")
public class QuotaProperties {

    /** Redis key 前缀，资格 key = 前缀 + 资格名[:后缀] */
    private String keyPrefix = "tora:qual:";
    /** 算自然小时 / 天 / 周 / 月用的时区，默认系统时区 */
    private String zone;
    /** @ConsumeQuota 注解是否生效 */
    private boolean aspectEnabled = true;

    private final Redis redis = new Redis();
    private final Log log = new Log();
    private final Web web = new Web();

    public static class Redis {
        /** 资格放在哪个 db；不配就用项目 Redis 连接的 db。连接地址、密码、连接池都沿用项目的 */
        private Integer database;

        public Integer getDatabase() {
            return database;
        }

        public void setDatabase(Integer database) {
            this.database = database;
        }
    }

    public static class Log {
        /** 是否发布变更日志 */
        private boolean enabled = true;
        /** 日志发布到的 Redis Stream（和资格在同一个 db） */
        private String streamKey = "tora:qual:log";
        /** 写库的消费组：多台机器同一个组，每条只写一次 */
        private String group = "tora-qual-log";
        /** 本机是否监听 Stream 写库（有数据源才会监听） */
        private boolean listenerEnabled = true;
        /** 每批读多少条 */
        private int batchSize = 200;
        /** 没有新日志时隔多久再读 */
        private Duration pollInterval = Duration.ofMillis(500);
        /** 别的实例拿走后多久没 ACK，就认领过来（实例挂了的情况） */
        private Duration claimIdle = Duration.ofMinutes(5);
        /** 日志表名（表用 migrations 里的脚本建） */
        private String table = "tora_qual_log";
        /** 数据库类型（mysql、mariadb、postgresql、oracle、sqlserver、h2），不填自动识别，只影响分页写法 */
        private String platform;
        /** 用哪个 DataSource Bean（多数据源时填 Bean 名）；不填用项目的主数据源 */
        private String dataSource;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStreamKey() {
            return streamKey;
        }

        public void setStreamKey(String streamKey) {
            this.streamKey = streamKey;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public boolean isListenerEnabled() {
            return listenerEnabled;
        }

        public void setListenerEnabled(boolean listenerEnabled) {
            this.listenerEnabled = listenerEnabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public Duration getClaimIdle() {
            return claimIdle;
        }

        public void setClaimIdle(Duration claimIdle) {
            this.claimIdle = claimIdle;
        }

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }

        public String getPlatform() {
            return platform;
        }

        public void setPlatform(String platform) {
            this.platform = platform;
        }

        public String getDataSource() {
            return dataSource;
        }

        public void setDataSource(String dataSource) {
            this.dataSource = dataSource;
        }
    }

    public static class Web {
        /** 是否注册查询接口（给查询页面用），默认关闭 */
        private boolean enabled = false;
        /** 接口路径前缀 */
        private String path = "/tora-qual";
        /** 访问令牌：配了的话请求头 X-Qual-Token 要带上；项目自己的登录拦截照样生效 */
        private String token;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public boolean isAspectEnabled() {
        return aspectEnabled;
    }

    public void setAspectEnabled(boolean aspectEnabled) {
        this.aspectEnabled = aspectEnabled;
    }

    public Redis getRedis() {
        return redis;
    }

    public Log getLog() {
        return log;
    }

    public Web getWeb() {
        return web;
    }
}
