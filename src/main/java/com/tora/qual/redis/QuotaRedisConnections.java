package com.tora.qual.redis;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * 沿用项目里 Spring 配好的 Redis 连接（地址、密码、SSL、连接池、超时都一样），只换一个 db 给资格用。
 * 支持 Lettuce、Jedis 的单机和哨兵模式；集群模式只有 db 0，不能选库。
 */
public final class QuotaRedisConnections {

    private static final String LETTUCE = "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory";
    private static final String JEDIS = "org.springframework.data.redis.connection.jedis.JedisConnectionFactory";

    private QuotaRedisConnections() {
    }

    /** 新建一个连到 database 的连接工厂；调用方负责 afterPropertiesSet / destroy（注册成 Bean 即可） */
    public static RedisConnectionFactory withDatabase(RedisConnectionFactory source, int database) {
        // 按类名判断：项目没引 Lettuce / Jedis 时不去加载对应的类
        if (isA(source, LETTUCE)) {
            return Lettuce.copy((LettuceConnectionFactory) source, database);
        }
        if (isA(source, JEDIS)) {
            return Jedis.copy((JedisConnectionFactory) source, database);
        }
        throw new IllegalStateException("tora.qual.redis.database 只支持 Lettuce / Jedis 连接，当前是 "
                + source.getClass().getName() + "；可以不配 database，直接用项目的 Redis 连接");
    }

    static boolean isA(Object o, String className) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    static RedisStandaloneConfiguration standalone(RedisStandaloneConfiguration s, int database) {
        RedisStandaloneConfiguration c = new RedisStandaloneConfiguration(s.getHostName(), s.getPort());
        c.setPassword(s.getPassword());
        final RedisStandaloneConfiguration from = s;
        final RedisStandaloneConfiguration to = c;
        ignoreMissingMethod(new Runnable() {
            @Override
            public void run() {
                to.setUsername(from.getUsername());
            }
        });
        c.setDatabase(database);
        return c;
    }

    static RedisSentinelConfiguration sentinel(RedisSentinelConfiguration s, int database) {
        RedisSentinelConfiguration c = new RedisSentinelConfiguration();
        c.setMaster(s.getMaster());
        c.setSentinels(s.getSentinels());
        c.setPassword(s.getPassword());
        c.setDatabase(database);
        return c;
    }

    /** 老版本没有的方法跳过，比如 Redis 6 的 ACL 用户名：Spring Data Redis 2.4 才有 */
    static void ignoreMissingMethod(Runnable r) {
        try {
            r.run();
        } catch (NoSuchMethodError ignored) {
            // Spring Data Redis < 2.4
        }
    }

    private static void checkNotCluster(boolean cluster) {
        if (cluster) {
            throw new IllegalStateException("Redis 集群模式只有 db 0，不能配置 tora.qual.redis.database");
        }
    }

    /** Lettuce 相关类单独放，项目没有 Lettuce 时不会加载 */
    private static final class Lettuce {
        static RedisConnectionFactory copy(LettuceConnectionFactory f, int database) {
            checkNotCluster(f.getClusterConfiguration() != null);
            LettuceClientConfiguration client = f.getClientConfiguration();
            if (f.getSentinelConfiguration() != null) {
                return new LettuceConnectionFactory(sentinel(f.getSentinelConfiguration(), database), client);
            }
            return new LettuceConnectionFactory(standalone(f.getStandaloneConfiguration(), database), client);
        }
    }

    /** Jedis 相关类单独放，项目没有 Jedis 时不会加载 */
    private static final class Jedis {
        static RedisConnectionFactory copy(JedisConnectionFactory f, int database) {
            checkNotCluster(f.getClusterConfiguration() != null);
            JedisClientConfiguration client = f.getClientConfiguration();
            if (f.getSentinelConfiguration() != null) {
                return new JedisConnectionFactory(sentinel(f.getSentinelConfiguration(), database), client);
            }
            return new JedisConnectionFactory(standalone(f.getStandaloneConfiguration(), database), client);
        }
    }
}
