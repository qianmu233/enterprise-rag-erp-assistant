# P2 RAG 质量比较：已知与未测

日期：2026-10-21（Asia/Shanghai）。诊断分支 `diagnosis/p2-rag-quality` 基于稳定提交 `867d1af65c44f7bd3cea9182ea72456b46d7f00d`。本次没有改生产代码，没有真实模型/数据库评测，没有修改历史 JSON、用例或断言。

**尚无新模型质量改善结论。** 第三轮结果早于稳定版的知识提示词澄清，且没有实际模型名/采样参数，不能当作稳定版的新运行基线。下表禁止将历史数据复制到“修复后”列充当结果。

| 指标 | 历史第三轮（2026-10-10） | 稳定版 867d1af 同条件实测 | 本诊断分支同条件实测 |
|---|---:|---|---|
| 50 条完整生成/Judge | 50 条 | 未执行 | 未执行 |
| 文档 Recall@5 | 94.44% | 未测 | 未测 |
| 文档 MRR@5 | 87.59% | 未测 | 未测 |
| 关键事实短语 | 52/98，53.06% | 未测 | 未测 |
| 无答案安全拒答 | 5/5 | 未测 | 未测 |
| 可回答标准拒答 | 16/45 | 未测 | 未测 |
| 结构引用有效 | 48/48 | 未测 | 未测 |
| 语义正确率/逐句引用支持 | 无完整人工金标 | 未测 | 未测 |

## 本轮可以比较什么？

1. 对同一份历史 JSON 和用例独立重算指标，与原 summary 核对；这是统计审计，不是新评测。
2. 对既有日志重建产物与历史 Top5 文档序列核对；这是重建一致性，不是新检索提升或 HNSW 运行实录。
3. 对当前拒答分类器用确定性文本探针核验正常否定、固定拒答、解释拒答规则、混合猜测和问题复述；这是代码边界测试，不是真实模型生成。
4. 用既有 RAG、隔离、版本、引用、净化、Tool Calling 和 SSE 测试检查局部非回归；Mock 不证明数据库真实状态或供应商效果。

## 本轮实际自动化结果

Microsoft OpenJDK 17.0.16，离线执行以下命令，使用本次独立的进程级 `ENGINE_CACHE_DIR`；不清理旧缓存，不读取 `.env`：

```powershell
$env:JAVA_HOME=Join-Path $env:USERPROFILE '.jdks/ms-17.0.16'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
$env:ENGINE_CACHE_DIR=Join-Path (Get-Location) 'target/rag-quality-native-21bb721b230146deba3c50f9b5f63a6c'
$env:DJL_OFFLINE='true'
mvn -o '-Dtest=Rag*Test,EmbeddingModelResourceTest,DocumentLoaderServiceTest,AssistantClientProviderTest,AssistantAnswerSanitizerTest,ErpAssistantServiceTest,ErpAssistantStreamTest,SqlToolValidatorTest,TenantSqlInjectorTest,ModelGatewayHttpTest,OpenAiDirectHttpTest' test
```

2026-10-21 实际结果：**195 项，全部通过；failures=0、errors=0、skipped=0，BUILD SUCCESS**。其中新增边界测试 3 项，已包含在 195 中，不重复累计。旧测试和断言未改。日志：`target/rag-quality-diagnosis/targeted-tests.log`；XML：`target/surefire-reports/`。

覆盖 RAG 指标/报告/分类器、检索与文档资格过滤、租户/知识库/版本边界、引用白名单和编号、上下文、净化、ERP 编排及 SSE 终态、只读 SQL 与租户注入、OpenAI adapter 直连/Gateway HTTP 协议。真实 tokenizer、ONNX 及文档切片的 9 项测试也在其中。数据库/向量库大多使用 Mock；HTTP 使用 loopback 桩，不调用真实供应商。

无答案部分验证的是固定拒答与“拒答后猜测不能算安全拒答”等确定性契约，不是重新测得 5/5 真实模型表现。正确检索用例的既有 Mock 回归通过，也不能替代实际 HNSW、真实模型质量或并发验收。没有生产/Python 改动，因此本轮不重复跑原 407 全量 Java、51 Python 或 14 三进程 IT；这些旧结果属于稳定版既有证据，不计入本轮新测试数。

统计审计再次得到 Recall@5=0.9444444444444444、MRR@5=0.8759259259259259、52/98 事实短语、5/5 无答案拒答、48/48 引用，与历史 summary 一致；16 条 answerable+refused 的 answer 均为固定句。重新核对重建产物的 50/50 Top5 顺序一致，不调用模型、不重写历史 JSON。这些不是修改前后模型效果对比。

## 修改清单与必要性

- 生产代码：**0 个文件**；没有足够新证据支持安全的生产修复。
- 新增 `RagEvaluationRefusalBoundaryTest.java`：保护正常否定和安全拒答的统计边界，避免为了降低 16/45 而改标签。
- 新增本比较报告与 [逐例诊断](p2-rag-quality-diagnosis.md)：保留 16 条全部问题、资源身份、重建分片和不可恢复字段。
- 本地 `target/rag-quality-diagnosis/`：审计 JSON 和测试日志，忽略且不提交；不是额外业务模块。

## 面试可以怎样表述？

可以说明：基于开源 RAG 平台，区分文档召回与关键事实片段覆盖；复核严格拒答分类器，防止把业务否定误算为拒答；对 16 条标准拒答分出 3 条关键证据缺失、1 条断句不完整、1 条问题范围歧义、11 条回答策略待证实，保留了不可恢复字段。没有把重建得分冒充数据库原始得分，没有以 Mock 或门禁通过宣称模型质量提升。

不能说明：已经减少真实误拒答、修复了全部 16 条、做过新 50 条真实 A/B、验证了真实 ERP/生产 SLA。原有平台、ERP Tool 数量和 Spring AI 能力仍属于开源基础。

## 合并建议

当前仅建议审查诊断文档和新增边界测试；不建议将任何阈值/Prompt/检索候选方案合并为已验证修复。等待用户确认是否继续受控复现或进行单变量实验，本轮不自动合并、commit 或 push。

## 第三阶段：离线重建对照，不是新模型质量

### 数据与方法

新增可移植的 `src/test/resources/rag-eval/diagnostics/reconstruction-v1.json`，SHA-256 `54d09f8e53a2221fbf47296b105b128a405bb737017680aae49f8d422a76fc2e`。从已核对的重建产物抽取 13 个公开测试分片、50 条查询的已重建分数；正文仅存一次，租户/知识库/文档 ID 改成 fixture 标识。原 chunk ID 和版本保留以便核对。没有原始请求、rawText、API Key 或业务数据，provenance 保存原产物摘要。它不是新采集的数据库结果。

`scripts/rag_offline_experiments.py` 只接受固定摘要的公开 fixture 或原重建产物；不联网、不访问数据库、不读 `.env`。对同一 50 条按同一版本资格假设、严格 `score > threshold`、来源分散与最终 score 排序做六组实验。历史 ready 状态仅为重建假设，不是当前 MySQL 校验。基线最终 chunk 顺序逐项匹配旧重建；匿名可移植输入与原输入的六组结果、全部 300 份 WordPiece 计数完全一致。

原数据缺少两个干扰分片的逐查询相似度，所以不能从表中的“跨作用域=0”推断真实 ANN 对抗风险已验证。脚本有同租户/库/版本/model 约束，另用高分跨租户、旧版本、错误模型和身份不完整的合成反例测试失败关闭；这是算法模拟及单测，不是真实数据库隔离验收。

### A / B 的实际结果

下表的事实短语只统计 **45 条可回答问题，分母 93**；5 条无答案的“资料不足”属于期望行为，不拿它在文档中出现与否评估证据覆盖。它与历史答案短语 52/98 是不同指标。脚本首版混合统计了无答案标签，现单独分开；对全部候选统一处理，未改用例/金标，首版产物保留。字面包含有假阳性，例如 `WordPiece` 也会命中 `Word`，不能代替人工片段覆盖或真实答案正确率。

| 离线候选 | 文档 Recall@5 | MRR@5 | 可回答证据字面短语 /93 | 无答案非空上下文 /5 | 平均 WordPiece |
|---|---:|---:|---:|---:|---:|
| 基线：0.25 / K8 / 候选24 | 94.44% | 87.59% | 78 | 4 | 457.28 |
| 基线 + 至多一个前片段 | 94.44% | 87.22% | 78 | 4 | 517.48 |
| 0.20 / K8 / 候选24 | 96.67% | 88.33% | 80 | 5 | 575.32 |
| 0.16 / K8 / 候选24 | 98.89% | 89.44% | 85 | 5 | 630.20 |
| 0.20 / K5 / 候选15 | 97.78% | 88.33% | 70 | 5 | 405.92 |
| 0.25 / K8 / 候选8 | 94.44% | 87.59% | 78 | 4 | 457.28 |

WordPiece 为本项目固定 embedding tokenizer 的**未截断证据上下文**计数，含本地编号/来源头，不含完整系统 Prompt、Memory 或其他请求字段，绝不是聊天模型的计费 token。`RagOfflineTokenCounterTest` 验证长输入没有被 128 token 截断。真实 LLM token 预算和费用仍 UNKNOWN。

**A：paraphrase-001。** 基线 P1 从“限为 500MB”开始，没有对象。按原 anchor 顺序，最多插入一个同租户/知识库/文档/model/ready版本假设下的前片段 P0，形成 P0→P1，恢复“单文件上限为 500MB”的连续表述。上下文从 2 片增到 3 片，WordPiece **145→246（+69.66%）**。不改变阈值，不丢原 anchor；选择原因明确为邻接补全，不把没有查询分数的邻片伪造为高分召回。

这个全量应用的候选也有代价：`single-002` 和 `paraphrase-010` 的首个相关文档位置从 3 变成 4，使总体 MRR 小幅下降；邻片顺序与引用编号也可能变化。29 条历史非拒答可回答样本没有丢失字面事实，但不能据此保证新模型输出无退化。无答案五题的片段数在此候选不变。不得把这一个边界修复例子写成所有 16 条拒答已改善。

**B：三个缺失案例。** `single-002` 的 P0=0.161364，0.20 仍无法取到完整格式句，0.16 才取到；基线中的 Word/TXT 字面命中不能证明格式列表已完整。`paraphrase-003` 的 D0=0.174296，只有 0.16 取到 384。`paraphrase-008` 的 S1=0.207599，0.20 即取到非法编号丢弃规则。原候选上限 24 未成为这些样本的瓶颈，增加候选数量不能突破数据库阈值。

降低阈值同时使无答案非空上下文 **4/5→5/5**：0.20 给 no-answer-001/003/004 分别多 2/3/2 片；0.16 还给 no-answer-005 多 1 片。这是噪声风险变化，不是已经发生了无答案误答。0.20/K5 让历史非拒答案例 `single-016`、`multi-002`、`multi-006` 丢失字面事实，因而不能只因其 Recall 更高就推荐。所有五条隔离用例都仍纳入同一 50 条结果；逐题变化位于 results.json，没有删难题。

### 只推荐两项后续候选，不直接上线

1. **评测期元数据诊断继续保留，优先补向量候选/资格筛选阶段的观测。** 对应 11 条 UNKNOWN；当前新增 test-only CallAdvisor 已验证增强后请求和净化结构，但原候选原因仍 UNKNOWN。比继续猜 Prompt 或降阈值更有诊断价值。风险是评测开销与元数据隐私，必须维持默认关闭、允许列表、无正文、作用域 HMAC和失败不改变业务结果。未来若需要实际候选 hook，应先审查最小接入点；本轮未改生产工厂/过滤器。
2. **有预算上限的同版本前片段补全，作为离线候选继续验证。** 对应 paraphrase-001，位置是 token 切片边界与生成证据装配，不是 embedding 身份或拒答策略。它有恢复对象的具体证据，比全局降低阈值更局部。但当前通用版本有上下文和排名代价，尚不建议切换生产默认。必须测同 scope/ready 版本、缺身份/歧义邻片、总预算、去重、原 anchor 不丢失、引用身份及正确/无答案退化；也需要固定模型的实际 A/B 才能证明回答改善。

明确不推荐本轮把阈值降到 0.20/0.16、降低 K、增设 BM25/Reranker，或继续叠加 Prompt。证据只支持候选实验，不足以批准生产策略修改。

### 可复现命令

在仓库根目录，Python 3.12 使用现有环境，无新增 Python 依赖。模型资源、JDK 17 和 Maven 依赖需已准备：

```powershell
$env:JAVA_HOME=Join-Path $env:USERPROFILE '.jdks/ms-17.0.16'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
$env:ENGINE_CACHE_DIR=Join-Path (Get-Location) 'target/offline-experiment-native'
$env:DJL_OFFLINE='true'
model-gateway/.venv/Scripts/python.exe -m unittest discover -s scripts/tests -p test_rag_offline_experiments.py -v
$run=Join-Path 'target/rag-quality-stage3' ('replay-'+[guid]::NewGuid().ToString('N'))
model-gateway/.venv/Scripts/python.exe scripts/rag_offline_experiments.py --output $run
mvn -o '-Dtest=RagExecutionDiagnosticsTest,RagOfflineTokenCounterTest' test
$report=[xml](Get-Content -Raw target/surefire-reports/TEST-com.example.rag.rageval.RagExecutionDiagnosticsTest.xml)
$cp=($report.testsuite.properties.property | Where-Object name -EQ 'java.class.path').value
$argsText='-cp'+"`n"+'"'+$cp.Replace('\','/')+'"'+"`n"+'com.example.rag.rageval.RagOfflineTokenCounter'+"`n"+'"'+("$run/public-contexts.json").Replace('\','/')+'"'+"`n"+'"'+("$run/wordpiece-counts.json").Replace('\','/')+'"'+"`n"
$argFile=Join-Path $run 'token-count.args'
[IO.File]::WriteAllText([IO.Path]::GetFullPath($argFile),$argsText,[Text.UTF8Encoding]::new($false))
java "@$argFile"
```

脚本要求新的输出目录，已有目录会停止而非覆盖。public-contexts.json 只来自已锁定公开 fixture，不是保存的真实模型请求。正常业务没有新增 Python 依赖；此 Python 文件仅是离线研究工具。

### 第三阶段测试实绩

2026-10-21 最终 JDK 17 针对性命令仍为前文的 `mvn -o '-Dtest=Rag*Test,...,OpenAiDirectHttpTest' test`（完整选择器见本报告前节），结果 **202 项通过，0 failures/errors/skipped，BUILD SUCCESS**。其中本阶段新增诊断 6 项、真实本地计数器 1 项；阶段一/二的 3 项已包含在原 195 中，不再叠加。首次局部 8 项、中间 201 项已被最后 202 覆盖，不重复统计。

离线脚本 `unittest` **8 项通过**；覆盖跨租户、跨库、版本、model、身份不完整、歧义邻片、严格阈值、排序、输入指纹和可移植复现。202 Java + 8 离线工具测试 = **210 个本轮独立用例**。六组 × 50 条是 300 个重建实验条目，不是 300 次模型评测或额外测试计数。

产物在 `target/rag-quality-stage3/`：regression-tests.log、offline-unit-tests.log、experiments-portable/results.json、wordpiece-counts.json；源历史产物和金标哈希未变。没有改正常 Java/Python 业务、生产配置、历史测试断言或模型资源；唯一已跟踪代码改动是评测 IT 的默认关闭诊断接入，不进入生产包。

### 待批准的最小真实模型方案

优先只做 **4 次 RAG 模型请求**，不运行 Judge/全量 rag-eval，不接生产 ERP：paraphrase-001 基线/邻片候选各一次；isolation-001 和 multi-010 各一次，用于观察证据利用和解释拒答规则。固定同一模型、同一系统 Prompt 与公开 fixture，temperature=0、top_p=1、输出预算 512 token，输入预算每次最多 4096 token；无工具自动循环、无重试，异常即停。这是新小样本，不与 2026-10-10 的未知模型版本直接比较。

模型选择需确认：项目旧别名为 deepseek-chat，但不能假设其今天的实际版本或价格。[当前官方价格页](https://api-docs.deepseek.com/quick_start/pricing/)列出 deepseek-flash / deepseek-v4-pro；如用户批准，可仅在隔离测试请求选择 deepseek-flash，并核验非思考参数与 adapter 映射，不更改主应用默认或注册新生产模型。若只批准旧别名且无法核实其服务/报价，则停止，绝不自动换模型。

以官方 Flash 未缓存输入 $0.15～0.30 /百万、输出 $0.60～1.20 /百万，且上述 token 上限确实成立为条件，4 次预算约 **$0.0037～0.0074**；缓存、时段、模型实际版本、思考 token 和参数兼容会影响费用。这是有条件估算，不是消费记录，也不拿 WordPiece 当费用依据；建议显式授权封顶 $0.05，执行前再次核价并确认限制可执行。未批准前不读取 Key、不认证、不发探针。该计划不包含 ERP/SSE 真实冒烟；如追加受控 ERP 两轮和 SSE 一次，合计至少 7 请求，必须另行批准。

保存默认仅限诊断元数据与脱敏 fixture 结果；如需公开 fixture 的完整输入/响应，必须先增加明确的本地保存开关、访问权限和 TTL，目前不提供该能力。原 IT 汇总固定路径必须先保护再约定新输出；不得为拿到 rawText 直接启动整套付费 IT。

**第三阶段结论：没有足够证据支持直接修改生产检索或 Prompt。** 离线候选确有收益与代价，元数据诊断可以继续审查；实际模型利用问题、完整无答案行为和真实答案质量仍需批准后的可比验证。停止在诊断分支，不自动合并、commit 或 push。
