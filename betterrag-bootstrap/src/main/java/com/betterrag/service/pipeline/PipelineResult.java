package com.betterrag.service.pipeline;

import java.util.List;

/**
 * 流水线执行结果:终态输出 + 全部节点/分支执行记录。
 */
public record PipelineResult(boolean success, Object output, List<NodeTrace> traces, String error) {

    public static PipelineResult ok(Object output, List<NodeTrace> traces) {
        return new PipelineResult(true, output, traces, null);
    }

    public static PipelineResult fail(String error, List<NodeTrace> traces) {
        return new PipelineResult(false, null, traces, error);
    }
}
