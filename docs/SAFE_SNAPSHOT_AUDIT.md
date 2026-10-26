# P2 全新安全源代码快照验收

日期：2026-10-26。文件基础：已审核旧发布 main `3efdbd00840f5f168437c5a77244b97a98368555`。本目录通过白名单读取文件，再独立 `git init`；不继承原始开发或旧发布 Git 历史，不配置远端，不推送，不改变 GitHub Private 可见性。

## 文件与数据边界

源码、依赖声明、测试、固定技术 Fixture、模型、必要前端资源及文档的完整清单和摘要见 [SAFE_SNAPSHOT_MANIFEST.json](SAFE_SNAPSHOT_MANIFEST.json)。该清单排除自身内容摘要以避免循环自引用，不代表来源证明或替代原文件。

没有复制旧 `.git`、分支、标签、日志、缓存、运行产物、原开发 `test-data/`、编辑器/OpenSpec 工作记录、上游六张 PNG 或 GIF。本次构建新生成的 target 和本地工具报告受 Git 忽略，不纳入源码快照。

36 条初始化结构语句保留，旧业务与联系人 seed 行排除，替换为明确的合成记录。没有旧会话、Token 用量、真实账单或交易样例。UI 和两处测试的公司名称替换为明显合成标签，未放宽断言。参见 [SYNTHETIC_DATA](SYNTHETIC_DATA.md)。本次没有对真实数据库执行 SQL。

`experiment/rag-quality-improvement` 的 `dc90a7234f654a37952dd1a221b0b72af4e1d733` 不是文件基础 main 的祖先，其扩展器、对应新增测试及脚本实验变体未迁入。生产检索未改变，没有新的真实模型质量改善结论。

## 离线构建和测试

JDK 17.0.16、Python 3.12.3，现有公开依赖与固定模型。未提供真实供应商凭据，未启用 `rag-eval`、真实模型/Judge、生产服务或数据库。

| 范围 | 本次最终结果 |
|---|---|
| `mvn -o -B -ntp verify` | BUILD SUCCESS；417 项，0 failures/errors/skipped；41.314 秒，含 ONNX 中文推理与打包 |
| Python Gateway 本机 ASGI/MockTransport 合约 | 51 passed，0 failed/skipped；0.64 秒 |
| 离线诊断工具 unittest | 16 passed；无数据库或模型调用 |
| 既有 Gateway Python 环境 `pip --isolated check` | 通过 |
| 历史三进程 Gateway 14 项 | 本次未重跑；旧报告按原日期保留，不能算成本次通过数 |
| 新 seed 的真实 MySQL/PgVector 导入、Compose 启动 | 未执行；现有初始化器测试在 Mock JDBC 下解析两份实际 SQL |
| 真实模型 HTTP / 质量评测 | 0；未重新生成质量结果 |

实际命令使用本次独立目录，Maven 指定无认证内容的本地临时 settings 来匹配已有 `central` 缓存；`ENGINE_CACHE_DIR` 指向已有用户级 DJL 缓存，`DJL_OFFLINE=true`。没有从旧仓库复制缓存或原生 DLL。Python 合约使用既有 P2 Python 3.12 解释器只读执行新快照文件，禁用字节码、使用新目录输出，并显式加载 `anyio.pytest_plugin`；没有复制或安装虚拟环境。后续使用者按依赖锁自行建立环境。

Python 命令在 `model-gateway/` 执行 `python -B -m pytest -p no:cacheprovider -p anyio.pytest_plugin tests -q`；诊断命令从根目录执行 `python -B -m unittest discover -s scripts/tests -v`。离线诊断有一个固定 Fixture 的 Git index 校验，因此验收前只将两份已审查 Fixture 白名单暂存，新根提交仍在全部验收后才创建。

### 本次失败与修复记录

1. 第一次 Maven 尚未进入测试：部署镜像 repository ID 与现有缓存 `central` 不一致，离线无法解析 parent。改为本次临时空 settings，不修改原配置、全局配置或旧仓库。
2. 随后执行 417 项：0 failures、10 errors、0 skipped。DJL 缓存目录缺少 Windows native 依赖，导致 Tokenizer 初始化失败。切换到已存在、完整的用户级 DJL 缓存后，417 项全部通过；未删除或跳过相关测试。
3. 该次打包仍因固定 Spring Boot 4.0.7 插件的缺失依赖失败；普通沙箱无法写用户 Maven 缓存。经受控权限补齐公开构建插件依赖后，完整离线 verify 成功。独立调用 repackage 仅用于解析插件依赖，其过程曾因缺少同一 lifecycle 的源 artifact 返回失败，不能当成构建通过；最终以上表完整离线命令为准。
4. Python 启动器第一次关闭自动插件加载但未显式加载 AnyIO：1 passed、50 skipped。该次不接受为验收通过；保留报告并显式加载插件后，51 项全部执行通过。没有改测试断言。

相关原始日志/XML 仅保留在忽略的本机 `.local/snapshot-audit/` 和 `target/`，不复制旧日志、不进入提交。本文保存脱敏原因和统计，不输出敏感环境或模型消息。

## 资源许可与身份

上游固定基线 Apache-2.0 LICENSE 与本快照全文一致，根 NOTICE 保留并补充新快照改动。两项 MiniLM 模型的官方固定 revision、Apache-2.0 声明、SHA-256、大小、本地完整文件和 LFS 指针均一致。

ECharts 6.1.0、word-cloud 1.0.1、liquid-fill 1.0.2、Marked 15.0.0、Highlight.js 11.9.0 的官方制品/许可均已核对；三项 ECharts NOTICE 与制品一致。详见[第三方资源](third-party-resources.md)。直接携带资源没有未解决许可阻塞；不将该结论扩大为对全部传递依赖、未来 JAR 或容器再分发的许可保证。

两项资源在新根建立自己的 LFS 对象，没有复制旧 `.git/lfs`。首次提交前已逐项检查 index 的 LFS 指针、全新 `.git/lfs/objects` 中的实际对象、完整字节数与 SHA-256，均相符。未来新远端 LFS 额度、上传及重新克隆尚未验证。

## 专用扫描与最终提交检查

Gitleaks 固定 v8.30.1 Windows x64，使用已核验的官方工具，其可执行文件 SHA-256 为 `17157e2ee8b76fc8b1d8bee607a250e34b8a8023c8bc81822d4b5ee4d78fcb7c`。执行内置规则、`--redact=100`、忽略内联豁免；报告在内存解析，只保留规则/位置等脱敏元数据，不保存 Secret 或 Match。

| 扫描范围 | 实际结果 |
|---|---|
| 当前完整候选快照，290 个文件，含实际模型资源 | 1 条命中，已确认误报；0 条未解决发现 |
| 从 Git index 独立导出的拟提交内容，290 个文件，模型为 LFS 指针 | 同一条误报；0 条未解决发现 |

误报为 `src/test/java/com/example/rag/chat/rag/EmbeddingModelResourceTest.java:32–33` 的固定 `TOKENIZER_SHA256`，规则为 `generic-api-key`。该值与已核对的官方 Tokenizer SHA-256 一致，不是 API Key、测试凭据或授权令牌。保留原测试常量，不改名、不加 inline allow、不关闭规则、不增加扫描 baseline。Gitleaks 原始退出码因此为 1；本报告不伪称“scanner 零命中/退出码 0”。分类时只保留位置和固定原因，不保存可能包含密钥的 Match/Secret 字段。

本次未发现真实凭据、可拨号手机号或本机用户主目录进入候选/拟提交内容；合成测试值和模型摘要与真实秘密区分。首次 Git LFS 暂存被沙箱阻止本地 shell 信号管道，随后仅对新仓库的同一明确白名单经受控权限完成暂存，没有复制旧 Git 或连接远端。`git diff --cached --check` 通过。

手机号格式、Windows 用户主目录、企业/联系人候选另做静态检查。数据字段名称、技术说明、官方 Tokenizer 词表和开源许可作者声明与业务数据区分。源代码中的本地测试 Key/占位符不代表真实凭据。

宽松手机号模式另命中清单 SHA-256 字段中的两处数字子串，已按完整 64 位十六进制资源摘要字段分类，非联系人号码；没有为消除命中而改资源摘要。非 loopback IPv4 模式唯一候选来自历史文档中四段 JDK 版本号，不是服务器地址。上述隐私模式检查不与 Gitleaks 的一条模型摘要误报混为同一统计。

与基础文件摘要比较：136 份 Java 生产源码保持逐字一致。两个测试文件只有合成客户名称替换；其余差异为 SQL seed、UI 合成标签、NOTICE 与文档/安全配置。没有修改原开发仓库或旧发布仓库的文件、分支和提交。

## 质量与后续发布边界

历史 16/45 错误拒答、复杂证据覆盖不足、SSE 限制、缺少完整历史模型上下文及生产身份认证不足继续保留。新根和回归通过不证明最终回答准确率，也不证明邻句扩展改善；个人贡献与上游已有能力按 README/NOTICE 分开。

满足本地验收后，仅建议准备上传到**另建的新 Private 仓库**，仍需用户单独授权创建远端及推送。不对旧 Private 仓库 force push，不推送任何旧引用，不在本轮公开仓库或发布二进制。
