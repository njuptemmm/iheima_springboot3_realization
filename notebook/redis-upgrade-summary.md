# Redis 升级任务总结

> 时间：2026-07-23 ~ 2026-07-24
> 项目：`iheima_springboot3_realization`（Spring Boot 3 + Spring AI 1.0.0-M6 + Kimi + MySQL）
> 目标：把 MySQL 使用场景中"真正适合 Redis"的部分迁到 Redis，同时把消息内容持久化，让 AI 会话重启不丢

---

## 一、完成的内容一览

按提交顺序，三个阶段：

### 阶段 1：Redis 引入 + 会话 chatId 持久化 + course/school 缓存
commit `b31b216` — feat(redis): introduce Redis for chat history + course/school caching

- 引入 Spring Data Redis + Spring Cache 依赖
- 配置 Redis 连接（`localhost:6379`，Memurai）与 Spring Cache（TTL 10min，key 前缀 `demo_ai_2:cache:`）
- 新增 `RedisConfig`：`RedisTemplate` 采用 String+JSON 序列化，启用 `@EnableCaching`
- 新增 `RedisChatHistoryRepository`（`@Primary`）：用 Redis Set 存 `chat_history:{type}`，代替内存版
- `InMemoryChatHistoryRepository` 保留作学习对照
- `CourseTools.querySchool()` / `queryCourses()` 加 `@Cacheable`
- 敏感配置迁移：yaml 移除明文，走 `application-local.yaml`（.gitignore 排除）+ `application-local.yaml.example` 模板

### 阶段 2：切换到 Kimi 模型 + 去硬编码
commit `3e7d473` — feat(model): switch chat to Kimi

- 对话模型：DashScope `qwen-omni-turbo` → Kimi `kimi-k2.6`
- Embedding 保留 DashScope（保留已有 PDF 向量数据）
- 移除 `CommonConfiguration.chatClient()` 里的硬编码 `.model("qwen-omni-turbo")`，让 4 个 ChatClient 统一走 yaml

### 阶段 3：ChatMemory 消息内容持久化
commit `7a0e82d` — feat(redis): persist ChatMemory to Redis, survive restart

- 新增 `RedisChatMemory`（`@Component`）：Redis List 存 `chat_memory:{conversationId}`
- 中间 DTO `PersistedMessage`：只序列化 type/text/metadata，丢弃 Media 和 ToolCall
- 移除 `CommonConfiguration` 里手动的 `InMemoryChatMemory` bean，让 `@Component` 实现自动接管
- `GlobalExceptionHandler`：单独处理 `NoResourceFoundException`（404 走 DEBUG，不打堆栈）

### 端到端验证结果
- ✅ 后端重启，`smembers chat_history:chat` 仍能返回历史 chatId
- ✅ 后端重启，`lrange chat_memory:{id} 0 -1` 仍完整保留消息 JSON
- ✅ 后端重启，AI 用同一个 chatId 仍能回答"你叫什么"（记忆生效）
- ✅ 客服 AI 查校区/课程命中缓存（第二次调用无 SQL 日志）
- ✅ 接口路径、请求参数、响应结构全部保持不变（前端零改动）

---

## 二、知识点整理

### 1. Redis 数据结构选型

Redis 提供 5 种基础数据结构，本次用了 3 种：

| 数据结构 | 命令 | 项目里用在 | 选型理由 |
|---|---|---|---|
| **Set**（无序集合） | `SADD` / `SMEMBERS` / `SCARD` | `chat_history:{type}` 存 chatId 列表 | 天然去重（对应原代码里的 `contains()` 判断）、O(1) 添加、并发安全 |
| **List**（有序列表） | `RPUSH` / `LRANGE key -N -1` / `DEL` | `chat_memory:{chatId}` 存对话消息流 | 消息有顺序，`LRANGE -N -1` 完美对应"取最近 N 条" |
| **String**（键值对） | `GET` / `SET` / `TTL` | `demo_ai_2:cache:{cacheName}::{key}` 存缓存 | Spring Cache 底层默认存法，String 存 JSON 后可读性最好 |

**没用到但要知道的**：
- **Hash**（哈希表）：适合存对象各字段，`HSET user:1 name Alice age 20`
- **ZSet**（有序集合）：带 score 排序，适合排行榜、分页排序索引

### 2. Spring Data Redis 集成

**依赖**：
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

**默认客户端**：Lettuce（非阻塞、线程安全，比 Jedis 现代）。

**核心操作模板**：
- `RedisTemplate<String, Object>`：通用模板，值可以是任意 JSON 对象
- `StringRedisTemplate`：值只能是 String 的轻量版，用于 chatId、消息 JSON 等纯字符串场景

**序列化策略**（本项目在 `RedisConfig` 里配的）：
- key/hashKey：`StringRedisSerializer`（UTF-8 字符串，`memurai-cli` 能直读）
- value/hashValue：`GenericJackson2JsonRedisSerializer`（JSON 存储，跨语言兼容）
- 用 `BasicPolymorphicTypeValidator` 限制反序列化白名单（安全考量）

### 3. Spring Cache 抽象层

Spring Cache 提供**声明式缓存**，可切换底层实现（Caffeine / Redis / EhCache 等），业务代码零改动。

**核心注解**：
| 注解 | 作用 |
|---|---|
| `@EnableCaching` | 启用缓存（放 `RedisConfig` 上）|
| `@Cacheable(value, key)` | 方法结果自动缓存（命中直接返回，未命中查完存缓存）|
| `@CacheEvict(value, key)` | 主动失效缓存（更新数据时调用）|
| `@CachePut` | 强制刷新缓存 |

**key 用 SpEL**：`@Cacheable(value="courses", key="#query != null ? #query.toString() : 'null'")`

**全局配置**（`application.yaml`）：
```yaml
spring.cache:
  type: redis
  redis:
    time-to-live: 600000        # TTL 10min
    cache-null-values: false    # 不缓存 null，避免缓存穿透加剧
    use-key-prefix: true
    key-prefix: "demo_ai_2:cache:"
```

### 4. Spring AI 的 ChatMemory 机制

**接口**（org.springframework.ai.chat.memory.ChatMemory）：
```java
void add(String conversationId, List<Message> messages);
List<Message> get(String conversationId, int lastN);
void clear(String conversationId);
```

**调用时机**（由 `MessageChatMemoryAdvisor` 自动触发）：
1. 每次请求前：`get(chatId, N)` 拉取历史，注入到 messages 数组
2. 模型响应后：`add(chatId, [userMsg, assistantMsg])` 存回

**LLM 记忆的本质**：**大模型本身无状态**，"记忆"是我们**每次都带完整历史**给它模拟出来的。所以 ChatMemory 决定了 AI 能"记住"多少。

**Message 子类型**：
- `UserMessage` — 用户输入
- `AssistantMessage` — 模型回复（含 ToolCall 参数）
- `SystemMessage` — 系统提示词
- `ToolResponseMessage` — 工具调用结果

本项目 `RedisChatMemory` 只持久化前 3 种文本消息，理由：Media（图片/PDF）和 ToolCall 是当次交互的临时资源，历史里保留没意义。

### 5. 多实现优先级：`@Primary` 与 `@Component` 组合

**场景**：`InMemoryChatHistoryRepository` 和 `RedisChatHistoryRepository` 都实现同一接口，Spring 该注入哪个？

**解决**：Redis 版加 `@Primary`，Spring 遇到多实现时默认选它。内存版仍作为 bean 存在（`@Component`），方便：
- 单元测试时可以显式注入内存版
- 想切回内存版时移动 `@Primary` 即可

### 6. 敏感配置分离（Profile 机制）

**问题**：API key、数据库密码不能进 git，但代码需要它们。

**方案**：
- `application.yaml`（进 git，无敏感值）设 `spring.profiles.active=local`
- `application-local.yaml`（`.gitignore` 排除，存真实值）
- `application-local.yaml.example`（进 git，占位模板，帮别人知道格式）

**Spring Boot 加载顺序**：
1. 先加载 `application.yaml`
2. 根据 `spring.profiles.active=local`，加载 `application-local.yaml`
3. 后者覆盖前者的同名 key

### 7. 全局异常处理与 404 特殊处理

**兜底陷阱**：`@ExceptionHandler(Exception.class)` 会把 404（`NoResourceFoundException`）也当成 500 抛，日志刷屏。

**正解**：给 `NoResourceFoundException` 单独 Handler，`@ResponseStatus(HttpStatus.NOT_FOUND)` 修正 HTTP 状态码，日志级别降到 DEBUG。

---

## 三、技术栈全景

### 后端主体
| 层 | 技术 | 版本 |
|---|---|---|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.5.3 |
| AI 集成 | Spring AI（OpenAI 兼容协议） | 1.0.0-M6 |
| Web | Spring MVC（`spring-boot-starter-web`）| 3.5.3 |
| 参数校验 | Bean Validation（`spring-boot-starter-validation`）| 3.5.3 |
| ORM | MyBatis-Plus | 3.5.10.1 |
| 缓存抽象 | Spring Cache（`spring-boot-starter-cache`）| 3.5.3 |
| Redis 客户端 | Spring Data Redis + Lettuce | 3.5.3 |
| 事务 | Spring Transaction (`@Transactional`) | 3.5.3 |
| 简化代码 | Lombok | 父 POM 管理 |
| 构建 | Maven Wrapper | 3.9.10 |

### 大模型服务商
| 用途 | 服务商 | 模型 |
|---|---|---|
| Chat（对话）| Kimi / Moonshot | `kimi-k2.6` |
| Embedding（向量）| 阿里云 DashScope | `text-embedding-v3`（1024 维） |

### 数据层
| 用途 | 软件 | 端口 |
|---|---|---|
| 关系型（course/school/course_reservation）| MySQL | 3306 |
| 缓存 + KV（会话历史 + 消息 + 查询缓存）| Memurai（Redis 兼容）| 6379 |
| 向量库（PDF RAG）| SimpleVectorStore（本地文件）| — |

### 前端
| 技术 | 版本 |
|---|---|
| Vue | 3.4.15 |
| Vite | 6.1.x |
| 端口 | 5173（dev）|

### 开发工具
- Git + GitHub
- Memurai Developer（Windows 版 Redis）
- IDE：VS Code + Claude Code

---

## 四、关键架构决策与"为什么"

### 决策 1：不完全替换 MySQL，而是分角色
- MySQL 留：`course_reservation`（预约单，交易数据不能丢）
- Redis 用：`chat_history`（chatId 列表）、`chat_memory`（消息内容）、`course/school` 缓存
- **理由**：Redis 内存存储 + 弱事务，不适合承担"丢了会赔钱"的交易数据

### 决策 2：ChatMemory 用 List 而非 Hash
- 场景是"取最近 N 条"，List 的 `LRANGE key -N -1` O(N) 完成
- Hash 需要额外维护有序索引，代码复杂度增加

### 决策 3：`RedisChatMemory` 用中间 DTO 而非直接序列化 Message
- Spring AI 的 `Message` 子类含 `Media`、`ToolCall` 等复杂对象，直接 JSON 化会失败
- 中间 DTO 只保留会话记忆真正需要的 3 个字段
- **额外好处**：Spring AI 升级导致 Message 类结构变化时，不影响已存数据的兼容性

### 决策 4：内存版实现保留而非删除
- 学习对照价值
- 单元测试时可绕开 Redis 依赖
- 切回内存版只需移动 `@Primary`

### 决策 5：敏感配置走 profile 而非环境变量
- 环境变量在长期不用的机器/新机器上容易忘记设
- profile 文件放项目里，用 `.gitignore` 保护，"看得见摸得着"
- `.example` 模板文件降低新人上手成本

---

## 五、Redis 里最终的 key 全景

在 Memurai 中一览项目产生的所有 key：

```
# Plan A 产物
chat_history:chat              # Set，存 chat 类的所有 chatId
chat_history:service           # Set
chat_history:game              # Set
chat_history:pdf               # Set

# 阶段 1 产物：Spring Cache
demo_ai_2:cache:schools::all               # String（JSON），校区列表缓存
demo_ai_2:cache:courses::CourseQuery(...)  # String（JSON），课程查询缓存

# ChatMemory 产物
chat_memory:{chatId1}          # List，某会话的完整消息流
chat_memory:{chatId2}          # List
...
```

---

## 六、可复用的 Redis 使用套路

从本次任务提炼的通用模式：

### 模式 1：会话/身份列表 → Set
适用：用户 ID 集合、活跃会话 ID、点赞用户列表
```
SADD chat_history:chat "chat-001"
SMEMBERS chat_history:chat
```

### 模式 2：时间序列消息 → List
适用：聊天记录、日志流、事件队列
```
RPUSH chat_memory:001 '{"type":"user","text":"你好"}'
LRANGE chat_memory:001 -100 -1    # 取最近 100 条
```

### 模式 3：查询结果缓存 → String + Spring @Cacheable
适用：字典表、几乎不变的配置、DB 压力大的读接口
```java
@Cacheable(value="cacheName", key="#param")
public XXX method(Param param) { ... }
```

### 模式 4：多实现优先级 → @Primary
适用：一个接口有多种实现（内存版、Redis 版、DB 版）时的默认选择
```java
@Primary @Component class RedisImpl implements Foo { ... }
@Component class InMemoryImpl implements Foo { ... }  // 作为对照保留
```

### 模式 5：敏感配置 → Profile 分离
适用：任何有 API key/密码的项目
```
application.yaml            → git tracked，无敏感
application-local.yaml      → gitignored，含真实值
application-local.yaml.example → git tracked，占位模板
```

---

## 七、下一步（未来任务）

按你原定计划，A 完成后是 **C**（把 MySQL 三张表全搬到 Redis 作为学习作业）。C 阶段将涉及：

- Hash 存储对象（`HSET course:1 name "Java" price 12999`）
- 手工建立多条件索引 Set（`course:by_type:编程` → 存 courseId 集合）
- ZSet 做排序索引（`course:sort:price` 用 price 作 score）
- 用 `SINTER` 做多条件筛选交集
- 在缺乏事务保护下的一致性妥协

**C 阶段的核心学习价值**：**理解"为什么工程上一般不这么做"**，通过亲手写复杂代码来对比 SQL 一行搞定的优雅。

---

## 八、Git 提交记录

```
7a0e82d  feat(redis): persist ChatMemory to Redis, survive restart
b31b216  feat(redis): introduce Redis for chat history + course/school caching
3e7d473  feat(model): switch chat to Kimi (moonshot), keep DashScope for embedding
abfd022  refactor: harden security, concurrency and validation, keep API stable
```

远程：`https://github.com/njuptemmm/iheima_springboot3_realization`
