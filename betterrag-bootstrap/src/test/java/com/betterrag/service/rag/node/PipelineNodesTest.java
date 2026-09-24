package com.betterrag.service.rag.node;

import java.util.List;

import com.betterrag.config.RAGProperties;
import com.betterrag.service.pipeline.ExceptionPolicy;
import com.betterrag.service.pipeline.NodeContext;
import com.betterrag.service.rag.RerankDocumentPostProcessor;
import com.betterrag.service.rag.RewriteQueryTransformer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S3 节点单测:RewriteNode / VectorBranchNode / KeywordBranchNode / RrfFuseNode / RerankNode。
 */
class PipelineNodesTest {

    private static Document doc(String text) {
        return Document.builder().text(text).build();
    }

    @Test
    void rewrite节点写入黑板并返回改写结果() {
        RewriteQueryTransformer transformer = mock(RewriteQueryTransformer.class);
        when(transformer.transform(any(Query.class)))
                .thenAnswer(invocation -> ((Query) invocation.getArgument(0)).mutate().text("改写后问题").build());

        RewriteNode node = new RewriteNode(transformer);
        NodeContext context = new NodeContext();
        context.put(RewriteNode.QUESTION_KEY, "原始问题");

        Object result = node.execute(context);

        assertEquals("改写后问题", result);
        assertEquals("改写后问题", context.<String>get(RewriteNode.REWRITTEN_KEY));
    }

    @Test
    void vector分支构造带kb过滤的Query() {
        VectorStoreDocumentRetriever retriever = mock(VectorStoreDocumentRetriever.class);
        org.mockito.ArgumentCaptor<Query> queryCaptor = org.mockito.ArgumentCaptor.forClass(Query.class);
        when(retriever.retrieve(queryCaptor.capture())).thenReturn(List.of(doc("v1")));

        VectorBranchNode node = new VectorBranchNode(retriever);
        NodeContext context = new NodeContext();
        context.put(RewriteNode.REWRITTEN_KEY, "改写后问题");
        context.put(RewriteNode.KB_KEY, "hr");

        List<?> result = assertInstanceOf(List.class, node.execute(context));

        assertEquals(1, result.size());
        Query passed = queryCaptor.getValue();
        assertEquals("改写后问题", passed.text());
        assertEquals("kb == 'hr'",
                passed.context().get(org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever.FILTER_EXPRESSION),
                "kb 应写入过滤表达式");
    }

    @Test
    void keyword分支策略为降级并正常检索() {
        com.betterrag.service.rag.KeywordDocumentRetriever retriever =
                mock(com.betterrag.service.rag.KeywordDocumentRetriever.class);
        when(retriever.retrieve(any(Query.class))).thenReturn(List.of(doc("k1")));

        KeywordBranchNode node = new KeywordBranchNode(retriever);
        assertEquals(ExceptionPolicy.SKIP_AND_CONTINUE, node.exceptionPolicy(),
                "keyword 分支必须可降级(对齐 advisor 模式)");

        NodeContext context = new NodeContext();
        context.put(RewriteNode.REWRITTEN_KEY, "改写后问题");
        List<?> result = assertInstanceOf(List.class, node.execute(context));
        assertEquals(1, result.size());
    }

    @Test
    void fuse节点融合双路并容忍缺失分支() {
        RAGProperties properties = new RAGProperties();
        properties.setRrfK(60);
        properties.setRetrieveTopK(5);

        RrfFuseNode fuseNode = new RrfFuseNode(properties);

        NodeContext context = new NodeContext();
        context.put("vector", List.of(doc("v1"), doc("v2")));
        // keyword 分支被 SKIP 时黑板无值

        @SuppressWarnings("unchecked")
        List<Document> fused = (List<Document>) fuseNode.execute(context);

        assertEquals(2, fused.size(), "降级分支缺位时,向量路结果应原样通过");
    }

    @Test
    void rerank节点透传检索结果给处理器() {
        RerankDocumentPostProcessor processor = mock(RerankDocumentPostProcessor.class);
        List<Document> candidates = List.of(doc("甲"), doc("乙"), doc("丙"));
        List<Document> reranked = List.of(doc("甲"));
        when(processor.process(any(Query.class), any())).thenReturn(reranked);

        RerankNode node = new RerankNode(processor);
        NodeContext context = new NodeContext();
        context.put(RewriteNode.REWRITTEN_KEY, "改写后问题");
        context.setLastOutput(candidates);

        Object result = node.execute(context);

        assertSame(reranked, result);
    }

    @Test
    void rerank节点无候选时返回空() {
        RerankDocumentPostProcessor processor = mock(RerankDocumentPostProcessor.class);
        RerankNode node = new RerankNode(processor);
        NodeContext context = new NodeContext();
        context.setLastOutput(null);

        Object result = node.execute(context);
        assertEquals(List.of(), result);
        assertNull(context.get(GenerateNode.SOURCES_KEY));
    }
}
