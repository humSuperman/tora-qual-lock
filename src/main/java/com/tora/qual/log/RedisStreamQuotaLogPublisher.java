package com.tora.qual.log;

import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * 日志发到 Redis Stream。Stream 会一直保存消息，监听方挂了、重启了也不丢；
 * 多台机器用同一个消费组，每条只会被一台写库。
 */
public class RedisStreamQuotaLogPublisher implements QuotaLogPublisher {

    private final StringRedisTemplate redis;
    private final String streamKey;

    public RedisStreamQuotaLogPublisher(StringRedisTemplate redis, String streamKey) {
        this.redis = redis;
        this.streamKey = streamKey;
    }

    @Override
    public void publish(List<QuotaLog> logs) {
        for (QuotaLog l : logs) {
            redis.opsForStream().add(StreamRecords.newRecord().in(streamKey).ofMap(QuotaLogCodec.encode(l)));
        }
    }
}
