# 新安全快照：来源与直接携带资源许可

2026-10-26 重新核对。源码以 [ly1836/spring-ai-rag-demo 的固定基线](https://github.com/ly1836/spring-ai-rag-demo/tree/b0bd1fd571e57e4943b5768a41bef0dba86852b3) 为上游；官方该提交 LICENSE 与本快照 Apache-2.0 全文一致。保留原源码及供应商声明，根 NOTICE 说明上游和个人增量，不把下游 NOTICE 冒称上游原文。

## 模型与新根 Git LFS

模型为 `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`，固定 revision `e8f8c211226b894fcb81acc59f3b34ba3efd5f42`。本次读取[官方固定 revision 元数据](https://huggingface.co/api/models/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/revision/e8f8c211226b894fcb81acc59f3b34ba3efd5f42?blobs=true)，确认模型卡许可证 `apache-2.0`，两项 LFS 大小和 SHA-256 如下，均与旧发布 main 指针及本次独立复制的完整资源一致。

| 本地资源 | 官方路径 | 字节数 | SHA-256 / 新 LFS OID |
|---|---|---:|---|
| `src/main/resources/models/embedding/model.onnx` | `onnx/model_quint8_avx2.onnx` | 118453870 | `98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f` |
| `src/main/resources/models/embedding/tokenizer.json` | `tokenizer.json` | 9081518 | `2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8` |

附 [Apache-2.0 全文](licenses/EMBEDDING-LICENSE.txt)，保留 [MODEL.md](../src/main/resources/models/embedding/MODEL.md) 的模型身份与运行约束。384 维、128 token、mask 平均池化、x86-64 AVX2；模型不由本项目训练。新仓库只从完整文件建立自己的 LFS 对象，不复制旧 `.git/lfs`，不迁入旧模型历史。新远端尚未创建，远端 LFS 存储额度和下载可用性未验证。

## 必要浏览器资源

本次取得以下固定版本的官方 npm 包元数据及压缩包，核对 registry `dist.integrity`。JS 与包内制品逐字相同；Highlight.js 的预构建 JS/CSS 另与官方 `highlightjs/cdn-release` 的 11.9.0 标签对应文件比较一致。包内 LICENSE 与附带许可逐字规范换行后相同，三项 ECharts NOTICE 也与对应包一致。压缩包仅用于本地核对，没有执行安装脚本或随仓库分发。

| 文件 / 包 | 固定版本 | 许可 | 文件 SHA-256 |
|---|---|---|---|
| `echarts.min.js` / `echarts` | 6.1.0 | Apache-2.0 | `b66b25aeb4df84e33199dc21694014d336d222cbd9deb0e5a7c14bd6aa0d0fd0` |
| `echarts-custom-word-cloud.auto.js` / `@echarts-x/custom-word-cloud` | 1.0.1 | Apache-2.0 | `cd512d1af5016711fe196439d9c8eb3efe1eb54ae7003472172f81d86f3e427a` |
| `echarts-custom-liquid-fill.auto.js` / `@echarts-x/custom-liquid-fill` | 1.0.2 | Apache-2.0 | `e11ce65f8e8fd7f4271fbebc412e6382ba5f912d5429f9d962694f00477db2ba` |
| `marked.min.js` / `marked` | 15.0.0 | MIT 及原许可中的继承声明 | `82c375b84779649a4a04248d75e1d4a6124e7378c48d516bccf176455e439940` |
| `highlight.min.js` / `highlight.js` | 11.9.0 | BSD-3-Clause | `837a6fa5b0c736b52bbde2b2b6190f305da3fc9ed41681db5321507057b5c846` |

前端 ECharts 许可/NOTICE 在 [vendor 目录](../src/main/resources/static/vendor/)，Marked、Highlight.js 在 [licenses](licenses/)。CSS 的单独摘要及其他文件摘要列入快照清单。上游六张 PNG、GIF 和非必要第三方图片全部排除，不沿用旧截图的隐私/权利假设。

## 依赖与分发范围

`pom.xml` 与 `model-gateway/requirements.lock` 保留公开依赖坐标/版本；本源码快照不携带 Maven/Python 缓存、虚拟环境、依赖 JAR、原生 DLL 或打包应用。离线测试使用本机已经存在或补齐的公开构建依赖；它们不进入 Git。

本次许可结论针对仓库直接携带的源码、合成材料、模型及前端制品，不是对未来 JAR、Docker 镜像或全部传递依赖再分发义务的担保。尤其不能把根 Apache-2.0 声明扩展成覆盖所有独立依赖的统一许可；发布二进制或镜像需另外审查。
