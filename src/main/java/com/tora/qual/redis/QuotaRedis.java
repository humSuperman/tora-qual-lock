package com.tora.qual.redis;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 资格用的 Redis：没配 tora.qual.redis.database 就是项目自己的 StringRedisTemplate；
 * 配了就沿用项目的连接信息另开一个连到那个 db 的连接。
 * 这里不把新连接注册成 RedisConnectionFactory / StringRedisTemplate Bean，免得项目里按类型注入时出现两个。
 */
public class QuotaRedis implements InitializingBean, DisposableBean {

    private final StringRedisTemplate template;
    /** 自己新建的连接，关闭时要释放；用项目的连接时为 null */
    private final RedisConnectionFactory own;

    public QuotaRedis(StringRedisTemplate projectTemplate, Integer database) {
        if (database == null) {
            this.template = projectTemplate;
            this.own = null;
        } else {
            this.own = QuotaRedisConnections.withDatabase(projectTemplate.getConnectionFactory(), database);
            this.template = new StringRedisTemplate(own);
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (own instanceof InitializingBean) {
            ((InitializingBean) own).afterPropertiesSet();
        }
        template.afterPropertiesSet();
    }

    public StringRedisTemplate getTemplate() {
        return template;
    }

    @Override
    public void destroy() throws Exception {
        if (own instanceof DisposableBean) {
            ((DisposableBean) own).destroy();
        }
    }
}
