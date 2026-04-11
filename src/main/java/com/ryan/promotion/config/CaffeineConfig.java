package com.ryan.promotion.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置。
 *
 * <p>提供全局统一的 Caffeine 构建器 Bean：
 * <ul>
 *   <li>maximumSize = 1000：单缓存最大条目数，超出后按 W-TinyLFU 策略淘汰。</li>
 *   <li>expireAfterWrite = 30s：写入后 30 秒过期，保证数据时效性。</li>
 *   <li>recordStats：开启命中率统计，可通过 {@code Cache#stats()} 监控缓存效果。</li>
 * </ul>
 *
 * <p>{@link com.ryan.promotion.cache.PromotionCacheManager} 使用相同规格独立构建
 * 业务 LoadingCache，以保留泛型类型安全。此 Bean 主要作为配置规格的统一声明。
 */
@Configuration
public class CaffeineConfig {

    /** L1 缓存最大条目数 */
    public static final long L1_MAX_SIZE = 1000;

    /** L1 缓存写入后过期时间（秒） */
    public static final long L1_EXPIRE_SECONDS = 30;

    /**
     * 全局 Caffeine 构建器 Bean，配置了统一的大小限制和过期策略。
     * 各业务缓存可直接调用 {@code .build(loader)} 创建 LoadingCache 实例。
     *
     * @return 配置好规格的 Caffeine 构建器
     */
    @Bean
    public Caffeine<Object, Object> caffeineSpec() {
        return Caffeine.newBuilder()
                .maximumSize(L1_MAX_SIZE)
                .expireAfterWrite(L1_EXPIRE_SECONDS, TimeUnit.SECONDS)
                .recordStats();
    }
}
