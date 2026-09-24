package com.betterrag.service.rag.node;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.betterrag.config.RAGProperties;
import com.betterrag.service.pipeline.DagPipeline;
import com.betterrag.service.pipeline.NodeContext;
import com.betterrag.service.pipeline.NodeStatus;
import com.betterrag.service.pipeline.NodeTrace;
import com.betterrag.service.pipeline.ParallelNode;
import com.betterrag.service.pipeline.PipelineResult;
import com.betterrag.service.rag.KeywordDocumentRetriever;
import com.betterrag.service.rag.RerankDocumentPostProcessor;
import com.betterrag.service.rag.RewriteQueryTransformer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;

import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * S3 装配测试:完整 DAG(rewrite → hybrid-retrieve ∥ → fuse → rerank → generate)
 * 以 mock 组件跑通全链路,验证 trace 粒度、降级与流式聚合。
 */
class RagPipelineAssemblyTest {

    private static Document doc(String text) {
        return Document.builder().text(text).build();
    }

    /** 可流式的 ChatModel mock:extraInterfaces 补 StreamingChatModel */
    private ChatModel streamingChatModel(String... tokens) {
        ChatModel chatModel = mock(ChatModel.class, withSettings().extraInterfaces(StreamingChatModel.class));
        List<ChatResponse> chunks = Arrays.stream(tokens)
                .map(token -> new ChatResponse(List.of(new Generation(new AssistantMessage(token)))))
                .toList();
        when(((StreamingChatModel) chatModel).stream(any(Prompt.class)))
                .thenReturn(Flux.fromIterable(chunks));
        return chatModel;
    }

    private DagPipeline buildPipeline(ChatModel chatModel,
                                      VectorStoreDocumentRetriever vectorRetriever,
                                      KeywordDocumentRetriever keywordRetriever,
                                      RewriteQueryTransformer transformer,
                                      RerankDocumentPostProcessor processor) {
        RAGProperties properties = new RAGProperties();
        properties.setRrfK(60);
        properties.setRetrieveTopK(5);
        ChatClient generateClient = ChatClient.builder(chatModel).build();
        return new DagPipeline()
                .add(new RewriteNode(transformer))
                .add(new ParallelNode("hybrid-retrieve", List.of(
                        new VectorBranchNode(vectorRetriever),
                        new KeywordBranchNode(keywordRetriever))))
                .add(new RrfFuseNode(properties))
                .add(new RerankNode(processor))
                .add(new GenerateNode(generateClient, properties));
    }

    private RewriteQueryTransformer transformer() {
        RewriteQueryTransformer transformer = mock(RewriteQueryTransformer.class);
        when(transformer.transform(any(Query.class))).thenAnswer(invocation ->
                ((Query) invocation.getArgument(0)).mutate().text("改写:年假政策").build());
        return transformer;
    }

    @Test
    void 全链路成功场景() {
        VectorStoreDocumentRetriever vector = mock(VectorStoreDocumentRetriever.class);
        when(vector.retrieve(any(Query.class))).thenReturn(List.of(doc("v-年假每年5天")));
        KeywordDocumentRetriever keyword = mock(KeywordDocumentRetriever.class);
        when(keyword.retrieve(any(Query.class))).thenReturn(List.of(doc("k-年假每年5天")));
        RerankDocumentPostProcessor processor = mock(RerankDocumentPostProcessor.class);
        List<Document> reranked = List.of(doc("v-年假每年5天"));
        when(processor.process(any(Query.class), any())).thenReturn(reranked);
        ChatModel chatModel = streamingChatModel("每", "年", "5", "天");

        List<String> tokens = new CopyOnWriteArrayList<>();
        NodeContext context = new NodeContext();
        context.put(RewriteNode.QUESTION_KEY, "年假有多少天?");
        context.put(RewriteNode.SESSION_ID_KEY, "s-1");
        context.put(RewriteNode.TOKEN_CONSUMER_KEY, (Consumer<String>) tokens::add);
        context.put(RewriteNode.KB_KEY, "hr");

        PipelineResult result = buildPipeline(chatModel, vector, keyword, transformer(), processor)
                .run(context);

        assertTrue(result.success());
        assertEquals("每年5天", result.output(), "答案应为流式 token 聚合");
        assertEquals(List.of("每", "年", "5", "天"), tokens, "token 应逐个推送给消费者");
        List<Document> sources = context.get(GenerateNode.SOURCES_KEY);
        assertEquals(1, sources.size(), "sources 直接取检索结果");

        List<String> traceNodes = result.traces().stream().map(NodeTrace::node).toList();
        assertEquals(List.of("rewrite", "vector", "keyword", "hybrid-retrieve", "fuse", "rerank", "generate"),
                traceNodes, "trace 应含双分支与全部节点");
        assertTrue(result.traces().stream().allMatch(t -> t.status() == NodeStatus.SUCCESS));
    }

    @Test
    void keyword分支失败时降级仍产出答案() {
        VectorStoreDocumentRetriever vector = mock(VectorStoreDocumentRetriever.class);
        when(vector.retrieve(any(Query.class))).thenReturn(List.of(doc("v-年假每年5天")));
        KeywordDocumentRetriever keyword = mock(KeywordDocumentRetriever.class);
        when(keyword.retrieve(any(Query.class))).thenThrow(new RuntimeException("ES 超时"));
        RerankDocumentPostProcessor processor = mock(RerankDocumentPostProcessor.class);
        when(processor.process(any(Query.class), any())).thenReturn(List.of(doc("v-年假每年5天")));
        ChatModel chatModel = streamingChatModel("答", "案");

        List<String> tokens = new CopyOnWriteArrayList<>();
        NodeContext context = new NodeContext();
        context.put(RewriteNode.QUESTION_KEY, "年假有多少天?");
        context.put(RewriteNode.SESSION_ID_KEY, "s-2");
        context.put(RewriteNode.TOKEN_CONSUMER_KEY, (Consumer<String>) tokens::add);

        PipelineResult result = buildPipeline(chatModel, vector, keyword, transformer(), processor)
                .run(context);

        assertTrue(result.success(), "keyword 分支降级不应中断流水线");
        assertEquals("答案", result.output());

        NodeTrace keywordTrace = result.traces().stream()
                .filter(t -> "keyword".equals(t.node())).findFirst().orElseThrow();
        assertEquals(NodeStatus.SKIPPED, keywordTrace.status());
        assertEquals("ES 超时", keywordTrace.error());
    }
}
