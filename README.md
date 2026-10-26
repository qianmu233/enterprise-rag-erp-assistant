# Enterprise RAG & ERP Assistant

**企业级 RAG 与 ERP 智能业务助手**

基于 Spring AI 的企业知识检索与 ERP 业务数据助手，整合检索增强生成（RAG）、结构化业务查询、受控工具调用和流式响应，为企业文档问答与业务数据查询提供统一的对话入口。

项目基于 [ly1836/spring-ai-rag-demo](https://github.com/ly1836/spring-ai-rag-demo) 二次开发。本文档区分上游已有能力与后续扩展，具体来源及修改范围见项目文档。

## 项目简介

企业业务问答通常涉及两类信息：一类存在于制度文档、产品资料和业务说明中，另一类存在于 ERP 等结构化业务系统中。单纯依靠向量检索难以准确回答实时业务数据问题，而直接调用业务接口又缺少统一的自然语言交互方式。

本项目将文档检索与业务工具调用整合到 Spring AI 对话流程中，根据问题类型选择合适的数据来源，并结合检索证据或业务工具结果组织回答。

## 核心功能

### 1. 企业文档检索与问答

- 支持文档解析、文本切分、向量化及相似度检索。
- 使用本地 ONNX Embedding 模型生成向量，并通过 PostgreSQL / PgVector 进行向量检索。
- 结合知识库、用户和文档版本等上下文筛选检索结果。
- 在回答中组织相关引用，支持基于检索证据的知识问答。

### 2. ERP 结构化数据查询

- 通过受控业务工具访问 ERP 数据。
- 支持多类 ERP 工具及动态工具调用。
- 提供动态只读 SQL 查询能力，便于处理适合结构化查询的业务问题。
- 将工具执行结果交给对话流程进行整理，而不是完全依赖模型记忆回答业务数据问题。

### 3. 统一对话与执行流程

提供不同的问题处理模式：

| 模式 | 主要用途 |
|---|---|
| `knowledge` | 企业文档与知识库问答 |
| `data` | ERP 结构化数据查询 |
| `auto` | 根据问题选择知识检索或业务工具 |

项目还包含会话管理、流式响应（SSE）、工具调用结果处理，以及基于业务结果生成图表等能力。

### 4. 可选 Python 模型网关

项目包含可选的 Python FastAPI Gateway，用于集中处理 OpenAI-compatible 请求、超时与错误映射、流式响应完整性等协议问题。

该网关是可选扩展，不应与 Java 主应用的全部功能混为一谈。

## 技术架构

```text
用户请求
   |
   v
Spring AI 对话流程
   |
   +----> 企业文档检索
   |         |
   |         +--> 文档解析与切分
   |         +--> ONNX Embedding
   |         +--> PgVector 相似度检索
   |
   +----> ERP 业务工具
   |         |
   |         +--> 受控业务查询
   |         +--> 动态只读 SQL
   |         +--> 工具结果处理
   |
   v
回答组织与引用
   |
   v
普通响应 / SSE 流式响应 / 图表
```

可选 Python Gateway 为模型请求提供独立的协议与错误处理边界。

## 技术栈

| 技术 | 用途 |
|---|---|
| Java、Spring Boot | 后端应用与服务组织 |
| Spring AI | 模型交互、RAG 流程与工具调用 |
| PostgreSQL、PgVector | 结构化数据存储与向量检索 |
| ONNX Runtime | 本地 Embedding 推理 |
| Python、FastAPI | 可选模型网关 |
| Docker Compose | 本地依赖与服务部署 |
| Maven | Java 项目构建与测试 |

具体版本及依赖以项目中的 `pom.xml`、Python 依赖声明和部署配置为准。

## 快速开始

### 环境要求

- JDK 17
- Maven
- Docker Desktop 与 Docker Compose（如使用容器部署）
- Git LFS（用于获取仓库中的模型资源）
- Python 环境（仅在使用 Python Gateway 时需要）

### 1. 克隆仓库

```bash
git clone https://github.com/qianmu233/enterprise-rag-erp-assistant.git
cd enterprise-rag-erp-assistant
git lfs install
git lfs pull
```

### 2. 配置运行环境

根据 `docker-compose.yml`、`deploy/` 目录和应用配置文件，准备所需数据库、模型服务及连接参数。

请通过环境变量或本地配置提供数据库凭据和模型 API Key，不要将真实凭据提交到 Git。

### 3. 启动与构建

根据所选运行方式，检查 `docker-compose.yml` 中定义的服务后启动容器，或使用 Maven 本地构建与启动。

```bash
mvn verify
```

本地源码启动可使用：

```bash
mvn spring-boot:run
```

上述 Maven 命令用于构建与启动 Java 应用；实际可用的启动方式、外部服务依赖及端口以仓库配置为准。若 Compose 已启动应用服务，不要再重复启动同一实例。

## 测试与验证

截至 2026-10-26 的独立安全快照验收记录：

| 验证范围 | 结果 |
|---|---:|
| Maven `verify`，包含打包与测试 | 417 项通过 |
| Python Gateway 合约测试 | 51 项通过 |
| 离线诊断工具测试 | 16 项通过 |
| Python 依赖一致性检查 | 通过 |

以上是指定本地环境中的验证结果，不代表所有操作系统、数据库配置及模型供应商环境均已验证。

详细记录见 [`TEST_REPORT.md`](TEST_REPORT.md) 与 [`docs/SAFE_SNAPSHOT_AUDIT.md`](docs/SAFE_SNAPSHOT_AUDIT.md)。


## 许可证与来源

本项目采用 Apache License 2.0，相关第三方资源的许可、来源及修改说明以仓库中的 `LICENSE`、`NOTICE` 和第三方资源说明为准。

项目是在上游开源工程基础上进行二次开发，具体个人扩展范围与原有能力的边界见架构与发布说明。

欢迎通过 Issues 讨论问题与改进建议。
