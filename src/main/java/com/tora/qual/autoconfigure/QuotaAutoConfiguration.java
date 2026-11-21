package com.tora.qual.autoconfigure;

import com.tora.qual.QuotaService;
import com.tora.qual.RedisQuotaService;
import com.tora.qual.aop.ConsumeQuotaAspect;
import com.tora.qual.log.JdbcQuotaLogStore;
import com.tora.qual.log.QuotaLogPublisher;
import com.tora.qual.log.QuotaLogStore;
import com.tora.qual.log.QuotaLogStreamListener;
import com.tora.qual.log.RedisStreamQuotaLogPublisher;
import com.tora.qual.redis.QuotaRedis;
import com.tora.qual.web.QuotaLogController;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.ZoneId;

/**
 * 资格插件自动配置：项目里有 StringRedisTemplate 就生效。
 * 日志发布到 Redis Stream；有数据源的实例监听 Stream 写库、提供日志查询。
 * 需要 Spring Boot 2.3+（Spring Data Redis 2.3+，Redis Stream 消费组 API）。
 * 用 @Configuration + @AutoConfigureAfter，Spring Boot 2.x、3.x 都认（同时登记在 spring.factories 和 AutoConfiguration.imports）。
 */
@Configuration
@AutoConfigureAfter(name = {"org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration"})
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnBean(StringRedisTemplate.class)
@EnableConfigurationProperties(QuotaProperties.class)
public class QuotaAutoConfiguration {

    /** Spring Data Redis 2.3 才有（查看 / 认领未确认的 Stream 消息），用来检查版本 */
    static final String STREAM_CLASS = "org.springframework.data.redis.connection.stream.PendingMessages";

    @Bean
    @ConditionalOnMissingBean
    public QuotaRedis quotaRedis(StringRedisTemplate projectTemplate, QuotaProperties props) {
        requireClass(STREAM_CLASS);
        return new QuotaRedis(projectTemplate, props.getRedis().getDatabase());
    }

    @Configuration
    @ConditionalOnClass(JdbcTemplate.class)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "tora.qual.log", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class JdbcLogConfiguration {

        /** 用项目的数据源；多数据源时按 tora.qual.log.data-source 指定 Bean 名 */
        @Bean
        @ConditionalOnMissingBean(QuotaLogStore.class)
        public JdbcQuotaLogStore quotaLogStore(BeanFactory beans, ObjectProvider<DataSource> dataSources,
                                               QuotaProperties props) {
            QuotaProperties.Log l = props.getLog();
            String name = l.getDataSource();
            DataSource ds = name == null || name.trim().isEmpty() ? dataSources.getIfUnique()
                    : beans.getBean(name.trim(), DataSource.class);
            if (ds == null) {
                throw new IllegalStateException("项目里有多个数据源，请用 tora.qual.log.data-source 指定资格日志用哪个");
            }
            return new JdbcQuotaLogStore(new JdbcTemplate(ds), l.getTable(), l.getPlatform());
        }

        /** 监听 Redis Stream 写库 */
        @Configuration
        @ConditionalOnProperty(prefix = "tora.qual.log", name = "listener-enabled", havingValue = "true",
                matchIfMissing = true)
        static class StreamListenerConfiguration {

            @Bean
            @ConditionalOnMissingBean
            public QuotaLogStreamListener quotaLogStreamListener(QuotaRedis redis, QuotaLogStore store,
                                                                 QuotaProperties props) {
                QuotaProperties.Log l = props.getLog();
                return new QuotaLogStreamListener(redis.getTemplate(), store, l.getStreamKey(), l.getGroup(),
                        l.getBatchSize(), l.getPollInterval(), l.getClaimIdle());
            }
        }
    }

    /** 日志发到 Redis Stream；关掉日志时不发 */
    @Bean
    @ConditionalOnMissingBean
    public QuotaLogPublisher quotaLogPublisher(QuotaRedis redis, QuotaProperties props) {
        if (props.getLog().isEnabled()) {
            return new RedisStreamQuotaLogPublisher(redis.getTemplate(), props.getLog().getStreamKey());
        }
        // tora.qual.log.enabled=false：什么都不发
        return logs -> { };
    }

    @Bean
    @ConditionalOnMissingBean(QuotaService.class)
    public RedisQuotaService quotaService(QuotaRedis redis, QuotaLogPublisher publisher,
                                         ObjectProvider<QuotaLogStore> store, QuotaProperties props) {
        String z = props.getZone();
        ZoneId zone = z == null || z.trim().isEmpty() ? ZoneId.systemDefault() : ZoneId.of(z.trim());
        return new RedisQuotaService(redis.getTemplate(), publisher, store.getIfAvailable(), Clock.system(zone),
                props.getKeyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
    @ConditionalOnProperty(prefix = "tora.qual", name = "aspect-enabled", havingValue = "true", matchIfMissing = true)
    public ConsumeQuotaAspect consumeQuotaAspect(QuotaService quota) {
        return new ConsumeQuotaAspect(quota);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    @ConditionalOnProperty(prefix = "tora.qual.web", name = "enabled", havingValue = "true")
    public QuotaLogController quotaLogController(QuotaService quota, QuotaProperties props) {
        return new QuotaLogController(quota, props.getWeb().getToken());
    }

    static void requireClass(String className) {
        try {
            Class.forName(className, false, QuotaAutoConfiguration.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("tora-qual-lock 需要 Spring Boot 2.3+（Spring Data Redis 2.3+）", e);
        }
    }
}
