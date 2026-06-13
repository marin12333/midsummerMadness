package com.madness.mqmremovemark.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 统一接口返回对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Response<T> {

    int code;

    T data;

    String message;

    public static <T> Response<T> success(T data) {
        return new Response<>(10000, data, "success");
    }
}