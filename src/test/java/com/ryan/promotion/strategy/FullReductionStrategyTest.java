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
 * FullReductionStrategy 单元测试。
 * 覆盖：最高档命中、未达门槛、精确边界、多档选最高。
 */
@DisplayName("FullReductionStrategy 单元测试")
class FullReductionStrategyTest {

    private FullReductionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new FullReductionStrategy(new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // 正常用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("满200减30（单档命中）→ 优惠 30")
    void calculate_singleTierHit_returnsReduction() {
        ActivityRule rule = rule(3001L,
                """
                {"tiers":[{"threshold":200,"reduction":30}]}
                """);
        OrderContext ctx = orderWith(new BigDecimal("270.00"));

        CalcResult result = strategy.calculate(ctx, rule);

        assertThat(result.getDiscountAmount()).isEqualByComparingTo("30.00");
        assertThat(result.getPromotionType()).isEqualTo(PromotionType.FULL_REDUCTION);
        assertThat(result.getDescription()).contains("满200").contains("减30");
    }

    @Test
    @DisplayName("多档满减：满500减100 > 满200减30，520元命中最高档 → 优惠 100")
    void calculate_multiTierSelectsHighest_returnsMaxReduction() {
        ActivityRule rule = rule(3001L,
                """
                {"tiers":[
                  {"threshold":200,"reduction":30},
                  {"threshold":500,"reduction":100}
                ]}
                """);
        OrderContext ctx = orderWith(new BigDecimal("520.00"));

        CalcResult result = strategy.calculate(ctx, rule);

        assertThat(result.getDiscountAmount()).isEqualByComparingTo("100.00");
        assertThat(result.getDescription()).contains("满500").contains("减100");
    }

    // ------------------------------------------------------------------
    // 边界用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("金额恰好等于门槛（= 200）→ 命中，优惠 30")
    void calculate_exactThreshold_hitsAndReturnsReduction() {
        ActivityRule rule = rule(3001L,
                """
                {"tiers":[{"threshold":200,"reduction":30}]}
                """);
        OrderContext ctx = orderWith(new BigDecimal("200.00"));

        CalcResult result = strategy.calculate(ctx, rule);

        assertThat(result.getDiscountAmount()).isEqualByComparingTo("30.00");
    }

    // ------------------------------------------------------------------
    // 不满足条件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("金额低于最低档门槛 → 零优惠")
    void calculate_belowAllThresholds_returnsZeroDiscount() {
        ActivityRule rule = rule(3001L,
                """
                {"tiers":[{"threshold":200,"reduction":30}]}
                """);
        OrderContext ctx = orderWith(new BigDecimal("150.00"));

        CalcResult result = strategy.calculate(ctx, rule);

        assertThat(result.getDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getDescription()).contains("未达");
    }

    @Test
    @DisplayName("rule_json 格式非法 → 抛出 BusinessException")
    void calculate_invalidRuleJson_throwsBusinessException() {
        ActivityRule rule = rule(3001L, "{invalid}");
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
                .orderId("TEST-003").storeId(101L).memberId(1000L)
                .memberLevel("GOLD").totalAmount(amount)
                .items(List.of(OrderContext.OrderItem.builder()
                        .skuId("SKU001").quantity(1).unitPrice(amount).subtotal(amount).build()))
                .build();
    }
}
