package com.example.demo.exception;

import com.example.demo.entity.vo.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：将异常统一转换为前端认识的 Result JSON 结构
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：把提示信息原样返回给调用方
     */
    @ExceptionHandler(BusinessException.class)
    public Result handleBusinessException(BusinessException e) {
        log.warn("Business error: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    /**
     * 参数校验失败：@Validated + @NotBlank 等触发
     */
    @ExceptionHandler({ConstraintViolationException.class, BindException.class})
    public Result handleValidationException(Exception e) {
        String message;
        if (e instanceof ConstraintViolationException cv) {
            message = cv.getConstraintViolations().stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.joining(", "));
        } else {
            message = "请求参数不合法";
        }
        log.warn("Validation error: {}", message);
        return Result.fail(message);
    }

    /**
     * 兜底异常：避免把堆栈直接暴露给前端
     */
    @ExceptionHandler(Exception.class)
    public Result handleException(Exception e) {
        log.error("Internal server error", e);
        return Result.fail("服务器内部错误");
    }
}
