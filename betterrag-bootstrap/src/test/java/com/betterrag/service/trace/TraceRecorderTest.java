package com.betterrag.service.trace;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 核心:TraceRecorder 单元测试(TDD 先行)。
 * 铁律(spec 01):观测代码任何异常不得影响问答业务。
 */
class TraceRecorderTest {

    /** 捕获写入的测试替身,可注入写库失败 */
    static class CapturingWriter implements TraceWriter {
        final List<TraceRecord> records = new CopyOnWriteArrayList<>();
        volatile boolean throwOnWrite;

        @Override
        public void write(TraceRecord record) {
            if (throwOnWrite) {
                throw new IllegalStateException("db down");
            }
            records.add(record);
        }
    }

    /** 同步执行器:测试中让异步写库变得确定 */
    private static final Executor DIRECT = Runnable::run;

    @Test
    void span记录与摘要截断() {
        CapturingWriter writer = new CapturingWriter();
        TraceRecorder recorder = new TraceRecorder(writer, true, DIRECT);

        TraceContext ctx = recorder.start("session-1", "年假有多少天?");
        assertSame(ctx, recorder.current(), "start 后应绑定当前线程");

        String longInput = "x".repeat(500);
        String result = recorder.spanAround("rewrite", longInput,
                () -> "改写后问题",
                out -> "chars=" + out.length());
        assertEquals("改写后问题", result);

        recorder.finish(ctx, "每年 5 天年假。");

        assertEquals(1, writer.records.size());
        TraceRecord record = writer.records.get(0);
        assertEquals("年假有多少天?", record.question());
        assertEquals("每年 5 天年假。", record.answerText());
        assertEquals("session-1", record.sessionId());
        assertNotNull(record.traceId());
        assertTrue(record.totalMs() >= 0, "totalMs 应被记录");
        assertNull(recorder.current(), "finish 后应清理 ThreadLocal 绑定");

        assertEquals(1, record.nodes().size());
        NodeSpan span = record.nodes().get(0);
        assertEquals("rewrite", span.node());
        assertEquals("SUCCESS", span.status());
        assertTrue(span.inputDigest().length() <= 201, "超长输入摘要应截断到 200 字符");
        assertEquals("chars=5", span.outputDigest());
        assertNull(span.error());
    }

    @Test
    void 失败span记录并继续抛出() {
        CapturingWriter writer = new CapturingWriter();
        TraceRecorder recorder = new TraceRecorder(writer, true, DIRECT);
        TraceContext ctx = recorder.start("s", "q");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> recorder.spanAround("rerank", () -> {
                    throw new IllegalStateException("rerank 超时");
                }));
        assertEquals("rerank 超时", thrown.getMessage(), "业务异常必须原样上抛,trace 只是旁观者");

        recorder.finishWithError(ctx, "stream error");

        TraceRecord record = writer.records.get(0);
        assertEquals("stream error", record.error());
        assertNull(record.answerText());
        assertEquals(1, record.nodes().size());
        assertEquals("FAILED", record.nodes().get(0).status());
        assertEquals("rerank 超时", record.nodes().get(0).error());
    }

    @Test
    void 写库失败不上抛() {
        CapturingWriter writer = new CapturingWriter();
        writer.throwOnWrite = true;
        TraceRecorder recorder = new TraceRecorder(writer, true, DIRECT);

        TraceContext ctx = recorder.start("s", "q");
        assertDoesNotThrow(() -> recorder.finish(ctx, "answer"), "写库异常绝不能影响业务线程");
    }

    @Test
    void 关闭开关零写入() {
        CapturingWriter writer = new CapturingWriter();
        TraceRecorder recorder = new TraceRecorder(writer, false, DIRECT);

        TraceContext ctx = recorder.start("s", "q");
        assertNull(recorder.current(), "关闭时 current() 应为 null,组件直通");

        String value = recorder.spanAround("node", () -> "value");
        assertEquals("value", value, "关闭时工作函数应直通执行");

        recorder.finish(ctx, "a");
        assertTrue(writer.records.isEmpty(), "关闭时不应产生任何写入");
    }

    @Test
    void 显式ctx的spanAround用于跨线程场景() {
        CapturingWriter writer = new CapturingWriter();
        TraceRecorder recorder = new TraceRecorder(writer, true, DIRECT);
        TraceContext ctx = recorder.start("s", "q");

        recorder.spanAround(ctx, "generate", () -> List.of(1, 2), l -> "docs=2");
        recorder.finish(ctx, "ans");

        TraceRecord record = writer.records.get(0);
        assertEquals("generate", record.nodes().get(0).node());
        assertEquals("docs=2", record.nodes().get(0).outputDigest());
    }

    @Test
    void 线程外调用spanAround直通() {
        CapturingWriter writer = new CapturingWriter();
        TraceRecorder recorder = new TraceRecorder(writer, true, DIRECT);
        // 未 start,无 ThreadLocal 上下文(模拟 advisor 在其它线程执行)
        String value = recorder.spanAround("hybrid", () -> "docs");
        assertEquals("docs", value, "无上下文时应直通执行并返回结果");
    }
}
