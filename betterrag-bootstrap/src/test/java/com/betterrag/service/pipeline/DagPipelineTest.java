package com.betterrag.service.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2:线性流水线执行器测试(spec 01 §4.3 / plan S2)。
 */
class DagPipelineTest {

    /** 按执行顺序记账的 fake 节点 */
    private static RagNode node(String id, List<String> order, String output) {
        return new RagNode() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Object execute(NodeContext context) {
                order.add(id);
                context.put(id, output);
                return output;
            }
        };
    }

    @Test
    void 顺序执行记录执行序与trace() {
        List<String> order = new ArrayList<>();
        DagPipeline pipeline = new DagPipeline()
                .add(node("a", order, "A"))
                .add(node("b", order, "B"))
                .add(node("c", order, "C"));

        NodeContext context = new NodeContext();
        PipelineResult result = pipeline.run(context);

        assertTrue(result.success());
        assertEquals(List.of("a", "b", "c"), order, "节点应按注册顺序执行");
        assertEquals("C", result.output(), "输出应为最后一个节点返回值");
        assertEquals(3, result.traces().size());
        assertEquals(NodeStatus.SUCCESS, result.traces().get(0).status());
        assertTrue(result.traces().stream().allMatch(t -> t.costMs() >= 0), "耗时均应记录");
        assertEquals("A", result.traces().get(1).inputDigest(), "下一节点输入摘要应为上一节点输出");
        assertEquals("B", context.get("b"), "黑板应能按 key 读取节点写入");
        assertEquals("C", context.getLastOutput());
    }

    @Test
    void failFast异常终止且后续节点不执行() {
        List<String> order = new ArrayList<>();
        AtomicInteger cExecuted = new AtomicInteger();
        RagNode boom = new RagNode() {
            @Override
            public String id() {
                return "boom";
            }

            @Override
            public Object execute(NodeContext context) {
                order.add("boom");
                throw new IllegalStateException("节点爆炸");
            }
        };
        RagNode c = new RagNode() {
            @Override
            public String id() {
                return "c";
            }

            @Override
            public Object execute(NodeContext context) {
                cExecuted.incrementAndGet();
                return "C";
            }
        };

        PipelineResult result = new DagPipeline()
                .add(node("a", order, "A"))
                .add(boom)
                .add(c)
                .run(new NodeContext());

        assertFalse(result.success());
        assertTrue(result.error().contains("节点爆炸"));
        assertEquals(2, result.traces().size(), "a=SUCCESS + boom=FAILED,c 不应有 trace");
        assertEquals(NodeStatus.FAILED, result.traces().get(1).status());
        assertEquals("节点爆炸", result.traces().get(1).error());
        assertEquals(0, cExecuted.get(), "FAIL_FAST 后续节点不得执行");
    }

    @Test
    void skipAndContinue降级后流水线继续() {
        List<String> order = new ArrayList<>();
        RagNode degrade = new RagNode() {
            @Override
            public String id() {
                return "degrade";
            }

            @Override
            public Object execute(NodeContext context) {
                throw new RuntimeException("关键词检索失败");
            }

            @Override
            public ExceptionPolicy exceptionPolicy() {
                return ExceptionPolicy.SKIP_AND_CONTINUE;
            }
        };

        PipelineResult result = new DagPipeline()
                .add(node("a", order, "A"))
                .add(degrade)
                .add(node("c", order, "C"))
                .run(new NodeContext());

        assertTrue(result.success(), "降级节点失败不应终止流水线");
        assertEquals(List.of("a", "c"), order);
        assertEquals(NodeStatus.SKIPPED, result.traces().get(1).status());
        assertEquals("关键词检索失败", result.traces().get(1).error());
        assertEquals("C", result.output(), "输出应为最后一个成功节点");
    }

    @Test
    void 空流水线直接成功() {
        PipelineResult result = new DagPipeline().run(new NodeContext());
        assertTrue(result.success());
        assertTrue(result.traces().isEmpty());
    }

    @Test
    void 超长输出摘要被截断() {
        RagNode longNode = new RagNode() {
            @Override
            public String id() {
                return "long";
            }

            @Override
            public Object execute(NodeContext context) {
                return "x".repeat(500);
            }
        };
        PipelineResult result = new DagPipeline().add(longNode).run(new NodeContext());
        String digest = result.traces().get(0).outputDigest();
        assertTrue(digest.length() <= 201, "摘要应截断到 200 字符,实际:" + digest.length());
    }
}
