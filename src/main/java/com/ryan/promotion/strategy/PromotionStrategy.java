package com.ryan.promotion.strategy;

import com.ryan.promotion.model.dto.CalcResult;
import com.ryan.promotion.model.dto.OrderContext;
import com.ryan.promotion.model.entity.ActivityRule;
import com.ryan.promotion.model.enums.PromotionType;

/**
 * 促销策略接口，策略模式核心契约。
 *
 * <p>每个实现类对应一种活动类型，通过 {@code @Component} 注入 Spring 容器。
 * {@link com.ryan.promotion.service.PromotionService} 启动时收集所有实现，
 * 按 {@link #getType()} 建立 {@code Map<PromotionType, PromotionStrategy>} 索引。
 *
 * <p>计算顺序由 {@code CalcHandler} 保证：
 * MEMBER_PRICE → DISCOUNT → FULL_REDUCTION → GIFT。
 * 各策略基于调用方传入的 {@code orderContext.totalAmount} 计算，
 * {@code CalcHandler} 在每步后更新 totalAmount 供下一策略使用。
 */
public interface PromotionStrategy {

    /**
     * 返回本策略处理的活动类型。
     *
     * @return 活动类型枚举值
     */
    PromotionType getType();

    /**
     * 执行促销计算，返回本活动产生的优惠结果。
     *
     * <p>入参 {@code orderContext.totalAmount} 已由 {@code CalcHandler}
     * 调整为扣除前序活动优惠后的当前金额，策略直接基于此值计算。
     *
     * @param orderContext 订单上下文（含商品明细、会员信息、门店ID及当前基准金额）
     * @param rule         本活动对应的规则记录，{@code rule.ruleJson} 为策略参数
     * @return 本活动的计算结果，若条件不满足则 discountAmount=0、gifts 为空列表
     */
    CalcResult calculate(OrderContext orderContext, ActivityRule rule);
}
