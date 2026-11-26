# Enterprise RAG & ERP Assistant

[简体中文](README.md)

Enterprise RAG & ERP Assistant uses Java 17, Spring Boot and Spring AI to answer questions about documents and ERP data. It supports document retrieval, ERP tool calling, ordinary chat responses and Server-Sent Events (SSE).

The project is an Apache-2.0 derivative of [ly1836/spring-ai-rag-demo](https://github.com/ly1836/spring-ai-rag-demo), based on upstream 4.0.0 (`b0bd1fd`). Core RAG, ERP tools, conversation management and charts originate upstream. Downstream changes cover SSE response handling, model and tool protocol tests, an optional model gateway, and retrieval and answer-quality diagnostics. [Provenance and change history](docs/publication-sync.md)

## Features and architecture

- **Knowledge retrieval.** Documents are parsed and split before indexing. A local ONNX model produces 384-dimensional embeddings. PostgreSQL with PgVector retrieves candidates, and the application filters them by tenant, knowledge base, embedding identity and eligible document version.
- **ERP queries.** Spring AI can invoke Java business tools against MySQL. Read-only SQL tools are subject to data-source and query constraints. Responses can include charts based on current tool results.
- **Response modes.** `knowledge` retrieves documents, `data` enables ERP tools, and `auto` combines managed retrieval with tool calling. The Java application handles conversation state and citations. Its streaming processor handles markers that span chunks while retaining ERP business fields.
- **Optional model gateway.** FastAPI and httpx provide timeout and error handling and check SSE completion for a local test upstream. Java owns RAG and ERP execution and also supports direct model providers. [Model gateway](docs/model-gateway.md)

The [system diagram](README.md#技术架构与执行流程) shows the ingestion path, online retrieval, tool execution and SSE response handling.

## Quick start

Install JDK 17, Maven and Git LFS. Docker Compose can run the local databases. Python is optional for the model gateway.

~~~bash
git clone https://github.com/qianmu233/enterprise-rag-erp-assistant.git
cd enterprise-rag-erp-assistant
git lfs install
git lfs pull
mvn verify
~~~

Set `POSTGRES_PASSWORD` and `ERP_DB_PASSWORD` in the shell and start the local databases.

~~~bash
docker compose up -d pgvector mysql
~~~

Set the matching `SPRING_DATASOURCE_PGVECTOR_PASSWORD` and `SPRING_DATASOURCE_ERP_PASSWORD` for the Java process and configure the API key for the selected provider.

~~~bash
mvn spring-boot:run
~~~

The default URL is `http://127.0.0.1:8080`. The Compose `app` service uses the upstream image by default. The Maven command runs the current checkout. Local protocol tests can use fake model responses. [Runtime examples](docs/p2-demo.md)

| API | Use |
|---|---|
| `GET /api/ask` | Get a standard answer |
| `GET /api/ask/stream` | Receive typed SSE events and the conversation ID |
| `GET /api/search` | Inspect retrieval results |

Send `question` and `mode` to the question endpoints. For knowledge queries, you can specify `knowledgeBaseId`. The application reads `X-Ent-Code` and `X-User-Id` as context headers. A deployment needs a trusted identity layer to supply them.

## Tests and evaluation

| Scope | Recorded result |
|---|---:|
| Maven `verify` for the release snapshot | 417 passed |
| Python gateway contracts, historical run | 51 passed |
| Python offline diagnostics | 16 passed |
| Python dependency consistency check | passed |

Three historical RAG evaluations used the same 50 questions. Empty final answers fell from **25/50 to 22/50 to 0/50**. Literal key-fact phrase matches were **36.73%, 39.80% and 53.06%**. In round three, 16 of 45 answerable questions still received the standard refusal. [Evaluation summary](docs/rag-evaluation-summary.md)

Six separate offline retrieval experiments compared similarity thresholds, Top-K settings and adjacent chunk selection. They measure effects on evidence coverage, context length and non-empty retrieval for unanswerable questions. [Offline comparison](docs/p2-rag-quality-comparison.md) · [Test report](TEST_REPORT.md)

## Technical documentation

- [Runtime examples, knowledge and ERP flows](docs/p2-demo.md)
- [Optional model gateway](docs/model-gateway.md)
- [Historical publication snapshot and validation](docs/SAFE_SNAPSHOT_AUDIT.md)
- [Publication provenance and change history](docs/publication-sync.md)

## License and third-party resources

The project uses the [Apache License 2.0](LICENSE). The upstream baseline, downstream changes, bundled model provenance and vendor notices are documented in [NOTICE](NOTICE) and [Third-party resources](docs/third-party-resources.md).
