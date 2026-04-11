package com.ryan.promotion.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 营销活动状态枚举。
 * DRAFT=草稿（仅可编辑），GRAY=灰度（按灰度规则投放），
 * ACTIVE=全量上线，EXPIRED=已过期/下线。
 */
@Getter
@RequiredArgsConstructor
public enum ActivityStatus {

    DRAFT("DRAFT", "草稿"),
    GRAY("GRAY", "灰度"),
    ACTIVE("ACTIVE", "全量上线"),
    EXPIRED("EXPIRED", "已过期");

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;
}
