# P2 简洁演示说明

整理日期：2026-10-21（Asia/Shanghai）。本项目基于 [ly1836/spring-ai-rag-demo](https://github.com/ly1836/spring-ai-rag-demo) 二次开发，保留原 LICENSE。演示分为“不付费的协议验证”和“准备好环境后的完整业务演示”，两者不混称。

## 1. 最短可复现入口：不需要数据库或真实模型

前提：Microsoft OpenJDK 17.0.16、Maven 依赖已缓存；模型资源为真实 LFS 实体。仓库根目录执行，JDK 路径按本机调整：

```powershell
$env:JAVA_HOME=Join-Path $env:USERPROFILE '.jdks/ms-17.0.16'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
$env:ENGINE_CACHE_DIR=Join-Path (Get-Location) 'target/demo-native-cache'
$env:DJL_OFFLINE='true'
mvn -version
mvn -o '-Dtest=OpenAiDirectHttpTest' test
```

这 7 项使用真实 Spring AI OpenAI adapter 和随机 loopback HTTP 桩，展示 Java 直连、messages/model、SSE、usage、同步/流式工具往返及 429。它不是供应商效果验收，也不是完整 RAG 问答。

已有 Python 3.12 虚拟环境和锁定依赖时，演示真实跨语言链路：

```powershell
powershell -NoProfile -File scripts/test-model-gateway.ps1
```

脚本先运行 Python/Java 回归，再启动隔离的 Spring Boot 测试探针 :8080、FastAPI Gateway :8001、Fake Model :8002，执行 14 项 IT，最后清理自己的进程。端口必须空闲。该探针不启动生产数据库；证明 HTTP 链路、工具协议和失败处理，不证明真实业务数据或模型质量。依赖准备步骤见 [model-gateway.md](model-gateway.md)，不要随意升级锁文件。

## 2. 完整应用的最短启动方式

此步骤仅供以后已获授权的受控演示使用，本次收尾没有执行。先准备隔离的 MySQL/PgVector、正确的模型实体，以及本地环境中的数据库连接与已授权模型配置；真实启动会初始化演示数据。不要连接生产数据库，不把 Key/密码写入源码、命令示例或演示截图。

```powershell
# 默认 Java → DeepSeek，无 Python 依赖
mvn spring-boot:run

# 或：启用可选 Gateway（需先按网关文档启动两个 Python 服务）
mvn spring-boot:run '-Dspring-boot.run.profiles=gateway'
```

访问 `http://localhost:8080`。使用与受控演示数据一致的租户上下文，选择知识库和模型；直接请求 `/api/**` 需 `X-Ent-Code`，用户上下文为 `X-User-Id`。Header 传递不等于完整鉴权。不要在未授权时点击触发真实问答或生成提示问题的入口。

| 模式 | 选择方式 | 实际链路与依赖 |
|---|---|---|
| 默认直连 | 不指定 modelId，或 `deepseek-chat` | Java → DeepSeek adapter；完整业务仍需数据库 |
| DashScope 直连 | `qwen-turbo` 等现有 ID | Java → OpenAI adapter → 阿里云百炼；`spring.ai.openai.base-url` 为 `/compatible-mode/v1` 完整前缀 |
| Gemini 直连 | `gemini-2.0-flash` | Java → Google GenAI adapter；不复用 DashScope 地址 |
| 可选 Gateway | `gateway` Profile **且** `gateway-chat` | Java → OpenAI adapter → FastAPI → 本地 Fake Model；省略 ID 仍默认 DeepSeek |

`openai` 是适配协议/provider 标识，不代表当前地址属于 OpenAI 公司。DashScope 接口归属可核对 [阿里云官方 Base URL 文档](https://www.alibabacloud.com/help/en/model-studio/base-url)。DeepSeek、Gemini 保留各自独立配置，未统一替换地址。Fake Model 返回的是协议 fixture，不会回答真实文档问题。

Compose 没有强制依赖 Python，默认应用镜像来自上游；若展示本地改动，需要先构建当前源码镜像。本次没有做 Docker 运行验收。

## 3. 一次知识问答怎么讲清楚？

在受控环境选择 `knowledge`、目标知识库及模型，询问“文档上传支持哪些文件类型？”。若允许导入演示文档，使用仓库 `src/test/resources/rag-eval/v1/documents/`，不要使用或处理 `test-data/`。没有导入/模型授权时，只展示代码和历史证据。

调用链：`ChatController → ErpAssistantService → RagAnswerService.prepare / RagAdvisorFactory → 本地 ONNX 查询向量 → PgVector 候选 → 当前租户/知识库/ready 版本资格过滤 → 本轮编号证据 + 知识提示词 → AssistantClientProvider / ModelRegistry → ChatClient / ChatModel → 对应 adapter → 模型 → Java 回答净化、引用、usage/Memory/生命周期 → REST 或 SSE`。

上传链路另为 Tika 解析、token 切片、真实 tokenizer 复核、ONNX embedding、向量批写和版本补偿。展示 `GET /api/search` 可检查受管检索结果，不调用聊天模型；这不是同一次生成请求的最终输入快照，不能拿后来搜索替代当时模型证据。

演示时指出：knowledge 不装配 ERP Tool；文档命中不等于关键句命中；引用编号来自本轮上下文，结构校验不等于语义蕴含验证。不要预先承诺这道题真实模型一定答对。

## 4. 一次 ERP Tool Calling 怎么讲清楚？

在隔离 ERP 演示数据中选 `data` 模式，使用当前已注册工具支持的问题，例如“查询当前租户的销售订单”。不预编造订单数量或结果。

调用链：`ChatController → ErpAssistantService → AssistantClientProvider 按 ToolSnapshot 装配 → ChatClient / adapter → 模型返回 tool_calls → Spring AI ToolCallingManager → Java Tool 回调/只读 SQL 校验/租户与数据源约束 → MySQL 查询 → ToolCallRecorder 与结构化结果捕获 → tool_call_id 对应的 tool result 回传模型 → 最终回答 → Java 净化、可选图表、Memory/usage、REST/SSE`。

Python 若启用，只透传 tool_calls 和 tool result，不执行 ERP 查询。无真实环境时演示 `OpenAiDirectHttpTest` 的本地 Java 回调和 14 项 IT 的工具往返；明确这不是 39 个 ERP Tool 的真实数据库逐项验收。

## 5. 证据、限制与贡献边界

2026-10-20 实测：Java 407、Python 51、跨语言 IT 14，均 0 failures/errors/skipped；合计 472 个独立用例。36 项针对性回归已包含在 407 中。9 项真实 ONNX/tokenizer/切片测试包含在 Java 回归中。命令、报告和构建产物见 [架构审查记录](p2-architecture-review.md)。本次收尾只核对报告和差异，不冒充重新跑过全部测试。

历史 50 条 RAG 第三轮：Recall@5 94.44%、MRR@5 87.59%、事实短语命中 53.06%、无答案拒答 5/5，但可回答误拒答 16/45。本轮没有新真实模型指标，不声称已修复这 16 条。直连 SSE 提前 EOF、取消传播、真实供应商兼容、生产鉴权、真实数据库端到端、吞吐/成本/SLA 仍有限制或未验证。

上游已有企业 RAG、Agent/ERP Tools、多租户、版本、引用、持久化等基础；既有个人增量为回答协议/净化稳定性与评测诊断、Gateway 接入和跨语言验证；本轮是 API 前缀修正、7 项直连测试、1 项知识提示词契约与文档。不能把整个平台或所有 ERP Tool 写成从零原创。

## 6. 当前真实模型验证状态

2026-10-21 仅检查相关环境变量是否存在：当前进程未发现模型 Key 及显式数据库 URL；存在本地 `.env`，内容未读。不能据此确认服务不可用，也不能宣称已有可安全调用条件。没有发送模型或数据库请求。

若另行批准，可计划最多 4 次模型 HTTP 请求：一次受控 RAG 问答、一次本地只读工具的两轮协议往返、一次流式响应；不运行 Judge 或全量 50 条评测，不执行真实 ERP 写操作，不重试，异常即停。实际供应商、模型、上下文/输出上限和费用授权需在执行前确认；这只是预算提案，不是已执行结果。缺少条件不阻塞版本冻结。
