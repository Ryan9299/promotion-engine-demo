package com.ryan.promotion.model.vo;

import com.ryan.promotion.model.dto.CalcResult;
import com.ryan.promotion.model.enums.PromotionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 促销计算结果视图对象，作为 POST /api/v1/promotion/calculate 接口的响应体。
 * 包含原价、最终应付金额、优惠明细列表及赠品列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionResultVO {

    /** 订单原始总金额（促销前），单位：元 */
    private BigDecimal originalAmount;

    /** 最终应付金额，单位：元（originalAmount - totalDiscount，最低为0） */
    private BigDecimal finalAmount;

    /** 本次合计优惠金额，单位：元 */
    @Builder.Default
    private BigDecimal totalDiscount = BigDecimal.ZERO;

    /**
     * 优惠明细列表，每个命中活动对应一条明细。
     * 前端可据此展示"已享X项优惠"。
     */
    @Builder.Default
    private List<DiscountDetail> discountDetails = new ArrayList<>();

    /**
     * 赠品列表，汇总所有 GIFT 类型活动的赠品。
     */
    @Builder.Default
    private List<CalcResult.GiftItem> gifts = new ArrayList<>();

    // ---------------------------------------------------------------
    // 内部类：优惠明细
    // ---------------------------------------------------------------

    /**
     * 单条优惠明细，对应一个命中活动的优惠信息。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiscountDetail {

        /** 活动 ID */
        private Long activityId;

        /** 活动名称 */
        private String activityName;

        /** 活动类型 */
        private PromotionType promotionType;

        /** 本活动优惠金额，单位：元 */
        private BigDecimal discountAmount;

        /** 优惠描述文案，如"满200减30"、"会员9折" */
        private String description;
    }
}
