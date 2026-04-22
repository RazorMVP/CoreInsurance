package com.nubeero.cia.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.util.List;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final T data;
    private final ApiMeta meta;
    private final List<ApiError> errors;

    private ApiResponse(T data, ApiMeta meta, List<ApiError> errors) {
        this.data = data;
        this.meta = meta;
        this.errors = errors;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data, null, null);
    }

    public static <T> ApiResponse<T> success(T data, ApiMeta meta) {
        return new ApiResponse<>(data, meta, null);
    }

    public static <T> ApiResponse<T> error(List<ApiError> errors) {
        return new ApiResponse<>(null, null, errors);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return error(List.of(new ApiError(code, message, null)));
    }
}
