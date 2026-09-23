package com.betterrag.service.trace;

/**
 * 单个节点(span)的执行记录,序列化为 rag_trace.nodes JSONB 数组的元素。
 */
public record NodeSpan(
        String node,
        String status,
        long costMs,
        String inputDigest,
        String outputDigest,
        String error) {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
}
