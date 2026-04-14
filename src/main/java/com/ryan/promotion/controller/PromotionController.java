package com.ryan.promotion.controller;

import com.ryan.promotion.common.Result;
import com.ryan.promotion.model.dto.OrderContext;
import com.ryan.promotion.model.vo.PromotionResultVO;
import com.ryan.promotion.service.PromotionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 促销计算接口，对外暴露结算优惠计算能力。
 *
 * <p>接口：{@code POST /api/v1/promotion/calculate}
 * <p>入参：{@link OrderContext}（订单信息、商品明细、会员信息、门店 ID）
 * <p>出参：{@link PromotionResultVO}（最终金额、优惠明细、赠品列表）
 */
@Tag(name = "促销计算", description = "结算时调用，计算订单可享优惠")
@RestController
@RequestMapping("/api/v1/promotion")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionService promotionService;

    /**
     * 执行促销优惠计算。
     *
     * <p>请求示例：
     * <pre>{@code
     * POST /api/v1/promotion/calculate
     * {
     *   "orderId": "ORD-20260411-001",
     *   "storeId": 101,
     *   "memberId": 888,
     *   "memberLevel": "GOLD",
     *   "totalAmount": 280.00,
     *   "items": [
     *     {"skuId": "SKU001", "skuName": "商品A", "quantity": 2,
     *      "unitPrice": 100.00, "subtotal": 200.00},
     *     {"skuId": "SKU002", "skuName": "商品B", "quantity": 1,
     *      "unitPrice": 80.00,  "subtotal": 80.00}
     *   ]
     * }
     * }</pre>
     *
     * @param orderContext 订单上下文（@Valid 触发 JSR-303 校验）
     * @return 包含优惠明细和最终金额的计算结果
     */
    @Operation(summary = "促销优惠计算", description = "传入订单上下文，返回优惠明细和最终金额")
    @PostMapping("/calculate")
    public Result<PromotionResultVO> calculate(@Valid @RequestBody OrderContext orderContext) {
        return Result.ok(promotionService.calculate(orderContext));
    }
}
