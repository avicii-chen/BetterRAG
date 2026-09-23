package com.betterrag.service.pipeline;

import java.util.List;

/**
 * ParallelNode 的输出:成功分支的输出列表 + 各分支执行记录。
 * DagPipeline 会把 branchTraces 展平进整体 traces。
 */
public record ParallelOutput(List<Object> outputs, List<NodeTrace> branchTraces) {
}
