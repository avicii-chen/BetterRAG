package com.betterrag.service.rag;

import com.betterrag.config.RAGProperties;
import com.betterrag.service.RerankService;
import com.betterrag.service.trace.NodeSpan;
import com.betterrag.service.trace.TraceContext;
import com.betterrag.service.trace.TraceRecorder;
import com.betterrag.service.trace.TraceRecord;
import com.betterrag.service.trace.TraceWriter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S1 埋点验证:RerankDocumentPostProcessor 的 rerank span。
 */
class RerankDocumentPostProcessorTest {

    static class CapturingWriter implements TraceWriter {
        final List<TraceRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void write(TraceRecord record) {
            records.add(record);
        }
    }

    private Document doc(String text) {
        return Document.builder().text(text).metadata("source", text + ".md").build();
    }

    @Test
    void rerank成功时记录span与候选保留数() {
        CapturingWriter writer = new CapturingWriter();
        TraceRecorder recorder = new TraceRecorder(writer, true, Runnable::run);
        TraceContext ctx = recorder.start("s", "q");

        RerankService rerankService = mock(RerankService.class);
        when(rerankService.rerank(eq("问题"), anyList(), eq(4))).thenReturn(List.of(
                new RerankService.RerankItem(0, 0.9),
                new RerankService.RerankItem(2, 0.5)));

        RAGProperties props = new RAGProperties();
        props.setRerankTopN(4);
        props.setRerankMaxDocumentChars(1000);

        RerankDocumentPostProcessor processor = new RerankDocumentPostProcessor(rerankService, props, recorder);
        List<Document> out = processor.process(new Query("问题"),
                List.of(doc("甲"), doc("乙"), doc("丙")));
        recorder.finish(ctx, "ans");

        assertEquals(2, out.size(), "按 rerank 结果取 top2");
        assertEquals("甲", out.get(0).getText());
        assertEquals("丙", out.get(1).getText());

        NodeSpan span = writer.records.get(0).nodes().get(0);
        assertEquals("rerank", span.node());
        assertEquals("SUCCESS", span.status());
        assertEquals("candidates=3", span.inputDigest());
        assertEquals("kept=2", span.outputDigest());
    }
}
