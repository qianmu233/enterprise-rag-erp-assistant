package com.example.rag.chat.output;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 助手最终答案净化器测试。
 */
class AssistantAnswerSanitizerTest {

	@Test
	public void shouldKeepAnswerBetweenRepeatedEmptyMarkers() {
		String marker = AssistantAnswerSanitizer.FINAL_ANSWER_MARKER;
		assertThat(new AssistantAnswerSanitizer().sanitize(
			"让我查询资料。" + marker + "\n" + marker + "支持 PDF。[1]" + marker + "\n" + marker))
			.isEqualTo("支持 PDF。[1]");
	}

	@Test
	public void shouldNotRecoverContentBeforeOnlyTrailingMarker() {
		assertThat(new AssistantAnswerSanitizer().sanitize(
			"让我查询资料。支持 PDF。" + AssistantAnswerSanitizer.FINAL_ANSWER_MARKER)).isEmpty();
	}

	@Test
	public void shouldRejectConflictingNonEmptyFinalSections() {
		String marker = AssistantAnswerSanitizer.FINAL_ANSWER_MARKER;
		assertThat(new AssistantAnswerSanitizer().sanitize(
			marker + "支持 PDF。[1]" + marker + "我将调用内部工具。")).isEmpty();
	}

	@Test
	public void shouldKeepOnlySafeBodyAfterBusinessIntroduction() {
		assertThat(new AssistantAnswerSanitizer().sanitize("让我说明支持的格式：PDF、Word、Excel。[1]"))
			.isEqualTo("PDF、Word、Excel。[1]");
		assertThat(new AssistantAnswerSanitizer().sanitize("我将为您介绍上传限制：单文件上限为 500MB。[1]"))
			.isEqualTo("单文件上限为 500MB。[1]");
	}

	@Test
	public void shouldNotRecoverBodyFromExecutionNarration() {
		AssistantAnswerSanitizer sanitizer = new AssistantAnswerSanitizer();
		assertThat(sanitizer.sanitize("我将查询资料：单文件上限为 500MB。[1]")).isEmpty();
		assertThat(sanitizer.sanitize("让我说明工具调用顺序：先执行内部查询。")).isEmpty();
		assertThat(sanitizer.sanitize("让我说明：我先调用工具确认答案。")).isEmpty();
		assertThat(sanitizer.sanitize("让我说明：先调用内部工具确认答案。")).isEmpty();
	}

	@Test
	public void shouldSuppressToolExecutionAfterBusinessIntroduction() {
		assertThat(new AssistantAnswerSanitizer().sanitize("让我说明：下面需要使用工具查询销售订单。"))
			.isEmpty();
	}

	@Test
	public void shouldSuppressToolExecutionAfterIntroductionInSingleStreamingChunk() {
		AssistantAnswerSanitizer.StreamSession session = new AssistantAnswerSanitizer().openStream();
		assertThat(session.accept("让我说明：下面需要使用工具查询销售订单。")).isEmpty();
		AssistantAnswerSanitizer.StreamCompletion completion = session.finish();
		assertThat(completion.content()).isEmpty();
		assertThat(completion.pendingDelta()).isEmpty();
	}

	@Test
	public void shouldBufferIncompleteIntroductionBodyBeforeToolExecutionArrives() {
		AssistantAnswerSanitizer.StreamSession session = new AssistantAnswerSanitizer().openStream();
		assertThat(session.accept("让我说明：下")).isEmpty();
		assertThat(session.accept("面需要使用工具查询销售订单。")).isEmpty();
		AssistantAnswerSanitizer.StreamCompletion completion = session.finish();
		assertThat(completion.content()).isEmpty();
		assertThat(completion.pendingDelta()).isEmpty();
	}

	@Test
	public void shouldSuppressIntroductionToolExecutionForEveryStreamingSplit() {
		String raw = "让我说明：下面需要使用工具查询销售订单。";
		for (int split = 1; split < raw.length(); split++) {
			AssistantAnswerSanitizer.StreamSession session = new AssistantAnswerSanitizer().openStream();
			assertThat(session.accept(raw.substring(0, split))).as("first chunk, split=%s", split).isEmpty();
			assertThat(session.accept(raw.substring(split))).as("second chunk, split=%s", split).isEmpty();
			AssistantAnswerSanitizer.StreamCompletion completion = session.finish();
			assertThat(completion.content()).as("content, split=%s", split).isEmpty();
			assertThat(completion.pendingDelta()).as("pending delta, split=%s", split).isEmpty();
		}
		AssistantAnswerSanitizer.StreamSession session = new AssistantAnswerSanitizer().openStream();
		for (int index = 0; index < raw.length(); index++) {
			assertThat(session.accept(raw.substring(index, index + 1))).as("character=%s", index).isEmpty();
		}
		AssistantAnswerSanitizer.StreamCompletion completion = session.finish();
		assertThat(completion.content()).isEmpty();
		assertThat(completion.pendingDelta()).isEmpty();
	}

	@Test
	public void shouldKeepLegalFormatIntroductionForEveryStreamingSplit() {
		String raw = "让我说明支持的格式：PDF、Word、Excel。[1]";
		for (int split = 1; split < raw.length(); split++) {
			AssistantAnswerSanitizer.StreamSession session = new AssistantAnswerSanitizer().openStream();
			String emitted = session.accept(raw.substring(0, split)) + session.accept(raw.substring(split));
			AssistantAnswerSanitizer.StreamCompletion completion = session.finish();
			assertThat(emitted + completion.pendingDelta()).as("split=%s", split)
				.isEqualTo("PDF、Word、Excel。[1]");
			assertThat(completion.content()).as("split=%s", split).isEqualTo("PDF、Word、Excel。[1]");
		}
	}

	@Test
	public void shouldKeepBusinessTextContainingPlanRejectionValue() {
		String answer = "审核结果字段中 accepted=false 表示本次申请未被接受。";
		assertThat(new AssistantAnswerSanitizer().sanitize(answer)).isEqualTo(answer);
		assertThat(new AssistantAnswerSanitizer().sanitize("该字段的示例值是 accepted=false。"))
			.isEqualTo("该字段的示例值是 accepted=false。");
	}

	@Test
	public void shouldStillSuppressInternalPlanRejection() {
		AssistantAnswerSanitizer sanitizer = new AssistantAnswerSanitizer();
		assertThat(sanitizer.sanitize("accepted=false，重新规划图表。")).isEmpty();
		assertThat(sanitizer.sanitize("图表规划返回 accepted=false，重新尝试。")).isEmpty();
		assertThat(sanitizer.sanitize("规划反馈：accepted=false。")).isEmpty();
		assertThat(sanitizer.sanitize("内部执行结果 accepted=false。")).isEmpty();
		assertThat(sanitizer.sanitize("工具返回审核字段中 accepted=false 表示需要重新规划。")).isEmpty();
	}

	@Test
	public void shouldKeepRepeatedMarkerAnswerForEveryStreamingSplit() {
		String marker = AssistantAnswerSanitizer.FINAL_ANSWER_MARKER;
		String raw = "让我查询资料。" + marker + marker + "支持 PDF。[1]" + marker + marker;
		for (int split = 1; split < raw.length(); split++) {
			AssistantAnswerSanitizer.StreamSession session = new AssistantAnswerSanitizer().openStream();
			String emitted = session.accept(raw.substring(0, split)) + session.accept(raw.substring(split));
			AssistantAnswerSanitizer.StreamCompletion completion = session.finish();
			assertThat(emitted + completion.pendingDelta()).as("split=%s", split).isEqualTo("支持 PDF。[1]");
			assertThat(completion.content()).as("split=%s", split).isEqualTo("支持 PDF。[1]");
		}
	}

	@Test
	public void shouldSuppressConflictingStreamingContinuation() {
		String marker = AssistantAnswerSanitizer.FINAL_ANSWER_MARKER;
		AssistantAnswerSanitizer.StreamSession session = new AssistantAnswerSanitizer().openStream();
		assertThat(session.accept(marker + "支持 PDF。[1]")).isEqualTo("支持 PDF。[1]");
		assertThat(session.accept(marker + "我将调用内部工具。")).isEmpty();
		assertThat(session.accept("继续内部查询。")).isEmpty();
		assertThat(session.finish().content()).isEmpty();
	}

	@Test
	public void shouldDiscardTrailingIncompleteMarkerAfterStreamingBody() {
		String marker = AssistantAnswerSanitizer.FINAL_ANSWER_MARKER;
		for (int length = 1; length < marker.length(); length++) {
			AssistantAnswerSanitizer.StreamSession session = new AssistantAnswerSanitizer().openStream();
			assertThat(session.accept(marker + "最终回答" + marker.substring(0, length)))
				.isEqualTo("最终回答");
			assertThat(session.finish().content()).isEqualTo("最终回答");
		}
	}

	@Test
	public void shouldRecoverBusinessIntroductionWhenStreaming() {
		AssistantAnswerSanitizer.StreamSession session = new AssistantAnswerSanitizer().openStream();
		assertThat(session.accept("让我说明支持的格式：")).isEmpty();
		assertThat(session.accept("PDF、Word。[1]")).isEqualTo("PDF、Word。[1]");
		assertThat(session.finish().content()).isEqualTo("PDF、Word。[1]");
	}

	@Test
	public void shouldKeepBusinessPlanRejectionValueWhenStreaming() {
		String answer = "审核结果字段中 accepted=false 表示本次申请未被接受。";
		AssistantAnswerSanitizer.StreamSession session = new AssistantAnswerSanitizer().openStream();
		assertThat(session.accept(answer)).isEqualTo(answer);
		assertThat(session.finish().content()).isEqualTo(answer);
	}

	@Test
	public void shouldSuppressStreamingPlanRejectionAcrossChunks() {
		AssistantAnswerSanitizer.StreamSession session = new AssistantAnswerSanitizer().openStream();
		assertThat(session.accept("accepted=")).isEmpty();
		assertThat(session.accept("false，重新规划图表。")).isEmpty();
		assertThat(session.finish().content()).isEmpty();
		AssistantAnswerSanitizer.StreamSession planSession = new AssistantAnswerSanitizer().openStream();
		assertThat(planSession.accept("图表规划")).isEmpty();
		assertThat(planSession.accept("返回 accepted=false，重新尝试。")).isEmpty();
		assertThat(planSession.finish().content()).isEmpty();
	}

	@Test
	public void shouldKeepNullBlankAndMarkerOnlyAnswersEmpty() {
		AssistantAnswerSanitizer sanitizer = new AssistantAnswerSanitizer();
		assertThat(sanitizer.sanitize(null)).isEmpty();
		assertThat(sanitizer.sanitize(" \n\t")).isEmpty();
		String marker = AssistantAnswerSanitizer.FINAL_ANSWER_MARKER;
		assertThat(sanitizer.sanitize(marker + "\n" + marker)).isEmpty();
		AssistantAnswerSanitizer.StreamSession session = sanitizer.openStream();
		assertThat(session.accept(marker + "\n" + marker)).isEmpty();
		assertThat(session.finish().content()).isEmpty();
	}

	/**
	 * 验证最终答案边界之前的内部执行旁白不会进入返回内容。
	 */
	@Test
	public void shouldKeepOnlyContentAfterFinalAnswerMarker() {
		AssistantAnswerSanitizer sanitizer = new AssistantAnswerSanitizer();

		String content = sanitizer.sanitize(
			"让我查询业务数据。让我规划图表。\n\n<!--FINAL_ANSWER-->\n## 查询结果\n\n共 8 条。\n");

		assertThat(content).isEqualTo("## 查询结果\n\n共 8 条。");
	}

	/**
	 * 验证旧消息存在最终分隔线时只返回分隔线后的业务答案。
	 */
	@Test
	public void shouldSanitizeLegacyNarrationBeforeFinalSection() {
		AssistantAnswerSanitizer sanitizer = new AssistantAnswerSanitizer();

		String content = sanitizer.sanitize(
			"让我修正图表规划，重新尝试。图表规划成功。\n\n---\n\n| 产品 | 销量 |\n|---|---:|\n| A | 10 |");

		assertThat(content)
			.isEqualTo("| 产品 | 销量 |\n|---|---:|\n| A | 10 |");
	}

	/**
	 * 验证跨分片边界标记之前的内容被抑制，标记之后的内容正常流出。
	 */
	@Test
	public void shouldSuppressStreamingNarrationAcrossSplitMarker() {
		AssistantAnswerSanitizer.StreamSession session =
			new AssistantAnswerSanitizer().openStream();

		assertThat(session.accept("让我查询并规划图表。<!--FINAL_ANS")).isEmpty();
		assertThat(session.accept("WER-->\n最终")).isEqualTo("最终");
		assertThat(session.accept("回答")).isEqualTo("回答");
		AssistantAnswerSanitizer.StreamCompletion completion = session.finish();

		assertThat(completion.content()).isEqualTo("最终回答");
		assertThat(completion.pendingDelta()).isEmpty();
	}

	/**
	 * 验证模型未输出边界标记时，识别分隔后的正文即可立即恢复输出。
	 */
	@Test
	public void shouldFallbackToSanitizedContentWithoutMarker() {
		AssistantAnswerSanitizer.StreamSession session =
			new AssistantAnswerSanitizer().openStream();

		assertThat(session.accept("让我整理结果。\n\n---\n\n最终回答")).isEqualTo("最终回答");
		AssistantAnswerSanitizer.StreamCompletion completion = session.finish();

		assertThat(completion.content()).isEqualTo("最终回答");
		assertThat(completion.pendingDelta()).isEmpty();
	}

	/**
	 * 验证没有边界且英文查询、规划旁白与中文答案连续拼接时只保留业务答案。
	 */
	@Test
	public void shouldRemoveLeadingEnglishNarrationWithoutMarker() {
		AssistantAnswerSanitizer sanitizer = new AssistantAnswerSanitizer();

		String content = sanitizer.sanitize(
			"I need to query the after-sales ticket data for March 2026 to get the current data for this round."
				+ "I'll plan the word cloud visualization based on the issue type frequency data."
				+ "2026年3月共产生5笔售后工单，按问题类型统计如下：");

		assertThat(content).isEqualTo("2026年3月共产生5笔售后工单，按问题类型统计如下：");
	}

	/**
	 * 验证流式阶段能够移除包含中文业务主体的英文内部旁白并恢复实时输出。
	 */
	@Test
	public void shouldRemoveLeadingEnglishNarrationWhenStreamingFinishesWithoutMarker() {
		AssistantAnswerSanitizer.StreamSession session =
			new AssistantAnswerSanitizer().openStream();

		assertThat(session.accept(
			"I need to query the accounts receivable for 合成客户-001.")).isEmpty();
		assertThat(session.accept(
			"I'll plan the gauge chart visualization for the receivable balance.")).isEmpty();
		assertThat(session.accept("合成客户-001当前的应收账款情况如下："))
			.isEqualTo("合成客户-001当前的应收账款情况如下：");
		assertThat(session.accept("\n\n| 指标 | 金额 |"))
			.isEqualTo("\n\n| 指标 | 金额 |");
		AssistantAnswerSanitizer.StreamCompletion completion = session.finish();

		assertThat(completion.content())
			.isEqualTo("合成客户-001当前的应收账款情况如下：\n\n| 指标 | 金额 |");
		assertThat(completion.pendingDelta()).isEmpty();
	}

	/**
	 * 验证普通英文业务回答不会被兼容净化规则误删。
	 */
	@Test
	public void shouldKeepOrdinaryEnglishBusinessAnswer() {
		AssistantAnswerSanitizer sanitizer = new AssistantAnswerSanitizer();

		String content = sanitizer.sanitize("Current receivable balance is 20,000 CNY.");

		assertThat(content).isEqualTo("Current receivable balance is 20,000 CNY.");
	}

	/**
	 * 验证没有最终答案边界的安全中文正文不会等待流完成后一次性补发。
	 */
	@Test
	public void shouldStreamSafeChineseContentWithoutMarker() {
		AssistantAnswerSanitizer.StreamSession session =
			new AssistantAnswerSanitizer().openStream();

		assertThat(session.accept("2026年3月")).isEqualTo("2026年3月");
		assertThat(session.accept("共产生5笔售后工单")).isEqualTo("共产生5笔售后工单");
		assertThat(session.accept("，统计如下：")).isEqualTo("，统计如下：");
		AssistantAnswerSanitizer.StreamCompletion completion = session.finish();

		assertThat(completion.content()).isEqualTo("2026年3月共产生5笔售后工单，统计如下：");
		assertThat(completion.pendingDelta()).isEmpty();
	}

	/**
	 * 验证合法技术说明和业务建议不会因包含内部协议同名词而被误删。
	 */
	@Test
	public void shouldKeepBusinessContentContainingNarrationKeywords() {
		AssistantAnswerSanitizer sanitizer = new AssistantAnswerSanitizer();

		String content = sanitizer.sanitize(
			"Spring Cloud Stream 的 bindings 用于配置消息通道，transform 表示数据转换规则。"
				+ "\n\n支付失败后可以重新尝试，图表规划功能不会改变原始业务数据。");

		assertThat(content).isEqualTo(
			"Spring Cloud Stream 的 bindings 用于配置消息通道，transform 表示数据转换规则。"
				+ "\n\n支付失败后可以重新尝试，图表规划功能不会改变原始业务数据。");
	}

	/**
	 * 验证流式输出在最终答案标记中间终止时不会泄漏协议片段。
	 */
	@Test
	public void shouldDiscardIncompleteFinalAnswerMarkerWhenStreamStops() {
		String marker = AssistantAnswerSanitizer.FINAL_ANSWER_MARKER;

		// 覆盖标记的所有非空真前缀，确保任意网络分片位置终止都不会泄漏。
		for (int length = 1; length < marker.length(); length++) {
			AssistantAnswerSanitizer.StreamSession session =
				new AssistantAnswerSanitizer().openStream();
			String markerPrefix = marker.substring(0, length);

			assertThat(session.accept(" \n" + markerPrefix)).isEmpty();
			AssistantAnswerSanitizer.StreamCompletion completion = session.finish();
			assertThat(completion.content()).isEmpty();
			assertThat(completion.pendingDelta()).isEmpty();
		}
	}

	/**
	 * 验证内部旁白与残缺边界分属不同段落时仍不会泄漏协议片段。
	 */
	@Test
	public void shouldBufferIncompleteMarkerAfterInternalNarration() {
		AssistantAnswerSanitizer.StreamSession completedSession =
			new AssistantAnswerSanitizer().openStream();

		assertThat(completedSession.accept("让我查询业务数据。\n\n<!--FINAL_ANS")).isEmpty();
		assertThat(completedSession.accept("WER-->\n最终回答")).isEqualTo("最终回答");
		assertThat(completedSession.finish().content()).isEqualTo("最终回答");

		AssistantAnswerSanitizer.StreamSession stoppedSession =
			new AssistantAnswerSanitizer().openStream();
		assertThat(stoppedSession.accept("让我查询业务数据。\n\n<!--FINAL_ANS")).isEmpty();
		AssistantAnswerSanitizer.StreamCompletion completion = stoppedSession.finish();

		assertThat(completion.content()).isEmpty();
		assertThat(completion.pendingDelta()).isEmpty();
	}

}
