# 电兵助手 · 高校校园智能助手

电兵助手计划建设为基于 RAG 与 MCP 的高校校园智能助手。当前版本首先实现校园规则知识库：导入学校官方规则和培养方案，经过人工核对后进行检索问答，并展示文件、条款或分块及官方链接。当前是**本地演示版**，默认只监听 `127.0.0.1`。

面试演示路径、指标证据和下一阶段验收条件见 [秋招项目展示与后续验收](doc/秋招项目展示与后续验收.md)。

## 当前项目状态

当前已完成的是校园规则 RAG 基础框架，主要支持：

- PDF、Word、Markdown 等规则文档导入；
- 文档原文保存、元数据维护和有效期管理；
- 按规则条款、章节和培养方案专业分块；
- PostgreSQL + pgvector 向量检索；
- 中文关键词检索与向量检索融合；
- 查询改写、证据闸门和无依据拒答；
- LLM 流式问答；
- 答案引用卡片和原文分块跳转；
- 会话保存、检索调试和评测数据记录；
- 针对培养方案的适用年份、身份、校区和历史版本隔离。

课程、课表、成绩、考试和选课建议已提供**虚构学生 Mock 演示**及只读 MCP Tool，访问 [/campus.html](http://localhost:8080/campus.html) 可查看同一份数据。首页聊天框还可直接提问“我的成绩是多少？”“我的课表周二有什么课？”“我的考试安排是什么？”“我还差多少学分？”“我推荐选什么课？”，这些明确的个人数据问题由 `CampusQuestionRouter` 走领域服务确定性回答，不调用 LLM。学生登录和真实教务系统接入尚未完成，详见[后续计划](#后续计划与未完成事项)。

问“我的成绩是否满足软件工程学硕毕业学分要求？”时，统一入口会分开呈现**虚构学业数据**和**检索到的官方规则依据**。规则说明可带引用，但系统不会用 Mock 数据判断真实毕业资格；规则检索没有证据时直接说明缺失。模型提示词只接收规则问题和检索片段，不接收演示学生的成绩明细或相关历史消息。

## 当前框架结构

```text
电兵助手
├── src/main/java/com/pkb
│   ├── config/       配置、数据库表结构和运行参数
│   ├── note/         规则文档 CRUD、元数据和原始文件
│   ├── ingest/       PDF/DOCX/Markdown 导入与正文清洗
│   ├── chunk/        规则、标题和定长分块策略
│   ├── index/        异步索引任务和索引重建
│   ├── search/       关键词/向量检索、融合、去重和证据闸门
│   ├── rewrite/      查询改写、术语映射和子问题拆分
│   ├── llm/          对话模型、Embedding 模型和向量编解码
│   ├── rag/          RAG 编排、流式问答、引用校验和调试接口
│   ├── campus/       校园领域服务与可替换数据提供者（当前为 Mock）
│   ├── mcp/          官方 Java SDK 的只读 Streamable HTTP 工具
│   └── conversation/ 会话和聊天消息持久化
├── src/main/resources
│   ├── static/       Vue 单页前端
│   ├── application.yml
│   └── application-deepseek.yml
├── src/test
│   ├── java/         单元测试、检索评测和生成评测
│   └── resources/   规则语料及评测集
├── doc/              官方 PDF、语料清单和评测报告
├── docker-compose.yml PostgreSQL + pgvector
└── PRD.md           产品需求和后续演进路线
```

## 当前请求处理流程

```text
用户问题
  ↓
ChatController
  ↓
RagService
  ├─ 明确的个人数据问题 → CampusQuestionRouter → CampusService → Mock 回答（零 LLM）
  ├─ 个人数据 + 学校规则问题 → Mock 数据摘要 + RAG 规则证据（分区展示，不作资格判定）
  └─ 校园规则问题
     ├─ 查询改写与子问题拆分
     ├─ Embedding
     ├─ 关键词检索 + 向量检索
     ├─ RRF 融合、去重和证据闸门
     ├─ 规则范围校验
     ├─ Prompt 组装
     └─ LLM 流式回答 → 引用校验 + SSE 返回
```

规则文档的处理流程为：

```text
上传/创建规则
  ↓
Tika 或纯文本解析
  ↓
正文清洗和规则分块
  ↓
Embedding + 内容哈希缓存
  ↓
PostgreSQL / pgvector / tsvector
  ↓
异步索引任务
```

## 技术栈

| 层次 | 当前技术 |
|---|---|
| 后端 | Java 17、Spring Boot 3.5、Spring Web、Spring JDBC |
| 数据库 | PostgreSQL、pgvector、PostgreSQL 全文检索 |
| 文档解析 | Apache Tika、Markdown/纯文本解析 |
| 模型 | OpenAI 兼容接口；默认可接 Ollama，也支持云端模型 |
| 检索 | 向量检索、中文双字切分关键词检索、RRF 融合、证据闸门 |
| 前端 | Spring Boot 静态托管、Vue 3 CDN、marked.js |
| 测试 | JUnit、真实 PDF 语料评测、检索和生成评测 |
| 部署 | 本地 Spring Boot + Docker Compose PostgreSQL |

## 后续计划与未完成事项

### 校园业务能力

- [x] Mock 课程目录结构化和课程查询；
- [x] Mock 课表数据模型与课表查询；
- [x] Mock 学生成绩数据模型与成绩查询；
- [x] 同一会话中的 Mock 个人问答历史不送入公开校规 RAG 的查询改写器或生成 Prompt；
- [x] Mock 考试安排查询；[ ] 真实考试安排接入；
- [x] 基于虚构整校周课表的空闲教室查询（按学期、星期、节次区间、教学楼和容量过滤）；[ ] 真实教室实时占用与预约状态接入；
- [ ] 培养方案课程表结构化；
- [x] Mock 培养要求的确定性学分缺口计算；[ ] 从真实培养方案结构化并人工校对后计算；
- [x] 基于 Mock 先修条件、开课学期、已排课、虚构培养要求、剩余名额和节次冲突的可解释课程候选；[ ] 真实规则引用与真实选课容量/冲突校验。

### MCP 与系统集成

- [x] 增加本机只读 MCP Server；
- [x] 暴露 `get_my_grades`、`get_my_schedule`、`get_my_exams`、`search_courses`、`get_course_detail`、`calculate_my_credit_gap`、`recommend_courses`、`find_free_classrooms`；
- [x] 增加统一的 `campus` 领域服务层，MCP Tool 不操作 SQL；
- [x] 先支持 Mock 数据；[ ] 成绩单/课表文件导入与真实教务系统适配；
- [ ] 后续评估拆分独立的 `academic-mcp-server` 服务。

### 安全与可用性

- [ ] 学生登录和统一身份认证；
- [ ] 基于当前登录身份的 `get_my_*` 数据访问；
- [ ] 工具级授权、跨学生访问隔离和查询审计；
- [ ] 成绩等敏感字段脱敏；
- [ ] 管理员规则审核和发布流程；
- [ ] 备份、恢复和真实部署配置；
- [ ] 移除或替换前端 CDN 依赖，完善生产环境资源管理。

### 推荐实施顺序

```text
课程查询
  → 课表查询
  → 成绩查询
  → 学分缺口分析
  → 考试安排
  → 课程推荐
  → RAG + MCP 组合问答
  → 统一身份认证和真实教务系统接入
```

在接入真实学生数据前，先使用 Mock 数据或成绩单/课程表文件验证工具协议、领域逻辑和交互闭环。

## 校园服务 Mock 与 MCP 演示

启动应用后打开 <http://localhost:8080/campus.html>。演示数据在 [mock-campus.json](src/main/resources/campus/mock-campus.json)，固定为虚构学生 `DEMO-001`：2025 年秋季已修课程，2026 年秋季在修课程与未来考试，时间线一致。另有独立的虚构整校教室占用表，不能用个人课表推断教室是否空闲。预览接口为 `GET /api/campus/demo`。八个 MCP Tool 通过官方 Java SDK 的 Streamable HTTP `/mcp` 暴露，和预览页共用 `CampusService`。`get_my_*` 无 `studentId` 参数；额外参数会被拒绝。当前仅绑定 `127.0.0.1`，尚未做真实身份认证，因此不得用于真实成绩和考试安排。

可用标准 MCP 客户端连接 `http://127.0.0.1:8080/mcp`。也可运行无数据库、无 Ollama 的协议集成测试，覆盖初始化、工具发现、模拟成绩查询、学分计算与非法参数：

```powershell
.\mvnw.cmd '-Dtest=CampusServiceTest,CampusMcpHttpTest,RetrievalAggregationTest' test
```

查询样例：`get_my_grades` 传 `{ "semester": "2025-秋" }`；`get_my_schedule` 传 `{ "semester": "2026-秋", "weekday": 2 }`；`get_my_exams` 传 `{ "semester": "2026-秋" }`；`search_courses` 传 `{ "query": "机器" }`；`get_course_detail` 传 `{ "courseCode": "CS502" }`；`calculate_my_credit_gap` 传 `{}`；`recommend_courses` 传 `{ "semester": "2026-秋" }`；`find_free_classrooms` 传 `{ "semester": "2026-秋", "weekday": 2, "startSection": 3, "endSection": 4 }`。工具结果包含 `dataSource: fictional-mock`，不代表真实教务记录或毕业资格。空教室只按虚构周课表推算整段节次是否无占用，不代表实时状态；Mock 学分缺口只计入已通过课程；推荐会检查虚构先修课、剩余名额和课表节次，返回候选与未推荐原因，但尚未验证真实开课和官方培养要求。

## 快速启动

### 只演示 Mock 校园服务（无需 Docker/Ollama）

```powershell
.\mvnw.cmd '-Dspring-boot.run.main-class=com.pkb.demo.CampusDemoApplication' spring-boot:run
```

打开 <http://127.0.0.1:8080/campus.html>；`/api/campus/demo` 与 `/mcp` 同时可用。此入口只加载虚构校园服务，不提供 RAG 对话或规则管理接口，便于无数据库的秋招现场演示。

### 完整 RAG + 校园助手

需要 JDK 17+、Docker 和 Ollama。先准备中文对话模型与向量模型：

```powershell
ollama pull qwen2.5:7b
ollama pull bge-m3
docker compose up -d
.\mvnw.cmd spring-boot:run
```

打开 <http://localhost:8080>，点击“加载示例”。系统会读取 [语料清单](doc/corpus-manifest.json)，核验两份 PDF 的 SHA-256 并导入数据库。2026 年全日制学术学位硕士培养方案被标为适用于 2026 级的有效文档；旧版研究生学位授予细则仅作历史存档。索引在后台创建，可在 `GET /api/index/tasks` 查看进度。第一次导入较长，索引完成前不会参与问答。

2026 年培养方案的[学校官方附件](https://gr.uestc.edu.cn/attached/papers/204/202608/2026%E5%B9%B4%E5%85%A8%E6%97%A5%E5%88%B6%E5%AD%A6%E6%9C%AF%E5%AD%A6%E4%BD%8D%E7%A1%95%E5%A3%AB%E7%A0%94%E7%A9%B6%E7%94%9F%E5%9F%B9%E5%85%BB%E6%96%B9%E6%A1%88_261716157506.pdf)来自[研究生院培养方案页面](https://gr.uestc.edu.cn/tongzhi/119/7725)。学位授予细则已有后续修订，旧 PDF 不应作为当前依据。

## 如何维护规则

页面可新建或导入 PDF、Word、Markdown 等文档。导入时保存原始文件，页面支持下载回看。每份新文档默认是草稿；填写发布部门、学校官网 HTTPS 地址、适用身份与学年或生效日期后，才可设为“有效”。有效规则经过索引并在有效期内，才进入检索。修改正文会先暂停该文档的检索资格，待新索引成功后恢复。历史文档可标记为“已过期”或“已废止”。当前版本是公开规则库，适用身份、校区和学年用于回答时说明范围，不作为登录权限过滤；统一身份认证和按规则库授权列入后续 TODO。

启用规则的官网域名由 `RAG_OFFICIAL_DOMAIN` 控制，默认 `uestc.edu.cn`。云模型可通过 `RAG_API_BASE_URL`、`LLM_API_KEY`、`RAG_CHAT_MODEL`、`RAG_EMBEDDING_MODEL` 配置。切换向量模型后需清理旧向量缓存并通过 `POST /api/index/rebuild` 重建索引。

页面的规则编辑接口仍沿用 `/api/notes` 路径，以便兼容已有数据；`PUT /api/notes/{id}/metadata` 可单独维护元数据。`GET /api/notes/{id}/original` 下载导入时的原始文件。

## 评测

真实 PDF 驱动的首版评测集是 [campus_eval_v1.jsonl](src/test/resources/eval/campus_eval_v1.jsonl)：10 个应答问题、3 个无依据应拒答问题和 2 个适用范围近邻问题，覆盖学制、学分、补修、必修环节、年份、身份和历史版本。必要证据按“原文片段 + 学科上下文”解析；多事实问题分别标注每一项必要证据，重建索引后无须手改分块 ID。适用范围近邻允许召回其他年份或身份的资料供澄清，但回答不得冒称其适用。

扩展验收集是 [campus_eval_v2.jsonl](src/test/resources/eval/campus_eval_v2.jsonl)：40 条样本，包含 30 条应答、5 条严格拒答和 5 条适用范围提醒，覆盖计算机、软件工程、数学、新闻传播、航空宇航、生物医学工程、论文要求和实践学分。每条应答题的必要事实都绑定真实 PDF 片段，并由 `CampusEvalCorpusTest` 校验。

v2 的首轮结果见 [扩展集评测记录](doc/杏规-扩展集评测记录-2026-09-22.md)；当前 Hit@5 为 93.3%，并暴露了软件工程复合学分和生物医学工程选课条件两个召回缺口。

```powershell
.\mvnw.cmd '-Dtest=CampusEvalCorpusTest,RuleChunkStrategyTest,RuleScopeTest,TextCleanerRuleTest' test
.\mvnw.cmd '-Dtest=ChunkQualityEvalTest' test
.\mvnw.cmd '-Dtest=RetrievalEvalTest' test
.\mvnw.cmd '-Dtest=GenerationEvalTest' '-Deval.tag=campus-baseline' test
# 使用扩展集时覆盖数据集参数
.\mvnw.cmd '-Dtest=RetrievalEvalTest' '-Deval.dataset=/eval/campus_eval_v2.jsonl' test
.\mvnw.cmd '-Dtest=GenerationEvalTest' '-Deval.dataset=/eval/campus_eval_v2.jsonl' '-Deval.tag=campus-v2' '-Deval.judge=false' test
```

检索和生成评测需要 PostgreSQL、已完成的有效规则索引，以及 Ollama。缺少当前有效的金标准证据时，测试会跳过并说明原因，不能把跳过当作通过。生成评测输出到 `target/eval-reports/`。现有 120 条 Java 技术问答在 `eval_set_v2.jsonl` 中保留为历史数据，**不用于证明杏规质量**。

本地 `mvn test` 在 PostgreSQL 未启动时会提前跳过 5 个依赖数据库的 SQL/评测测试；其余单元、语料与 Mock MCP HTTP 测试仍执行。GitHub Actions 分别运行基础测试和带 pgvector 服务的 SQL Repository 测试；数据库作业要求真实连接，连接失败不会以跳过掩盖。完整 RAG 检索与生成指标仍须启动 PostgreSQL、导入有效语料和模型服务后按上面的命令单独运行，不应把 CI 绿色误读为在线评测通过。

课程表 PDF 中，表格末行可能与后面的选课说明粘连。规则切块现在会在这类边界拆出带专业标题的短说明块；真实 PDF 离线测试覆盖 C128 依据句。修改切块规则后需重建索引，再运行在线评测确认召回变化。

现有自动指标包括 Hit@K、Recall@K、MRR、误拒/过召回、来源日期与适用范围匹配、角标可映射率、TTFT，以及独立模型 Judge 的答案评分。角标可映射率只能说明引用编号存在，不能说明引用真正支撑结论；对事实完整性、引用支撑和规则冲突还需人工复核。详见 [使用与评测说明](doc/杏规-使用与评测.md)。

本次真实 PDF 的运行结果、失败样本和速度记录见 [2026-09-22 评测报告](doc/杏规-评测报告-2026-09-22.md)。

DeepSeek profile 的实测结果见 [DeepSeek 评测报告](doc/杏规-DeepSeek评测报告-2026-09-22.md)。

若需对比更快的云端对话模型，可仅切换对话接口，保留本地 Ollama 的 `bge-m3` 向量服务与现有索引。例如在 PowerShell 中配置 DeepSeek V4 Flash（需自行提供 API Key）：

```powershell
$env:RAG_CHAT_API_BASE_URL = 'https://api.deepseek.com'
$env:RAG_CHAT_API_KEY = '<你的 DeepSeek API Key>'
$env:RAG_CHAT_MODEL = 'deepseek-v4-flash'
```

项目也提供了独立配置文件，可直接使用 profile 启动：

```powershell
$env:RAG_CHAT_API_KEY = '<你的 DeepSeek API Key>'
& .\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=deepseek'
```

`application-deepseek.yml` 只覆盖对话接口地址、密钥和模型；`RAG_API_BASE_URL` 与 `LLM_API_KEY` 仍控制本地向量接口，默认无需修改。也可以用 `RAG_CHAT_API_BASE_URL` 覆盖 DeepSeek 地址，用 `RAG_CHAT_MODEL` 改为账号实际可用的模型名。随后重启服务；评测时使用新的 `eval.tag`，并核对 TTFT、必要事实、引用和拒答表现。没有配置云端 Key 时无法实测云端延迟。

## 当前边界

- 语料仅覆盖已导入且启用的规则。目前自带的有效语料只针对 2026 级全日制学术学位硕士研究生培养方案。宿舍、奖助、请假、本科生等主题需要继续导入官方文件。
- PDF 文本可以提取，但复杂课程表可能在纯文本中丢失列关系；表格类答案应点击官方原文核对。
- 当前没有管理员登录与审核流程，默认仅允许本机访问。面向学生开放前需完成权限、发布审核和定期核验。
- 旧版“知源”说明保存在 [历史 README](doc/知源-旧版README.md)，原设计稿保留在仓库中。

## 后续演进计划：校园智能助手

当前项目先以“杏规 · 校园规则查看助手”为核心，验证校园规则的导入、检索、问答和引用溯源。后续计划将产品演进为“基于 RAG 与 MCP 的高校校园智能助手”，规则问答只是其中一个能力。

目标能力包括：

- 规则知识层：培养方案、考试规定、奖助政策、宿舍制度等官方资料的 RAG 检索与引用；
- 校园服务层：通过 MCP 封装成绩、课表、课程、考试安排、空闲教室等查询能力；
- 学业分析层：结合学生已修课程与培养方案，计算学分缺口并推荐课程；
- 统一交互层：根据问题自动选择 RAG、MCP 工具或多个能力组合完成回答。

推荐的职责边界是：RAG 负责理解规则并提供原文依据，MCP Tool 负责访问结构化校园数据，学分计算和课程推荐由后端领域服务确定性执行，LLM 负责解释结果而不是自行计算。

计划按以下顺序推进：

1. 课程查询；
2. 课表查询；
3. 成绩查询；
4. 学分缺口分析；
5. 考试安排查询；
6. 课程推荐与规则问答组合；
7. 统一身份认证、权限控制和真实教务系统适配。

在接入真实学生数据前，先使用 Mock 数据或成绩单/课程表导入文件验证 MCP 工具协议、业务计算和交互流程。面向真实学生开放前必须补充身份认证、工具级授权、查询审计、数据脱敏和跨学生访问隔离。

相关产品路线、数据模型和模块拆分记录在 [PRD 第 10 节](PRD.md)。
