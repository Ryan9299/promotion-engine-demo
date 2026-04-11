package com.ryan.promotion.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 营销活动类型枚举。
 * FULL_REDUCTION=满减，DISCOUNT=折扣，GIFT=赠品，MEMBER_PRICE=会员等级价。
 * 计算顺序：MEMBER_PRICE → DISCOUNT → FULL_REDUCTION → GIFT。
 */
@Getter
@RequiredArgsConstructor
public enum PromotionType {

    MEMBER_PRICE("MEMBER_PRICE", "会员等级价"),
    DISCOUNT("DISCOUNT", "折扣"),
    FULL_REDUCTION("FULL_REDUCTION", "满减"),
    GIFT("GIFT", "赠品");

    /** 存入数据库的值 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 展示名称 */
    private final String desc;

    /**
     * 根据 code 查找枚举，找不到返回 null。
     */
    public static PromotionType of(String code) {
        for (PromotionType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        return null;
    }
}
