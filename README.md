# 知源 · 个人知识库（MVP-0）

单用户、自托管的 AI 增强型个人知识库最小 Demo：**你写过的笔记，能被 AI 基于你自己的内容回答，且答案可溯源。**

```
笔记 CRUD → 按 ## 标题切片 → Embedding(OpenAI 兼容, 哈希缓存) → pgvector 余弦 Top-K
    → 片段带 [1][2] 编号拼 Prompt → LLM 流式生成 → 引用卡片跳回原笔记
```

## 快速开始（两条命令）

前置要求：JDK 17+、Docker、[Ollama](https://ollama.com)（本地模型服务）。

```bash
# 准备模型：对话用 llama3.1:8b，向量化用 bge-m3（生成模型不能做向量化，必须单独配 embedding 模型）
ollama pull llama3.1:8b
ollama pull bge-m3
```

**1. 启动 PostgreSQL（pgvector）**

```bash
docker compose up -d
```

**2. 启动应用**

```powershell
# Windows PowerShell
.\mvnw.cmd spring-boot:run
```

```bash
# macOS / Linux
./mvnw spring-boot:run
```

浏览器打开 <http://localhost:8080>。

> - 首次运行 Maven Wrapper 会自动下载 Maven 与依赖，需要网络。
> - 默认对接本地 Ollama（`http://localhost:11434/v1`），无需任何 API Key。
> - Ollama 未启动时应用仍可启动，笔记 CRUD 可用；提问会返回"无法连接模型服务"的提示。

### 模型服务说明

默认走**本地 Ollama**：对话模型 `llama3.1:8b`，向量模型 `bge-m3`（1024 维、中英文友好）。也可换任何 OpenAI 兼容云 API（如阿里云百炼、DeepSeek、SiliconFlow），通过环境变量覆盖：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `RAG_API_BASE_URL` | `http://localhost:11434/v1` | OpenAI 兼容基地址 |
| `LLM_API_KEY` | `ollama` | API Key（Ollama 无需鉴权，占位即可；云 API 时必须设置） |
| `RAG_CHAT_MODEL` | `llama3.1:8b` | 对话模型 |
| `RAG_EMBEDDING_MODEL` | `bge-m3` | 向量模型 |
| `PKB_DB_URL` / `PKB_DB_USER` / `PKB_DB_PASSWORD` | `jdbc:postgresql://localhost:5432/pkb` / `pkb` / `pkb123` | 数据库连接 |

> **切换向量模型后必须重建索引**（不同模型的向量空间不互通）：
>
> ```bash
> docker exec pkb-postgres psql -U pkb -d pkb -c "TRUNCATE embedding_cache; UPDATE chunk SET embedding = NULL;"
> ```
>
> 然后在页面上逐篇重新保存笔记（或修改后保存）即可重新向量化。只清 chunk 不清 embedding_cache 无效——缓存会把旧模型的向量重新灌回。

> 本地 8B 模型的中文指令遵循度有限：答案质量和角标引用的稳定性不如云端大模型属正常现象，追求效果可 `ollama pull qwen2.5:7b` 后设 `RAG_CHAT_MODEL=qwen2.5:7b` 对比。

模型与检索参数（`src/main/resources/application.yml` 中 `rag.*`）：`chat-model`（默认 llama3.1:8b）、`embedding-model`（默认 bge-m3）、`embedding-dimension`（1024）、`top-k`（5）、`min-similarity`（0.30，低于该阈值直接回答"未找到"，不调 LLM）。

## 验收自测（对应 PRD §0.4）

1. 新建 3 篇笔记：`JVM GC`、`CMS 与 G1 的区别`、`OOM 排查`（正文用 `##` 分节）
2. 提问 **"CMS 和 G1 有什么区别？"** → 答案带 [1] 角标，引用卡片指向《CMS 与 G1 的区别》
3. 提问 **"Redis 持久化原理"**（库外问题）→ 回答"知识库中未找到相关内容"，不编造
4. 修改《CMS 与 G1 的区别》内容并保存 → 重新提问 → 答案反映修改后内容（切片整体替换，无残留）
5. 1000 条切片规模下检索耗时 < 500ms

## 技术栈与结构

Spring Boot 3.5（web + jdbc/JdbcClient，无 ORM）· PostgreSQL 16 + pgvector（HNSW 余弦索引）· OpenAI 兼容 Chat/Embedding（接口抽象，换厂商只换实现）· 前端为 Boot 托管单页（Vue3 + marked CDN，免 npm 构建）

```
src/main/java/com/pkb/
├── PkbApplication.java        # 启动类 + SSE 异步线程池
├── config/                    # RagProperties 配置；SchemaInitializer 启动建表(维度可配)
├── note/                      # 笔记 CRUD + 保存时切片/向量化编排(整体替换、失败不阻塞保存)
├── chunk/                     # 切片策略(## 标题 + 500字定长重叠50) + chunk/embedding_cache 仓储
├── llm/                       # ChatClient/EmbeddingClient 接口 + OpenAI 兼容实现 + 向量编解码
├── search/                    # pgvector 余弦 Top-K(相似度阈值在 SQL 层过滤)
└── rag/                       # RAG 编排(检索→Prompt→流式) + SSE 接口(/api/chat, /api/config)
```

关键设计（面试可讲）：

- **先落库后建索引**：Embedding 失败不阻塞笔记保存，切片以无向量形式入库，重新保存即重建（PRD 可靠性要求）
- **内容 SHA-256 哈希缓存**：`embedding_cache` 按内容哈希去重，重复内容不二次调用 Embedding（成本控制）
- **相似度阈值护栏**（min-similarity=0.30）：低于阈值直接回答"未找到"，不调 LLM——防编造且省钱
- **SSE 协议**：`sources`（引用数组）→ `delta`（增量文本）→ `done` / `error`，前端 fetch 流式解析
- **切片整体替换**：更新笔记先删旧切片再重建，保证无脏切片残留
