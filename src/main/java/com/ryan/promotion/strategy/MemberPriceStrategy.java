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
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

/**
 * 会员等级价策略。
 *
 * <p>根据订单会员等级判断是否命中配置的等级范围，命中后按折扣率计算优惠金额。
 * 会员价作用于订单原始总金额，产生的优惠额将作为后续折扣、满减的基准基价。
 *
 * <p>rule_json 格式：
 * <pre>{@code
 * {
 *   "memberLevels": ["GOLD", "PLATINUM", "DIAMOND"],
 *   "discountRate": 0.90
 * }
 * }</pre>
 *
 * <p>计算公式：优惠金额 = 原始金额 × (1 - 折扣率)，结果保留两位小数 HALF_UP。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberPriceStrategy implements PromotionStrategy {

    private final ObjectMapper objectMapper;

    @Override
    public PromotionType getType() {
        return PromotionType.MEMBER_PRICE;
    }

    /**
     * 计算会员等级价优惠。
     * 若会员等级为空或不在配置范围内，直接返回零优惠结果。
     */
    @Override
    public CalcResult calculate(OrderContext orderContext, ActivityRule rule) {
        MemberPriceRule memberPriceRule = parseRule(rule);

        // 会员未登录或等级不在命中列表中，跳过
        if (!StringUtils.hasText(orderContext.getMemberLevel())
                || !memberPriceRule.getMemberLevels().contains(orderContext.getMemberLevel())) {
            log.debug("活动[{}]会员等级不命中，memberId={}, memberLevel={}",
                    rule.getActivityId(), orderContext.getMemberId(), orderContext.getMemberLevel());
            return noDiscount(rule);
        }

        BigDecimal discountRate = memberPriceRule.getDiscountRate();
        // 优惠金额 = 总额 × (1 - 折扣率)
        BigDecimal discountAmount = orderContext.getTotalAmount()
                .multiply(BigDecimal.ONE.subtract(discountRate))
                .setScale(2, RoundingMode.HALF_UP);

        String description = String.format("会员%s折优惠（%s等级）",
                discountRate.multiply(new BigDecimal("10")).stripTrailingZeros().toPlainString(),
                orderContext.getMemberLevel());

        log.debug("活动[{}]会员价命中，等级={}，折扣率={}，优惠={}",
                rule.getActivityId(), orderContext.getMemberLevel(), discountRate, discountAmount);

        return CalcResult.builder()
                .activityId(rule.getActivityId())
                .promotionType(PromotionType.MEMBER_PRICE)
                .discountAmount(discountAmount)
                .description(description)
                .build();
    }

    // ---------------------------------------------------------------
    // 私有方法
    // ---------------------------------------------------------------

    /** 解析 rule_json 为强类型规则对象 */
    private MemberPriceRule parseRule(ActivityRule rule) {
        try {
            return objectMapper.readValue(rule.getRuleJson(), MemberPriceRule.class);
        } catch (Exception e) {
            throw BusinessException.ruleParseError(rule.getActivityId(), e);
        }
    }

    /** 构造零优惠结果 */
    private CalcResult noDiscount(ActivityRule rule) {
        return CalcResult.builder()
                .activityId(rule.getActivityId())
                .promotionType(PromotionType.MEMBER_PRICE)
                .discountAmount(BigDecimal.ZERO)
                .description("会员等级不满足条件")
                .build();
    }

    // ---------------------------------------------------------------
    // 规则 POJO
    // ---------------------------------------------------------------

    /**
     * 会员等级价规则参数，对应 rule_json 的反序列化结果。
     */
    @Data
    static class MemberPriceRule {

        /** 命中的会员等级列表，如 ["GOLD", "PLATINUM", "DIAMOND"] */
        private List<String> memberLevels = Collections.emptyList();

        /**
         * 折扣率，取值 (0, 1]，如 0.90 表示九折。
         * 禁止使用 double，Jackson 反序列化时指定为 BigDecimal。
         */
        private BigDecimal discountRate = BigDecimal.ONE;
    }
}
