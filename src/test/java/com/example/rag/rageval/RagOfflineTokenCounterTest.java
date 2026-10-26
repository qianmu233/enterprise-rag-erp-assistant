package com.example.rag.rageval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RagOfflineTokenCounterTest {
    @Test
    void shouldCountFullContextsWithoutTruncatingAtTheEmbeddingWindow(@TempDir Path folder) throws Exception {
        Path input = folder.resolve("public-context.json");
        Path output = folder.resolve("counts.json");
        var mapper = JsonMapper.builder().build();
        Files.writeString(input, mapper.writeValueAsString(Map.of("long-public-fixture", "中文公开测试语料。".repeat(100))));
        RagOfflineTokenCounter.main(new String[] {input.toString(), output.toString()});
        var result = mapper.readTree(Files.readString(output));
        assertThat(result.path("truncation").asBoolean()).isFalse();
        assertThat(result.path("counts").path("long-public-fixture").asInt()).isGreaterThan(128);
        assertThat(result.path("tokenizer").asText()).contains("NOT-chat-model-tokenizer");
    }
}
