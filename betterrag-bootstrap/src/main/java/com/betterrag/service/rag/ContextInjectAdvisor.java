package com.betterrag.service.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;

/**
 * 检索上下文注入 Advisor(S3,spec 01 §4.4 关键设计)。
 * <p>
 * 顺序刻意排在 memory advisor 之后:memory 先把<b>原始问题</b>写入会话历史,
 * 本 advisor 再把检索文档拼进发给 LLM 的用户消息——保证历史不被增强文本污染。
 * 这是 pipeline 模式与 advisor 模式记忆语义严格一致的前提。
 */
public class ContextInjectAdvisor implements BaseAdvisor {

    /** advisor 参数 key:检索文档列表(GenerateNode 传入) */
    public static final String DOCUMENTS_PARAM = "rag.documents";

    private final QueryAugmenter queryAugmenter;
    private final int order;

    public ContextInjectAdvisor(QueryAugmenter queryAugmenter) {
        this(queryAugmenter, Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 100);
    }

    public ContextInjectAdvisor(QueryAugmenter queryAugmenter, int order) {
        this.queryAugmenter = queryAugmenter;
        this.order = order;
    }

    @Override
    public String getName() {
        return "ContextInjectAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Object documents = request.context().get(DOCUMENTS_PARAM);
        if (!(documents instanceof List<?> list) || list.isEmpty()) {
            return request; // 无检索结果直通,与 allowEmptyContext 语义一致
        }
        @SuppressWarnings("unchecked")
        List<Document> docs = (List<Document>) list;

        Prompt prompt = request.prompt();
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        int userIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                userIndex = i;
                break;
            }
        }
        if (userIndex < 0) {
            return request;
        }

        String userText = messages.get(userIndex).getText();
        Query augmented = queryAugmenter.augment(new Query(userText), docs);
        if (augmented == null || augmented.text() == null || augmented.text().equals(userText)) {
            return request;
        }
        messages.set(userIndex, new UserMessage(augmented.text()));
        return request.mutate()
                .prompt(new Prompt(messages, prompt.getOptions()))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }
}
