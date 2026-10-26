package com.example.rag.chat;

import java.time.Duration;
import java.util.List;

import com.example.rag.config.ChatModelConfig;
import com.example.rag.config.GatewayModelConfig;
import com.example.rag.config.ModelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 加载真实 YAML 和模型自动配置，不启动完整应用、不连接数据库或远程模型。 */
class GatewayModelConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(OpenAiChatAutoConfiguration.class, ChatClientAutoConfiguration.class))
            .withUserConfiguration(StandIns.class, ChatModelConfig.class, GatewayModelConfig.class, ModelRegistry.class)
            .withPropertyValues("spring.ai.openai.base-url=http://127.0.0.1:9/v1",
                    "spring.ai.openai.api-key=local-original-openai-test-key");

    @Test
    void shouldLeaveDefaultProfileAndBuilderOnDeepSeek() {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(GatewayChatModel.class);
            ModelProperties models = context.getBean(ModelProperties.class);
            assertThat(models.getModels()).hasSize(5);
            assertThat(models.getDefault().getId()).isEqualTo("deepseek-chat");
            ModelRegistry registry = context.getBean(ModelRegistry.class);
            assertThatThrownBy(() -> registry.getModelItem("gateway-chat")).isInstanceOf(IllegalArgumentException.class);
            assertThat(registry.getChatModel("qwen-max")).isSameAs(context.getBean("openAiChatModel"));
            assertThat(registry.getChatModel("gemini-2.0-flash")).isSameAs(context.getBean("googleGenAiChatModel"));
            assertDefaultBuilder(context.getBean(ChatClient.Builder.class), context.getBean("deepSeekChatModel", ChatModel.class));
        });
    }

    @Test
    void shouldAddGatewayWithoutSuppressingOriginalOpenAiOrChangingDefault() {
        this.runner.withPropertyValues("spring.profiles.active=gateway").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(GatewayChatModel.class).hasSingleBean(OpenAiChatModel.class);
            ModelProperties models = context.getBean(ModelProperties.class);
            assertThat(models.getModels()).extracting(ModelProperties.ModelItem::getId)
                    .containsExactly("deepseek-chat", "deepseek-reasoner", "qwen-max", "qwen-turbo", "gemini-2.0-flash", "gateway-chat");
            assertThat(models.getDefault().getId()).isEqualTo("deepseek-chat");
            ModelRegistry registry = context.getBean(ModelRegistry.class);
            assertThat(registry.getChatModel("gateway-chat")).isSameAs(context.getBean("gatewayChatModel"));
            assertThat(registry.getChatModel("qwen-max")).isSameAs(context.getBean("openAiChatModel"));
            assertThat(registry.getChatModel("qwen-turbo")).isSameAs(context.getBean("openAiChatModel"));
            assertThat(registry.getChatModel("gemini-2.0-flash")).isSameAs(context.getBean("googleGenAiChatModel"));
            GatewayChatModel gateway = context.getBean(GatewayChatModel.class);
            assertThat(gateway.getOptions().getBaseUrl()).isEqualTo("http://127.0.0.1:8001/v1");
            assertThat(gateway.getOptions().getTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(gateway.getOptions().getMaxRetries()).isZero();
            assertThat(gateway.getOptions().getStreamOptions().includeUsage()).isTrue();
            // 原 adapter 的 SDK 连接由 common properties 单独构造，不写入 chat options。
            assertThat(context.getBean(OpenAiCommonProperties.class).getBaseUrl()).isEqualTo("http://127.0.0.1:9/v1");
            assertThat(context.getBean(ChatModel.class)).isSameAs(context.getBean("deepSeekChatModel"));
            assertDefaultBuilder(context.getBean(ChatClient.Builder.class), context.getBean("deepSeekChatModel", ChatModel.class));
            verifyNoInteractions(context.getBean("googleGenAiChatModel"));
        });
    }

    @Test
    void shouldRejectInvalidGatewayConnectionBeforeAnyRequest() {
        assertThatThrownBy(() -> properties("http://127.0.0.1:8001", Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("http://127.0.0.1:8001/v1", Duration.ZERO, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("http://127.0.0.1:8001/v1", Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private GatewayModelConfig.Properties properties(String url, Duration timeout, int retries) {
        return new GatewayModelConfig.Properties(url, "local-test-key", "gateway-chat", timeout, retries);
    }

    private void assertDefaultBuilder(ChatClient.Builder builder, ChatModel deepSeek) {
        assertThat(builder.build().prompt().user("local default routing check").call().content()).isEqualTo("default-stub");
        verify(deepSeek).call(any(Prompt.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ModelProperties.class)
    static class StandIns {
        @Bean
        ToolCallingManager toolCallingManager() {
            return ToolCallingManager.builder().build();
        }

        @Bean
        ChatModel deepSeekChatModel() {
            ChatModel model = mock(ChatModel.class);
            when(model.getOptions()).thenReturn(DefaultToolCallingChatOptions.builder().build());
            when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("default-stub")))));
            return model;
        }

        @Bean
        ChatModel googleGenAiChatModel() {
            return mock(ChatModel.class);
        }
    }
}
