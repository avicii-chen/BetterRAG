package com.betterrag.service.pipeline;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2:并行节点测试(spec 01 §4.3:双路并发 join、单路降级、FAIL_FAST 传播)。
 */
class ParallelNodeTest {

    private static RagNode branch(String id, Object output) {
        return new RagNode() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Object execute(NodeContext context) {
                return output;
            }
        };
    }

    private static RagNode failing(String id, ExceptionPolicy policy, String message) {
        return new RagNode() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Object execute(NodeContext context) {
                throw new RuntimeException(message);
            }

            @Override
            public ExceptionPolicy exceptionPolicy() {
                return policy;
            }
        };
    }

    private static RagNode sleeper(String id, long millis) {
        return new RagNode() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Object execute(NodeContext context) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return id + "-done";
            }
        };
    }

    @Test
    void 并行分支join合并输出并写黑板() {
        ParallelNode parallel = new ParallelNode("hybrid", List.of(
                branch("vector", "V"), branch("keyword", "K")));

        NodeContext context = new NodeContext();
        PipelineResult result = new DagPipeline().add(parallel).run(context);

        assertTrue(result.success());
        ParallelOutput output = (ParallelOutput) result.output();
        assertEquals(List.of("V", "K"), output.outputs());
        assertEquals(2, output.branchTraces().size());
        assertTrue(output.branchTraces().stream()
                .allMatch(t -> t.status() == NodeStatus.SUCCESS));

        assertEquals(3, result.traces().size(), "分支 trace 展平 + parallel 自身 trace");
        assertEquals("vector", result.traces().get(0).node());
        assertEquals("keyword", result.traces().get(1).node());
        assertEquals("hybrid", result.traces().get(2).node());
        assertEquals("branches=2/2", result.traces().get(2).outputDigest());

        assertEquals("V", context.get("vector"), "分支输出应写入黑板(key=分支id)");
        assertEquals("K", context.get("keyword"));
    }

    @Test
    void 单路失败skip降级另一路结果保留() {
        ParallelNode parallel = new ParallelNode("hybrid", List.of(
                branch("vector", "V"),
                failing("keyword", ExceptionPolicy.SKIP_AND_CONTINUE, "ES 超时")));

        PipelineResult result = new DagPipeline().add(parallel).run(new NodeContext());

        assertTrue(result.success(), "SKIP_AND_CONTINUE 分支失败不应终止");
        ParallelOutput output = (ParallelOutput) result.output();
        assertEquals(List.of("V"), output.outputs(), "降级分支不产生输出");
        assertEquals(NodeStatus.SUCCESS, output.branchTraces().get(0).status());
        assertEquals(NodeStatus.SKIPPED, output.branchTraces().get(1).status());
        assertEquals("ES 超时", output.branchTraces().get(1).error());
        assertEquals("branches=1/2", result.traces().get(2).outputDigest());
    }

    @Test
    void failFast分支异常解包后向上传播() {
        ParallelNode parallel = new ParallelNode("hybrid", List.of(
                failing("vector", ExceptionPolicy.FAIL_FAST, "向量库宕机"),
                branch("keyword", "K")));

        PipelineResult result = new DagPipeline().add(parallel).run(new NodeContext());

        assertFalse(result.success());
        assertTrue(result.error().contains("向量库宕机"),
                "根因应从 CompletionException 中解包,实际:" + result.error());
    }

    @Test
    void 分支真并发执行() {
        long startTime = System.currentTimeMillis();
        PipelineResult result = new DagPipeline()
                .add(new ParallelNode("p", List.of(sleeper("a", 150), sleeper("b", 150))))
                .run(new NodeContext());
        long elapsed = System.currentTimeMillis() - startTime;

        assertTrue(result.success());
        assertTrue(elapsed < 280, "两路各睡 150ms,并发总耗时应远低于串行 300ms,实际:" + elapsed + "ms");
    }
}
