package com.ryan.promotion.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置类。
 *
 * <p>配置两个核心 Bean：
 * <ol>
 *   <li>{@code RedisTemplate<String, Object>}：Key 使用 StringRedisSerializer，
 *       Value 使用 GenericJackson2JsonRedisSerializer（含类型信息），
 *       支持 List/Map 等复杂对象的序列化与反序列化。</li>
 *   <li>{@code RedisMessageListenerContainer}：用于 Pub/Sub 订阅，
 *       {@link com.ryan.promotion.cache.PromotionCacheManager} 在
 *       {@code @PostConstruct} 阶段向此容器注册监听器。</li>
 * </ol>
 */
@Configuration
public class RedisConfig {

    /**
     * 配置 RedisTemplate，Key 为 String，Value 为 JSON（含 @class 类型信息）。
     *
     * <p>Value 序列化器使用独立的 ObjectMapper（开启默认类型推断），
     * 与 Spring MVC 的 Jackson ObjectMapper 隔离，避免影响 REST 响应格式。
     *
     * @param connectionFactory Lettuce 连接工厂，由 Spring Boot 自动配置
     * @return 配置完成的 RedisTemplate
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        // 独立配置 Redis 专用 ObjectMapper，开启多态类型信息存储
        ObjectMapper redisObjectMapper = new ObjectMapper();
        redisObjectMapper.registerModule(new JavaTimeModule());
        redisObjectMapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Redis Pub/Sub 消息监听容器。
     *
     * <p>由 {@link com.ryan.promotion.cache.PromotionCacheManager} 在启动时
     * 向此容器注册频道监听器，用于跨实例的 Caffeine 缓存失效通知。
     *
     * @param connectionFactory Redis 连接工厂
     * @return 消息监听容器
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
