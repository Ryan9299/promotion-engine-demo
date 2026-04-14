package com.ryan.promotion.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ryan.promotion.config.CaffeineConfig;
import com.ryan.promotion.mapper.ActivityConflictMapper;
import com.ryan.promotion.mapper.ActivityMapper;
import com.ryan.promotion.mapper.ActivityRuleMapper;
import com.ryan.promotion.model.entity.Activity;
import com.ryan.promotion.model.entity.ActivityConflict;
import com.ryan.promotion.model.entity.ActivityRule;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
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
 * <h3>L2 序列化策略（金融级安全）</h3>
 * <p>Redis 存储纯 JSON 字符串（不含 {@code @class} 类型信息），
 * 反序列化时通过显式 {@link TypeReference} 指定目标类型，
 * 彻底规避 Jackson DefaultTyping 带来的反序列化攻击面。
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
 *   <li>冲突关系：{@code promo:conflicts:{storeId}}</li>
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

    /** Redis 活动规则 Key 前缀 */
    static final String KEY_RULES = "promo:rules:";

    /** Redis 活动冲突关系 Key 前缀 */
    static final String KEY_CONFLICTS = "promo:conflicts:";

    /** Redis Pub/Sub 缓存失效广播频道 */
    static final String CHANNEL_INVALIDATE = "promo:cache:invalidate";

    /** L2 Redis TTL（分钟） */
    private static final long L2_TTL_MINUTES = 5;

    // ---------------------------------------------------------------
    // 预定义 TypeReference（显式类型，杜绝反序列化攻击）
    // ---------------------------------------------------------------

    private static final TypeReference<List<Activity>> TYPE_ACTIVITY_LIST =
            new TypeReference<>() {};

    private static final TypeReference<Map<Long, ActivityRule>> TYPE_RULE_MAP =
            new TypeReference<>() {};

    private static final TypeReference<List<ActivityConflict>> TYPE_CONFLICT_LIST =
            new TypeReference<>() {};

    // ---------------------------------------------------------------
    // 依赖注入
    // ---------------------------------------------------------------

    /** 用于 L2 缓存读写和 Pub/Sub，存储纯 JSON 字符串 */
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ActivityMapper activityMapper;
    private final ActivityRuleMapper activityRuleMapper;
    private final ActivityConflictMapper activityConflictMapper;

    /** Spring MVC 的 ObjectMapper（已注册 JavaTimeModule），用于 JSON 序列化/反序列化 */
    private final ObjectMapper objectMapper;

    // ---------------------------------------------------------------
    // L1 缓存实例（@PostConstruct 初始化，类型安全）
    // ---------------------------------------------------------------

    /** L1：storeId(String) → 活动列表 */
    private LoadingCache<String, List<Activity>> activityL1;

    /** L1：storeId(String) → 活动规则 Map */
    private LoadingCache<String, Map<Long, ActivityRule>> ruleL1;

    /** L1：storeId(String) → 活动冲突关系列表 */
    private LoadingCache<String, List<ActivityConflict>> conflictL1;

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

        conflictL1 = Caffeine.newBuilder()
                .maximumSize(CaffeineConfig.L1_MAX_SIZE)
                .expireAfterWrite(CaffeineConfig.L1_EXPIRE_SECONDS, TimeUnit.SECONDS)
                .recordStats()
                .build(this::loadConflictsThrough);

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
     * 获取门店活动冲突关系列表（L1 → L2 → DB）。
     * 仅返回该门店活动之间的冲突记录，供 ConflictResolveHandler 构建冲突图。
     *
     * @param storeId 门店 ID
     * @return 冲突关系列表，不为 null
     */
    public List<ActivityConflict> getConflicts(Long storeId) {
        return conflictL1.get(String.valueOf(storeId));
    }

    /**
     * 主动失效指定门店的全部缓存（活动 + 规则 + 冲突关系）。
     *
     * <p>执行顺序：
     * <ol>
     *   <li>删除 Redis 中三个 key（L2 失效）</li>
     *   <li>发布 Pub/Sub 消息，通知所有实例驱逐 Caffeine（L1 失效）</li>
     *   <li>立即驱逐当前实例的 Caffeine（不等待 Pub/Sub 回调）</li>
     * </ol>
     *
     * @param storeId 需要失效的门店 ID
     */
    public void invalidate(Long storeId) {
        String key = String.valueOf(storeId);

        // 删除 L2 Redis
        stringRedisTemplate.delete(KEY_ACTIVITIES + key);
        stringRedisTemplate.delete(KEY_RULES + key);
        stringRedisTemplate.delete(KEY_CONFLICTS + key);

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
     *   <li>使用 SCAN 迭代删除所有 {@code promo:*} key，
     *       避免 KEYS 命令在大规模 Key 场景下阻塞 Redis。</li>
     *   <li>发布 {@code ALL} 广播消息，通知所有实例清空本地 Caffeine。</li>
     *   <li>立即清空当前实例 Caffeine（不等待 Pub/Sub 回调）。</li>
     * </ol>
     */
    public void invalidateAll() {
        // 使用 SCAN 迭代删除 L2 Redis key，避免阻塞
        scanAndDelete(KEY_ACTIVITIES + "*");
        scanAndDelete(KEY_RULES + "*");
        scanAndDelete(KEY_CONFLICTS + "*");

        // 广播全量失效消息
        stringRedisTemplate.convertAndSend(CHANNEL_INVALIDATE, "ALL");

        // 当前实例立即清空
        activityL1.invalidateAll();
        ruleL1.invalidateAll();
        conflictL1.invalidateAll();
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
            conflictL1.invalidateAll();
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
     *
     * <p>L2 存储为纯 JSON 字符串（不含 @class），反序列化时显式指定
     * {@code List<Activity>} 类型，杜绝反序列化攻击。
     */
    private List<Activity> loadActivitiesThrough(String storeIdKey) {
        String redisKey = KEY_ACTIVITIES + storeIdKey;

        // 查 L2 Redis（纯 JSON 字符串）
        String json = stringRedisTemplate.opsForValue().get(redisKey);
        if (json != null) {
            log.debug("活动列表 L2 命中：storeId={}", storeIdKey);
            return deserialize(json, TYPE_ACTIVITY_LIST);
        }

        // 回源 DB
        log.debug("活动列表 L2 miss，回源 DB：storeId={}", storeIdKey);
        Long storeId = Long.parseLong(storeIdKey);
        List<Activity> activities = activityMapper.selectActiveActivities(storeId, LocalDateTime.now());

        // 回填 L2（即使为空列表也写入，防止缓存穿透）
        setToRedis(redisKey, activities);
        log.debug("活动列表已写入 L2 Redis：storeId={}，数量={}", storeIdKey, activities.size());

        return activities;
    }

    /**
     * 活动规则穿透加载：L2 Redis → DB。
     * 规则的 activityId 集合依赖活动列表，因此会先触发（或复用）活动列表的缓存加载。
     */
    private Map<Long, ActivityRule> loadRulesThrough(String storeIdKey) {
        String redisKey = KEY_RULES + storeIdKey;

        // 查 L2 Redis
        String json = stringRedisTemplate.opsForValue().get(redisKey);
        if (json != null) {
            log.debug("活动规则 L2 命中：storeId={}", storeIdKey);
            return deserialize(json, TYPE_RULE_MAP);
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
        setToRedis(redisKey, ruleMap);
        log.debug("活动规则已写入 L2 Redis：storeId={}，规则数={}", storeIdKey, ruleMap.size());

        return ruleMap;
    }

    /**
     * 冲突关系穿透加载：L2 Redis → DB。
     * 先获取该门店的活动 ID 列表（走缓存），再查询这些活动之间的冲突记录。
     */
    private List<ActivityConflict> loadConflictsThrough(String storeIdKey) {
        String redisKey = KEY_CONFLICTS + storeIdKey;

        // 查 L2 Redis
        String json = stringRedisTemplate.opsForValue().get(redisKey);
        if (json != null) {
            log.debug("冲突关系 L2 命中：storeId={}", storeIdKey);
            return deserialize(json, TYPE_CONFLICT_LIST);
        }

        // 回源 DB：先获取该门店的活动 ID 列表（走缓存）
        log.debug("冲突关系 L2 miss，回源 DB：storeId={}", storeIdKey);
        List<Activity> activities = activityL1.get(storeIdKey);
        if (activities == null || activities.size() <= 1) {
            List<ActivityConflict> empty = Collections.emptyList();
            setToRedis(redisKey, empty);
            return empty;
        }

        List<Long> activityIds = activities.stream()
                .map(Activity::getId)
                .collect(Collectors.toList());
        List<ActivityConflict> conflicts = activityConflictMapper.selectConflictsByActivityIds(activityIds);

        // 回填 L2
        setToRedis(redisKey, conflicts);
        log.debug("冲突关系已写入 L2 Redis：storeId={}，记录数={}", storeIdKey, conflicts.size());

        return conflicts;
    }

    // ---------------------------------------------------------------
    // L2 Redis 序列化/反序列化（显式类型，安全核心）
    // ---------------------------------------------------------------

    /**
     * 将对象序列化为 JSON 字符串并写入 Redis L2。
     * JSON 中不包含 @class 类型信息，是纯净的业务 JSON。
     *
     * @param key  Redis key
     * @param data 业务对象
     */
    private void setToRedis(String key, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            stringRedisTemplate.opsForValue().set(key, json, L2_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (JsonProcessingException e) {
            log.error("缓存序列化失败：key={}", key, e);
        }
    }

    /**
     * 从 JSON 字符串反序列化为指定类型。
     * 通过显式 {@link TypeReference} 指定目标类型，不依赖 JSON 中的类型标记，
     * 杜绝了 Jackson DefaultTyping 反序列化攻击（CVE-2017-7525 等）。
     *
     * @param json    JSON 字符串
     * @param typeRef 目标类型引用
     * @return 反序列化后的对象，解析失败时返回该类型的空集合
     */
    private <T> T deserialize(String json, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            log.error("缓存反序列化失败：typeRef={}", typeRef.getType(), e);
            // 返回安全的空值，触发下次回源
            return emptyValue(typeRef);
        }
    }

    /**
     * 根据 TypeReference 返回类型安全的空值。
     */
    @SuppressWarnings("unchecked")
    private <T> T emptyValue(TypeReference<T> typeRef) {
        if (typeRef == TYPE_RULE_MAP) {
            return (T) Collections.emptyMap();
        }
        return (T) Collections.emptyList();
    }

    // ---------------------------------------------------------------
    // 私有方法
    // ---------------------------------------------------------------

    /**
     * 使用 SCAN 命令迭代匹配 pattern 的 key 并批量删除，避免 KEYS 阻塞 Redis。
     * 每轮 SCAN 返回的 key 立即删除，适用于中等规模的 key 清理场景。
     */
    private void scanAndDelete(String pattern) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        try (var cursor = stringRedisTemplate.scan(options)) {
            List<String> batch = new ArrayList<>();
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= 100) {
                    stringRedisTemplate.delete(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                stringRedisTemplate.delete(batch);
            }
        }
    }

    /** 驱逐当前实例的 Caffeine L1 缓存（活动 + 规则 + 冲突关系） */
    private void evictLocal(String storeIdKey) {
        activityL1.invalidate(storeIdKey);
        ruleL1.invalidate(storeIdKey);
        conflictL1.invalidate(storeIdKey);
    }
}
