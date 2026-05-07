package com.ai.guide.model;


/**
 * 统一后端返回格式
 */
public record Result<T>(
        int code,       // 状态码：200 成功，500 失败
        boolean success, // 是否成功
        String message,  // 提示消息
        T data           // 返回的数据（可以是文件名、ID等）
) {
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, true, message, data);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, false, message, null);
    }
}
