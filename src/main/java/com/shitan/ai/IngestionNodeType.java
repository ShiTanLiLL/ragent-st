package com.shitan.ai;

/**
 * 当前两条摄取流程中真实存在的四种工作节点。
 */
public enum IngestionNodeType {
    FETCH,
    PARSE,
    EMBEDDING,
    PUBLISH
}
