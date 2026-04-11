package com.ryan.promotion.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 响应包装，所有接口均返回此结构。
 *
 * <pre>
 * {
 *   "code":    200,
 *   "message": "success",
 *   "data":    { ... }
 * }
 * </pre>
 *
 * @param <T> 业务数据类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /** HTTP 语义码：200=成功，400=参数错误，4xxx=业务错误，500=系统错误 */
    private int code;

    /** 人可读描述 */
    private String message;

    /** 业务数据，失败时为 null */
    private T data;

    // ---------------------------------------------------------------
    // 工厂方法
    // ---------------------------------------------------------------

    /** 成功并携带数据 */
    public static <T> Result<T> ok(T data) {
        return Result.<T>builder().code(200).message("success").data(data).build();
    }

    /** 成功，无数据（如删除、上下线操作） */
    public static Result<Void> ok() {
        return Result.<Void>builder().code(200).message("success").build();
    }

    /** 业务错误 */
    public static <T> Result<T> error(int code, String message) {
        return Result.<T>builder().code(code).message(message).build();
    }

    /** 参数校验错误（400） */
    public static <T> Result<T> badRequest(String message) {
        return error(400, message);
    }

    /** 系统内部错误（500） */
    public static <T> Result<T> serverError(String message) {
        return error(500, message);
    }
}
