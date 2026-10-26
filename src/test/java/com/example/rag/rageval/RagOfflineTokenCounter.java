package com.example.rag.rageval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/** Explicit offline helper: counts local WordPiece tokens, never claims chat-model billing tokens. */
public final class RagOfflineTokenCounter {
    private RagOfflineTokenCounter() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected public-fixture contexts input and output paths");
        var mapper = JsonMapper.builder().build();
        var input = mapper.readTree(Files.readString(Path.of(args[0])));
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (var stream = new ClassPathResource("models/embedding/tokenizer.json").getInputStream();
                var tokenizer = HuggingFaceTokenizer.newInstance(stream,
                        Map.of("addSpecialTokens", "true", "truncation", "false", "padding", "false"))) {
            input.properties().forEach(entry -> counts.put(entry.getKey(), tokenizer.encode(entry.getValue().asText()).getIds().length));
        }
        mapper.writeValue(Path.of(args[1]).toFile(), Map.of(
                "tokenizer", "fixed-local-WordPiece-NOT-chat-model-tokenizer", "truncation", false, "counts", counts));
    }
}
