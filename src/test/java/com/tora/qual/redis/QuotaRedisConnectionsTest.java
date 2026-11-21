package com.tora.qual.redis;

import com.tora.qual.RedisSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.ClassUtils;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class QuotaRedisConnectionsTest {

    @Test
    void 沿用项目的连接_换一个库() throws Exception {
        StringRedisTemplate project = RedisSupport.template();
        QuotaRedis qr = new QuotaRedis(project, 14);
        qr.afterPropertiesSet();
        StringRedisTemplate t = qr.getTemplate();
        assertThat(t).isNotSameAs(project);
        RedisSupport.flush(t);
        t.opsForValue().set("k", "v14");
        assertThat(project.hasKey("k")).isFalse();
        LettuceConnectionFactory f = (LettuceConnectionFactory) t.getConnectionFactory();
        assertThat(f.getDatabase()).isEqualTo(14);
        assertThat(f.getHostName()).isEqualTo(((LettuceConnectionFactory) project.getConnectionFactory()).getHostName());
        qr.destroy();

        // 不配库：直接用项目的
        QuotaRedis same = new QuotaRedis(project, null);
        same.afterPropertiesSet();
        assertThat(same.getTemplate()).isSameAs(project);
        same.destroy();
    }

    @Test
    void 单机和哨兵复制连接信息_集群不能选库() {
        RedisStandaloneConfiguration s = new RedisStandaloneConfiguration("10.0.0.1", 6380);
        s.setPassword(RedisPassword.of("pwd"));
        s.setUsername("app");
        s.setDatabase(1);
        LettuceConnectionFactory copy = (LettuceConnectionFactory) QuotaRedisConnections.withDatabase(
                new LettuceConnectionFactory(s), 9);
        assertThat(copy.getStandaloneConfiguration().getHostName()).isEqualTo("10.0.0.1");
        assertThat(copy.getStandaloneConfiguration().getPort()).isEqualTo(6380);
        assertThat(copy.getStandaloneConfiguration().getPassword()).isEqualTo(RedisPassword.of("pwd"));
        assertThat(copy.getStandaloneConfiguration().getUsername()).isEqualTo("app");
        assertThat(copy.getStandaloneConfiguration().getDatabase()).isEqualTo(9);

        RedisSentinelConfiguration sen = new RedisSentinelConfiguration("mymaster", Collections.singleton("10.0.0.2:26379"));
        sen.setPassword(RedisPassword.of("pwd"));
        LettuceConnectionFactory sc = (LettuceConnectionFactory) QuotaRedisConnections.withDatabase(
                new LettuceConnectionFactory(sen), 3);
        assertThat(sc.getSentinelConfiguration().getMaster().getName()).isEqualTo("mymaster");
        assertThat(sc.getSentinelConfiguration().getDatabase()).isEqualTo(3);
        assertThat(sc.getSentinelConfiguration().getSentinels()).hasSize(1);

        final LettuceConnectionFactory cluster = new LettuceConnectionFactory(
                new RedisClusterConfiguration(Collections.singletonList("10.0.0.3:7000")));
        assertThatThrownBy(() -> QuotaRedisConnections.withDatabase(cluster, 1)).hasMessageContaining("集群");
        final RedisConnectionFactory other = mock(RedisConnectionFactory.class);
        assertThatThrownBy(() -> QuotaRedisConnections.withDatabase(other, 1)).hasMessageContaining("只支持 Lettuce / Jedis");
    }

    @Test
    void Jedis单机和哨兵复制连接信息_集群不能选库() {
        // Spring Data Redis 2.7+ / 3.x 要 Jedis 3.6+ 的 JedisClientConfig；版本不配套时跳过
        Assumptions.assumeTrue(ClassUtils.isPresent("redis.clients.jedis.JedisClientConfig", null),
                "classpath 上的 Jedis 和 Spring Data Redis 版本不配套");
        RedisStandaloneConfiguration s = new RedisStandaloneConfiguration("10.0.0.1", 6380);
        s.setPassword(RedisPassword.of("pwd"));
        JedisConnectionFactory copy = (JedisConnectionFactory) QuotaRedisConnections.withDatabase(
                new JedisConnectionFactory(s), 9);
        assertThat(copy.getStandaloneConfiguration().getHostName()).isEqualTo("10.0.0.1");
        assertThat(copy.getStandaloneConfiguration().getPassword()).isEqualTo(RedisPassword.of("pwd"));
        assertThat(copy.getStandaloneConfiguration().getDatabase()).isEqualTo(9);

        RedisSentinelConfiguration sen = new RedisSentinelConfiguration("mymaster", Collections.singleton("10.0.0.2:26379"));
        JedisConnectionFactory sc = (JedisConnectionFactory) QuotaRedisConnections.withDatabase(
                new JedisConnectionFactory(sen), 3);
        assertThat(sc.getSentinelConfiguration().getMaster().getName()).isEqualTo("mymaster");
        assertThat(sc.getSentinelConfiguration().getDatabase()).isEqualTo(3);

        final JedisConnectionFactory cluster = new JedisConnectionFactory(
                new RedisClusterConfiguration(Collections.singletonList("10.0.0.3:7000")));
        assertThatThrownBy(() -> QuotaRedisConnections.withDatabase(cluster, 1)).hasMessageContaining("集群");
    }

    @Test
    void 按类名判断连接类型_子类也算() {
        LettuceConnectionFactory sub = new LettuceConnectionFactory() {
        };
        assertThat(QuotaRedisConnections.isA(sub, LettuceConnectionFactory.class.getName())).isTrue();
        assertThat(QuotaRedisConnections.isA(sub, JedisConnectionFactory.class.getName())).isFalse();
    }

    @Test
    void 老版本没有的方法跳过() {
        final AtomicBoolean ran = new AtomicBoolean();
        QuotaRedisConnections.ignoreMissingMethod(() -> {
            ran.set(true);
            throw new NoSuchMethodError("setUsername");
        });
        assertThat(ran).isTrue();
    }
}
