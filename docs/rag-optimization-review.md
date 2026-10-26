# P2 限时 RAG 质量优化复核

> 最终收尾结论：Java 17 离线测试 377/377 通过；历史 Top 5 检索重建与报告 50/50 一致。11 条回答策略候选问题尚未通过真实模型验证，**没有实现或验证 RAG 问答质量改善**。业务源码和配置保持 `81bad69` 对应内容，P2 质量优化与环境诊断已结束。
>
> 本文保留两轮诊断的时间顺序。第 1—6 节是首轮历史记录，第 7 节更新环境恢复和证据分类；各节“未提交”“未读取 test-data”等描述指当时状态。第 8 节记录本次只做文档审核与 Git 收尾。发布前仅作描述澄清和本机路径脱敏，不改历史测试结果。

日期：2026-10-15（Asia/Shanghai）。稳定版本：`81bad69`。本轮从北京时间 09:00:45 开始，预算上限 4 小时、最多两轮小范围修改。

结果复核记录截至北京时间 09:08:11，约 7 分 26 秒；因复现停止条件提前结束，没有耗满预算。

## 结论与停止决定

本轮触发停止条件 4：**无法可靠复现原有真实评测**。没有进行源码或配置优化，优化尝试次数为 **0/2**，不需要回滚；不因为尚有时间预算而开始无法验证收益的提示词修改。

已完成仓库及历史结果核对、16 条拒答归类、无凭据输出的环境前置检查、Java 17 离线回归。真实优化前复跑和优化后评测均未执行，不能宣称拒答减少、正确率提升或安全性得到新的真实验证。只保留本复核文档，不创建本地 Git Commit、不 push。

## 1. 稳定版本、历史基线与复现方法

起始 HEAD 为 `81bad69`，跟踪文件没有未提交修改；仅有原有未跟踪目录 `test-data/`。本轮未读取或修改该目录，未访问 P1/P3 项目。

三份历史 JSON 分别来自项目上级的以下目录；原始 JSON/Markdown 及当前 `target/rag-eval/` 报告保持不变：

| 轮次 | 备份目录 | 生成时间（北京时间） | 空回答 | Recall@5 | MRR@5 | 关键事实命中率 | 无答案拒答 |
|---|---|---|---:|---:|---:|---:|---:|
| 第一轮 | `rag-eval-baselines/baseline-20261010/` | 2026-10-10 11:16:29 | 25/50 | 94.44% | 87.59% | 36.73% | 0/5 |
| 第二轮 | `rag-eval-baselines/phase1-eval-20261010/` | 2026-10-10 16:48:40 | 22/50 | 94.44% | 87.59% | 39.80% | 2/5 |
| 第三轮，作为本轮历史基线 | `rag-eval-baselines/prompt-fix-eval-20261010/` | 2026-10-10 17:11:38 | 0/50 | 94.44% | 87.59% | 53.06% | 5/5 |

### 数据、知识库与调用路径

- [v1 用例](../src/test/resources/rag-eval/v1/cases.jsonl)：50 条，其中单文档 20、多文档 10、同义改写 10、无答案 5、隔离安全 5；45 条可回答、5 条不可回答。
- 目标来源为 product-manual、deployment-guide、security-guide、operations-guide、billing-guide 五份短文档；isolation-interference 仅作其他租户/知识库的干扰 fixture。
- [RagEvaluationIT](../src/test/java/com/example/rag/rageval/RagEvaluationIT.java) 为随机租户创建知识库并重新导入 fixture，使用知识专用客户端及同轮 RAG Advisor，再调用相关性、事实一致性 Judge。逻辑上的知识库别名是 primary，随机租户和知识库 ID 本来就不应要求逐次相同。
- 当前封板脚本通过 `rag.eval.modelId` 选择模型，未指定时采用注册表默认模型；提交配置的默认 ID、模型名均为 deepseek-chat，同时注册了 deepseek-reasoner。三轮报告只保存 Provider=deepseek，没有保存具体 modelId/modelName，不能把当前默认值当成三次运行时选项的证明。
- 当前源码参数为 knowledge 最终 Top K=8、相似度阈值=0.25；向量候选数为 min(24, 3×Top K)，即 24，再执行资格过滤和文档多样性选取。本地嵌入为 384 维、多语言 MiniLM ONNX。以上是封板脚本的复现候选参数，不是三轮报告各自记录的完整运行快照。
- 报告 Recall/MRR 只使用前五个分片对应的文档 ID。历史结果未保存完整分片正文、相似度分数、完整模型输入、具体模型名及完整采样参数/运行覆盖项，也没有逐题的人工正确性金标。

历史报告可以用于核对观测值，但不能单独还原一次同配置真实调用。若环境可用，应先在未改动的稳定版本上建立新的真实基线，再做相同条件下的前后比较；本轮尚未具备执行前提。

### 当前复现前置检查（不读取凭据值）

| 检查 | 实际结果 | 能说明什么 |
|---|---|---|
| Java / Maven | Microsoft OpenJDK 17.0.16，Maven 3.9.1，Maven 运行于 Java 17 | 离线构建工具链已确认 |
| 常用模型环境变量 | DEEPSEEK_API_KEY、DASHSCOPE_API_KEY、GOOGLE_GENAI_API_KEY 均未在当前进程中出现；DeepSeek 的 Spring 属性环境变量别名也未出现 | 未确认真实模型接入；未打印或读取变量的凭据值 |
| 额外配置入口 | 当前进程未设置 SPRING_APPLICATION_JSON、SPRING_CONFIG_LOCATION / ADDITIONAL_LOCATION、JAVA_TOOL_OPTIONS、MAVEN_OPTS | 未发现这些入口，但不能据此排除 IDE 或其他外部配置 |
| 本机 TCP 端口 | 127.0.0.1:5432、13306 不可达；3306 可达 | 仅为 TCP 连接探测，未认证、未执行 SQL；不能据此确认数据库身份、向量库可用或远程服务状态 |
| 本地 Tokenizer | 重建后全量单元测试出现 DJL JNI DLL 加载错误 | 本地嵌入依赖也未恢复到此前可用状态 |

封板公开配置的默认数据源分别为 localhost:5432/rag_demo（PostgreSQL）和 localhost:13306/erp（MySQL），这两个端口本次均不可达。当前进程也未设置两个数据源的 URL 环境覆盖项；3306 可达不能当作目标 ERP 数据库已经就绪。

本轮没有读取 `.env`、密码或密钥，没有尝试模型认证请求，故结论是“未能确认可复现的调用条件”，不是“服务商已返回不可用”。没有为绕过限制而读取隐藏配置、切换模型、启动容器或修改数据库。

原 IT 会写入随机评测租户、导入向量并执行本轮数据清理；其输出还会覆盖 `target/rag-eval/` 固定报告。本轮在前置条件未确认时没有启动它，也没有执行任何数据库或 Docker 持久化数据清理。

## 2. 16 条拒答的逐例证据与原因归类

下表来自第三轮备份 JSON 与 v1 原问题。**16 条回答都严格等于“资料不足，无法确认。”，引用均为 0/0，case error 均为空。** 原始模型文本及实际分片正文未保存，不能补造它们。

来源简称：P=product-manual；D=deployment-guide；S=security-guide；O=operations-guide；B=billing-guide。Top 5 保留原顺序与重复文档 ID，重复 ID 对应不同分片而非完整文档副本。

| Case | 原问题 | 预期文档 | 报告 Top 5 文档 | 证据能确认的分类 |
|---|---|---|---|---|
| single-002 | 文档上传支持哪些文件类型？ | P | D,S,P,O,D | P 命中；格式答案分片未知 |
| single-004 | 一次请求的上传大小上限是多少？ | P | P,D,S,D,B | P 命中；550MB 所在分片未知 |
| multi-003 | 真实评测与生产计费和部署环境如何隔离？ | B,D | B,O,B,D | 预期文档齐全；答案分片、跨片覆盖未知 |
| multi-004 | 为什么文档写入中失败不会让半成品成为回答证据？ | O,S | S,O,B,O,S | 预期文档齐全；状态/清理规则分片未知 |
| multi-008 | 如何保证引用不引入第二次检索或额外扣费？ | S,B | S,B,O,B,D | 预期文档齐全；引用/计费答案分片未知 |
| multi-010 | 知识回答资料不足和连接提前结束时各应怎样处理？ | P,O | P,S,B,O,O | 预期文档齐全；跨片覆盖未知 |
| paraphrase-001 | 只传一个附件最多能有多大？ | P | P,S | P 命中；500MB 所在分片未知 |
| paraphrase-003 | 向量表每条向量有多少维？ | D | P | 预期文档未召回 |
| paraphrase-007 | 正在灌向量的新版内容能直接拿来回答吗？ | S,O | S,D,O,P,O | 预期文档齐全；状态规则分片未知 |
| paraphrase-008 | 模型胡写了一个 [99]，后端会认吗？ | S | B | 预期文档未召回 |
| paraphrase-009 | SSE 断了但没见到完成信号，引用还能展示吗？ | O | O,B,S,O,D | O 命中；终止规则分片未知 |
| paraphrase-010 | 文件导入半路报错会伤到线上旧版吗？ | O | S,D,O,O,P | O 命中；旧版本保留规则分片未知 |
| isolation-001 | 跨租户知识库是否允许共享检索结果？ | S | B,S,B,D,D | S 命中；隔离规则分片未知 |
| isolation-002 | 其他知识库的语义相关文档能进入当前回答吗？ | S | S,P,D,O,S | S 命中；knowledge_base_id 规则分片未知 |
| isolation-003 | 处理中版本能否绕过状态校验成为证据？ | S,O | B,S,O,O,S | 预期文档齐全；状态规则分片未知 |
| isolation-004 | 引用能否从当前知识库之外的第二次搜索补造？ | S,B | S,P | 缺少预期 B；S 中相关规则是否进入上下文未知 |

### 已确认的事实与尚不能确认的原因

1. **明确的文档级检索缺失：2 条。** paraphrase-003 的 384 维答案在 D，paraphrase-008 的非法引用处理规则在 S；实际结果各只有一个其他文档。不能要求模型用常识或相近主题补答。
2. **预期文档集合部分缺失：1 条。** isolation-004 缺 B，但 S 已写明“引用只允许来自本轮实际召回证据”。缺少所有预期文档不自动等于无法回答；如果该规则分片确实进入上下文，它仍可能足以支持否定结论。现有报告不能确认。
3. **全部预期文档 ID 命中：13 条。** 只能确认文档覆盖，不能确认答案分片覆盖。比如 P 同时包含问答模式、上传限制及分片规则，召回其中一段不代表 500MB、550MB、文件格式段落也被提供。
4. **没有证据证明引用验证器造成这 16 条文本拒答。** `RagAnswerService.completeFromDocuments` 原样保留传入回答；引用校验器筛选引用列表，不把答案改成固定拒答句。评测拒答分类发生在模型回答之后，也不参与生成决策。引用为 0/0 是输出结果，不能当作原因。
5. **没有证据支持直接降低相似度阈值。** 报告没有候选得分、过滤前结果或实际答案分片，无法区分阈值截断、向量语义偏差、资格过滤及分片边界问题。降低阈值可能增加无关证据，当前不做。

资料足够与否必须根据实际分片判定。数据集层面可回答而被拒答的 16 条，不能全部直接标为“在充分证据下的错误拒答”。

### 最值得验证的两个小问题（未修改）

**优先项一：知识系统提示的技术文档边界。** `KNOWLEDGE_SYSTEM_PROMPT` 禁止“向量库字段或其他内部实现”和“内部过程”，而 fixture 问题需要解释 ent_code、knowledge_base_id、processing / ready、导入补偿和 SSE 规则。这些约束可能混淆文档公开规则与助手自身执行过程。候选最小修复是只澄清知识提示的语义范围，同时继续禁止真实凭据、隐藏运行信息及当前轮内部旁白。

**优先项二：上下文提示的证据支持判断。** `RagContextFormatter` 用“具体对象、数值、日期或步骤未在证据中出现”触发固定拒答，可能把等义表达、跨片综合或明确否定规则解释得过于字面。候选最小修复是说明允许由本轮明确证据支持的等义回答和直接否定结论，并继续要求真实编号与无证据拒答。

以上仅是源码可定位的歧义与待验证假设。没有真实同配置基线和实际分片，不能断言它们就是全部 13 条的真实原因，也不修改分类器去把不合格输出记为成功。

`isolation-005` 另有“固定拒答句 + 原问题复述”，引用为 0/0，报告 refused=false。它没有进入上述 16 条，也不是正确回答；保留此失败，不用“答案非空”将其计入改善。

## 3. 修改清单与尝试次数

| 项目 | 本轮处理 |
|---|---|
| 生产 Java、测试、配置、数据库、Docker 文件 | 未修改 |
| 数据集、知识文档、租户过滤、引用白名单、错误结论校验 | 未修改 |
| 源码/配置优化尝试 | 0/2；先因无法可靠复现而停止 |
| 本地提交 / push | 未执行；不满足“确有改善且无明显回归”的提交条件 |
| 交付 | 新增本文 `docs/rag-optimization-review.md` |

未引入 BM25、Reranker、新模型或 Agent 编排，没有针对测试问题硬编码答案。无需回滚源码；封板版本继续作为稳定版本。

## 4. 优化前后指标对照

“历史基线”是第三轮已保存的真实结果；“本轮优化前/后”没有真实评测，不能填入历史值冒充新复跑，不能写作改善 0 或提升若干百分点。

| 指标 | 历史封板基线 | 本轮优化前真实复跑 | 本轮优化后真实评测 |
|---|---|---|---|
| 可回答问题正确回答数 | 未建立分片级/人工正确性金标，不能确认 | 未执行 | 未执行 |
| 可回答用例标准拒答数 | 16/45；其中 13 条答案分片覆盖未知 | 未执行 | 未执行 |
| 不可回答问题安全拒答 | 5/5 | 未执行 | 未执行 |
| 无依据回答数 | 未逐例作证据级标注，不能声称为 0 | 未执行 | 未执行 |
| 无效引用编号 | 0，48/48 个输出编号有效 | 未执行 | 未执行 |
| 跨租户/知识库违规 | 历史检查为 0 | 未执行 | 未执行 |
| 指定禁止错误结论命中 | 历史检查为 0，不代表全部幻觉为 0 | 未执行 | 未执行 |
| Recall@5 / MRR@5 | 94.44% / 87.59% | 未执行 | 未执行 |
| 关键事实短语命中率 | 53.06% | 未执行 | 未执行 |

辅助核对：历史 45 条可回答用例中 29 条被标记为“非拒答”，但不能把 29 当成正确回答数；其中还有 isolation-005 的复述失败。历史 Judge 相关性与事实检查都通过的有 26 条，关键事实短语全部命中的有 21 条。这两项分别是已存自动 Judge、离线短语代理，也不能冒充人工证据级正确数。本轮没有调用 Judge 或用 Mock 生成新的质量指标。

## 5. 实际 Maven 验证与环境问题

工具链：Microsoft OpenJDK 17.0.16，Maven 3.9.1。均使用 `-o`，没有启用 rag-eval Profile，没有执行真实 RAG IT。

首次相关测试执行 115 项，0 断言失败、5 错误；完整测试首次在发现阶段中止，0 项执行。错误涉及现有 `target` 类文件的 `ClassFormatError: Invalid method Code length 0`，发生在未修改源码的状态。

只将 `target/classes`、`target/test-classes` 移到同目录下的 `classes.pre-rag-optimization-20261015`、`test-classes.pre-rag-optimization-20261015`，重新生成编译产物。移动前验证源和目标都位于本仓库 target 内、不是链接，保留旧编译产物而不删除整个 target；没有触碰历史报告或数据库。

重建后的结果：

| 命令 | 执行数 | 通过 | 断言失败 | 错误 | 跳过 | 结果 |
|---|---:|---:|---:|---:|---:|---|
| `mvn -o test` | 377 | 368 | 0 | 9 | 0 | BUILD FAILURE |
| 下列相关离线回归 | 115 | 115 | 0 | 0 | 0 | BUILD SUCCESS |

```text
mvn -o '-Dtest=AssistantClientProviderTest,AssistantAnswerSanitizerTest,RagContextFormatterTest,RagCitationValidatorTest,RagAdvisorFactoryTest,RagEvaluationRefusalClassifierTest,RagEvaluationAnswerCheckerTest,RagEvaluationMetricsTest,RagEvaluationBuildContractTest' test
```

9 个错误为 DocumentLoaderServiceTest 的 7 项及 EmbeddingModelResourceTest 的 2 项，起因为 DJL Tokenizer JNI 无法加载 Windows 缓存路径下的 `libwinpthread-1.dll`，后续出现初始化失败。记录的是依赖加载错误，不能写成测试全部通过，也没有通过跳过这 9 项、改测试或替换嵌入模型掩盖它们。

日志保存在忽略的运行产物目录：`target/rag-optimization-focused-test.log`、`target/rag-optimization-mvn-test.log`、`target/rag-optimization-rebuilt-mvn-test.log`、`target/rag-optimization-rebuilt-focused-test.log`。离线测试以 Mock/模型桩及本地资源验证代码契约，不提供真实回答质量改善证据。

## 6. 风险、是否保留与停止原因

没有新增源码行为，因此不存在本轮提示词优化带来的新幻觉或引用/租户安全退化的实验结论；也不能把离线通过当成新的真实安全验收。现存类文件与 JNI 环境问题已如实记录，不修改系统级依赖或生产配置。

本轮不保留任何优化源码；只保留诊断报告。先决条件不足时盲改提示词、阈值或拒答分类，会使简历项目失去可信的前后对照，收益无法证明。继续投入完整环境恢复、模型运行元数据补齐和分片级观测已超出本次可验证的最小质量优化路径，因此提前停止，不耗满四小时。

若未来单独重启此任务，应先由使用者恢复原有可用接入与本地嵌入依赖，明确历史实际模型及运行覆盖项，保留现有报告，再在稳定源码上建立带同轮证据的真实基线。只有实际改善且无安全退化时，才值得保留小范围提示词修改；当前不承诺任何指标改善。

P2 继续以 `81bad69` 的实现、已有真实观测及限制收尾。本轮不启动新功能开发，不修改 P1/P3，不做数据库或 Docker 数据清理。

最终文件检查：`git diff --check` 通过，跟踪文件与稳定版本没有差异；新增报告本地链接有效、无行尾空白。三轮备份及当前报告共 8 个 JSON/Markdown 文件经 SHA-256 前后比对均未变化。Git 状态仅有新增的本报告与原有 `test-data/`，未暂存或提交。


---

## 7. 两小时续查：环境修复与历史分片证据恢复

本次续查于 **2026-10-15 15:31:49（北京时间）** 开始，最迟 17:31:49 停止。第 1—6 节保留为上一轮原始记录；本节及后续附录是续查结果，尤其以本节更新原来的“9 项 JNI 错误”和“没有分片正文”状态。

**当前结论：离线测试环境已恢复，真实生成评测环境没有恢复。** 保持 `81bad69` 所有跟踪文件原样，没有进行本轮唯一一次可选业务优化（0/1），没有真实 LLM 调用、数据库连接认证/SQL、数据库初始化、容器或数据卷创建/删除，也没有 Commit/push。仅续写本文，保留原有 `test-data/` 和上一轮编译产物备份。

使用者在续查期间进一步确认：历史 modelId/modelName、temperature、命令行覆盖参数及数据库来源均无法回忆；没有提供当前真实模型凭据，也不准备进行新的付费 API 调用。因此不强行启动完整应用或真实 IT，不把源码默认值填作历史事实。

### 7.1 运行条件：已证实、可推断、UNKNOWN

| 项目 | 恢复结果与证据等级 |
|---|---|
| 正式源码 | 已证实：HEAD=`81bad69`；所有跟踪文件与该版本无差异。源码和配置没有修改。 |
| 历史模型 Provider | 已证实：三份历史报告均为 deepseek。 |
| 历史实际 modelId / modelName | **UNKNOWN**。日志中的 deepseek-chat / deepseek-reasoner 出现在演示数据初始化参数及 SQL 注释，不是实际请求模型的记录。不能据此确认选择。 |
| temperature、top_p、seed、最大生成长度及外部覆盖 | **UNKNOWN**。历史 JSON 和所查日志没有完整请求快照，使用者也未确认。当前源码未显式设置这些采样参数，不代表历史实际值已知。 |
| 第一轮检索运行参数 | 未发现相应真实运行日志；当前源码只能作为候选复现配置，不能反推第一轮参数。 |
| 第二、三轮向量检索参数 | **已证实于 SQL TRACE**：各有 50 次相同作用域的向量查询，余弦距离条件 `< 0.75`（等价于相似度 `> 0.25`），候选 LIMIT=24。第二轮日志为上级目录 `rag-eval-baselines/phase1-eval.log`，第三轮为 `prompt-fix-eval-20261010/eval.log`。 |
| 最终 Top K、资格过滤、拼接顺序 | 当前源码 Top K=8，检查 ready 身份、文档多样性选取；Spring AI 默认 Joiner 再按 score 降序。按这条链重建后的报告 Top 5 与第三轮 50/50 一致，属于有交叉验证的重建，非原始上下文快照。 |
| Embedding | 第三轮写入元数据证实 `paraphrase-multilingual-MiniLM-L12-v2@e8f8c211-avx2-int8`；11 个目标分片均为 384 维。当前资源摘要与本地 ONNX/Tokenizer 测试通过。其他历史轮次不能仅因同一仓库就认定所有资源一致。 |
| 数据集与来源 | v1 50 条；五份目标 TXT、两个干扰知识库分片。第三轮日志恢复 11 个目标分片和 2 个干扰分片，保留正文、chunk ID、文档身份及写入向量。 |
| 提示词版本 | 当前正式源码可由 Git 确认；已有 patch 可辅助检查修复过程，但三轮均未保存实际发送的系统/增强提示词或构建指纹，**精确运行时版本 UNKNOWN**。 |
| 历史数据库来源 | **UNKNOWN**；不能把 Docker 配置存在等同于当时使用 Docker。第三轮日志证明当时向量表可访问、完成过导入和清理；不能据此确认现在仍有那批数据。 |
| 本次模型可访问性 | 当前进程常用 Provider 凭据入口未设置；没有读取 `.env` 或尝试认证/付费调用。按使用者确认，真实模型条件未具备，服务端可访问性 **UNKNOWN**。 |

历史 16 条标准拒答的文本、0/0 引用及 error=null 均来自保存的第三轮 JSON。**原始模型输出、完整请求消息、当时的最终上下文全文及逐条原生检索结果行没有保存，仍为 UNKNOWN。** 后处理后的 answer 不能冒充 rawText。

### 7.2 Tokenizer：真实原因与环境恢复

默认终端 `JAVA_HOME=E:\develop\JDK`，`java` 与 Maven 默认实际使用 Oracle JDK **25.0.4.1**。本次所有测试均显式切换为 Microsoft OpenJDK **17.0.16**（`%USERPROFILE%\.jdks\ms-17.0.16`），Maven **3.9.1**；Surefire 运行属性证实 `os.arch=amd64`、`sun.arch.data.model=64`。不能把默认终端的 JDK 25 与这些 Java 17 测试混为一谈。

实际测试 classpath 来自 `D:\java\ali_repo`：Spring AI Transformers **2.0.0** → DJL API / model-zoo / HuggingFace tokenizers **0.32.0**，ONNX Runtime **1.20.0**。tokenizers 的原生资源版本为 **0.21.0-0.32.0**。通过 Surefire classpath、缓存 POM 和实际 JAR 反汇编核对；离线 `dependency:tree` 因 dependency-plugin 未缓存而失败，没有为此联网下载依赖。另一个 `.m2` 下同版本 Tokenizer JAR 与实际使用的 JAR SHA-256 相同，没有发现两份包内容冲突。

**直接故障：原缓存的最终目录已经存在但为空，DJL 解包后的目录移动失败被忽略，随后绝对路径加载不存在的 DLL。**

1. 原报错目录 `%USERPROFILE%\AppData\Local\Temp\.djl.ai\tokenizers\0.21.0-0.32.0-cpu-win-x86_64\120` 存在、不是链接，但包含 0 个文件。原路径复跑 9 项仍全部错误。
2. 本地 JAR 完整包含四个 DLL。`LibUtils.copyJniLibraryFromClasspath` 先将四个 DLL 解到临时目录，再调用 DJL `Utils.moveQuietly` 以 `ATOMIC_MOVE` 移到最终目录；该方法在目标已经存在时吞掉 IOException，随后删除临时目录，返回一个仍然空的目标目录。
3. 在仓库 target 下用 Java 17 做相同的“非空源目录 → 已存在空目标目录”原子移动探针，实际得到 `java.nio.file.AccessDeniedException`，目标依旧存在且文件数为 0。这证实当前 Windows 下该移动/忽略机制可以导致本次现象；**最初是谁、何时创建了原空目录 UNKNOWN**，没有证据指认杀毒软件、某次 JDK 升级或缓存清理程序。
4. 仅对测试进程设置 `ENGINE_CACHE_DIR` 到仓库内全新可写路径，DJL 自动从原有 JAR 解出四个 DLL；9 项全部通过，随后全量 377 项全部通过。未修改 POM、源码、测试或系统环境变量，未跳过测试，未替换嵌入模型。
5. 四个 DLL 均为 PE Machine `0x8664`（AMD64），与 JVM 匹配；每个文件的 SHA-256 与 JAR 对应条目完全一致。通过新缓存实际加载和本地嵌入运算，未发现缺少 VC/MinGW 运行依赖的证据。这里使用 `System.load(绝对路径)`；盲目追加 PATH 不能修复一个根本不存在的 DLL。

| DLL | 实际字节数 | 与 JAR 条目一致 |
|---|---:|---|
| libwinpthread-1.dll | 56,978 | 是 |
| libgcc_s_seh-1.dll | 511,408 | 是 |
| libstdc++-6.dll | 2,011,975 | 是 |
| tokenizers.dll | 11,447,808 | 是 |

原始首个异常堆栈（原缓存复跑；本机用户目录已用 `%USERPROFILE%` 脱敏，异常类型与调用帧保留）：

```text
java.lang.UnsatisfiedLinkError: Can't load library: %USERPROFILE%\AppData\Local\Temp\.djl.ai\tokenizers\0.21.0-0.32.0-cpu-win-x86_64\120\libwinpthread-1.dll
    at java.base/java.lang.ClassLoader.loadLibrary(ClassLoader.java:2398)
    at java.base/java.lang.Runtime.load0(Runtime.java:755)
    at java.base/java.lang.System.load(System.java:1970)
    at ai.djl.huggingface.tokenizers.jni.LibUtils.loadLibrary(LibUtils.java:98)
    at ai.djl.huggingface.tokenizers.jni.LibUtils.<clinit>(LibUtils.java:54)
    at ai.djl.huggingface.tokenizers.HuggingFaceTokenizer.newInstance(HuggingFaceTokenizer.java:186)
    at com.example.rag.chat.DocumentLoaderService.createEmbeddingTokenizer(DocumentLoaderService.java:325)
    at com.example.rag.chat.DocumentLoaderService.<init>(DocumentLoaderService.java:86)
    at com.example.rag.chat.DocumentLoaderServiceTest.shouldAttachManagedDocumentEvidenceMetadata(DocumentLoaderServiceTest.java:157)
```

其余错误为同一静态初始化失败的连带异常：

```text
java.lang.NoClassDefFoundError: Could not initialize class ai.djl.huggingface.tokenizers.jni.LibUtils
Caused by: java.lang.ExceptionInInitializerError: Exception java.lang.UnsatisfiedLinkError: Can't load library: %USERPROFILE%\AppData\Local\Temp\.djl.ai\tokenizers\0.21.0-0.32.0-cpu-win-x86_64\120\libwinpthread-1.dll [in thread "main"]
```

完整原始日志保留在 `target/rag-diagnosis-original-cache-tests.log`，没有被后续成功测试覆盖。具体依赖 JNI 的 9 项为：

- `DocumentLoaderServiceTest`：`shouldAttachManagedDocumentEvidenceMetadata`、`shouldCleanManagedVersionWhenBatchWriteFails`、`shouldCleanPartialSourceWhenBatchWriteFails`、`shouldExtractShortUtf8TextFromManagedFile`、`shouldKeepLongDocumentChunksWithinEmbeddingModelLimit`、`shouldRejectTextBeyondExtractionBudget`、`shouldReplaceExistingSourceBeforeBatchWrite`。构造服务会创建真实本地 WordPiece Tokenizer；VectorStore 使用 Mock，不操作真实数据库。
- `EmbeddingModelResourceTest`：`shouldLoadPinnedMultilingualEmbeddingResources`、`shouldEmbedChineseContentWithExpectedSemanticOrder`。分别实际加载 Tokenizer/ONNX 和执行本地中文嵌入；没有调用远程 LLM。

保留的可用测试方法（PowerShell，在仓库根目录执行；变量仅影响当前终端及其子进程，JDK 路径按本机安装位置调整）：

```powershell
$env:JAVA_HOME=(Join-Path $env:USERPROFILE '.jdks\ms-17.0.16')
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:ENGINE_CACHE_DIR=(Join-Path (Get-Location) 'target\rag-diagnosis-native-cache')
$env:DJL_OFFLINE='true'
java -version
mvn -version
mvn -o '-Dtest=DocumentLoaderServiceTest,EmbeddingModelResourceTest' test
mvn -o test
```

**环境修复的边界**：可用 DLL 缓存已保留在上述 target 目录，原外部空缓存未删除、未移动或覆盖。新终端若仍使用 JDK 25 或不指定缓存，不能保证默认命令就能复现成功；这是一项可重复的局部环境恢复，不是系统级永久修复。`mvn clean` 会删除 target 缓存，本轮没有执行 clean。

### 7.3 Docker、数据库和真实模型状态

- Compose 文件定义 pgvector/pgvector:pg16（容器名 pgvector，5432，卷 pgdata）、mysql:8.0（mysql-erp，13306→3306，卷 mysqldata）及应用 rag-demo。Dockerfile 使用 Java 17 构建/运行镜像。这些是仓库声明，不是当前容器存在和运行的证明。
- 当前 Docker context 为 desktop-linux；Docker API 管道 `dockerDesktopLinuxEngine` 不存在，Desktop status 无法取得运行状态，未发现 Docker 进程。Desktop CLI 还报告其宿主日志目录写入 Access denied。**已证实客户端无法连接引擎；不能据此声称数据卷丢失或数据库损坏。**
- 127.0.0.1:5432、13306 TCP 不可达；3306 可达，但身份、用途、账户及数据均 UNKNOWN，没有连接认证，也未操作它。当前默认地址与 Compose 端口一致；没有证据证明发生了端口变更。
- 引擎未运行，无法只读核验既有容器标签、挂载及数据状态；按使用者最新停止指示，没有启动 Docker Desktop、应用或新 Compose 资源，没有初始化/覆盖知识库。原有 P2 数据库是否来自 Docker 保持 UNKNOWN。
- 没有当前模型凭据和付费调用授权；未探测模型认证接口，未读取 `.env`、密码或密钥。**真实生成评测未恢复**，即使后续数据库恢复也仍不能还原历史模型/采样条件。

### 7.4 从 SQL 日志恢复历史证据的方法与可信边界

第三轮日志原文件为 UTF-16。仅解析 `public.vector_store` 的 INSERT 与向量 SELECT 参数，不导出其他业务表初始化参数。恢复：

- 13 条 INSERT 分片（目标来源 11 条，跨库/跨租户干扰各 1 条）的正文、向量、chunk ID 和身份元数据。
- 50 次 SELECT 的查询向量、0.75 距离上限和 24 候选上限；按 IT 顺序关联 50 条 v1 case。
- 对日志原有向量做离线余弦计算，按租户/知识库/模型作用域排除干扰，模拟已导入 ready 文档的资格校验、文档多样性选取、Top 8，再模拟 Spring AI 2.0.0 默认 `ConcatenationDocumentJoiner` 按 score 重新排序。
- 与独立保存的第三轮报告交叉核验，**50/50 条 Top 5 文档序列完全一致**。这里的 50/50 是历史检索重建的一致性检查，**不是新的回答正确率、不是新的 Recall 指标，也不是一次真实 LLM 评测**。

最初单看多样性排序只匹配 21/50；进一步反汇编实际依赖确认默认 Joiner 会再排序，补齐后匹配 50/50。因此撤销“可能源码与历史检索不一致”的中间猜测，没有据此改代码。

仍需保留限制：日志未保存 SQL 返回行、HNSW 实际遍历路径、完整最终上下文及原始模型回答。重算得分是从已保存浮点向量计算的余弦，不冒充数据库当时记录的 score。前五序列一致是较强交叉证据，不能数学上证明所有第六至八条分片、顺序及实际模型输入逐字一致。

可审查运行产物保留于 `target/rag-diagnosis-reconstructed-evidence.json`，包含重建候选/选取上下文、真实 INSERT chunk ID/正文、查询日志行号；脚本为 `target/rag-diagnosis-log-evidence.py`。它们是忽略的本地诊断产物，不是生产模块，也未提交。后续附录将关键证据写入本文，避免只依赖临时产物。

### 7.5 16 条拒答：更新后的分类

A=关键证据未召回；B=只召回部分证据；C=证据充分但回答策略错误拒答；D=引用/后处理造成拒答；E=证据不足以确认因果。下列 **C 候选** 仅表示“重建上下文足够支持回答”，不是已经证实真实模型内部原因。依据日志重建的证据覆盖工作分类为 **A 3、B 1、E 12；已证实 C/D 为 0**；E 中 11 条可优先调查回答策略，1 条有问题/金标范围歧义。

来源简称：P、D、S、O、B 沿用上文。分片编号从 0 开始；例如 P1 是 product-manual 的第 2 个分片，不是引用编号 `[1]`。实际回答均为固定拒答句、引用均 0/0，原始模型输出及具体拒答理由均 UNKNOWN。

| Case | 分类与证据完整度 | 根因判断及不能越过的边界 |
|---|---|---|
| single-002 | A；重建证据高，最终输入 UNKNOWN | 只选到 P1，没有含 PDF/Word/Excel/TXT/HTML/RTF 的 P0。P0 重算相似度 0.161364，低于 0.25。属于“文档 ID 命中而答案分片缺失”，不能靠放宽拒答来补造格式。 |
| single-004 | E / C 候选 | P1 明确“整次请求上限为 550MB”，重算 0.6178。答案不依赖缺失的前半句，固定拒答不符合重建证据；实际提示词/模型策略未恢复。 |
| multi-003 | E；问题/金标歧义 | B1 和 D0 覆盖四项预期事实，但文档仅说明评测不扣余额、部署依赖，未完整说明“部署环境如何隔离”。不能仅凭金标词都出现，就保证整道问题有充分答案。 |
| multi-004 | E / C 候选 | O0 的 processing→ready、失败精确清理及 S0/S1 的资格限制均具备。问题要求解释公开文档规则，可能被“内部实现/过程”禁令误伤；未证明模型实际走该理由。 |
| multi-008 | E / C 候选 | S1 写明仅本轮证据，B0 写明引用校验不调用模型、不额外计费；可据此回答，引用与计费链无需推测。 |
| multi-010 | E / C 候选 | P0 的资料不足规则和 O1 的未收到 done 丢弃未确认引用/图表齐全；可能混淆“解释拒答规则”与“本题证据不足”。 |
| paraphrase-001 | B；句子边界截断 | P1 以“限为 500MB”开头，“单文件上”在未召回 P0。数值存在但对象被切断，不能确信模型能安全把 500MB 绑定为单文件上限。 |
| paraphrase-003 | A | 384 在 D0，重算相似度 0.174296；超过距离上限而未选取。实际仅 P1，与维数无关。 |
| paraphrase-007 | E / C 候选 | S0/S1 和 O0 共同提供 processing 禁入与全部写入后晋级 ready，足以给出否定结论。 |
| paraphrase-008 | A | 非法编号丢弃规则在 S1，重算相似度 0.207599；只选到 B0。用户 `[99]` 改写在增强提示阶段，检索使用原问题；没有记录支持将改写定为检索失败原因。 |
| paraphrase-009 | E / C 候选 | O1 明确连接关闭前没收到 done 必须丢弃未确认引用和图表；同义问法无需常识补答。 |
| paraphrase-010 | E / C 候选 | O0/O1 两片合起来给出旧 ready 版本保持可检索，失败清理只针对新版本。单句跨片增加理解成本，但两端均在重建上下文。 |
| isolation-001 | E / C 候选 | S0 明确 ent_code 隔离、跨租户对象视为不存在，能支持“不允许”。技术字段禁令是优先验证的提示词歧义。 |
| isolation-002 | E / C 候选 | S0 的 knowledge_base_id 限定及 S1 连续规则存在，能支持“其他库不能进入当前回答”。 |
| isolation-003 | E / C 候选 | S0/S1 的 processing 禁入及 O0 的状态规则同时存在，支持“不能绕过”。 |
| isolation-004 | E / C 候选 | B 未召回，但 S1 “引用只允许来自本轮实际召回证据”已足以否定跨库第二次补造。**缺少预期 B 不等于本题证据不足**，修正上一轮只能停在文档级的分类。 |

因此，13 条“预期文档齐全”的病例现在能进一步区分：single-002 答案片段实质缺失、paraphrase-001 主语被切断、multi-003 问题范围歧义、其余 10 条证据充分候选；加上 isolation-004，总计 11 条 C 候选。不能把这 11 条当作新确认的模型错误率，也不能把检索重建等同于恢复了真实生成环境。

### 7.6 提示词、引用/后处理与 isolation-005

**有证据支持的提示词歧义，但尚无真实 A/B 因果验证：**

- `AssistantClientProvider.KNOWLEDGE_SYSTEM_PROMPT` 一方面要求依据上传文档回答，另一方面笼统禁止“向量库字段或其他内部实现”“内部过程、分析”。本评测正好询问公开文档中的 ent_code、knowledge_base_id、文档状态、导入补偿、引用和 SSE 规则；范围禁令可能把合法文档解释也包含进去。
- `RagContextFormatter` 要求问题所需具体对象/数值/步骤未出现就立即固定拒答，没有澄清允许证据支持的同义表达、跨片组合以及明确禁止规则的直接否定回答。这能解释部分策略倾向，但不能解释为“所有资料充分拒答都由该句造成”。
- `multi-010` 要求解释“资料不足时怎么办”，P0 本身也包含拒答相关措辞；模型可能把材料中的规则当成当前题目的执行指令。原始输出和注意力不可恢复，此处仍是假设。

**没有发现程序引用校验将这 16 条变成拒答的路径。** `RagAnswerService.completeFromDocuments` 保留传入回答，引用验证器只筛选引用快照；拒答分类器在回答生成之后打标签，不影响模型决策。净化器可删文本但没有将普通答案改写为固定拒答句的业务分支。因为 rawText 没保存，仍不能逐题排除模型输出中被删去的内容，D 类不得臆测确认。

`isolation-005` 不在这 16 条中。历史最终 answer 为“资料不足，无法确认。”加空行和原问题复述，引用 0/0，refused=false。日志重建的 S1 已明确“旧格式向量不能用于检索或引用，必须……重新导入”；有答案证据。因此它是另一个有依据却未正常回答的候选，同时违反“固定拒答后立即结束”协议。分类器拒绝将夹带原问题的非标准回答计作安全拒答是合理的，不能为抬高拒答率修改标签。没有原始模型输出，不能确认复述是由模型自发产生还是净化保留了某段；源码中未发现后端主动拼回原问题的回答分支。

### 7.7 优化决定、实际测试与停止条件

没有建立新的真实生成基线，**本轮业务优化尝试 0/1**。不进行候选提示词修改，不降低阈值，不改分片规则、租户过滤、引用白名单、拒答分类或错误结论检查。没有无效业务改动需要回滚。

| 本次验证 | 实际执行 | 通过 | 断言失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|---:|
| Java 17，原缓存，两个 JNI 相关测试类 | 9 | 0 | 0 | 9 | 0 |
| Java 17，独立可写缓存，同两个测试类 | 9 | 9 | 0 | 0 | 0 |
| Java 17，独立可写缓存，`mvn -o test` | 377 | 377 | 0 | 0 | 0 |

9 项相关测试包含实际本地 Tokenizer/ONNX 运算；377 项涵盖提示词、净化器、引用、隔离、评测工具及 ERP Tool 的既有回归。没有 rag-eval Profile、没有真实 LLM/Judge、没有真实数据库访问。成功日志为 `target/rag-diagnosis-isolated-cache-tests.log`、`target/rag-diagnosis-full-tests.log`。

| RAG 质量指标 | 第三轮历史观测 | 本轮新基线 / 优化后 |
|---|---|---|
| 可回答正确回答数 | 没有人工证据级金标，UNKNOWN | 未评测 / 未评测 |
| 可回答标准拒答 | 16/45；按上表重新诊断，不能全部判成同一种错误 | 未评测 / 未评测 |
| 不可回答安全拒答 | 5/5 | 未评测 / 未评测 |
| 无依据回答 | 未完整人工标注，UNKNOWN | 未评测 / 未评测 |
| 无效引用编号 | 0；48/48 有效，不保证每个结论有依据 | 未评测 / 未评测 |
| Recall@5 / MRR@5 | 94.44% / 87.59% | 没有新增真实检索评测 |
| 关键事实短语命中率 | 53.06% | 未评测 / 未评测 |

**可以保留：** 本文、仓库 target 内的新 DJL 缓存和无凭据诊断产物；Java 17 的进程级启动方法。**没有源码优化可保留，也没有可信的 RAG 质量改善结论。** 离线全绿是一项有效环境恢复，不是回答质量提升。

后续若另行恢复真实对照，至少需要：明确授权且可用的固定模型及采样参数；只读核验过的既有 P2 数据库/挂载与安全连接；同一源码/构建和数据摘要；新运行独立报告目录；逐题最终分片 ID/正文/顺序、检索得分及安全的模型运行元数据快照。历史完整输入和原始输出若从未留存，数据库启动也不能自动找回，原 IT 已完成本轮清理。

届时最小候选是仅澄清知识提示中“内部实现”的保护对象是助手隐藏运行信息，而非用户上传文档已公开的技术规则；必要时再澄清上下文允许有直接证据的同义和跨片回答。必须继续禁止编造并要求真实引用。single-002 / paraphrase-003 / paraphrase-008 是证据检索问题，不能靠放松回答决策修复；本轮没有试做上述任何改动。

停止理由：使用者确认历史运行条件无法补齐且无新付费调用计划，触发“无法可靠复现”条件；继续修改无法建立可信收益对照。预算为上限，不为消耗剩余时间扩大开发。P2 正式源码继续保持稳定版本，P1/P3 未触碰。

### 附录 A：逐题问题、金标事实、报告文档与重建分片

下表“报告文档”是实际历史 JSON，“重建分片”是离线恢复后模拟链路的选取结果，两列不能混称原始模型输入。分片缩写可在附录 B 查到真实 chunk ID 和日志写入正文；所有 16 条均为固定拒答且无引用，原始提示词、rawText 和真实逐题拒答理由为 UNKNOWN。

| Case / 原问题 | 预期关键事实 | 历史报告文档 Top 5 | 重建选取分片（至多 8 条） |
|---|---|---|---|
| single-002：文档上传支持哪些文件类型？ | PDF；Word；Excel；TXT；HTML；RTF | D,S,P,O,D | D1,S1,P1,O0,D0 |
| single-004：一次请求的上传大小上限是多少？ | 550MB | P,D,S,D,B | P1,D0,S2,D1,B0,S1,O1,B1 |
| multi-003：真实评测与生产计费和部署环境如何隔离？ | 不经过租户余额扣减；MySQL；PgVector；模型密钥 | B,O,B,D | B0,O0,B1,D0 |
| multi-004：为什么文档写入中失败不会让半成品成为回答证据？ | processing；ready；精确清理 | S,O,B,O,S | S1,O1,B0,O0,S0,B1,D1,S2 |
| multi-008：如何保证引用不引入第二次检索或额外扣费？ | 本轮实际召回；不调用模型；不会触发额外计费 | S,B,O,B,D | S1,B0,O1,B1,D1,O0,S2,P1 |
| multi-010：知识回答资料不足和连接提前结束时各应怎样处理？ | 明确说明资料不足；丢弃尚未确认的引用和图表 | P,S,B,O,O | P0,S0,B0,O1,O0,B1,D1,D0 |
| paraphrase-001：只传一个附件最多能有多大？ | 500MB | P,S | P1,S2 |
| paraphrase-003：向量表每条向量有多少维？ | 384 | P | P1 |
| paraphrase-007：正在灌向量的新版内容能直接拿来回答吗？ | 不能；processing | S,D,O,P,O | S1,D1,O0,P1,O1,B0,S0 |
| paraphrase-008：模型胡写了一个 [99]，后端会认吗？ | 丢弃 | B | B0 |
| paraphrase-009：SSE 断了但没见到完成信号，引用还能展示吗？ | 不能；丢弃 | O,B,S,O,D | O1,B0,S1,O0,D1 |
| paraphrase-010：文件导入半路报错会伤到线上旧版吗？ | 不会；上一 ready 版本保持可检索 | S,D,O,O,P | S1,D1,O0,O1,P1,B1,S0,B0 |
| isolation-001：跨租户知识库是否允许共享检索结果？ | 不允许；ent_code 隔离 | B,S,B,D,D | B0,S0,B1,D0,D1,P0,O0 |
| isolation-002：其他知识库的语义相关文档能进入当前回答吗？ | 不能；knowledge_base_id | S,P,D,O,S | S1,P0,D1,O0,S0 |
| isolation-003：处理中版本能否绕过状态校验成为证据？ | 不能；processing | B,S,O,O,S | B0,S1,O0,O1,S0,D1 |
| isolation-004：引用能否从当前知识库之外的第二次搜索补造？ | 不能；本轮实际召回 | S,P | S1,P0 |

### 附录 B：恢复出的原始写入分片证据

以下正文取自第三轮 INSERT 参数，并非重新生成或从完整文档随意截取。保留跨片断句，供核对证据不完整的问题。每段的 chunk ID 均来自同一条历史元数据。

**B0 — billing-guide.txt**，chunk ID `47be7b14-4592-4b4b-821b-9566fde01b1f`（INSERT 语句从历史 eval.log 第 2031 行开始，同批多分片共享语句）。

> 模型调用前会检查租户额度，模型调用后按实际可观测 Token 扣费一次。消息持久化与计费事务相互独立，计费失败不会回滚已经保存的助手消息。
>
> 流式回答发生取消时，只按已经观测到的 usage 最多结算一次。引用校验不调用模型，也不会触发额外计费

**B1 — billing-guide.txt**，chunk ID `324d84db-069f-4785-815d-a759e3886f6e`（INSERT 语句从历史 eval.log 第 2031 行开始，同批多分片共享语句）。

> 。真实 RAG 评测不创建用户会话、不写入聊天历史，并且不经过租户余额扣减。
>
> 计费报告保留输入 Token 和输出 Token。Provider 单价缺失时，估算成本必须标记为不可用，不能显示为零成本。

**D0 — deployment-guide.txt**，chunk ID `a1dd380e-d3b9-42fe-bda1-b6e09c145681`（INSERT 语句从历史 eval.log 第 2056 行开始，同批多分片共享语句）。

> 系统使用两个数据源。MySQL 保存租户、知识库、文档版本、会话和计费数据；PostgreSQL PgVector 保存文档向量。PgVector 使用 384 维向量、HNSW 索引和余弦距离。
>
> 部署前必须准备 MySQL、PostgreSQL 的 vector 扩展以及至少一个可用模型密钥。服务默认端口为 8080。4.0.0 发布时前

**D1 — deployment-guide.txt**，chunk ID `973fc27f-23c8-4346-9d00-f235dc66a6c3`（INSERT 语句从历史 eval.log 第 2056 行开始，同批多分片共享语句）。

> 后端必须同步发布，并递增静态资源缓存版本。
>
> 回滚应用时保留新增知识库表和消息引用字段，不执行破坏性 DDL。旧向量缺少稳定文档身份，需要在升级后通过预置加载或文件上传重新导入。

**O0 — operations-guide.txt**，chunk ID `f930c536-b475-4227-b126-3c18fd10836f`（INSERT 语句从历史 eval.log 第 2081 行开始，同批多分片共享语句）。

> 文档导入使用 processing、ready、failed 三阶段。提取和向量写入在 MySQL 本地事务外执行，全部写入成功后才把新版本晋级为 ready，并把上一 ready 版本标记为 superseded。
>
> 导入中途失败时，系统按租户、知识库、文档和版本精确清理新版本向量，并把登记版本标记为 failed。上一 ready 版本保持可检

**O1 — operations-guide.txt**，chunk ID `1d8cfd7a-8a00-4c78-9213-e34a6b6d7aed`（INSERT 语句从历史 eval.log 第 2081 行开始，同批多分片共享语句）。

> 索。删除稳定文档时会软删除全部版本并尽力清理对应向量。
>
> 流式成功事件顺序为零个或多个 delta、可选 citations、可选 chart、最后一个 done。异常或取消不能发布引用、图表或成功 done。客户端在连接关闭前没有收到 done 时，必须丢弃尚未确认的引用和图表。

**P0 — product-manual.txt**，chunk ID `446bbf4c-7ac4-45f3-a84e-54c27f27ffac`（INSERT 语句从历史 eval.log 第 2106 行开始，同批多分片共享语句）。

> Spring AI RAG Demo 提供 auto、data 和 knowledge 三种问答模式。knowledge 模式只使用知识库检索，不注册 ERP 业务工具。知识回答的证据通过方括号编号，例如 [1]。资料不足时，助手应明确说明当前知识库资料不足，不得编造答案。
>
> 文档上传支持 PDF、Word、Excel、TXT、HTML 和 RTF。单文件上

**P1 — product-manual.txt**，chunk ID `7f24e910-f0ef-40d1-9312-0d8fd2b907f2`（INSERT 语句从历史 eval.log 第 2106 行开始，同批多分片共享语句）。

> 限为 500MB，整次请求上限为 550MB。提取后的文本最多 500 万字符，受管文档分片会写入稳定文档 ID、版本、分片 ID 和分片顺序。
>
> 文档会按 token 边界二次切分，单个分片最多 128 个 WordPiece token。向量写入按批执行，每批最多 100 个分片。

**S0 — security-guide.txt**，chunk ID `f981d498-3ce7-44ab-a027-ea007ec200d9`（INSERT 语句从历史 eval.log 第 2131 行开始，同批多分片共享语句）。

> 所有知识库、文档版本和向量必须按 ent_code 隔离。向量检索还必须同时限定 knowledge_base_id。跨租户知识库统一表现为不存在，不能泄漏对象状态。
>
> 受管检索只接受 MySQL 中当前状态为 ready 的文档版本。processing、failed、superseded、deleted 版本和缺少稳定身份的旧格式向量都不能进入模型上

**S1 — security-guide.txt**，chunk ID `6d51836c-ee7f-46c9-9101-554599287d4d`（INSERT 语句从历史 eval.log 第 2131 行开始，同批多分片共享语句）。

> 下文或引用。
>
> 4.0.0 中旧格式向量不能用于检索或引用，必须通过预置加载或文件上传重新导入后才能成为受管证据。
>
> 引用只允许来自本轮实际召回证据。伪造、越界或不属于本轮召回的编号会被丢弃。每轮最多八条引用，单条摘要最多

**S2 — security-guide.txt**，chunk ID `891f053d-750e-4e7e-b62e-e410d0fb27a2`（INSERT 语句从历史 eval.log 第 2131 行开始，同批多分片共享语句）。

> 五百字符，引用 JSON 不超过 32 KiB。前端使用文本节点展示来源和摘要，不能执行其中的标签、链接或脚本。

### 7.8 最终完整性检查与交付

收尾核验于北京时间 **15:49:03** 完成，距本次开始约 **17 分 14 秒**；按无法真实复现的停止条件提前结束，没有消耗剩余时间扩大范围。

`git diff --check` 退出 0；另外对未跟踪报告执行 no-index 空白检查，没有空白错误输出（新文件差异退出码为 1）。本文的原始 17,067 字节前缀 SHA-256 核验一致，上一轮内容完整保留；本轮仅追加诊断。三轮备份及当前 target 报告共 8 个 JSON/Markdown 与原摘要核验一致，本地文档链接有效。所有跟踪文件与 `81bad69` 无差异，`git diff --stat` 为空；因为本报告原本就未跟踪，其全文不会显示在常规 diff 中。

最终 Git 状态仅为：

```text
?? docs/rag-optimization-review.md
?? test-data/
```

本轮没有暂存、提交或 push。`test-data/` 未读取、未修改、未删除。停止本轮开发，等待人工审核。

## 8. 最终文档与 Git 收尾

本次只审核文档、只读盘点未跟踪数据并建立独立本地文档提交，不重新运行测试、环境诊断或真实评测。377/377 为上一轮已执行的 Java 17 离线结果；50/50 是历史检索重建一致性，不是新问答正确率。11 条策略候选问题仍缺真实模型对照，保持未验证结论。

### test-data 盘点与处理

| 文件 | 大小 / 格式 | 来源与用途 | 敏感性与处理 |
|---|---|---|---|
| `test-data/rag-test.txt` | 303 字节，UTF-8，11 行 | 内容呈 RAG 手工测试材料形式，包含供应商交期、最小采购量和产品保修等测试事实。Git 历史无该路径记录，已检查的跟踪文件没有引用；创建者、生成方式、事实是否虚构均 UNKNOWN，不能称为正式 v1 评测数据或必需测试依赖。 | 含口令样式字段和内部编号；未发现常见 Provider Key、私钥、邮箱或手机号格式，但无法确认字段真实性，按潜在敏感材料处理。不公开这些值、不纳入提交，不删除、覆盖、移动文件。 |

文件 SHA-256：`ba7c9f2bc8ecf4707ac9d23d4127fcc348b55f9f4e5210695f919456553ea363`。只读盘点前后核验一致。没有将 test-data 整体加入 Git，也没有调整忽略规则来隐藏它；保留未跟踪状态即可，不需要为清洁工作区删除用户数据。

### 文档安全与提交范围

对待提交文档检查常见 API Token、私钥、凭据赋值、带密码 URL、个人联系方式及内网地址格式，并人工审核上下文；未发现真实凭据或应排除的内部业务数据。API Key 环境变量名称、公开代码字段名和测试文档中的“模型密钥”文字是说明，不是凭据值。附录正文来自仓库 v1 测试 fixture，chunk ID 是评测随机标识；没有加入用户 test-data 正文、真实租户记录或完整原始运行日志。检查是有限范围审核，不能等同于通用秘密检测保证。

本机用户名目录改为 `%USERPROFILE%`，示例缓存路径改为从仓库根目录计算；异常堆栈明确标注路径脱敏。保留历史未知项、因果限制、JNI 缓存修复边界及未做真实质量对照的结论，不宣称生产级质量或指标提升。

仅暂存并提交 `docs/rag-optimization-review.md`，提交前检查暂存清单和 `git diff --cached --check`。业务 Java、测试、配置、数据库和 Docker 文件与 `81bad69` 无差异，历史评测报告未覆盖；没有操作 P1/P3、数据库、容器或模型接口，不 push。提交后剩余未跟踪项应只有 `test-data/`，这符合保留用户数据的收尾要求。P2 的全部开发、质量优化和环境排查至此结束。
