package com.ai.guide.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 全局统一 RESTful API 响应模型封装
 *
 * 所属领域：common.model（全局通用模型）
 * 架构职责：统一服务端返回给客户端的 HTTP JSON 响应信封结构（包含 code、message、data、timestamp）。
 *
 * @param <T> 业务负载数据类型
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private boolean success;

    public static <T> Result<T> success() {
        return new Result<>(200, "ok", null, true);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "ok", data, true);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, message, data, true);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null, false);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null, false);
    }
}