package com.example.rag.chat;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;

/**
 * 独立 Gateway 模型身份；协议转换全部委托 Spring AI OpenAI adapter。
 * 不将委托对象注册为 OpenAiChatModel Bean，避免抑制原 OpenAI 自动配置。
 */
public final class GatewayChatModel implements ChatModel {

    private final OpenAiChatModel delegate;

    public GatewayChatModel(OpenAiChatModel delegate) {
        Assert.notNull(delegate, "Gateway adapter 不能为空");
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return this.delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return this.delegate.stream(prompt);
    }

    @Override
    public OpenAiChatOptions getOptions() {
        return this.delegate.getOptions();
    }

}
