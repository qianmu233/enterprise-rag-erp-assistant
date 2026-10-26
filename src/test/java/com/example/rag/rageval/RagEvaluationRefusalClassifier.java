package com.example.rag.rageval;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 判断无答案用例是否得到明确且没有继续猜测结论的拒答。
 */
public final class RagEvaluationRefusalClassifier {

	/** 受控说明中单个安全字符的表达式，排除转折或并列引出的新结论。 */
	private static final String SAFE_REFUSAL_DETAIL_CHARACTER_REGEX =
		"(?:(?!(?:但|不过|然而|实际上|事实上|却|确实|可以确定|能够确定|"
			+ "并且|而且|同时|答案是|结论是))"
			+ "[^，,；;。.!！\\r\\n])";

	/** 需要与原始问题一致的资料范围拒答句式。 */
	private static final Pattern EVIDENCE_SCOPE_REFUSAL_PATTERN = Pattern.compile(
		"^(?:根据)?当前知识库(?:提供的)?资料[，,]\\s*\\*{0,2}无法确认("
			+ SAFE_REFUSAL_DETAIL_CHARACTER_REGEX + "{0,80}(?:是否|能否)"
			+ SAFE_REFUSAL_DETAIL_CHARACTER_REGEX
			+ "{1,120})\\*{0,2}[。.!！]\\s*(?:现有资料|检索到的文档中)仅提及"
			+ SAFE_REFUSAL_DETAIL_CHARACTER_REGEX + "{1,500}[，,]\\s*(?:但)?均未涉及"
			+ SAFE_REFUSAL_DETAIL_CHARACTER_REGEX + "{1,240}[。.!！]\\s*"
			+ "(?:资料不足[，,]\\s*无法确认[。.!！])?$");

	/** 需要与原始问题一致的缺失主题拒答句式。 */
	private static final Pattern MISSING_TOPIC_REFUSAL_PATTERN = Pattern.compile(
		"^(?:根据)?当前知识库(?:提供的)?资料[，,]\\s*\\*{0,2}"
			+ "(?:无法确认|无法回答|不能确定)(" + SAFE_REFUSAL_DETAIL_CHARACTER_REGEX
			+ "{1,120})\\*{0,2}[。.!！]\\s*"
			+ "(?:当前)?资料中(?:未提供|未包含|没有包含|未找到)(?:任何)?(?:关于)?"
			+ SAFE_REFUSAL_DETAIL_CHARACTER_REGEX + "{1,120}"
			+ "(?:资料|信息|内容|证据)[。.!！]?$");

	/** 仅接受文档格式/导入流程这两类资料范围说明，不允许任意肯定句混入解释。 */
	private static final String DOCUMENT_FORMAT_REGEX = "(?:pdf|word|excel|txt|html|rtf)";

	private static final Pattern CITED_REFUSAL_PATTERN = Pattern.compile(
		"^资料不足[，,]\\s*无法确认[。.!！]\\s*当前检索到的资料中[，,]\\s*"
			+ "(.{1,1000})[。.!！]\\s*因此现有知识库资料不足以确认("
			+ SAFE_REFUSAL_DETAIL_CHARACTER_REGEX + "{1,120})[。.!！]$");

	private static final Pattern CITED_MISSING_DETAIL_PATTERN = Pattern.compile(
		"^\\[[1-9][0-9]{0,3}]\\s*(?:仅说明文档上传支持\\s*" + DOCUMENT_FORMAT_REGEX
			+ "(?:\\s*[、，,和及]\\s*" + DOCUMENT_FORMAT_REGEX + "){0,5}\\s*(?:等)?格式"
			+ "|提到文档导入包含提取和向量写入阶段)[，,]\\s*(?:但)?(?:未提及|未说明)("
			+ SAFE_REFUSAL_DETAIL_CHARACTER_REGEX + "{1,120})$");

	private static final Pattern UNSAFE_CITED_DETAIL = Pattern.compile(
		"也|以及|并|可能|或许|大概|也许|应该|猜测|推测|预计|肯定|确定|答案|结论");

	/** 无需原始问题即可确定的中英文完整拒答句式。 */
	private static final List<Pattern> SAFE_REFUSAL_PATTERNS = List.of(
		// 中文回答只允许固定拒答句，以及受控的补充资料请求。
		Pattern.compile("^(?:当前知识库)?资料不足"
			+ "(?:[，,]\\s*无法确认)?"
			+ "(?:[，,]\\s*(?:但)?请补充(?:合同原文|部署资料|相关资料|所需资料|完整资料|"
			+ "相关信息|所需信息|完整信息|原始文件|源文件)(?:后再试|后重试)?)?[。.!！]?$"),
		Pattern.compile("^(?:资料|当前资料)?(?:未提供|未包含)"
			+ "(?:(?:问题)?所需的?|相关的?)?(?:资料|信息|内容|证据)?[。.!！]?$"),
		Pattern.compile("^(?:没有找到|没有包含|未找到|未包含)"
			+ "(?:(?:问题)?所需的?|相关的?)?(?:资料|信息|内容|证据)?[。.!！]?$"),
		Pattern.compile("^(?:无法回答|无法确认|不能确定)"
			+ "(?:(?:问题)?所需的?|相关的?)?(?:资料|信息|内容|证据|答案)?[。.!！]?$"),
		// 固定拒答句后只允许说明知识库未包含被询问主题，不接受任何实际答案。
		Pattern.compile("^资料不足[，,]\\s*无法确认[。.!！]\\s*当前知识库资料"
			+ "(?:（[^）\\r\\n]{1,100}）)?中未包含“[^”\\r\\n]{1,100}”相关(?:功能)?信息[。.!！]?$"),
		// 英文回答同样只允许明确拒答，以及受控的补充资料请求。
		Pattern.compile("^(?:(?:the available|the provided) (?:evidence|information) is |there is )?"
			+ "(?:insufficient|not enough)(?: (?:evidence|information|data|contribution data))?"
			+ "(?: to (?:answer|confirm)(?: this question)?)?[.!]?$"),
		Pattern.compile("^(?:cannot answer|cannot confirm|unable to answer)(?: this question)?[.!]?$"),
		Pattern.compile("^(?:not enough|insufficient)(?: evidence| information)?[,;]\\s*(?:but\\s+)?"
			+ "please (?:provide|supply|share) (?:the )?(?:contract|contract text|deployment documents?|"
			+ "relevant (?:information|documents?)|required (?:information|documents?)|source documents?)[.!]?$"));

	/**
	 * 禁止实例化评测拒答识别工具。
	 */
	private RagEvaluationRefusalClassifier() {
	}

	/**
	 * 判断回答是否明确拒答且没有在拒答后继续给出确定结论。
	 *
	 * @param answer 模型回答
	 * @return 明确拒答且未继续作答时返回 true
	 */
	public static boolean isRefusal(String answer) {
		String normalized = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
		// 必须由完整安全句式覆盖整段回答，任何额外内容都会使匹配失败。
		return SAFE_REFUSAL_PATTERNS.stream()
			.anyMatch(pattern -> pattern.matcher(normalized).matches());
	}

	/**
	 * 结合原始问题判断带资料范围说明的回答是否属于安全拒答。
	 *
	 * @param answer   模型回答
	 * @param question 评测原始问题
	 * @return 固定拒答或待确认对象与原问题一致时返回 true
	 */
	public static boolean isRefusal(String answer, String question) {
		if (isRefusal(answer)) {
			return true;
		}
		String normalizedAnswer = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
		String normalizedQuestion = normalizeQuestion(question);
		if (normalizedQuestion.isEmpty()) {
			return false;
		}
		// 扩展说明只有待确认对象与评测原问题一致时才计入拒答，避免夹带额外结论。
		return matchesContextualRefusal(EVIDENCE_SCOPE_REFUSAL_PATTERN,
			normalizedAnswer, normalizedQuestion)
			|| matchesContextualRefusal(MISSING_TOPIC_REFUSAL_PATTERN,
				normalizedAnswer, normalizedQuestion)
			|| matchesCitedRefusal(normalizedAnswer, normalizedQuestion);
	}

	/** 引用解释必须完整覆盖回答，缺失对象及最后的拒答对象均须与原问题相符。 */
	private static boolean matchesCitedRefusal(String answer, String question) {
		Matcher envelope = CITED_REFUSAL_PATTERN.matcher(answer);
		if (!envelope.matches() || !question.equals(normalizeQuestion(envelope.group(2)))) {
			return false;
		}
		String[] details = envelope.group(1).split("[；;]", -1);
		if (details.length > 5) {
			return false;
		}
		for (String detail : details) {
			Matcher citation = CITED_MISSING_DETAIL_PATTERN.matcher(detail.strip());
			if (!citation.matches() || UNSAFE_CITED_DETAIL.matcher(citation.group(1)).find()) {
				return false;
			}
			// 只移除这类缺失说明中的语法成分；每个剩余对象必须实际出现在问题中。
			String missing = normalizeQuestion(citation.group(1)).replace("能力", "");
			String[] subjects = missing.split("(?:对|进行)", -1);
			for (String subject : subjects) {
				if (subject.isEmpty() || !question.contains(subject)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * 校验上下文拒答中的待确认对象与原始问题一致。
	 *
	 * @param pattern            带待确认对象捕获组的完整句式
	 * @param normalizedAnswer   已规范大小写的回答
	 * @param normalizedQuestion 已规范化的问题
	 * @return 句式完整且问题对象一致时返回 true
	 */
	private static boolean matchesContextualRefusal(Pattern pattern, String normalizedAnswer,
			String normalizedQuestion) {
		Matcher matcher = pattern.matcher(normalizedAnswer);
		return matcher.matches()
			&& normalizedQuestion.equals(normalizeQuestion(matcher.group(1)));
	}

	/**
	 * 移除不改变问题对象的标点和常见疑问句式。
	 *
	 * @param value 原始问题或回答中的待确认对象
	 * @return 可用于严格比较的问题对象
	 */
	private static String normalizeQuestion(String value) {
		String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT)
			.replaceAll("[\\s\\p{Punct}\\p{P}]+", "");
		// 仅移除疑问形式，保留业务对象和谓词，任何附加结论都会造成比较不一致。
		return normalized.replace("请问", "")
			.replace("是否", "")
			.replace("能否", "")
			.replace("有哪些", "")
			.replace("是什么", "")
			.replace("是多少", "")
			.replace("吗", "")
			.replace("呢", "")
			.replace("的", "");
	}

}
