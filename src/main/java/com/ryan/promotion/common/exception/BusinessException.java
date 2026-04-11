package com.ryan.promotion.common.exception;

import lombok.Getter;

/**
 * 业务异常，统一封装 code + message，由各层抛出后由全局异常处理器捕获并响应。
 * 禁止直接使用 RuntimeException 抛出业务错误。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    // ---------------------------------------------------------------
    // 常用业务错误码工厂方法
    // ---------------------------------------------------------------

    /** 活动规则 JSON 解析失败 */
    public static BusinessException ruleParseError(long activityId, Throwable cause) {
        throw new BusinessException(4001,
                "活动[" + activityId + "]规则JSON解析失败: " + cause.getMessage());
    }

    /** 活动规则为空 */
    public static BusinessException ruleNotFound(long activityId) {
        return new BusinessException(4002, "活动[" + activityId + "]未找到对应规则");
    }
}
