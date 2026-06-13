package com.madness.mqmremovemark.common;


import lombok.Getter;
import lombok.experimental.Accessors;
/**
 * 自定义业务异常
 */
@Getter
@Accessors(chain = true)
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
    }
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}