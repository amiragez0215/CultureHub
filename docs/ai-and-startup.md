# AI 功能与启动方式

## 三个 AI 入口

1. 普通用户登录后点击“智能导览”。Alibaba Graph 编排多轮澄清、查询、推荐、Java 校验与选择；独立确认后调用原 Booking。RAG 在配置 Chroma 时检索公开知识，未配置时明确说明；Tool Calling 只读，ChatMemory 保存短期上下文。活动时间、名额和预约资格由 Java 业务服务裁决。
2. 管理员进入“管理工作台 → 智能活动策划”。输入场馆、主题、人群和时长，模型输出结构化方案，Java 校验后供人工编辑，再通过普通活动接口保存草稿、人工发布。
3. 管理员进入“管理工作台 → 智能复盘”。选择已结束的场次或活动，Java 计算真实指标，模型归纳评价，页面显示指标、分析和生成时间。

AI1 实现在 `culturehub-business/src/main/java/cn/culturehub/AiGuideGraph.java` 与 `AiBookingProposalService.java`；AI2/AI3 保留 `AiService.java`，REST 入口为 `AiController.java`。未配置真实模型时明确返回不可用。迁移、接口和测试说明见 `docs/ai-graph-upgrade.md`。

2026-11-01 已使用本机 `.env` 中的凭据，真实验证 DeepSeek `deepseek-flash`：智能导览及多轮追问、结构化活动策划、真实评价复盘均通过。导览返回的活动与场次名额经过 Java 数据库接口复核，策划总时长为 60 分钟并通过 DTO 校验，场次 48 的复盘保留 Java 统计并校验评价引用。策划未保存或发布，复盘按原业务保存到 `ai_report`。记录见 `docs/evidence/deepseek-direct.json` 和 `docs/evidence/deepseek-business.json`。Embedding 与 Chroma 未启用，完整 RAG 仍未验收。

DeepSeek 使用以下非密钥配置，真实密钥仅保留在本地 `.env`：

```properties
AI_CHAT_PROVIDER=openai
AI_BASE_URL=https://api.deepseek.com
AI_CHAT_MODEL=deepseek-flash
SPRING_PROFILES_ACTIVE=deepseek
AI_EMBEDDING_PROVIDER=none
AI_VECTORSTORE=none
```

`application-deepseek.yml` 设置非思考模式、单次最多 2048 输出 token 和有限重试。项目根目录的 `.env` 使用 `AI_API_KEY` 保存用户自己的密钥；从 IDE 启动时保持工作目录为根目录。未配置 Embedding 时导览使用真实业务工具查询，不将其称为已完成知识库检索。

## 编辑器

本项目是 Java + Vue，建议用 VS Code 打开整个项目根目录，安装 Extension Pack for Java、Spring Boot Extension Pack 和 Vue - Official。PyCharm 不作为本项目的主要开发工具。后端也可以使用 IntelliJ IDEA。

## 两种启动方式

完整本地部署：安装 Docker Desktop（Linux 容器）与 Compose，在根目录复制 `.env.example` 为 `.env`，配置密码和密钥，然后执行 `docker compose up --build -d`。完整 Compose 的前端端口为 8089，API 为 8080。

开发调试：先准备 MySQL、Redis、Kafka、XXL-JOB 和 Chroma，设置环境变量，再用 Maven 构建。分别运行 ApiApplication 与 WorkerApplication；前端在 web 目录执行 npm ci 和 npm run dev。Vite 默认端口通常为 5173；以终端实际输出为准。IDE 只负责编辑和调试，不代替数据库及消息等基础服务。

当前聊天期间的本机预览地址为 `http://127.0.0.1:15173/`，API 为 18080，使用临时独立数据库。它与 Compose 标准端口不同，预览进程停止后不会自动恢复。

## 启用真实 AI

主 API 已通过 `spring.config.import=optional:file:.env[.properties]` 在启动时读取**工作目录中的 `.env`**。本地从项目根目录启动时，位置就是 `D:\agent\CultureHub\.env`；使用 IDE 时也将 Working directory 设为项目根目录。这个文件不会被源码压缩包收录，前端不会读取它。环境变量优先于文件；修改后需要重新启动 Java 主 API，仅刷新网页不会重新加载模型配置。

先开聊天能力（活动策划、复盘，以及使用真实业务查询工具的导览）：

```properties
AI_CHAT_PROVIDER=openai
AI_API_KEY=在这里填聊天服务的真实密钥
AI_BASE_URL=https://你的OpenAI兼容服务域名
AI_CHAT_MODEL=服务商实际提供的模型名称
AI_EMBEDDING_PROVIDER=none
AI_VECTORSTORE=none
```

这里 `openai` 表示 Spring AI 的 OpenAI 兼容协议适配器，并不要求模型必须由 OpenAI 提供。聊天模型需支持工具调用和结构化输出。当前客户端默认使用 `/v1/chat/completions` 路径，Base URL 应按此配置，不填写完整的 chat/completions 请求地址。不要在值两侧添加引号，不要把密钥放入 `web`、前端代码或 `VITE_` 变量。

完整启用知识检索时，再设置以下项，并确保 Chroma 可访问：

```properties
AI_EMBEDDING_PROVIDER=openai
AI_EMBEDDING_MODEL=服务商实际提供的向量模型名称
AI_VECTORSTORE=chroma
CHROMA_HOST=http://localhost
CHROMA_PORT=8000
```

聊天和向量模型可以使用不同服务商。需要分开时，取消 `.env` 中 `AI_EMBEDDING_API_KEY`、`AI_EMBEDDING_BASE_URL` 两行的注释并填写；不设置时复用聊天密钥和地址。仅启用聊天、未配置 Chroma 时，导览仍可查真实活动，但不具备完整的 RAG 知识检索。

配置聊天模型、Embedding 模型及其服务凭据；聊天模型需要支持工具调用。启用 `AI_CHAT_PROVIDER=openai`、`AI_EMBEDDING_PROVIDER=openai`、`AI_VECTORSTORE=chroma`，并设置 AI_API_KEY、AI_BASE_URL、AI_CHAT_MODEL、AI_EMBEDDING_MODEL。这些配置使用 Spring AI 的对应接口，服务端地址应按实际模型服务填写。

启动 Chroma 和主 API 后，准备公开知识文件，以平台管理员调用 `/api/v1/admin/ai/knowledge/reindex`。密钥只放在本机环境变量或不纳入分享的 .env 文件。缺少 Embedding 或知识资料时，不能将 RAG 视为已经配置完成。
