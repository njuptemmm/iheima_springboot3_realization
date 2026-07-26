# Anki 卡组导入指南

## 我为什么用 TXT 而不是 .apkg？

原本你希望在 GitHub 上找**现成的 .apkg**（Anki 二进制导出包）直接导入。

但：
1. 我在这个环境**无法访问 GitHub 搜索**（WebSearch 返回空、WebFetch 被安全策略挡）
2. 就算能搜到，通用的 Java 面试 anki 与你**这个具体项目**基本不沾边

**替代方案（当前这一版）**：TXT 制表符分隔卡组。
- Anki 桌面版原生支持导入
- 你能自己看内容、增删改
- 每张卡都**结合本项目实际代码**（比如"本项目 @Primary 用在哪里"），而不是空泛问题
- 未来可以基于这些 TXT 用第三方工具（如 `genanki` Python 库）编译成 .apkg 分发

## 5 个卡组文件

| 文件 | 卡片数 | 建议 Deck 名 | 优先级 |
|---|---|---|---|
| `01-project-specific.txt` | ~32 | `项目实战-iheima-springboot` | ⭐⭐⭐ 最高 |
| `02-redis.txt` | ~42 | `Redis-体系八股` | ⭐⭐⭐ |
| `03-spring.txt` | ~33 | `Spring-全家桶八股` | ⭐⭐ |
| `04-mybatis-mysql.txt` | ~30 | `MyBatis-MySQL-八股` | ⭐⭐ |
| `05-java-jvm-concurrent.txt` | ~48 | `Java-基础-JVM-并发` | ⭐⭐⭐ |
| `06-vector-rag-memory.txt` | ~27 | `向量数据库-RAG-长期记忆` | ⭐⭐⭐⭐ |

**总计约 212 张卡**。按每天新学 20 张 + 复习计算，12-15 天能全部过一遍第一轮。

## 导入步骤（Anki 桌面版）

**Anki 官方下载**：<https://apps.ankiweb.net/>（免费）

### 步骤 1：创建 Deck

`File` → 或直接在主界面点 `Create Deck`，输入名字（例如 `项目实战-iheima-springboot`）。

### 步骤 2：导入 TXT

1. `File` → `Import`
2. 选择要导入的 TXT 文件
3. 在导入对话框里设置：
   - **Type**：`Basic`（基础正反面卡片）
   - **Deck**：选你刚创建的 Deck
   - **Fields separated by**：`Tab`（**关键：必须是 Tab**）
   - 勾选 `Allow HTML in fields`（因为卡片背面有 `<br>` 换行）
   - `Field 1` → `Front`
   - `Field 2` → `Back`
4. 点 `Import`，会提示导入了多少张卡

### 步骤 3：重复 5 次

对 5 个 TXT 文件分别执行步骤 2，每次导入到不同的 Deck。

### 步骤 4：开始学习

主界面点你的 Deck → `Study Now`

## 常见问题

### Q1：导入后卡片显示成一整行没有换行？

A：没勾 `Allow HTML in fields`。重新导入并勾上。

### Q2：`Type: Basic` 找不到？

A：Anki 默认就有这个 Note Type。如果被删了，`Tools` → `Manage Note Types` → `Add` → `Add: Basic`。

### Q3：想在手机上学？

A：Anki 有免费移动端：
- Android：AnkiDroid（Google Play / F-Droid，免费）
- iOS：AnkiMobile（付费）
- 同步：AnkiWeb 账号（免费）

桌面导入后 `Sync` 到云端，手机端 `Sync` 拉下来即可。

### Q4：想改卡片内容？

A：Anki 里直接双击卡片编辑，或者改 TXT 后重新导入（会更新已有卡片，不重复）。

### Q5：某张卡背面太长？

A：Anki 里点 `Edit` 修改。或者改 TXT 里对应行，重新导入。

## 学习建议

### 频率控制
默认 Anki 会根据你答对/答错自动调节间隔。**别自己手动调**，相信算法。

### 每天时长
- 学习期（前 2 周）：每天 30-45 分钟
- 复习期（之后）：每天 15-20 分钟

### 优先顺序
1. **先过一遍 `01-project-specific.txt`**（32 张）
   - 这些是你亲手写过的代码，容易记
   - 建立"看到项目细节就能想到 Anki 卡"的关联
2. **然后 `02-redis.txt`**（Redis 是本次重点）
3. **接着 `06-vector-rag-memory.txt`**（RAG + 向量库 + 长期记忆，招聘 JD 加分项）
4. **然后 `05-java-jvm-concurrent.txt`**（面试最高频）
5. **最后 `03-spring.txt` + `04-mybatis-mysql.txt`**（补充深度）

### 卡片质量反馈
学习中如果发现某张卡：
- **答案错了**：直接改 TXT，重新导入
- **问得不好**：改问法
- **想拆细**：一张变两张
- **想合并**：删掉冗余的

**这份卡组是活的**，用起来才有价值。

## 未来扩展

想让卡组更全，可以做：

1. **补计算机网络**（TCP/HTTP/HTTPS）
2. **补设计模式**（单例、工厂、观察者，Spring 里大量用到）
3. **补分布式基础**（CAP、BASE、一致性协议）
4. **补 Go 语言专题**（招聘 JD 写 Go 背景优先）

需要我补充哪个专题，告诉我即可。

## 关于 .apkg 打包

如果你需要**打包成 .apkg**（比如给同学、发到 AnkiWeb 分享）：

**方案 A：Anki 桌面版导出**（最简单）
- 学习完的 Deck 上右键 → `Export Deck` → 选 `.apkg`

**方案 B：Python 脚本自动化**（多个 TXT 一键打包）
- 装 `pip install genanki`
- 写脚本读 TXT → 生成 `.apkg`
- 我可以帮你写这个脚本，需要就说

## 关于我在 GitHub 上找 anki

现有生态中，直接把这个项目的知识点做成 anki 的公开卡组几乎没有 —— 因为这是私人学习项目。GitHub 上找到的 anki 卡组多是：

1. **Anki 官方共享站**：<https://ankiweb.net/shared/decks/>（搜 "Java Interview"、"Redis" 有一些）
2. **awesome-anki 收集**：搜 `github.com/topics/anki-deck`
3. **CS 教材配套 deck**：如"Fluent Forever"社区

但这些卡组都是通用内容，**没有针对你这个项目的**。我给的 `01-project-specific.txt` 是这类需求的唯一合理答案 —— **必须自己造**。
