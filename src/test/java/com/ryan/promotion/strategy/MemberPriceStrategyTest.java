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
 * MemberPriceStrategy 单元测试。
 * 覆盖：等级命中、等级不命中、null 等级、折扣率 1.0 边界。
 */
@DisplayName("MemberPriceStrategy 单元测试")
class MemberPriceStrategyTest {

    private MemberPriceStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new MemberPriceStrategy(new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // 正常用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GOLD 等级命中 → 优惠 = 总额 × (1 - 折扣率)")
    void calculate_goldMemberHits_returnsCorrectDiscount() {
        // 规则：GOLD/PLATINUM/DIAMOND 享 9 折
        ActivityRule rule = rule(1001L,
                """
                {"memberLevels":["GOLD","PLATINUM","DIAMOND"],"discountRate":0.90}
                """);
        // 订单：金额 300 元，GOLD 会员
        OrderContext ctx = orderWith(new BigDecimal("300.00"), "GOLD");

        CalcResult result = strategy.calculate(ctx, rule);

        // 预期优惠 = 300 * (1 - 0.90) = 30.00
        assertThat(result.getDiscountAmount()).isEqualByComparingTo("30.00");
        assertThat(result.getPromotionType()).isEqualTo(PromotionType.MEMBER_PRICE);
        assertThat(result.getActivityId()).isEqualTo(1001L);
        assertThat(result.getDescription()).contains("9折").contains("GOLD");
    }

    @Test
    @DisplayName("PLATINUM 等级命中 → 与 GOLD 享相同折扣率")
    void calculate_platinumMemberHits_returnsCorrectDiscount() {
        ActivityRule rule = rule(1001L,
                """
                {"memberLevels":["GOLD","PLATINUM"],"discountRate":0.85}
                """);
        OrderContext ctx = orderWith(new BigDecimal("200.00"), "PLATINUM");

        CalcResult result = strategy.calculate(ctx, rule);

        // 200 * 0.15 = 30.00
        assertThat(result.getDiscountAmount()).isEqualByComparingTo("30.00");
    }

    // ------------------------------------------------------------------
    // 边界用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("折扣率 = 1.0（不打折）→ 优惠金额为 0")
    void calculate_discountRateOne_returnsZeroDiscount() {
        ActivityRule rule = rule(1002L,
                """
                {"memberLevels":["GOLD"],"discountRate":1.0}
                """);
        OrderContext ctx = orderWith(new BigDecimal("500.00"), "GOLD");

        CalcResult result = strategy.calculate(ctx, rule);

        assertThat(result.getDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------
    // 不满足条件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("会员等级不在白名单 → 零优惠")
    void calculate_memberLevelNotInList_returnsZeroDiscount() {
        ActivityRule rule = rule(1001L,
                """
                {"memberLevels":["GOLD","PLATINUM"],"discountRate":0.90}
                """);
        OrderContext ctx = orderWith(new BigDecimal("300.00"), "SILVER"); // SILVER 不在列表

        CalcResult result = strategy.calculate(ctx, rule);

        assertThat(result.getDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getDescription()).contains("不满足");
    }

    @Test
    @DisplayName("会员等级为 null（未登录）→ 零优惠")
    void calculate_nullMemberLevel_returnsZeroDiscount() {
        ActivityRule rule = rule(1001L,
                """
                {"memberLevels":["GOLD"],"discountRate":0.90}
                """);
        OrderContext ctx = orderWith(new BigDecimal("300.00"), null);

        CalcResult result = strategy.calculate(ctx, rule);

        assertThat(result.getDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("rule_json 格式非法 → 抛出 BusinessException")
    void calculate_invalidRuleJson_throwsBusinessException() {
        ActivityRule rule = rule(1001L, "not-json");
        OrderContext ctx = orderWith(new BigDecimal("300.00"), "GOLD");

        assertThatThrownBy(() -> strategy.calculate(ctx, rule))
                .isInstanceOf(BusinessException.class);
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private ActivityRule rule(long activityId, String ruleJson) {
        return ActivityRule.builder().id(1L).activityId(activityId).ruleJson(ruleJson).build();
    }

    private OrderContext orderWith(BigDecimal amount, String memberLevel) {
        return OrderContext.builder()
                .orderId("TEST-001").storeId(101L).memberId(1000L)
                .memberLevel(memberLevel).totalAmount(amount)
                .items(List.of(OrderContext.OrderItem.builder()
                        .skuId("SKU001").quantity(1).unitPrice(amount).subtotal(amount).build()))
                .build();
    }
}
