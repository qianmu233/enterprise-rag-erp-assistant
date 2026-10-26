# 全新根的白名单快照

本快照的文件基础为旧独立发布 main `3efdbd00840f5f168437c5a77244b97a98368555`。仅使用该提交的 Git blob 内容与经完整哈希核对的两个 LFS 实际模型资源，不 clone、不 merge、不 cherry-pick，不复制旧 `.git`、refs、对象库、钩子或历史日志。

原始开发历史与旧发布历史都不是新根的祖先。文件清单见 [SAFE_SNAPSHOT_MANIFEST.json](SAFE_SNAPSHOT_MANIFEST.json)；来源哈希只用于说明文件依据，不把来源 commit 引入新 Git 对象库。

白名单包括 Java 源码/测试、净化后的运行配置与合成 seed、固定技术 Fixture、可选 Python Gateway 与测试/依赖锁、离线诊断工具、必要构建文件、许可、模型及受审核文档。旧截图/GIF、聊天日志、编辑器/OpenSpec 工作记录、原开发 `test-data/`、旧评测运行结果、缓存和原始 `.env` 均不迁入。

已核对 `experiment/rag-quality-improvement` 的 `dc90a7234f654a37952dd1a221b0b72af4e1d733` 不属于基础 main 的祖先。该实验的 Java 扩展器、测试、脚本分支及实验报告不在新快照中。现有离线重建工具仍有实验性邻片对照，但不进入生产检索，也不代表真实模型质量改善。

与基础文件的明确差异：新合成 SQL seed、合成 UI/测试名称、独立根的忽略/LFS/换行规则、公开说明及许可复核。Java 生产检索、模型 Gateway、提示词及原测试断言未做功能优化。

本轮不配置远端、不创建 GitHub 仓库、不推送、不改变旧 Private 仓库可见性。未来如获授权，只能从本目录的新 main 推送到单独的新 Private 仓库；不要把旧开发或发布目录的任何引用混入。
