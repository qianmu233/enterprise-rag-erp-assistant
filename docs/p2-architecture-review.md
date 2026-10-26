# P2 最小架构审查与验证记录

审查日期：2026-10-20（Asia/Shanghai）。工作对象为开发仓库 `spring-ai-rag-demo`，分支 `feature/enterprise-ai-upgrade`，起始 HEAD `0b6a54b22095a0752b49490add4b9fc0225fb25d`；不是发布快照 main。本项目基于 ly1836/spring-ai-rag-demo 二次开发，LICENSE 保留。

## 审查结论

Java 主线合理，无需架构迁移或新增直连 Profile。默认 DeepSeek、现有 OpenAI-compatible 和 Gemini 路由均直接使用 Java 模型适配。仅 `gateway` Profile + `gateway-chat` 进入 Python。Compose、默认 Maven 测试及普通 Java 启动没有强制 Python 依赖。

Python 的独立价值是 HTTP 协议与连接管理：请求/响应校验、固定 alias 和 URL 白名单、timeout/deadline、无重试、错误分类、SSE finish/DONE 检测、usage、request_id 和日志脱敏。它不是 RAG 或 Agent 服务。当前只对固定本地上游验收，未证明真实供应商或生产部署。

发现并修正的配置问题：Spring AI 2.0.0 `OpenAiSetup.calculateBaseUrl` 对自定义 OpenAI URL 原样返回，adapter 请求 `/chat/completions`；原配置缺少 `/v1`，旧注释错误地声称会自动补齐。现将 DashScope 前缀改成 `/compatible-mode/v1`；默认 DeepSeek 和 Gateway 地址均不变。新测试分别验证自定义地址不自动补版本、正常 `/v1` 路径及真实 YAML 配置，未访问 DashScope。

RAG 提示词小改：区分隐藏运行信息与上传技术文档公开字段，避免把合法文档解释一概当作内部泄露；原来的固定拒答、引用白名单、凭据保护和无 ERP Tool 规则保留。增加捕获真实装配 Prompt 的契约测试；没有宣称真实模型效果已改善。

`ModelRegistry` 仅更正 Javadoc：请求切换已装配模型不等于配置热更新。没有重写 provider、RAG 检索、SSE 生命周期、ERP Tools、数据库、Python 实现或测试断言。

## RAG 质量证据与原因边界

历史评测代码、50 条 v1 用例和第三轮运行 JSON 均仍存在。用例 SHA-256：`ef62c6f48d5f008bfbdc5821a2c52538eb8f673f6c8ebedb9b8e990e00b0c8ed`；历史 JSON `target/rag-eval/rag-evaluation.json` SHA-256：`d79d73f5788d742289822c5ce6a817b9776c12ca5da241d267e7b558dbc1d978`。JSON 为忽略的本地历史产物，不是克隆后自动包含的证据；仓库内保留统计摘要和测试入口。

| 指标 | 既有第三轮结果 | 本轮 |
|---|---:|---|
| 用例 | 50（45 可回答、5 无答案） | 未重跑真实模型 |
| 文档 Recall@5 / MRR@5 | 94.44% / 87.59% | 无新指标 |
| 关键事实短语命中 | 52/98，53.06% | 无新指标 |
| 无答案拒答 | 5/5 | 无新指标 |
| 可回答问题被判拒答 | 16/45 | 无新指标 |
| 结构引用有效率 | 48/48 | 不代表语义支持率 |

真实质量评测会接入数据库和模型/Judge，本轮没有启用 `rag-eval`，没有读取 Key 或消耗付费 Token。不对新提示词编造前后提升数据。

现有诊断的可解释结论：

- 16 条误拒答中，13 条的预期文档在 Top5 出现；这不代表包含关键事实的片段进入了生成上下文。
- 历史片段复盘有答案位于相邻 chunk、主体被切走、关键句相似度低于阈值等证据。不能仅靠改拒答 Prompt 解决检索遗漏。
- `RagContextFormatter` 会拼接已选片段全文，未发现额外按字符截短；但当前没有精确的整轮 Prompt token 预算或邻片恢复，模型端窗口行为不能据此确认。
- `RagEvidenceExtractor`/引用校验复用实际响应上下文，不再检索一次来“补引用”；校验索引、租户/版本资格及输出结构不等于检验所有结论的来源蕴含。
- 历史报告未完整保存每条生成请求的最终证据与原始回答，因此剩余提示词、模型与后处理影响不能逐例确定归因。保留失败样本和限制比降低标准更有价值。

后续如获真实评测授权：固定模型、参数、语料、集合及评测脚本，保存脱敏的 chunk ID/版本/顺序和证据哈希，先分解“关键片段未检出”和“检出仍拒答”，再决定单独实验邻片恢复、预算或提示词。不得把 Recall 改善与答案效果混算；本轮没有实现这些计划。

## 测试证据

正式回归使用 Microsoft OpenJDK 17.0.16；Python 3.12.3，既有锁定依赖。所有 HTTP 模型响应均为本地桩/Fake Model，无真实供应商请求、数据库或 Docker。

执行入口：

```powershell
mvn -o '-Dtest=OpenAiDirectHttpTest,AssistantClientProviderTest,ModelRegistryTest,GatewayModelConfigTest,ModelGatewayHttpTest' test
mvn -o verify
model-gateway/.venv/Scripts/python.exe -m pytest model-gateway/tests -c model-gateway/pyproject.toml -q
# 三进程流程见 scripts/test-model-gateway.ps1
```

本轮实际结果（2026-10-20，Asia/Shanghai）：

| 范围 | 实际命令/入口 | 通过 | failures | errors | skipped |
|---|---|---:|---:|---:|---:|
| 针对性 Java | 上述 5 类测试 | 36 | 0 | 0 | 0 |
| 默认 Java 全量及打包 | `mvn -o verify` | 407 | 0 | 0 | 0 |
| Python | 现有脚本调用锁定 venv 的 pytest | 51 | 0 | 0 | 0 |
| 三进程 IT | `mvn -o '-Dtest=ModelGatewayIT' test` | 14 | 0 | 0 | 0 |

407 = 原有 399 + 新直连 HTTP 7 + 知识提示词契约 1；原测试和断言均保留。针对性 36 项已包含在 407 中，不重复计算。三组独立用例共 472 项；这不是 472 个真实模型/数据库端到端场景。

完整模型资源测试 2 项、文档导入/切片测试 7 项均包含在默认回归中，真实执行 tokenizer 初始化、ONNX Session、384 维有限向量与中文语义排序。源码、target/classes、可执行 JAR 内的两个模型实体均通过既有 SHA-256：ONNX `98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f`（118453870 字节）；tokenizer `2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8`（9081518 字节）。JAR：`target/spring-ai-rag-demo-0.0.1-SNAPSHOT.jar`（378118809 字节，仅对应本轮构建）。

证据位置均为忽略的本地构建产物：

- `target/architecture-review/targeted.log`：针对性回归。
- `target/architecture-review/java-verify.log`、`target/surefire-reports/`：全量 BUILD SUCCESS、逐类 XML、9 项真实资源/切片测试。
- `target/gateway-phase2/python.xml`、`python-tests.log`：51 项 Python。
- `target/architecture-review/cross-language/integration.xml`、`integration-tests.log`、`processes.json`：三进程 IT 及本次启动的 PID/端口。
- `target/architecture-review/run-it.ps1`：从现有脚本提取的本次 IT 驱动，只省去已完成的 Python/Java 前置回归，沿用同一三进程启动和 finally 清理；不是新的项目实现或需要提交的脚本。

环境问题也保留记录：首次全量脚本在发现测试时遇到既有 `target/classes/.../KnowledgeBaseEntity.class` 的 `ClassFormatError: Invalid method Code length 0`，执行了 0 项，不能算通过。仅将生成的 classes/test-classes 备份至 `target/architecture-review/stale-classes-85410c18e88541689ca0af7187241d65` 后重新编译，问题不再出现；未修改实体源码。正式 verify 使用新的进程级 `ENGINE_CACHE_DIR=E:\temp\p2-review-full-native-0b20fab59af04ca6840e239ff5b4c6d6` 和 `DJL_OFFLINE=true`，不清理旧原生缓存。

Windows PowerShell 的 `ErrorActionPreference=Stop` 会将 JVM stderr 警告当作终止错误；正式运行按原脚本同样的进程退出码判断，未关闭测试/断言。首次尝试日志位于 `target/gateway-phase2/java-tests.log`。新增测试初次编译也发现 ChatClient 2.0 的 `options` 接收 Builder，已修正新增测试调用，未修改现有生产 API。

新直连测试使用真实 Spring 自动配置、ModelRegistry 和 AssistantClientProvider，验证本地路径、model/messages、usage、同步/流式工具往返及 429 无重试；默认 DeepSeek 通过 Mock 保持隔离。它不验证真实供应商可用性、structured-output 特性兼容或直连提前 EOF 的修复。

## 保留的风险与不做的改造

- 不引入 Redis、Kafka、LangChain/LangGraph、复杂调度平台或新 RAG 算法；没有业务证据支持这些投入。
- 不把固定本地 Gateway 改为任意 URL 转发，不删除网关及已有回归。
- 不把直连称作与网关完全等价：原 adapter 的异常 EOF 和取消传播边界仍存在。
- 缺失非 Gateway provider Bean 的部分历史扩展路径仍可能走默认 Builder；当前已验收的三类 provider 和严格未知 ID/Gateway 失败路径有回归，不宣称所有扩展 provider 可用。
- 该原开发仓库存在旧上游历史及开发配置风险，不代表当前可直接重新公开发布；本轮不进行凭据扫描扩围、读取 `.env` 或 Git 发布。

启动方式、面试问答、贡献边界和可核验简历条目见 [P2_INTERVIEW_GUIDE.md](../P2_INTERVIEW_GUIDE.md)。

## 2026-10-21 收尾复核：等待确认提交

分支和 HEAD 保持 `feature/enterprise-ai-upgrade` / `0b6a54b22095a0752b49490add4b9fc0225fb25d`。暂存区为空。起始状态除本轮 9 个文件外，仅有此前保留的未跟踪 `test-data/`；未读取、移动或清理该目录。本次仅补充文档，没有进一步生产代码修改。

`application.yml` 中变更仅涉及 `spring.ai.openai.base-url` 及注释，归属阿里云百炼 DashScope 北京地域 OpenAI-compatible 接口，见 [官方说明](https://www.alibabacloud.com/help/en/model-studio/base-url)。`openai` 是 adapter/provider 类型，不能误称实际供应商为 OpenAI。DeepSeek、Google GenAI、独立 Gateway 配置未改；默认仍为 DeepSeek，Gateway 仍要求 Profile + 显式 ID。

审查全部拟提交文件的新增/变更内容：未发现新加入的真实凭据、私钥或含凭据 URL；测试中的 `local-direct-test-key` 是显式本地 fixture。没有删除原有测试/断言，也未修改 RAG/Agent 编排、ERP、数据库或 SSE 生命周期。该结论仅针对拟提交增量，不等于重新审计或消除了原仓库已有配置/历史风险。没有读取 `.env`；它仍被忽略。未暂存任何文件，未提交或 push。

### 三类代表性误拒答

下表重新核对当前 v1 用例、第三轮 JSON 和既有 [逐例诊断与分片附录](rag-optimization-review.md#2-16-条拒答的逐例证据与原因归类)。JSON 仍为同一 SHA-256；50 条用例没有变动，标准误拒答仍是原来的 16 条。分片重建来自历史 INSERT 日志的既有复盘，**不是本次恢复到的实际生成输入**。

| 案例/问题 | 历史报告确定的事实 | 片段证据与分类 | 不能确认的部分 |
|---|---|---|---|
| `single-002`：上传支持哪些文件类型？ | 目标 product-manual 已在 Top5；最终固定拒答、0 引用、无 case error | 格式列表在 P0；历史重建选中的是 P1，没有 P0。属于文档命中、关键片段缺失的候选 | 原始逐题模型输入未保存，不能将重建当成完整输入实录 |
| `paraphrase-003`：向量表每条向量有多少维？ | 目标 deployment-guide 未命中，报告只检出 product-manual；固定拒答 | 384 维在 D0；既有重算相似度 0.174296，低于当前 knowledge 阈值 0.25。属于同义问法下的检索缺失 | 历史完整运行参数未保存，不能证明当时使用了所有当前参数；不据此全局降阈值 |
| `isolation-001`：跨租户知识库是否允许共享检索结果？ | security-guide 命中；最终固定拒答、0 引用 | 重建的 S0 有 ent_code 隔离和跨租户视为不存在规则，足以支持“不允许”；笼统的内部字段禁令是可能的回答策略歧义 | 原始 rawText/最终 Prompt 未保存，不能排除模型或净化影响；新澄清未做真实 A/B，不能判定已修复 |

另一个片段边界例子 `paraphrase-001` 中，P0 末尾“单文件上”与 P1 开头“限为 500MB”被分开，重建只选 P1。它支持检查片段级证据，而非只看文档 Recall；本次不改变切片或检索规则。

**下一轮只推荐一项：补齐固定 50 条用例的逐题证据链诊断包。** 在独立报告中记录实际使用的 chunk ID/版本/顺序、检索分数、证据哈希、模型标识/参数和关键事实覆盖标注，区分“事实未进入上下文”和“证据充分仍拒答”。仅对仓库公开 fixture 在本地受控报告保留正文，默认不记录真实业务 Prompt/Tool 参数，不提交日志。先让上述三类问题可以确定归因，再决定是否值得做邻片补全或提示词实验。此项尚未实现，也不作为本轮冻结前置条件；不改样本、不放宽引用/拒答标准，不编造提升。

### 拟提交清单

本轮原有 9 个文件加本次新增的简洁演示文档，共 **10 个**；未将历史用户数据或构建产物混入：

```text
README.md
P2_INTERVIEW_GUIDE.md
docs/model-gateway.md
docs/p2-architecture-review.md
docs/p2-demo.md
src/main/java/com/example/rag/chat/ModelRegistry.java
src/main/java/com/example/rag/chat/client/AssistantClientProvider.java
src/main/resources/application.yml
src/test/java/com/example/rag/chat/OpenAiDirectHttpTest.java
src/test/java/com/example/rag/chat/client/AssistantClientProviderTest.java
```

建议 Commit Message：`fix(p2): correct DashScope endpoint and finalize verified demo docs`。待用户确认后才能按明确清单暂存并创建本地 commit；禁止无差别 add、覆盖旧改动或直接 push。

冻结判断：已具备进入简历准备和技术复习的工程证据及可解释边界，建议停止功能扩张；真实模型小样本与下一轮 RAG 证据诊断均可独立决策。完整真实 RAG/ERP 演示仍需已授权模型和隔离数据库，不能将当前协议演示等同于全业务生产验收。简洁演示见 [p2-demo.md](p2-demo.md)。
