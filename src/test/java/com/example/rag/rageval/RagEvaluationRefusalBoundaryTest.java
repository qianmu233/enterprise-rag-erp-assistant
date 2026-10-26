package com.example.rag.rageval;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic language-boundary probes, not new model answers or quality measurements. */
class RagEvaluationRefusalBoundaryTest {

    @Test
    void shouldNotClassifyEvidenceBasedNegativeConclusionsAsRefusals() {
        List<Probe> probes = List.of(
                new Probe("跨租户知识库是否允许共享检索结果？", "不允许。必须按 ent_code 隔离。"),
                new Probe("其他知识库的文档能进入当前回答吗？", "不能。检索限定当前 knowledge_base_id。"),
                new Probe("处理中版本能成为证据吗？", "不能。processing 版本不能进入回答证据。"),
                new Probe("模型胡写了一个非法编号，后端会认吗？", "不会。后端丢弃不存在的引用编号。"),
                new Probe("没有 done 时引用还能展示吗？", "不能。应丢弃尚未确认的引用和图表。"),
                new Probe("新版导入失败会伤到旧版吗？", "不会。上一 ready 版本保持可检索。"));
        for (Probe probe : probes) {
            assertThat(RagEvaluationRefusalClassifier.isRefusal(probe.answer())).as(probe.question()).isFalse();
            assertThat(RagEvaluationRefusalClassifier.isRefusal(probe.answer(), probe.question()))
                    .as(probe.question()).isFalse();
        }
        var factCheck = new RagEvaluationAnswerChecker().check(probes.get(0).answer(),
                List.of("不允许", "ent_code 隔离"), List.of("允许跨租户共享"));
        assertThat(factCheck.matchedKeyFactCount()).isEqualTo(2);
        assertThat(factCheck.criticalFactViolations()).isEmpty();
    }

    @Test
    void shouldDistinguishExplainingARefusalRuleFromActuallyRefusing() {
        String question = "资料不足和 SSE 提前结束时应怎样处理？";
        String explanation = "资料不足时应原样回答“资料不足，无法确认。”；"
                + "SSE 未收到 done 时，应丢弃尚未确认的引用和图表。";
        assertThat(RagEvaluationRefusalClassifier.isRefusal(explanation, question)).isFalse();
        assertThat(RagEvaluationRefusalClassifier.isRefusal("资料不足，无法确认。", question)).isTrue();
    }

    @Test
    void shouldNotInflateSafeRefusalRateWithQuestionEchoOrUnsupportedNegativeAnswer() {
        String question = "旧格式向量可以直接成为证据吗？";
        assertThat(RagEvaluationRefusalClassifier.isRefusal("资料不足，无法确认。\n\n" + question, question))
                .isFalse();
        assertThat(RagEvaluationRefusalClassifier.isRefusal("资料不足，无法确认。系统不支持 OCR。",
                "系统支持 OCR 吗？")).isFalse();
        assertThat(RagEvaluationRefusalClassifier.isRefusal("资料不足，无法确认。", "系统支持 OCR 吗？"))
                .isTrue();
    }

    private record Probe(String question, String answer) { }
}
