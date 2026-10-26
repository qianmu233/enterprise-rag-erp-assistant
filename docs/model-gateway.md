# 本地 Java + Python Model Gateway

本项目基于开源项目进行二次开发。此次增量在已有 Spring AI 应用的模型 HTTP 边界增加 FastAPI 网关，用于验证跨语言协议、失败处理和连接管理。原有 RAG、39 个 ERP Tool、多租户及 Spring AI 基础能力不作为此次个人新增功能。

## 职责和调用链

```text
正式应用：用户 → Java HTTP → 现有 RAG/Agent/Prompt → Spring AI → Python Gateway → Fake Model
本阶段验收：HTTP 客户端 → Spring Boot 测试入口 :8080 → 原 ModelRegistry/GatewayChatModel
           → FastAPI :8001 → OpenAI-compatible Fake Model :8002 → 原路返回
```

Java 保留文档解析、切片、本地 Embedding、检索、租户、会话、Agent、ERP Tool 执行、引用、答案净化、SSE 业务生命周期和计费。Python 仅负责模型协议、alias、参数校验、超时、错误、SSE、日志和上游连接。Python 不接数据库，不执行任何 Tool，不生成 Prompt。

跨语言验收使用**真实独立 Spring Boot/Tomcat 进程**，通过 HTTP 访问测试专用 `/probe`。它导入 Phase 1 的真实 Gateway 配置和注册中心，未启用应用组件扫描或数据库自动配置。它验证模型边界，不代表已运行完整生产 `/api/ask`、文档导入或数据库流程；测试中的 `local_probe` 是专用 Java 工具，不是 ERP Tool。

## 模型、协议与安全范围

Gateway 提供 `POST /v1/chat/completions`，两个 alias `gateway-chat`、`local-fake` 均固定映射到上游模型 `local-fake`，固定地址为 `http://127.0.0.1:8002/v1/chat/completions`。上游地址必须精确匹配本地白名单。客户端不能指定 URL，未知模型返回 400 `REQUEST_INVALID`，没有任何模型 fallback。

Java 仅启用 `gateway` Profile 并显式选择 `modelId=gateway-chat` 时接入。默认 DeepSeek、原 OpenAI/Gemini 配置保持不动。不要把 Gateway 设为默认模型。单独注册的 `GatewayChatModel` 委托 Spring AI OpenAI adapter，避免额外 `OpenAiChatModel` Bean 抑制原自动配置。

支持文本消息（字符串或 text part 数组）、system/developer/user/assistant/tool 角色、temperature、max_tokens、max_completion_tokens、stream、tools、tool_choice、assistant tool_calls、tool_call_id、stream_options 及常用采样字段。工具参数须为 JSON 对象字符串；Tool 结果必须关联历史工具调用。请求和响应只支持一个 choice，不支持图片、音频、Responses API 或任意供应商扩展。未知请求字段会被拒绝。

请求使用本地演示 token `local-gateway-test-key`；它不是第三方 API Key。Authorization 不转发上游。请求上限 1 MiB、响应总量上限 8 MiB、单 SSE frame 上限 64 KiB。日志和错误不包含完整 Prompt、知识库正文、Tool 参数、认证值或原始上游错误正文。

## 超时、错误与日志

默认连接、读取、写入、连接池超时分别为 1 秒，总请求 deadline 为 3 秒。deadline 从收到请求开始计算，贯穿请求读取、上游响应头、响应正文或 SSE；即使持续收到 heartbeat，也不会重置总 deadline。配置位于 `config.py` 的 `Settings`，部署者可以通过受控应用工厂参数调整；本阶段不开放任意上游配置。

HTTPX transport retries=0；不跟随重定向，不读取环境代理。Java Phase 1 的 Gateway SDK retries=0，避免叠加重试。ASGI 框架处理客户端发送的背压；总 deadline 约束上游读取，不是独立的下游慢客户端带宽控制器。

| 情况 | HTTP 状态（流开始前） | category/code |
|---|---:|---|
| 请求或 alias 不合法 | 400；请求过大 413 | REQUEST_INVALID |
| 本地认证失败、上游 401 | 401 | AUTH_FAILED |
| 上游 429 | 429 | RATE_LIMITED |
| 四类 HTTP 超时、总 deadline、上游 504 | 504 | UPSTREAM_TIMEOUT |
| 拒绝连接、上游 502/503 | 503 或对应 502/503 | UPSTREAM_UNAVAILABLE |
| 上游 500 | 500 | UPSTREAM_ERROR |
| 非法 JSON、协议或尺寸 | 502 | RESPONSE_INVALID |
| 上游提前 EOF | 502；流已开始则维持 200 | UPSTREAM_EOF |

错误格式为 `{"error":{"message":"固定脱敏描述","type":"分类","code":"分类","param":null},"request_id":"..."}`。流式响应头发送后不能修改 HTTP 状态，所以通过 SSE `data: {"error":...}` 通知 Java。异步 HTTP 异常可能包在 `CompletionException` 内。

每次请求生成 request_id；客户端 `X-Request-ID` 符合 `[A-Za-z0-9_.-]{1,64}` 时安全复用。响应 `X-Request-ID`、错误正文、上游同名 Header 和 JSON 审计日志保留该 ID。日志仅包含 request_id、允许的模型 alias、stream、status、latency_ms、upstream_status、error_category，以及真实收到的三个 usage 数字；忽略额外 usage 元数据。

## SSE 完整性和连接管理

网关逐 frame 解析 UTF-8 data，支持 LF/CRLF 和注释 heartbeat，校验文本及分片工具调用结构。必须同时观察到合法 `finish_reason` 和 `[DONE]` 才转发正常结束标记：

- 正文后直接 EOF、收到 finish_reason 后缺失 `[DONE]`、或 `[DONE]` 前没有 finish_reason，均输出 `UPSTREAM_EOF` 错误帧。
- 非法 frame、流内错误及超时输出相应脱敏错误帧，之后不会补发 `[DONE]`。
- 已发出的部分正文无法撤回；错误后的业务状态继续由 Java 原有生命周期处理。

`ManagedStream` 在响应完成、异常或 ASGI 客户端断开时关闭上游 response 和迭代器，使用取消屏蔽区保证清理动作可以执行。HTTPX 客户端在 FastAPI lifespan 中复用，并在应用退出时关闭。

**取消边界：**网关收到真正的下游 disconnect 时可以释放 Fake Model 流；Python 单元测试和实际 HTTP 断开测试对此分别验证。Java 前端断开是否立即变成 Gateway 的 HTTP disconnect，仍取决于 Spring AI adapter；集成测试单独观测并验证资源在网关超时内释放，不将它表述为立即取消上游。

本次真实三进程测试观察到：Java 前端断开 200ms 后 Fake Model 仍有 1 条活跃流，随后在 Gateway 的超时约束下清理为 0。因此不能声称已经实现 Java 用户取消到模型上游的立即取消传播；没有为此修改现有 Java adapter 或 SSE 生命周期。

## Tool Calling 和 usage

Gateway 只传递工具 schema、名称、arguments 字符串、assistant tool_calls、tool_call_id 和 tool message。Fake Model 返回工具调用，Java 执行测试工具并返回 `42`，结果再次经过 Gateway 到 Fake Model，随后返回最终答案；同步和流式路径均测试。Python 不调用该工具。

上游 usage 原样透传，包括 usage-only SSE chunk；缺失时保持缺失，不估算、不补造。Fake Model 的 `7/3/10` 是**明确声明的协议 fixture 数字**，不是真实模型计量，也不能作为费用或性能证据。Java SDK 自身对缺失 usage 的默认表示不属于 Python 生成数据。

## 安装和启动

要求 Python 3.12、已验证 JDK 17、Maven 及已缓存的 Java 依赖。在仓库根目录：

```powershell
python -m venv model-gateway/.venv
model-gateway/.venv/Scripts/python.exe -m pip install -r model-gateway/requirements.lock
```

`pyproject.toml` 固定直接依赖，`requirements.lock` 记录本次虚拟环境的完整依赖版本。安装依赖可能联网，运行测试不调用任何真实模型。

需要手动运行 Python 时，进入 `model-gateway`，分别在两个终端执行：

```powershell
.venv/Scripts/python.exe -m uvicorn fake_model:app --app-dir tests --host 127.0.0.1 --port 8002 --no-access-log --log-level warning
.venv/Scripts/python.exe -m uvicorn app:app --host 127.0.0.1 --port 8001 --no-access-log --log-level warning
```

Fake Model 仅使用本地逻辑，支持 normal、stream、tool、HTTP 错误、slow、非法响应、early-eof、missing-done、stall、trickle 和 cancel 等测试场景，通过专用 `probe:场景` 文本选择。该逻辑只存在于 Fake Model。返回的 proof 包含 alias、消息数量、请求 ID 和消息摘要，用于证明路径和内容一致性；它不是认证机制。Fake Model 只用于隔离测试，不能接收真实敏感业务数据。

完整生产应用在已有受控数据库环境下可以使用 `mvn spring-boot:run '-Dspring-boot.run.profiles=gateway'`，并显式选择 `gateway-chat`。本阶段没有执行这个命令，因为它会启动数据库初始化。

## 测试方法与证据

Python 单元测试只使用进程内 ASGI/MockTransport，不依赖常驻服务：

```powershell
model-gateway/.venv/Scripts/python.exe -m pytest model-gateway/tests -c model-gateway/pyproject.toml -q
```

Java 默认测试不依赖 Python。使用 JDK 17，并沿用已验证的本地 DJL 缓存：

```powershell
$env:JAVA_HOME=Join-Path $env:USERPROFILE '.jdks/ms-17.0.16'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
$env:ENGINE_CACHE_DIR=Join-Path (Get-Location) 'target/rag-diagnosis-native-cache'
$env:DJL_OFFLINE='true'
mvn -o test
mvn verify
```

跨语言测试仅显式运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-model-gateway.ps1
```

脚本先检查端口空闲，运行 Python 单元测试、Java 默认回归，再分别启动 Fake Model、Gateway、Spring Boot 测试进程，最后运行 `mvn -o '-Dtest=ModelGatewayIT' test`。它仅清理自己创建的进程树，不停止已有服务；8080/8001/8002 有占用时直接失败。结果位于 `target/gateway-phase2/`，包括三进程日志、PID/端口记录、Python XML、Java回归日志、跨语言 XML 与日志。该目录属于忽略的运行产物，不作为源码提交。

Phase 1 的 399 项默认 Java 测试保持；新增跨语言 IT 不由普通 Surefire 命名规则选中。不要启用 `rag-eval`，该 Profile 会执行真实付费模型评测。本阶段没有 Docker、真实模型或真实数据库验收。

2026-10-16（Asia/Shanghai）实际验收：Python 48 项、JDK 17 默认 Java 回归 399 项、三进程跨语言 IT 14 项，均为 0 failures / 0 errors / 0 skipped。IT 证实模型 alias、消息摘要、request_id、同步/流式、Java 工具往返、usage、429/500/504、读取超时、heartbeat 下的总 deadline、提前 EOF、缺失 DONE 和连接释放。Mock usage 和消息摘要只作为协议证据。

同日 `mvn -o test` 和随后 `mvn verify` 均为 BUILD SUCCESS；后者在 Python 测试服务全部停止后执行，并完成普通 399 项测试及打包，没有启用 rag-eval。日志为 `target/gateway-phase2/java-tests.log`、`maven-verify.log` 和 `integration-tests.log`。

以上 48 项是 Phase 2 初次验收记录。最终收尾审计保留这 48 项，并针对深层 JSON 增加 3 项安全回归；当前数量、日志安全修正、历史疑似凭据风险和贡献清单见 [P2 最终审计](p2-final-audit.md)。

## 已知限制

Java 已有直接使用 DeepSeek/OpenAI-compatible/Gemini 的路径；Python 不是默认运行依赖。直连与网关共用 Java RAG/Agent 主链，完整配置、失败语义差异及当前回归证据见 [面试与复现指南](../P2_INTERVIEW_GUIDE.md) 和 [本轮审查](p2-architecture-review.md)。本轮只修正现有 OpenAI-compatible API 前缀，不更换默认模型、不增加新的 provider 或 Python 业务职责。

这是固定本地上游的模型协议增强，不是生产模型调度平台。未证明真实模型质量、成本、并发吞吐或生产 SLA；未实现分布式限流、重试、模型缓存和供应商容灾。Gateway 认证是本地演示 token，实际部署认证、TLS 和访问控制不在本阶段范围内。

保持上游出处、许可及原 RAG 评测限制；简历只能表述已实现且实际验证的网关增量，不能将此处的协议 Mock 测试写成真实模型效果或完整生产 RAG 端到端验收。
