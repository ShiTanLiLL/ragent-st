package com.shitan.ai;

/**
 * 一个候选模型在熔断器中的三态：正常、暂时隔离、等待一次探测。
 */
public enum ModelHealthState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
