package com.betterrag.service.rag.node;

import java.util.List;
import java.util.function.Consumer;

import com.betterrag.config.RAGProperties;
import com.betterrag.service.pipeline.NodeContext;
import com.betterrag.service.pipeline.RagNode;
import com.betterrag.service.rag.ChatResponseUtils;
import com.betterrag.service.rag.ContextInjectAdvisor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.util.StringUtils;

/**
 * 生成节点:以<b>原始问题</b>调用 LLM——memory 先存原始问题,
 * 检索文档经 ContextInjectAdvisor(memory 之后)注入,历史不被增强文本污染。
 * 流式消费并聚合答案;sources 直接取检索结果,不再从 advisor context 解析。
 */
public class GenerateNode implements RagNode {

    public static final String SOURCES_KEY = "sources";

    private final ChatClient chatClient;
    private final RAGProperties properties;

    public GenerateNode(ChatClient chatClient, RAGProperties properties) {
        this.chatClient = chatClient;
        this.properties = properties;
    }

    @Override
    public String id() {
        return "generate";
    }

    @Override
    public Object execute(NodeContext context) {
        String question = context.get(RewriteNode.QUESTION_KEY);
        String sessionId = context.get(RewriteNode.SESSION_ID_KEY);
        Consumer<String> tokenConsumer = context.get(RewriteNode.TOKEN_CONSUMER_KEY);
        Object last = context.getLastOutput();
        @SuppressWarnings("unchecked")
        List<Document> docs = last instanceof List<?> list ? (List<Document>) list : List.of();
        context.put(SOURCES_KEY, docs);

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt().user(question);
        spec.advisors(advisor -> {
            advisor.param(ChatMemory.CONVERSATION_ID, sessionId);
            if (!docs.isEmpty()) {
                advisor.param(ContextInjectAdvisor.DOCUMENTS_PARAM, docs);
            }
        });
        if (StringUtils.hasText(properties.getAnswerModel())) {
            spec.options(ChatOptions.builder().model(properties.getAnswerModel()).build());
        }

        StringBuilder answer = new StringBuilder();
        spec.stream().chatClientResponse().toStream().forEach(chunk -> {
            String token = ChatResponseUtils.extractText(chunk);
            if (StringUtils.hasText(token)) {
                answer.append(token);
                if (tokenConsumer != null) {
                    tokenConsumer.accept(token);
                }
            }
        });
        return answer.toString();
    }
}
