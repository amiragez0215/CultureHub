# CultureHub V3 P0 审计报告

> 本文记录 Seata AT 切换前的版本。当前跨服务实现及验证见 [Seata AT 迁移说明](v3-seata-at.md)。


审计日期为 2026-11-11。目标规格为 CultureHub V3 修订版，文档版本 V2.1。受审源码为 `codex/ai-guide-v2-round3` 的 `561d4382cc39cf45dd02fe79d3e3a4c05c0441a8`。本次交付待审核，差异及逐项验收条件见 [V3 P0 差异清单](v3-p0-gap-matrix.md)。

## 1. 基线核实

| 对象 | 核实结果 |
| --- | --- |
| V1.0 正式业务冻结 | 按 D04，以用户确认的《CultureHub_V1.0_完整项目需求与技术实现规格说明书.docx》（V1.0 实施规格整合版，2026-10-31）为准。仓库中的 `CultureHub_V1_Complete_Specification.docx` 的封面名称、版本和日期均与其相符，正文包含五个 Java 模块、三项 Spring AI 功能及原 Kafka 规格。该英文文件名副本的 SHA-256 为 `bdbe6c28d3e75225f65cba6f572ff2f4e1078afa1cd239053a5136c287005802`。未取得同名原件作独立字节对照。 |
| V1.0 初始源码 | `5f9790a33ba0538fe9ce32403459e5c39bd2d19a` 是完整本地历史的根提交，无父提交，时间 2026-11-02 13:22:02 +08:00，说明为 `Initial release of CultureHub V1.0`。GitHub 可按 SHA 读取，本地及远端公开 tag 查询均无记录。该提交内的规格副本与当前跟踪文件均为 Git blob `0882742ffa347b6df805be628eb6ecb3961a7518`。据此登记为可追溯的初始 V1.0 源码基线。独立发布制品及正式验收签署尚无可核实证据。 |
| 迁移前稳定参照 | `5e5c509f590776caf25e0751543990b979fba9e8` 是仓库当前记录的 `main` 和本地跟踪引用 `origin/main`，也是 RabbitMQ 迁移报告采用的迁移前基线。包含 MyBatis 查询改造及原同步预约 HTTP 实测。 |
| RabbitMQ 业务 | `7d112761696fcaea8c2350aa0a4dd374cd0cf60d`，2026-11-04。该提交正是 `git merge-base 7d11276 HEAD` 的结果。 |
| AI Round 3 | `561d4382cc39cf45dd02fe79d3e3a4c05c0441a8`，2026-11-08。在 RabbitMQ 迁移提交之后增加六个提交。建议此完整 Git 树作为 V3 实施起点。 |
| V3 目标文档 | 工作区 `CultureHub_V3_升级实施规格说明书_修订版.docx`，文档版本 V2.1，SHA-256 `8f1911a80f7f1507ac639eb0c09136ad03fdf1269992b9a8aac5e81cbbb6ea0e`。D01–D05 单独登记，V1 规则仍用于保护存量业务和回归。 |

初始提交已包含 Kafka、Outbox 和独立通知 Worker，符合冻结文档当时的消息架构。可核实的初始源码身份与逐项业务符合性分别登记。例如，冻结规格要求 UUID 风格请求标识，现有入口接受 8–80 位受限字符，数据库按 `(user_id, request_id)` 唯一。V3 的请求登记及冲突契约需要显式兼容旧客户端和历史数据。 [初始源码提交](https://github.com/amiragez0215/CultureHub/commit/5f9790a33ba0538fe9ce32403459e5c39bd2d19a) · [入口](../culturehub-business/src/main/java/cn/culturehub/Booking.java#L18)

### 交付压缩包与源码的对应关系

| 资料 | SHA-256 | 完整性和对应关系 |
| --- | --- | --- |
| `CultureHub-AI-V2.0-round3-source-no-data.zip` | `d65597affd447bfb1a8ec1050c116280546163e96ab6516d22dd1bdfabab6378` | ZIP CRC 校验通过。声明源码为 `561d438`。与 Git 共有 160 个文件，统一行尾后 156 个相同，四个差异是 `.env.example`、`README.md`、`compose.yaml`、`deploy/mysql/03-xxl-job.sql`。另有 `SOURCE-VERSION.txt`，省略了测试目录数据、演示 SQL 等文件。 |
| `CultureHub-RabbitMQ-migration-draft.zip` | `3c7e2005630ba34adbbc7c4c6c5eaac34924bd4644762d861cf14fa429cb8ad4` | ZIP CRC 校验通过。与 `7d11276` 共有 207 个文件，统一行尾后 15 个仍有内容差异，包括 Publisher、RabbitTransport、Compose、集成测试和历史证据。压缩包缺少迁移终版的部分测试和恢复资产。 |

实施基线以完整 Git 提交为准。分享包用于查看源码时，应保留其裁剪范围说明。

## 2. Java、API 与数据盘点

现状为 Maven 单仓库、两个 Java 进程和一个 Vue 3 前端。Java 17、Spring Boot 3.5.7、Spring AI 1.1.2、Spring AI Alibaba Graph 1.1.2.0、MyBatis-Plus 3.5.14、XXL-JOB 3.1.1 来自现有 POM。Compose 固定 MySQL 8.0.44、Redis 7.4.6、RabbitMQ 4.1.4、Chroma 1.0.0 和 XXL-JOB Admin 3.1.1。 [父 POM](../pom.xml) · [业务 POM](../culturehub-business/pom.xml) · [Compose](../compose.yaml)

| 单元 | 实际职责与边界 |
| --- | --- |
| `culturehub-contracts` | `Event` 通知消息 DTO 与白名单、共享 `RabbitTopology`、`RabbitTransport` 的 Confirm/Return 判定。 |
| `culturehub-business` | 五个 Java 业务域和 AI 领域实现，随 API 一起启动。目录与列表使用 MyBatis-Plus Mapper 和 XML JOIN/动态条件；预约和部分业务事务使用同一数据源上的 JdbcTemplate。GuideGraphV2 的只读工具仍通过进程内 Java 查询 MySQL。 |
| `culturehub-api` | Spring Boot HTTP、Spring Security/JWT、Controller、全局异常处理、演示数据、SSE、XXL-JOB Executor。库存变更与 Outbox 在 Core 事务，Outbox Publisher 由 `outboxPublisher` handler 调用。 |
| `culturehub-notification-worker` | 独立 Spring Boot 进程，消费通知及重试队列。通知落库后 ACK，通过数据库唯一约束对重复事件收敛。与 API 共用 `culturehub` 数据库，API 直接查询和更新该库的通知已读状态。 |
| `web` | Vue 3、Vite、Nginx 代理；用户端、管理员端、AI SSE、候补与凭证交互。现有回归也覆盖保留的管理员 AI2/AI3 交互。 |

### HTTP 入口

源码共声明 55 个 HTTP 方法映射。业务控制器 39 个，认证 3 个，AI 主控制器 10 个，SSE 1 个，会话及保存计划读取 2 个。

| 业务面 | 已存在的主要接口（统一前缀 `/api/v1`） | V3 需要对接的行为 |
| --- | --- | --- |
| 账号、目录与授权 | `/auth/{register,login,me}`，`/venues`，`/activities`，`/activities/{id}/sessions`，`/admin/users/{id}/venue-grants`，活动/场次发布、管理与取消接口 | 服务边界建立后保留用户可见的目录、分页和 RBAC，Partner 演示活动只进专用入口。 |
| 预约、候补与状态 | `POST /sessions/{id}/reservations`、`/sessions/{id}/waitlist`、`/participations/{id}/confirm`、`/participations/{id}/cancel`。已有 `GET /me/participations/by-request/{request}` 查询已落库资格 | 前三类写入切到统一异步入口，新增可查询请求受理和终态的 API。普通取消仍以 MySQL 提交即时确认。 |
| 签到、通知与评价 | `/participations/{id}/{credential,checkin-status}`、`/admin/check-ins`、`/me/notifications`、`/me/notifications/{id}/read`、`/sessions/{id}/my-review`、`/admin/statistics` | 签到和统计继续按数据库权威事实。通知库拆分后由通知服务拥有读、已读写入及权限。 |
| AI 与知识 | 会话、保存计划、消息及 SSE、候选选择、提案状态、确认/拒绝、管理员策划/复盘与知识重建。确认已由 Java 校验身份、版本和有效期 | GuideGraphV2 通过 Core 只读 API 和 Knowledge 检索 API。确认 API 接异步请求或受控组合入口，分别呈现已授权、已受理和业务终态。 |

现有授权由入口 JWT、`Access` 查用户/角色/场馆授权及 Service 所有权校验组合完成。内部服务鉴权和跨服务上下文传递尚无运行实现。 [REST 控制器](../culturehub-api/src/main/java/cn/culturehub/ApiController.java) · [JWT](../culturehub-api/src/main/java/cn/culturehub/SecurityConfiguration.java) · [场馆与用户权限](../culturehub-business/src/main/java/cn/culturehub/Access.java) · [AI 接口](../culturehub-api/src/main/java/cn/culturehub/AiController.java)

### MySQL、Redis 与调度

- **MySQL**。业务主库 `culturehub`，另有 XXL-JOB Admin 所需数据。主库包含用户/角色/场馆授权、活动/场次、`participation` / `participation_history`、签到、评价、`outbox`、站内通知、AI 报告、提案、会话、计划与计划项。场次 CHECK 约束和带受影响行数判定的条件更新共同限制容量。活跃资格生成列及 `uq_active`、`uq_request`、`uq_queue` 分别保护单个有效资格、用户范围请求重放和场次候补序号；`uq_notification` 保护通知消费幂等。当前 AI 表有指向 Core 用户、场次/资格的外键。初始 DDL、手动增量 SQL 和演示初始化可单独识别。现有环境升级应用增量脚本，首装才使用 `01-schema.sql`。 [DDL](../deploy/mysql/01-schema.sql) · [增量目录](../deploy/migrations/)
- **Redis**。`Cache.limit` 用 Lua 对用户+场次和场次入口限流（5 次/2 秒、100 次/秒）。Redis 异常时拒绝新预约，已提交的同 request_id 先读 MySQL 回放。场次展示、场次统计、签到状态采用短 TTL Cache-Aside，批量展示 MGET 和 Pipeline 回填，业务事务提交后尽力删除。AI 使用带用户/会话范围的聊天快照、Graph Checkpoint 和知识索引代际登记。当前没有库存账本、Hold、预约 Redis Stream、PEL Relay 或候补 ZSET。现有两键限流 Lua 无同槽 Hash Tag，未来若采用 Redis Cluster，应与库存和展示缓存的键路由一起验证。 [限流及缓存](../culturehub-business/src/main/java/cn/culturehub/Cache.java) · [批量场次](../culturehub-business/src/main/java/cn/culturehub/PublicSessions.java) · [会话与计划持久化](../culturehub-business/src/main/java/cn/culturehub/ai/guide/GuidePersistence.java)
- **XXL-JOB**。现有 handler 为 `candidateMaintenance`、`sessionReminders`、`outboxPublisher`，分别处理候补维护、场次提醒和通知发布。V3 的请求补登记、Hold 处理、库存对账、Saga 恢复和知识任务恢复需要单独验收。 [任务入口](../culturehub-business/src/main/java/cn/culturehub/Jobs.java)

## 3. RabbitMQ 与 AI Round 3 的集成判断

**源码已集成在同一提交树。** 在 `7d11276..561d438` 比较中，`culturehub-contracts`、`culturehub-notification-worker`、`Publisher`、`Events`、`Booking`、`ParticipationService`、`compose.yaml` 均无内容差异。API 主配置差异是默认启用 GuideGraphV2。DDL 差异是新增会话、计划和计划项三张表及对应增量脚本。当前活动代码和运行依赖只配置 RabbitMQ，Kafka 文件为历史归档。 [Round 3 说明](ai-v2-round3.md) · [RabbitMQ 迁移记录](rabbitmq-test-report.md)

**当前通知链路**。直接预约在 MySQL 同一事务写资格、历史和 Outbox。Publisher 按 `attempt_count` 条件领取，30 秒租约可恢复，在数据库事务外进行 Broker I/O。只有 ACK 且无 Return 才标记 `PUBLISHED`，逐条退避且单条可路由失败后继续批次。通知 Worker 在成功提交后手动 ACK，或确认重试/DLQ 副本后 ACK。重试最多三次，延时由 Worker 执行，通知消费依靠 `source_event_id + user_id + type` 数据库唯一约束。API readiness 只含自身状态、DB 和 Redis，Broker 状态仍在全局健康端点呈现。Compose 仅 Worker 将 Broker healthy 作为依赖。 [Publisher](../culturehub-business/src/main/java/cn/culturehub/Publisher.java) · [共享拓扑](../culturehub-contracts/src/main/java/cn/culturehub/RabbitTopology.java) · [通知监听器](../culturehub-notification-worker/src/main/java/cn/culturehub/RabbitNotificationListener.java)

**当前 AI 链路**。GuideGraphV2 保留 15 节点、只读工具、证据验证、规划版本和 SSE。选择场次只创建提案，用户确认后 `AiBookingProposalService` 调 `Booking.create()`，同步写入同一 Core 事务及 Outbox。现有真实通知测试与提案测试分别覆盖各段，当前 P0 未重新执行一次同时包含模型、浏览器、HTTP、RabbitMQ 的全链路。现有多个活动的规划可以逐项生成提案，V3 同库整组确认和跨服务 Saga 仍待开发。 [Graph](../culturehub-business/src/main/java/cn/culturehub/ai/guide/GuideGraphV2.java) · [提案授权与执行](../culturehub-business/src/main/java/cn/culturehub/AiBookingProposalService.java#L150) · [真实 Broker 历史用例](rabbitmq-test-report.md#测试结果)

**队列和消息契约**。当前为 `culturehub.notification.events.v1` Topic Exchange、`notification.*` 路由、通知/重试/DLQ 三个 durable Quorum Queue。消息为 `Event(eventId,eventType,eventVersion,aggregateId,recipientUserId,occurredAt,data)`，五类通知业务事件由 DTO 校验。V3 的 booking ingress、领域事件、知识任务和 Saga 命令/事件需要独立声明，带公共版本、追踪与业务幂等标识。迁移宜保留旧通知 event_id 和可重放性，通过版本化映射逐步切换。 [Event](../culturehub-contracts/src/main/java/cn/culturehub/Event.java) · [拓扑](../culturehub-contracts/src/main/java/cn/culturehub/RabbitTopology.java)

## 4. 三项并发风险及可复现验收

### R1 请求状态迁移与重复提交

**现状证据**。`Booking.create` 先读已提交资格、再限流、同步执行 `ParticipationService.createTx`。事务内锁场次并校验重放，`ApiController` 的按请求查询只读取已成功持久化的参与资格。AI 的 `PENDING → PROCESSING → SUCCEEDED/FAILED/…` 是授权提案状态，已有租约、执行令牌与按请求恢复。仓库没有 V3 `booking_request` 或 Redis 预约入队，故尚无法触发 Worker 抢先提交后被 API 回写的 V3 竞争。 [同步入口](../culturehub-business/src/main/java/cn/culturehub/Booking.java#L18) · [事务](../culturehub-business/src/main/java/cn/culturehub/ParticipationService.java#L103) · [提案恢复](../culturehub-business/src/main/java/cn/culturehub/AiBookingProposalService.java#L117)

**P2 候选机制**。持久化请求归属和操作指纹，入队重试复用同一请求和结果。用条件状态迁移区分待受理、在途和终态，晚到的登记只补允许补写的元数据，不能把 `PROCESSING` 或终态回退为 `QUEUED`。Worker 的请求终态、资格、历史及 Outbox 共用本地事务。GET 从已认证用户可见的权威请求记录返回结果。P1 先确定旧的用户范围幂等键与 V3 全局 `request_id UNIQUE` 的映射，以及旧资格查询与新请求状态端点的并存契约。

**验收 CH06 / CH07**。用屏障暂停 API 在 XADD 后、登记 QUEUED 前，放行 Worker 先处理，分别制造成功、业务拒绝和处理中的结果。再放行 API 和恢复任务重放；断言状态不倒退、一个逻辑请求只生成一份资格/历史/业务 Outbox，跨用户查询被拒绝。另测 `RECEIVED` 后未入队、已入队但响应丢失、Broker 重复投递以及消费提交后 ACK 丢失。

### R2 Hold 到期与 Booking Worker 确认竞争

**现状证据**。当前 `OFFERED` 是持久化参与资格，直接占用 MySQL 容量。确认和超时均先锁场次再锁资格，锁后取 DB 时间，确认条件 `now < deadline`，过期条件 `now >= deadline`。重复过期调用会重读状态。现有 `expiredConfirmationRace` 在并发开始前就将截止时间设为过去，验证过期确认拒绝和单次释放。V3 Redis Hold、到期回补和 Worker 并行裁决尚无实现。 [确认与超时](../culturehub-business/src/main/java/cn/culturehub/ParticipationService.java#L187) · [原确认竞争回归](../culturehub-business/src/test/java/cn/culturehub/BookingIntegrationTest.java#L342)

**P2 候选机制**。先约定持久化 Hold 标识和处理版本，确认与过期走同一个权威状态裁决并维持一致的行锁顺序。确认事务提交后发出确认事实，确定拒绝/过期后才通过幂等动作回补 Redis。单纯 Key 到期或外部超时不足以证明可加库存。释放、续租、库存重建用 Hold 标识和版本区分旧操作；结果未知时保留恢复入口，暂停可能造成超额的分配。默认 TTL 及续租约束按队列等待 P99、处理时限和有限重试余量验证。

**验收 CH07 / CH09**。分别让 Worker 与超时任务先取得裁决权，覆盖恰好到期、确认事务回滚、MySQL 提交后 Redis 写失败、重复释放消息以及服务重启。检查终态单向、无负库存、已确认场次不被重复回补、合法释放最终仅一次，并核对持久资格/有效 Hold 与 Redis 的可分配量。最终处理机制由 P2 在真实 DB、Redis 与 Broker 上验证。

### R3 FIFO 消息乱序、锁住队首与取消后的空窗

**现状证据**。当前在场次行锁内生成 `last_waitlist_seq`，取消或过期事务直接选择最早 WAITING 行并授予 OFFERED，直接预约会检查 WAITING 优先。RabbitMQ 投递只决定通知到达时机。候补没有 `waitlist.join` Stream、独立消费落库、SeatReleased 或补位 Worker，所以现有代码不存在 V3 所描述的队列消息乱序处理路径。V3 从同步补位改为 Outbox 异步后，队首消息尚未落库或正在处理中时会出现可被后到请求利用的空窗。 [候补入队](../culturehub-business/src/main/java/cn/culturehub/ParticipationService.java#L129) · [取消与补位](../culturehub-business/src/main/java/cn/culturehub/ParticipationService.java#L169)

**P2 候选机制**。按场次建立可证明的持久顺序推进边界，例如对已完成入队或明确终止的序号维护连续处理位置。缺少较早记录时保留待处理，并使用 Stream/PEL 或登记记录恢复。MySQL 按最早有效序号裁决，禁止跳过锁住的队首。释放事件重复、乱序时须再查权威资格和当前场次状态。直接预约的预留闸门及 MySQL 最终校验都要覆盖「已受理但尚未落库」的优先候补，报名截止后只释放。实现前验证 `INCR`、Hold 写入或 XADD 局部出错不会形成无法识别的序号缺口或半套状态。

**验收 CH08**。构造 A 的早序号消息被延迟且 B 先落库，再释放一个名额并并发新直约。分别测试 A 暂时被锁、A 已取消可跳过、SeatReleased 重投、队首机会到期以及报名窗口关闭。断言未终止的 A 优先，B 只在 A 完成或明确终止后推进，容量和通知去重成立。对照 V2.1 还需覆盖单场次一个有效 PROMOTING/OFFERED 处理位、默认 5 分钟可配置且仍被报名截止与场次开始截断。现实现使用固定 10 分钟、循环填充空位。

## 5. 构建、回归与证据范围

Windows，Java 17.0.6、Maven 3.6.3、Node 22.21.0。默认构建使用已缓存 Maven 依赖。默认测试统计包括受环境条件禁用的用例，继承套件的同名场景也各计一次。

| 本轮执行 | 结果 |
| --- | --- |
| `mvn -o -B -ntp -s settings.xml clean verify`（设置工作区 Java 临时目录） | BUILD SUCCESS。383 个 JUnit 计数，100 通过、0 失败、0 错误、283 跳过。 |
| `npm.cmd run test:ui`（`web`） | 通过模板、中文标签、SSE 边界、Vue 交互及历史管理功能回归。 |
| `npm.cmd run build`（`web`） | 首次沙箱中 esbuild 子进程 `spawn EPERM`。以相同命令在获准的运行环境复跑，Vite 6.1.0 构建通过。 |
| 专用 MySQL、Redis、RabbitMQ 的现有集成回归 | BUILD SUCCESS。383 个 JUnit 计数，285 通过、0 失败、0 错误、98 跳过。独立回环实例为 MySQL 8.0.27、Redis 7.4.9、单节点 RabbitMQ 4.1.4 / Erlang 27.3.4.1。 |

本地真实集成结果包括 `LocalBookingTest` 15、`ProposalLocalTest` 30、`CatalogLocalTest` 27、`OptimizationLocalTest` 23、`GuideV2LocalTest` 27，以及 `OutboxRabbitLocalTest` 21、`RabbitEndToEndLocalTest` 17、`RabbitReliabilityLocalTest` 25 项通过。Rabbit 相关计数包含继承的业务用例。实际覆盖正常通知、重复投递、数据库提交后 ACK 前进程退出、延迟重试与死信、Return、单条失败的批次继续以及生产 Quorum 拓扑重复声明。预约回归覆盖最后一个名额、同请求重放、候补序号并发、取消后补位、现有 OFFERED 超时竞争、事务回滚和签到取消竞争。

本地集成复跑使用新建测试库和独立 RabbitMQ vhost。初次测试环境引导的 MySQL 连接参数被 PowerShell 拆分，改为带引号的长参数后建立测试库并运行上述 Maven 测试。原 MySQL 实例及演示数据未用于这批会清表的测试。

```powershell
# 先按 docs/rabbitmq-local-guide.md 准备专用测试实例。
# 本轮端口分别为 MySQL 13306、Redis 16379、RabbitMQ 5673。
$env:RABBIT_VHOST = 'culturehub_p0_audit'
mvn -o -B -ntp -s settings.xml verify '-Djava.io.tmpdir=D:/agent/CultureHub/.tools/p0-audit/tmp' '-Dculturehub.localTest=true' '-Dculturehub.rabbitLocalTest=true' '-Drabbit.port=5673'
```

本轮跳过 96 个 Docker/Testcontainers 用例和 2 个需要 Chroma/Embedding 的本地用例。Docker Compose、真实模型调用、独立 HTTP 进程的 Broker 停机/重启、XXL-JOB Admin 调度、多节点 Quorum 故障及 HTTP 压测本轮未重跑。V3 新增链路当前处于方案与验收设计阶段。Maven 原始日志位于本机 `.tools/p0-audit/maven.log`、`.tools/p0-audit/maven-local.log`，各套件明细位于各模块 `target/surefire-reports/`。

历史证据独立列账。RabbitMQ 迁移验收记录为 298 计数、202 通过、96 跳过，含真实 Broker 启停、重投、死信、发送批次恢复。Round 3 历史记录为 383 计数、287 通过、96 跳过，包含真实 Chroma + ONNX Embedding 和另录的 DeepSeek HTTP/SSE 证据。历史同步 HTTP 压测及原始记录位于 `scripts/perf/`、`docs/performance-report.md`、`docs/evidence/performance*.json`。本轮用其作为旧入口比较依据，V3 的 202 受理 RPS 与最终确认 TPS 将分别采集。 [RabbitMQ 历史报告](rabbitmq-test-report.md) · [Round 3 原始统计](evidence/ai-v2-round3-test-summary.json) · [原压测报告](performance-report.md)

## 6. 后续实施计划与审核点

| 阶段 | 计划及退出条件 |
| --- | --- |
| P0 审核 | 审核本报告、差异清单和 R1–R3 验收设计。源码树保留在原 Round 3 分支。审核后从 `561d438` 创建独立 V3 分支并登记完整 SHA，作为 P1 开工前的基线手续。 |
| P1 服务骨架 | 锁定与 Boot 3.5.7 和 Graph 1.1.2.0 兼容的 Gateway/Nacos/OpenFeign/Sentinel 组合，先做最小联通和可回滚构建。划定 Core、Knowledge、AI、Orchestrator、Notification 的服务边界、Schema 和账号，定义 JWT/服务身份、超时、错误及 trace 传播。先完成独立启动、越权拒绝、数据库权限隔离和存量 API 路由回归。 |
| P2 统一异步预约 | 定稿请求状态、Hold 裁决、FIFO 顺序及消息契约。实现幂等登记、Redis Lua/Stream、Relay、Booking Worker、可查询结果、普通取消 Outbox、补位和对账。分阶段迁移同一受理入口，最终验收所有写入口无同步旁路。以 CH02–CH09 和 R1–R3 的真实并发/故障断言为门槛。 |
| P3 知识入库 | 先实现场馆权限、MinIO 文件入库、任务和版本模型，再对接解析、切块、Embedding、Chroma、检索与回收。复用 Round 3 证据规则，用 CH10–CH12 验证旧版本不中断、跨场馆隔离与删除即时生效。 |
| P4 组合预约 | 同库整组由 Worker 单事务最终确认。跨服务通过 Orchestrator 和独立参与方本地事务、幂等命令/事件、补偿和恢复验证。按 D02 保留用户显式部分保留、人工介入和逐项状态。Partner A/B 受控演示，完成 CH13–CH15。 |
| P5 AI 集成 | 保留 Graph、证据和 SSE 契约，将进程内查询迁到 Core/Knowledge API，确认操作对接统一异步入口和组合流程。保留认证归属、提案版本、稳定请求和未知结果恢复，执行 CH16–CH17。 |
| P6 集成与验收 | 跑完整 CH01–CH19、Compose 首装与增量迁移、故障注入、端到端链路跟踪和真实压测。记录队列积压、待处理项、受理/终态延迟及结果数，分别归档历史 V1/Round 3 和 V3 结果。 |

P1 接口评审应确定三项契约。请求 ID 的用户归属及旧客户端兼容，状态查询如何区分受理、最终资格和授权提案，分库后用户/场馆/场次引用、通知已读与计划授权由哪个服务负责。P2 再根据本报告的竞争窗口确定具体一致性实现。

审计只新增报告和差异清单。业务代码、部署配置、现有测试和历史压测证据的跟踪内容未改动。当前报告用于审阅，P1 尚未启动。
