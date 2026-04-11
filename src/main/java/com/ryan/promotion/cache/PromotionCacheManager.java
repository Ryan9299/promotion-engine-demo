package com.ryan.promotion.cache;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ryan.promotion.config.CaffeineConfig;
import com.ryan.promotion.mapper.ActivityMapper;
import com.ryan.promotion.mapper.ActivityRuleMapper;
import com.ryan.promotion.model.entity.Activity;
import com.ryan.promotion.model.entity.ActivityRule;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 促销引擎两级缓存管理器（L1=Caffeine 本地缓存，L2=Redis 分布式缓存）。
 *
 * <h3>缓存分层</h3>
 * <pre>
 *   请求
 *    ↓ get(storeId)
 *   L1 Caffeine（30s TTL，单进程）
 *    ↓ miss
 *   L2 Redis（5min TTL，跨实例共享）
 *    ↓ miss
 *   DB（回填 L2 → L1 自动填充）
 * </pre>
 *
 * <h3>缓存失效策略</h3>
 * <ol>
 *   <li>活动变更时调用 {@link #invalidate(Long)} 删除 Redis key。</li>
 *   <li>通过 Pub/Sub 频道 {@value #CHANNEL_INVALIDATE} 广播失效消息。</li>
 *   <li>各实例收到消息后立即驱逐本地 Caffeine，避免脏读。</li>
 * </ol>
 *
 * <h3>缓存 Key 规则</h3>
 * <ul>
 *   <li>活动列表：{@code promo:activities:{storeId}}</li>
 *   <li>活动规则：{@code promo:rules:{storeId}}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromotionCacheManager implements MessageListener {

    // ---------------------------------------------------------------
    // 常量
    // ---------------------------------------------------------------

    /** Redis 活动列表 Key 前缀 */
    static final String KEY_ACTIVITIES = "promo:activities:";

    /** Redis 活动规则 Key 前缀（CLAUDE.md 规范） */
    static final String KEY_RULES = "promo:rules:";

    /** Redis Pub/Sub 缓存失效广播频道 */
    static final String CHANNEL_INVALIDATE = "promo:cache:invalidate";

    /** L2 Redis TTL（分钟） */
    private static final long L2_TTL_MINUTES = 5;

    // ---------------------------------------------------------------
    // 依赖注入
    // ---------------------------------------------------------------

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ActivityMapper activityMapper;
    private final ActivityRuleMapper activityRuleMapper;

    // ---------------------------------------------------------------
    // L1 缓存实例（@PostConstruct 初始化，类型安全）
    // ---------------------------------------------------------------

    /** L1：storeId(String) → 活动列表 */
    private LoadingCache<String, List<Activity>> activityL1;

    /** L1：storeId(String) → 活动规则 Map */
    private LoadingCache<String, Map<Long, ActivityRule>> ruleL1;

    /**
     * 初始化 L1 LoadingCache 并注册 Pub/Sub 监听器。
     * LoadingCache 的 loader 函数实现 L2→DB 的自动穿透逻辑。
     */
    @PostConstruct
    public void init() {
        // 使用与 CaffeineConfig 相同的规格（maximumSize=1000, expireAfterWrite=30s）
        activityL1 = Caffeine.newBuilder()
                .maximumSize(CaffeineConfig.L1_MAX_SIZE)
                .expireAfterWrite(CaffeineConfig.L1_EXPIRE_SECONDS, TimeUnit.SECONDS)
                .recordStats()
                .build(this::loadActivitiesThrough);

        ruleL1 = Caffeine.newBuilder()
                .maximumSize(CaffeineConfig.L1_MAX_SIZE)
                .expireAfterWrite(CaffeineConfig.L1_EXPIRE_SECONDS, TimeUnit.SECONDS)
                .recordStats()
                .build(this::loadRulesThrough);

        // 注册 Pub/Sub 监听：收到失效消息后驱逐本地 Caffeine
        listenerContainer.addMessageListener(this, new ChannelTopic(CHANNEL_INVALIDATE));
        log.info("PromotionCacheManager 初始化完成，已订阅 Pub/Sub 频道: {}", CHANNEL_INVALIDATE);
    }

    // ---------------------------------------------------------------
    // 公共 API
    // ---------------------------------------------------------------

    /**
     * 获取门店当前有效活动列表（L1 → L2 → DB）。
     * LoadingCache 自动执行穿透加载，调用方无需关心缓存层细节。
     *
     * @param storeId 门店 ID
     * @return 有效活动列表（ACTIVE 或 GRAY 状态，按优先级降序），不为 null
     */
    public List<Activity> getActivities(Long storeId) {
        return activityL1.get(String.valueOf(storeId));
    }

    /**
     * 获取门店活动规则 Map（L1 → L2 → DB），key=activityId。
     *
     * @param storeId 门店 ID
     * @return activityId → ActivityRule 的映射，不为 null
     */
    public Map<Long, ActivityRule> getRules(Long storeId) {
        return ruleL1.get(String.valueOf(storeId));
    }

    /**
     * 主动失效指定门店的全部缓存（活动 + 规则）。
     *
     * <p>执行顺序：
     * <ol>
     *   <li>删除 Redis 中两个 key（L2 失效）</li>
     *   <li>发布 Pub/Sub 消息，通知所有实例驱逐 Caffeine（L1 失效）</li>
     *   <li>立即驱逐当前实例的 Caffeine（不等待 Pub/Sub 回调）</li>
     * </ol>
     *
     * @param storeId 需要失效的门店 ID
     */
    public void invalidate(Long storeId) {
        String key = String.valueOf(storeId);

        // 删除 L2 Redis
        redisTemplate.delete(KEY_ACTIVITIES + key);
        redisTemplate.delete(KEY_RULES + key);

        // 广播失效消息（触发其他实例的 Caffeine 驱逐）
        stringRedisTemplate.convertAndSend(CHANNEL_INVALIDATE, key);

        // 当前实例立即驱逐，不依赖 Pub/Sub 回调延迟
        evictLocal(key);
        log.info("缓存失效：storeId={}，已清除 L1+L2，已广播失效消息", storeId);
    }

    // ---------------------------------------------------------------
    // Pub/Sub 回调
    // ---------------------------------------------------------------

    /**
     * 全量失效所有门店的缓存，用于活动上下线、规则变更等影响全局的操作。
     *
     * <p>执行顺序：
     * <ol>
     *   <li>使用 KEYS 扫描删除所有 {@code promo:activities:*} 和 {@code promo:rules:*} key。</li>
     *   <li>发布 {@code ALL} 广播消息，通知所有实例清空本地 Caffeine。</li>
     *   <li>立即清空当前实例 Caffeine（不等待 Pub/Sub 回调）。</li>
     * </ol>
     *
     * <p>注意：生产环境大规模 Key 场景下，建议将 KEYS 替换为 SCAN 以避免阻塞 Redis。
     */
    public void invalidateAll() {
        // 删除 L2 Redis 中所有活动和规则的 key
        var activityKeys = redisTemplate.keys(KEY_ACTIVITIES + "*");
        var ruleKeys = redisTemplate.keys(KEY_RULES + "*");
        if (activityKeys != null && !activityKeys.isEmpty()) {
            redisTemplate.delete(activityKeys);
        }
        if (ruleKeys != null && !ruleKeys.isEmpty()) {
            redisTemplate.delete(ruleKeys);
        }

        // 广播全量失效消息
        stringRedisTemplate.convertAndSend(CHANNEL_INVALIDATE, "ALL");

        // 当前实例立即清空
        activityL1.invalidateAll();
        ruleL1.invalidateAll();
        log.info("全量缓存失效完成，已清除所有 L1+L2 缓存并广播 ALL 消息");
    }

    /**
     * 接收 Redis Pub/Sub 缓存失效消息，驱逐本实例对应的 Caffeine 缓存。
     * 消息体为 storeId 字符串（单门店失效）或 {@code ALL}（全量失效）。
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody());
        if ("ALL".equals(payload)) {
            activityL1.invalidateAll();
            ruleL1.invalidateAll();
            log.info("收到全量缓存失效 Pub/Sub 通知，已清空本地所有 Caffeine 缓存");
        } else {
            evictLocal(payload);
            log.info("收到缓存失效 Pub/Sub 通知，已清除本地 Caffeine：storeId={}", payload);
        }
    }

    // ---------------------------------------------------------------
    // 穿透加载（Caffeine LoadingCache 的 loader 函数）
    // ---------------------------------------------------------------

    /**
     * 活动列表穿透加载：L2 Redis → DB。
     * 由 Caffeine LoadingCache 在 L1 miss 时自动调用。
     */
    @SuppressWarnings("unchecked")
    private List<Activity> loadActivitiesThrough(String storeIdKey) {
        String redisKey = KEY_ACTIVITIES + storeIdKey;

        // 查 L2 Redis
        Object cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            log.debug("活动列表 L2 命中：storeId={}", storeIdKey);
            return (List<Activity>) cached;
        }

        // 回源 DB
        log.debug("活动列表 L2 miss，回源 DB：storeId={}", storeIdKey);
        Long storeId = Long.parseLong(storeIdKey);
        List<Activity> activities = activityMapper.selectActiveActivities(storeId, LocalDateTime.now());

        // 回填 L2（即使为空列表也写入，防止缓存穿透；TTL 短于正常 TTL 可进一步优化）
        redisTemplate.opsForValue().set(redisKey, activities, L2_TTL_MINUTES, TimeUnit.MINUTES);
        log.debug("活动列表已写入 L2 Redis：storeId={}，数量={}", storeIdKey, activities.size());

        return activities;
    }

    /**
     * 活动规则穿透加载：L2 Redis → DB。
     * 规则的 activityId 集合依赖活动列表，因此会先触发（或复用）活动列表的缓存加载。
     */
    @SuppressWarnings("unchecked")
    private Map<Long, ActivityRule> loadRulesThrough(String storeIdKey) {
        String redisKey = KEY_RULES + storeIdKey;

        // 查 L2 Redis
        Object cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            log.debug("活动规则 L2 命中：storeId={}", storeIdKey);
            return (Map<Long, ActivityRule>) cached;
        }

        // 回源 DB：先获取该门店的活动 ID 列表（走缓存，避免重复查库）
        log.debug("活动规则 L2 miss，回源 DB：storeId={}", storeIdKey);
        List<Activity> activities = activityL1.get(storeIdKey);
        if (activities == null || activities.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Long> activityIds = activities.stream()
                .map(Activity::getId)
                .collect(Collectors.toList());
        List<ActivityRule> rules = activityRuleMapper.selectByActivityIds(activityIds);
        Map<Long, ActivityRule> ruleMap = rules.stream()
                .collect(Collectors.toMap(ActivityRule::getActivityId, Function.identity()));

        // 回填 L2
        redisTemplate.opsForValue().set(redisKey, ruleMap, L2_TTL_MINUTES, TimeUnit.MINUTES);
        log.debug("活动规则已写入 L2 Redis：storeId={}，规则数={}", storeIdKey, ruleMap.size());

        return ruleMap;
    }

    // ---------------------------------------------------------------
    // 私有方法
    // ---------------------------------------------------------------

    /** 驱逐当前实例的 Caffeine L1 缓存（活动 + 规则） */
    private void evictLocal(String storeIdKey) {
        activityL1.invalidate(storeIdKey);
        ruleL1.invalidate(storeIdKey);
    }
}
