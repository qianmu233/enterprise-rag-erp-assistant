# P2 最终收尾审计

审计日期：2026-10-16（Asia/Shanghai）。分支 `feature/enterprise-ai-upgrade`，HEAD `2f44292eac9fc7a4f3c871c1db2c3f1d886d7cfa`。Phase 1/2 尚未提交；本次不提交、不 push、不操作 test-data，也不启动真实模型或业务数据库。

## 验收口径

功能交付范围是：在开源项目及既有二次开发成果上，完成 Java + Python 本地模型协议增强。正式职责链为 Spring Boot → Spring AI OpenAI adapter → FastAPI Gateway → OpenAI-compatible upstream。本地验收使用真实 Spring Boot 隔离测试进程、Gateway 和 Fake Model 三个进程。

本次开发分支审查覆盖模型协议和网关集成。真实 RAG、ERP 数据库和生产部署未纳入该次验收。历史开发分支的疑似凭据发现见下文，公开仓库的独立来源见[发布快照审查](SAFE_SNAPSHOT_AUDIT.md)。

## 上游来源与阶段性改动

| 分类 | 有证据支持的归属 | 不应扩大为 |
|---|---|---|
| 上游已有 | Spring AI RAG/Agent 基础、39 个 ERP Tool、多租户、持久化、受管文档/知识库/引用、本地嵌入、动态图表等；例如 `591fbf1`、`1f9ad9e`、`ac31b70` | 从零开发整个企业 RAG/ERP 平台 |
| 此前二次开发 | 提交 `68bf8a1` 的 Windows SSE fixture 修复，`81bad69` 的知识回答协议/净化边界、拒答分类与回归、评测证据整理，以及 `2f44292` 诊断文档收尾 | 原有检索、工具及多租户功能全部由个人原创 |
| Phase 1 新增 | 独立 gateway Profile、显式 gateway-chat、严格注册路由、委托现成 OpenAI adapter 的模型 Bean，以及新增 22 项离线验证（377→399） | 自行实现 Spring AI 或 OpenAI SDK |
| Phase 2 新增 | FastAPI/httpx 模型网关、固定 alias/URL、请求校验、超时/deadline、SSE 完整性、错误分类、request_id/脱敏日志、Tool/usage 透传、Fake Model、48 项 Python 测试与 14 项跨语言 IT、启动脚本和文档 | Python RAG/Agent 重写、生产模型服务 |
| 本次必要修复 | 深层 JSON 异常统一归类及 3 项新增安全回归；关闭默认 SQL 参数/框架正文 DEBUG 日志；忽略 `.env.*`；更新过时测试口径 | 新增模型、算法或复杂基础设施 |
| 不能宣称 | 从零完成全部项目；真实模型效果提升；真实成本/性能/SLA；用户取消立即到达模型；完整生产 RAG 端到端测试；历史凭据已经失效 | 使用协议 Mock 结果代替这些证据 |

提交作者和差异是贡献核对依据；完整原项目许可证和上游归属保持。

## 功能与架构核对

| 检查项 | 依据和范围 |
|---|---|
| 显式路由、默认模型、无 unknown fallback | ModelRegistry、GatewayModelConfigTest、ModelRegistryTest；默认 DeepSeek 与原 OpenAI/Gemini Bean 路由保留，缺失 Gateway Bean 明确失败 |
| FastAPI /v1/chat/completions | app.py；gateway-chat/local-fake 固定映射到唯一 loopback upstream |
| 同步、SSE、同步/流式工具调用 | ModelGatewayHttpTest、ModelGatewayIT 和 Python 测试；工具实际在 Java 执行 |
| usage、request_id、错误分类 | 原样透传 fixture usage/缺失不补造；响应 Header、错误正文和审计日志关联 request_id；400/401/429/5xx 及网络错误分类 |
| timeout、deadline、提前 EOF | 四类 HTTP timeout；总 deadline 不随 heartbeat 重置；没有 finish_reason 或 DONE 时发送错误，不伪造成功 |
| 连接释放 | ASGI 断开、实际 HTTP 断开和 Java 断开后的资源寿命分别验证；不承诺 Java adapter 立即传递取消 |
| Java 职责 | REST/SSE、RAG、Tika/切片/Embedding/PgVector、Prompt、Agent/Tool 编排/ERP、租户、引用、净化、记忆和持久化保持在原 Java 侧 |
| Python 职责 | 模型 HTTP 协议与连接管理；没有 MySQL/PgVector 客户端、RAG、Tool 执行、第二套 Agent/会话/计费 |

真实收费模型可用性为 **UNKNOWN（未调用）**；生产数据库/RAG 全链路本轮未执行，不能由上述模型边界测试推断通过。

## 安全审计

| 项目 | 结论 |
|---|---|
| 当前候选文件中的真实 Key | 有限格式扫描及人工核对未发现；local-gateway-test-key 为明确写明的本地测试 token，不是服务商凭据 |
| 历史疑似 Key | 可达历史中 3 个 application.yml blob 包含同一疑似 DeepSeek Key；早期上游 `19b3694` 涉及该硬编码配置，`1043df2` 涉及其移除。属于 2f44292 之前内容；未输出该值、未调用认证接口 |
| 历史 Key 有效性/撤销状态 | **UNKNOWN**。需凭据持有人确认撤销或更换；如需清理公共历史，应另行授权并保留开源归属。此次不自动改写历史 |
| .env / .env.* | 当前未跟踪；补充忽略环境变体文件，不读取本地凭据文件 |
| Python 日志 | 只记录白名单元数据和已观测 usage 数字；日志测试覆盖 Prompt、认证值、Tool 参数和 usage extra 的不输出 |
| Java 日志 | 原配置 SQL 参数 TRACE 可能输出持久化的问题/回答/业务参数，本次降至 INFO；Web/AI 默认 INFO，防止测试或演示框架 DEBUG 正文日志。外部覆盖 DEBUG/TRACE 和既有异常栈仍不能视为全面脱敏保证 |
| SSRF / 上游 URL | 只能精确访问 http://127.0.0.1:8002/v1/chat/completions；客户端字段不能更改 URL，不跟随重定向，不使用环境代理。仍需信任本机 8002 服务及本机进程隔离 |
| body / SSE / 时间上限 | 请求 1 MiB、响应 8 MiB、frame 64 KiB，HTTP 四类超时默认各 1 秒，总 deadline 3 秒；客户端背压不是独立带宽控制器，不能宣称所有慢客户端场景都有硬实时终止保证 |
| 深层 JSON | 修复前约 20KB 深层 JSON 可导致无结构 500、错误日志状态不准确；统一转换为 ValueError 后，分别走请求 400、响应 502、流内 RESPONSE_INVALID，新增 3 项回归 |
| 扫描边界 | 审查当前候选文本及可达历史常见凭据格式，扫描报告只留路径/行号/摘要，不保存密钥。大型 tokenizer JSON 单独跟进扫描无候选匹配；不是通用秘密检测或依赖漏洞认证 |

## 测试证据

本轮修复前重跑：Java 默认 399、Python 原 48、真实三进程 IT 14，均 0 failures / 0 errors / 0 skipped。新增深层 JSON 安全回归 3 项，不修改、删除或跳过原有断言。修复后重新执行完整脚本并核验报告：

| 测试层 | 原有 / 本次新增 | 实测总数 | failures | errors | skipped |
|---|---|---:|---:|---:|---:|
| JDK 17 默认 Java | 基线 377 + Phase 1 新增 22；本次未新增 | 399 | 0 | 0 | 0 |
| Python | Phase 2 原 48 + 本次安全回归 3 | 51 | 0 | 0 | 0 |
| 显式跨语言 IT | Phase 2 的 14；本次未新增 | 14 | 0 | 0 | 0 |

`mvn -o test` 为 BUILD SUCCESS；三进程退出、8080/8001/8002 端口释放后，使用 JDK 17.0.16 再执行 `mvn verify`，399 项测试全通过并完成打包，仍为 0 failures / 0 errors / 0 skipped。打包日志为 `target/gateway-final-verify.log`。默认 Java 测试没有因 Python 服务停止而失败。

Phase 1 七个文件与其验收快照 SHA-256 全部一致；未删除历史测试。修复后的 Java 演示日志中框架 `Writing [` 正文 DEBUG 行为 0；Python 审计日志保持白名单元数据。跨语言测试验证的是模型边界，不是生产 `/api/ask`、RAG 与数据库全链路。

测试入口及日志：`scripts/test-model-gateway.ps1`；`target/gateway-phase2/python.xml`、`java-tests.log`、`integration.xml`、`integration-tests.log`。默认测试在 JDK 17 及已验证 DJL 缓存条件下运行。报告位于忽略的 target，不纳入提交。不要运行 rag-eval（会调用真实模型）。

## 协议复现

网关说明涵盖安装、Java 与 Python 的职责、三进程测试脚本、错误、SSE 及取消处理。测试覆盖同步和流式协议、Java 工具结果回传及提前 EOF，使用本地模型桩。[模型网关](model-gateway.md) · [运行示例](p2-demo.md)
