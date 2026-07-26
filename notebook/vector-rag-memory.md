# 向量数据库与 AI 长期记忆 —— 项目学习笔记

## 1. 向量数据库是什么？

### 一句话定义
向量数据库 = 以"向量（embedding）"为索引的专用数据库，核心操作是"给定一个向量，找出最相似的向量"。

### 为什么需要向量数据库

大模型有两大痛点：
1. **上下文长度有限**：Kimi k2.6 虽能处理长上下文，但塞入整本 PDF 会稀释注意力、增加成本。
2. **知识不是实时的**：模型权重训练完就固定了，无法记住本次对话里的私人事实。

向量数据库解决思路：
- 把文档/事实切成小块 → 用 Embedding 模型转成向量 → 存进向量库。
- 查询时把用户问题也转成向量 → 做相似度检索 → 只把最相关的片段塞进 prompt。
- 这样模型每次只需处理"相关片段 + 用户问题"，而不是整本 PDF 或全部历史。

### 相似度怎么算

最常用的是**余弦相似度（Cosine Similarity）**：

```
cos(A,B) = (A·B) / (|A|*|B|)
```

值域 `[-1, 1]`，越接近 1 越相似。OpenAI/DashScope 等 embedding 模型输出的向量通常已经归一化，所以点积 ≈ 余弦相似度。

### 向量检索的常见索引

| 索引类型 | 原理 | 优点 | 缺点 |
|---|---|---|---|
| 暴力扫描（Flat） | 逐个计算余弦相似度 | 精确、实现简单 | 数据量大时慢 O(n) |
| HNSW | 近似最近邻图索引 | 快、召回率高 | 内存占用大 |
| IVF | 先聚类再搜索 | 内存友好 | 召回率略低 |
| PQ / SCaNN | 向量量化压缩 | 省内存 | 精度有损失 |

**本项目用的是 SimpleVectorStore，内部是暴力扫描**，面试时要说清：学习/ demo 场景够用，生产环境要换 HNSW / pgvector / Milvus。

---

## 2. 本项目 RAG 全链路

```
用户上传 PDF
    │
    ▼
PagePdfDocumentReader（按页粗切，保留 page_number）
    │
    ▼
给每页 Document 补 file_name 元数据（修复过滤 bug）
    │
    ▼
TokenTextSplitter（按 token 细切，chunkSize=500）
    │
    ▼
DashScope text-embedding-v3（1024 维）生成向量
    │
    ▼
SimpleVectorStore.add(chunks) 写入内存向量库
    │
    ▼
用户提问时：RerankAdvisor 粗排 topK=8 → LLM 精排 top 3
    │
    ▼
把 top 3 文档片段注入 prompt → Kimi k2.6 生成回答
```

### 接口契约

- 上传：`POST /ai/pdf/upload/{chatId}`（MultipartFile）
- 对话：`GET/POST /ai/pdf/chat?prompt=...&chatId=...`（SSE 流式返回）
- 前后端接口**没有变化**，所有升级都是后端内部实现。

### 关键代码对应

| 环节 | 文件 | 关键类/方法 |
|---|---|---|
| 切块 | `controller/PdfController.java` | `writeToVectorStore(...)` |
| Embedding | `application.yaml` | `spring.ai.openai.embedding.*` |
| 向量库 | `config/CommonConfiguration.java` | `VectorStore vectorStore(...)` |
| 检索+重排 | `advisor/RerankAdvisor.java` | `before()` |
| 记忆注入 | `advisor/SemanticMemoryAdvisor.java` | `before()` |

---

## 3. 文本切块（Chunking）策略

### 五种策略对比

| 策略 | 做法 | 优点 | 缺点 | 本项目使用 |
|---|---|---|---|---|
| 固定大小分块 | 按 token/字符数切分 | 简单、速度快 | 可能切断语义 | ✅ TokenTextSplitter |
| 语义分块 | 按段落/章节/句子边界 | 语义完整 | 依赖文档结构解析 | ❌ 未用 |
| 滑动窗口 | 固定大小 + overlap | 保留上下文连续性 | 存储冗余 | ❌ M6 不支持 |
| 递归分块 | 先按大分隔符，再逐步细化 | 灵活 | 实现复杂 | ❌ 未用 |
| 文档结构感知 | 保留标题层级、表格、页码 | 检索结果可溯源 | 需针对格式定制 | ✅ 保留 page_number |

### 本项目选型理由

- **按页粗切**：PDF 天然有页边界，`PagePdfDocumentReader` 自动产出 `page_number` metadata，回答时可以标注"出自第 N 页"。
- **按 token 细切**：防止单页过长超出 embedding 模型 token 上限，也防止单页过短导致检索粒度太粗。
- **无 overlap**：Spring AI 1.0.0-M6 的 `TokenTextSplitter` 不支持 overlap 参数。面试时诚实说明版本限制，但解释 chunkSize=500 + minChunkSizeChars=350 的宽松下限能在语义边界处自然保留上下文。

### 关键参数

```java
TokenTextSplitter.builder()
    .withChunkSize(500)             // 每个 chunk 约 500 tokens
    .withMinChunkSizeChars(350)     // 最小 350 字符，防止碎片
    .withMinChunkLengthToEmbed(50)  // 小于 50 tokens 不单独入向量库
    .withMaxNumChunks(100)          // 单页最多 100 个 chunk，防止异常大页
    .withKeepSeparator(true)        // 保留段落分隔符
    .build();
```

### 面试话术

> "我们采用两级切块：先按 PDF 页边界粗切（保留页码 metadata 实现可溯源），再用 TokenTextSplitter 按 token 数细切。chunkSize 500、minChunk 350，兼顾语义完整和检索粒度。当前 Spring AI M6 不支持 overlap，后续升级 M7 后会补上滑动窗口。"

---

## 4. Embedding 与向量检索

### 本项目 Embedding 配置

```yaml
spring:
  ai:
    openai:
      embedding:
        base-url: https://dashscope.aliyuncs.com/compatible-mode
        api-key: ${DASHSCOPE_API_KEY}
        options:
          model: text-embedding-v3
          dimensions: 1024
```

### 为什么聊天用 Kimi、Embedding 仍用 DashScope

1. **向量维度绑定**：已存 PDF 向量都是 1024 维。如果换 Embedding 模型，维度会变，已存向量库直接报错。
2. **迁移成本高**：换模型后必须清空 `chat-pdf.json`，重新上传所有 PDF。
3. **计费隔离**：对话和 Embedding 分别计费，用不同 key 方便看各自消耗。

### 向量检索流程

```java
SearchRequest.builder()
    .query(userQuery)
    .topK(8)
    .similarityThresholdAll()
    .filterExpression("file_name == 'xxx.pdf'")  // 可选过滤
    .build();
```

SimpleVectorStore 内部：
1. 把 query 也 embedding 成 1024 维向量
2. 与库中每个 chunk 向量算余弦相似度
3. 按相似度降序返回 topK

---

## 5. Rerank 重排

### 为什么需要 Rerank

向量检索（余弦相似度）速度快但**语义理解弱**：
- 它只看"词向量平均后的方向是否接近"
- 可能把"包含相同关键词但不相关"的片段排在前面
- 对长文档、同义词、否定句效果差

Rerank = 用更强的模型（通常是 LLM）对粗排结果做二次精排。

### 本项目两级排序

```
用户问题
    │
    ▼
粗排：vectorStore.similaritySearch(topK=8)
      余弦相似度，快速召回候选
    │
    ▼
精排：LLM 对每个候选打 1-10 分
      "Query: ...\nDocument: ...\nRate relevance 1-10."
    │
    ▼
取 top 3 注入 prompt
```

### 代码实现要点

- 自定义 `RerankAdvisor` 实现 `BaseAdvisor`
- `before()` 中完成检索 + 打分 + 注入
- 支持 `FILTER_EXPRESSION` advisor param，与 QuestionAnswerAdvisor 用法兼容
- 打分失败时给一个默认低分，不阻塞主流程

### 面试话术

> "RAG 里向量检索是粗排，靠余弦相似度快速召回但语义理解有限。我们加了一层 LLM 精排：先召回 8 个候选，再用大模型给每个候选打相关分，取 top 3 注入 prompt。这样兼顾速度和准确度。"

---

## 6. AI 长期记忆系统

### 为什么 Redis ChatMemory 不是长期记忆

RedisChatMemory 存的是**会话原文**（精确到每条 User/Assistant/System 消息）：
- 优点：能精确复现最近 N 轮对话
- 缺点：换 chatId 就失效；token 占用高；模型需要读大量原文才能提炼事实

### 语义长期记忆做什么

把原文中的**事实**提炼出来，单独向量化存储：
- "我是一名大三计算机学生" → 事实
- "我正在准备秋招" → 事实
- "我喜欢打篮球" → 事实

下次用户换 chatId 后提问"我适合学什么"，系统召回这些事实，在 system prompt 里告诉模型：

```
【你的长期记忆】
你在之前的对话中了解到关于用户的以下事实：
- 用户是计算机专业大三学生
- 用户正在准备秋招
- 用户喜欢打篮球
```

### 双层记忆架构

```
短期记忆（Redis ChatMemory）        长期记忆（SemanticMemoryService）
├── 存：完整消息原文                 ├── 存：提炼的事实向量
├── key: chat_memory:{chatId}       ├── key: semantic-memory.json（本地文件）
├── 作用：当前会话上下文             ├── 作用：跨会话语义召回
└── 生命周期：会话级                  └── 生命周期：永久（持久化文件）
```

### 事实提取流程

```
流式响应结束（ChatController.doOnComplete）
    │
    ▼
SemanticMemoryService.extractAndStore(chatId)
    │
    ▼
从 RedisChatMemory 读最近 20 条消息
    │
    ▼
取最后一条 USER + 最后一条 ASSISTANT
    │
    ▼
LLM prompt："提取关于用户的事实性记忆，以 - 开头每条一行"
    │
    ▼
解析返回的列表 → 向量化 → 存入 SimpleVectorStore
```

### 为什么不在 Advisor.after() 里提取

Spring AI 1.0.0-M6 的 `BaseAdvisor.aroundStream()` 会对**每一块 chunk** 调用一次 `after()`，会导致：
1. 重复提取同一条事实
2. assistant 消息还没完整就触发
3. 多次调用 LLM，浪费 token

所以提取逻辑改由 `ChatController` 在 `Flux.doOnComplete()` 中触发，保证只执行一次。

### 面试话术

> "我们把记忆分成两层：Redis 存会话原文做短期记忆，向量库存提炼的事实做长期记忆。短期记忆精确但换会话就失效；长期记忆跨会话召回，让 AI 持续了解用户。事实提取不用 Advisor.after()，因为流式场景下 after() 会对每块 chunk 重复触发，我们改在 Controller 的 doOnComplete 里统一触发。"

---

## 7. 生产环境向量数据库选型

| 方案 | 适用场景 | 优点 | 缺点 |
|---|---|---|---|
| **SimpleVectorStore** | 学习/demo | 零运维、开箱即用 | 内存中、暴力扫描、无并发 |
| **pgvector** | 已有 PostgreSQL 的团队 | SQL 友好、ACID、可 JOIN | 大并发时性能一般 |
| **Milvus / Zilliz** | 大规模向量检索 | 分布式、HNSW、企业级 | 运维重、学习曲线陡 |
| **Redis Stack (RediSearch)** | 已有 Redis 的团队 | 低延迟、与缓存统一 | 向量功能需 RediSearch 模块 |
| **ChromaDB** | Python 生态快速原型 | 安装简单、本地优先 | Java 生态支持弱 |
| **Pinecone / Weaviate** | 全托管 SaaS | 免运维、弹性扩缩 | 成本高、数据在外部 |

### 本项目为什么用 SimpleVectorStore

1. **学习阶段**：先理解 RAG 原理，不被某个具体产品 API 绑架。
2. **零运维**：本地文件即可，不用装 PostgreSQL / Milvus。
3. **接口隔离**：Spring AI 的 `VectorStore` 是接口，换实现只改一个 Bean。

### 迁移路径

如果未来要换 pgvector：
1. 引入 `spring-ai-pgvector-store-spring-boot-starter`
2. 把 `CommonConfiguration.vectorStore()` 改成返回 `PgVectorStore`
3. 业务代码（Controller、Advisor、Service）**一行都不用改**

---

## 8. 面试高频问题与话术

### Q1: 你们怎么实现 RAG 的？

> "用户上传 PDF 后，先按页粗切保留页码，再用 TokenTextSplitter 按 token 细切，调用 DashScope text-embedding-v3 生成 1024 维向量，存入 SimpleVectorStore。提问时，RerankAdvisor 先做向量检索召回 8 个候选，再用 LLM 打分取 top 3 注入 prompt，最后由 Kimi k2.6 生成回答。"

### Q2: 为什么要切块？怎么切的？

> "如果不切块，整页或整篇 PDF 直接 embedding 会超出 token 上限，而且检索粒度太粗。我们先用 PagePdfDocumentReader 按页切（保留 page_number 可溯源），再用 TokenTextSplitter 按 500 token 细切，每个 chunk 继承 file_name 和 page_number 元数据。"

### Q3: Rerank 解决了什么问题？

> "向量检索基于余弦相似度，快但语义理解有限，容易把含相同关键词但不相关的片段排前面。Rerank 用 LLM 对候选文档做二次语义打分，只把最相关的注入 prompt，提升回答准确度。"

### Q4: 什么是向量数据库在 AI 长期记忆中的应用？

> "短期记忆存会话原文，换会话就失效。长期记忆把用户的事实性陈述（偏好、背景、目标）提炼出来向量化，跨会话也能语义召回。我们用 Redis 做短期记忆，SimpleVectorStore 做长期记忆，形成双层架构。"

### Q5: 你们为什么用 SimpleVectorStore 而不是 Milvus/pgvector？

> "学习阶段用 SimpleVectorStore 零运维、零成本，先把 RAG 链路跑通。Spring AI 的 VectorStore 是接口，生产环境换 pgvector 或 Milvus 只改一个 Bean，业务代码零改动。"

### Q6: 你做过哪些缓存/性能优化？

> "除了 Redis ChatMemory 持久化，课程/校区查询用 Spring Cache + Redis 做了缓存。RAG 侧通过 Rerank 减少注入 prompt 的噪音，提高检索质量。"

---

## 9. 后续可扩展方向

1. **overlap 支持**：Spring AI 升级到 1.0.0-M7+ 后给 TokenTextSplitter 加 overlap。
2. **Rerank 模型**：用 DashScope 或火山引擎的专用 rerank API，替代 LLM 打分。
3. **向量库升级**：把 SimpleVectorStore 换成 pgvector / Milvus，解决并发和扩展问题。
4. **记忆去重**：语义记忆存储前先做相似度去重，避免重复事实积累。
5. **多模态记忆**：用户上传的图片/PDF 也可以用多模态 embedding 加入长期记忆。

---

## 10. 关键文件速查

| 文件 | 作用 |
|---|---|
| `controller/PdfController.java` | PDF 上传、切块、写入向量库 |
| `advisor/RerankAdvisor.java` | 粗排 + LLM 精排 |
| `advisor/SemanticMemoryAdvisor.java` | 语义记忆召回并注入 system prompt |
| `memory/SemanticMemoryService.java` | 事实提取、向量化、持久化 |
| `controller/ChatController.java` | 流式响应结束后触发事实提取 |
| `config/CommonConfiguration.java` | ChatClient + VectorStore + Advisor 装配 |
| `notebook/vector-rag-memory.md` | 本学习笔记 |

---

*最后更新：2026-07-26*  
*对应项目：iheima_springboot3_realization*