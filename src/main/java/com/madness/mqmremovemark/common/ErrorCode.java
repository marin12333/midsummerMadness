package com.madness.mqmremovemark.common;


import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    // 成功
    SUCCESS(10000, "操作成功"),

    // 客户端错误 20001-20999
    PARAM_ERROR(20001, "参数不能为空"),
    URL_NOT_FOUND(20002, "未在文本中检测到有效的小红书链接"),

    // 业务逻辑错误 21001-29999
    DATA_PARSE_ERROR(21001, "无法解析页面数据，请重试"),
    NOTE_EMPTY(21002, "笔记详情数据为空或已被删除"),
    NETWORK_ERROR(21003, "请求处理异常，网络连接失败");

    private final Integer code;
    private final String msg;
}