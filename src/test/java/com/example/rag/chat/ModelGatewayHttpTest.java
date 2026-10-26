package com.example.rag.chat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.rag.chat.chart.tool.ChartPlanToolCallback;
import com.example.rag.chat.client.AssistantClientProvider;
import com.example.rag.chat.guard.BusinessDataTurnGuard;
import com.example.rag.chat.lifecycle.AssistantLifecycleService;
import com.example.rag.chat.rag.RagAnswerService;
import com.example.rag.config.GatewayModelConfig;
import com.example.rag.config.ModelProperties;
import com.example.rag.conversation.JdbcChatMemoryRepository;
import com.example.rag.tool.registry.ToolRegistryService;
import com.example.rag.tool.registry.ToolSnapshot;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.SseException;
import com.openai.errors.UnauthorizedException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 真实 Spring AI adapter + JDK loopback HTTP 桩；不依赖 Python、数据库或第三方模型。 */
class ModelGatewayHttpTest {

    private static final Duration WAIT = Duration.ofSeconds(8);
    private final JsonMapper json = JsonMapper.builder().build();
    private final LinkedBlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService executor;
    private GenericApplicationContext context;
    private ChatModel defaultModel;
    private AssistantClientProvider clients;
    private ToolRegistryService tools;

    @BeforeEach
    void startLoopbackServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.executor = Executors.newCachedThreadPool();
        this.server.setExecutor(this.executor);
        this.server.createContext("/", this::handle);
        this.server.start();
        this.defaultModel = mock(ChatModel.class);
        when(this.defaultModel.getOptions()).thenReturn(DefaultToolCallingChatOptions.builder().build());
        this.tools = mock(ToolRegistryService.class);
        when(this.tools.currentSnapshot()).thenReturn(new ToolSnapshot(1L, List.of(), List.of()));
        this.context = new GenericApplicationContext();
        this.context.registerBean("gatewayChatModel", GatewayChatModel.class, () -> gateway(Duration.ofSeconds(3)));
        this.context.registerBean("deepSeekChatModel", ChatModel.class, () -> this.defaultModel);
        this.context.refresh();
        ModelProperties models = new ModelProperties();
        models.setModels(List.of(item("deepseek-chat", "deepseek", true), item("gateway-chat", "gateway", false)));
        ModelRegistry registry = new ModelRegistry(models, this.context, ChatClient.builder(this.defaultModel));
        ChartPlanToolCallback chart = mock(ChartPlanToolCallback.class);
        when(chart.getToolDefinition()).thenReturn(ToolDefinition.builder().name("plan_chart_visualization")
                .description("Local unused chart stub").inputSchema("{\"type\":\"object\",\"properties\":{}}").build());
        this.clients = new AssistantClientProvider(ChatClient.builder(this.defaultModel), registry, this.tools, chart);
    }

    @AfterEach
    void stopLoopbackServer() {
        if (this.context != null) this.context.close();
        if (this.server != null) this.server.stop(0);
        if (this.executor != null) this.executor.shutdownNow();
    }

    @Test
    void shouldRouteKnowledgeRequestThroughGatewayWithMessagesAndUsage() {
        enqueueJson(200, answer("来自本地证据 [1]"));
        ChatResponse response = this.clients.resolveKnowledgeClient("gateway-chat").prompt()
                .options(ChatOptions.builder().model(this.clients.resolveModelName("gateway-chat")))
                .user("问题\n[1] 本轮证据").call().chatResponse();
        assertThat(response.getResult().getOutput().getText()).isEqualTo("来自本地证据 [1]");
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(7);
        assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(3);
        assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(10);
        Request request = onlyRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/chat/completions");
        assertThat(request.authorization()).isEqualTo("Bearer local-gateway-test-key");
        assertThat(request.body().path("model").asText()).isEqualTo("gateway-chat");
        assertThat(request.body().path("messages").toString()).contains("知识库问答助手", "本轮证据");
        assertThat(request.body().path("tools").isMissingNode()).isTrue();
        assertThat(request.body().path("stream").asBoolean()).isFalse();
        verifyNoDefaultCalls();
        verifyNoInteractions(this.tools);
    }

    @Test
    void shouldStreamTextAndUsageThroughTheSameAdapter() {
        enqueueSse(chunk("{\"role\":\"assistant\",\"content\":\"本地\"}", "null")
                + chunk("{\"content\":\"回答 [1]\"}", "null")
                + chunk("{}", "\"stop\"") + usageChunk() + "data: [DONE]\n\n");
        List<ChatResponse> responses = this.clients.resolveKnowledgeClient("gateway-chat").prompt()
                .options(ChatOptions.builder().model("gateway-chat")).user("本轮问题")
                .stream().chatResponse().collectList().block(WAIT);
        assertThat(text(responses)).isEqualTo("本地回答 [1]");
        assertThat(responses).anySatisfy(response ->
                assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(10));
        assertThat(onlyRequest().body().path("stream").asBoolean()).isTrue();
        assertThat(onlyRequest().body().path("stream_options").path("include_usage").asBoolean()).isTrue();
        verifyNoDefaultCalls();
    }

    @Test
    void shouldDeliverSseDeltaBeforeTheHttpResponseEnds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstDelta = new CountDownLatch(1);
        this.replies.add(new Reply(200, "text/event-stream",
                chunk("{\"role\":\"assistant\",\"content\":\"first\"}", "null")
                        + chunk("{}", "\"stop\"") + "data: [DONE]\n\n", 0, release));
        try {
            var result = this.clients.resolveKnowledgeClient("gateway-chat").prompt().user("incremental probe")
                    .stream().chatResponse().doOnNext(response -> firstDelta.countDown()).collectList().toFuture();
            assertThat(firstDelta.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(result).isNotDone();
            release.countDown();
            assertThat(text(result.get(3, TimeUnit.SECONDS))).isEqualTo("first");
        }
        finally {
            release.countDown();
        }
    }

    @Test
    void shouldExecuteJavaToolAndSendToolResultBackOverHttp() {
        AtomicInteger calls = new AtomicInteger();
        enqueueJson(200, toolAnswer());
        enqueueJson(200, answer("本地工具结果为 42"));
        ChatResponse response = this.clients.resolveNoToolsClient("gateway-chat").prompt()
                .options(ChatOptions.builder().model("gateway-chat"))
                .toolCallbacks(localTool(calls)).user("执行本地测试工具").call().chatResponse();
        assertThat(response.getResult().getOutput().getText()).isEqualTo("本地工具结果为 42");
        assertToolRoundTrip(calls);
    }

    @Test
    void shouldMergeStreamedToolArgumentsAndContinueWithJavaToolResult() {
        AtomicInteger calls = new AtomicInteger();
        enqueueSse(chunk("{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_local\",\"type\":\"function\",\"function\":{\"name\":\"local_probe\",\"arguments\":\"{\"}}]}", "null")
                + chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"}\"}}]}", "null")
                + chunk("{}", "\"tool_calls\"") + usageChunk() + "data: [DONE]\n\n");
        enqueueSse(chunk("{\"role\":\"assistant\",\"content\":\"工具结果 42\"}", "null")
                + chunk("{}", "\"stop\"") + usageChunk() + "data: [DONE]\n\n");
        List<ChatResponse> responses = this.clients.resolveNoToolsClient("gateway-chat").prompt()
                .options(ChatOptions.builder().model("gateway-chat"))
                .toolCallbacks(localTool(calls)).user("执行本地测试工具")
                .stream().chatResponse().collectList().block(WAIT);
        assertThat(text(responses)).isEqualTo("工具结果 42");
        assertToolRoundTrip(calls);
    }

    @Test
    void shouldKeepAutoDataClientOnGatewayInsteadOfTheDefaultBuilder() {
        enqueueJson(200, answer("<!--FINAL_ANSWER-->本地回答"));
        this.clients.resolveClient("gateway-chat").prompt()
                .options(ChatOptions.builder().model("gateway-chat")).user("普通问题").call().chatResponse();
        assertThat(onlyRequest().body().path("model").asText()).isEqualTo("gateway-chat");
        verifyNoDefaultCalls();
    }

    @Test
    void shouldClassifyHttpErrorsWithoutRetrying() {
        int[] statuses = {400, 401, 429, 500, 504};
        List<Class<? extends Throwable>> types = List.of(BadRequestException.class, UnauthorizedException.class,
                RateLimitException.class, InternalServerException.class, InternalServerException.class);
        for (int index = 0; index < statuses.length; index++) {
            enqueueJson(statuses[index], error());
            Class<? extends Throwable> type = types.get(index);
            assertThatThrownBy(() -> this.clients.resolveKnowledgeClient("gateway-chat").prompt()
                    .user("error probe").call().chatResponse()).isInstanceOf(type);
        }
        assertThat(this.requests).hasSize(statuses.length);
        verifyNoDefaultCalls();
    }

    @Test
    void shouldPropagateHttpErrorsFromStreamingRequests() {
        enqueueJson(429, error());
        assertThatThrownBy(() -> this.clients.resolveKnowledgeClient("gateway-chat").prompt()
                .user("stream error probe").stream().chatResponse().collectList().block(WAIT))
                .hasRootCauseInstanceOf(RateLimitException.class);
        assertThat(this.requests).hasSize(1);
    }

    @Test
    void shouldPropagateErrorAfterPartialSseInsteadOfCompletingNormally() {
        enqueueSse(chunk("{\"role\":\"assistant\",\"content\":\"部分回答\"}", "null")
                + "data: " + error() + "\n\n");
        List<ChatResponse> observed = new ArrayList<>();
        assertThatThrownBy(() -> this.clients.resolveKnowledgeClient("gateway-chat").prompt()
                .user("stream failure probe").stream().chatResponse().doOnNext(observed::add)
                .collectList().block(WAIT)).isInstanceOf(SseException.class);
        assertThat(text(observed)).isEqualTo("部分回答");
        assertThat(this.requests).hasSize(1);
    }

    @Test
    void shouldRejectMalformedJsonRatherThanReturningAnAnswer() {
        enqueueJson(200, "{invalid-json");
        assertThatThrownBy(() -> this.clients.resolveKnowledgeClient("gateway-chat").prompt()
                .user("invalid response probe").call().chatResponse()).isInstanceOf(OpenAIInvalidDataException.class);
        assertThat(this.requests).hasSize(1);
    }

    @Test
    void shouldApplyConfiguredTimeoutWithoutRetryOrFallback() {
        this.replies.add(new Reply(200, "application/json", answer("too late"), 2000));
        GatewayChatModel model = gateway(Duration.ofMillis(300));
        assertThatThrownBy(() -> ChatClient.builder(model).build().prompt().user("timeout probe").call().chatResponse())
                .isInstanceOf(OpenAIIoException.class).hasCauseInstanceOf(java.io.InterruptedIOException.class);
        assertThat(this.requests).hasSize(1);
        verifyNoDefaultCalls();
    }

    @Test
    void shouldApplyTimeoutToStreamingRequests() {
        this.replies.add(new Reply(200, "text/event-stream", chunk("{}", "\"stop\""), 2000));
        assertThatThrownBy(() -> ChatClient.builder(gateway(Duration.ofMillis(300))).build().prompt()
                .user("stream timeout probe").stream().chatResponse().collectList().block(WAIT))
                .isInstanceOf(CompletionException.class).cause().isInstanceOf(OpenAIIoException.class)
                .hasCauseInstanceOf(java.io.InterruptedIOException.class);
        assertThat(this.requests).hasSize(1);
    }

    @Test
    void shouldRejectMalformedSse() {
        enqueueSse("data: {invalid-json\n\n");
        assertThatThrownBy(() -> this.clients.resolveKnowledgeClient("gateway-chat").prompt()
                .user("malformed SSE probe").stream().chatResponse().collectList().block(WAIT))
                .isInstanceOf(OpenAIInvalidDataException.class);
        assertThat(this.requests).hasSize(1);
    }

    @Test
    void shouldRejectUnknownIdsBeforeRagConversationOrModelAcrossAllModes() {
        AssistantLifecycleService lifecycle = mock(AssistantLifecycleService.class);
        RagAnswerService rag = mock(RagAnswerService.class);
        BusinessDataTurnGuard guard = mock(BusinessDataTurnGuard.class);
        ErpAssistantService service = new ErpAssistantService(this.clients, lifecycle,
                mock(JdbcChatMemoryRepository.class), this.tools, guard, rag);
        assertThatThrownBy(() -> service.ask("q", "c", "gateway-chta", false, "kb"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.askStream("q", "c", "gateway-chta", false, "kb"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.askData("q", "c", "gateway-chta", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.askDataStream("q", "c", "gateway-chta", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.askKnowledge("q", "c", "gateway-chta", false, "kb"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.askKnowledgeStream("q", "c", "gateway-chta", false, "kb"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(lifecycle, rag, guard, this.tools);
        verifyNoDefaultCalls();
        assertThat(this.requests).isEmpty();
    }

    @Test
    void shouldExposeTheAdaptersUnfinishedEofLimitation() {
        // 记录原 adapter 边界：正常 HTTP EOF 不保证有 finish_reason / [DONE]。
        // 下一阶段 Gateway 必须检测上游未完成就 EOF，并主动发送 error；此处不改 SSE 主链。
        enqueueSse(chunk("{\"role\":\"assistant\",\"content\":\"未完成\"}", "null"));
        List<ChatResponse> responses = this.clients.resolveKnowledgeClient("gateway-chat").prompt()
                .user("EOF probe").stream().chatResponse().collectList().block(WAIT);
        assertThat(text(responses)).isEqualTo("未完成");
        assertThat(responses).allSatisfy(response -> {
            if (response.getResult() != null) {
                assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("_UNKNOWN");
            }
        });
    }

    private GatewayChatModel gateway(Duration timeout) {
        return new GatewayModelConfig().gatewayChatModel(new GatewayModelConfig.Properties(
                "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1",
                "local-gateway-test-key", "gateway-chat", timeout, 0), ToolCallingManager.builder().build());
    }

    private ToolCallback localTool(AtomicInteger calls) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("local_probe").description("Local protocol probe")
                        .inputSchema("{\"type\":\"object\",\"properties\":{},\"required\":[]}").build();
            }

            @Override
            public String call(String input) {
                assertThat(json.readTree(input).isObject()).isTrue();
                calls.incrementAndGet();
                return "42";
            }
        };
    }

    private void assertToolRoundTrip(AtomicInteger calls) {
        assertThat(calls).hasValue(1);
        assertThat(this.requests).hasSize(2);
        assertThat(this.requests.get(0).body().path("tools").toString()).contains("local_probe");
        JsonNode messages = this.requests.get(1).body().path("messages");
        List<JsonNode> toolMessages = new ArrayList<>();
        for (JsonNode message : messages) {
            if ("tool".equals(message.path("role").asText())) toolMessages.add(message);
        }
        assertThat(toolMessages).hasSize(1);
        assertThat(toolMessages.get(0).path("tool_call_id").asText()).isEqualTo("call_local");
        assertThat(toolMessages.get(0).path("content").asText()).isEqualTo("42");
        assertThat(messages.toString()).contains("tool_calls", "local_probe");
        verifyNoDefaultCalls();
    }

    private void verifyNoDefaultCalls() {
        verify(this.defaultModel, never()).call(any(Prompt.class));
        verify(this.defaultModel, never()).stream(any(Prompt.class));
    }

    private ModelProperties.ModelItem item(String id, String provider, boolean isDefault) {
        ModelProperties.ModelItem item = new ModelProperties.ModelItem();
        item.setId(id);
        item.setProvider(provider);
        item.setModelName(id);
        item.setDefault(isDefault);
        return item;
    }

    private String text(List<ChatResponse> responses) {
        return responses.stream().filter(response -> response.getResult() != null)
                .map(response -> response.getResult().getOutput().getText())
                .filter(value -> value != null).collect(java.util.stream.Collectors.joining());
    }

    private Request onlyRequest() {
        assertThat(this.requests).hasSize(1);
        return this.requests.get(0);
    }

    private void enqueueJson(int status, String body) {
        this.replies.add(new Reply(status, "application/json", body, 0));
    }

    private void enqueueSse(String body) {
        this.replies.add(new Reply(200, "text/event-stream", body, 0));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            this.requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    this.json.readTree(exchange.getRequestBody().readAllBytes())));
            Reply reply = this.replies.poll(1, TimeUnit.SECONDS);
            if (reply == null) throw new IOException("Unexpected extra HTTP request");
            if (reply.delayMs() > 0) Thread.sleep(reply.delayMs());
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", reply.contentType());
            exchange.sendResponseHeaders(reply.status(), 0);
            if (reply.continueAfterFirstFrame() == null) {
                exchange.getResponseBody().write(bytes);
            }
            else {
                int split = reply.body().indexOf("\n\n") + 2;
                byte[] first = reply.body().substring(0, split).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseBody().write(first);
                exchange.getResponseBody().flush();
                if (!reply.continueAfterFirstFrame().await(4, TimeUnit.SECONDS)) {
                    throw new IOException("Incremental SSE probe did not release server");
                }
                exchange.getResponseBody().write(reply.body().substring(split).getBytes(StandardCharsets.UTF_8));
            }
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String answer(String content) {
        return "{\"id\":\"chatcmpl-local\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gateway-chat\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
                + this.json.writeValueAsString(content) + "},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}";
    }

    private String toolAnswer() {
        return "{\"id\":\"chatcmpl-local\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gateway-chat\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                + "{\"id\":\"call_local\",\"type\":\"function\",\"function\":{\"name\":\"local_probe\",\"arguments\":\"{}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}";
    }

    private String chunk(String delta, String finishReason) {
        return "data: {\"id\":\"chatcmpl-local\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gateway-chat\","
                + "\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":" + finishReason + "}]}\n\n";
    }

    private String usageChunk() {
        return "data: {\"id\":\"chatcmpl-local\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gateway-chat\","
                + "\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}\n\n";
    }

    private String error() {
        return "{\"error\":{\"message\":\"local simulated failure\",\"type\":\"upstream_error\",\"code\":\"local_failure\",\"param\":null}}";
    }

    private record Request(String method, String path, String authorization, JsonNode body) { }
    private record Reply(int status, String contentType, String body, long delayMs, CountDownLatch continueAfterFirstFrame) {
        Reply(int status, String contentType, String body, long delayMs) {
            this(status, contentType, body, delayMs, null);
        }
    }
}
