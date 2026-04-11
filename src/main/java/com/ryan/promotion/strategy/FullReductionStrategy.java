package com.ryan.promotion.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ryan.promotion.common.exception.BusinessException;
import com.ryan.promotion.model.dto.CalcResult;
import com.ryan.promotion.model.dto.OrderContext;
import com.ryan.promotion.model.entity.ActivityRule;
import com.ryan.promotion.model.enums.PromotionType;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 满减策略。
 *
 * <p>支持多档梯度满减，选取当前金额能触达的最高档位执行减免。
 * 例如配置"满200减30、满500减100"时，下单450元命中"满200减30"。
 *
 * <p>rule_json 格式：
 * <pre>{@code
 * {
 *   "tiers": [
 *     {"threshold": 200, "reduction": 30},
 *     {"threshold": 500, "reduction": 100}
 *   ]
 * }
 * }</pre>
 *
 * <p>计算基准为 CalcHandler 传入的 totalAmount（已扣除会员价和折扣后的金额）。
 * 减免金额取档位中配置的固定值，不再做二次舍入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FullReductionStrategy implements PromotionStrategy {

    private final ObjectMapper objectMapper;

    @Override
    public PromotionType getType() {
        return PromotionType.FULL_REDUCTION;
    }

    /**
     * 计算满减优惠。
     * 从所有档位中找出 threshold ≤ totalAmount 的最高档，返回对应 reduction。
     * 无档位命中时返回零优惠。
     */
    @Override
    public CalcResult calculate(OrderContext orderContext, ActivityRule rule) {
        FullReductionRule fullReductionRule = parseRule(rule);

        // 找最高命中档位：threshold ≤ 当前金额，取 threshold 最大的那档
        Optional<Tier> hitTier = fullReductionRule.getTiers().stream()
                .filter(t -> orderContext.getTotalAmount().compareTo(t.getThreshold()) >= 0)
                .max(Comparator.comparing(Tier::getThreshold));

        if (hitTier.isEmpty()) {
            log.debug("活动[{}]满减未达门槛，当前金额={}",
                    rule.getActivityId(), orderContext.getTotalAmount());
            return noDiscount(rule);
        }

        Tier tier = hitTier.get();
        String description = String.format("满%.0f减%.0f",
                tier.getThreshold(), tier.getReduction());

        log.debug("活动[{}]满减命中，档位={}，优惠={}",
                rule.getActivityId(), description, tier.getReduction());

        return CalcResult.builder()
                .activityId(rule.getActivityId())
                .promotionType(PromotionType.FULL_REDUCTION)
                .discountAmount(tier.getReduction())
                .description(description)
                .build();
    }

    // ---------------------------------------------------------------
    // 私有方法
    // ---------------------------------------------------------------

    private FullReductionRule parseRule(ActivityRule rule) {
        try {
            return objectMapper.readValue(rule.getRuleJson(), FullReductionRule.class);
        } catch (Exception e) {
            throw BusinessException.ruleParseError(rule.getActivityId(), e);
        }
    }

    private CalcResult noDiscount(ActivityRule rule) {
        return CalcResult.builder()
                .activityId(rule.getActivityId())
                .promotionType(PromotionType.FULL_REDUCTION)
                .discountAmount(BigDecimal.ZERO)
                .description("未达满减门槛")
                .build();
    }

    // ---------------------------------------------------------------
    // 规则 POJO
    // ---------------------------------------------------------------

    /**
     * 满减规则，包含一个或多个梯度档位。
     */
    @Data
    static class FullReductionRule {

        /** 满减梯度列表，至少配置一档 */
        private List<Tier> tiers = Collections.emptyList();
    }

    /**
     * 满减单档配置。
     */
    @Data
    static class Tier {

        /** 满减门槛金额，单位：元，使用 BigDecimal 保证精度 */
        private BigDecimal threshold = BigDecimal.ZERO;

        /** 减免金额，单位：元 */
        private BigDecimal reduction = BigDecimal.ZERO;
    }
}
