package com.madness.mqmremovemark.common;


import com.madness.mqmremovemark.model.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    /**
     * 捕获自定义业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public Response handleBusinessException(BusinessException e) {
        log.error("业务异常: {}", e.getMessage());
        // 返回错误码，数据和图片集合置空
        return new Response(e.getErrorCode().getCode(), null, e.getMessage());
    }
    /**
     * 捕获其他未知异常
     */
    @ExceptionHandler(Exception.class)
    public Response handleException(Exception e) {
        log.error("系统异常", e);
        // 返回通用网络/系统错误码
        return new Response(ErrorCode.NETWORK_ERROR.getCode(), null, e.getMessage());
    }
}