package com.ryan.promotion.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 活动冲突关系枚举。
 * EXCLUSIVE=互斥（两活动不可同时叠加），COMPATIBLE=兼容（可叠加）。
 */
@Getter
@RequiredArgsConstructor
public enum ConflictRelation {

    EXCLUSIVE("EXCLUSIVE", "互斥"),
    COMPATIBLE("COMPATIBLE", "兼容");

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;
}
