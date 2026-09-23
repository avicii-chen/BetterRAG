package com.betterrag.service.pipeline;

/**
 * 节点执行终态。RUNNING 保留给未来异步执行场景,当前同步执行器只产生后三种。
 */
public enum NodeStatus {
    RUNNING, SUCCESS, FAILED, SKIPPED
}
