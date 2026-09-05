# 银龄智护 · ElderlyHealth-WeChatAgent（后端 Agent）

> 老年人全周期健康管理微信智能 Agent —— 让每个老人都有一个 24 小时在线的健康管家，
> 让每个子女都能远程守护父母健康。

基于 Spring Boot + 大语言模型（qwen 系列 / Function Calling）的微信智能体后端。
本仓库是银龄智护产品的后端 Agent 服务，由 WeChat-ClawBot-Agent 二次开发而来，
品牌、包名、数据库均已独立（详见总目录 `docs/银龄智护-开发计划.md`）。

## ✨ 核心能力（健康垂直方向）

- **健康档案**：用药档案、体征记录（血压/血糖/心率，支持"早上量的血压"等自然语言时间识别）、
  体检报告归档、慢病饮食/运动健康方案，按用户隔离存储 + MySQL 落库
- **用药管理**：登记、服药打卡、余量预警，向"相互作用检查 / 依从性分析 / 处方 OCR"深化中
- **体征分级预警**：绿色（正常）/ 黄色（关注）/ 红色（危险）三级判定与家人通知（开发中）
- **家庭联动**：子女守护视图、健康日报推送、异常实时通知（开发中）
- **主动健康管理**：每日健康简报、天气联动提醒、RAG 健康知识问答
- **Function Calling Agent**：@Tool 自动注册工具，ReAct 循环，零硬编码意图
- **多模态**：文本 / 图片 / 语音 / 文件收发，AI 绘图与识别（DashScope）
- **RAG 知识库**：文档入库、向量检索、问答引用溯源
- **适老化语音**：语音输入→输出链路，慢语速 / 大字卡片方向持续改造

> 详细的产品规划与 P0/P1/P2 排期见总目录 `docs/银龄智护-开发计划.md`；M1 自测指引见 `docs/M1-测试手册.md`。

## 🏗️ 技术栈

| 技术 | 用途 |
|------|------|
| Spring Boot 4 + Java 21 | 应用框架 |
| MyBatis + MySQL | 数据持久化 |
| iLink SDK | 微信消息接入 |
| DashScope SDK | 阿里大模型（qwen 系列）、语音、图像 |
| LangChain4j | RAG 知识库向量检索 |
| Redis / POI / PDFBox | 会话存储 / 文档解析 |

## 🚀 快速开始

### 环境要求
- JDK 21+、Maven 3.6+
- MySQL 8.0+（库名 `elderly_health_db`）
- 微信 iLink 应用配置、阿里云 DashScope API Key

### 1. 克隆与配置
```bash
git clone git@github.com:lihaoshijie/ElderlyHealth-WeChatAgent.git
cd ElderlyHealth-WeChatAgent

# 复制配置模板并填入密钥
cp src/main/resources/application.example.yaml src/main/resources/application.yaml
```

### 2. 初始化数据库
```bash
mysql -uroot -p -e "CREATE DATABASE elderly_health_db DEFAULT CHARACTER SET utf8mb4"
mysql -uroot -p elderly_health_db < sql/schema.sql
```

### 3. 编译运行
```bash
./mvnw clean package -DskipTests
java -jar target/elderly-health-agent-0.0.1-SNAPSHOT.jar
```
首次运行控制台显示二维码，使用微信扫码即可登录并开始对话。

### 4. 管理后台
前端管理台在独立仓库 [ElderlyHealth-WeChatAgent-admin](https://github.com/lihaoshijie/ElderlyHealth-WeChatAgent-admin)。

## 🔒 安全说明
- `src/main/resources/application.yaml`（含数据库口令 / 第三方密钥）已被 `.gitignore` 排除，**不要**强制提交。
- 请使用 `application.example.yaml` 模板在本地自行配置。
- 健康数据按用户隔离保存，仅作个人健康记录用途，不构成医疗建议。

## 📁 目录结构（后端）
```
src/main/java/com/elderlyhealth/agent/
├── bot/        微信接入、Skill 技能路由、Agent 循环
├── tool/       Function Calling 工具注册（30+）
├── service/    业务服务（HealthManagementService / ReminderScheduler / RAG / 语音…）
├── controller/ REST API（管理端 / 知识库 / 支付回调）
├── entity/     MyBatis 实体
├── mapper/     数据访问接口 + XML
├── config/     安全 / 跨域等配置
└── util/       工具类
```
