# 本地学习与丰富演示数据（2026-11-01）

当前演示账号的登录密码已统一为 `123`，包括平台管理员、场馆管理员和普通用户。数据库连接凭据与 JWT 密钥使用独立配置。

## 本轮范围

在原项目上修复，不要求 Docker 或真实模型。五个 Java 模块、三个 AI 功能和技术栈保持不变。Kafka 与 XXL-JOB 的代码继续保留，未启动对应服务时不会自动投递通知、触发超时或发送提醒。

## 数据及初始化

丰富目录为 `culturehub-api/src/main/resources/demo/catalog.json`：8 个场馆、30 个不同活动。`RichDemo` 根据当前时间生成 50 个场次、34 个账号、251 条资格、56 条签到、56 条评价和演示通知。页面可以观察满员、排队、待确认、已结束、已签到和取消场景。

这是合成的学习数据。初始化在一个事务内完成，并检查容量不变量；同一数据库重复启动不会重复插入目录，也不删除已有数据。需要重新生成相对时间时，请新建另一个以 `_demo` 结尾的演示库，而不是修改已有资格关联的时间。

先准备本地 MySQL、Redis，设置自己的演示密码，PowerShell 执行：

```powershell
$env:DEMO_PASSWORD='123'
$env:MYSQL_PWD='你的本地MySQL密码'
$env:REDIS_HOST='127.0.0.1'
$env:REDIS_PORT='6379'
.\scripts\init-demo.ps1 -MysqlClient 'mysql' -Database culturehub_demo -Port 3306
```

脚本只允许专用 `_demo` 库，缺表时导入 DDL，构建后以 `culture.demo.rich=true` 启动主 API；不会要求安装 Docker，也不会调用模型。普通数据库不要开启这一初始化开关。默认关闭本地执行器，真实 XXL-JOB 环境通过 `JOBS_ENABLED=true` 启用。

另开终端在 `web` 中运行 `npm ci`、`npm run dev`；默认代理 API 8080。当前已运行预览在 15173，代理 18080，使用独立的 `culturehub_rich_demo`，与默认脚本端口不同。

账号：`admin` 为平台管理员；`manager1` 到 `manager8` 分别管理一个场馆；`user` 和 `demo01` 到 `demo23` 为普通用户；`venueadmin` 为第一个场馆的兼容演示账号。密码统一来自初始化时的 `DEMO_PASSWORD`。后续更改该变量不会重设已存在的密码。

只读审计：

```powershell
python scripts/verify-demo.py --database culturehub_demo --port 3306
```

审计检查容量、有效资格唯一性、签到与资格、评价依据、取消场次和确认截止时间。不会修改数据库。

## 推荐学习顺序

1. `SecurityConfiguration → Access → Management`：认证、接口角色与实体场馆权限。
2. `Booking → ParticipationService → Events`：Lua、事务代理、场次锁、名额与资格、同事务 Outbox。
3. `Publisher → NotificationConsumer → WorkerConfiguration`：至少一次发布与消费幂等、有限重试和死信。
4. `CheckInService → Cache`：核销与取消竞争、凭证、缓存的展示边界。
5. `Operations → Jobs`：实时进度和最终指标的区别、任务复用业务 Service。
6. `AiGuideGraph → AiBookingProposalService → Booking`：多轮 Graph、只读工具、用户明确确认与幂等恢复；`AiService`：策划、复盘的结构化生成与 Java 指标。详见 `ai-graph-upgrade.md`。

`checkinProgress` 是实时已签到人数/当前正式预约人数；`checkinRate` 只在正常场次结束后提供。汇总最终签到率只计算正常结束场次，按人数加权，分母零返回空。

## 本轮修改及验证

必填数字、文本、时间和枚举集中到 `Inputs`；不存在记录统一返回 404；主要缺失或格式错误返回 400。中文仅用于展示，表单提交分类、人群、状态编码。新增封面、场馆详情、活动须知、分页总数、操作反馈与统计卡片。

数据目录的图片为本地 SVG，不依赖图片站点。RAG 在 knowledge 目录保留八个场馆的公开规则。完整源码包保留这些合成资料；无数据分享包排除资料正文和演示目录，但包含 nginx.conf、SVG、配置模板与所有业务代码。

Maven 与 MySQL 测试、HTTP 参数和权限、前端检查结果见 evidence。Docker、真实 Kafka、XXL-JOB Admin、真实模型和 Chroma 检索效果没有在本轮执行，不要求为了学习先完成这些环境。
