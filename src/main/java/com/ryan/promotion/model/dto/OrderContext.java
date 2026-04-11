package com.ryan.promotion.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 促销计算请求上下文，封装一次结算所需的全部输入信息。
 * 由 PromotionController 接收后传入责任链处理。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderContext {

    /** 订单号，用于幂等校验 */
    @NotNull(message = "订单号不能为空")
    private String orderId;

    /** 门店 ID，用于活动范围过滤和灰度路由 */
    @NotNull(message = "门店ID不能为空")
    private Long storeId;

    /** 会员 ID，未登录时为 null */
    private Long memberId;

    /**
     * 会员等级，如 SILVER / GOLD / PLATINUM / DIAMOND。
     * 非会员时为 null，影响 MEMBER_PRICE 类型活动的命中。
     */
    private String memberLevel;

    /** 订单商品明细，至少包含一项 */
    @NotEmpty(message = "商品列表不能为空")
    @Valid
    private List<OrderItem> items;

    /**
     * 订单原始总金额（各商品 unitPrice × quantity 之和），单位：元。
     * 由调用方传入，引擎内部不再重新计算，以保证精度一致性。
     */
    @NotNull(message = "订单金额不能为空")
    @Positive(message = "订单金额必须大于0")
    private BigDecimal totalAmount;

    // ---------------------------------------------------------------
    // 内部类：订单商品行
    // ---------------------------------------------------------------

    /**
     * 订单中的单个商品行信息。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItem {

        /** 商品 SKU ID */
        @NotNull(message = "skuId不能为空")
        private String skuId;

        /** 商品名称，用于展示 */
        private String skuName;

        /** 商品分类，部分活动按品类限制范围 */
        private String category;

        /** 购买数量 */
        @NotNull(message = "商品数量不能为空")
        @Positive(message = "商品数量必须大于0")
        private Integer quantity;

        /**
         * 商品单价，单位：元，禁止使用 double/float。
         */
        @NotNull(message = "商品单价不能为空")
        @Positive(message = "商品单价必须大于0")
        private BigDecimal unitPrice;

        /**
         * 该行小计（unitPrice × quantity），单位：元。
         * 由调用方计算传入，保证精度。
         */
        @NotNull(message = "商品小计不能为空")
        private BigDecimal subtotal;
    }
}
