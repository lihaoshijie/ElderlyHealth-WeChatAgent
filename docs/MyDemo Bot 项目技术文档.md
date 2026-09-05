> 更新日期：2026-08-01

## 目录
1. [项目概述](#一项目概述)
2. [技术栈清单](#二技术栈清单)
3. [项目代码目录结构](#三项目代码目录结构)
4. [核心架构设计](#四核心架构设计)  
 4.1 Agent ReAct 循环（消息主流程）  
 4.2 Skill 按需规则系统  
 4.3 RAG 检索增强管线  
 4.4 MCP 外部工具集成方案  
 4.5 工具注册与执行机制  
 4.6 ThreadLocal 事件队列（ToolExecutionContext）  
 4.7 对话历史持久化管理  
 4.8 B站收藏夹集成模块  
 4.9 统一异常路由处理  
 4.10 配置管理体系
5. [工具总览](#五工具总览)
6. [开发接入指南](#六开发接入指南)
7. [历史问题与解决方案](#七历史问题与解决方案)

---

## 一、项目概述
基于 **Spring Boot + MyBatis + LangChain4j**，对接微信 iLink SDK 实现微信智能助手 Bot。  
核心设计特点：**全部能力基于 LLM Function Calling + @Tool 注解驱动，零硬编码关键词匹配**。  
能力范围：60+内置本地工具 + 12306 MCP外部工具 + B站收藏夹能力集成。

## 二、技术栈清单
| 分层 | 技术组件 | 版本 & 说明 |
| --- | --- | --- |
| 基础框架 | Spring Boot + MyBatis | Spring Boot 4.0.7，Java 21 |
| 微信接入 | wechat-ilink-sdk-java | 2.3.3 |
| LLM SDK | langchain4j-open-ai | 0.36.2（OpenAiChatModel + Tokenizer） |
| 大模型底座 | DashScope | qwen3.6-flash（OpenAI兼容接口） |
| 多模态识图 | DashScope MultiModalConversation | qwen-vl-max |
| 文生图 | DashScope ImageGeneration | wan2.6-t2i |
| 语音合成 | DashScope TTS | cosyvoice-v3-flash |
| 向量嵌入 | DashScope TextEmbedding V3 | 用于RAG检索 |
| 数据库 | MySQL 8.0 | MyBatis + HikariCP |
| 对话持久化 | JdbcTemplate | ChatService 对话存储 |
| 文件解析 | Apache POI + PDFBox | POI 5.3.0、PDFBox 3.0.3 |
| MCP协议 | JSON-RPC 2.0 | stdio模式对接12306 |
| 二维码生成 | ZXing | 3.5.3，纯Java实现 |
| JSON序列化 | Jackson + Gson | Jackson 2.21、Gson 2.10 |


## 三、项目代码目录结构
```plain
src/main/java/com/Myself/demo/
├── bot/                              # 消息处理核心模块
│   ├── WeChatBotService.java         # 微信消息入口、消息发送（线程池16）
│   ├── CommandRouter.java            # 消息路由主逻辑：域过滤→LLM调度→工具执行→ReAct循环→上下文截断
│   ├── ToolRegistry.java             # 工具注册中心：@Tool扫描、域过滤、外部MCP工具管理
│   ├── ToolExecutionContext.java     # ThreadLocal 事件队列，处理工具副作用
│   ├── BotManager/Instance.java      # 多机器人实例管理
│   ├── SessionManager.java           # 微信登录会话持久化
│   ├── McpIntegration.java           # 12306 MCP stdio 集成实现
│   ├── WeChatClientRegistry.java     # 微信客户端实例注册管理
│   └── skill/                        # 13套Skill行为规则（按需动态注入）
│
├── tool/                             # 本地@Tool工具集合（60+工具）
│   ├── TravelTool.java               # 景点搜索、旅游攻略生成
│   ├── BiliFavTool.java              # B站收藏夹：扫码登录、列表查询、字幕获取
│   ├── IpLocationTool.java           # IP位置解析
│   ├── TicketBuyLinkTool.java        # 火车票购票链接
│   ├── FliggyTravelTool.java         # 飞猪旅行搜索
│   ├── HealthManagementTool.java     # 健康管理（16个子工具）
│   └── ...（剩余30+工具类）
│
├── service/                          # 通用业务服务层
│   ├── LlmService.java               # LangChain4j封装：对话、流式输出、Token估算
│   ├── ChatService.java              # 对话历史持久化、上下文压缩、异步落库
│   ├── RagService.java               # 旧版RAG检索管线
│   ├── RagPipelineService.java       # 新版RAG管线（待接入主路由）
│   ├── RagQueryRewriter.java         # LLM查询改写（备用）
│   ├── EmbeddingService.java         # 文本向量化、相似度计算
│   ├── AmapService.java              # 高德地图接口封装（地理编码、POI、距离计算）
│   ├── TravelPlanService.java        # 旅游规划（FlyAI子进程调用）
│   └── ...（剩余30+业务服务）
│
├── controller/                       # HTTP REST接口
│   ├── ConfigCheckController.java    # API Key配置状态检查接口
│   └── ...
├── entity/ mapper/ exception/        # 数据实体、MyBatis映射、全局异常
└── util/                             # 通用工具类
```

## 四、核心架构设计
### 4.1 Agent ReAct 循环（消息主流程）
入口：`CommandRouter.route()`，整个Bot智能调度核心循环。  
**完整流程示例**

```plain
用户消息 → 明天杭州天气怎么样
    │
    ▼
1. ToolRegistry.filterToolsByInput()
   根据输入域过滤工具，仅加载关联工具（示例：6个，而非全部82个）
    │
    ▼
2. LlmService.chatRaw(用户消息 + 过滤后工具列表)
   LLM决策：调用weather、local_time，返回function_call
    │
    ▼
3. 工具并发执行
   • 单工具：同步执行
   • ≥2个工具：CompletableFuture并行执行（耗时优化：5s → 2s）
    │
    ▼
4. 工具结果回填消息列表，超长内容截断保护
    │
    ▼
5. LLM结合工具返回结果，生成自然语言回复
```

**循环保护机制**

| 机制 | 参数/实现 | 作用 |
| --- | --- | --- |
| 动态工具分域 | filterToolsByInput | 闲聊场景仅加载少量通用工具，降低Token消耗 |
| Token预算保护 | TOKEN_BUDGET=100K | 上下文超限自动摘要，防止上下文爆炸 |
| 全局超时 | TOTAL_TIMEOUT_MS=90s | 避免无限循环卡死 |
| 工具结果截断 | 最大10000字符 | 字幕、长文本不撑爆对话上下文 |
| 工具调用去重 | calledKeys集合 | 相同工具+相同参数禁止重复调用 |
| 最大执行轮次 | MAX_TOOL_ROUNDS=3 | 强制终止循环，杜绝死循环 |
| 降级重试 | autoDegrade() | 搜索引擎多线路自动切换 |


### 4.2 Skill 按需规则系统
Skill 是**工具触发后动态注入**的行为约束，区别于全局固定System Prompt。

> 优势：仅在对应工具启用时加载，节省Token；支持按工具绑定不同业务规则。
>

**执行流程**

```plain
CommandRouter 执行工具调用
    │
    ▼
skillManager.getSkillsFor(工具名称) → 获取绑定Skill
    │
    ▼
未激活 → 加入activeSkills
    │
    ▼
下一轮LLM请求 → injectSkills()，将规则注入SystemPrompt
```

现有13个Skill：搜索、天气、旅游、图片、文件、热搜、健康饮食、待办提醒、语音、生日命理、快递、记忆。

**新增Skill标准模板**

```java
@Component
public class YourSkill implements Skill {
    @Override
    public String getName() { return "技能名称"; }
    @Override
    public String getRule() { return "行为约束规则文本"; }
    @Override
    public List<String> getTools() { return List.of("绑定工具名称"); }
}
```

**Skill 与全局System Prompt对比**

| 维度 | System Prompt | Skill |
| --- | --- | --- |
| 注入时机 | 每次LLM请求永久携带 | 工具触发后才注入 |
| Token开销 | 持续占用 | 按需加载，闲置无开销 |
| 使用场景 | 全局基础行为规范 | 工具组合调用业务约束 |
| 修改方式 | 修改yaml配置 | 新增/修改Java类 |


### 4.3 RAG 检索增强管线
两套管线并存，新版管线待接入主路由

1. **旧管线 RagService（当前使用）**  
 空库预检 → Embedding向量化 → 向量+全文检索 → 结果注入上下文
2. **新管线 RagPipelineService（待接入）**

```plain
用户问题 → 闲聊短路判断 → 知识库相似度预检(阈值0.45)
    → 向量检索(0.6权重)+BM25(0.4权重)混合检索
    → 长度限制注入（单块≤500字，总长度≤1500字）
    → 送入LLM生成答案
```

**关键优化机制**

+ 闲聊短路：普通闲聊跳过检索流程
+ TTL缓存：相同查询60秒免查询数据库
+ 向量内存缓存：重复问题避免重复调用Embedding接口
+ 空库预检：知识库无数据时，跳过Embedding调用，节省API费用

**文档入库流程**

```plain
文件消息 → WeChatBotService.handleFileMessage()
    └── ragService.ingestDocument()
        ├── DocumentService.extractText()    // 文本提取
        ├── DocumentService.splitText()      // 文本切块（重叠100字）
        ├── EmbeddingService.embed()         // 向量化
        └── 写入doc_embeddings向量表
```

### 4.4 MCP 外部工具集成方案
支持两种MCP接入模式：

1. **stdio模式（当前12306工具）**  
 启动Node子进程，stdin/stdout传输JSON-RPC；启动阶段自动获取远端工具列表，注册至ToolRegistry。
2. **HTTP模式（预留扩展）**  
 POST方式调用远程MCP服务，适合独立部署外部工具服务。

**统一工具模型**

+ 本地工具：`@Tool`注解 + 反射调用
+ MCP外部工具：通过`registerExternal()`注册，统一入口执行

> 特殊处理：12306/B站所属域，关闭工具分域过滤，返回全部可用外部工具。
>

### 4.5 工具注册与执行机制
**初始化扫描流程（ToolRegistry @PostConstruct）**

1. 扫描Spring容器内所有Bean，识别带`@Tool`注解方法
2. 解析工具名称、描述、参数，自动生成JSON Schema
3. 过滤系统内置参数（userId等），缓存工具元信息

**工具调用流程**

```plain
ToolRegistry.execute(toolName, args, userId)
    │
    ├── 判断工具类型
    │    ├── 外部MCP工具：执行自定义executor回调
    │    ├── 本地@Tool工具：反射调用目标方法，自动注入userId
    │
    └── 返回字符串结果
```

### 4.6 ThreadLocal 事件队列（ToolExecutionContext）
**解决痛点**：@Tool内部不能直接发送图片/文件，防止LLM上下文与实际输出不一致。

+ 在`@Tool`方法中**仅记录事件**（生成图片、二维码、文件导出等）
+ 在消息回复阶段`sendReply()`统一消费事件、执行发送
+ 生命周期：消息入口清空 → 回复完成后清理ThreadLocal

### 4.7 对话历史持久化管理（ChatService）
1. 粗Token估算（字符/1.5），超过阈值触发摘要压缩
2. 保留最近对话 + LLM摘要，控制上下文长度
3. 数据库写入采用异步线程池，不阻塞消息响应  
数据表：
+ chat_history：用户对话汇总JSON
+ admin_user_info：用户基础信息
+ admin_user_messages：逐条消息明细

### 4.8 B站收藏夹集成模块
依赖java-fav SDK，Cookie持久化至`bili-cookie.txt`  
可用工具：

+ bili_login_qr：生成登录二维码
+ bili_login_confirm：轮询确认登录，保存Cookie
+ bili_fav_folders：查询收藏夹列表
+ bili_fav_videos：抓取收藏视频元数据
+ bili_video_subtitle：获取视频字幕（截断保护2500字）

### 4.9 统一异常路由 ErrorRouter
统一分类错误，对外输出友好提示

| 错误分类 | 场景 | 用户提示文案 |
| --- | --- | --- |
| API_KEY缺失 | 第三方密钥未配置 | 服务未配置，请联系管理员 |
| NETWORK | 接口超时、连接失败 | 网络不稳定，请稍后重试 |
| CITY_NOT_FOUND | 地点解析失败 | 未找到该城市 |
| INVALID_PARAM | 参数不合法 | 参数不正确，请检查输入 |
| SERVICE_LIMIT | 限流、Token超限 | 请求太频繁，请稍等 |


### 4.10 配置管理体系
1. 所有敏感密钥通过环境变量注入 `${VAR_NAME:}`，支持空默认值；缺失不阻止项目启动
2. 配置文件加入`.gitignore`，避免密钥泄露
3. 调试接口：`http://127.0.0.1:8080/config/check`，查看所有密钥加载状态（脱敏展示）

核心配置项

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| llm.model | qwen3.6-flash | 大模型名称 |
| llm.max-history | 30 | 单用户最大对话轮数 |
| llm.token-threshold | 30000 | 触发对话摘要阈值 |
| llm.log-traffic | false | LLM请求日志开关 |
| llm.tool-result-max-chars | 10000 | 工具返回文本最大长度 |
| server.address | 127.0.0.1 | 本地监听，禁止外网访问 |


## 五、工具总览
### 本地内置工具（60+）
天气环境、搜索、图片生成、文件处理、时间日历、命理运势、健康生活、资讯热搜、计算、二维码、语音合成、出行、订单票券、待办提醒、B站工具、IP定位、RAG查询、记忆管理、快递查询、菜谱、系统工具

### MCP外部工具
12306：余票查询、车站检索（stdio协议）

## 六、开发接入指南
### 6.1 添加新本地工具
直接新建Bean，添加`@Tool`注解，项目启动自动扫描注册，无需修改路由代码

```java
@Component
public class YourTool {
    @Tool(name = "tool_name", value = "工具描述，说明用途与适用场景")
    public String yourMethod(@P("参数说明") String param) {
        try {
            // 业务逻辑
            return "执行结果";
        } catch (Exception e) {
            return "执行失败: " + e.getMessage();
        }
    }
}
```

### 6.2 配置规范
所有密钥统一在`application.yaml`使用环境变量占位符；敏感配置不提交代码仓库。

## 七、历史问题与解决方案
| 序号 | 历史问题 | 解决方案 |
| --- | --- | --- |
| 1 | 重启机器人需要重复扫码登录 | SessionManager会话持久化 |
| 2 | 无法自然语言触发指令 | 全面切换LLM Function Calling架构 |
| 3 | 工具注册代码冗余繁琐 | @Tool注解 + 启动自动扫描 |
| 4 | 工具原始结果直接返回用户，体验差 | ReAct循环，LLM二次整合结果 |
| 5 | 图片/文件发送逻辑混乱 | ToolExecutionContext事件队列 |
| 6 | 对话上下文持续膨胀 | Token阈值检测 + LLM摘要压缩 |
| 7 | 每次请求携带全部工具，Token消耗巨大 | 动态工具分域过滤 |
| 8 | 简单闲聊存在两次LLM冗余调用 | 优化路由，移除多余二次调用 |
| 9 | RAG知识库为空时依然调用Embedding | 数据库数量预检+TTL缓存 |
| 10 | MySQL写入阻塞消息回复 | 异步线程池执行数据库落库 |
| 11 | 传给LLM的工具缺少完整JSON Schema | 统一toToolSpecs补全参数定义 |
| 12 | MCP工具名称无法硬编码到域列表 | 指定域关闭工具过滤，返回全部外部工具 |
| 13 | 首次启动缺少向量表 | 补充doc_embeddings建表SQL |


---

