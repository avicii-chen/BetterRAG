package com.betterrag.service.rag;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S3 关键测试(spec 01 §4.4):
 * ① 检索文档经 ContextInjectAdvisor 注入发给 LLM 的用户消息;
 * ② memory advisor 先执行——会话历史存的是<b>原始问题</b>,不被增强文本污染。
 */
class ContextInjectAdvisorTest {

    private ChatModel chatModelAnswering(String text) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
        return chatModel;
    }

    private ChatMemory memory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(10)
                .build();
    }

    private ContextInjectAdvisor injectAdvisor() {
        ContextualQueryAugmenter augmenter = ContextualQueryAugmenter.builder()
                .promptTemplate(new PromptTemplate("[片段]\n{context}\n[问题]\n{query}"))
                .allowEmptyContext(true)
                .build();
        return new ContextInjectAdvisor(augmenter);
    }

    @Test
    void 文档注入消息且记忆只存原始问题() {
        ChatModel chatModel = chatModelAnswering("每年 5 天。");
        ChatMemory chatMemory = memory();

        ChatClient client = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        injectAdvisor())
                .build();

        String answer = client.prompt()
                .user("年假有多少天?")
                .advisors(spec -> {
                    spec.param(ChatMemory.CONVERSATION_ID, "conv-1");
                    spec.param(ContextInjectAdvisor.DOCUMENTS_PARAM,
                            List.of(Document.builder().text("年假每年 5 天。").build()));
                })
                .call()
                .chatClientResponse()
                .chatResponse()
                .getResult()
                .getOutput()
                .getText();

        assertEquals("每年 5 天。", answer);

        // 1) 模型收到的用户消息包含注入的片段
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        String modelUserText = promptCaptor.getValue().getInstructions().stream()
                .filter(message -> message instanceof UserMessage)
                .map(Message::getText)
                .findFirst()
                .orElse("");
        assertTrue(modelUserText.contains("年假每年 5 天。"), "LLM 消息应含检索片段:" + modelUserText);
        assertTrue(modelUserText.contains("年假有多少天?"));

        // 2) 记忆存的是原始问题(spec 01 关键设计,不得被增强文本污染)
        List<Message> stored = chatMemory.get("conv-1");
        String storedUserText = stored.stream()
                .filter(message -> message instanceof UserMessage)
                .map(Message::getText)
                .findFirst()
                .orElse("");
        assertEquals("年假有多少天?", storedUserText, "记忆必须存原始问题");
    }

    @Test
    void 无文档参数时消息直通() {
        ContextInjectAdvisor advisor = injectAdvisor();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("你好"))))
                .context(new java.util.HashMap<>())
                .build();

        ChatClientRequest processed = advisor.before(request, null);

        assertSame(request, processed, "无文档参数时应原样返回请求");
        assertEquals("你好", processed.prompt().getInstructions().get(0).getText());
    }

    @Test
    void 空文档列表时链路直通且消息不被改写() {
        ChatModel chatModel = chatModelAnswering("好的");
        ChatClient client = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(memory()).build(),
                        injectAdvisor())
                .build();

        var callSpec = client.prompt()
                .user("你好")
                .advisors(spec -> {
                    spec.param(ChatMemory.CONVERSATION_ID, "conv-2");
                    spec.param(ContextInjectAdvisor.DOCUMENTS_PARAM, List.of());
                });
        var response = callSpec.call();
        org.junit.jupiter.api.Assertions.assertNotNull(response.chatClientResponse(),
                "调用应正常返回(空文档场景)");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        String modelUserText = promptCaptor.getValue().getInstructions().stream()
                .filter(message -> message instanceof UserMessage)
                .map(Message::getText)
                .findFirst()
                .orElse("");
        assertEquals("你好", modelUserText, "空文档列表时用户消息应保持原样");
    }
}
