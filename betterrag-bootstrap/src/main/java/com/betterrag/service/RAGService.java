package com.betterrag.service;

import cn.hutool.core.collection.CollUtil;
import com.betterrag.config.RAGProperties;
import com.betterrag.model.RAGRequest;
import com.betterrag.service.rag.ChatResponseUtils;
import com.betterrag.service.pipeline.DagPipeline;
import com.betterrag.service.pipeline.NodeContext;
import com.betterrag.service.pipeline.NodeTrace;
import com.betterrag.service.pipeline.PipelineResult;
import com.betterrag.service.rag.node.GenerateNode;
import com.betterrag.service.rag.node.RewriteNode;
import com.betterrag.service.trace.TraceContext;
import com.betterrag.service.trace.TraceRecorder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
public class RAGService {

    private final ChatClient chatClient;
    private final ChatClient titleClient;
    private final RAGProperties ragProperties;
    private final Resource titleSystemPrompt;
    private final Resource titleUserPrompt;
    private final TaskExecutor taskExecutor;
    private final SuggestionService suggestionService;
    private final TraceRecorder traceRecorder;
    private final DagPipeline ragPipeline;

    public RAGService(@Qualifier("chatClient") ChatClient chatClient,
                      ChatModel chatModel,
                      RAGProperties ragProperties,
                      @Value("classpath:/prompts/title-system.st") Resource titleSystemPrompt,
                      @Value("classpath:/prompts/title-user.st") Resource titleUserPrompt,
                      @Qualifier("ragTaskExecutor") TaskExecutor taskExecutor,
                      SuggestionService suggestionService,
                      TraceRecorder traceRecorder,
                      DagPipeline ragPipeline) {
        this.chatClient = chatClient;
        this.titleClient = ChatClient.builder(chatModel).build();
        this.ragProperties = ragProperties;
        this.titleSystemPrompt = titleSystemPrompt;
        this.titleUserPrompt = titleUserPrompt;
        this.taskExecutor = taskExecutor;
        this.suggestionService = suggestionService;
        this.traceRecorder = traceRecorder;
        this.ragPipeline = ragPipeline;
    }

    public SseEmitter streamChat(RAGRequest request) {
        SseEmitter emitter = new SseEmitter(180000L);

        boolean newSession = !StringUtils.hasText(request.getSessionId());
        String sessionId = newSession
                ? UUID.randomUUID().toString()
                : request.getSessionId();
        log.info("[RAG] sessionId={}, newSession={}, rawSessionId=[{}]", sessionId, newSession, request.getSessionId());

        taskExecutor.execute(() -> {
            try {
                sendEvent(emitter, "meta", Map.of("sessionId", sessionId));

                // 新会话：异步生成标题，生成完单独推送
                if (newSession) {
                    CompletableFuture.supplyAsync(() -> generateTitle(request.getQuestion()), taskExecutor)
                            .thenAccept(title -> {
                                log.info("[RAG] 会话标题: {}", title);
                                sendEvent(emitter, "title", Map.of("sessionTitle", title));
                            });
                }

                CompletableFuture<List<String>> suggestionsFuture = CompletableFuture
                        .supplyAsync(() -> suggestionService.generate(request.getQuestion(), request.getKb()), taskExecutor);

                List<Document> sources = streamAnswer(request.getQuestion(), request.getKb(), sessionId,
                        token -> sendEvent(emitter, "token", token));

                pushSources(emitter, sources);
                pushSuggestions(emitter, suggestionsFuture);
                sendEvent(emitter, "done", "[DONE]");
                emitter.complete();
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof IOException) {
                    return;
                }
                try {
                    sendEvent(emitter, "error", ex.getMessage() == null ? "stream error" : ex.getMessage());
                } catch (Exception ignored) {
                }
                emitter.completeWithError(ex);
            }
        });

        return emitter;
    }

    private void pushSources(SseEmitter emitter, List<Document> sources) {
        if (!sources.isEmpty()) {
            LinkedHashMap<String, Map<String, Object>> grouped = new LinkedHashMap<>();
            for (Document doc : sources) {
                String fileName = String.valueOf(doc.getMetadata().getOrDefault("source", "未知来源"));
                grouped.computeIfAbsent(fileName, k -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("source", fileName);
                    item.put("kb", String.valueOf(doc.getMetadata().getOrDefault("kb", "")));
                    item.put("chunkCount", 0);
                    return item;
                });
                grouped.get(fileName).merge("chunkCount", 1, (a, b) -> (int) a + (int) b);
            }
            sendEvent(emitter, "sources", Map.of("documents", List.copyOf(grouped.values())));
        }
    }

    public List<Document> streamAnswer(String question, String kb, String sessionId,
                                       Consumer<String> tokenConsumer) {
        if ("pipeline".equalsIgnoreCase(ragProperties.getOrchestration())) {
            return streamAnswerViaPipeline(question, kb, sessionId, tokenConsumer);
        }
        return streamAnswerViaAdvisor(question, kb, sessionId, tokenConsumer);
    }

    /**
     * S3:pipeline 模式——DAG 先执行(组件内 ThreadLocal 无 trace,直通零重复),
     * 而后创建 trace 回放全部 NodeTrace(含 vector/keyword 分支粒度)再落库。
     */
    private List<Document> streamAnswerViaPipeline(String question, String kb, String sessionId,
                                                   Consumer<String> tokenConsumer) {
        long startedAt = System.currentTimeMillis();
        NodeContext nodeContext = new NodeContext();
        nodeContext.put(RewriteNode.QUESTION_KEY, question);
        nodeContext.put(RewriteNode.SESSION_ID_KEY, sessionId);
        nodeContext.put(RewriteNode.TOKEN_CONSUMER_KEY, tokenConsumer);
        if (StringUtils.hasText(kb)) {
            nodeContext.put(RewriteNode.KB_KEY, kb);
        }

        TraceContext traceCtx = traceRecorder.start(sessionId, question, startedAt);
        try {
            PipelineResult result = ragPipeline.run(nodeContext);
            for (NodeTrace nodeTrace : result.traces()) {
                traceCtx.addSpan(nodeTrace.node(), nodeTrace.status().name(),
                        nodeTrace.costMs(), nodeTrace.inputDigest(),
                        nodeTrace.outputDigest(), nodeTrace.error());
            }
            if (!result.success()) {
                throw new IllegalStateException("pipeline 执行失败: " + result.error());
            }
            traceRecorder.finish(traceCtx, String.valueOf(result.output()));

            List<Document> sources = nodeContext.get(GenerateNode.SOURCES_KEY);
            return sources == null ? new ArrayList<>() : sources;
        } catch (RuntimeException e) {
            traceRecorder.finishWithError(traceCtx, e.getMessage());
            if (e.getCause() instanceof IOException) {
                throw e;
            }
            log.error("[RAG][pipeline] 问答过程出错", e);
            tokenConsumer.accept("处理问题时出错：" + e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<Document> streamAnswerViaAdvisor(String question, String kb, String sessionId,
                                                  Consumer<String> tokenConsumer) {
        List<Document> sources = new ArrayList<>();
        StringBuilder answerBuffer = new StringBuilder();
        TraceContext traceCtx = traceRecorder.start(sessionId, question);
        try {
            long startTime = System.currentTimeMillis();
            log.info("[RAG] 原始问题: {}", question);

            ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt()
                    .user(question);

            requestSpec.advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId));
            if (StringUtils.hasText(kb)) {
                requestSpec.advisors(spec -> spec.param(
                        VectorStoreDocumentRetriever.FILTER_EXPRESSION,
                        "kb == '" + escapeForFilter(kb) + "'"));
            }
            if (StringUtils.hasText(ragProperties.getAnswerModel())) {
                requestSpec.options(ChatOptions.builder()
                        .model(ragProperties.getAnswerModel())
                        .build());
            }

            log.info("[RAG] 开始流式调用 LLM...");
            long llmStartTime = System.currentTimeMillis();

            traceRecorder.spanAround(traceCtx, "generate",
                    () -> consumeStream(requestSpec, tokenConsumer, answerBuffer, sources),
                    ignored -> "chars=" + answerBuffer.length());

            log.info("[RAG] LLM 流式调用完成 ({}ms, 总 {}ms)",
                    System.currentTimeMillis() - llmStartTime,
                    System.currentTimeMillis() - startTime);
            traceRecorder.finish(traceCtx, answerBuffer.toString());
        } catch (RuntimeException e) {
            traceRecorder.finishWithError(traceCtx, e.getMessage());
            if (e.getCause() instanceof IOException) {
                throw e;
            }
            log.error("[RAG] 问答过程出错", e);
            tokenConsumer.accept("处理问题时出错：" + e.getMessage());
        }

        return sources;
    }

    private List<Document> consumeStream(ChatClient.ChatClientRequestSpec requestSpec,
                                        Consumer<String> tokenConsumer,
                                        StringBuilder answerBuffer,
                                        List<Document> sources) {
        requestSpec.stream().chatClientResponse().toStream().forEach(chunk -> {
            if (CollUtil.isEmpty(sources)) {
                @SuppressWarnings("unchecked")
                List<Document> docs = (List<Document>) chunk.context()
                        .get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
                if (CollUtil.isNotEmpty(docs)) {
                    sources.addAll(docs);
                }
            }
            String token = ChatResponseUtils.extractText(chunk);
            if (StringUtils.hasText(token)) {
                answerBuffer.append(token);
                tokenConsumer.accept(token);
            }
        });
        return sources;
    }

    private void pushSuggestions(SseEmitter emitter, CompletableFuture<List<String>> suggestionsFuture) {
        List<String> suggestions;
        try {
            suggestions = suggestionsFuture.get(15, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.warn("[RAG] 推荐问题获取失败: {}", ex.getMessage());
            suggestions = List.of();
        }
        if (suggestions.isEmpty()) {
            sendEvent(emitter, "suggestions", Map.of("fallback", "暂无推荐问题"));
        } else {
            log.info("[RAG] 推荐问题: {}", suggestions);
            sendEvent(emitter, "suggestions", Map.of("questions", suggestions));
        }
    }

    private void sendEvent(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException ex) {
            log.info("[SSE] 客户端已断开连接，停止推送");
            throw new RuntimeException(ex);
        }
    }

    private String escapeForFilter(String kb) {
        return kb.replace("'", "\\'");
    }

    private String generateTitle(String question) {
        try {
            ChatClientResponse response = titleClient.prompt()
                    .system(system -> system.text(titleSystemPrompt))
                    .user(user -> user.text(titleUserPrompt).param("question", question))
                    .options(ChatOptions.builder().temperature(0.0).maxTokens(32).build())
                    .call()
                    .chatClientResponse();

            String title = ChatResponseUtils.extractText(response);
            return StringUtils.hasText(title) ? title.trim() : question;
        } catch (Exception ex) {
            log.warn("生成会话标题失败，回退原问题: {}", ex.getMessage());
            return question;
        }
    }
}
