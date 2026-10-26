package com.example.rag.chat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.rag.chat.chart.tool.ChartPlanToolCallback;
import com.example.rag.chat.client.AssistantClientProvider;
import com.example.rag.config.ChatModelConfig;
import com.example.rag.config.GatewayModelConfig;
import com.example.rag.config.ModelProperties;
import com.example.rag.tool.registry.ToolRegistryService;
import com.openai.errors.RateLimitException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real auto-configured OpenAI adapter and application routing, with loopback HTTP only. */
class OpenAiDirectHttpTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final LinkedBlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService executor;

    @BeforeEach
    void start() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.executor = Executors.newCachedThreadPool();
        this.server.setExecutor(this.executor);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    @AfterEach
    void stop() {
        if (this.server != null) this.server.stop(0);
        if (this.executor != null) this.executor.shutdownNow();
    }

    @Test
    void shouldUseExistingOpenAiRouteWithoutGatewayOrChangingDeepSeekDefault() {
        reply(200, "application/json", answer("local evidence [1]"));
        runner("/v1").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(GatewayChatModel.class);
            assertThat(context.getBean(ModelProperties.class).getDefault().getId()).isEqualTo("deepseek-chat");
            AssistantClientProvider clients = context.getBean(AssistantClientProvider.class);
            ChatResponse response = clients.resolveKnowledgeClient("qwen-turbo").prompt()
                    .options(options()).user("public document evidence [1]").call().chatResponse();
            assertThat(response.getResult().getOutput().getText()).isEqualTo("local evidence [1]");
            assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(10);
            assertThat(clients.resolveModelName("qwen-turbo")).isEqualTo("qwen-turbo");
            assertThat(this.requests).hasSize(1);
            assertRequest(this.requests.get(0), "/v1/chat/completions");
            assertThat(this.requests.get(0).body().path("messages").toString())
                    .contains("public document evidence [1]", "知识库问答助手");
            assertThat(this.requests.get(0).body().has("tools")).isFalse();
            verifyNoInteractions(context.getBean("deepSeekChatModel"), context.getBean(ToolRegistryService.class));
        });
    }

    @Test
    void shouldNotSilentlyAppendV1ToConfiguredBaseUrl() {
        reply(200, "application/json", answer("path probe"));
        runner("/compatible-mode").run(context -> {
            context.getBean(AssistantClientProvider.class).resolveKnowledgeClient("qwen-turbo")
                    .prompt().options(options()).user("path probe").call().content();
            assertRequest(this.requests.get(0), "/compatible-mode/chat/completions");
        });
    }

    @Test
    void shouldKeepTheBundledCompatibleEndpointVersioned() {
        // Real application YAML, without overriding the endpoint or sending any request.
        runner(null).run(context -> assertThat(context.getBean(
                org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties.class).getBaseUrl())
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1"));
        assertThat(this.requests).isEmpty();
    }

    @Test
    void shouldStreamTextAndUsageDirectly() {
        reply(200, "text/event-stream", chunk("{\"content\":\"direct answer\"}", "null")
                + chunk("{}", "\"stop\"") + usage() + "data: [DONE]\n\n");
        runner("/v1").run(context -> {
            List<ChatResponse> responses = context.getBean(AssistantClientProvider.class)
                    .resolveKnowledgeClient("qwen-turbo").prompt().options(options()).user("stream probe")
                    .stream().chatResponse().collectList().block(Duration.ofSeconds(8));
            assertThat(text(responses)).isEqualTo("direct answer");
            assertThat(responses).anySatisfy(r -> assertThat(r.getMetadata().getUsage().getTotalTokens()).isEqualTo(10));
            assertRequest(this.requests.get(0), "/v1/chat/completions");
            assertThat(this.requests.get(0).body().path("stream_options").path("include_usage").asBoolean()).isTrue();
        });
    }

    @Test
    void shouldExecuteJavaToolAndSendItsResultInSynchronousMode() {
        reply(200, "application/json", "{\"id\":\"local\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"qwen-turbo\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                + "{\"id\":\"call_local\",\"type\":\"function\",\"function\":{\"name\":\"local_probe\",\"arguments\":\"{}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}");
        reply(200, "application/json", answer("result 42"));
        toolRoundTrip(false);
    }

    @Test
    void shouldExecuteJavaToolAndSendItsResultInStreamingMode() {
        reply(200, "text/event-stream", chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call_local\",\"type\":\"function\","
                + "\"function\":{\"name\":\"local_probe\",\"arguments\":\"{}\"}}]}", "null")
                + chunk("{}", "\"tool_calls\"") + "data: [DONE]\n\n");
        reply(200, "text/event-stream", chunk("{\"content\":\"result 42\"}", "null")
                + chunk("{}", "\"stop\"") + "data: [DONE]\n\n");
        toolRoundTrip(true);
    }

    @Test
    void shouldPropagateRateLimitWithoutRetryingOrFallingBack() {
        reply(429, "application/json", "{\"error\":{\"message\":\"local rate limit\",\"type\":\"rate_limit_error\"}}");
        runner("/v1").run(context -> {
            assertThatThrownBy(() -> context.getBean(AssistantClientProvider.class).resolveKnowledgeClient("qwen-turbo")
                    .prompt().options(options()).user("rate limit probe").call().content()).isInstanceOf(RateLimitException.class);
            assertThat(this.requests).hasSize(1);
            verifyNoInteractions(context.getBean("deepSeekChatModel"));
        });
    }

    private void toolRoundTrip(boolean streaming) {
        AtomicInteger calls = new AtomicInteger();
        ToolCallback callback = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("local_probe").description("Local test only")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            @Override public String call(String input) {
                assertThat(json.readTree(input).isObject()).isTrue();
                calls.incrementAndGet();
                return "42";
            }
        };
        runner("/v1").run(context -> {
            var spec = context.getBean(AssistantClientProvider.class).resolveNoToolsClient("qwen-turbo")
                    .prompt().options(options()).toolCallbacks(callback).user("local tool probe");
            String result = streaming ? text(spec.stream().chatResponse().collectList().block(Duration.ofSeconds(8)))
                    : spec.call().content();
            assertThat(result).isEqualTo("result 42");
            assertThat(calls).hasValue(1);
            assertThat(this.requests).hasSize(2).allSatisfy(r -> assertRequest(r, "/v1/chat/completions"));
            assertThat(this.requests.get(0).body().path("tools").toString()).contains("local_probe");
            List<JsonNode> toolMessages = new java.util.ArrayList<>();
            this.requests.get(1).body().path("messages").forEach(m -> {
                if ("tool".equals(m.path("role").asText())) toolMessages.add(m);
            });
            assertThat(toolMessages).hasSize(1);
            assertThat(toolMessages.get(0).path("tool_call_id").asText()).isEqualTo("call_local");
            assertThat(toolMessages.get(0).path("content").asText()).isEqualTo("42");
            assertThat(this.requests.get(1).body().path("messages").toString()).contains("tool_calls", "local_probe");
            verifyNoInteractions(context.getBean("deepSeekChatModel"), context.getBean(ToolRegistryService.class));
        });
    }

    private ApplicationContextRunner runner(String suffix) {
        ApplicationContextRunner runner = new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(OpenAiChatAutoConfiguration.class, ChatClientAutoConfiguration.class))
                .withUserConfiguration(StandIns.class, ChatModelConfig.class, GatewayModelConfig.class,
                        ModelRegistry.class, AssistantClientProvider.class)
                .withPropertyValues("spring.ai.openai.api-key=local-direct-test-key", "spring.ai.openai.max-retries=0",
                        "spring.ai.openai.timeout=2s");
        return suffix == null ? runner : runner.withPropertyValues(
                "spring.ai.openai.base-url=http://127.0.0.1:" + this.server.getAddress().getPort() + suffix);
    }

    private OpenAiChatOptions.Builder options() {
        return OpenAiChatOptions.builder().model("qwen-turbo").streamUsage(true);
    }

    private void assertRequest(Request request, String path) {
        assertThat(request.path()).isEqualTo(path);
        assertThat(request.body().path("model").asText()).isEqualTo("qwen-turbo");
    }

    private String text(List<ChatResponse> responses) {
        assertThat(responses).isNotNull();
        return responses.stream().filter(r -> r.getResult() != null)
                .map(r -> r.getResult().getOutput().getText()).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.joining());
    }

    private void reply(int status, String type, String body) { this.replies.add(new Reply(status, type, body)); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            this.requests.add(new Request(exchange.getRequestURI().getPath(),
                    this.json.readTree(exchange.getRequestBody().readAllBytes())));
            Reply response = this.replies.poll(2, TimeUnit.SECONDS);
            if (response == null) throw new IOException("Unexpected HTTP request");
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.type());
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private String answer(String text) {
        return "{\"id\":\"local\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"qwen-turbo\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":" + this.json.writeValueAsString(text)
                + "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}";
    }

    private String chunk(String delta, String reason) {
        return "data: {\"id\":\"local\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"qwen-turbo\","
                + "\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":" + reason + "}]}\n\n";
    }

    private String usage() {
        return "data: {\"id\":\"local\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"qwen-turbo\","
                + "\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}\n\n";
    }

    private record Request(String path, JsonNode body) { }
    private record Reply(int status, String type, String body) { }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ModelProperties.class)
    static class StandIns {
        @Bean ToolCallingManager toolCallingManager() { return ToolCallingManager.builder().build(); }
        @Bean ChatModel deepSeekChatModel() {
            ChatModel model = mock(ChatModel.class);
            when(model.getOptions()).thenReturn(DefaultToolCallingChatOptions.builder().build());
            return model;
        }
        @Bean ChatModel googleGenAiChatModel() { return mock(ChatModel.class); }
        @Bean ToolRegistryService toolRegistryService() { return mock(ToolRegistryService.class); }
        @Bean ChartPlanToolCallback chartPlanToolCallback() { return mock(ChartPlanToolCallback.class); }
    }
}
