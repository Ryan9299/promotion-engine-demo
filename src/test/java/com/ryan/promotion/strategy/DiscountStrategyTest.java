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
 * DiscountStrategy 单元测试。
 * 覆盖：全单折扣、未达门槛、单品折扣、折扣率 1.0 边界。
 */
@DisplayName("DiscountStrategy 单元测试")
class DiscountStrategyTest {

    private DiscountStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new DiscountStrategy(new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // 正常用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("全单 8.8 折 → 优惠 = 300 × 0.12 = 36.00")
    void calculate_fullOrderDiscount_returnsCorrectAmount() {
        ActivityRule rule = rule(2001L,
                """
                {"discountRate":0.88,"minOrderAmount":0,"skuIds":[]}
                """);
        OrderContext ctx = orderWith(new BigDecimal("300.00"), List.of(
                item("SKU001", 1, "300.00")));

        CalcResult result = strategy.calculate(ctx, rule);

        assertThat(result.getDiscountAmount()).isEqualByComparingTo("36.00");
        assertThat(result.getPromotionType()).isEqualTo(PromotionType.DISCOUNT);
        assertThat(result.getDescription()).contains("全单");
    }

    @Test
    @DisplayName("单品折扣：仅命中 SKU001 → 优惠 = 200 × 0.12 = 24.00")
    void calculate_skuSpecificDiscount_onlyHitSkuDiscounted() {
        ActivityRule rule = rule(2002L,
                """
                {"discountRate":0.88,"minOrderAmount":0,"skuIds":["SKU001"]}
                """);
        // 订单含 SKU001(200) + SKU002(100)，只有 SKU001 参与折扣
        OrderContext ctx = orderWith(new BigDecimal("300.00"), List.of(
                item("SKU001", 2, "200.00"),
                item("SKU002", 1, "100.00")));

        CalcResult result = strategy.calculate(ctx, rule);

        // 200 × (1 - 0.88) = 24.00
        assertThat(result.getDiscountAmount()).isEqualByComparingTo("24.00");
        assertThat(result.getDescription()).contains("指定商品");
    }

    // ------------------------------------------------------------------
    // 边界用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("折扣率 = 1.0 → 优惠金额为 0")
    void calculate_discountRateOne_returnsZeroDiscount() {
        ActivityRule rule = rule(2003L,
                """
                {"discountRate":1.0,"minOrderAmount":0,"skuIds":[]}
                """);
        OrderContext ctx = orderWith(new BigDecimal("300.00"), List.of(
                item("SKU001", 1, "300.00")));

        CalcResult result = strategy.calculate(ctx, rule);

        assertThat(result.getDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------
    // 不满足条件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未达最低消费门槛 → 零优惠")
    void calculate_belowMinOrderAmount_returnsZeroDiscount() {
        ActivityRule rule = rule(2001L,
                """
                {"discountRate":0.88,"minOrderAmount":500,"skuIds":[]}
                """);
        OrderContext ctx = orderWith(new BigDecimal("300.00"), List.of(
                item("SKU001", 1, "300.00")));

        CalcResult result = strategy.calculate(ctx, rule);

        assertThat(result.getDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getDescription()).contains("不满足");
    }

    @Test
    @DisplayName("单品折扣但 SKU 不匹配 → 零优惠")
    void calculate_skuNotMatched_returnsZeroDiscount() {
        ActivityRule rule = rule(2002L,
                """
                {"discountRate":0.88,"minOrderAmount":0,"skuIds":["SKU_OTHER"]}
                """);
        OrderContext ctx = orderWith(new BigDecimal("300.00"), List.of(
                item("SKU001", 1, "300.00")));

        CalcResult result = strategy.calculate(ctx, rule);

        assertThat(result.getDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("rule_json 格式非法 → 抛出 BusinessException")
    void calculate_invalidRuleJson_throwsBusinessException() {
        ActivityRule rule = rule(2001L, "not-json");
        OrderContext ctx = orderWith(new BigDecimal("300.00"), List.of(
                item("SKU001", 1, "300.00")));

        assertThatThrownBy(() -> strategy.calculate(ctx, rule))
                .isInstanceOf(BusinessException.class);
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private ActivityRule rule(long activityId, String ruleJson) {
        return ActivityRule.builder().id(1L).activityId(activityId).ruleJson(ruleJson).build();
    }

    private OrderContext orderWith(BigDecimal totalAmount, List<OrderContext.OrderItem> items) {
        return OrderContext.builder()
                .orderId("TEST-002").storeId(101L).memberId(1000L)
                .memberLevel("GOLD").totalAmount(totalAmount)
                .items(items)
                .build();
    }

    private OrderContext.OrderItem item(String skuId, int qty, String subtotal) {
        BigDecimal sub = new BigDecimal(subtotal);
        return OrderContext.OrderItem.builder()
                .skuId(skuId).quantity(qty)
                .unitPrice(sub.divide(BigDecimal.valueOf(qty)))
                .subtotal(sub).build();
    }
}
