package com.ryan.promotion.model.dto;

import com.ryan.promotion.model.enums.PromotionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个活动的计算结果，由各 PromotionStrategy 实现返回。
 * CalcHandler 汇总多个 CalcResult 后交由 ResultAssembleHandler 组装最终输出。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalcResult {

    /** 命中的活动 ID */
    private Long activityId;

    /** 活动名称，用于展示 */
    private String activityName;

    /** 活动类型 */
    private PromotionType promotionType;

    /**
     * 本活动产生的优惠金额（正数），单位：元。
     * 赠品类活动优惠金额为 0，赠品信息存于 gifts 列表。
     */
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    /** 优惠描述，用于向用户展示，如"满200减30"、"8.8折优惠" */
    private String description;

    /**
     * 本活动赠送的赠品列表，非赠品类型活动为空列表。
     */
    @Builder.Default
    private List<GiftItem> gifts = new ArrayList<>();

    // ---------------------------------------------------------------
    // 内部类：赠品信息
    // ---------------------------------------------------------------

    /**
     * 赠品信息，由 GiftStrategy 填充。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GiftItem {

        /** 赠品 SKU ID */
        private String skuId;

        /** 赠品名称 */
        private String skuName;

        /** 赠送数量 */
        private Integer quantity;

        /** 赠品原价（市场价），仅展示用，单位：元 */
        private BigDecimal marketPrice;
    }
}
