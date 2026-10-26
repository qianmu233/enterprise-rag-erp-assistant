package com.example.rag.chat;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.rag.config.GatewayModelConfig;
import com.example.rag.config.ModelProperties;
import com.openai.errors.OpenAIServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in only: scripts/test-model-gateway.ps1 starts three real loopback processes. */
public class ModelGatewayIT {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private HttpResponse<String> probe(String scenario, boolean stream) throws Exception {
        return this.http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/probe?scenario=" + scenario + "&stream=" + stream))
                .timeout(Duration.ofSeconds(12)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void shouldTraverseJavaPythonAndFakeWithModelMessagesAndUsage() throws Exception {
        var result = probe("normal", false);
        assertThat(result.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.body());
        String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("probe:normal".getBytes(StandardCharsets.UTF_8)));
        assertThat(body.path("answer").asText()).contains("via-python-gateway|model=local-fake|messages=1|request_id=", "question_sha256=" + digest);
        assertThat(body.path("usage").path("total_tokens").asInt()).isEqualTo(10);
    }

    @Test
    void shouldStreamThroughBothPythonProcesses() throws Exception {
        String body = probe("normal", true).body();
        assertThat(body).contains("event:delta", "via-python-gateway", "event:usage", "total_tokens", "event:done");
        assertThat(body).doesNotContain("event:error");
    }

    @Test
    void shouldExecuteToolInJavaAndReturnResultThroughGateway() throws Exception {
        JsonNode body = JSON.readTree(probe("tool", false).body());
        assertThat(body.path("answer").asText()).contains("via-python-gateway", "tool_result=42");
        assertThat(body.path("tool_calls").asInt()).isEqualTo(1);
    }

    @Test
    void shouldExecuteStreamedToolInJava() throws Exception {
        String body = probe("tool", true).body();
        assertThat(body).contains("tool_result=42", "event:done").doesNotContain("event:error");
    }

    @Test
    void shouldClassify429() throws Exception {
        assertError("429", 429, "RATE_LIMITED");
    }

    @Test
    void shouldClassify500() throws Exception {
        assertError("500", 500, "UPSTREAM_ERROR");
    }

    @Test
    void shouldClassify504() throws Exception {
        assertError("504", 504, "UPSTREAM_TIMEOUT");
    }

    @Test
    void shouldApplyPythonReadTimeout() throws Exception {
        assertError("slow", 504, "UPSTREAM_TIMEOUT");
    }

    @Test
    void shouldApplyTotalDeadlineDespiteUpstreamHeartbeats() throws Exception {
        String body = probe("trickle", true).body();
        assertThat(body).contains("event:error", "UPSTREAM_TIMEOUT").doesNotContain("event:done");
    }

    @Test
    void shouldTurnEarlyEofIntoJavaErrorNotSuccess() throws Exception {
        String body = probe("early-eof", true).body();
        assertThat(body).contains("event:delta", "event:error", "UPSTREAM_EOF").doesNotContain("event:done");
    }

    @Test
    void shouldRejectMissingDoneEvenAfterFinishReason() throws Exception {
        assertThat(probe("missing-done", true).body()).contains("UPSTREAM_EOF", "event:error").doesNotContain("event:done");
    }

    @Test
    void shouldRejectInvalidUpstreamJson() throws Exception {
        assertError("invalid-json", 502, "RESPONSE_INVALID");
    }

    @Test
    void shouldReleaseUpstreamWhenDirectGatewayClientDisconnects() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8001/v1/chat/completions"))
                .header("Authorization", "Bearer local-gateway-test-key").header("Content-Type", "application/json")
                .header("X-Request-ID", "phase2-direct-cancel").timeout(Duration.ofSeconds(8))
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gateway-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"probe:cancel\"}],\"stream\":true}")).build();
        try (var body = this.http.send(request, HttpResponse.BodyHandlers.ofInputStream()).body()) {
            readFirstFrame(body);
            assertThat(activeFakeStreams()).isEqualTo(1);
        }
        awaitFakeCleanup(Duration.ofSeconds(2));
    }

    @Test
    void shouldBoundUpstreamLifetimeAfterJavaClientDisconnects() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/probe?scenario=cancel&stream=true"))
                .timeout(Duration.ofSeconds(8)).POST(HttpRequest.BodyPublishers.noBody()).build();
        try (var body = this.http.send(request, HttpResponse.BodyHandlers.ofInputStream()).body()) {
            readFirstFrame(body);
            assertThat(activeFakeStreams()).isEqualTo(1);
        }
        // This bounds resource retention; it does not assert that the Spring AI adapter propagates cancellation immediately.
        Thread.sleep(200);
        System.out.println("Java client disconnect: fake active streams after 200ms=" + activeFakeStreams());
        awaitFakeCleanup(Duration.ofSeconds(4));
    }

    private void readFirstFrame(java.io.InputStream input) throws Exception {
        int previous = -1;
        for (int count = 0; count < 8192; count++) {
            int current = input.read();
            assertThat(current).isNotEqualTo(-1);
            if (previous == '\n' && current == '\n') return;
            previous = current;
        }
        throw new AssertionError("No complete first SSE frame");
    }

    private int activeFakeStreams() throws Exception {
        var response = this.http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8002/health"))
                .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(response.body()).path("active_streams").asInt();
    }

    private void awaitFakeCleanup(Duration limit) throws Exception {
        long end = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < end) {
            if (activeFakeStreams() == 0) return;
            Thread.sleep(50);
        }
        assertThat(activeFakeStreams()).isZero();
    }

    private void assertError(String scenario, int status, String category) throws Exception {
        var result = probe(scenario, false);
        assertThat(result.statusCode()).isEqualTo(status);
        assertThat(JSON.readTree(result.body()).path("category").asText()).isEqualTo(category);
        assertThat(result.body()).doesNotContain("sensitive upstream details");
    }

    /** Real Spring Boot/Tomcat test process. No component scan or database/AI auto-configuration. */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableConfigurationProperties(ModelProperties.class)
    @Import({GatewayModelConfig.class, ModelRegistry.class, ProbeController.class})
    public static class ProbeApplication {
        public static void main(String[] args) {
            SpringApplication app = new SpringApplication(ProbeApplication.class);
            app.setWebApplicationType(WebApplicationType.SERVLET);
            app.setDefaultProperties(Map.of("spring.profiles.active", "gateway", "server.address", "127.0.0.1",
                    "spring.main.banner-mode", "off"));
            app.run(args);
        }

        @Bean
        TomcatServletWebServerFactory webServer() throws Exception {
            var server = new TomcatServletWebServerFactory(8080);
            server.setAddress(InetAddress.getByName("127.0.0.1"));
            return server;
        }

        @Bean
        ServletRegistrationBean<DispatcherServlet> dispatcher(WebApplicationContext context) {
            var bean = new ServletRegistrationBean<>(new DispatcherServlet(context), "/");
            bean.setAsyncSupported(true);
            bean.setLoadOnStartup(1);
            return bean;
        }

        @Bean
        ToolCallingManager toolCallingManager() {
            return ToolCallingManager.builder().build();
        }

        @Bean
        ChatModel deepSeekChatModel() {
            return prompt -> { throw new IllegalStateException("Opt-in probe must never call the default provider"); };
        }

        @Bean
        ChatClient.Builder chatClientBuilder(@Qualifier("deepSeekChatModel") ChatModel model) {
            return ChatClient.builder(model);
        }
    }

    @RestController
    public static class ProbeController {
        private final ModelRegistry registry;

        public ProbeController(ModelRegistry registry) {
            this.registry = registry;
        }

        @GetMapping("/health")
        public String health() {
            return "ok";
        }

        @PostMapping(value = "/probe", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
        public ResponseEntity<?> probe(@RequestParam String scenario, @RequestParam boolean stream) {
            AtomicInteger toolCalls = new AtomicInteger();
            var prompt = this.registry.getClient("gateway-chat").prompt()
                    .options(ChatOptions.builder().model(this.registry.getModelItem("gateway-chat").getModelName()))
                    .user("probe:" + scenario);
            if (scenario.equals("tool")) prompt.toolCallbacks(tool(toolCalls));
            if (!stream) {
                try {
                    ChatResponse response = prompt.call().chatResponse();
                    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(Map.of(
                            "answer", response.getResult().getOutput().getText(), "usage", usage(response), "tool_calls", toolCalls.get())));
                }
                catch (Exception error) {
                    var failure = serviceError(error);
                    return ResponseEntity.status(failure.statusCode()).contentType(MediaType.APPLICATION_JSON)
                            .body(JSON.writeValueAsString(Map.of("category", failure.code().orElse("UNKNOWN"))));
                }
            }
            Flux<ServerSentEvent<String>> events = prompt.stream().chatResponse().concatMap(response -> {
                var frames = new java.util.ArrayList<ServerSentEvent<String>>();
                if (response.getResult() != null && response.getResult().getOutput().getText() != null) {
                    frames.add(ServerSentEvent.builder(response.getResult().getOutput().getText()).event("delta").build());
                }
                if (response.getMetadata().getUsage().getTotalTokens() != null && response.getMetadata().getUsage().getTotalTokens() > 0) {
                    frames.add(ServerSentEvent.builder(JSON.writeValueAsString(usage(response))).event("usage").build());
                }
                return Flux.fromIterable(frames);
            }).concatWithValues(ServerSentEvent.builder("success").event("done").build())
                    .onErrorResume(error -> Flux.just(ServerSentEvent.builder(serviceError(error).code().orElse("UNKNOWN")).event("error").build()));
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(events);
        }

        private static Map<String, Integer> usage(ChatResponse response) {
            var usage = response.getMetadata().getUsage();
            return Map.of("prompt_tokens", usage.getPromptTokens(), "completion_tokens", usage.getCompletionTokens(), "total_tokens", usage.getTotalTokens());
        }

        private static OpenAIServiceException serviceError(Throwable error) {
            Throwable current = error;
            while (!(current instanceof OpenAIServiceException) && current.getCause() != null) current = current.getCause();
            if (current instanceof OpenAIServiceException service) return service;
            throw new IllegalStateException("Unexpected local protocol failure", current);
        }

        private static ToolCallback tool(AtomicInteger calls) {
            return new ToolCallback() {
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder().name("local_probe").description("Test-only Java tool")
                            .inputSchema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}").build();
                }
                public String call(String input) {
                    if (!JSON.readTree(input).path("value").asText().equals("probe")) throw new IllegalArgumentException("Tool arguments changed");
                    calls.incrementAndGet();
                    return "42";
                }
            };
        }
    }
}
