# RabbitMQ 迁移逐文件范围核查

对照稳定提交 `5e5c509f590776caf25e0751543990b979fba9e8`。原性能脚本、原始数据和报告逐文件 SHA256 一致；既有证据未改；旧 Kafka 测试归档与 Git 原始字节一致。

| 文件 | 原因 |
|---|---|
| `.env.example` | RabbitMQ external configuration template |
| `README.md` | Current messaging stack and pending acceptance notice |
| `compose.yaml` | Replace Kafka with durable RabbitMQ service |
| `culturehub-api/src/main/resources/application.yml` | RabbitMQ connection and confirms only |
| `culturehub-business/pom.xml` | AMQP/Rabbit test dependencies; test-scoped Worker support artifact |
| `culturehub-business/src/main/java/cn/culturehub/Publisher.java` | Outbox lease, Confirm/Return, recovery and microsecond due comparison |
| `culturehub-business/src/test/java/cn/culturehub/LostAckWorkerProcess.java` | 仅测试进程：真实通知提交后 halt，不发送 ACK |
| `culturehub-business/src/test/java/cn/culturehub/OutboxRabbitLocalTest.java` | 消息可靠性、真实数据库回归或真实 Broker 测试 |
| `culturehub-business/src/test/java/cn/culturehub/PublisherIntegrationTest.java` | Original two publisher scenarios migrated with assertions plus Return case |
| `culturehub-business/src/test/java/cn/culturehub/RabbitConfirmTest.java` | 消息可靠性、真实数据库回归或真实 Broker 测试 |
| `culturehub-business/src/test/java/cn/culturehub/RabbitEndToEndContainerTest.java` | 消息可靠性、真实数据库回归或真实 Broker 测试 |
| `culturehub-business/src/test/java/cn/culturehub/RabbitEndToEndLocalTest.java` | 消息可靠性、真实数据库回归或真实 Broker 测试 |
| `culturehub-business/src/test/java/cn/culturehub/RabbitEndToEndTest.java` | 消息可靠性、真实数据库回归或真实 Broker 测试 |
| `culturehub-business/src/test/java/cn/culturehub/RabbitReliabilityLocalTest.java` | 真实 Quorum Broker 下的批次隔离、Return、重试/DLQ、提交后 JVM 退出及重投验收 |
| `culturehub-contracts/pom.xml` | Boot-managed common AMQP transport/topology dependency |
| `culturehub-contracts/src/main/java/cn/culturehub/RabbitTopology.java` | Shared exchanges, durable queues and bindings |
| `culturehub-contracts/src/main/java/cn/culturehub/RabbitTransport.java` | Shared confirmed persistent send |
| `culturehub-notification-worker/pom.xml` | AMQP dependencies and attached test support jar; executable artifact unchanged |
| `culturehub-notification-worker/src/main/java/cn/culturehub/DeadLetterReplay.java` | Bounded RabbitMQ DLQ replay preserving IDs |
| `culturehub-notification-worker/src/main/java/cn/culturehub/NotificationConsumer.java` | Remove Kafka listener annotation only; DB transaction unchanged |
| `culturehub-notification-worker/src/main/java/cn/culturehub/RabbitNotificationListener.java` | Manual ACK, bounded recovery and isolation adapter |
| `culturehub-notification-worker/src/main/java/cn/culturehub/WorkerConfiguration.java` | Manual ACK/prefetch/recovery configuration |
| `culturehub-notification-worker/src/main/resources/application.yml` | Broker connection and listener properties |
| `culturehub-notification-worker/src/test/java/cn/culturehub/ManualAckTest.java` | 受控 ACK/重试协议单测；不冒充真实 Broker 验收 |
| `culturehub-notification-worker/src/test/java/cn/culturehub/WorkerKafkaTest.java` | Replaced only after user-approved exact baseline archival |
| `culturehub-notification-worker/src/test/java/cn/culturehub/WorkerRabbitTest.java` | Original four Worker scenarios plus exhaustion/lost-ack/replay |
| `culturehub-notification-worker/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` | Subclass mocking for transport tests without Java agent |
| `docs/ai-and-startup.md` | Startup infrastructure name only; AI implementation unchanged |
| `docs/design.md` | Current message guarantees, no business state-machine changes |
| `docs/evidence/rabbitmq-baseline.json` | 消息迁移专用文档、真实验收证据或复现工具 |
| `docs/evidence/rabbitmq-draft-tests.json` | 消息迁移专用文档、真实验收证据或复现工具 |
| `docs/evidence/rabbitmq-junit-cases.json` | 消息迁移专用文档、真实验收证据或复现工具 |
| `docs/evidence/rabbitmq-standalone-acceptance.json` | 消息迁移专用文档、真实验收证据或复现工具 |
| `docs/evidence/rabbitmq-tests.json` | 消息迁移专用文档、真实验收证据或复现工具 |
| `docs/learning-and-demo.md` | Add historical-baseline notice and link current message startup guide |
| `docs/migration-baseline/PublisherIntegrationTest.java.txt` | 用户已授权原样归档的旧测试及校验记录 |
| `docs/migration-baseline/WorkerKafkaTest.java.txt` | 用户已授权原样归档的旧测试及校验记录 |
| `docs/migration-baseline/kafka-tests.json` | 用户已授权原样归档的旧测试及校验记录 |
| `docs/rabbitmq-diff.md` | 消息迁移专用文档、真实验收证据或复现工具 |
| `docs/rabbitmq-local-guide.md` | 消息迁移专用文档、真实验收证据或复现工具 |
| `docs/rabbitmq-migration.md` | 消息迁移专用文档、真实验收证据或复现工具 |
| `docs/rabbitmq-test-report.md` | 消息迁移专用文档、真实验收证据或复现工具 |
| `scripts/collect-verification.py` | Collect new message test names; not executed against old evidence |
| `scripts/package-source.py` | Messaging name in share README only |
| `scripts/rabbitmq/acceptance.py` | 消息迁移专用文档、真实验收证据或复现工具 |
| `scripts/rabbitmq/local-broker.ps1` | 消息迁移专用文档、真实验收证据或复现工具 |
| `scripts/rabbitmq/prepare-local-broker.py` | 消息迁移专用文档、真实验收证据或复现工具 |
| `scripts/rabbitmq/report.py` | 消息迁移专用文档、真实验收证据或复现工具 |

业务目录唯一实现差异为消息发布器 Publisher.java。预约、取消、FIFO、签到、评价、统计、Redis、MyBatis、AI Graph/HITL/SSE 和前端均无实现修改。
Publisher 相关测试将“单条发送失败抛出异常”改为“批次继续且失败事件保留”，对应本轮明确要求；原数据库状态、重试次数、容量和幂等断言仍保留。
根目录生成的 ZIP 作为本地交付物排除，不提交到 Git。未合并 main。
