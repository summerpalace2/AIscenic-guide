package com.ai.guide.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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