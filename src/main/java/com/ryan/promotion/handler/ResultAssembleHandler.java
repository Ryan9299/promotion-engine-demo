package com.ryan.promotion.handler;

import com.ryan.promotion.model.dto.CalcResult;
import com.ryan.promotion.model.vo.PromotionResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 责任链第4步（链尾）：结果组装处理器。
 *
 * <p>汇总所有 {@link CalcResult}，组装最终的 {@link PromotionResultVO}：
 * <ul>
 *   <li>将各活动的 discountAmount 求和，得到 totalDiscount。</li>
 *   <li>finalAmount = max(0, originalAmount - totalDiscount)，防止负数。</li>
 *   <li>将所有赠品列表合并到统一的 gifts 列表。</li>
 *   <li>每个有优惠的活动转为一条 DiscountDetail（discountAmount=0 的同样保留，供前端展示）。</li>
 * </ul>
 *
 * <p>本节点为链尾，执行完毕后不再调用 {@code passToNext}。
 */
@Slf4j
@Component
public class ResultAssembleHandler extends PromotionHandler {

    /**
     * 组装最终优惠结果，写入 {@code context.finalResult}。
     */
    @Override
    public void handle(PromotionContext context) {
        BigDecimal originalAmount = context.getOrderContext().getTotalAmount();
        List<CalcResult> calcResults = context.getCalcResults();

        // 汇总优惠金额
        BigDecimal totalDiscount = calcResults.stream()
                .map(CalcResult::getDiscountAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 最终应付金额，最低为0
        BigDecimal finalAmount = originalAmount.subtract(totalDiscount).max(BigDecimal.ZERO);

        // 构建优惠明细列表（全部活动均输出，前端可按 discountAmount>0 过滤展示）
        List<PromotionResultVO.DiscountDetail> details = calcResults.stream()
                .map(r -> PromotionResultVO.DiscountDetail.builder()
                        .activityId(r.getActivityId())
                        .activityName(r.getActivityName())
                        .promotionType(r.getPromotionType())
                        .discountAmount(r.getDiscountAmount())
                        .description(r.getDescription())
                        .build())
                .collect(Collectors.toList());

        // 汇总所有赠品
        List<CalcResult.GiftItem> gifts = calcResults.stream()
                .flatMap(r -> r.getGifts().stream())
                .collect(Collectors.toList());

        PromotionResultVO result = PromotionResultVO.builder()
                .originalAmount(originalAmount)
                .finalAmount(finalAmount)
                .totalDiscount(totalDiscount)
                .discountDetails(details)
                .gifts(gifts)
                .build();

        context.setFinalResult(result);

        log.debug("结果组装完成，原价={}，优惠={}，应付={}，赠品数量={}",
                originalAmount, totalDiscount, finalAmount, gifts.size());

        // 链尾，不再 passToNext
    }
}
