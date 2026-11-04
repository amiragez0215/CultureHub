# RabbitMQ 本地运行与真实验收

本轮实测日期：2026-11-04。使用工作区 ZIP 便携部署，未安装 Windows 服务、未修改系统 Erlang 注册信息、未使用 Docker。

## 便携 Broker

在仓库根目录运行：

```powershell
python scripts/rabbitmq/prepare-local-broker.py
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/rabbitmq/local-broker.ps1 -Action Start
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/rabbitmq/local-broker.ps1 -Action Status
```

下载官方 OTP 27.3.4.1、RabbitMQ 4.1.4 Windows ZIP，校验固定 SHA256，解压到 `.tools/rabbit-runtime`。首次下载需要网络；需要系统已有 VC++ 运行库（本机已有）。参考 [RabbitMQ Windows 手动运行说明](https://www.rabbitmq.com/docs/install-windows-manual)、[4.1 Erlang 兼容矩阵](https://www.rabbitmq.com/docs/4.1/which-erlang)。二进制、日志、消息数据均不提交到 Git。

- AMQP：`127.0.0.1:5673`；管理页面：<http://127.0.0.1:15673>。
- 测试专用 vhost：`culturehub_acceptance`；本机测试账号：`guest / guest`，仅允许本机使用。
- 节点：`culturehub_acceptance@localhost`；数据在 `.tools/rabbit-runtime/state`；停止后再次 Start 复用原持久化目录。
- 停止：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/rabbitmq/local-broker.ps1 -Action Stop`。

这是单节点 Quorum Queue 验证环境，未声称验证三节点仲裁、节点丢盘或跨机网络分区。

## 完整 Maven 回归

须先有专用 MySQL `127.0.0.1:13306/culturehub`（root 空密码）、Redis `127.0.0.1:16379`。这些是已有本地集成测试约定。**测试会清空专用 culturehub 库的业务表并清理固定测试队列，禁止指向共享/演示数据环境。** 不会读取或重置 culturehub_rich_demo。

```powershell
$env:RABBIT_USER='guest'
$env:RABBIT_PASSWORD='guest'
$env:RABBIT_VHOST='culturehub_acceptance'
mvn -s settings.xml clean package '-Dculturehub.localTest=true' '-Dculturehub.rabbitLocalTest=true' '-Drabbit.port=5673' '-DskipTests=false'
```

RabbitEndToEndLocalTest 与 RabbitReliabilityLocalTest 使用真实生产拓扑。测试包含真实 Booking→Outbox→Broker→原 NotificationConsumer→通知，Return、数据库触发器注入失败、延迟重试/DLQ/重放、领取租约和 Worker JVM 在提交后 ACK 前退出。消息套件中也继承原预约回归，测试执行数不能当作不同故障数。

Docker 可用时保留原 PublisherIntegrationTest、WorkerRabbitTest、RabbitEndToEndContainerTest 入口；生产拓扑均复用 RabbitTopology。它们在本机因 Docker 缺失跳过，未记为通过。

## 独立 API 与 Worker 的启动/停机恢复验收

完成上面的 package 后运行：

```powershell
python scripts/rabbitmq/acceptance.py --mysql D:\mysql\mysqlx\bin\mysql.exe
python scripts/rabbitmq/report.py
```

`--mysql` 可改为本机 mysql.exe 绝对路径。脚本只重建专用 `culturehub_rabbit_acceptance` 库，停止/恢复上述独立测试 Broker，启动真实打包后的 API(18091) 和 Worker(18092)。它先停 Broker 再启动 API，经 HTTP 注册、登录和预约，核对事务结果和 PENDING Outbox；恢复 Broker 后不手工修改 next_attempt_at，等待退避到期补发并查询通知。

脚本通过 HTTP 调用真实 XXL-JOB 执行器(9999) 的原 outboxPublisher Handler，每2秒触发一次。它只替代 Admin 的定时触发时钟，未运行 XXL-JOB Admin；不是生产新增后台轮询器。普通部署仍应配置原有 XXL-JOB 任务。临时 API/Worker 会在结束后退出，Broker 保持运行。测试结果写入 docs/evidence/rabbitmq-standalone-acceptance.json。

API RabbitAdmin 随连接延迟声明并在重连后重声明，没有启动阶段同步发送。全局 `/actuator/health` 保留 Rabbit 故障 DOWN/503；核心 `/actuator/health/readiness` 在 MySQL/Redis 正常时保持 UP（后者沿用认证规则，需要 JWT）。API 启动和业务访问均经过实测。

## 普通运行

API 从根目录读取 `.env`，Worker 读取进程环境变量。配置同一 DB_URL/DB_USER/DB_PASSWORD 及 RABBIT_HOST/PORT/USER/PASSWORD/VHOST，然后运行原 API/Worker JAR。便携测试节点需 RABBIT_PORT=5673、RABBIT_VHOST=culturehub_acceptance；Compose 默认端口5672/vhost由.env指定，勿混淆。

```powershell
java -jar culturehub-api/target/culturehub-api-1.0.0.jar
java -jar culturehub-notification-worker/target/culturehub-notification-worker-1.0.0.jar
```

Compose 配置仍保留，API 仅依赖 MySQL/Redis 健康，Worker 等待 Rabbit 健康。`docker compose up --build` 未在本机执行。
