package com.ryan.promotion.handler;

import com.ryan.promotion.cache.PromotionCacheManager;
import com.ryan.promotion.gray.GrayRuleEvaluator;
import com.ryan.promotion.model.entity.Activity;
import com.ryan.promotion.model.entity.ActivityRule;
import com.ryan.promotion.model.enums.ActivityStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 责任链第1步：活动筛选处理器。
 *
 * <p>负责筛选当前请求命中的活动列表，写入 {@code context.matchedActivities} 和 {@code context.ruleMap}：
 * <ol>
 *   <li>通过 {@link PromotionCacheManager} 获取活动（L1 Caffeine → L2 Redis → DB）。</li>
 *   <li>状态为 GRAY 的活动额外调用 {@link GrayRuleEvaluator} 做多维度灰度判断。</li>
 *   <li>ACTIVE 活动无需灰度判断，直接命中。</li>
 * </ol>
 *
 * <p>规则通过 {@code PromotionCacheManager.getRules(storeId)} 批量预加载，
 * 避免后续 Handler 逐条查库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityFilterHandler extends PromotionHandler {

    private final PromotionCacheManager cacheManager;
    private final GrayRuleEvaluator grayRuleEvaluator;

    /**
     * 执行活动筛选：从缓存取候选活动，过滤灰度未命中活动，预加载规则到 context。
     */
    @Override
    public void handle(PromotionContext context) {
        Long storeId = context.getOrderContext().getStoreId();

        // L1 → L2 → DB，内部按优先级降序排列
        List<Activity> candidates = cacheManager.getActivities(storeId);

        // 过滤 GRAY 活动中灰度不命中的（ACTIVE 活动直接通过）
        List<Activity> matched = candidates.stream()
                .filter(activity -> {
                    if (activity.getStatus() == ActivityStatus.GRAY) {
                        boolean hit = grayRuleEvaluator.evaluate(activity, context.getOrderContext());
                        if (!hit) {
                            log.debug("活动[{}]灰度未命中，跳过", activity.getId());
                        }
                        return hit;
                    }
                    return true;
                })
                .collect(Collectors.toList());

        log.debug("门店[{}]候选活动 {}，命中活动 {}", storeId, candidates.size(), matched.size());

        context.setMatchedActivities(matched);

        if (matched.isEmpty()) {
            passToNext(context);
            return;
        }

        // 从缓存获取规则（同样走 L1→L2→DB），过滤出命中活动对应的规则
        Map<Long, ActivityRule> allRules = cacheManager.getRules(storeId);
        Map<Long, ActivityRule> ruleMap = matched.stream()
                .filter(a -> allRules.containsKey(a.getId()))
                .collect(Collectors.toMap(Activity::getId, a -> allRules.get(a.getId())));

        context.setRuleMap(ruleMap);
        passToNext(context);
    }
}
