package com.betterrag.service.rag;

import com.betterrag.service.trace.TraceContext;
import com.betterrag.service.trace.TraceRecorder;
import com.betterrag.service.trace.TraceRecord;
import com.betterrag.service.trace.TraceWriter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.Query;
import org.springframework.core.io.ByteArrayResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S1 埋点验证:RewriteQueryTransformer 的 rewrite span 与改写结果记录。
 */
class RewriteQueryTransformerTest {

    static class CapturingWriter implements TraceWriter {
        final List<TraceRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void write(TraceRecord record) {
            records.add(record);
        }
    }

    private ChatModel chatModelReturning(String text) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
        return chatModel;
    }

    @Test
    void 改写成功时记录rewrite的span与改写结果() {
        CapturingWriter writer = new CapturingWriter();
        TraceRecorder recorder = new TraceRecorder(writer, true, Runnable::run);
        TraceContext ctx = recorder.start("session-x", "原始问题");

        RewriteQueryTransformer transformer = new RewriteQueryTransformer(
                chatModelReturning("改写后的检索问题"),
                new ByteArrayResource("system".getBytes()),
                new ByteArrayResource("user: {question}".getBytes()),
                null,
                recorder);

        Query result = transformer.transform(new Query("原始问题"));
        recorder.finish(ctx, "答案");

        assertEquals("改写后的检索问题", result.text());
        TraceRecord record = writer.records.get(0);
        assertEquals("改写后的检索问题", record.rewrittenQuery());
        assertEquals(1, record.nodes().size());
        assertEquals("rewrite", record.nodes().get(0).node());
        assertEquals("SUCCESS", record.nodes().get(0).status());
        assertEquals("原始问题", record.nodes().get(0).inputDigest());
    }

    @Test
    void 改写失败时回退原问题且主流程不受影响() {
        CapturingWriter writer = new CapturingWriter();
        TraceRecorder recorder = new TraceRecorder(writer, true, Runnable::run);
        TraceContext ctx = recorder.start("session-x", "原始问题");

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("模型超时"));
        RewriteQueryTransformer transformer = new RewriteQueryTransformer(
                chatModel,
                new ByteArrayResource("system".getBytes()),
                new ByteArrayResource("user: {question}".getBytes()),
                null,
                recorder);

        Query result = transformer.transform(new Query("原始问题"));
        recorder.finish(ctx, "答案");

        assertEquals("原始问题", result.text(), "改写失败应回退原问题");
        TraceRecord record = writer.records.get(0);
        assertEquals("原始问题", record.rewrittenQuery());
        // rewrite() 内部吞掉异常并回退,对调用方而言节点完成(降级成功),span 为 SUCCESS
        assertEquals("SUCCESS", record.nodes().get(0).status());
    }
}
