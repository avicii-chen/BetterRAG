package com.betterrag.service.rag;

import com.betterrag.config.RAGProperties;
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
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S1 埋点验证:HybridDocumentRetriever 的 hybrid-retrieve span。
 */
class HybridDocumentRetrieverTest {

    static class CapturingWriter implements TraceWriter {
        final List<TraceRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void write(TraceRecord record) {
            records.add(record);
        }
    }

    private Document doc(String text) {
        return Document.builder().text(text).build();
    }

    @Test
    void 混合检索记录span与融合后文档数() {
        CapturingWriter writer = new CapturingWriter();
        TraceRecorder recorder = new TraceRecorder(writer, true, Runnable::run);
        TraceContext ctx = recorder.start("s", "问题");

        VectorStoreDocumentRetriever vectorRetriever = mock(VectorStoreDocumentRetriever.class);
        KeywordDocumentRetriever keywordRetriever = mock(KeywordDocumentRetriever.class);
        when(vectorRetriever.retrieve(any(Query.class))).thenReturn(List.of(doc("v1"), doc("v2")));
        when(keywordRetriever.retrieve(any(Query.class))).thenReturn(List.of(doc("k1")));

        RAGProperties props = new RAGProperties();
        props.setRetrieveTopK(5);
        props.setRrfK(60);

        HybridDocumentRetriever hybrid =
                new HybridDocumentRetriever(vectorRetriever, keywordRetriever, props, recorder);
        List<Document> out = hybrid.retrieve(new Query("问题"));
        recorder.finish(ctx, "ans");

        assertEquals(3, out.size(), "RRF 融合应保留全部三个不同文档");

        NodeSpan span = writer.records.get(0).nodes().get(0);
        assertEquals("hybrid-retrieve", span.node());
        assertEquals("SUCCESS", span.status());
        assertEquals("问题", span.inputDigest());
        assertEquals("docs=3", span.outputDigest());
    }
}
