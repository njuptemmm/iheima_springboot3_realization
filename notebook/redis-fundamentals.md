# Redis 体系知识学习笔记

> 配套阅读：[redis-upgrade-summary.md](./redis-upgrade-summary.md)（项目实战篇）
>
> 定位区别：
> - 实战篇 = 「我们在这个项目做了什么、怎么做的」
> - 本篇 = 「Redis 本身应该怎么理解、面试和进阶要点」
>
> 全篇尽量结合本项目 `iheima_springboot3_realization` 的实际场景，避免脱离项目的空泛描述。

---

## 目录

- [一、数据结构底层实现](#一数据结构底层实现)
- [二、持久化：RDB 与 AOF](#二持久化rdb-与-aof)
- [三、高可用与集群方案](#三高可用与集群方案)
- [四、缓存三大问题：穿透 / 击穿 / 雪崩](#四缓存三大问题穿透--击穿--雪崩)
- [五、分布式锁](#五分布式锁)
- [六、本项目实际使用了什么、没使用什么](#六本项目实际使用了什么没使用什么)

---

## 一、数据结构底层实现

Redis 对外暴露的类型（String / List / Hash / Set / ZSet）都是**逻辑类型**，底层根据数据量和内容自动选择不同**编码方式**（encoding）。理解底层实现能帮你在使用时避免踩坑。

### 1.1 String —— SDS（简单动态字符串）

Redis 不用 C 的原生字符串，而是自己造了个 SDS：

```
struct sdshdr {
    int len;      // 已使用长度
    int free;     // 未使用空间
    char buf[];   // 字符数组
}
```

**为什么不用 C 原生字符串**：
- C 字符串必须遍历才知道长度，SDS 用 `len` O(1) 拿到
- C 字符串靠 `\0` 结尾，无法存二进制；SDS 用长度界定，可以存任意字节
- SDS 有**空间预分配**（避免每次 append 都 realloc），末尾有**惰性释放**（短时缩短不立刻回收）

**在项目里**：`chat_history:{type}` 的成员、`chat_memory:{chatId}` 的每条 JSON、`demo_ai_2:cache:*` 的缓存 JSON，都存在 SDS 里。中文按 UTF-8 存原始字节，`memurai-cli` 看到的 `\xe8\xaf\xb7` 就是 SDS 的字节视图（"请"字的 UTF-8）。

### 1.2 List —— QuickList = LinkedList + ZipList

- **早期（Redis 3.2 前）**：小列表用 ZipList（连续内存块），大列表用双端链表
- **现在**：QuickList = 双端链表节点里再嵌 ZipList
- 好处：既保留链表 O(1) 首尾插入，又用 ZipList 减少每个元素独立对象的内存开销

**触发 ZipList → 普通链表的阈值**（可配置）：
- `list-max-listpack-size`：单节点最大元素数或字节数
- 超过阈值 QuickList 节点从 ZipList 升级为普通链表

**在项目里**：`chat_memory:{chatId}` 用 `RPUSH` 追加、`LRANGE -N -1` 取尾部。会话对话量小时是 ZipList（省内存），聊长了自动升级 QuickList。**我们不需要手动介入**，Redis 自己处理。

### 1.3 Hash —— ZipList / Hashtable

- 元素少（字段少、值短）时用 ZipList，紧凑连续
- 超过阈值（`hash-max-listpack-entries` / `hash-max-listpack-value`）自动升级 Hashtable（拉链法散列表）

**为什么懂这个有用**：如果你存的 Hash 每个字段都超过 64 字节，Redis 早早升级 Hashtable，内存开销就上去了。存对象前评估字段大小是有意义的。

**在项目里没用 Hash**。假如 C 阶段要把 `course` 表存 Redis，会用 `HSET course:1 name Java price 12999`，那时才涉及。

### 1.4 Set —— IntSet / Hashtable

- 全是整数且数量少时用 IntSet（有序数组，二分查找）
- 有非整数元素、或数量超过 `set-max-intset-entries`（默认 512）时升级 Hashtable

**在项目里**：`chat_history:{type}` 存的是 chatId（长字符串时间戳），从一开始就是 Hashtable 编码，不会是 IntSet。

### 1.5 ZSet —— ZipList / SkipList + Hashtable

- 元素少时用 ZipList
- 元素多时用**跳表 + Hashtable 组合**：
  - **跳表**：按 score 排序，支持范围查询 O(logN)
  - **Hashtable**：按 member 查 score O(1)
  - 两个结构共享数据，各自服务不同查询

**跳表**：一种概率性数据结构，用多层链表模拟二叉搜索，实现比红黑树简单但性能相近。Redis 选它是因为**代码简单、易于范围查询**。

**在项目里没用 ZSet**。假如 C 阶段要给课程按 price 排序，会用 `ZADD course:sort:price 12999 course:1`。

### 1.6 表格总结

| 逻辑类型 | 底层编码 | 触发升级的条件 |
|---|---|---|
| String | int / embstr / raw | 数字 / 短字符串 / 长字符串 |
| List | ZipList → QuickList | 元素数或大小超阈值 |
| Hash | ZipList → Hashtable | 字段数或值大小超阈值 |
| Set | IntSet → Hashtable | 出现非整数 或 数量超阈值 |
| ZSet | ZipList → SkipList+Hashtable | 元素数或大小超阈值 |

**看某 key 的底层编码**：
```
OBJECT ENCODING mykey
```

**在项目里试试**（能加深理解）：
```
> OBJECT ENCODING chat_history:chat
> OBJECT ENCODING chat_memory:1784799376653
> OBJECT ENCODING demo_ai_2:cache:schools::all
```

---

## 二、持久化：RDB 与 AOF

Redis 是内存数据库，如果不做持久化，进程死了数据就没了。项目里 chat_history / chat_memory 存 Redis，如果 Memurai 宕机重启数据丢了，我们就白干了。所以持久化必须理解。

### 2.1 RDB（Redis Database）—— 快照

**原理**：每隔一段时间把内存里的**全部数据**打包写成一个二进制文件 `dump.rdb`。

**触发方式**：
- 配置自动触发：`save 900 1` = 900 秒内至少 1 次写就触发
- 手动触发：`SAVE`（阻塞主线程，几乎不用）、`BGSAVE`（fork 子进程后台写）

**优点**：
- 文件小、加载快（重启恢复快）
- 对性能影响小（fork 子进程写盘）

**缺点**：
- **可能丢数据**：两次快照之间的写操作，宕机就没了
- fork 子进程会短暂阻塞主线程（大数据集下明显）

**举例**：Memurai 默认配 `save 3600 1 300 100 60 10000`，即：
- 3600 秒内 ≥1 次写触发
- 300 秒内 ≥100 次写触发
- 60 秒内 ≥10000 次写触发

**在项目里**：如果依赖默认 RDB，你在猫娘聊天里发一条消息后立刻宕机（5 分钟内没到触发条件），这条消息就丢了。学习项目里问题不大，生产环境必须叠加 AOF。

### 2.2 AOF（Append Only File）—— 追加日志

**原理**：**每次写操作**都追加到日志文件（`RPUSH`、`SADD`、`SET` 都记）。重启时**重放日志**恢复数据。

**同步策略**（`appendfsync` 配置）：
| 策略 | 说明 | 丢数据风险 | 性能 |
|---|---|---|---|
| `always` | 每次写都 fsync 落盘 | 几乎不丢 | 差 |
| `everysec` | 每秒 fsync 一次 | 最多丢 1 秒 | 中（推荐）|
| `no` | 交给操作系统决定 | 最多丢 30 秒 | 最好 |

**优点**：
- 数据安全性高
- 日志可读（是 Redis 命令序列）

**缺点**：
- 文件比 RDB 大（因为记每一步）
- 恢复慢（要重放所有命令）
- 需要"重写"（AOF rewrite）压缩日志

**AOF 重写**：过一段时间 AOF 文件里可能有大量冗余（比如同一个 key 被 SET 了 100 次），Redis 会 fork 子进程根据当前内存状态**重新生成**精简版 AOF。

### 2.3 混合持久化（Redis 4.0+）—— 目前主流

RDB 加载快、AOF 数据全，那就组合起来：**AOF 文件的开头是 RDB 快照，后面追加 AOF 增量日志**。重启时先加载 RDB 部分（快），再重放少量增量 AOF（近全）。

**开启**：`aof-use-rdb-preamble yes`（默认开启）

### 2.4 项目里应该怎么选

**当前状态**：Memurai 默认配置，主要是 RDB。丢数据的窗口可能到几分钟。

**学习项目建议**：不改。默认能用就够了。

**如果这个项目要上生产**（假设有真实用户在使用会话记忆）：
- 至少开启 AOF `everysec` 模式
- 保留 RDB 快照做备份（万一 AOF 坏了）
- 混合持久化开着

**查看当前 Memurai 持久化配置**：
```
> CONFIG GET save
> CONFIG GET appendonly
> CONFIG GET appendfsync
```

---

## 三、高可用与集群方案

单机 Redis 挂了业务就死了。生产环境必须有高可用方案。三种主流架构，复杂度和能力依次递增。

### 3.1 主从复制（Replication）—— 基础

**结构**：1 主 + N 从
- 主库负责读写
- 从库从主库同步数据，只处理读

**同步机制**：
- 全量同步：从库首次连接 → 主库生成 RDB 发过去 → 从库加载
- 增量同步：主库把新的写命令持续推给从库

**能解决什么**：
- ✅ 读性能扩展（读从库）
- ❌ 高可用（主库挂了要人工切换）
- ❌ 数据安全（主库单点故障）

**在项目里**：单机 Memurai，没有主从。学习阶段够用。

### 3.2 哨兵（Sentinel）—— 自动故障转移

**结构**：主从 + 3 个以上 Sentinel 节点监控

**Sentinel 做什么**：
- 持续 ping 主/从库
- 主库挂了 → Sentinel 投票选举一个从库升级为新主
- 通知客户端连新主

**优势**：
- ✅ 自动故障转移
- ✅ 客户端不需要感知（Sentinel 通知它连新主）

**局限**：
- ❌ 数据总量仍限于单机内存（一个主库）
- ❌ 写性能仍限于单机

### 3.3 Cluster —— 分片集群

**结构**：多个"主从组"，数据分散到 16384 个哈希槽（slot）里

**分片规则**：
```
slot = CRC16(key) % 16384
每个主库负责若干 slot
```

**能力**：
- ✅ 数据总量 = 所有主库内存之和（水平扩展）
- ✅ 写性能水平扩展
- ✅ 自动故障转移（每个主库有从库备用）

**代价**：
- 多 key 操作受限（`SINTER key1 key2` 要求两个 key 在同一 slot）
- Redis 提供 `hash tag`：`{group}user:1` 和 `{group}user:2` 会落到同一 slot

**在项目里**：如果 C 阶段真的要把 MySQL 三张表全搬 Redis，`SINTER course:by_type:编程 course:by_edu:2` 这种交集操作，在 Cluster 下必须让两个 key 落到同一 slot，就需要 hash tag：`{course-idx}by_type:编程`、`{course-idx}by_edu:2`。

### 3.4 选型建议

| 场景 | 推荐 |
|---|---|
| 学习 / 个人项目 | 单机（本项目就是）|
| 小型生产（数据 < 20GB，QPS 中等）| 主从 + Sentinel |
| 大规模（数据 >20GB 或需水平扩展）| Cluster |

---

## 四、缓存三大问题：穿透 / 击穿 / 雪崩

缓存本意是保护数据库，但如果用得不对反而会**放大问题**。三个经典陷阱要理解。

### 4.1 缓存穿透

**定义**：查询一个**根本不存在**的数据，缓存不命中，请求全打到 DB。攻击者可以用不存在的 key 循环发请求，把 DB 打爆。

**举例**：`GET /course/999999`，course 表没有 id=999999 的记录。缓存里也没有。每次请求都查一次 DB。

**解法 1：缓存空值**
- 查 DB 得到空，也把"空"缓存起来（TTL 短，比如 1 分钟）
- 下次同样的请求就命中缓存的"空"，不查 DB
- **代价**：占内存

**解法 2：布隆过滤器**
- 用一个位数组预先"标记"所有存在的 key
- 请求先过布隆过滤器，说"不存在"就直接返回
- **代价**：内存占用（但比缓存空值省）、有极小误判率

**在项目里**：`application.yaml` 里配了 `cache-null-values: false` **反倒关闭了缓存空值**。理由是：
- 本项目 course/school 查询由 AI 触发，AI 不会像攻击者那样构造大量不存在的 key
- 缓存了 null 反而占空间

**如果有对外暴露的 REST 接口按 id 查课程**（未来 C 阶段可能有），就需要考虑穿透防护，那时把 `cache-null-values: true` 打开。

### 4.2 缓存击穿

**定义**：某个**热点 key** 突然过期，大量并发请求同时打到 DB。DB 一瞬间压力暴增。

**举例**：假设"首页推荐课程"缓存 TTL 到期的瞬间，1000 个用户同时刷新页面 → 1000 次同样的 DB 查询。

**解法 1：互斥锁（Mutex）**
- 缓存不命中时，只让一个线程去查 DB
- 其他线程等着，等第一个线程写完缓存后共享结果
- 用 Redis `SETNX` 实现

**解法 2：热点 key 永不过期 + 后台异步更新**
- 缓存不设 TTL
- 后台定时任务定期刷新

**在项目里**：本项目**没有真正的热点 key**。course/school 是 AI 工具调用，QPS 极低（一个用户一次对话触发一次），不存在"1000 请求同时打过来"的场景。所以没做击穿防护。

### 4.3 缓存雪崩

**定义**：**大量 key 同时过期**，或**缓存服务整体宕机**，请求全打到 DB。

**场景 1：大批 key 同时过期**
- 你启动服务时批量预热了缓存，全设了同样的 TTL 10 分钟
- 10 分钟后同一秒所有缓存过期，DB 瞬间被打爆

**解法**：TTL 加随机抖动。10 分钟 → 9~11 分钟之间随机

**场景 2：Redis 集群整体宕机**
- 缓存层完全不可用，所有请求打 DB

**解法**：
- 集群化部署（主从 / Sentinel / Cluster）
- 熔断降级（缓存不可用时直接给用户"稍后重试"，别打 DB）

**在项目里**：Spring Cache 用全局固定 TTL 600000ms，理论上有雪崩风险。但因为 QPS 极低，没做防护。生产环境会用 `RedisCacheConfiguration` 给不同 cacheName 设不同 TTL，或用自定义 `CacheManager` 加随机抖动。

### 4.4 三者对比

| 问题 | 起因 | 影响 | 项目里的风险 |
|---|---|---|---|
| 穿透 | key 从来就不存在 | 每次都查 DB | 低（AI 不构造恶意请求）|
| 击穿 | 热点 key 突然过期 | 瞬时 DB 压力 | 低（无高并发）|
| 雪崩 | 大量 key 同时过期 或 缓存挂 | DB 被打爆 | 中低 |

---

## 五、分布式锁

**为什么需要**：单机 `synchronized` 或 `ReentrantLock` 只在同一 JVM 里生效。分布式部署下（多个 Spring Boot 实例），需要跨进程的锁。

### 5.1 SETNX 方案（最简单，不推荐生产用）

```
SET lock:order:001 <uuid> NX EX 30
```
- `NX`：不存在时才设置
- `EX 30`：30 秒后自动过期（防止持有锁的进程死亡后死锁）

**释放**：`DEL lock:order:001`
- **正确姿势**：先检查 value 是自己的 uuid 再删（防止误删别人的锁），用 Lua 保证原子性

**问题**：
- 锁自动过期时业务还没做完 → 别人拿到锁，你以为自己还持有 → 数据竞争
- 主从架构下：主库设锁成功但还没同步到从库时主库挂了，从库升级为主但没这把锁 → 别人也能拿到锁

### 5.2 RedLock（Redis 官方推荐）

**原理**：向 N 个独立的 Redis 实例申请锁，超过半数成功才算加锁成功。

**优点**：容忍少数 Redis 节点挂
**缺点**：实现复杂、性能有开销、争议不小（Martin Kleppmann 写过质疑文章）

### 5.3 Redisson —— 最实用

Java 生态的 Redis 客户端库，封装了：
- **可重入锁**（RLock）
- **看门狗自动续期**（业务没做完，锁自动延期）
- **公平锁 / 读写锁 / 信号量**
- **RedLock**

**典型用法**：
```java
RLock lock = redissonClient.getLock("myLock");
lock.lock();
try {
    // 业务
} finally {
    lock.unlock();
}
```

### 5.4 在项目里没做的原因

**分布式锁的核心用途**：防止多进程/多线程对同一资源的并发争抢。项目里没有这种场景 ——

- `course_reservation` 表虽然有插入，但没有"同一秒 1000 人抢同一门课"的秒杀场景
- Redis 缓存的读写是自动串行的（Redis 单线程模型）
- ChatMemory 每个会话独立 chatId，不同 chatId 不冲突，同一 chatId 也不会并发（对话本身是串行的）

**什么时候会需要**：
- 未来做真实的"课程报名秒杀"、限时抢购
- 定时任务在多实例部署时，需要选举一个实例执行（分布式定时任务）
- 涉及扣库存、扣积分等"读改写"操作时

---

## 六、本项目实际使用了什么、没使用什么

用一张表对照，帮你看清"体系知识"和"项目实际"之间的关系：

| 知识点 | 项目里用到 | 说明 |
|---|---|---|
| String | ✅ | Spring Cache 存 JSON |
| List | ✅ | `chat_memory:{chatId}` |
| Set | ✅ | `chat_history:{type}` |
| Hash | ❌ | C 阶段会用 |
| ZSet | ❌ | C 阶段可能用（排序索引）|
| RDB | ✅（默认）| 没主动配 |
| AOF | ❌ | 学习项目没开启 |
| 主从 | ❌ | 单机 |
| 哨兵 | ❌ | 单机 |
| Cluster | ❌ | 单机 |
| 缓存穿透防护 | ⚠️ 关闭空值缓存 | 项目场景不需要 |
| 缓存击穿防护 | ❌ | 无热点 key |
| 缓存雪崩防护 | ❌ | 无高并发 |
| 分布式锁 | ❌ | 单实例无争抢 |

**结论**：项目直接用到的 Redis 知识**很基础**（3 种数据结构 + Spring Cache 抽象）。这份体系笔记里其他 80% 的内容，是**你以后面试和做真实项目时会用到**的储备。

---

## 七、推荐进一步学习路径

按学习深度推荐，从浅到深：

### 阶段 1：动手熟练（你现在的位置）
- ✅ 5 种数据结构的 API
- ✅ Spring Data Redis + Spring Cache 集成
- ✅ 序列化策略

### 阶段 2：性能与运维（1-2 周）
- CONFIG 参数调优
- 慢查询日志（`SLOWLOG`）
- 内存分析（`INFO memory`、`redis-cli --bigkeys`）
- 持久化实操：开 AOF 观察文件，模拟宕机重启

### 阶段 3：高可用（2-3 周）
- 部署主从
- 配置 Sentinel
- 尝试 Cluster（可用 Docker Compose 起 6 节点）

### 阶段 4：源码 / 底层（长线学习）
- 读 Redis 源码理解 SDS / QuickList / SkipList 实现
- 网络模型（epoll + 单线程）
- 6.0 引入的多线程 I/O 是怎么回事

### 阶段 5：设计模式与实战
- 布隆过滤器（用 `BITMAP` 或 `RedisBloom` 模块）
- HyperLogLog（UV 统计）
- GEO 命令（地理位置）
- Stream 数据结构（Redis 5.0+，替代消息队列）

---

## 八、推荐资源

**书**：
- 《Redis 设计与实现》黄健宏 —— 讲底层数据结构，中文最好的一本
- 《Redis 深度历险：核心原理与应用实践》钱文品 —— 补集群/分布式锁部分

**在线**：
- 官方文档：<https://redis.io/docs/>
- Redis 命令参考：<https://redis.io/commands/>
- Spring Data Redis 官方指南：<https://docs.spring.io/spring-data/redis/reference/>

**互动**：
- Try Redis：<https://try.redis.io/>（浏览器里练命令）

**面试准备**：
- Martin Kleppmann《How to do distributed locking》—— 对 RedLock 的经典质疑，读了才算真理解分布式锁
- 阿里 P7 系列 Redis 面试题（各技术公众号都有）

---

## 九、总结：怎么把这份笔记用起来

**别当教程通读**。这份笔记的用法：

1. **做项目遇到不懂的**（比如 C 阶段开始用 Hash 存 course），先来这里找对应节，看代码里怎么落地
2. **面试前速览**：一节一节过，看到不熟的就深挖那节的推荐资源
3. **踩坑对照**：线上出问题（比如缓存击穿）时回来查对应节，看解法

一次性记住所有细节不现实。**知识点先建索引，用到时能定位到**，才是这份笔记的价值。
