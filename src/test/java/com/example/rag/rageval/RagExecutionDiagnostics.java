package com.example.rag.rageval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in evaluation metadata only. Never stores prompt, document or answer bodies. */
final class RagExecutionDiagnostics implements CallAdvisor {
    private final boolean enabled;
    private final String question;
    private final String tenant;
    private final String knowledgeBase;
    private final byte[] key = new byte[32];
    private final Map<String, Object> data = new LinkedHashMap<>();

    RagExecutionDiagnostics(boolean enabled, String caseId, String question, String tenant, String knowledgeBase) {
        this.enabled = enabled;
        this.question = question;
        this.tenant = tenant;
        this.knowledgeBase = knowledgeBase;
        if (!enabled) return;
        new SecureRandom().nextBytes(this.key);
        this.data.put("schema", "rag-execution-metadata-v1");
        this.data.put("caseId", caseId != null && caseId.matches("(?:single|multi|paraphrase|isolation|no-answer)-[0-9]{3}")
                ? caseId : "REDACTED");
        this.data.put("diagnosticTraceId", UUID.randomUUID().toString());
        this.data.put("tenantHmac", fingerprint(tenant));
        this.data.put("knowledgeBaseHmac", fingerprint(knowledgeBase));
        this.data.put("rawCandidatesAndExclusionReasons", "UNKNOWN_NOT_CAPTURED_BEFORE_ELIGIBILITY");
        this.data.put("llmPreflightTokenBudget", "UNKNOWN");
        this.data.put("llmTruncation", "UNKNOWN");
    }

    @Override public String getName() { return "rag-evaluation-metadata"; }
    @Override public int getOrder() { return Integer.MAX_VALUE - 1; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (!this.enabled) return chain.nextCall(request);
        String contents = request.prompt().getInstructions().stream()
                .map(m -> m.getMessageType() + ":" + m.getText()).collect(java.util.stream.Collectors.joining("\n"));
        this.data.put("augmentedPromptHmac", fingerprint(contents));
        this.data.put("promptUtf8Bytes", contents.getBytes(StandardCharsets.UTF_8).length);
        this.data.put("messageCount", request.prompt().getInstructions().size());
        var options = request.prompt().getOptions();
        if (options != null) {
            String model = options.getModel();
            this.data.put("model", model != null && model.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,100}")
                    && !model.startsWith("sk-") ? model : "REDACTED_OR_UNKNOWN");
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("temperature", options.getTemperature());
            parameters.put("topP", options.getTopP());
            parameters.put("maxTokens", options.getMaxTokens());
            this.data.put("requestParameters", parameters);
        }
        try { return chain.nextCall(request); }
        catch (RuntimeException failure) {
            this.data.put("exceptionType", failure.getClass().getName());
            persist();
            throw failure;
        }
    }

    void complete(ChatResponse response, String raw, String cleaned, List<Document> evidence,
            int topK, double threshold) {
        if (!this.enabled) return;
        this.data.put("rawAnswerHmac", fingerprint(raw));
        this.data.put("finalAnswerHmac", fingerprint(cleaned));
        this.data.put("rawProtocolRefusal", RagEvaluationRefusalClassifier.isRefusal(raw, this.question));
        this.data.put("finalProtocolRefusal", RagEvaluationRefusalClassifier.isRefusal(cleaned, this.question));
        this.data.put("sanitizerChangedText", !Objects.equals(raw, cleaned));
        this.data.put("becameProtocolRefusal", !RagEvaluationRefusalClassifier.isRefusal(raw, this.question)
                && RagEvaluationRefusalClassifier.isRefusal(cleaned, this.question));
        this.data.put("topK", topK);
        this.data.put("threshold", threshold);
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Document d : evidence) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("position", selected.size() + 1);
            row.put("documentHmac", fingerprint(String.valueOf(d.getMetadata().get("document_id"))));
            row.put("chunkHmac", fingerprint(String.valueOf(d.getMetadata().get("chunk_id"))));
            row.put("version", numeric(d.getMetadata().get("document_version")));
            row.put("chunkIndex", numeric(d.getMetadata().get("chunk_index")));
            row.put("score", d.getScore());
            row.put("scopeMatches", Objects.equals(this.tenant, d.getMetadata().get("ent_code"))
                    && Objects.equals(this.knowledgeBase, d.getMetadata().get("knowledge_base_id")));
            selected.add(row);
        }
        this.data.put("finalEvidence", selected);
        if (response != null && response.getMetadata().getUsage() != null) {
            var usage = response.getMetadata().getUsage();
            Map<String, Object> tokens = new LinkedHashMap<>();
            tokens.put("promptTokens", usage.getPromptTokens());
            tokens.put("completionTokens", usage.getCompletionTokens());
            this.data.put("observedUsage", tokens);
        }
        persist();
    }

    Map<String, Object> snapshot() { return new LinkedHashMap<>(this.data); }

    private Object numeric(Object value) { return value instanceof Number ? value : "UNKNOWN"; }

    private String fingerprint(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(this.key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException unavailable) { return "UNAVAILABLE"; }
    }

    private void persist() {
        // Fixed ignored directory, UUID names, no raw-body option and no exception-message logging.
        try {
            Path folder = Path.of("target", "rag-execution-diagnostics");
            if (Files.isSymbolicLink(Path.of("target")) || Files.isSymbolicLink(folder)) return;
            Files.createDirectories(folder);
            Path output = folder.resolve(this.data.get("diagnosticTraceId") + ".json");
            // One immutable observation per request; never overwrite an older diagnostic.
            if (Files.exists(output)) return;
            Files.writeString(output, JsonMapper.builder().build().writeValueAsString(this.data),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (java.io.IOException | RuntimeException unavailable) {
            // Diagnostics must not turn a successful evaluation request into a business failure.
            this.data.put("persistenceStatus", "UNAVAILABLE");
        }
    }
}
