package com.example.demo.exception;

/**
 * 业务异常：用于区分可预知的业务错误与系统未知错误
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
