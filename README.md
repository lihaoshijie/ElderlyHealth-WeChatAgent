# 🤖 智能微信机器人 (WeChat‑ClawBot‑Agent)
一个基于 Spring Boot + 大语言模型 (LLM) 的微信智能机器人，支持多轮对话、上下文记忆、图像识别与生成、语音合成、文件解析、搜索、旅行规划、健康管理、八字塔罗等 30+ 内置工具，通过 Function Calling 实现 Agent 能力。

## ✨ 核心能力
- **Function Calling Agent 架构**：@Tool 注解自动注册工具，ReAct 循环，零硬编码意图
- **多模态消息**：文本 / 图片 / 语音 / 文件收发与处理
- **上下文记忆**：多轮对话、Token 压缩与历史摘要、用户画像记忆
- **RAG 知识库**：文档入库、向量检索、问答引用
- **MCP 集成**：标准 MCP 服务器接入，扩展外部工具
- **AI 绘画**：文生图、图生图、图片内容识别(基于阿里万象)
- **工具集(30+)**：天气、地图、航班旅行、订票、健康管理、医学问答、八字排盘、塔罗、B站、新闻、热搜、菜谱、科学计算、二维码、待办提醒等

## 🏗️ 技术栈
| 技术 | 用途 |
|------|------|
| Spring Boot 4 + Java 21 | 应用框架 |
| MyBatis + MySQL | 数据持久化 |
| WebFlux / WebClient | 响应式 HTTP 请求 |
| iLink SDK | 微信消息接入 |
| DashScope SDK | 阿里大模型(qwen 系列) |
| LangChain4j 向量存储 | RAG 知识库检索 |
| Chrome DevTools (CDP) | 浏览器自动化 |

## 🚀 快速开始
### 环境要求
- JDK 21+
- Maven 3.6+
- MySQL 8.0+
- 微信 iLink 应用配置
- 阿里云 DashScope API Key

### 1. 克隆项目
```bash
git clone git@github.com:lihaoshijie/WeChat-ClawBot-Agent.git
cd WeChat‑ClawBot‑Agent
```

### 2. 初始化数据库
```sql
-- 创建数据库，然后执行建表脚本
mysql -uroot -p < sql/schema.sql
```

### 3. 配置 application.yaml
```yaml
# 微信机器人配置
ilink:
  app-id: your_app_id
  app-secret: your_app_secret

# 大模型配置
llm:
  api-key: your_dashscope_api_key
  model: qwen-max
  base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
  max-history: 50
  token-threshold: 100000
  system-prompt: 你是一个智能助手...

# 数据库配置
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/demo_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: your_password

# 可选:飞猪 AI 旅行工具(不填则使用平台体验额度)
flyai:
  api-key: ${FLYAI_API_KEY:}
```

### 4. 编译运行
```bash
./mvnw clean package -DskipTests
java -jar target/demo-0.0.1-SNAPSHOT.jar
```
首次运行控制台会显示二维码，使用微信扫码即可登录并开始对话。

## 💬 使用示例
| 消息类型 | 示例 |
|---------|------|
| 文本对话 | `帮我规划下周去北京的行程` |
| 图片编辑 | 发送图片 + `帮我把背景换成海边` |
| 图片生成 | `画一只戴帽子的柴犬` |
| 语音 | 发送语音消息自动转文字对话 |
| 文件 | 上传 md/office/pdf，自动提取内容总结 |
| 工具调用 | `查一下北京明天天气`、`今天有什么热搜`、`我的健康档案` |

## 🔧 关键设计
### 上下文压缩机制
对话历史超过 token 阈值时自动触发 LLM 摘要，保留最近对话 + 历史摘要，避免长会话膨胀。

### 多图处理流程
```
发送多张图片 → 缓冲等待文本指令 → 拼接图片上下文 → LLM 理解 → 生成回复/新图
```

### RAG 问答
```
文档上传 → 文本切片 → 向量化入库 → 检索 TopK → LLM 引用回答
```

### 文件处理策略
- 文本文件：直接解码提取内容
- Office/PDF：POI / PDFBox 解析
- 二进制文件：检测并提示格式不支持

## 📁 项目结构
```
.
├── src/main/java/com/Myself/demo/
│   ├── bot/              # 机器人核心(消息处理、路由、工具注册、MCP)
│   ├── tool/             # 30+ 工具实现(@Tool 注解)
│   ├── service/          # 业务服务(对话、图像、语音、RAG、健康、八字等)
│   ├── config/           # 配置类
│   ├── controller/       # Web 控制器
│   ├── entity/           # 实体类
│   ├── mapper/           # MyBatis 数据访问
│   ├── exception/        # 异常定义
│   └── util/             # 工具类
├── src/main/resources/   # application.yaml、MyBatis XML
├── sql/schema.sql        # 建表脚本
└── data/                 # 运行期数据
```

## 📄 许可证
本项目遵循 LICENSE 文件中的许可协议。