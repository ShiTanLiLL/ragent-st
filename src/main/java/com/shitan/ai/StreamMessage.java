package com.shitan.ai;

/**
 * SSE 的回答增量事件；调用方按收到顺序拼接 content 就能得到完整回答。
 *
 * @param content 百炼本次新生成的一小段文字，不是截至当前的完整回答
 */
public record StreamMessage(
        String content
) {
}
