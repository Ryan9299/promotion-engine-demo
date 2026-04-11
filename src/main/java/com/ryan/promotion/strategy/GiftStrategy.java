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
import java.util.List;

/**
 * 赠品策略。
 *
 * <p>订单金额达到指定门槛时，向 CalcResult 中添加赠品 SKU 信息。
 * 赠品不产生优惠金额（discountAmount=0），赠品信息由 ResultAssembleHandler
 * 汇总至 PromotionResultVO.gifts 列表输出。
 *
 * <p>rule_json 格式：
 * <pre>{@code
 * {
 *   "minOrderAmount": 150,
 *   "gifts": [
 *     {
 *       "giftSkuId":   "SKU_CUP_001",
 *       "giftSkuName": "定制保温杯",
 *       "giftQuantity": 1,
 *       "marketPrice":  49.90
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>支持单条规则配置多个赠品，所有赠品在门槛满足时一并赠出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GiftStrategy implements PromotionStrategy {

    private final ObjectMapper objectMapper;

    @Override
    public PromotionType getType() {
        return PromotionType.GIFT;
    }

    /**
     * 判断订单金额是否达到赠品门槛，命中后返回赠品列表。
     * 赠品活动 discountAmount 固定为 0，不影响后续价格链路。
     */
    @Override
    public CalcResult calculate(OrderContext orderContext, ActivityRule rule) {
        GiftRule giftRule = parseRule(rule);

        if (orderContext.getTotalAmount().compareTo(giftRule.getMinOrderAmount()) < 0) {
            log.debug("活动[{}]赠品未达门槛，当前金额={}，门槛={}",
                    rule.getActivityId(), orderContext.getTotalAmount(), giftRule.getMinOrderAmount());
            return noGift(rule);
        }

        // 将规则中的赠品配置转换为 CalcResult.GiftItem
        List<CalcResult.GiftItem> gifts = giftRule.getGifts().stream()
                .map(g -> CalcResult.GiftItem.builder()
                        .skuId(g.getGiftSkuId())
                        .skuName(g.getGiftSkuName())
                        .quantity(g.getGiftQuantity())
                        .marketPrice(g.getMarketPrice())
                        .build())
                .toList();

        String description = String.format("满%.0f元赠%s",
                giftRule.getMinOrderAmount(),
                gifts.stream().map(CalcResult.GiftItem::getSkuName)
                        .reduce((a, b) -> a + "、" + b).orElse("赠品"));

        log.debug("活动[{}]赠品命中，赠品数量={}，描述={}",
                rule.getActivityId(), gifts.size(), description);

        return CalcResult.builder()
                .activityId(rule.getActivityId())
                .promotionType(PromotionType.GIFT)
                .discountAmount(BigDecimal.ZERO)
                .description(description)
                .gifts(gifts)
                .build();
    }

    // ---------------------------------------------------------------
    // 私有方法
    // ---------------------------------------------------------------

    private GiftRule parseRule(ActivityRule rule) {
        try {
            return objectMapper.readValue(rule.getRuleJson(), GiftRule.class);
        } catch (Exception e) {
            throw BusinessException.ruleParseError(rule.getActivityId(), e);
        }
    }

    private CalcResult noGift(ActivityRule rule) {
        return CalcResult.builder()
                .activityId(rule.getActivityId())
                .promotionType(PromotionType.GIFT)
                .discountAmount(BigDecimal.ZERO)
                .description("未达赠品门槛")
                .build();
    }

    // ---------------------------------------------------------------
    // 规则 POJO
    // ---------------------------------------------------------------

    /**
     * 赠品规则，包含门槛金额及赠品列表。
     */
    @Data
    static class GiftRule {

        /** 触发赠品的最低订单金额，单位：元 */
        private BigDecimal minOrderAmount = BigDecimal.ZERO;

        /** 赠品配置列表，支持一次活动赠送多个 SKU */
        private List<GiftConfig> gifts = Collections.emptyList();
    }

    /**
     * 单个赠品配置项。
     */
    @Data
    static class GiftConfig {

        /** 赠品 SKU ID */
        private String giftSkuId;

        /** 赠品名称 */
        private String giftSkuName;

        /** 赠送数量，默认1 */
        private Integer giftQuantity = 1;

        /**
         * 赠品市场原价，仅用于前端展示（如"价值49.9元"），
         * 不参与实际金额计算，单位：元。
         */
        private BigDecimal marketPrice = BigDecimal.ZERO;
    }
}
