package com.tora.qual.autoconfigure;

import com.tora.qual.ExpectedErrorLogs;
import com.tora.qual.ExpireRule;
import com.tora.qual.Migrations;
import com.tora.qual.QuotaException;
import com.tora.qual.QuotaService;
import com.tora.qual.QuotaSpec;
import com.tora.qual.RedisSupport;
import com.tora.qual.aop.ConsumeQuota;
import com.tora.qual.aop.ConsumeQuotaAspect;
import com.tora.qual.log.JdbcQuotaLogStore;
import com.tora.qual.log.QuotaLog;
import com.tora.qual.log.QuotaLogPage;
import com.tora.qual.log.QuotaLogPublisher;
import com.tora.qual.log.QuotaLogQuery;
import com.tora.qual.log.QuotaLogStore;
import com.tora.qual.log.QuotaLogStreamListener;
import com.tora.qual.log.QuotaLogType;
import com.tora.qual.log.RedisStreamQuotaLogPublisher;
import com.tora.qual.redis.QuotaRedis;
import com.tora.qual.web.QuotaLogController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotaAutoConfigurationTest {

    ApplicationContextRunner runner;
    String[] redisProps;

    @BeforeEach
    void setUp() {
        String[] hp = RedisSupport.hostPort();
        RedisSupport.template();
        // Boot 2 是 spring.redis.*，Boot 3 是 spring.data.redis.*，两套都写上
        redisProps = new String[]{"spring.data.redis.host=" + hp[0], "spring.data.redis.port=" + hp[1],
                "spring.data.redis.database=15", "spring.redis.host=" + hp[0], "spring.redis.port=" + hp[1],
                "spring.redis.database=15"};
        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class, DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class, AopAutoConfiguration.class, QuotaAutoConfiguration.class))
                .withPropertyValues(redisProps)
                .withPropertyValues("tora.qual.key-prefix=auto:qual:", "tora.qual.zone=Asia/Tokyo",
                        "tora.qual.log.stream-key=auto:qual:log", "tora.qual.log.poll-interval=50ms")
                .withUserConfiguration(App.class);
    }

    @Configuration
    static class App {

        /** 测试库：先按 MySQL 脚本建好表（正式环境用 migrate up） */
        @Bean
        DataSource dataSource() {
            return Migrations.up("MySQL", "mysql");
        }

        @Bean
        Lottery lottery() {
            return new Lottery();
        }
    }

    @Configuration
    static class TwoPlainDataSources {

        @Bean
        DataSource aDataSource() {
            return Migrations.h2("MySQL");
        }

        @Bean
        DataSource bDataSource() {
            return Migrations.h2("MySQL");
        }
    }

    @Configuration
    static class TwoDataSources {

        @Bean
        @Primary
        DataSource mainDataSource() {
            return Migrations.h2("MySQL");
        }

        /** 资格日志放在另一个库 */
        @Bean
        DataSource logDataSource() {
            return Migrations.up("MySQL", "mysql");
        }
    }

    static class Lottery {

        @org.springframework.beans.factory.annotation.Autowired
        QuotaService quota;

        /** 总资格按会员等级算：等级 * 2 */
        @ConsumeQuota(name = "lottery:prize", total = "#level * 2", expire = "NATURAL_DAY", suffix = "#userId",
                bizId = "#orderNo")
        public String draw(Long userId, int level, String orderNo, boolean fail) {
            if (fail) {
                throw new IllegalStateException("发奖失败");
            }
            return "奖品";
        }

        @ConsumeQuota(name = "lottery:prize", total = "2", expire = "NATURAL_DAY", suffix = "#userId",
                restoreOnError = false)
        public void drawNoRestore(Long userId) {
            throw new IllegalStateException("发奖失败");
        }

        /** 方法执行期间资格被删了：退不回去，原来的异常照常抛出 */
        @ConsumeQuota(name = "vanish", total = "1", suffix = "#userId")
        public void vanish(Long userId) {
            quota.delete("vanish", String.valueOf(userId), "期间被删");
            throw new IllegalStateException("发奖失败");
        }

        /** 后缀表达式算出来是 null：当共用资格 */
        @ConsumeQuota(name = "shared", total = "1", suffix = "#userId")
        public void shared(Long userId) {
        }

        @ConsumeQuota(name = "bad", total = "#missing")
        public void bad() {
        }
    }

    private static QuotaLogPage waitLogs(QuotaService quota, QuotaLogQuery q, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        QuotaLogPage p = quota.logs(q);
        while (p.getTotal() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            p = quota.logs(q);
        }
        return p;
    }

    @Test
    void 自动装配_注解扣减_异常退回_日志经Stream写库_分页查询() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(QuotaService.class).hasSingleBean(ConsumeQuotaAspect.class)
                    .hasSingleBean(QuotaLogStreamListener.class).doesNotHaveBean(QuotaLogController.class);
            // 没有多出来的 StringRedisTemplate，项目按类型注入不受影响
            assertThat(ctx).hasSingleBean(StringRedisTemplate.class);
            assertThat(ctx.getBean(QuotaLogStore.class)).isInstanceOf(JdbcQuotaLogStore.class);
            assertThat(ctx.getBean(QuotaLogPublisher.class)).isInstanceOf(RedisStreamQuotaLogPublisher.class);
            QuotaService quota = ctx.getBean(QuotaService.class);
            final Lottery lottery = ctx.getBean(Lottery.class);
            QuotaSpec spec = QuotaSpec.of("lottery:prize", 2, ExpireRule.naturalDay());

            assertThat(lottery.draw(1L, 1, "o-1", false)).isEqualTo("奖品");
            assertThat(quota.get(spec, "1").getUsed()).isEqualTo(1);
            assertThatThrownBy(() -> lottery.draw(1L, 1, "o-2", true)).hasMessage("发奖失败");
            assertThat(quota.get(spec, "1").getUsed()).isEqualTo(1);
            assertThatThrownBy(() -> lottery.drawNoRestore(1L)).hasMessage("发奖失败");
            assertThatThrownBy(() -> lottery.draw(1L, 1, "o-3", false)).isInstanceOf(QuotaException.class)
                    .hasMessageContaining("不足");
            assertThat(lottery.draw(1L, 2, "o-4", false)).isEqualTo("奖品");
            assertThatThrownBy(lottery::bad).isInstanceOf(IllegalArgumentException.class);

            QuotaLogPage page = waitLogs(quota, new QuotaLogQuery().name("lottery:prize").suffix("1").page(1, 3), 7);
            assertThat(page.getTotal()).isEqualTo(7);
            assertThat(page.getList()).hasSize(3);
            assertThat(page.getList().get(0).getBizId()).isEqualTo("o-4");
            List<QuotaLog> creates = quota.logs(new QuotaLogQuery().name("lottery:prize").types(QuotaLogType.CREATE))
                    .getList();
            assertThat(creates).hasSize(1);
            assertThat(creates.get(0).getExpireAfter()).isNotNull();
        });
    }

    @Test
    @ExpectedErrorLogs(QuotaLogStreamListener.class)
    void 资格放在单独的Redis库_连接信息沿用项目的() {
        runner.withPropertyValues("tora.qual.redis.database=14").run(ctx -> {
            assertThat(ctx).hasSingleBean(StringRedisTemplate.class);
            StringRedisTemplate project = ctx.getBean(StringRedisTemplate.class);
            StringRedisTemplate qual = ctx.getBean(QuotaRedis.class).getTemplate();
            RedisSupport.flush(qual);
            ctx.getBean(QuotaService.class).consume(QuotaSpec.of("db14", 3), null);
            assertThat(qual.hasKey("auto:qual:db14")).isTrue();
            assertThat(project.hasKey("auto:qual:db14")).isFalse();
            // 日志 Stream 也在资格的库里，照样写库
            assertThat(waitLogs(ctx.getBean(QuotaService.class), new QuotaLogQuery().name("db14"), 2).getTotal())
                    .isEqualTo(2);
        });
    }

    @Test
    void 多数据源时指定用哪个() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class, QuotaAutoConfiguration.class))
                .withPropertyValues(redisProps)
                .withPropertyValues("tora.qual.log.data-source=logDataSource", "tora.qual.log.listener-enabled=false")
                .withUserConfiguration(TwoDataSources.class)
                .run(ctx -> {
                    QuotaLogStore store = ctx.getBean(QuotaLogStore.class);
                    // logDataSource 里有表，查得通；主库没有表会报错
                    assertThat(store.query(new QuotaLogQuery()).getTotal()).isZero();
                });
    }

    @Test
    void 开启查询接口() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class, QuotaAutoConfiguration.class))
                .withPropertyValues(redisProps)
                .withPropertyValues("tora.qual.web.enabled=true", "tora.qual.web.token=abc")
                .run(ctx -> assertThat(ctx).hasSingleBean(QuotaLogController.class));
    }

    @Test
    void 没有数据源只发布不写库_关掉日志_关掉注解() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class, QuotaAutoConfiguration.class))
                .withPropertyValues(redisProps)
                .withPropertyValues("tora.qual.aspect-enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(QuotaLogStore.class).doesNotHaveBean(QuotaLogStreamListener.class)
                            .doesNotHaveBean(ConsumeQuotaAspect.class);
                    assertThat(ctx.getBean(QuotaLogPublisher.class)).isInstanceOf(RedisStreamQuotaLogPublisher.class);
                    assertThat(ctx.getBean(QuotaService.class).logs(new QuotaLogQuery()).getList()).isEmpty();
                });
        runner.withPropertyValues("tora.qual.log.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(QuotaLogStreamListener.class).doesNotHaveBean(QuotaLogStore.class);
            assertThat(ctx.getBean(QuotaLogPublisher.class)).isNotInstanceOf(RedisStreamQuotaLogPublisher.class);
            ctx.getBean(QuotaService.class).consume(QuotaSpec.of("nolog", 1), null);
            assertThat(ctx.getBean(StringRedisTemplate.class).hasKey("auto:qual:log")).isFalse();
        });
        runner.withPropertyValues("tora.qual.log.listener-enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(QuotaLogStreamListener.class).hasSingleBean(QuotaLogStore.class));
    }

    @Test
    void 没有Redis不生效() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(QuotaAutoConfiguration.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(QuotaService.class));
    }

    @Test
    void 配置项默认值() {
        QuotaProperties p = new QuotaProperties();
        assertThat(p.getKeyPrefix()).isEqualTo("tora:qual:");
        assertThat(p.getZone()).isNull();
        assertThat(p.isAspectEnabled()).isTrue();
        assertThat(p.getRedis().getDatabase()).isNull();
        QuotaProperties.Log l = p.getLog();
        assertThat(l.isEnabled()).isTrue();
        assertThat(l.getStreamKey()).isEqualTo("tora:qual:log");
        assertThat(l.getGroup()).isEqualTo("tora-qual-log");
        assertThat(l.isListenerEnabled()).isTrue();
        assertThat(l.getBatchSize()).isEqualTo(200);
        assertThat(l.getPollInterval().toMillis()).isEqualTo(500);
        assertThat(l.getClaimIdle().toMinutes()).isEqualTo(5);
        assertThat(l.getTable()).isEqualTo("tora_qual_log");
        assertThat(l.getPlatform()).isNull();
        assertThat(l.getDataSource()).isNull();
        assertThat(p.getWeb().isEnabled()).isFalse();
        assertThat(p.getWeb().getPath()).isEqualTo("/tora-qual");
        assertThat(p.getWeb().getToken()).isNull();
    }

    @Test
    @ExpectedErrorLogs(ConsumeQuotaAspect.class)
    void 注解_退回失败不盖住原异常_后缀算出null当共用资格() {
        runner.run(ctx -> {
            final Lottery lottery = ctx.getBean(Lottery.class);
            QuotaService quota = ctx.getBean(QuotaService.class);
            assertThatThrownBy(() -> lottery.vanish(1L)).hasMessage("发奖失败");
            assertThat(quota.peek("vanish", "1")).isNull();
            lottery.shared(null);
            assertThat(quota.peek("shared", null).getUsed()).isEqualTo(1);
        });
    }

    @Test
    void 数据源名和时区留空按没配处理() {
        runner.withPropertyValues("tora.qual.log.data-source=", "tora.qual.zone=", "tora.qual.log.listener-enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(QuotaLogStore.class);
                    ctx.getBean(QuotaService.class).consume(QuotaSpec.of("zone", 1, ExpireRule.naturalDay()), null);
                });
    }

    @Test
    @ExpectedErrorLogs(AnnotationConfigApplicationContext.class)
    void 多个数据源又没指定_启动报错() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class, QuotaAutoConfiguration.class))
                .withPropertyValues(redisProps)
                .withUserConfiguration(TwoPlainDataSources.class)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining("tora.qual.log.data-source"));
    }

    @Test
    void 缺少Redis_Stream类_提示要Boot2_3() {
        assertThatThrownBy(() -> QuotaAutoConfiguration.requireClass("org.example.NoSuchClass"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Spring Boot 2.3+");
    }

    @Test
    void 配置项都能改() {
        QuotaProperties p = new QuotaProperties();
        p.setKeyPrefix("a:");
        p.setZone("UTC");
        p.setAspectEnabled(false);
        p.getRedis().setDatabase(3);
        QuotaProperties.Log l = p.getLog();
        l.setEnabled(false);
        l.setStreamKey("s");
        l.setGroup("g");
        l.setListenerEnabled(false);
        l.setBatchSize(5);
        l.setPollInterval(java.time.Duration.ofSeconds(1));
        l.setClaimIdle(java.time.Duration.ofSeconds(2));
        l.setTable("t");
        l.setPlatform("oracle");
        l.setDataSource("ds");
        QuotaProperties.Web w = p.getWeb();
        w.setEnabled(true);
        w.setPath("/p");
        w.setToken("tk");
        assertThat(p.getKeyPrefix()).isEqualTo("a:");
        assertThat(p.getZone()).isEqualTo("UTC");
        assertThat(p.isAspectEnabled()).isFalse();
        assertThat(p.getRedis().getDatabase()).isEqualTo(3);
        assertThat(l.isEnabled()).isFalse();
        assertThat(l.getStreamKey()).isEqualTo("s");
        assertThat(l.getGroup()).isEqualTo("g");
        assertThat(l.isListenerEnabled()).isFalse();
        assertThat(l.getBatchSize()).isEqualTo(5);
        assertThat(l.getPollInterval().getSeconds()).isEqualTo(1);
        assertThat(l.getClaimIdle().getSeconds()).isEqualTo(2);
        assertThat(l.getTable()).isEqualTo("t");
        assertThat(l.getPlatform()).isEqualTo("oracle");
        assertThat(l.getDataSource()).isEqualTo("ds");
        assertThat(w.isEnabled()).isTrue();
        assertThat(w.getPath()).isEqualTo("/p");
        assertThat(w.getToken()).isEqualTo("tk");
    }
}
