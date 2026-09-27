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

课程、课表、成绩、考试和选课建议使用虚构学生 Mock 数据。问答会把校园数据问题交给 Agent；Agent 根据 MCP Server 发布的工具定义选择只读工具，再由电兵助手中的 MCP Client 执行调用。运行完整 RAG 对话并查询校园数据时，需要同时启动 MCP Server。访问 [/campus.html](http://localhost:8080/campus.html) 可查看 Mock 演示数据。学生登录和真实教务系统接入尚未完成，详见[后续计划](#后续计划与未完成事项)。

问“我的成绩是否满足软件工程学硕毕业学分要求？”时，统一入口会分开呈现**虚构学业数据**和**检索到的官方规则依据**。规则说明可带引用，但系统不会用 Mock 数据判断真实毕业资格；规则检索没有证据时直接说明缺失。模型提示词只接收规则问题和检索片段，不接收演示学生的成绩明细或相关历史消息。

## 当前框架结构

```text
电兵助手（Spring Boot 单体）
├── knowledge/       规则文档、解析切片、异步索引
├── qa/              问答编排、检索融合、改写、引用与会话
├── campus/          校园查询路由与 MCP Client
├── infrastructure/  数据库配置、LLM/Embedding 外部接口适配
├── demo/            独立校园 Mock 演示入口
└── resources/       配置、前端静态资源和 Mock 数据
```

电兵助手保持单体部署；MCP Server 位于同级目录的 `../campus-mcp-server/` Maven 项目。电兵助手作为 MCP Client 连接该服务，校园 Mock 工具的数据和实现由服务端项目提供。

## 当前请求处理流程

```text
用户问题
  ↓
ChatController
  ↓
RagService
  ├─ 明确的校园数据问题 → CampusQuestionRouter → MCP Client → 独立 MCP Server → Mock 回答（零 LLM）
  ├─ 个人数据 + 学校规则问题 → MCP Client 查询 Mock 数据 + RAG 规则证据（分区展示，不作资格判定）
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

- [x] 独立 `../campus-mcp-server/` 项目通过 Streamable HTTP 暴露只读 MCP Server；
- [x] 电兵助手使用官方 Java MCP SDK Client 连接服务端；
- [x] 问答路由通过 MCP 工具查询成绩、课表、考试、课程、学分缺口、推荐和空教室；
- [x] Server 使用独立项目内的 Mock 数据，不依赖电兵助手的进程内校园服务；
- [x] 校园查询 Agent 通过 OpenAI-compatible tool calling 自动选择 MCP 工具，并执行有轮数和工具数上限的调用循环；
- [ ] Agent 跨轮记忆、人工确认流和正式的任务完成率/工具选择质量评测；
- [ ] 成绩单/课表文件导入与真实教务系统适配。

### 安全与可用性

- [ ] 学生登录和统一身份认证；
- [ ] 基于当前登录身份的 `get_my_*` 数据访问；
- [ ] 工具级授权、跨学生访问隔离和查询审计；
- [ ] 成绩等敏感字段脱敏；
- [ ] 管理员规则审核和发布流程；
- [ ] 备份、恢复和真实部署配置；
- [x] 首页 Vue/Marked 资源本地化，离线演示不依赖公共 CDN；[ ] 生产环境资源版本管理与缓存策略。

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

Mock 校园数据和 8 个只读工具位于独立的 `../campus-mcp-server/` Maven 项目。先在一个终端启动 Server：`.\mvnw.cmd -f ..\campus-mcp-server\pom.xml spring-boot:run`。Server 默认监听 `127.0.0.1:8090/mcp`。标准 MCP 客户端也可直接连接该地址。电兵助手 MCP Client 默认连接此地址（可通过 `CAMPUS_MCP_SERVER_URL` 修改）；工具调用发生在电兵问答路由中。Server 使用虚构学生 `DEMO-001` 数据，工具结果不是真实教务记录或毕业资格结论。

工具包括 `get_my_grades`、`get_my_schedule`、`get_my_exams`、`search_courses`、`get_course_detail`、`calculate_my_credit_gap`、`recommend_courses`、`find_free_classrooms`。MCP Client 会在首次工具调用时连接服务端。校园查询 Agent 先从服务端发现工具和 JSON Schema，再由模型选择工具、生成参数；电兵校验工具白名单与必填参数，执行后将结果交回模型组织答案。循环最多 4 次工具调用、5 轮模型请求，单次 MCP 调用超时后最多重试一次；只允许只读校园工具。关闭自动工具选择可设置 `CAMPUS_AGENT_ENABLED=false`，已知问法会退回确定性路由。当前 Agent 只在单轮对话内保留工具调用上下文，不具备跨轮记忆，也不是通用 Agent。

## 快速启动

### 只演示 Mock 校园服务（无需 Docker/Ollama）

```powershell
.\mvnw.cmd '-Dspring-boot.run.main-class=com.dianbing.demo.CampusDemoApplication' spring-boot:run
```

打开 <http://127.0.0.1:8080/campus.html>；页面可输入星期和节次交互查询 Mock 空教室，`/api/campus/demo` 与 `/api/campus/free-classrooms` 可用。此入口只加载虚构校园服务，不提供 RAG 对话或 MCP Client，不再承载 MCP Server。

### 完整 RAG + 校园助手

需要 JDK 17+、Docker、Ollama 和 DeepSeek API Key。对话、校园 Agent 与查询改写默认使用 DeepSeek；向量仍由本地 Ollama 的 `bge-m3` 生成：

```powershell
ollama pull bge-m3
docker compose up -d
$env:RAG_CHAT_API_KEY = '<你的 DeepSeek API Key>'
.\mvnw.cmd spring-boot:run
```

打开 <http://localhost:8080>，点击“加载示例”。系统会读取 [语料清单](doc/corpus-manifest.json)，核验两份 PDF 的 SHA-256 并导入数据库。2026 年全日制学术学位硕士培养方案被标为适用于 2026 级的有效文档；旧版研究生学位授予细则仅作历史存档。索引在后台创建，可在 `GET /api/index/tasks` 查看进度。第一次导入较长，索引完成前不会参与问答。

2026 年培养方案的[学校官方附件](https://gr.uestc.edu.cn/attached/papers/204/202608/2026%E5%B9%B4%E5%85%A8%E6%97%A5%E5%88%B6%E5%AD%A6%E6%9C%AF%E5%AD%A6%E4%BD%8D%E7%A1%95%E5%A3%AB%E7%A0%94%E7%A9%B6%E7%94%9F%E5%9F%B9%E5%85%BB%E6%96%B9%E6%A1%88_261716157506.pdf)来自[研究生院培养方案页面](https://gr.uestc.edu.cn/tongzhi/119/7725)。学位授予细则已有后续修订，旧 PDF 不应作为当前依据。

## 如何维护规则

页面可新建或导入 PDF、Word、Markdown 等文档。导入时保存原始文件，页面支持下载回看。每份新文档默认是草稿；填写发布部门、学校官网 HTTPS 地址、适用身份与学年或生效日期后，才可设为“有效”。有效规则经过索引并在有效期内，才进入检索。修改正文会先暂停该文档的检索资格，待新索引成功后恢复。历史文档可标记为“已过期”或“已废止”。当前版本是公开规则库，适用身份、校区和学年用于回答时说明范围，不作为登录权限过滤；统一身份认证和按规则库授权列入后续 TODO。

启用规则的官网域名由 `RAG_OFFICIAL_DOMAIN` 控制，默认 `uestc.edu.cn`。对话模型可通过 `RAG_CHAT_API_BASE_URL`、`RAG_CHAT_API_KEY`、`RAG_CHAT_MODEL` 配置；向量接口和模型可通过 `RAG_API_BASE_URL`、`LLM_API_KEY`、`RAG_EMBEDDING_MODEL` 配置。切换向量模型后需清理旧向量缓存并通过 `POST /api/index/rebuild` 重建索引。

页面的规则编辑接口仍沿用 `/api/notes` 路径，以便兼容已有数据；`PUT /api/notes/{id}/metadata` 可单独维护元数据。`GET /api/notes/{id}/original` 下载导入时的原始文件。

## 评测

真实 PDF 驱动的首版评测集是 [campus_eval_v1.jsonl](src/test/resources/eval/campus_eval_v1.jsonl)：10 个应答问题、3 个无依据应拒答问题和 2 个适用范围近邻问题，覆盖学制、学分、补修、必修环节、年份、身份和历史版本。必要证据按“原文片段 + 学科上下文”解析；多事实问题分别标注每一项必要证据，重建索引后无须手改分块 ID。适用范围近邻允许召回其他年份或身份的资料供澄清，但回答不得冒称其适用。

扩展验收集是 [campus_eval_v2.jsonl](src/test/resources/eval/campus_eval_v2.jsonl)：40 条样本，包含 30 条应答、5 条严格拒答和 5 条适用范围提醒，覆盖计算机、软件工程、数学、新闻传播、航空宇航、生物医学工程、论文要求和实践学分。每条应答题的必要事实都绑定真实 PDF 片段，并由 `CampusEvalCorpusTest` 校验。

v2 的首轮结果见 [扩展集评测记录](doc/电兵助手-扩展集评测记录-2026-09-22.md)；重新建索引后的 [在线回归记录](doc/电兵助手-在线回归记录-2026-09-23.md) 测得 Hit@5 为 96.7%、必要证据 Recall@5 为 95.0%。C128 已命中；C119 原句单查询仍未命中，但完整问答的确定性拆分召回了两条证据。0.60 主闸门、0.55 主题检查和 0.55 具体追问检查组合后，现有 40 道库外题全部拒答，原 30 道应答题零误拒。具体追问规则使用了首批 8 道近邻题校准；随后新增的 8 道未参与调参题首次有效在线运行 8/8 拒答，其中一题由具体追问检查拦下。样本量仍小。

```powershell
.\mvnw.cmd '-Dtest=CampusEvalCorpusTest,RuleChunkStrategyTest,RuleScopeTest,TextCleanerRuleTest' test
.\mvnw.cmd '-Dtest=ChunkQualityEvalTest' test
.\mvnw.cmd '-Dtest=RetrievalEvalTest' test
.\mvnw.cmd '-Dtest=GenerationEvalTest' '-Deval.tag=campus-baseline' test
# 使用扩展集时覆盖数据集参数
.\mvnw.cmd '-Dtest=RetrievalEvalTest' '-Deval.dataset=/eval/campus_eval_v2.jsonl' test
.\mvnw.cmd '-Dtest=GenerationEvalTest' '-Deval.dataset=/eval/campus_eval_v2.jsonl' '-Deval.tag=campus-v2' '-Deval.judge=false' test
# 对复杂题只跑指定题号
.\mvnw.cmd '-Dtest=GenerationEvalTest' '-Deval.dataset=/eval/campus_eval_v2.jsonl' '-Deval.queryIds=C119,C128' '-Deval.judge=false' test
# 拒答回归：40 道题当前全拒；新一批 8 道题未参与规则校准
.\mvnw.cmd '-Dtest=RefusalHoldoutTest' '-Deval.online=true' test
```

检索和生成评测需要 PostgreSQL、已完成的有效规则索引，以及 Ollama。数据库或 Embedding 服务不可用时测试会跳过；数据库已连通但金标准证据缺失或失效时测试会失败，并提示重新导入或标注，不能把跳过当作通过。生成评测输出到 `target/eval-reports/`。评测只覆盖当前电兵助手的规则语料与问答样本；各指标的样本范围和限制见对应报告。

本地 `mvn test` 在 PostgreSQL 未启动时会提前跳过 5 个依赖数据库的 SQL/评测测试；其余单元与语料测试仍执行。GitHub Actions 分别运行基础测试和带 pgvector 服务的 SQL Repository 测试；数据库作业要求真实连接，连接失败不会以跳过掩盖。完整 RAG 检索与生成指标仍须启动 PostgreSQL、导入有效语料和模型服务后按上面的命令单独运行，不应把 CI 绿色误读为在线评测通过。

课程表 PDF 中，表格末行可能与后面的选课说明粘连。规则切块现在会在这类边界拆出带专业标题的短说明块；真实 PDF 离线测试覆盖 C128 依据句。修改切块规则后需重建索引，再运行在线评测确认召回变化。

现有自动指标包括 Hit@K、Recall@K、MRR、误拒/过召回、来源日期与适用范围匹配、角标可映射率、数字学分已引来源字面匹配率、TTFT，以及独立模型 Judge 的答案评分。角标可映射率只说明引用编号存在；数字学分匹配率也只检查少数标签和数值是否出现在已引片段中，不能证明完整语义支撑。事实完整性、引用位置、适用范围和规则冲突仍需人工复核。详见 [使用与评测说明](doc/电兵助手-使用与评测.md)。

若回答中的学分数字无法在已引片段中找到对应字面事实，问答页会提示核对原文；刷新会话后提示仍保留。此提示只覆盖学分数值，不会自动改写或删除回答。

本次真实 PDF 的运行结果、失败样本和速度记录见 [2026-09-22 评测报告](doc/电兵助手-评测报告-2026-09-22.md)。

DeepSeek profile 的实测结果见 [DeepSeek 评测报告](doc/电兵助手-DeepSeek评测报告-2026-09-22.md)。

DeepSeek 对话接口默认已启用。在 PowerShell 中设置 API Key 后启动即可：

```powershell
$env:RAG_CHAT_API_KEY = '<你的 DeepSeek API Key>'
```

也可按需覆盖默认接口或模型：

```powershell
$env:RAG_CHAT_API_BASE_URL = 'https://api.deepseek.com'
$env:RAG_CHAT_MODEL = 'deepseek-flash'
```

密钥只放在环境变量 `RAG_CHAT_API_KEY` 中，不要提交到配置文件。`RAG_API_BASE_URL` 与 `LLM_API_KEY` 仍控制本地 Ollama 向量接口。修改环境变量后重启服务。

## 当前边界

- 语料仅覆盖已导入且启用的规则。目前自带的有效语料只针对 2026 级全日制学术学位硕士研究生培养方案。宿舍、奖助、请假、本科生等主题需要继续导入官方文件。
- PDF 文本可以提取，但复杂课程表可能在纯文本中丢失列关系；表格类答案应点击官方原文核对。
- 当前没有管理员登录与审核流程，默认仅允许本机访问。面向学生开放前需完成权限、发布审核和定期核验。
- 校园工具使用演示 Mock 数据，尚未接入真实教务系统，也没有真实学生身份认证。

## 项目定位与下一步

电兵助手当前以校园规则 RAG 和只读校园 MCP 工具为核心。RAG 检索官方规则并给出引用；MCP 提供校园结构化数据查询；确定性服务负责学分计算等业务逻辑。当前工具数据为 Mock 演示数据，尚不能据此回答真实学生的个人学业问题。

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

本仓库当前结构以 `knowledge`、`qa`、`campus` 和 `infrastructure` 为边界；新增能力应归入对应业务包，避免重新形成按技术层横向散落的结构。
