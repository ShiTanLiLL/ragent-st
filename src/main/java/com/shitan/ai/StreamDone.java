package com.shitan.ai;

/**
 * SSE 的最后一条事件，说明生成正常完成、被取消或异常结束。
 *
 * @param sourceTitle 正常完成时使用的证据标题
 * @param cancelled   是否由用户主动取消
 * @param error       异常结束时的简短错误；正常或取消时为 null
 */
public record StreamDone(
        String sourceTitle,
        boolean cancelled,
        String error
) {
}
