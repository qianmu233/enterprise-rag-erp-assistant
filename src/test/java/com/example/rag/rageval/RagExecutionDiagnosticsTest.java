package com.example.rag.rageval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.example.rag.chat.output.AssistantAnswerSanitizer;
import com.example.rag.chat.rag.RagContextFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.core.task.SyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagExecutionDiagnosticsTest {
    @Test
    void shouldBeInertWhenDisabled() {
        var diagnostic = diagnostic(false);
        var model = new CapturingModel("normal answer");
        assertThat(ChatClient.builder(model).build().prompt().advisors(diagnostic).user("question").call().content())
                .isEqualTo("normal answer");
        diagnostic.complete(model.response(), "normal answer", "normal answer", List.of(), 8, .25);
        assertThat(diagnostic.snapshot()).isEmpty();
    }

    @Test
    void shouldObserveTheAugmentedRequestAndFinalEvidenceWithoutBodies() throws Exception {
        var diagnostic = diagnostic(true);
        var model = new CapturingModel("answer-body-private [1]");
        var document = new Document("doc-id-private", "document-body-private", Map.of(
                "document_id", "doc-id-private", "chunk_id", "chunk-id-private", "document_version", 1,
                "chunk_index", 0, "ent_code", "tenant-private", "knowledge_base_id", "kb-private"));
        var rag = RetrievalAugmentationAdvisor.builder().documentRetriever(q -> List.of(document))
                .queryAugmenter(new RagContextFormatter()).taskExecutor(new SyncTaskExecutor()).build();
        ChatResponse response = ChatClient.builder(model).build().prompt()
                .options(ChatOptions.builder().model("local-fixture").temperature(0.0))
                .advisors(rag, diagnostic).user("question-private").call().chatResponse();
        diagnostic.complete(response, "answer-body-private [1]", "answer-body-private [1]", List.of(document), 8, .25);
        assertThat(model.prompt.getContents()).contains("document-body-private", "本轮知识库证据");
        assertThat((Integer) diagnostic.snapshot().get("promptUtf8Bytes"))
                .isGreaterThan("question-private".getBytes(StandardCharsets.UTF_8).length);
        assertThat(diagnostic.snapshot()).containsEntry("sanitizerChangedText", false).containsEntry("topK", 8)
                .containsEntry("rawCandidatesAndExclusionReasons", "UNKNOWN_NOT_CAPTURED_BEFORE_ELIGIBILITY");
        String serialized = persisted(diagnostic);
        assertThat(serialized).contains("finalEvidence", "augmentedPromptHmac", "local-fixture")
                .doesNotContain("question-private", "document-body-private", "answer-body-private",
                        "tenant-private", "kb-private", "doc-id-private", "chunk-id-private");
    }

    @Test
    void shouldDistinguishProtocolCleanupFromModelGeneration() throws Exception {
        var diagnostic = diagnostic(true);
        String raw = "<!--FINAL_ANSWER-->资料不足，无法确认。";
        String cleaned = new AssistantAnswerSanitizer().sanitize(raw);
        diagnostic.complete(null, raw, cleaned, List.of(), 8, .25);
        assertThat(diagnostic.snapshot()).containsEntry("sanitizerChangedText", true)
                .containsEntry("rawProtocolRefusal", false).containsEntry("finalProtocolRefusal", true);
        assertThat(persisted(diagnostic)).doesNotContain("资料不足", "FINAL_ANSWER");
    }

    @Test
    void shouldRecordExceptionTypeWithoutItsSensitiveMessage() throws Exception {
        var diagnostic = diagnostic(true);
        ChatModel failing = new CapturingModel("unused") {
            @Override public ChatResponse call(Prompt prompt) { throw new IllegalStateException("authorization-private"); }
        };
        assertThatThrownBy(() -> ChatClient.builder(failing).build().prompt().advisors(diagnostic)
                .user("question-private").call().content()).isInstanceOf(IllegalStateException.class);
        assertThat(persisted(diagnostic)).contains("java.lang.IllegalStateException")
                .doesNotContain("authorization-private", "question-private");
    }

    @Test
    void shouldNotReuseCorrelatableScopeFingerprintsAcrossRequests() {
        var first = diagnostic(true);
        var second = diagnostic(true);
        assertThat(first.snapshot().get("tenantHmac")).isNotEqualTo(second.snapshot().get("tenantHmac"));
        assertThat(first.snapshot().get("diagnosticTraceId")).isNotEqualTo(second.snapshot().get("diagnosticTraceId"));
    }

    @Test
    void shouldPreserveControlledEvidenceAnswersForAllElevenUnknownCases() {
        // Authored probes, not recovered raw model answers. No evidence/model quality claim.
        List<String> probes = List.of(
                "整次请求的上传上限为 550MB。",
                "新版本先进入 processing，全部成功后晋级 ready；失败时精确清理新版本。",
                "引用只来自本轮实际召回；引用校验不调用模型，不触发额外计费。",
                "证据不足时应明确说明资料不足；未收到 done 时丢弃尚未确认的引用和图表。",
                "不能。processing 中的新版不能用于回答。",
                "不能。SSE 未收到完成信号时丢弃尚未确认的引用和图表。",
                "不会。上一 ready 版本保持可检索。",
                "不允许。必须按 ent_code 隔离。",
                "不能。只能使用当前 knowledge_base_id 下的证据。",
                "不能。processing 版本不能绕过状态校验。",
                "不能。引用只允许来自本轮实际召回证据。");
        var answers = new com.example.rag.chat.rag.RagAnswerService(null, null,
                new com.example.rag.chat.rag.RagCitationValidator(JsonMapper.builder().build()));
        var context = new com.example.rag.chat.rag.RagRequestContext("fixture-kb", null, null);
        for (String probe : probes) {
            String cleaned = new AssistantAnswerSanitizer().sanitize(probe);
            assertThat(cleaned).isEqualTo(probe);
            var finalAnswer = answers.completeFromDocuments(cleaned, List.of(), context);
            assertThat(finalAnswer.answer()).isEqualTo(probe);
            assertThat(RagEvaluationRefusalClassifier.isRefusal(finalAnswer.answer())).isFalse();
        }
    }

    private RagExecutionDiagnostics diagnostic(boolean enabled) {
        return new RagExecutionDiagnostics(enabled, "isolation-001", "question-private", "tenant-private", "kb-private");
    }

    private String persisted(RagExecutionDiagnostics diagnostic) throws Exception {
        Path file = Path.of("target", "rag-execution-diagnostics", diagnostic.snapshot().get("diagnosticTraceId") + ".json");
        String text = Files.readString(file);
        assertThat(JsonMapper.builder().build().readTree(text).path("diagnosticTraceId").asText())
                .isEqualTo(diagnostic.snapshot().get("diagnosticTraceId"));
        return text;
    }

    private static class CapturingModel implements ChatModel {
        private final String answer;
        private Prompt prompt;
        CapturingModel(String answer) { this.answer = answer; }
        ChatResponse response() { return new ChatResponse(List.of(new Generation(new AssistantMessage(this.answer)))); }
        @Override public ChatResponse call(Prompt prompt) { this.prompt = prompt; return response(); }
        @Override public ChatOptions getOptions() { return ChatOptions.builder().build(); }
    }
}
