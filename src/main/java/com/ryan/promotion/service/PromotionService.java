package com.ryan.promotion.service;

import com.ryan.promotion.chain.PromotionChainBuilder;
import com.ryan.promotion.handler.PromotionContext;
import com.ryan.promotion.model.dto.OrderContext;
import com.ryan.promotion.model.vo.PromotionResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 促销计算服务，责任链模式的对外入口。
 *
 * <p>调用方只需注入此服务并调用 {@link #calculate(OrderContext)}，
 * 无需了解责任链内部的 Handler 实现细节。
 *
 * <p>耗时日志记录整条链（筛活动→冲突解决→计算→组装）的端到端执行时间。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionService {

    private final PromotionChainBuilder chainBuilder;

    /**
     * 执行一次完整的促销计算。
     *
     * <p>内部流程：
     * <ol>
     *   <li>构建本次请求的 {@link PromotionContext}（含原始 OrderContext）。</li>
     *   <li>驱动责任链：ActivityFilter → ConflictResolve → Calc → ResultAssemble。</li>
     *   <li>从 context 中取出 finalResult 返回。</li>
     * </ol>
     *
     * @param orderContext 订单上下文（含商品明细、会员信息、门店 ID）
     * @return 促销计算结果（优惠明细、最终金额、赠品列表）
     * @throws com.ryan.promotion.common.exception.BusinessException 规则解析失败等业务异常
     */
    public PromotionResultVO calculate(OrderContext orderContext) {
        long start = System.currentTimeMillis();
        log.info("[促销计算] 开始 | orderId={} storeId={} memberId={} amount={}",
                orderContext.getOrderId(),
                orderContext.getStoreId(),
                orderContext.getMemberId(),
                orderContext.getTotalAmount());
        try {
            PromotionContext context = PromotionContext.builder()
                    .orderContext(orderContext)
                    .build();

            chainBuilder.getHead().handle(context);

            PromotionResultVO result = context.getFinalResult();
            log.info("[促销计算] 完成 | orderId={} 原价={} 优惠={} 应付={} 耗时={}ms",
                    orderContext.getOrderId(),
                    result.getOriginalAmount(),
                    result.getTotalDiscount(),
                    result.getFinalAmount(),
                    System.currentTimeMillis() - start);
            return result;

        } catch (Exception e) {
            log.error("[促销计算] 异常 | orderId={} 耗时={}ms",
                    orderContext.getOrderId(), System.currentTimeMillis() - start, e);
            throw e;
        }
    }
}
