package com.betterrag.service.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * 并行节点(D1):包装多个分支节点并发执行,join 后合并输出。
 * <p>
 * 分支自身的 exceptionPolicy 生效:SKIP_AND_CONTINUE 的分支失败记 SKIPPED、
 * 其余分支继续;FAIL_FAST 分支失败则整体向上抛(通常导致流水线终止)。
 * 分支输出同时写入黑板(key = 分支节点 id)。
 */
public class ParallelNode implements RagNode {

    private final String nodeId;
    private final List<RagNode> branches;
    private final Executor executor;

    public ParallelNode(String nodeId, List<RagNode> branches) {
        this(nodeId, branches, null);
    }

    public ParallelNode(String nodeId, List<RagNode> branches, Executor executor) {
        this.nodeId = nodeId;
        this.branches = List.copyOf(branches);
        this.executor = executor;
    }

    @Override
    public String id() {
        return nodeId;
    }

    @Override
    public Object execute(NodeContext context) {
        Executor effectiveExecutor = executor != null ? executor : ForkJoinPool.commonPool();
        List<CompletableFuture<BranchResult>> futures = new ArrayList<>();
        for (RagNode branch : branches) {
            futures.add(CompletableFuture.supplyAsync(() -> runBranch(branch, context), effectiveExecutor));
        }

        List<Object> outputs = new ArrayList<>();
        List<NodeTrace> traces = new ArrayList<>();
        for (CompletableFuture<BranchResult> future : futures) {
            BranchResult result = future.join();
            traces.add(result.trace());
            if (result.trace().status() == NodeStatus.SUCCESS) {
                outputs.add(result.output());
            }
        }
        return new ParallelOutput(outputs, traces);
    }

    private BranchResult runBranch(RagNode branch, NodeContext context) {
        long startTime = System.nanoTime();
        try {
            Object output = branch.execute(context);
            long costMs = (System.nanoTime() - startTime) / 1_000_000;
            if (output != null) {
                context.put(branch.id(), output);
            }
            return new BranchResult(output,
                    NodeTrace.success(branch.id(), costMs, null, NodeTrace.digest(output)));
        } catch (Exception ex) {
            if (branch.exceptionPolicy() == ExceptionPolicy.SKIP_AND_CONTINUE) {
                return new BranchResult(null, NodeTrace.skipped(branch.id(), message(ex)));
            }
            throw new CompletionException(ex);
        }
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private record BranchResult(Object output, NodeTrace trace) {
    }
}
