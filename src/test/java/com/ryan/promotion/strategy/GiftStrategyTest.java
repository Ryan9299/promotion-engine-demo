package com.ryan.promotion.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ryan.promotion.common.exception.BusinessException;
import com.ryan.promotion.model.dto.CalcResult;
import com.ryan.promotion.model.dto.OrderContext;
import com.ryan.promotion.model.entity.ActivityRule;
import com.ryan.promotion.model.enums.PromotionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * GiftStrategy 单元测试。
 * 覆盖：门槛命中赠品、未达门槛、精确边界、多赠品配置。
 */
@DisplayName("GiftStrategy 单元测试")
class GiftStrategyTest {

    private GiftStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new GiftStrategy(new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // 正常用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("达到门槛 → 赠品列表非空，discountAmount = 0")
    void calculate_thresholdMet_returnsGifts() {
        ActivityRule rule = rule(4001L,
                """
                {"minOrderAmount":150,"gifts":[
                  {"giftSkuId":"SKU_CUP_001","giftSkuName":"保温杯","giftQuantity":1,"marketPrice":49.90}
                ]}
                """);
        OrderContext ctx = orderWith(new BigDecimal("200.00"));

        CalcResult result = strategy.calculate(ctx, rule);

        assertThat(result.getDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getPromotionType()).isEqualTo(PromotionType.GIFT);
        assertThat(result.getGifts()).hasSize(1);
        assertThat(result.getGifts().get(0).getSkuId()).isEqualTo("SKU_CUP_001");
        assertThat(result.getGifts().get(0).getSkuName()).isEqualTo("保温杯");
        assertThat(result.getDescription()).contains("保温杯");
    }

    @Test
    @DisplayName("多赠品配置 → 所有赠品均返回")
    void calculate_multipleGifts_allReturned() {
        ActivityRule rule = rule(4002L,
                """
                {"minOrderAmount":100,"gifts":[
                  {"giftSkuId":"SKU_G1","giftSkuName":"赠品A","giftQuantity":1,"marketPrice":10.00},
                  {"giftSkuId":"SKU_G2","giftSkuName":"赠品B","giftQuantity":2,"marketPrice":5.00}
                ]}
                """);
        OrderContext ctx = orderWith(new BigDecimal("150.00"));

        CalcResult result = strategy.calculate(ctx, rule);

        assertThat(result.getGifts()).hasSize(2);
        assertThat(result.getGifts()).extracting(CalcResult.GiftItem::getSkuId)
                .containsExactlyInAnyOrder("SKU_G1", "SKU_G2");
    }

    // ------------------------------------------------------------------
    // 边界用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("金额恰好等于门槛 → 命中，赠品返回")
    void calculate_exactThreshold_returnsGifts() {
        ActivityRule rule = rule(4001L,
                """
                {"minOrderAmount":150,"gifts":[
                  {"giftSkuId":"SKU_CUP","giftSkuName":"保温杯","giftQuantity":1,"marketPrice":49.90}
                ]}
                """);
        OrderContext ctx = orderWith(new BigDecimal("150.00"));

        CalcResult result = strategy.calculate(ctx, rule);

        assertThat(result.getGifts()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 不满足条件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未达门槛 → 赠品为空，discountAmount = 0")
    void calculate_belowThreshold_returnsNoGifts() {
        ActivityRule rule = rule(4001L,
                """
                {"minOrderAmount":150,"gifts":[
                  {"giftSkuId":"SKU_CUP","giftSkuName":"保温杯","giftQuantity":1,"marketPrice":49.90}
                ]}
                """);
        OrderContext ctx = orderWith(new BigDecimal("100.00"));

        CalcResult result = strategy.calculate(ctx, rule);

        assertThat(result.getDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getGifts()).isEmpty();
        assertThat(result.getDescription()).contains("未达");
    }

    @Test
    @DisplayName("rule_json 格式非法 → 抛出 BusinessException")
    void calculate_invalidRuleJson_throwsBusinessException() {
        ActivityRule rule = rule(4001L, "not-json");
        OrderContext ctx = orderWith(new BigDecimal("300.00"));

        assertThatThrownBy(() -> strategy.calculate(ctx, rule))
                .isInstanceOf(BusinessException.class);
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private ActivityRule rule(long activityId, String ruleJson) {
        return ActivityRule.builder().id(1L).activityId(activityId).ruleJson(ruleJson).build();
    }

    private OrderContext orderWith(BigDecimal amount) {
        return OrderContext.builder()
                .orderId("TEST-004").storeId(101L).memberId(1000L)
                .memberLevel("GOLD").totalAmount(amount)
                .items(List.of(OrderContext.OrderItem.builder()
                        .skuId("SKU001").quantity(1).unitPrice(amount).subtotal(amount).build()))
                .build();
    }
}
