# 本地学习入口

2026-11-01 技术增强：公开场次 MGET 缓存、运营报表批量 SQL、AI1 SSE 分片回答与停止按钮、场馆 CRUD 的 MyBatis-Plus 写入。变更清单、索引迁移、缓存语义、SSE 契约与测试边界见 [技术增强与学习导读](docs/technical-enhancement.md)。已有库执行 `deploy/migrations/20261101-participation-history-index.sql`，不需要重置演示数据。

本轮无需 Docker 或模型密钥。丰富数据、本地初始化与学习顺序见 [本地学习与演示说明](docs/learning-and-demo.md)。以下保留完整部署参考。

AI 增量升级已实现：AI1 使用 Alibaba Graph 进行多轮导览与场次推荐，用户明确确认后复用原 Booking 预约入口；AI2/AI3 继续 ChatClient。已有库需执行新增方案表迁移，详见 [AI Graph 升级与源码导读](docs/ai-graph-upgrade.md)。

# CultureHub｜文化有约

Java 17 / Spring Boot 3 公共文化活动预约平台。主 API 是模块化单体，Notification Worker 独立运行；Vue 3 提供用户端和管理端。免费活动，一账号一场次一名额，无支付业务。

## 启动完整系统

需要 Docker Engine / Docker Desktop（Linux 容器）及 Docker Compose v2。复制 `.env.example` 为 `.env`，修改数据库密码、JWT 密钥、演示账号密码及任务 Token，再执行：

```sh
docker compose up --build -d
docker compose logs -f api worker
```

首次启动可能需要下载 Maven/npm 依赖和镜像。访问：

| 服务 | 地址 |
|---|---|
| Vue 用户端 / 管理端 | http://localhost:8089 |
| API | http://localhost:8080/api/v1 |
| XXL-JOB | http://localhost:8088/xxl-job-admin |
| Chroma（本地维护） | http://localhost:8000 |

`admin`、`venueadmin`、`user` 三个演示账号的密码均取 `DEMO_PASSWORD`。仅首次不存在账号时创建；更改环境变量不会重设已有密码。`venueadmin` 仅被授予场馆 1，场馆 2 可验证越权拒绝。普通注册不允许指定角色。

XXL-JOB 的独立本地控制台初始账号为 `admin / 123456`，进入后修改。初始化包含 `culturehub-api` 执行器和三个启用任务：`candidateMaintenance`（10 秒）、`sessionReminders`（30 秒）、`outboxPublisher`（5 秒）。执行器地址是容器内 `http://api:9999/`，访问令牌需与 `.env` 一致。

数据库脚本在 `deploy/mysql`，只在 **MySQL 空数据卷** 首次启动时执行。不会自动升级已有库。已有开发库应显式执行版本变更；不要以删除数据卷作为日常升级方式。模拟场次时间相对首次初始化时间生成；过期后可用管理员创建新场次。示例第一场容量 2，便于使用三个用户演示候补。

## 本地开发

后端需 JDK 17、Maven 3.6.3+；前端需 Node 22。可以只启动 Compose 基础设施，再本地启动 Java。设置 `DB_URL`、`DB_USER`、`DB_PASSWORD`、`JWT_SECRET`、`REDIS_HOST/PORT`、`KAFKA_BOOTSTRAP`。本地 Java 连接容器 Kafka 应使用 `localhost:29092`；容器内部使用 `kafka:9092`。

```sh
mvn -s settings.xml package
java -jar culturehub-api/target/culturehub-api-1.0.0.jar
java -jar culturehub-notification-worker/target/culturehub-notification-worker-1.0.0.jar
cd web
npm ci
npm run dev
```

项目的 `settings.xml` 仅提供 HTTPS Maven Central 和工作区 `.tools/m2` 缓存，避免依赖机器全局镜像配置，可按实际环境选用。容器构建使用标准 Maven 配置。普通 API 使用默认 8080，Worker 使用 8081。Vite 默认代理 8080，可用 `CULTUREHUB_API` 覆盖。

容器内所有业务时间使用 Asia/Shanghai。API 场次输入为该时区的无偏移 ISO 本地时间。创建场次前检查浏览器时区，前端日期控件按本地时间输入。

## 模型与 RAG

缺少密钥时普通业务正常工作，AI 接口明确返回 503，不伪造模型结果。启用真实模型需配置：

* `AI_CHAT_PROVIDER=openai`、`AI_EMBEDDING_PROVIDER=openai`、`AI_VECTORSTORE=chroma`。
* `AI_API_KEY`、`AI_BASE_URL`、`AI_CHAT_MODEL`、`AI_EMBEDDING_MODEL`。
* 若 Embedding 使用不同服务，可通过 `SPRING_AI_OPENAI_EMBEDDING_API_KEY` 和 `SPRING_AI_OPENAI_EMBEDDING_BASE_URL` 覆盖，或直接配置 application 配置项。
* `CHROMA_HOST` 与 `CHROMA_PORT`，Compose 内默认 `http://chroma:8000`。

重启 API 后，以平台管理员调用 `POST /api/v1/admin/ai/knowledge/reindex`，导入 `culturehub-business/src/main/resources/knowledge`。资料按公开规则维护，更新后重新导入；更换 Embedding 维度需重新建立集合。导入期间不应开展导览验收；导入失败应修复并重新执行，不能把部分知识当作完整资料。知识与文档内容都不具备授权能力。

导览模型仅使用只读工具，场次卡片由 Java 重新校验；选择卡片后生成持久化待确认方案，只有独立确认接口才能调用原预约入口。策划结果进入可编辑预览，另行保存草稿并人工发布；复盘使用 Java 指标和限制长度的真实评价。对话与偏好在进程内保存，重启清空，空闲 30 分钟过期；已提交预约可通过 MySQL 方案和原 request_id 恢复结果，不保存长期用户画像。

## 目录与关键实现

| 路径 | 内容 |
|---|---|
| `culturehub-contracts` | 五类事件契约与版本校验 |
| `culturehub-business/.../ParticipationService.java` | 场次锁、六状态、容量更新、候补补位和超时 |
| `culturehub-business/.../Booking.java` | 先查已提交幂等结果；新请求经 Lua 限流后进入事务代理 |
| `culturehub-business/.../Management.java` | 权限范围、活动发布取消、场次修改限制 |
| `culturehub-business/.../Publisher.java` | Outbox 至少一次发布，网络发送不持数据库事务 |
| `culturehub-notification-worker/.../NotificationConsumer.java` | 通知唯一约束、事务与消费幂等 |
| `culturehub-notification-worker/.../WorkerConfiguration.java` | 有限重试、死信和发送失败保留 |
| `culturehub-business/.../CheckInService.java` | 随机凭证、核销与取消的共同锁顺序 |
| `culturehub-business/.../Operations.java`、`Jobs.java` | 指标口径、评价与三类定时任务 |
| `culturehub-business/.../AiGuideGraph.java` | AI1 Graph 节点/条件边、偏好、ChatMemory、RAG 与只读工具 |
| `culturehub-business/.../AiBookingProposalService.java` | 待确认方案、归属、租约、独立确认与原预约结果恢复 |
| `culturehub-business/.../AiService.java` | AI2/AI3 ChatClient、结构化策划和真实评价复盘 |
| `web/src/main.js` | 用户端、管理端与实际 REST 调用 |
| `deploy/mysql` | 业务 DDL、模拟数据及 XXL-JOB 初始化 |

## 测试

```sh
mvn -s settings.xml test
cd web
node check-template.cjs
npm run build
```

有 Docker 时自动运行 `ContainerBookingTest`、`PublisherIntegrationTest`、`WorkerKafkaTest`。无 Docker 时这些测试明确跳过，不能据构建成功认定 Kafka 已验证。核心容器使用真实 MySQL、Redis、Kafka，不以 Mock 替代数据库并发和消息链路。

也支持已存在的**独立测试实例** MySQL 13306（root 空密码、culturehub 测试库）和 Redis 16379：

```sh
mvn -s settings.xml '-Dculturehub.localTest=true' test
```

**该模式会清空专用 culturehub 测试库中的业务表，只能用于隔离测试实例，严禁指向有业务数据的数据库。** 默认不启用。容器测试的数据同样是临时数据。

`scripts/smoke.py` 是实际 HTTP 业务冒烟，会创建独立命名用户及场次，默认连接 `http://localhost:18080`；通过 `API_URL` 与 `DEMO_PASSWORD` 指定目标。在未配置真实模型的本地验收环境运行，最后检查 AI 明确不可用的结果。

AI 自动化使用受控模型响应验证结构解析、时长校验、权限、会话隔离和指标来源，不调用付费模型。真实模型及 Chroma 的效果必须另行验收，不能由模拟测试代替。

2026-11-01 补充：本机已通过 DeepSeek `deepseek-flash` 的真实导览、多轮对话、策划和评价复盘联调，记录见 `docs/evidence/deepseek-business.json`。本地 `.env` 中的密钥不会打包。DeepSeek 非思考模式配置在 `application-deepseek.yml`，通过 `SPRING_PROFILES_ACTIVE=deepseek` 启用；Embedding/Chroma 完整 RAG 尚未验证。日常单元测试继续使用受控响应，不自动消耗模型额度。

## 业务演示

1. 管理员创建活动和场次，发布；普通用户查询活动。
2. 两个账号预约容量 2 的场次，第三个主动加入候补。
3. 其中一人取消；第三个获得待确认机会，页面可直接查询，通知不控制资格。
4. 确认后展示二维码或复制随机凭证；管理员选择正确场次粘贴核销。
5. 重复核销返回同一结果；签到后取消被拒绝。
6. 场次结束后评价和修改评价，管理员查看统计及复盘。
7. 停止 Worker，产生预约事件后恢复 Worker，观察通知积压恢复；测试重发同一事件不产生第二条通知。

## 故障排查与死信

查看 `docker compose ps`、`docker compose logs mysql api worker kafka xxl-job`。数据库初始化失败先检查日志和脚本版本；JWT 密钥短于 32 字符会拒绝启动；Redis 故障暂停新资格，但不阻止取消确认；Kafka 故障保留 Outbox。任务未执行先检查执行器地址、Token 和任务启用状态。

持续消费失败或毒消息进入 `culturehub.notification.events.v1.dlt`。先检查原始内容与异常、修复数据库或契约问题，再使用 Worker 的 `--culture.replay-dlt=true` 显式运行重放。它采用固定重放消费组，只重放未提交过的死信记录并保持事件 ID；不要在未修复毒消息时反复重放。重放会先确认发送，再提交死信消费位点。原 Worker 正常消费仍可继续。

本地 Compose 为单节点演示部署，不宣称容忍 Kafka 磁盘丢失。未建立商业运营、第三方通知、支付或微服务平台。实际验证范围见 `docs/verification.md`；没有预置任何压测成绩。
