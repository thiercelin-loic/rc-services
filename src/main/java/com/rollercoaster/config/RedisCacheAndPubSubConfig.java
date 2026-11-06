package com.rollercoaster.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;

/**
 * Configures Redis L1 Query Cache (300-second TTL) and Pub/Sub invalidation channels.
 * Query cache keys follow: cache:query:{tenant_id}:{rls_hash}:{context_hash}:{measure_id}
 */
@Configuration
public class RedisCacheAndPubSubConfig {

    public static final String CACHE_INVALIDATION_TOPIC = "rc:channel:cache-invalidation";
    public static final String WRITEBACK_EVENTS_TOPIC = "rc:channel:writeback-events";
    public static final String CHANNEL_WRITEBACK_EVENTS = WRITEBACK_EVENTS_TOPIC;
    public static final Duration QUERY_CACHE_TTL = Duration.ofSeconds(300);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(QUERY_CACHE_TTL)
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(RedisSerializer.string()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(RedisSerializer.json()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public ChannelTopic cacheInvalidationTopic() {
        return new ChannelTopic(CACHE_INVALIDATION_TOPIC);
    }

    @Bean
    public ChannelTopic writebackEventsTopic() {
        return new ChannelTopic(WRITEBACK_EVENTS_TOPIC);
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
