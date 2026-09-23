# 知源（rag-knowledge）RAG 系统改造设计文档 v2

| 项 | 内容 |
|---|---|
| 文档版本 | v2.0（设计稿，待评审） |
| 编写日期 | 2026-09-22 |
| 上游文档 | `PRD.md`（v1.0，MVP-0 需求）· `README.md`（当前实现说明） |
| 配套文档 | `rag-knowledge-简历化路线图.md`（为什么做、怎么换成简历）· `ragent/rag-review-notes.md`（rabgent 参考实现的审查结论） |
| 定位 | **MVP-0 → v1 的完整设计与需求**。本轮**只做这一个项目** |
| 冻结目标 | 10/7 冻结（对齐 `秋招-17天补救方案.md`） |

**硬约束（整份文档的设计前提）**

1. 单用户、单机、自托管：**不引 ES / Redis / MQ / Milvus / 分布式锁 / 多租户**；
2. 依赖只有两个：PostgreSQL 16 + pgvector（Docker），本地 Ollama（bge-m3 + llama3.1:8b）；
3. **clone 后两条命令能跑通**（`docker compose up -d` + `./mvnw spring-boot:run`），不要求任何 API Key；
4. 已有前端与 SSE 协议**只增不改**（不破坏 MVP-0 的可用性）。

---

## 1. 现状基线与缺陷

### 1.1 已实现（对照代码核实）

| 能力 | 实现位置 | 状态 |
|---|---|---|
| 笔记 CRUD | `com.pkb.note.{Note,NoteController,NoteRepository,NoteService}` | ✅ |
| 切片：`##` 标题 + 500 字定长 / 重叠 50 | `com.pkb.chunk.ChunkService` | ✅ |
| Embedding + 内容哈希缓存 | `com.pkb.llm.OpenAiCompatClient`、`embedding_cache` 表 | ✅ |
| pgvector 余弦 Top-K + SQL 层阈值过滤 | `com.pkb.search.VectorSearchService` | ✅ |
| 阈值护栏（`sources` 为空 → 不调 LLM 直接兜底） | `com.pkb.rag.RagService#chat` L72-79 | ✅ 代码在，**配置失效**（见 F1） |
| SSE 流式：`sources` → `delta` → `done`/`error` | `com.pkb.rag.RagService` | ✅ |
| 引用溯源到分块（`sources` JSONB 可回放） | `chat_message.sources` + `ChatMessage` | ✅ |
| 多会话并发问答 + 最近 3 轮历史 | `com.pkb.conversation.*` | ✅ |
| 文件导入（md/txt 直读 + Tika 多格式）+ 文本清洗 | `com.pkb.ingest.*` | ✅ |
| 检索层离线评测 + 阈值扫描 | `com.pkb.eval.{RetrievalEvalTest,MinSimilaritySweepTest,EvalSupport,EvalSample}` | ✅ |

代码规模：生产 28 个类约 1554 行，测试 4 个类 418 行。

**库内实际数据量**（`docker exec pkb-postgres psql` 实测，2026-09-22）：

| 表 | 行数 | 说明 |
|---|---:|---|
| `note` | 6 | 语料规模小 |
| `chunk` | 253 | **253 条 `embedding` 全部非空** ✅ |
| `conversation` | **1** | ⚠️ 问答链路基本没被真实使用过 |
| `chat_message` | **2** | ⚠️ 同上 —— 这是"生成层没有度量"的直接证据 |

> 三个含义：
> ① **检索确实有真实语料**（253 切片支撑了 16 条库内样本的评测），不是空库跑分；
> ② **问答链路几乎没被端到端用过**（1 会话 / 2 消息）→ D7 生成层评测与 D8 护栏是所有缺陷里最欠账的部分；
> ③ **253 切片 < NFR-1 要求的 1000 切片** → 测检索性能指标前，必须先把语料灌到 1000 条（否则那条指标没有意义）。

### 1.2 实测基线（2026-09-22 17:39 / 17:42 surefire 真实输出）

```
评估集 eval_set_v1.jsonl｜20 条（库内 16 / 库外 4）
embedding=bge-m3  topK=5  min-similarity=0.30
Hit@1 68.8%  Hit@3 81.3%  Hit@5 81.3%
Recall@5(must) 81.3%  Recall@5(inclusive) 84.4%  MRR 75.0%
误拒率 0.0%   过召回率 100.0%
```

阈值扫描（`topK=5`）：

| 阈值 | Hit@5 | Recall@5(must) | 误拒率 | 过召回率 | 丢证据 |
|---:|---:|---:|---:|---:|---|
| 0.30 ← 当前 | 81.3% | 81.3% | 0.0% | **100.0%** | — |
| 0.55 | 81.3% | 78.1% | 0.0% | 50.0% | E10(-1) |
| **0.60** | **81.3%** | **78.1%** | **0.0%** | **0.0%** | E10(-1) |
| 0.65 | 68.8% | 65.6% | 12.5% | 0.0% | E08(-1) E10(-2) E14(-1) |
| 0.70 | 37.5% | 37.5% | 56.3% | 0.0% | 7 个样本 |

相似度分布：**信号下限 0.608（E08） vs 噪声上限 0.578（N04）→ 存在完美分界区间 0.578~0.608**。

> 这份分布数据是本设计文档的关键依据：**它证明"用余弦分做批级闸门"在这个语料上是可分的**，不需要先引入精排模型。

### 1.3 缺陷清单（F1–F8，每条都有证据）

| # | 缺陷 | 证据 | 影响 |
|:--:|---|---|---|
| **F1** | **护栏配置失效**：`VectorSearchService` 用 `maxDist = 1 - minSimilarity` 在 SQL 层过滤，`min-similarity=0.30` 时库外样本**全部通过** | 过召回率 **100%（4/4）** | 「防编造」的核心承诺不成立 |
| **F2** | **拒答话术口径三处不一致**：README §0.4-2 写「知识库中未找到相关内容」；`RagService.NOT_FOUND_ANSWER` 是「知识库中未找到与该问题相关的内容。可以先在左侧补充相关笔记……」；`SYSTEM_PROMPT` 规则 3 又要求「必须直接回答"知识库中未找到相关内容"」 | 三处文本互不相同 | **"拒答准确率"这个指标现在无法定义分子** |
| **F3** | **生成的角标无任何校验**：模型漏打角标不会被发现 | 实测探针 A（见 §1.4） | 「可溯源」只靠模型自觉 |
| **F4** | **复合问题单次检索必然漏一半** | E01「Minor GC 和 Full GC 分别在什么情况下触发？」期望 `5:0`，**完全没进 Top-5**（阈值调到 0 也救不了） | 一类问题结构性答不全 |
| **F5** | **分块参数硬编码且无边界校验**：`ChunkService.MAX_CHUNK_CHARS=500` / `OVERLAP_CHARS=50` 为 `static final` 常量；导入的 pdf/docx 纯文本无 `##` 结构，整篇按定长切 | `ChunkService` L17-18 | 无法做切片策略对比实验，也无法回答"为什么这么切" |
| **F6** | **候选池 = topK = 5，无扩池、无多通道、无融合** | `VectorSearchService` L36 `LIMIT :k` | 检索上限被硬锁在单通道 Top-5 |
| **F7** | **无生成层评测**：只评检索（Hit/Recall/MRR/误拒/过召回） | `com.pkb.eval` 只有检索测试 | 「答案对不对、引用准不准」无度量 |
| **F8** | **索引同步执行、失败只打日志**：无重试、无全量重建入口 | `RagService`/`NoteService` 注释「失败不阻塞保存」 | Embedding 挂了会留下永不建索引的笔记 |

### 1.4 生成层实测（2026-09-22，直接打 `llama3.1:8b`）

**探针 A（库内多跳问题，`topK` 无关，只测模型合规性）**
资料：`[1] G1 把堆划分为 Region…` `[2] CMS 用标记-清除，会产生内存碎片。`
问题：`CMS 会产生内存碎片吗？G1 是怎么避免碎片的？`
输出节选：
```
* CMS 使用标记-清除算法，会产生内存碎片。[2]
* G1 收集器把堆划分为多个 Region，默认约 2048 个…… (但资料没有提及如何避免内存碎片)
```
→ **G1 那句完全没有角标**，尽管 `SYSTEM_PROMPT` 规则 2 明确要求"每个论断都必须标注"。**F3 成立。**

**探针 B（库外问题）** 问题：`Redis 的持久化有哪两种方式？`
输出：`无法提供答案，因为问题与参考资料无关。`
→ 模型没有使用指定话术。**但注意**：这不是主要问题 —— 因为 `RagService` 里**本来就不该让 LLM 走到这一步**（护栏应该在检索侧拦掉，直接返回 `NOT_FOUND_ANSWER`）。探针 B 说明的是：**一旦护栏失效（F1），拒答就退化成"依赖模型自觉"，而模型自觉是不可靠的**。

> **由此得出本设计的核心原则（§3 D8）：把"不编造"从"依赖模型遵守指令"改成"检索侧闸门决定是否调用 LLM"。**架构方向 MVP-0 已经是对的（`sources.isEmpty()` 分支在调 LLM 之前），要做的只是让护栏**真的会触发**。

---

## 2. 目标架构

### 2.1 改造后的问答链路

```
问题 + 历史
  → ① 问句归一（词表映射，纯规则，零 token）
  → ② 改写 + 多问句拆分（LLM，temperature=0.1；失败 → 规则切分兜底）
  → ③ 子问题并行召回（各自独立扩池）
         向量通道：pgvector 余弦 Top-candidateLimit
         关键词通道：tsvector(bigram) + GIN，ts_rank Top-candidateLimit
  → ④ 后置处理器链（责任链，按 order）
         Dedup(1) → Fusion·RRF(5) → ScoreFill(8) → EvidenceGate(15)
  → ⑤ 闸门判定
         max(gateScore) < 阈值  → 整批丢弃 → 直接返回「未找到」，**不调 LLM**
  → ⑥ Prompt 组装（子问题命中合并去重 → 重编号 [1..n]）
  → ⑦ LLM 流式生成
  → ⑧ 生成后校验（角标覆盖率，**只埋点与评测，不改写答案**）
  → ⑨ SSE：meta(归因) → sources → delta* → done(reason)
```

### 2.2 新增扩展点

| 接口 | 职责 | 实现 | 开关 |
|---|---|---|---|
| `SearchChannel` | 一通路的召回，返回原始有序列表 + 分数 | `VectorSearchChannel`、`KeywordSearchChannel` | `rag.channels.*.enabled` |
| `SearchResultPostProcessor` | 对候选集做一次变换（去重/融合/打分/闸门） | `Deduplication`、`Fusion`、`ScoreFill`、`EvidenceGate` | 各自 `isEnabled()` |
| `QueryRewriter` | 改写与拆分 | `LlmQueryRewriter` + `RuleBasedSplitter` 兜底 | `rag.rewrite.enabled` |
| `TermMapper` | 词表归一 | `JdbcTermMapper` | `rag.term-mapping.enabled` |
| `IndexTaskStore` | 异步索引任务 | `JdbcIndexTaskStore` | `rag.index.async-enabled` |
| `AnswerJudge` | 生成层评分（评测用，不进生产链路） | `LlmAnswerJudge` | 仅测试 |

---

## 3. 设计点逐条

### D1 后置处理器链（责任链）

**目标**：把 `VectorSearchService.search()` 这个"一个函数干完"的黑盒，改成可插拔、可开关、可单独测试的链。

**关键设计**

1. 接口四方法：`getName()` / `getOrder()` / `isEnabled(SearchContext)` / `process(...)`。
   Spring 注入 `List<SearchResultPostProcessor>` 后按 `getOrder()` 排序，**与 ragent 的 order 约定对齐**：
   `Dedup(1) → Fusion(5) → ScoreFill(8) → EvidenceGate(15)`。
2. `isEnabled(context)` 是**降级能力的前提** —— 每一环都能独立关掉，关掉后链路仍然正确（fail-open）。
3. **任一处理器抛异常不得中断主链路**，但**必须 WARN + 记录"该环降级"标记**（rabgent 的 `MultiChannelRetrievalEngine` 只 log 不标记，导致 Rerank 挂掉后静默返回融合序，无法事后归因 —— 这是要避开的坑）。
4. 链的输入输出结构固定：`List<RetrievedChunk> process(List<RetrievedChunk>, List<SearchChannelResult>, SearchContext)`。
   **同时拿到"去重后的集合"和"各通道原始列表"**，因为 RRF 需要原始名次（见 D3）。

**类与包**
```
com.pkb.search.channel.{SearchChannel, VectorSearchChannel, KeywordSearchChannel,
                         SearchChannelResult, SearchChannelType, SearchContext}
com.pkb.search.postprocessor.{SearchResultPostProcessor, DeduplicationPostProcessor,
                               FusionPostProcessor, ScoreFillPostProcessor, EvidenceGatePostProcessor}
com.pkb.search.RetrievalEngine        // 串链、计时、归因
com.pkb.search.RetrievedChunk         // 候选实体（见下）
```

**`RetrievedChunk` 字段**（替代当前的 `Source`，`Source` 降级为"送给前端与 Prompt 的视图"）
```
noteId, seq, noteTitle, content
vectorScore   // 余弦相似度，可空
keywordScore  // ts_rank，可空
rrfScore      // 融合分，可空
gateScore     // 闸门读的分（由 ScoreFill 填充），可空
hitChannels   // EnumSet<SearchChannelType>，用于归因与"多路命中"加成
```

**验收**
- 单通道 + 全部处理器关闭时，检索结果与改造前**逐条一致**（回归基线）；
- 关闭 `Fusion` 但开启两个通道时，链路不报错（退化为向量序 + 关键词结果并集）；
- 处理器抛异常时：结果仍然返回、日志有 `degraded=[Fusion]` 标记。

---

### D2 关键词通道（含中文分词方案 —— 本设计的最大技术取舍）

**目标**：补上"术语精确匹配"通道。E01/E11/E12 这类失败样本，本质是**语义向量对专有名词不敏感**（`ConcurrentHashMap`、`ThreadLocal`、`Minor GC`），而这正是关键词检索的强项。

**关键取舍：中文分词怎么做**

**已实测**（`docker exec pkb-postgres psql -c "select name, default_version, installed_version from pg_available_extensions"`，2026-09-22）：

```
   name    | default_version | installed_version
-----------+-----------------+-------------------
 pg_trgm   | 1.6             |
 unaccent  | 1.1             |
 vector    | 0.8.6           | 0.8.6
```

→ 镜像 `pgvector/pgvector:pg16` = 标准 PG16 + pgvector：**contrib 可用（`pg_trgm` 在）**，
**`zhparser` / `pg_jieba` 确认不可用**（要装必须自建镜像，违背"clone 两条命令跑通"的约束）。下表据此定案。

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| (a) `pg_trgm` 三元组 | 零扩展、中文有效 | **不是 BM25**：无词频归一、无长度归一，`similarity()` 对长文本普遍偏低，阈值难定 | 备选，做对照实验 |
| **(b) bigram + `tsvector`** | **零新扩展**；`ts_rank` 带词频与长度归一；中英混排可控；索引可重建 | 索引体积约为原文 2 倍 | **✅ 采用** |
| (c) `zhparser` / `pg_jieba` | 分词质量最好 | **必须自建镜像** → 违背可复现约束 | ❌ 不采用 |

**方案 (b) 的具体做法**

1. 应用层切分：`"内存碎片"` → `"内存 存碎 碎片"`；ASCII 词（`ConcurrentHashMap`）整词保留不切；标点分隔。实现为 `com.pkb.search.tokenizer.BigramTokenizer`（**放在应用层而不是 PG 的 generated column**，因为这样换分词器只需改一个类 + 重建索引，不用改 DDL）。
2. 写入 `chunk.tsv`（见 §6 DDL），GIN 索引。
3. 查询侧对每个子问题做同样切分，`plainto_tsquery('simple', :bigrams)` + `ts_rank(tsv, query)` 排序，取 `candidateLimit`。
4. 提供 `POST /api/index/rebuild` 回填 `tsv`（历史数据一次性重建）。

**为什么 `simple` 配置而不是 `english`**：`english` 会做词干还原（`chunks`→`chunk`）并对英文停用词过滤，对中文 bigram 无意义且会误删 ASCII 词。

**验收**
- 关键词通道单独跑评估集：`ConcurrentHashMap`、`ThreadLocal`、`Minor GC` 三类查询的 `Hit@5` 相对向量通道**有提升**（记录提升幅度）；
- 中英混排笔记（含 `ConcurrentHashMap` 这类驼峰词）能正确命中；
- `CREATE EXTENSION` 列表**没有新增**（除已有的 `vector`）。

> **这个取舍本身就是简历素材**：「不引 ES，用 bigram + tsvector 在单库内实现关键词通道，索引体积换零中间件」—— 面试可讲 3 分钟。

---

### D3 RRF 融合（Reciprocal Rank Fusion）

**目标**：把向量分（余弦）与关键词分（`ts_rank`）**量纲不同、不可直接比较**的两路结果合成一路。

**关键设计**

1. 公式：`rrf(chunk) = Σ_channels  weight_ch / (k + rank_ch + 1)`
   - `k = rag.fusion.rrf-k`，默认 **60**（标准 RRF 常数）
   - `weight_ch` 走配置：`rag.fusion.channel-weights.vector=1.0`、`.keyword=0.6`（关键词通道新接入、先降权，避免噪声通道靠名次抢前排）
2. **名次取自各通道的原始列表，不是去重后的 chunks**。否则"被两路同时命中"的信息会丢 —— 这正是 RRF 相对加权求和的核心优势。
   → 所以 `FusionPostProcessor` 的入参必须同时拿到 `List<SearchChannelResult>`（D1 第 4 点）。
3. **候选池截断**：融合排序后按 `rag.retrieval.candidate-limit`（默认 **20**）截断，再送闸门。
   截断上限要 **> topK(5)** —— 这是"粗排扩池 + 闸门精选"两阶段分工能成立的前提。
4. **单通道时跳过融合，只做截断**（`results.size() > 1` 才融合），保证只开向量通道时行为与改造前一致。
5. 多子问题（D6）时：**每个子问题各自跑通道 + 各自 RRF**，然后在合并阶段按 `(noteId, seq)` 全局去重，排序键 = `(命中子问题数 desc, rrfScore desc)`。

**配置**
```yaml
rag:
  fusion:
    strategy: rrf          # rrf | none
    rrf-k: 60
    channel-weights: { vector: 1.0, keyword: 0.6 }
  retrieval:
    candidate-limit: 20    # 扩池上限，须 > top-k
    top-k: 5               # 最终送进 Prompt 的片段数
```

**验收**
- 同一套评估集，`Recall@5(must)` 相对"仅向量通道"**提升**（记录 before → after）；
- 关闭关键词通道后指标回落到基线（证明改进来自关键词通道而非噪声）；
- 日志里有按通道的候选数归因（`pool by channel` vs `sent to gate by channel`）。

---

### D4 证据闸门 + 阈值定档 + 启动期 fail-fast

**目标**：让"库里没答案就不答"从**模型自觉**变成**系统保证**。

**关键设计**

1. **两层护栏，分工明确**
   | 层 | 位置 | 作用 | 配置 |
   |---|---|---|---|
   | 单条过滤 | `VectorSearchChannel` 的 SQL（`WHERE 距离 <= maxDist`） | 省 IO 与计算，拦掉明显不相关的单条 | `rag.min-similarity` |
   | **批级闸门** | `EvidenceGatePostProcessor`（链上 order=15） | **决定要不要调 LLM** | `rag.evidence.min-gate-score` |

2. **闸门是批级判定，不是逐条判定**：取本批 `max(gateScore)` 与阈值比较，**不合格整批丢弃**。
   理由（沿用 ragent 的取舍并保留其原话依据）：**误丢比误放贵**；且逐条判会把"过线证据的弱兄弟"一起砍掉，反而丢上下文。
3. **`gateScore` 由 `ScoreFillPostProcessor`(order=8) 填充**，取值优先级：
   `rerankScore`（若将来接入精排） > `rrfScore` 归一化 > **`vectorScore`（余弦，当前方案）**。
   **当前用余弦是经过数据论证的**：§1.2 的分布显示信号下限 0.608 > 噪声上限 0.578，**存在完美分界**，所以不需要先引入精排模型。
4. **无分可读一律放行 + WARN**（fail-open）：降级路径**绝不能制造假阴性** —— 否则精排出问题时，表现会和"库里真没资料"完全一样，无法事后区分。
5. **启动期 fail-fast**：`afterPropertiesSet` 校验配置一致性 ——
   若 `rag.evidence.min-gate-score > 0` 但**没有任何 scorer 可用**（`channels.vector.enabled=false` 且 `rerank.enabled=false`），**直接抛 `IllegalStateException` 拒绝启动**。
   理由：闸门会"无分可读、恒放行"，等于静默空转 —— **宁可起不来，也不要静默失效**。
   > 这一条正是 F1 的解药：MVP-0 的护栏失效是"配置错了但没人知道"，新设计把这类问题变成"启动即报错"。
6. **阈值定档：`min-similarity: 0.30 → 0.60`**
   依据 §1.2 扫描结果：过召回率 `100% → 0%`、误拒率保持 `0%`、`Recall@5(must)` `81.3% → 78.1%`（代价 3.2pp），全局 `Hit@5` **不变（81.3%）**。
   这是一个**显式的三方权衡**：用 3.2pp 的召回换掉 100% 的过召回。
   ⚠️ **前置条件：库外样本必须先扩到 ≥20 条**（当前分母只有 4，任何百分比都不成立）。

**配置**
```yaml
rag:
  min-similarity: 0.60
  evidence:
    min-gate-score: 0.60     # ≤0 表示关闭闸门
    mode: batch              # batch（整批判）| none
```

**验收**
- 4 条库外样本（扩到 ≥20 后为准）过召回率 `100% → 0%`；
- 库内 16 条误拒率仍为 `0%`；
- 配置矛盾（`min-gate-score>0` + `vector.enabled=false` + `rerank.enabled=false`）时**应用启动失败并给出可读原因**；
- 闸门丢弃时 `done.reason = "no_sources"`，且**实测确认没有调用 LLM**（Ollama 侧无请求日志 / 记一个 `llmCalls` 计数）。

---

### D5 分块用「预算」而不是硬编码字符数

**目标**：把 `ChunkService` 的两个 `static final` 常量升级为可配置、有边界校验的**预算对象**，并支持策略对比实验。

**关键设计**（设计依据来自 ragent 的 `ChunkBudget`，取舍理由逐条保留）

| 设计点 | 理由 |
|---|---|
| **`maxChars` 是目标不是硬上限**，另给 `toleranceChars() = maxChars × toleranceFactor`（默认 3×，封顶 8192） | 「切开语义单元的代价高于超出目标」：宁可超一点，也不要把一个表格/代码块/小节切两半 |
| **默认 `maxChars = 1024`**（从 500 上调） | 「防语义稀释靠**章节边界**而非把块压小；`topK=5` 时块太小会让名额被**同一章节的碎片**占满」—— 直接反驳当前 500 |
| **重叠按块大小等比给**：`defaultOverlapFor(n) = n/8`（1024 → 128） | 「重叠不只冗余，它同时是**回退寻找句末标点的最大距离**」；500/50 对中文长句常常回退不到句号（当前 50 同样偏小） |
| **构造期校验**：`0 ≤ overlapChars < maxChars`、`maxChars ≤ 8192`、`toleranceFactor ∈ [1,8]` | 「否则超长块要到嵌入那一步才炸」；当前 `ChunkService` 没有任何校验 |
| **策略可插拔**：`ChunkStrategy` 接口 | 让"切片策略对比实验"（D5 验收）成为可能，替代现在的隐式单一策略 |

**策略集合（本轮实现两个，够做 A/B）**
- `HeadingChunkStrategy`（现状的改良版）：`##` 标题切 + 预算内不切 + 超预算按句号回退切 + 等比重叠；
- `FixedWindowChunkStrategy`：纯定长 + 重叠（对照组，用于回答"按结构切到底比定长好多少"）。

**配置**
```yaml
rag:
  chunk:
    strategy: heading        # heading | fixed
    max-chars: 1024
    overlap-chars: 128       # 默认 = max-chars / 8
    tolerance-factor: 3
    rows-per-chunk: 50       # 留口：将来表格切片
```

**验收**（这是"为什么这么切"的答案来源）
- 用同一套评估集跑 `heading` vs `fixed`、`500/50` vs `1024/128` 四组，产出 `Recall@5` / `Hit@5` 对比表；
- 单个 chunk 长度分布：`heading` 策略下 >1024 的块占比 < 10%（且都在 `toleranceChars` 内）；
- `overlapChars >= maxChars` 时**启动失败**并提示合法区间。

---

### D6 查询改写 + 多问句拆分（E01 类失败的解药）

**目标**：解决 F4 —— 复合问题（"A 和 B 分别……"）单次向量检索只能命中一个语义中心。

**关键设计**

1. 流程：`归一化(词表映射) → LLM 改写+拆分 → 得到 {rewrite, sub_questions}`；
2. **输出格式严格 JSON**，解析用容错解析（剥离 Markdown 代码块围栏后再 parse）；字段缺失/非法 → 视为失败；
3. **失败降级为规则切分，绝不抛异常**：按 `[?？。；;\n]+` 切分，每段补问号。
   → 这一条在 ragent 里被明确实现（`ruleBasedSplit`），是本设计**必须保留的降级路径**；
4. 调用参数：`temperature = 0.1`、`topP = 0.3`（改写要**稳定**不要发散）；
5. 子问题上限 `rag.rewrite.max-sub-questions = 3`（防延迟与成本失控）；
6. 喂给改写的历史（对齐 ragent 的两条经验）：
   - **保留全部历史提问**（指代的落点绝大多数是用户自己提过的主体）；
   - **助手回复只留最近 2 条**，且**被中断/被限流的半截消息不占名额**（`chat_message` 目前没有状态位 → 见 §6 DDL 新增 `status`）；
7. 检索合并：每个子问题各自跑 D2/D3，再按 `(noteId, seq)` 全局去重，排序键 `(命中子问题数 desc, rrfScore desc)`，**重编号 [1..n] 后再拼 Prompt**（编号必须与 `sources` 数组下标一致，否则引用卡片点错）；
8. **性能**：改写（LLM）与问句向量化（Embedding）**并行发起**，避免串行叠加首字延迟。

**配置**
```yaml
rag:
  rewrite:
    enabled: true
    max-sub-questions: 3
    temperature: 0.1
    top-p: 0.3
    timeout-ms: 3000        # 超时即走规则兜底
```

**验收**
- E01 类复合问题：期望切片进入 Top-5（记录 `before: 未命中 → after: 命中`）；
- LLM 不可用时（停 Ollama 的 chat 模型）走规则切分，问答**仍然可用**，仅拆分质量下降；
- 关闭 `rewrite.enabled` 时，行为与改造前一致（回归基线）。

---

### D7 生成层评测（补上唯一的真正缺口）

**目标**：F7 —— 现在只有尺子量检索，没有尺子量生成。

**三个指标（定义必须无歧义）**

| 指标 | 定义 | 判定方式 | 依赖模型 |
|---|---|---|---|
| **引用支撑率** | `(答案中的角标总数 − 无法映射到本次 sources 的角标数) / 角标总数`（角标总数为 0 时记为 0 并单列计数） | **纯字符串解析**，确定性 | ❌ 不依赖 |
| **拒答准确率** | 库外样本中 `done.reason == "no_sources"` 的占比（**误答率 = 1 − 该值**） | **看 `done.reason`**，确定性 | ❌ 不依赖 |
| **答案正确率** | 给定「问题 + 送入 Prompt 的片段 + 最终答案」，judge 输出 `0 / 1 / 2`（错 / 部分对 / 对） | LLM-as-judge | ✅ 依赖 |

**关键设计：录制与评分分离**（这是 ragent 配套评测仓库 `ragenteval` 的方法论，可以直接带走）

```
阶段 1 录制（Runner）：对评估集逐条调用 /api/chat，把
   (query_id, question, 各通道命中与分数, gateScore, gateDecision,
    sources, answer, done_reason, timings)
 原样写入 eval_record 表 + 导出 JSONL。**此阶段只调生产链路，不调 judge。**
阶段 2 评分（Scorer）：读 JSONL 算全部指标。检索类指标纯计算、**零 token**；
 生成类指标只在需要时调 judge，且 judge 结果回填 eval_record.judge 可复用。
```
这样做的好处：**换一次参数（阈值/权重/分块）只需重跑阶段 1，评分与报告秒级重算**；且历史 run 永久可比。

**报告形态**：`EvalDiffReport` 输出 markdown，形如

| 指标 | before | after | Δ |
|---|---:|---:|---:|
| Recall@5(must) | 81.3% | \_\_% | \_\_ |
| 过召回率 | 100.0% | \_\_% | \_\_ |
| 引用支撑率 | \_\_% | \_\_% | \_\_ |

**judge 可靠性（必须写进文档，否则指标不可信）**
- judge 的 prompt 必须给定"只能依据片段判断"，并要求输出固定格式；
- **从判为"对"的样本里人工抽检 ≥10 条复核**，报告人工一致率；
- judge 与生产用的**不是同一个模型**（用 `qwen3:14b` 或 `deepseek-r1:14b` 当 judge，避免自己评自己）。

**验收**
- 三个指标都有数字，且**每个数字都能说出分母**；
- 支撑率低的具体样本能被 `E01` 式失败明细列出来（可定位到是哪一句漏了角标）；
- 至少一次 `diff` 报告：D4 或 D6 改造前后的完整对比表。

---

### D8 拒答口径统一与「护栏优先」原则

**目标**：F2 + F1 的根治。

**关键设计**

1. **统一为单一事实来源**：新增 `RagProperties.notFoundAnswer`（或 `RagMessages` 常量类），
   Prompt 模板、护栏兜底回答、README 验收标准、评测判据**全部引用同一份文本**。
   当前三处不一致（README / `NOT_FOUND_ANSWER` / `SYSTEM_PROMPT` 规则 3）**必须收敛为一处**。
2. **原则：拒答由检索侧决定，不由模型决定。**
   `sources` 为空或闸门整批丢弃 → **直接返回兜底回答，不调用 LLM**。
   → 于是「拒答准确率」变成一个**确定性指标**（看 `done.reason`），不再受模型话术影响。
3. 模型侧仍保留规则 3（作为第二道防线，防止"检索到了但答非所问"），但**不再依赖它**。
4. **模型不遵守规则时怎么办**：不做"重试让模型改口"（不确定、慢、贵），而是
   **先度量、再决定** —— 用 D7 的引用支撑率量化，若支撑率 < 目标，才考虑
   （a）换 chat 模型（`qwen2.5:7b`）、（b）拆 Prompt、（c）生成后正则补角标。
   **顺序不能颠倒：先有度量，再谈优化。**

**验收**
- 全仓 `grep` 后，「未找到」类文案**只出现在一个地方**；
- README §0.4 的验收标准 2 与代码行为**逐字一致**；
- 评测里库外样本的拒答归因只依赖 `done.reason`，不调用任何模型。

---

### D9 异步索引管道（DB 任务表，**不引 MQ**）

**目标**：F8 —— 索引失败可重试、可全量重建、可观测。

**关键设计**

1. 保存笔记只写 `note` + 投递一个 `index_task`，**同步路径只做轻量校验**；
2. 单线程执行器消费任务，状态机：`PENDING → RUNNING → SUCCESS / FAILED`；
3. **幂等靠部分唯一索引**（不是靠代码判断）：
   `CREATE UNIQUE INDEX ... ON index_task(note_id) WHERE status IN ('PENDING','RUNNING')`
   → 同一笔记不会堆积多个未完成任务，重复保存只是"复用已有任务"；
4. 重试：`attempts < rag.index.max-retry`（默认 3）时按指数退避重排，超限置 `FAILED` 并保留 `last_error`；
5. **全量重建入口**：`POST /api/index/rebuild`（清 `chunk.embedding` 与 `embedding_cache` 后重新投递全部笔记）—— 换向量模型时必须走这条路，README 里那段手写 `TRUNCATE embedding_cache; UPDATE chunk SET embedding = NULL;` 的说明可以删掉了；
6. **为什么不用 MQ（必须写进简历的话术）**：
   > 「单用户单机、单库，"任务持久化 + 重试 + 幂等"用一张表 + 一个执行器就能满足；
   > 引入 MQ 会多一个部署依赖和一段最终一致性窗口，收益是负的。
   > 我保留了任务表这个**可观测、可重放**的中间态，将来要换 MQ 只需替换 `IndexTaskStore` 的实现。」

**配置**
```yaml
rag:
  index:
    async-enabled: true
    max-retry: 3
    worker-threads: 1
    retry-backoff-ms: 2000
```

**验收**
- 停掉 Ollama 的 embedding 服务 → 保存笔记仍成功、任务进入重试、恢复后自动建好索引（**这是当前版本做不到的**）；
- 同一笔记连续保存 5 次 → `index_task` 里未完成任务数恒为 1；
- `POST /api/index/rebuild` 后全库 `chunk.embedding IS NOT NULL` 比例 100%（可用 `/api/debug/retrieval` 抽查）。

---

### D10 父块-子块 / 多粒度召回（**加分区，非必做**）

**目标**：在 ragent 里被验证为**真空地带**的能力（全仓 grep `parentChunk|childChunk|父子分块` 零命中），做成自己的增量。

**关键设计（只在 D3/D5 完成后有余力再做）**

1. 切片时产出两级：**子块**（~256 字，用于召回）+ **父块**（所属 `##` 小节，用于喂模型）；
2. `chunk` 表加 `parent_id`（自引用）；召回在子块粒度打分，命中后**用父块内容进 Prompt**；
3. 预期收益：E01/E11/E12 这类"答案散落在同小节多句"的样本 —— 子块把语义中心切得更纯，召回更准；父块保证喂给模型的上下文完整；
4. **风险**：父块变长 → Prompt token 上升 → 首字延迟上升。**必须同时报 TTFT 的 before/after**，否则这个改造的收益无法评估。

**验收**：`Recall@5` 提升幅度 + `TTFT P95` 变化，两者一起报。**如果 TTFT 恶化超过 30%，不加权保留。**

---

### D11 调试与可观测（最便宜的加分项）

**目标**：让检索链路**可见**。既方便自己排查失败样本，也是面试演示的抓手。

**关键设计**

1. **调试接口**：`GET /api/debug/retrieval?q=...&explain=true`
   返回：每个子问题 → 每个通道的原始命中（含分数与名次）→ RRF 分 → `gateScore`/`gateDecision` → 最终送入 Prompt 的片段（含重编号后的 `[n]`）。
   **这是把 D1/D2/D3/D4 一次性变成"看得见的东西"的最低成本方式。**
2. **SSE 增加 `meta` 事件**（**只增不改**，旧前端不受影响）：
   `{traceId, subQuestions, channels:{vector:n, keyword:n}, candidateCount, gateScore, gateDecision, timings:{rewriteMs,embedMs,retrieveMs,gateMs,llmFirstTokenMs}}`
3. **一次问答一条汇总日志**：`traceId + 各步耗时 + 通道归因 + 闸门判定 + 是否调用 LLM`。
   用途：评测之外的**线上归因**（"这条为什么答错"能在日志里一眼看出来）。
4. 不做：完整 OpenTelemetry / 链路存储表 / 前端 dashboard（**明确不做**，见 §11）。

**验收**
- 用 `/api/debug/retrieval` 能把 E01 的失败讲清楚（哪个通道没命中、闸门判了什么、最终送了什么）；
- `meta` 事件在浏览器 Network 面板可见，且旧前端（不监听 `meta`）**功能不受影响**。

---

## 4. 功能需求（FR）

| # | 需求 | 优先级 | 对应设计 |
|:--:|---|:--:|---|
| FR-1 | 支持关键词通道（bigram+tsvector）与向量通道并行召回，各自独立扩池 | P0 | D2 |
| FR-2 | 支持 RRF 融合与按通道加权、候选池截断 | P0 | D3 |
| FR-3 | 支持批级证据闸门；闸门不过则不进 LLM 并归因 `no_sources` | P0 | D4/D8 |
| FR-4 | 阈值/权重/分块参数全部可配置，并有合法性校验 | P0 | D4/D5 |
| FR-5 | 配置矛盾时启动失败并给出可读原因 | P0 | D4 |
| FR-6 | 支持改写 + 多问句拆分；LLM 不可用时降级为规则切分 | P0 | D6 |
| FR-7 | 子问题命中结果全局去重、重编号，且编号与 `sources` 下标严格一致 | P0 | D6 |
| FR-8 | 生成层评测：引用支撑率、拒答准确率、答案正确率 | P0 | D7 |
| FR-9 | 评测支持录制/评分分离，run 之间可比（含配置快照） | P0 | D7 |
| FR-10 | 分块策略可插拔（heading / fixed），支持参数化对比实验 | P1 | D5 |
| FR-11 | 索引异步化：任务持久化、重试、幂等、全量重建入口 | P1 | D9 |
| FR-12 | 检索调试接口 `/api/debug/retrieval` | P1 | D11 |
| FR-13 | SSE 新增 `meta` 事件（含耗时与归因） | P1 | D11 |
| FR-14 | 拒答文案单一事实来源，README/代码/评测三方一致 | P0 | D8 |
| FR-15 | 词表映射（同义词/别名归一），纯规则零 token | P2 | D6 |
| FR-16 | 父块-子块多粒度召回 | P2 | D10 |
| FR-17 | 评测报告输出 markdown diff 表 | P1 | D7 |
| FR-18 | 评估集扩到 ≥100 条（库内 ≥80 / 库外 ≥20） | P0 | §9 |

---

## 5. 非功能需求（NFR）

| # | 需求 | 目标值 | 现状 |
|:--:|---|---|---|
| NFR-1 | 检索耗时（含两通道 + 融合 + 闸门）P95 @1000 切片 | **< 500ms** | ❌ **从未实测**（PRD §0.4-4 未验收） |
| NFR-2 | 首字延迟 TTFT P50/P95 | 记录并**不劣化**（D6 引入改写后的硬指标） | 未度量 |
| NFR-3 | 闸门省下的 LLM 调用次数 | 库外样本 100% 不调用 LLM | 0% |
| NFR-4 | 幂等：重复保存/导入同一内容 | 不产生重复切片；`embedding_cache` 命中 | 部分（有哈希缓存） |
| NFR-5 | 可复现：clone 后两条命令跑通 | 不新增任何扩展/中间件/Key | ✅ 需保持 |
| NFR-6 | 可观测：一次问答一条汇总日志 | 含 traceId、各步耗时、通道归因、闸门判定 | ❌ |
| NFR-7 | 单机资源 | 不引入常驻新进程（除 PG 容器） | ✅ 需保持 |
| NFR-8 | 启动期配置校验 | 矛盾配置拒绝启动 | ❌ |

---

## 6. 数据模型变更（DDL）

沿用 `SchemaInitializer` 的既有兼容写法（`CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`）。

```sql
-- ① 关键词通道：应用层写入 bigram 切分后的 tsvector（不用 generated column，便于换分词器）
ALTER TABLE chunk ADD COLUMN IF NOT EXISTS tsv tsvector;
ALTER TABLE chunk ADD COLUMN IF NOT EXISTS token_count INT;
CREATE INDEX IF NOT EXISTS idx_chunk_tsv_gin ON chunk USING GIN (tsv);

-- ② 多粒度召回（D10，加分区）：子块 -> 父块
ALTER TABLE chunk ADD COLUMN IF NOT EXISTS parent_id BIGINT REFERENCES chunk(id) ON DELETE CASCADE;
ALTER TABLE chunk ADD COLUMN IF NOT EXISTS level VARCHAR(8) NOT NULL DEFAULT 'child';  -- parent | child
CREATE INDEX IF NOT EXISTS idx_chunk_parent_id ON chunk(parent_id);
-- 同笔记内 (level, seq) 唯一，保证重建时不产生重复
CREATE UNIQUE INDEX IF NOT EXISTS uq_chunk_note_level_seq ON chunk(note_id, level, seq);

-- ③ 异步索引任务（D9）：幂等靠部分唯一索引，不靠代码判断
CREATE TABLE IF NOT EXISTS index_task (
    id          BIGSERIAL PRIMARY KEY,
    note_id     BIGINT NOT NULL REFERENCES note(id) ON DELETE CASCADE,
    type        VARCHAR(16) NOT NULL DEFAULT 'upsert',   -- upsert | rebuild
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING|RUNNING|SUCCESS|FAILED
    attempts    INT NOT NULL DEFAULT 0,
    last_error  TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_index_task_active
    ON index_task(note_id) WHERE status IN ('PENDING','RUNNING');
CREATE INDEX IF NOT EXISTS idx_index_task_status ON index_task(status, created_at);

-- ④ 会话消息状态（D6 第 6 点：半截消息不占改写名额）
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'NORMAL';
-- 取值：NORMAL | INTERRUPTED | RATE_LIMITED

-- ⑤ 词表映射（FR-15）
CREATE TABLE IF NOT EXISTS query_term_mapping (
    id          BIGSERIAL PRIMARY KEY,
    source_term TEXT NOT NULL UNIQUE,
    target_term TEXT NOT NULL,
    priority    INT NOT NULL DEFAULT 0,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE
);

-- ⑥ 评测录制（D7：录制/评分分离）
CREATE TABLE IF NOT EXISTS eval_run (
    id         BIGSERIAL PRIMARY KEY,
    tag        TEXT NOT NULL,          -- baseline-0.30 / gate-0.60 / rrf-keyword ...
    config     JSONB NOT NULL,         -- 关键配置快照（阈值/权重/分块/模型），保证 run 之间可比
    metrics    JSONB,                  -- 评分阶段回填
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS eval_record (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT NOT NULL REFERENCES eval_run(id) ON DELETE CASCADE,
    query_id    TEXT NOT NULL,
    question    TEXT NOT NULL,
    sub_questions JSONB,
    retrieved   JSONB NOT NULL,        -- 各通道命中/分数/RRF/闸门判定/最终 sources
    answer      TEXT,
    done_reason TEXT,                  -- ok | no_sources
    timings     JSONB,
    judge       JSONB,                 -- 评分阶段回填，可复用
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_eval_record_run ON eval_record(run_id, query_id);
```

**迁移与兼容**
- 全部用 `IF NOT EXISTS`，老库直接启动即可升级；
- `tsv` 为 `NULL` 的历史切片**不参与关键词通道**（查询侧 `WHERE tsv IS NOT NULL`），
  首次启动后提示执行 `POST /api/index/rebuild` 回填 —— **不做隐式全量重建**（避免启动变慢且不可控）。

---

## 7. 接口变更

| 接口 | 变更 | 兼容性 |
|---|---|---|
| `POST /api/chat`（SSE） | **新增 `meta` 事件**（首个事件）；`done` 事件增加 `traceId`、`llmCalls` | ✅ 只增不改，旧前端忽略未知事件 |
| `GET /api/notes/{id}/chunks` | 返回增加 `tokenCount`、`level` | ✅ 只增 |
| `GET /api/debug/retrieval?q=&explain=` | **新增** | ✅ 新增 |
| `POST /api/index/rebuild` | **新增**（全量重建） | ✅ 新增 |
| `GET /api/index/tasks?status=` | **新增**（任务列表，含失败原因） | ✅ 新增 |
| `GET /api/eval/runs` / `POST /api/eval/run` | **新增**（可选：触发一次录制；也可只用测试跑） | ✅ 新增 |
| `GET /api/config` | 返回增加闸门/融合/分块的当前取值（便于前端与面试演示） | ✅ 只增 |

**SSE 事件协议（改造后）**
```
meta   → {traceId, subQuestions, channels, candidateCount, gateScore, gateDecision, timings}
sources→ [ {index, noteId, seq, noteTitle, similarity, channels} ]
delta  → {text}            (多次)
done   → {reason: "ok" | "no_sources", traceId, llmCalls}
error  → {message}
```

---

## 8. 配置项全量（改造后）

```yaml
rag:
  # —— 模型 ——
  api-base-url: ${RAG_API_BASE_URL:http://localhost:11434/v1}
  api-key: ${LLM_API_KEY:ollama}
  chat-model: ${RAG_CHAT_MODEL:llama3.1:8b}
  embedding-model: ${RAG_EMBEDDING_MODEL:bge-m3}
  embedding-dimension: 1024

  # —— 召回 ——
  top-k: 5                    # 最终送进 Prompt 的片段数
  min-similarity: 0.60        # 【变更】0.30 → 0.60，SQL 层单条过滤
  retrieval:
    candidate-limit: 20       # 【新增】每通道扩池上限，必须 > top-k
  channels:
    vector:  { enabled: true }
    keyword: { enabled: true, tokenizer: bigram, weight: 0.6 }

  # —— 融合 ——
  fusion:
    strategy: rrf             # 【新增】rrf | none
    rrf-k: 60
    channel-weights: { vector: 1.0, keyword: 0.6 }

  # —— 闸门 ——
  evidence:
    mode: batch               # 【新增】batch | none
    min-gate-score: 0.60      # 【新增】批级最高分下限，≤0 关闭
    on-missing-score: allow   # 【新增】无分可读时 fail-open + WARN

  # —— 分块 ——
  chunk:
    strategy: heading         # 【变更】heading | fixed（原为硬编码）
    max-chars: 1024           # 【变更】500 → 1024
    overlap-chars: 128        # 【变更】50 → 128（= max-chars/8）
    tolerance-factor: 3       # 【新增】允许超出 max-chars 的倍数，封顶 8192
    rows-per-chunk: 50

  # —— 改写 ——
  rewrite:
    enabled: true
    max-sub-questions: 3
    temperature: 0.1
    top-p: 0.3
    timeout-ms: 3000
  term-mapping:
    enabled: false            # P2

  # —— 索引 ——
  index:
    async-enabled: true
    max-retry: 3
    worker-threads: 1
    retry-backoff-ms: 2000

  # —— 评测（仅测试用） ——
  eval:
    dataset: /eval/eval_set_v2.jsonl
    judge-model: qwen3:14b
```

---

## 9. 评测体系设计

### 9.1 评估集规范

文件：`src/test/resources/eval/eval_set_v2.jsonl`，每行一条：

```json
{
  "query_id": "E01",
  "query": "Minor GC 和 Full GC 分别在什么情况下触发？",
  "expected_chunks": ["5:0"],
  "expected_nice": [],
  "requires_rag": true,
  "category": "jvm",
  "note": "笔记5《JVM GC 基础》> 分代模型"
}
```

| 字段 | 说明 |
|---|---|
| `query_id` | 库内 `E##`、库外 `N##` |
| `expected_chunks` | **必须**命中的切片，`noteId:seq`。用于 `Recall@5(must)` |
| `expected_nice` | 命中加分、不命中不扣分（同义/补充切片） |
| `requires_rag` | `false` = 库外问题，**期望被拒答** |
| `category` | 便于按类别看指标（jvm / 并发 / mysql / redis …） |

### 9.2 规模与配比

| 项 | 现状 | 目标 |
|---|---:|---:|
| 库内样本 | 16 | **≥80** |
| 库外样本 | **4** | **≥20** |
| 合计 | 20 | **≥100** |

**库外样本的设计原则（决定"拒答准确率"是否可信）**
> 必须是**主题邻近**的库外问题，不能是"一眼无关"的问题。
> 现有 N01–N04（Go goroutine / Python GIL / Docker 网络模式 / Java 泛型擦除）就是好的范例：都在"后端技术"这个大主题内，但库里的笔记确实没写。
> **为什么必须这样设计**：如果库外样本一眼无关（如"今天天气怎么样"），任何阈值都能拦住，指标会虚高 —— 那就测不出护栏的真实能力。

### 9.3 指标定义

| 指标 | 公式 | 依赖 |
|---|---|---|
| Hit@K | 期望切片出现在前 K 的样本占比 | 纯计算 |
| Recall@K(must) | `命中期望切片数 / 期望切片总数` 的均值 | 纯计算 |
| Recall@K(inclusive) | 同上，但分母含 `expected_nice` | 纯计算 |
| MRR | 首个命中期望切片名次倒数的均值 | 纯计算 |
| **误拒率** | 库内样本中"召回为空"的占比（**越低越好**） | 纯计算 |
| **过召回率** | 库外样本中"仍召回到内容"的占比（**越低越好**） | 纯计算 |
| **引用支撑率** | `(角标数 − 无法映射的角标数) / 角标数` | 纯计算（D7） |
| **拒答准确率** | 库外样本中 `done.reason == "no_sources"` 的占比 | 纯计算（D8） |
| **答案正确率** | judge 判 0/1/2 的均值 | LLM-judge |
| TTFT P50/P95 | 请求到首个 `delta` 的毫秒数 | 纯计算 |

### 9.4 流程

```
1) 冻结基线：跑一次全量 → eval_run.tag = 'baseline'（此时是 0.30 配置）
2) 每次改造后：跑录制 → 评分 → 生成 diff 报告（markdown）
3) 报告归档：docs/eval/baseline.md、gate-0.60.md、rrf-keyword.md ...
4) README 顶部只放**最新一次 vs 基线**的对比表
```

### 9.5 必须产出的对比（这也正是简历上的数字）

| run tag | 改了什么 | 预期写入简历的指标 |
|---|---|---|
| `baseline` | — | `Recall@5 81.3%`、过召回 `100%`、支撑率 `__%` |
| `gate-0.60` | D4 阈值定档 | 过召回 `100% → 0%`、误拒 `0%`、`Recall@5 → 78.1%` |
| `rrf-keyword` | D2 + D3 双通道 RRF | `Recall@5 78.1% → __%` |
| `rewrite-split` | D6 改写拆分 | E01 类样本命中率 `__% → __%` |
| `chunk-1024` | D5 分块预算 | `Recall@5 __ → __`、平均块长 `__` |
| `gen-eval` | D7 生成层 | 引用支撑率 `__%`、拒答准确率 `__%`、答案正确率 `__%` |

---

## 10. 排期与验收（对齐 9/23 – 10/7，15 天）

| 批次 | 日期 | 内容 | 产出（DoD） | 简历价值 |
|:--:|---|---|---|---|
| **B0** | 9/23 | 评估集扩到 ≥100（库内 80 / 库外 20）；冻结 `baseline` run | 基线 run + 报告；**F2 口径统一** | 所有数字的分母 |
| **B1** | 9/24 | D1 后置处理器链 + D4 闸门 + 阈值定档 0.60 | 过召回 `100%→0%`；配置矛盾启动失败 | ⭐ 主数字 1 |
| **B2** | 9/25–9/26 | D2 关键词通道 + D3 RRF 融合 | `Recall@5` 提升数字 + 通道归因日志 | ⭐ 主数字 2 |
| **B3** | 9/27 | D6 改写 + 多问句拆分（含规则兜底） | E01 类样本前后对比 | ⭐ 主数字 3 |
| **B4** | 9/28 | D7 生成层评测（三指标 + judge + 人工抽检） | 支撑率/拒答准确率/答案正确率 + 一个修复动作 | ⭐ 主数字 4 |
| **B5** | 9/29–9/30 | D5 分块预算化 + 策略 A/B；D11 调试接口 + `meta` | 切片策略对比表；`/api/debug/retrieval` 可演示 | 加分 |
| **B6** | 10/1–10/2 | D9 异步索引（任务表 + 重试 + 重建） | 断 embedding 服务仍可用+自愈 | 加分（取舍话术） |
| **B7** | 10/3 | D10 父块-子块（**可选**） | `Recall@5` 与 `TTFT` 成对报告 | 加分区 |
| **B8** | 10/4 | 仓库门面：README 评测表 + 架构图 + 复现命令；公开仓库 + 逻辑 commit 历史 | 可点开的链接 | 凭证 |
| **B9** | 10/5–10/7 | 简历定稿 + 追问彩排 + 冻结 | 5 个追问的防守答案 | — |

**必做 vs 加分**
- **必做（B0–B4 + B8）**：做完就够写简历。**不要为了做满而推迟投递。**
- 加分（B5–B7）。
- 决策门 **9/28**：B1–B4 的数字拿到了 → 继续 B5/B6；没拿到 → 砍到 B8，把时间给追问彩排与简历。

---

## 11. 明确不做（Out of Scope）

**产品功能类（对 Java 后端 / AI 岗面试零加分，且吃掉数周）**
登录鉴权 / 多用户与权限 · 标签 · 目录树 · 双向链接 · 知识图谱 · 网页剪藏 · 图片与扫描件 OCR · 版本历史 · 回收站 · 导出备份 · 推荐问题 · 消息反馈点赞点踩 · 前端 dashboard

**技术选型类（过度设计，一问就崩）**
Elasticsearch / OpenSearch · Redis（缓存/分布式锁）· Kafka / RocketMQ · Milvus 或其他独立向量库 · 微服务拆分 · 分布式锁/分布式限流 · 多租户与配额 · LightRAG 图谱检索 · 意图树与多知识库路由 · 可配置编排引擎（链式节点 + 环检测）· 完整 OpenTelemetry 链路存储

> **统一理由**：这些都在解决「多租户 + 集群 + 多数据源」的问题。本项目是**单用户、单机、单库**。
> 面试官只要问一句"你这个规模为什么需要分布式锁 / 为什么不用本地锁"，答不出来就是负分。
> **把"不做"写成取舍，比把"做了"堆上去更值钱。**

---

## 12. 风险与降级分支

| # | 风险 | 触发信号 | 降级动作 |
|:--:|---|---|---|
| R1 | 库外样本凑不够 20 条 | 9/23 结束时 < 15 条 | 过召回率数字改为"4 条小样本实测"并在简历注明样本量；不写百分比 |
| R2 | 中文关键词通道效果不及预期 | `rrf-keyword` 的 `Recall@5` 相对基线**无提升** | 保留通道但把 `channel-weights.keyword` 降到 0.3；主数字仍用"闸门 + 改写"，**如实报告"关键词通道无收益"**（这本身也是一个可信结论） |
| R3 | LLM-judge 不稳定 / 与人工分歧大 | 人工抽检一致率 < 70% | 答案正确率降级为"人工抽检 20 条的通过率"，不用 judge 的均值 |
| R4 | 改写引入延迟，TTFT 明显恶化 | TTFT P95 恶化 > 50% | 改写与 Embedding 并行 → 仍超 → 改为"仅当问句含并列连词/多问号时才改写"（规则预判） |
| R5 | 时间不够 | 9/28 决策门未达标 | 保 B0–B4 的数字真实性，砍 B5–B7；**绝不编数字** |
| R6 | 环境问题（Ollama 未启动） | 评测走 `Assumptions.abort` 跳过 | 保留现有"环境不可用即跳过"的机制（**不把环境问题误报成质量下降**），但报告里标注 `SKIPPED` 而非 `PASS` |

---

## 13. 附：面试必答问题与答案落点（本设计的副产品）

| 追问 | 答案落在哪 |
|---|---|
| 为什么纯向量不够？为什么要关键词通道？ | D2（术语精确匹配 vs 语义）、§9.5 `rrf-keyword` 的 before/after |
| 两路分数怎么合？为什么用 RRF 不用加权求和？ | D3（量纲不可比；RRF 只看名次；多路命中自然加分） |
| 库里没有答案时怎么保证不编造？ | D8（**闸门在检索侧决定是否调 LLM，不依赖模型自觉**）+ 拒答准确率数字 |
| 阈值怎么定的？ | D4（**扫描出来的**：信号下限 0.608 vs 噪声上限 0.578；0.60 的取舍是 3.2pp recall 换 100% 过召回） |
| 为什么这么切片？定长不行吗？ | D5（预算而非硬上限、等比重叠是回退找句末的距离、1024 的理由）+ §9.5 `chunk-1024` 对比表 |
| 中文为什么不用 ES？ | D2（bigram+tsvector 的取舍表；索引体积换零中间件） |
| Embedding 服务挂了怎么办？ | D9（任务表 + 重试 + 幂等；**为什么不引 MQ**） |
| 你怎么证明改完变好了？ | §9 录制/评分分离 + diff 报告 + 基线 run |
