package com.ryan.promotion.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ryan.promotion.cache.PromotionCacheManager;
import com.ryan.promotion.gray.GrayRuleEvaluator;
import com.ryan.promotion.handler.*;
import com.ryan.promotion.model.dto.CalcResult;
import com.ryan.promotion.model.dto.OrderContext;
import com.ryan.promotion.model.entity.Activity;
import com.ryan.promotion.model.entity.ActivityConflict;
import com.ryan.promotion.model.entity.ActivityRule;
import com.ryan.promotion.model.enums.ActivityStatus;
import com.ryan.promotion.model.enums.ConflictRelation;
import com.ryan.promotion.model.enums.PromotionType;
import com.ryan.promotion.model.vo.PromotionResultVO;
import com.ryan.promotion.strategy.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 责任链端到端测试：手动组装 Handler 链 + Mock 缓存层，验证完整计算流程。
 *
 * <p>不启动 Spring 容器，仅测试 Filter → Conflict → Calc → Assemble 四个节点
 * 串联后的业务正确性。
 *
 * <h3>场景设计</h3>
 * <pre>
 * 订单：300元，GOLD 会员，storeId=101
 *
 * 活动（按优先级降序）：
 *   1001  FULL_REDUCTION  priority=30  满200减30
 *   1002  DISCOUNT        priority=20  全单8折
 *   1003  MEMBER_PRICE    priority=10  GOLD 9折
 *   1004  GIFT            priority=5   满150赠保温杯
 *
 * 冲突：1001(FULL_REDUCTION) EXCLUSIVE 1002(DISCOUNT)
 *
 * 冲突解决（贪心，priority降序）：
 *   1001 入选 → 1002 与 1001 互斥，丢弃 → 1003 入选 → 1004 入选
 *   resolvedActivities = [1001, 1003, 1004]
 *
 * 计算（滚动基准价）：
 *   MEMBER_PRICE(1003)  : 300 × (1-0.90) = 30.00 → currentAmount = 270
 *   FULL_REDUCTION(1001): 270 ≥ 200 → 减30       → currentAmount = 240
 *   GIFT(1004)          : 240 ≥ 150 → 赠保温杯（不减金额）
 *
 * 预期结果：
 *   totalDiscount = 60.00
 *   finalAmount   = 240.00
 *   gifts         = [保温杯]
 * </pre>
 */
@DisplayName("促销引擎责任链测试：会员价 + 满减 + 赠品（折扣被互斥剔除）")
@ExtendWith(MockitoExtension.class)
class PromotionChainTest {

    // ---------------------------------------------------------------
    // Mock 依赖
    // ---------------------------------------------------------------

    @Mock
    private PromotionCacheManager cacheManager;

    // ---------------------------------------------------------------
    // 责任链（真实实现）
    // ---------------------------------------------------------------

    private ActivityFilterHandler filterHandler;
    private ConflictResolveHandler conflictHandler;
    private CalcHandler calcHandler;
    private ResultAssembleHandler assembleHandler;

    // ---------------------------------------------------------------
    // 测试数据
    // ---------------------------------------------------------------

    private static final long ID_FULL_REDUCTION = 1001L;
    private static final long ID_DISCOUNT        = 1002L;
    private static final long ID_MEMBER_PRICE    = 1003L;
    private static final long ID_GIFT            = 1004L;

    private Activity fullReductionActivity;
    private Activity discountActivity;
    private Activity memberPriceActivity;
    private Activity giftActivity;

    private ActivityRule fullReductionRule;
    private ActivityRule discountRule;
    private ActivityRule memberPriceRule;
    private ActivityRule giftRule;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();

        // 构造 Handler 实例
        filterHandler   = new ActivityFilterHandler(cacheManager, new GrayRuleEvaluator(objectMapper));
        conflictHandler = new ConflictResolveHandler(cacheManager);
        calcHandler     = new CalcHandler(List.of(
                new MemberPriceStrategy(objectMapper),
                new DiscountStrategy(objectMapper),
                new FullReductionStrategy(objectMapper),
                new GiftStrategy(objectMapper)
        ));
        assembleHandler = new ResultAssembleHandler();

        // 调用 CalcHandler 的 @PostConstruct（构建 strategyMap）
        calcHandler.init();

        // 组装责任链
        filterHandler.setNext(conflictHandler);
        conflictHandler.setNext(calcHandler);
        calcHandler.setNext(assembleHandler);

        // 构造活动实体（按 priority 降序）
        fullReductionActivity = activity(ID_FULL_REDUCTION, PromotionType.FULL_REDUCTION, 30);
        discountActivity      = activity(ID_DISCOUNT,       PromotionType.DISCOUNT,        20);
        memberPriceActivity   = activity(ID_MEMBER_PRICE,   PromotionType.MEMBER_PRICE,    10);
        giftActivity          = activity(ID_GIFT,           PromotionType.GIFT,             5);

        // 构造规则
        fullReductionRule = rule(ID_FULL_REDUCTION,
                """
                {"tiers":[{"threshold":200,"reduction":30}]}
                """);
        discountRule = rule(ID_DISCOUNT,
                """
                {"discountRate":0.80,"minOrderAmount":0,"skuIds":[]}
                """);
        memberPriceRule = rule(ID_MEMBER_PRICE,
                """
                {"memberLevels":["GOLD","PLATINUM"],"discountRate":0.90}
                """);
        giftRule = rule(ID_GIFT,
                """
                {"minOrderAmount":150,"gifts":[
                  {"giftSkuId":"SKU_CUP_001","giftSkuName":"定制保温杯","giftQuantity":1,"marketPrice":49.90}
                ]}
                """);
    }

    // ---------------------------------------------------------------
    // 主场景测试
    // ---------------------------------------------------------------

    @Test
    @DisplayName("完整计算：totalDiscount=60，finalAmount=240，赠品=[保温杯]，折扣活动被互斥剔除")
    void calculate_memberPriceAndFullReductionAndGift_discountExcluded() {
        // 缓存返回全部四个活动（按 priority 降序）
        when(cacheManager.getActivities(101L)).thenReturn(
                List.of(fullReductionActivity, discountActivity, memberPriceActivity, giftActivity));
        when(cacheManager.getRules(101L)).thenReturn(Map.of(
                ID_FULL_REDUCTION, fullReductionRule,
                ID_DISCOUNT,       discountRule,
                ID_MEMBER_PRICE,   memberPriceRule,
                ID_GIFT,           giftRule));

        // FULL_REDUCTION EXCLUSIVE DISCOUNT
        when(cacheManager.getConflicts(101L))
                .thenReturn(List.of(
                        conflict(ID_FULL_REDUCTION, ID_DISCOUNT, ConflictRelation.EXCLUSIVE)));

        // 执行
        OrderContext orderCtx = order(new BigDecimal("300.00"), "GOLD");
        PromotionContext ctx = PromotionContext.builder().orderContext(orderCtx).build();
        filterHandler.handle(ctx);

        PromotionResultVO result = ctx.getFinalResult();

        // 基础断言
        assertThat(result).isNotNull();
        assertThat(result.getOriginalAmount()).isEqualByComparingTo("300.00");
        assertThat(result.getTotalDiscount()).isEqualByComparingTo("60.00");
        assertThat(result.getFinalAmount()).isEqualByComparingTo("240.00");

        // 赠品断言
        assertThat(result.getGifts()).hasSize(1);
        assertThat(result.getGifts().get(0).getSkuId()).isEqualTo("SKU_CUP_001");
        assertThat(result.getGifts().get(0).getSkuName()).isEqualTo("定制保温杯");

        // 折扣活动被剔除，不应出现在明细中
        List<Long> detailIds = result.getDiscountDetails().stream()
                .map(PromotionResultVO.DiscountDetail::getActivityId)
                .toList();
        assertThat(detailIds).doesNotContain(ID_DISCOUNT);
        assertThat(detailIds).contains(ID_FULL_REDUCTION, ID_MEMBER_PRICE, ID_GIFT);
    }

    @Test
    @DisplayName("冲突解决后 resolvedActivities 不含折扣活动")
    void conflictResolution_discountActivityExcluded() {
        when(cacheManager.getActivities(101L)).thenReturn(
                List.of(fullReductionActivity, discountActivity, memberPriceActivity, giftActivity));
        when(cacheManager.getRules(101L)).thenReturn(Map.of(
                ID_FULL_REDUCTION, fullReductionRule,
                ID_DISCOUNT,       discountRule,
                ID_MEMBER_PRICE,   memberPriceRule,
                ID_GIFT,           giftRule));
        when(cacheManager.getConflicts(101L))
                .thenReturn(List.of(
                        conflict(ID_FULL_REDUCTION, ID_DISCOUNT, ConflictRelation.EXCLUSIVE)));

        PromotionContext ctx = PromotionContext.builder()
                .orderContext(order(new BigDecimal("300.00"), "GOLD")).build();
        filterHandler.handle(ctx);

        assertThat(ctx.getResolvedActivities())
                .extracting(Activity::getId)
                .containsExactlyInAnyOrder(ID_FULL_REDUCTION, ID_MEMBER_PRICE, ID_GIFT)
                .doesNotContain(ID_DISCOUNT);
    }

    @Test
    @DisplayName("无活动命中时：finalAmount = originalAmount，无优惠，无赠品")
    void calculate_noActivities_returnsOriginalAmount() {
        when(cacheManager.getActivities(101L)).thenReturn(List.of());

        PromotionContext ctx = PromotionContext.builder()
                .orderContext(order(new BigDecimal("300.00"), "GOLD")).build();
        filterHandler.handle(ctx);

        PromotionResultVO result = ctx.getFinalResult();
        assertThat(result.getTotalDiscount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getFinalAmount()).isEqualByComparingTo("300.00");
        assertThat(result.getGifts()).isEmpty();
    }

    // ---------------------------------------------------------------
    // 辅助方法
    // ---------------------------------------------------------------

    private Activity activity(long id, PromotionType type, int priority) {
        return Activity.builder()
                .id(id).storeId(101L).name(type.getDesc() + "活动").type(type)
                .status(ActivityStatus.ACTIVE).priority(priority)
                .build();
    }

    private ActivityRule rule(long activityId, String ruleJson) {
        return ActivityRule.builder().id(activityId * 10).activityId(activityId).ruleJson(ruleJson).build();
    }

    private ActivityConflict conflict(long idA, long idB, ConflictRelation relation) {
        return ActivityConflict.builder().id(9001L)
                .activityIdA(idA).activityIdB(idB).relation(relation).build();
    }

    private OrderContext order(BigDecimal amount, String memberLevel) {
        return OrderContext.builder()
                .orderId("ORD-CHAIN-001").storeId(101L).memberId(888L)
                .memberLevel(memberLevel).totalAmount(amount)
                .items(List.of(
                        OrderContext.OrderItem.builder()
                                .skuId("SKU001").skuName("商品A").quantity(2)
                                .unitPrice(new BigDecimal("100.00"))
                                .subtotal(new BigDecimal("200.00")).build(),
                        OrderContext.OrderItem.builder()
                                .skuId("SKU002").skuName("商品B").quantity(1)
                                .unitPrice(new BigDecimal("100.00"))
                                .subtotal(new BigDecimal("100.00")).build()
                ))
                .build();
    }
}
