# demo-ai-1（Spring Boot 3 + Spring AI 实战：聊天 / 客服工具调用 / PDF 向量问答）

这是一个基于 **Spring Boot 3.5.x** 与 **Spring AI (1.0.0-M6)** 的示例项目，完成了多种 AI 对话场景：

- 普通聊天（支持文本 + 多文件多模态）
- 恋爱“小游戏”对话（纯提示词驱动）
- 课程咨询“智能客服”（**工具调用** + MySQL）
- PDF 上传 / 下载 + 向量检索问答（RAG）
- 会话列表与历史消息查询（按 `chatId`）

> 项目当前无登录鉴权；全局 CORS 放开，适合本地前后端联调与学习验证。

---

## 运行环境

- **JDK**: 17（`pom.xml` 指定）
- **Maven**: 3.8+（建议）
- **MySQL**: 8.x（默认连接 `localhost:3306/itheima`）
- **Redis**: 6.x+（推荐 Windows 用户使用 [Memurai Developer](https://www.memurai.com/get-memurai)，Redis 完全兼容；Linux/macOS 用官方 Redis 或 Docker）
- **大模型与向量模型**: 通过 Spring AI OpenAI 协议兼容接入（当前默认对话走 Kimi，Embedding 走阿里云 DashScope）

---

## 关键依赖与能力清单

来自 `pom.xml` 的核心依赖：

- **Web**: `spring-boot-starter-web`
- **流式输出**: `spring-boot-starter-webflux`（Controller 返回 `Flux<String>`）
- **Spring AI**:
  - `spring-ai-openai-spring-boot-starter`
  - `spring-ai-pdf-document-reader`
- **数据库**:
  - `mysql-connector-j`
  - `mybatis-plus-spring-boot3-starter`
- **Lombok**: 简化实体与日志代码

---

## 配置说明（`application.yaml`）

文件位置：`src/main/resources/application.yaml`

### 1) 大模型 / Embedding 配置

项目使用 Spring AI 的 OpenAI 兼容配置，默认指向 DashScope：

- `spring.ai.openai.base-url`: `https://dashscope.aliyuncs.com/compatible-mode`
- `spring.ai.openai.api-key`: `${API_KEY}`（从环境变量读取）
- Chat model: `qwen-omni-turbo`
- Embedding model: `text-embedding-v3`，维度 `1024`

你需要在系统环境变量里设置：

- **Windows PowerShell（当前会话）**：

```bash
$env:API_KEY="你的key"
```

- **Windows（永久）**：系统环境变量里新增 `API_KEY`

> 注意：`CommonConfiguration` 里也显式指定了 chat 模型为 `qwen-omni-turbo`（与 YAML 保持一致）。

### 2) 文件上传大小

- `spring.servlet.multipart.max-file-size`: `20MB`
- `spring.servlet.multipart.max-request-size`: `20MB`

### 3) MySQL 数据源

默认连接：

- `jdbc:mysql://localhost:3306/itheima`
- 用户名：`root`
- 密码：`123456`

如需修改，直接编辑 `application.yaml` 中的 `spring.datasource.*`。

### 4) Redis（新增）

用于持久化会话历史（`InMemoryChatHistoryRepository` → `RedisChatHistoryRepository`）和缓存 `course`/`school` 查询结果。

- `spring.data.redis.host`: `localhost`
- `spring.data.redis.port`: `6379`
- `spring.cache.type`: `redis`
- `spring.cache.redis.time-to-live`: `600000`（缓存 TTL 10 分钟）
- `spring.cache.redis.key-prefix`: `demo_ai_2:cache:`

#### Windows 下用 Memurai 启动 Redis

1. 到 [Memurai 官网](https://www.memurai.com/get-memurai) 下载 Memurai Developer（免费）并安装
2. 安装完成后 Memurai 会作为 Windows 服务自动启动，监听 `localhost:6379`
3. 验证连接：

```powershell
# 服务状态
Get-Service Memurai
# 应返回：Status Running

# Ping 测试（如果 memurai-cli 不在 PATH，用绝对路径，例如 D:\Memurai\memurai-cli.exe）
memurai-cli ping
# 应返回：PONG
```

> **注意**：项目启动依赖 Redis 可连接，Memurai 未启动时应用会启动失败并报 `Unable to connect to Redis`。

---

## 数据库表结构（最小可用）

项目的客服工具调用（`CourseTools`）依赖三张表：

- `course`（课程/学科表）
- `school`（校区表）
- `course_reservation`（预约表）

对应实体：

- `com.example.demo.entity.po.Course`
- `com.example.demo.entity.po.School`
- `com.example.demo.entity.po.CourseReservation`

下面给出一份 **最小可运行** 的建表 SQL（字段与实体注解一致）：

```sql
CREATE DATABASE IF NOT EXISTS itheima DEFAULT CHARSET utf8mb4;
USE itheima;

DROP TABLE IF EXISTS course;
CREATE TABLE course (
  id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(255) NOT NULL,
  edu INT NOT NULL COMMENT '0-无，1-初中，2-高中，3-大专，4-本科以上',
  type VARCHAR(50) NOT NULL COMMENT '编程/设计/自媒体/其它',
  price BIGINT NOT NULL,
  duration INT NOT NULL COMMENT '学习时长(天)'
);

DROP TABLE IF EXISTS school;
CREATE TABLE school (
  id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(255) NOT NULL,
  city VARCHAR(100) NOT NULL
);

DROP TABLE IF EXISTS course_reservation;
CREATE TABLE course_reservation (
  id INT PRIMARY KEY AUTO_INCREMENT,
  course VARCHAR(255) NOT NULL,
  student_name VARCHAR(255) NOT NULL,
  contact_info VARCHAR(255) NOT NULL,
  school VARCHAR(255) NOT NULL,
  remark VARCHAR(500)
);
```

建议插入一些演示数据，便于客服工具查询：

```sql
INSERT INTO school(name, city) VALUES
('北京校区', '北京'),
('上海校区', '上海');

INSERT INTO course(name, edu, type, price, duration) VALUES
('Java后端开发', 2, '编程', 12999, 120),
('UI设计', 1, '设计', 8999, 90),
('短视频运营', 0, '自媒体', 6999, 60);
```

---

## 启动项目

在项目根目录执行：

```bash
mvn spring-boot:run
```

或打包运行：

```bash
mvn -DskipTests package
java -jar target/demo-ai-1-0.0.1-SNAPSHOT.jar
```

主启动类：`com.example.demo.DemoAi2Application`

---

## 核心设计：`chatId` + ChatMemory + Advisor

项目用 `chatId` 作为“会话标识”（由前端生成并传入）。

在 Controller 调用模型时都会设置：

- `CHAT_MEMORY_CONVERSATION_ID_KEY = chatId`

这样 `MessageChatMemoryAdvisor` 就能把每个会话的历史消息写入 `ChatMemory`，并在后续请求中自动拼接上下文。

在 `CommonConfiguration` 中默认启用了：

- `SimpleLoggerAdvisor`：打印请求/响应日志
- `MessageChatMemoryAdvisor(chatMemory)`：会话记忆（内存）
- `QuestionAnswerAdvisor(vectorStore, SearchRequest...)`：PDF 问答时的向量检索增强

---

## PDF 向量问答的本地持久化文件

`LocalPdfFileRepository` 会在**项目运行目录**生成/读取两个文件：

- `chat-pdf.properties`：保存 `chatId -> 文件名` 的映射
- `chat-pdf.json`：`SimpleVectorStore` 的向量数据持久化

> 这使得服务重启后仍能继续对之前上传的 PDF 做问答（前提是运行目录下这两个文件还在）。

---

## API 接口文档

所有接口均为示例接口，**无鉴权**。

### 1) 普通聊天 / 多模态聊天

- **路径**：`GET/POST /ai/chat`
- **参数**：
  - `prompt`：用户输入
  - `chatId`：会话 id
  - `files`：可选，多文件（用于多模态）
- **返回**：`text/html; charset=UTF-8` 的 **流式输出**（`Flux<String>`）

示例（纯文本）：

```bash
curl "http://localhost:8080/ai/chat?prompt=你好&chatId=chat-001"
```

示例（多文件，多模态；PowerShell 下建议用 `curl.exe`）：

```bash
curl.exe -F "prompt=请描述这些文件内容" -F "chatId=chat-002" -F "files=@a.png" -F "files=@b.pdf" http://localhost:8080/ai/chat
```

### 2) 智能客服（工具调用 + MySQL）

- **路径**：`GET/POST /ai/service`
- **参数**：`prompt`、`chatId`
- **返回**：流式输出

示例：

```bash
curl "http://localhost:8080/ai/service?prompt=我想报Java课，顺便推荐下北京校区&chatId=svc-001"
```

客服背后可调用 `CourseTools`：

- `queryCourses(CourseQuery)`：按 `type`/`edu`/排序查询课程
- `querySchool()`：查询所有校区
- `CreateCourseReservation(...)`：生成预约单并入库

### 3) 恋爱游戏对话

- **路径**：`GET/POST /ai/game`
- **参数**：`prompt`、`chatId`
- **返回**：流式输出

示例：

```bash
curl "http://localhost:8080/ai/game?prompt=我错了，别生气了&chatId=game-001"
```

### 4) PDF 上传 / 问答 / 下载

#### 4.1 上传 PDF

- **路径**：`POST /ai/pdf/upload/{chatId}`
- **表单字段**：`file`
- **返回**：JSON（`Result`）

示例：

```bash
curl.exe -F "file=@database_study.pdf;type=application/pdf" http://localhost:8080/ai/pdf/upload/pdf-001
```

成功返回：

```json
{"ok":1,"msg":"ok"}
```

#### 4.2 基于 PDF 问答（RAG）

- **路径**：`GET/POST /ai/pdf/chat`
- **参数**：`prompt`、`chatId`
- **返回**：流式输出
- **说明**：
  - 会先检查 `chatId` 是否已绑定 PDF 文件；
  - 会通过 `FILTER_EXPRESSION` 仅检索该 PDF 的向量内容；
  - 默认 `topK=2`（见 `CommonConfiguration.pdfChatClient`）

示例：

```bash
curl "http://localhost:8080/ai/pdf/chat?prompt=请总结这份PDF的核心内容&chatId=pdf-001"
```

#### 4.3 下载 PDF

- **路径**：`GET /ai/pdf/file/{chatId}`
- **返回**：文件下载（二进制）

示例：

```bash
curl -OJ http://localhost:8080/ai/pdf/file/pdf-001
```

### 5) 会话列表与历史消息

#### 5.1 获取某类业务的 chatId 列表

- **路径**：`GET /ai/history/{type}`
- **type**：`chat` / `service` / `pdf`

示例：

```bash
curl http://localhost:8080/ai/history/chat
```

> 注意：当前 `InMemoryChatHistoryRepository` 是内存存储，**服务重启后列表会丢失**。

#### 5.2 获取某个 chatId 的消息历史

- **路径**：`GET /ai/history/{type}/{chatId}`
- **返回**：`MessageVO[]`（`role` + `content`）

示例：

```bash
curl http://localhost:8080/ai/history/chat/chat-001
```

---

## 目录结构速览

```text
src/main/java/com/example/demo
  config/
    CommonConfiguration.java      # ChatClient/ChatMemory/VectorStore/Advisor 配置
    MvcConfiguration.java         # 全局 CORS 放开
  constants/
    SystemConstants.java          # 游戏/客服等长系统提示词
  controller/
    ChatController.java           # /ai/chat 文本+多模态聊天
    CustomerServiceController.java# /ai/service 智能客服（工具调用）
    GameController.java           # /ai/game 游戏对话
    PdfController.java            # /ai/pdf 上传/下载/问答
    ChatHistoryController.java    # /ai/history 会话列表与历史消息
  repository/
    ChatHistoryRepository.java    # 会话列表抽象
    InMemoryChatHistoryRepository.java # 会话列表内存实现
    FileRepository.java           # 文件仓库抽象
    LocalPdfFileRepository.java   # PDF 本地存储 + 映射 + 向量库持久化
  Tools/
    CourseTools.java              # AI 工具调用：课程/校区/预约
  entity/
    po/                           # MyBatis-Plus 实体：course/school/course_reservation
    query/                        # CourseQuery：工具参数对象
    vo/                           # Result/MessageVO：接口返回 VO
```

---

## 常见问题（FAQ）

### 1) 启动后调用接口报 401/403？

本项目默认没有 Spring Security；如果你遇到 401/403，通常是你本地环境或网关做了拦截，请确认直接访问的是应用端口。

### 2) 调用模型报鉴权/额度/网络错误

请检查：

- 是否已正确设置环境变量 `API_KEY`
- `application.yaml` 中 `spring.ai.openai.base-url` 是否可访问
- 网络是否能连通对应服务

### 3) PDF 问答提示“会话文件不存在”

说明该 `chatId` 没有上传过 PDF，或运行目录下的 `chat-pdf.properties` 丢失/被清空。

先执行上传：

```bash
curl.exe -F "file=@xxx.pdf;type=application/pdf" http://localhost:8080/ai/pdf/upload/pdf-001
```

再问答：

```bash
curl "http://localhost:8080/ai/pdf/chat?prompt=...&chatId=pdf-001"
```

### 4) 会话列表重启后没了？

会话列表目前由 `InMemoryChatHistoryRepository` 存内存，重启自然丢失。PDF 的映射与向量库则由 `LocalPdfFileRepository` 持久化到本地文件。

---

## 许可证

本项目为学习示例工程，可按你的团队规范补充 LICENSE。

---

## 参考与关联项目

- **课程来源**：黑马 Spring AI 实战课程（B 站）  
  - 链接：`https://www.bilibili.com/video/BV14z4y1N7pg/`
- **前端项目**：本仓库为后端部分；如果你有配套的 Vue3 前端仓库（例如 `iheima_vue3`），请先启动前端再启动本服务进行联调。
