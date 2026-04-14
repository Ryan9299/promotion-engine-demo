package com.ryan.promotion.common.exception;

import com.ryan.promotion.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器，统一将各类异常转换为 {@link Result} 格式响应。
 *
 * <p>处理优先级（从高到低）：
 * <ol>
 *   <li>{@link BusinessException}：业务逻辑异常，使用其自定义 code 与 message。</li>
 *   <li>{@link MethodArgumentNotValidException}：JSR-303 参数校验失败，逐字段聚合错误。</li>
 *   <li>{@link MethodArgumentTypeMismatchException}：路径/查询参数类型转换失败（如枚举值不合法）。</li>
 *   <li>{@link Exception}：兜底处理，返回 500，不暴露内部堆栈。</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常，直接使用业务层定义的错误码和消息。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理 @Valid 参数校验失败，聚合所有字段错误信息返回。
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidationException(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败：{}", details);
        return Result.badRequest(details);
    }

    /**
     * 处理路径参数或查询参数类型不匹配（如传入非法枚举值）。
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<?> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = String.format("参数 [%s] 值非法：%s", e.getName(), e.getValue());
        log.warn("参数类型异常：{}", message);
        return Result.badRequest(message);
    }

    /**
     * 处理静态资源未找到（如 favicon.ico），返回 404 不打印堆栈。
     */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<?> handleNoResourceFound(NoResourceFoundException e) {
        return Result.error(404, e.getMessage());
    }

    /**
     * 兜底处理所有未捕获异常，记录完整堆栈，响应 500 不暴露内部细节。
     */
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.serverError("系统内部错误，请稍后重试");
    }
}
