package com.betterrag.service.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * 轻量 DAG 执行器(D1,S2):线性节点序列 + ParallelNode 一层并行,不做通用图。
 * <p>
 * 每个节点执行后记录 NodeTrace;ParallelOutput 的分支记录展平进 traces。
 * 异常策略见 {@link ExceptionPolicy}。
 */
public class DagPipeline {

    private final List<RagNode> nodes = new ArrayList<>();

    public DagPipeline add(RagNode node) {
        nodes.add(node);
        return this;
    }

    public PipelineResult run(NodeContext context) {
        List<NodeTrace> traces = new ArrayList<>();
        Object lastOutput = context.getLastOutput();
        for (RagNode node : nodes) {
            long startTime = System.nanoTime();
            String inputDigest = NodeTrace.digest(lastOutput);
            try {
                Object output = node.execute(context);
                long costMs = (System.nanoTime() - startTime) / 1_000_000;
                context.setLastOutput(output);
                if (output instanceof ParallelOutput parallelOutput) {
                    traces.addAll(parallelOutput.branchTraces());
                    traces.add(NodeTrace.success(node.id(), costMs, inputDigest,
                            "branches=" + parallelOutput.outputs().size()
                                    + "/" + parallelOutput.branchTraces().size()));
                } else {
                    traces.add(NodeTrace.success(node.id(), costMs, inputDigest,
                            NodeTrace.digest(output)));
                }
                lastOutput = output;
            } catch (Exception ex) {
                Throwable cause = (ex instanceof CompletionException && ex.getCause() != null)
                        ? ex.getCause() : ex;
                String message = cause.getMessage() == null
                        ? cause.getClass().getSimpleName() : cause.getMessage();
                if (node.exceptionPolicy() == ExceptionPolicy.SKIP_AND_CONTINUE) {
                    traces.add(NodeTrace.skipped(node.id(), message));
                    // lastOutput 保持上一个成功节点的输出,继续执行(降级)
                } else {
                    long costMs = (System.nanoTime() - startTime) / 1_000_000;
                    traces.add(NodeTrace.failed(node.id(), costMs, inputDigest, message));
                    return PipelineResult.fail(message, traces);
                }
            }
        }
        return PipelineResult.ok(lastOutput, traces);
    }
}
