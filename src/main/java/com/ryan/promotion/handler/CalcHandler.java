package com.ryan.promotion.handler;

import com.ryan.promotion.model.dto.CalcResult;
import com.ryan.promotion.model.dto.OrderContext;
import com.ryan.promotion.model.entity.Activity;
import com.ryan.promotion.model.entity.ActivityRule;
import com.ryan.promotion.model.enums.PromotionType;
import com.ryan.promotion.strategy.PromotionStrategy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 责任链第3步：促销计算处理器。
 *
 * <p>按固定顺序依次调用对应类型的 {@link PromotionStrategy}，
 * 计算每个活动的优惠金额，并维护一个<b>滚动基准价</b>：
 * <pre>
 *   currentAmount = originalAmount
 *   1. MEMBER_PRICE → discountAmount = currentAmount × (1 - rate)
 *      currentAmount -= discountAmount
 *   2. DISCOUNT     → discountAmount = currentAmount × (1 - rate)
 *      currentAmount -= discountAmount
 *   3. FULL_REDUCTION → discountAmount = tier.reduction（若达门槛）
 *      currentAmount -= discountAmount
 *   4. GIFT         → 赠品，不减 currentAmount
 * </pre>
 *
 * <p>每步均将更新后的 currentAmount 作为 {@code OrderContext.totalAmount} 传入下一策略，
 * 保证"折扣基于会员价，满减基于折后价"的计算语义。
 *
 * <p>计算完成后，每个 {@link CalcResult} 会被补充 {@code activityName} 字段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalcHandler extends PromotionHandler {

    /** 策略计算的固定顺序 */
    private static final List<PromotionType> CALC_ORDER = List.of(
            PromotionType.MEMBER_PRICE,
            PromotionType.DISCOUNT,
            PromotionType.FULL_REDUCTION,
            PromotionType.GIFT
    );

    private final List<PromotionStrategy> strategies;

    /** 按类型索引的策略 Map，启动时初始化 */
    private Map<PromotionType, PromotionStrategy> strategyMap;

    /**
     * 初始化策略 Map，按 getType() 建立索引。
     * Spring 自动收集容器内所有 PromotionStrategy 实现注入 strategies 列表。
     */
    @PostConstruct
    public void init() {
        strategyMap = strategies.stream()
                .collect(Collectors.toMap(PromotionStrategy::getType, Function.identity()));
        log.info("促销策略加载完成：{}", strategyMap.keySet());
    }

    /**
     * 按计算顺序依次执行各活动的优惠计算，填充 calcResults。
     */
    @Override
    public void handle(PromotionContext context) {
        List<Activity> resolved = context.getResolvedActivities();
        if (resolved.isEmpty()) {
            context.setCalcResults(new ArrayList<>());
            passToNext(context);
            return;
        }

        // 按活动类型分组，保持 priority 降序（来自上游已排序）
        Map<PromotionType, List<Activity>> byType = resolved.stream()
                .collect(Collectors.groupingBy(Activity::getType,
                        Collectors.toList()));

        OrderContext originalCtx = context.getOrderContext();
        // currentAmount 随每步计算滚动更新（GIFT 除外）
        BigDecimal currentAmount = originalCtx.getTotalAmount();
        List<CalcResult> results = new ArrayList<>();

        for (PromotionType type : CALC_ORDER) {
            List<Activity> activitiesOfType = byType.getOrDefault(type, List.of());
            PromotionStrategy strategy = strategyMap.get(type);

            if (strategy == null || activitiesOfType.isEmpty()) {
                continue;
            }

            for (Activity activity : activitiesOfType) {
                ActivityRule rule = context.getRuleMap().get(activity.getId());
                if (rule == null) {
                    log.warn("活动[{}]规则未找到，跳过计算", activity.getId());
                    continue;
                }

                // 将当前滚动金额注入 OrderContext 供策略使用
                OrderContext runningCtx = buildRunningContext(originalCtx, currentAmount);
                CalcResult result = strategy.calculate(runningCtx, rule);

                // 补充策略无法感知的活动名称
                result.setActivityName(activity.getName());

                results.add(result);

                // GIFT 类型不改变金额链路；其他类型扣减优惠后更新滚动金额
                if (type != PromotionType.GIFT
                        && result.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
                    currentAmount = currentAmount
                            .subtract(result.getDiscountAmount())
                            .max(BigDecimal.ZERO);
                    log.debug("活动[{}]{}计算完毕，优惠={}，剩余基准金额={}",
                            activity.getId(), type, result.getDiscountAmount(), currentAmount);
                }
            }
        }

        context.setCalcResults(results);
        passToNext(context);
    }

    // ---------------------------------------------------------------
    // 私有方法
    // ---------------------------------------------------------------

    /**
     * 以原始 OrderContext 为模板，替换 totalAmount 为当前滚动金额，创建副本。
     * 使用 Builder 逐字段复制，确保 items / memberLevel 等字段不丢失。
     */
    private OrderContext buildRunningContext(OrderContext original, BigDecimal currentAmount) {
        return OrderContext.builder()
                .orderId(original.getOrderId())
                .storeId(original.getStoreId())
                .memberId(original.getMemberId())
                .memberLevel(original.getMemberLevel())
                .items(original.getItems())
                .totalAmount(currentAmount)
                .build();
    }
}
