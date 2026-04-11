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
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

/**
 * 折扣策略。
 *
 * <p>支持两种折扣模式：
 * <ol>
 *   <li><b>全单折扣</b>：{@code skuIds} 为空时，对 {@code orderContext.totalAmount} 整体打折。</li>
 *   <li><b>单品折扣</b>：{@code skuIds} 非空时，仅对命中 SKU 的小计之和打折，其余商品原价。</li>
 * </ol>
 *
 * <p>rule_json 格式：
 * <pre>{@code
 * {
 *   "discountRate": 0.88,
 *   "minOrderAmount": 0,
 *   "skuIds": []
 * }
 * }</pre>
 *
 * <p>计算公式：优惠金额 = 参与折扣的金额 × (1 - 折扣率)，保留两位小数 HALF_UP。
 * 折扣基于上一步（会员价）处理后的 {@code totalAmount}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiscountStrategy implements PromotionStrategy {

    private final ObjectMapper objectMapper;

    @Override
    public PromotionType getType() {
        return PromotionType.DISCOUNT;
    }

    /**
     * 计算折扣优惠。
     * totalAmount 已由 CalcHandler 调整为会员价后的基准金额。
     */
    @Override
    public CalcResult calculate(OrderContext orderContext, ActivityRule rule) {
        DiscountRule discountRule = parseRule(rule);

        // 最低消费门槛校验
        if (orderContext.getTotalAmount().compareTo(discountRule.getMinOrderAmount()) < 0) {
            log.debug("活动[{}]未达折扣门槛，当前金额={}，门槛={}",
                    rule.getActivityId(), orderContext.getTotalAmount(), discountRule.getMinOrderAmount());
            return noDiscount(rule);
        }

        // 计算参与折扣的金额：单品模式 or 全单模式
        BigDecimal baseAmount = calcBaseAmount(orderContext, discountRule);
        if (baseAmount.compareTo(BigDecimal.ZERO) == 0) {
            return noDiscount(rule);
        }

        BigDecimal discountAmount = baseAmount
                .multiply(BigDecimal.ONE.subtract(discountRule.getDiscountRate()))
                .setScale(2, RoundingMode.HALF_UP);

        // 折扣率转换为"X折"文案：0.88 → "8.8折"
        String discountText = discountRule.getDiscountRate()
                .multiply(new BigDecimal("10"))
                .stripTrailingZeros().toPlainString();
        String description = discountRule.getSkuIds().isEmpty()
                ? String.format("全单%s折优惠", discountText)
                : String.format("指定商品%s折优惠", discountText);

        log.debug("活动[{}]折扣命中，折扣率={}，参与金额={}，优惠={}",
                rule.getActivityId(), discountRule.getDiscountRate(), baseAmount, discountAmount);

        return CalcResult.builder()
                .activityId(rule.getActivityId())
                .promotionType(PromotionType.DISCOUNT)
                .discountAmount(discountAmount)
                .description(description)
                .build();
    }

    // ---------------------------------------------------------------
    // 私有方法
    // ---------------------------------------------------------------

    /**
     * 计算参与折扣的基准金额。
     * skuIds 为空时返回 totalAmount；否则仅汇总命中 SKU 的 subtotal。
     */
    private BigDecimal calcBaseAmount(OrderContext orderContext, DiscountRule discountRule) {
        if (discountRule.getSkuIds().isEmpty()) {
            return orderContext.getTotalAmount();
        }
        return orderContext.getItems().stream()
                .filter(item -> discountRule.getSkuIds().contains(item.getSkuId()))
                .map(OrderContext.OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private DiscountRule parseRule(ActivityRule rule) {
        try {
            return objectMapper.readValue(rule.getRuleJson(), DiscountRule.class);
        } catch (Exception e) {
            throw BusinessException.ruleParseError(rule.getActivityId(), e);
        }
    }

    private CalcResult noDiscount(ActivityRule rule) {
        return CalcResult.builder()
                .activityId(rule.getActivityId())
                .promotionType(PromotionType.DISCOUNT)
                .discountAmount(BigDecimal.ZERO)
                .description("不满足折扣条件")
                .build();
    }

    // ---------------------------------------------------------------
    // 规则 POJO
    // ---------------------------------------------------------------

    /**
     * 折扣规则参数，对应 rule_json 的反序列化结果。
     */
    @Data
    static class DiscountRule {

        /**
         * 折扣率，取值 (0, 1]，如 0.88 表示 8.8 折。
         * 必须使用 BigDecimal，禁止 double。
         */
        private BigDecimal discountRate = BigDecimal.ONE;

        /** 最低参与金额，0 表示无门槛，单位：元 */
        private BigDecimal minOrderAmount = BigDecimal.ZERO;

        /**
         * 参与折扣的 SKU ID 列表。
         * 为空列表时表示全单折扣；非空时仅对列表内 SKU 打折。
         */
        private List<String> skuIds = Collections.emptyList();
    }
}
