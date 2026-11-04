# RabbitMQ 迁移收尾修复与真实验收报告

验收日期：2026-11-04。**本机真实 RabbitMQ 消息链路与故障恢复已通过；本分支未合并 main。** 本报告不把 Mock 响应或跳过测试计为真实 Broker 验证。

## 基线与范围

- 稳定基线：`5e5c509f590776caf25e0751543990b979fba9e8`，迁移前工作区干净。
- 工作分支：`migration/rabbitmq`。本轮从上一轮未提交迁移工作区继续修复。
- 保留 MySQL Transactional Outbox 本地事务、事件字段/语义、event_id、数据库幂等唯一约束；没有 Outbox 表结构或公开接口变更。
- 原 HTTP 并发压测脚本、数据、EXPLAIN、报告逐文件 SHA256 不变；既有非消息业务实现没有修改。详见 [逐文件差异](rabbitmq-diff.md)。

## 三项问题的修复

| 问题 | 文件 | 处理与证据 |
|---|---|---|
| 单条失败终止整批 | business/Publisher.java；contracts/RabbitTransport.java | 发送、确认/Return、成功标记失败后，只保留该事件并记录失败；后续事件继续。10/20/40/80/160/300 秒退避封顶；attempt_count 领取栅栏、30秒租约保持；失败记录写入失败也靠租约恢复。真实首条连续两次 Return，后续成功，修复路由后原 event_id 恢复。|
| API 启动依赖 Broker | compose.yaml；api/application.yml | 删除 API 的 Rabbit service_healthy 依赖；保留 Worker 和 Broker 健康检查。确认 Boot RabbitAdmin 延迟声明、Transport 构造无连接、无同步启动发布；全局健康保留 Rabbit DOWN，readiness 仅 readinessState/db/redis。真实停 Broker→启动打包 API→HTTP 预约→恢复→补发与消费全流程通过。|
| 测试拓扑与生产不同 | PublisherIntegrationTest.java；RabbitEndToEndTest.java；RabbitReliabilityLocalTest.java | 全部复用 RabbitTopology 的生产 Declarables。三队列均 durable quorum，Topic/Direct Exchange、Binding、Routing Key 相同；不存在用 Classic 绕过问题的测试。重复声明无冲突，管理 API 查询确认为 quorum。|

成功状态沿用数据库原来的 `PUBLISHED`。只有 Confirm ACK 且无 Return 才会尝试设置该状态；确认丢失或标记失败允许再次投递，通知数据库负责幂等，不承诺端到端 exactly-once。

## 实际运行环境

- Windows 本机，Java 17；MySQL **8.0.27**，Redis **7.4.9**。
- RabbitMQ **4.1.4**、Erlang/OTP **27.3.4.1**，由官方 Windows ZIP 解压到 `.tools/rabbit-runtime`，未安装系统服务；包版本及 SHA256 固定在 prepare-local-broker.py。
- 实例：`culturehub_acceptance@localhost`；AMQP `127.0.0.1:5673`；管理界面 `http://127.0.0.1:15673`；vhost `culturehub_acceptance`。三条 Quorum Queue 在同一节点，未模拟三节点集群。
- Maven 使用专用 `culturehub` 测试库、Redis 16379；独立进程验收使用 `culturehub_rabbit_acceptance` 库，不修改 rich_demo/perf 库。
- 独立进程 API18091、Worker18092 启动真实可执行 JAR。结束后退出并核对端口关闭，Broker 保持运行。
- XXL-JOB **真实执行器与原 outboxPublisher Handler** 已执行；测试脚本每2秒通过原 `/run` 协议触发，未运行 XXL-JOB Admin。没有新增生产轮询器。

## 测试结果

| 阶段 | 执行 | 通过 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|---:|
| 稳定 Kafka 基线 | 205 | 131 | 0 | 0 | 74 |
| 上一轮迁移草稿及本轮修复前复跑 | 272 | 159 | 0 | 0 | 113 |
| 本轮最终完整回归 | 298 | 202 | 0 | 0 | 96 |

这些是 JUnit 测试执行数，包含不同本地/容器上下文继承的相同业务测试；**不能当成同等数量的独立业务功能或消息故障场景**。当前汇总取各 suite 最新 XML，不把重复跑同一 suite 累加。逐方法结果见 [JUnit 原始条目](evidence/rabbitmq-junit-cases.json)。

本地真实 Broker 两个 suite 分别17次和25次，合计42次执行，其中包含继承的预约回归；独立消息场景对应下面的实际测试方法。独立进程验收另有 **15 项检查通过**，不与 JUnit 次数混算。

| 验收要求 | 真实执行入口 | 结果 |
|---|---|---|
| 预约→Outbox→Broker→通知 | bookingOutboxConfirmedDeliveryAndDuplicateProduceOneNotice；acceptance.py HTTP 链路 | 通过 |
| Broker 完全停止后 API 启动、预约提交、恢复后自动补发 | acceptance.py：真实停节点，再启动 API；真实 XXL Handler 周期执行；未强改 next_attempt_at | 通过 |
| 同 event_id 重复发布/消费最终一条 | 本地端到端套件；独立 Worker 重复消费；DB 唯一约束 | 通过 |
| 通知提交后 ACK 前进程退出并重投 | actualWorkerJvmExitAfterCommitBeforeAckRedeliversAndDeduplicates | 通过：测试专用 Worker JVM 使用原 NotificationConsumer 提交后 halt(86)，真实 Broker redelivered=true，仍一条通知 |
| 数据库临时失败后延迟重试恢复 | transientDatabaseFailureActuallyRetriesAfterDelayThenRecovers | 通过：真实 MySQL 触发器令 INSERT 失败，观测 retry 头>=1，移除故障后成功；不是数据库服务器断电 |
| 持续失败到上限后 DLQ，无即时无限循环 | persistentFailureHasThreeDelayedRetriesThenDeadLetterAndManualReplay | 通过：真实累计延迟>=3s，retry-attempt=3，DLQ 原 ID，无通知；移除故障后人工重放成功 |
| 无匹配路由，ACK+Return 不误记成功 | realReturnInFirstEventDoesNotBlockLaterEventAndRecovers | 通过：连续两次 NO_ROUTE，PENDING、错误原因和退避正确，后续正常事件成功；修复路由后第三次成功 |
| 确认后数据库标记失败 | markerDatabaseFailureAfterConfirmRetainsStableEventForRetry | 通过：真实数据库触发器拒绝 PUBLISHED 更新，保留 PENDING，重试后不重复通知 |
| 生产 Quorum 拓扑重复声明与重启兼容 | productionQuorumTopologyCanBeRedeclaredWithoutParameterConflict；独立进程重启 Broker 后原生产声明 | 通过 |
| Worker 停止/重新启动恢复排队消息；非法 JSON 入 DLQ | actualListenerRestartRecoversQueuedEvent；malformedJsonIsRetainedInDeadLetter | 通过 |

受控单测也保留：RabbitConfirmTest 的 Ack/Nack/Return，ManualAckTest 的转发确认与 ACK 顺序；它们验证代码分支，未伪装为真实 Broker 故障。

## 原业务与 Git Diff 审核

原稳定提交中可运行且通过的 suite 本轮均未改为跳过。预约/并发/重复资格、FIFO/确认超时竞争、签到、权限、统计、MyBatis 动态分页、缓存优化、AI Graph/HITL/校验和 SSE 回归通过；Vue 模板、中文映射、SSE 解析及实际组件交互回归也通过。

业务 main 目录与 API Java 源码中唯一差异为消息 Publisher.java；Redis/Cache、MyBatis/XML、Booking/ParticipationService、签到/评价/统计、AI Graph/HITL/SSE、前端无实现差异。NotificationConsumer 仅移除 Kafka import/监听注解，事务体保持。既有 Kafka 测试按用户原样归档授权保存，校验与 Git 基线字节一致。

原 Publisher 测试中“单条失败应抛异常”断言按本轮要求改为“批次继续且失败状态保留”，不是删除故障断言；保留并增强原状态、重试计数、幂等断言。没有通过更改业务规则让测试过关。

## 实际失败与修复记录

1. 首次基线复跑：115个数据库连接错误，因为现有隔离 MySQL 未启动。启动原数据目录对应实例后，修复前基线272次恢复为159通过/113跳过，无代码或断言变更。
2. 首轮新增真实 Broker 测试62次中1错误：测试临时文件写入系统 Temp 被拒绝。改用模块 target 后复跑通过，未降低断言。
3. 独立进程验收初版15项业务检查通过，但 Windows Oracle javapath 启动器退出后遗留实际 API/Worker 子 JVM，抢占后续测试消息，造成后续25次复测7失败/1错误。修复为解析实际 java.home、直接启动真实 JVM、等待终止并检查18091/18092/9999端口关闭；增加专用队列存在其他消费者时快速失败，修复子进程失败清理。随后重跑完整回归及独立进程验收通过。历史失败不删改成通过。

## 未执行与剩余边界

- **96次容器测试跳过**：本机无 Docker。包含 ContainerBooking(15)、OptimizationContainer(23)、ProposalContainer(30)、PublisherIntegration(3)、RabbitEndToEndContainer(18)、WorkerRabbit(7)。保留全部测试及原断言，不能写成容器验收通过。
- Compose YAML 解析及启动依赖检查通过，**未实际执行 Docker Compose**。本机真实 Broker 使用官方便携发行包，不是容器，也不是模拟 Broker。
- 未启动真实 XXL-JOB Admin；只实测原执行器协议及原 Handler。
- 未验证三节点 Quorum 故障、节点丢盘、跨机分区、生产负载或 Broker 主动 Nack 故障注入（Nack 分支只有受控单测）。这些不是本轮单节点验收的已测结论。
- AI 实现无修改，原受控模型/校验/SSE 回归已过，未重新进行真实模型/Embedding 调用。
- 本轮未重跑或覆盖历史 HTTP 压测，不新增 QPS 或性能提升百分比。

没有尚未修复的本轮可复现功能错误。以上环境限制明确保留，不能推导为商业生产可靠性保证。

## 复现命令与证据

```powershell
python scripts/rabbitmq/prepare-local-broker.py
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/rabbitmq/local-broker.ps1 -Action Start
$env:RABBIT_VHOST='culturehub_acceptance'
mvn -s settings.xml clean package '-Dculturehub.localTest=true' '-Dculturehub.rabbitLocalTest=true' '-Drabbit.port=5673' '-DskipTests=false'
python scripts/rabbitmq/acceptance.py --mysql D:\mysql\mysqlx\bin\mysql.exe
python scripts/rabbitmq/report.py
cd web
npm run test:ui
```

先准备专用 MySQL13306/Redis16379；测试会清理专用库和测试队列。完整说明见 [本地指南](rabbitmq-local-guide.md)。证据：[最终汇总](evidence/rabbitmq-tests.json)、[修复前草稿记录](evidence/rabbitmq-draft-tests.json)、[稳定基线](evidence/rabbitmq-baseline.json)、[独立进程原始检查](evidence/rabbitmq-standalone-acceptance.json)。
