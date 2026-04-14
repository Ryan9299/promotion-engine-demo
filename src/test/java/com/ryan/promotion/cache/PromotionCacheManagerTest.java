package com.ryan.promotion.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ryan.promotion.mapper.ActivityConflictMapper;
import com.ryan.promotion.mapper.ActivityMapper;
import com.ryan.promotion.mapper.ActivityRuleMapper;
import com.ryan.promotion.model.entity.Activity;
import com.ryan.promotion.model.entity.ActivityRule;
import com.ryan.promotion.model.enums.ActivityStatus;
import com.ryan.promotion.model.enums.PromotionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.ryan.promotion.cache.PromotionCacheManager.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PromotionCacheManager 单元测试（纯 Mockito，不启动 Spring 容器）。
 * 覆盖：L1 命中、L1 穿透 L2 命中、L1+L2 双穿透回源 DB、缓存失效后重新加载。
 *
 * <p>L2 层改为 StringRedisTemplate + 显式 TypeReference，
 * 测试中使用真实 ObjectMapper 做序列化/反序列化。
 */
@DisplayName("PromotionCacheManager 缓存测试")
@ExtendWith(MockitoExtension.class)
class PromotionCacheManagerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RedisMessageListenerContainer listenerContainer;

    @Mock
    private ActivityMapper activityMapper;

    @Mock
    private ActivityRuleMapper activityRuleMapper;

    @Mock
    private ActivityConflictMapper activityConflictMapper;

    @Mock
    private ValueOperations<String, String> stringValueOps;

    private ObjectMapper objectMapper;

    private PromotionCacheManager cacheManager;

    private static final Long STORE_ID = 101L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOps);

        cacheManager = new PromotionCacheManager(
                stringRedisTemplate, listenerContainer,
                activityMapper, activityRuleMapper, activityConflictMapper,
                objectMapper);
        // 手动触发 @PostConstruct（测试环境不启动 Spring）
        cacheManager.init();
    }

    // ------------------------------------------------------------------
    // L1 命中（二次调用不再穿透）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("L1 命中：第一次加载后第二次直接返回 Caffeine 缓存，DB 仅调用一次")
    void getActivities_l1Hit_dbCalledOnlyOnce() {
        Activity activity = sampleActivity(1001L);
        when(stringValueOps.get(KEY_ACTIVITIES + STORE_ID)).thenReturn(null); // L2 miss
        when(activityMapper.selectActiveActivities(eq(STORE_ID), any()))
                .thenReturn(List.of(activity));

        // 第一次调用（L1 miss → L2 miss → DB）
        List<Activity> first = cacheManager.getActivities(STORE_ID);
        // 第二次调用（L1 命中）
        List<Activity> second = cacheManager.getActivities(STORE_ID);

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        // DB 仅被调用一次
        verify(activityMapper, times(1)).selectActiveActivities(eq(STORE_ID), any());
    }

    // ------------------------------------------------------------------
    // L1 穿透，L2 命中
    // ------------------------------------------------------------------

    @Test
    @DisplayName("L1 miss + L2 命中：从 Redis 返回 JSON 字符串反序列化，不查 DB")
    void getActivities_l1MissL2Hit_dbNotCalled() throws Exception {
        List<Activity> cached = List.of(sampleActivity(1001L));
        // Redis 返回 JSON 字符串（L2 命中）
        String json = objectMapper.writeValueAsString(cached);
        when(stringValueOps.get(KEY_ACTIVITIES + STORE_ID)).thenReturn(json);

        List<Activity> result = cacheManager.getActivities(STORE_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1001L);
        verify(activityMapper, never()).selectActiveActivities(any(), any());
    }

    // ------------------------------------------------------------------
    // L1+L2 双穿透，回源 DB
    // ------------------------------------------------------------------

    @Test
    @DisplayName("L1+L2 双穿透：查 DB 并将结果以 JSON 字符串回填 Redis L2")
    void getActivities_bothMiss_dbCalledAndResultCachedToRedis() {
        Activity activity = sampleActivity(1001L);
        when(stringValueOps.get(KEY_ACTIVITIES + STORE_ID)).thenReturn(null); // L2 miss
        when(activityMapper.selectActiveActivities(eq(STORE_ID), any()))
                .thenReturn(List.of(activity));

        List<Activity> result = cacheManager.getActivities(STORE_ID);

        assertThat(result).hasSize(1);
        // 回填 Redis（以 JSON 字符串形式）
        verify(stringValueOps, times(1))
                .set(eq(KEY_ACTIVITIES + STORE_ID), anyString(), anyLong(), any());
    }

    // ------------------------------------------------------------------
    // 缓存失效后重新加载
    // ------------------------------------------------------------------

    @Test
    @DisplayName("invalidate 后再次 getActivities：L1 被驱逐，重新穿透到 DB")
    void getActivities_afterInvalidate_dbCalledAgain() {
        Activity activity = sampleActivity(1001L);
        when(stringValueOps.get(KEY_ACTIVITIES + STORE_ID)).thenReturn(null); // L2 always miss
        when(activityMapper.selectActiveActivities(eq(STORE_ID), any()))
                .thenReturn(List.of(activity));

        // 第一次：加载并缓存到 L1
        cacheManager.getActivities(STORE_ID);

        // 失效该门店缓存
        cacheManager.invalidate(STORE_ID);

        // 第二次：L1 已失效，再次穿透
        cacheManager.getActivities(STORE_ID);

        // DB 应被调用两次
        verify(activityMapper, times(2)).selectActiveActivities(eq(STORE_ID), any());
    }

    @Test
    @DisplayName("getRules - L1+L2 双穿透：查活动列表再查规则，结果以 JSON 回填 Redis")
    void getRules_bothMiss_queriesDbAndCachesToRedis() {
        Activity activity = sampleActivity(1001L);
        ActivityRule rule = ActivityRule.builder()
                .id(1L).activityId(1001L).ruleJson("{\"discountRate\":0.9}").build();

        // activities L2 miss → DB
        when(stringValueOps.get(KEY_ACTIVITIES + STORE_ID)).thenReturn(null);
        when(activityMapper.selectActiveActivities(eq(STORE_ID), any()))
                .thenReturn(List.of(activity));
        // rules L2 miss → DB
        when(stringValueOps.get(KEY_RULES + STORE_ID)).thenReturn(null);
        when(activityRuleMapper.selectByActivityIds(anyList()))
                .thenReturn(List.of(rule));

        Map<Long, ActivityRule> ruleMap = cacheManager.getRules(STORE_ID);

        assertThat(ruleMap).containsKey(1001L);
        verify(activityRuleMapper, times(1)).selectByActivityIds(anyList());
        // 规则结果也写入 Redis（JSON 字符串）
        verify(stringValueOps, times(1))
                .set(eq(KEY_RULES + STORE_ID), anyString(), anyLong(), any());
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private Activity sampleActivity(long id) {
        return Activity.builder()
                .id(id).storeId(STORE_ID).name("测试活动-" + id)
                .type(PromotionType.MEMBER_PRICE)
                .status(ActivityStatus.ACTIVE).priority(10)
                .startTime(LocalDateTime.now().minusDays(1))
                .endTime(LocalDateTime.now().plusDays(7))
                .build();
    }
}
