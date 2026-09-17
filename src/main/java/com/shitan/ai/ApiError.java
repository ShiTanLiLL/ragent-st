package com.shitan.ai;

/**
 * 当前 Web 层最小的错误响应，避免把 Spring 内部异常结构直接暴露给调用方。
 *
 * @param message 调用方可以直接理解的错误原因
 */
public record ApiError(
        String message
) {
}
