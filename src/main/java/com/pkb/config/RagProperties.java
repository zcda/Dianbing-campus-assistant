package com.pkb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * rag.* 全量配置（改造设计文档 §8）。
 *
 * <p>嵌套配置组通过 getter 返回的可变实例绑定，各配置含义与默认值见改造设计文档；
 * 关键默认值的依据：
 * <ul>
 *   <li>min-similarity 0.60 / min-gate-score 0.60：阈值扫描实测信号下限 0.608 vs 噪声上限 0.578（D4）；</li>
 *   <li>chunk 1024/128：防语义稀释靠章节边界而非压小块，重叠等比 = max/8（D5）；</li>
 *   <li>rrf-k 60：标准 RRF 常数；关键词通道先降权 0.6 防噪声抢前排（D3）。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    // ===== 模型 =====

    /** OpenAI 兼容服务基地址，默认本地 Ollama */
    private String apiBaseUrl = "http://localhost:11434/v1";

    /** API Key：Ollama 无需鉴权，默认占位 "ollama"；云 API 通过环境变量 LLM_API_KEY 注入 */
    private String apiKey = "ollama";

    /** 可选：对话走独立服务；为空时沿用 apiBaseUrl，向量仍走 apiBaseUrl。 */
    private String chatApiBaseUrl;

    /** 对话服务独立部署时必须配置；未分离时沿用 apiKey。 */
    private String chatApiKey;

    private String chatModel = "qwen2.5:7b";

    /** 向量模型：bge-m3（1024 维、中英文友好）；生成模型（如 llama3.1）不能做向量化 */
    private String embeddingModel = "bge-m3";

    private int embeddingDimension = 1024;

    /**
     * 拒答兜底话术（D8 单一事实来源）：护栏拦截、闸门丢弃、SYSTEM_PROMPT 规则 3、README 验收标准
     * 全部引用这一份文本，全仓"未找到"类文案只出现在这里。
     */
    private String notFoundAnswer = "未找到足以回答该问题的当前有效校园规则。请核对身份、校区或学年，并查看学校官方文件。";
    /** 仅允许学校官网子域名的链接被标记为有效规则。 */
    private String officialDomain = "uestc.edu.cn";

    // ===== 召回 =====

    /** 最终送进 Prompt 的片段数 */
    private int topK = 5;

    /** 向量通道 SQL 层单条过滤阈值（余弦相似度下限，拦截明显不相关的单条候选） */
    private double minSimilarity = 0.50;

    private final Retrieval retrieval = new Retrieval();
    private final Channels channels = new Channels();
    private final Fusion fusion = new Fusion();
    private final Evidence evidence = new Evidence();
    private final Chunk chunk = new Chunk();
    private final Rewrite rewrite = new Rewrite();
    private final TermMapping termMapping = new TermMapping();
    private final Index index = new Index();
    private final Eval eval = new Eval();

    // ===== getter / setter =====

    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getChatApiBaseUrl() { return chatApiBaseUrl == null || chatApiBaseUrl.isBlank()
            ? apiBaseUrl : chatApiBaseUrl; }
    public void setChatApiBaseUrl(String chatApiBaseUrl) { this.chatApiBaseUrl = chatApiBaseUrl; }

    public String getChatApiKey() {
        if (chatApiKey != null && !chatApiKey.isBlank()) return chatApiKey;
        return chatApiBaseUrl == null || chatApiBaseUrl.isBlank() ? apiKey : null;
    }
    public void setChatApiKey(String chatApiKey) { this.chatApiKey = chatApiKey; }

    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public int getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(int embeddingDimension) { this.embeddingDimension = embeddingDimension; }

    public String getNotFoundAnswer() { return notFoundAnswer; }
    public void setNotFoundAnswer(String notFoundAnswer) { this.notFoundAnswer = notFoundAnswer; }
    public String getOfficialDomain() { return officialDomain; }
    public void setOfficialDomain(String officialDomain) { this.officialDomain = officialDomain; }

    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }

    public double getMinSimilarity() { return minSimilarity; }
    public void setMinSimilarity(double minSimilarity) { this.minSimilarity = minSimilarity; }

    public Retrieval getRetrieval() { return retrieval; }
    public Channels getChannels() { return channels; }
    public Fusion getFusion() { return fusion; }
    public Evidence getEvidence() { return evidence; }
    public Chunk getChunk() { return chunk; }
    public Rewrite getRewrite() { return rewrite; }
    public TermMapping getTermMapping() { return termMapping; }
    public Index getIndex() { return index; }
    public Eval getEval() { return eval; }

    // ===== 嵌套配置组 =====

    /** 每通道扩池上限（粗排），必须 > top-k，两阶段"粗排扩池 + 闸门精选"的前提 */
    public static class Retrieval {
        private int candidateLimit = 20;
        public int getCandidateLimit() { return candidateLimit; }
        public void setCandidateLimit(int candidateLimit) { this.candidateLimit = candidateLimit; }
    }

    public static class Channels {
        private final Vector vector = new Vector();
        private final Keyword keyword = new Keyword();
        public Vector getVector() { return vector; }
        public Keyword getKeyword() { return keyword; }

        public static class Vector {
            private boolean enabled = true;
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
        }

        /** 关键词通道（D2）：bigram + tsvector，术语精确匹配 */
        public static class Keyword {
            private boolean enabled = true;
            private String tokenizer = "bigram";
            private double weight = 0.6;
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public String getTokenizer() { return tokenizer; }
            public void setTokenizer(String tokenizer) { this.tokenizer = tokenizer; }
            public double getWeight() { return weight; }
            public void setWeight(double weight) { this.weight = weight; }
        }
    }

    /** RRF 融合（D3）：两路分数量纲不可比，只看名次合成一路 */
    public static class Fusion {
        /** rrf | none */
        private String strategy = "rrf";
        private int rrfK = 60;
        private Map<String, Double> channelWeights = new LinkedHashMap<>(Map.of("vector", 1.0, "keyword", 0.6));
        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public int getRrfK() { return rrfK; }
        public void setRrfK(int rrfK) { this.rrfK = rrfK; }
        public Map<String, Double> getChannelWeights() { return channelWeights; }
        public void setChannelWeights(Map<String, Double> channelWeights) { this.channelWeights = channelWeights; }
    }

    /** 批级证据闸门（D4）：max(gateScore) 不过线则整批丢弃、不调 LLM */
    public static class Evidence {
        /** batch（整批判）| none（关闭） */
        private String mode = "batch";
        /** 批级最高分下限，<=0 表示关闭闸门（v2 语料扫描定档：信号/噪声重叠无完美分界，按"误丢比误放贵"取 0.50） */
        private double minGateScore = 0.50;
        /** Cohort/program-prefixed questions must also match their actual topic; <=0 disables this check. */
        private double minFocusScore = 0.55;
        /** 无分可读时的策略：allow = fail-open 放行 + WARN（降级路径绝不制造假阴性） */
        private String onMissingScore = "allow";
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public double getMinGateScore() { return minGateScore; }
        public void setMinGateScore(double minGateScore) { this.minGateScore = minGateScore; }
        public double getMinFocusScore() { return minFocusScore; }
        public void setMinFocusScore(double minFocusScore) { this.minFocusScore = minFocusScore; }
        public String getOnMissingScore() { return onMissingScore; }
        public void setOnMissingScore(String onMissingScore) { this.onMissingScore = onMissingScore; }
    }

    /** 分块预算（D5）：maxChars 是目标不是硬上限 */
    public static class Chunk {
        /** heading | fixed */
        private String strategy = "heading";
        private int maxChars = 1024;
        /** null 时默认 = maxChars / 8（等比重叠：重叠同时是回退找句末标点的最大距离） */
        private Integer overlapChars;
        /** 允许超出 maxChars 的倍数（切开语义单元的代价高于超出目标），实际硬上限封顶 8192 */
        private int toleranceFactor = 3;
        /** 留口：将来表格切片 */
        private int rowsPerChunk = 50;
        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
        public Integer getOverlapChars() { return overlapChars; }
        public void setOverlapChars(Integer overlapChars) { this.overlapChars = overlapChars; }
        public int getToleranceFactor() { return toleranceFactor; }
        public void setToleranceFactor(int toleranceFactor) { this.toleranceFactor = toleranceFactor; }
        public int getRowsPerChunk() { return rowsPerChunk; }
        public void setRowsPerChunk(int rowsPerChunk) { this.rowsPerChunk = rowsPerChunk; }
    }

    /** 查询改写 + 多问句拆分（D6） */
    public static class Rewrite {
        private boolean enabled = true;
        private int maxSubQuestions = 3;
        /** 改写要稳定不要发散 */
        private double temperature = 0.1;
        private double topP = 0.3;
        /** 超时即走规则兜底（本地 8B 模型生成 JSON 实测 10~20s，配合 R4 规则预判只对复合问句改写） */
        private long timeoutMs = 15000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxSubQuestions() { return maxSubQuestions; }
        public void setMaxSubQuestions(int maxSubQuestions) { this.maxSubQuestions = maxSubQuestions; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public double getTopP() { return topP; }
        public void setTopP(double topP) { this.topP = topP; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    /** 词表映射（FR-15，P2）：同义词/别名归一，纯规则零 token */
    public static class TermMapping {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** 异步索引（D9）：DB 任务表 + 单线程执行器，不引 MQ */
    public static class Index {
        private boolean asyncEnabled = true;
        private int maxRetry = 3;
        private int workerThreads = 1;
        private long retryBackoffMs = 2000;
        public boolean isAsyncEnabled() { return asyncEnabled; }
        public void setAsyncEnabled(boolean asyncEnabled) { this.asyncEnabled = asyncEnabled; }
        public int getMaxRetry() { return maxRetry; }
        public void setMaxRetry(int maxRetry) { this.maxRetry = maxRetry; }
        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
        public long getRetryBackoffMs() { return retryBackoffMs; }
        public void setRetryBackoffMs(long retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }
    }

    /** 评测（仅测试用，D7） */
    public static class Eval {
        private String dataset = "/eval/campus_eval_v1.jsonl";
        /** judge 与生产不同模型，避免自己评自己 */
        private String judgeModel = "qwen3:14b";
        public String getDataset() { return dataset; }
        public void setDataset(String dataset) { this.dataset = dataset; }
        public String getJudgeModel() { return judgeModel; }
        public void setJudgeModel(String judgeModel) { this.judgeModel = judgeModel; }
    }
}
