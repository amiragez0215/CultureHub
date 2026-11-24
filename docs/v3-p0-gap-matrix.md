# CultureHub V3 P0 差异清单

> 本文记录 Seata AT 切换前的版本。当前跨服务实现及验证见 [Seata AT 迁移说明](v3-seata-at.md)。


对照目标为 V3 修订版规格 V2.1，受审提交为 `561d4382cc39cf45dd02fe79d3e3a4c05c0441a8`。本文记录现状与目标之间的差异及交付门槛。实际测试计数、证据来源和 R1–R3 并发用例见 [P0 审计报告](v3-p0-audit.md)。

## 架构与功能差异

| ID | V2.1 目标 | 当前可核实的实现 | 差距与阶段 |
| --- | --- | --- | --- |
| G01 | D01、D04 分别登记业务文档与发布提交 | V1 初始 Git 根提交、文档 blob、RabbitMQ 迁移与 Round 3 祖先关系均可复核 | 正式冻结原件字节对照、独立发布制品和验收签署未取得。以用户确认的文档规则和完整 Round 3 Git 树分别登记。P0 审核。 |
| G02 | §3、§7 Gateway、Nacos、OpenFeign、Sentinel、独立部署 | Vue/Nginx 指向一个主 API，另有独立通知 Worker。主 API 内含 Core、AI、XXL-JOB Executor | 新服务骨架、发现/调用、入口治理、服务端鉴权与失败语义待建。P1。 |
| G03 | §7、§8 按服务拥有 Schema、账号和实体 | Core、AI、通知共用业务库。AI 计划外键关联 Core 用户/场次，通知表也关联用户。Core 的 `ApiController` 直接访问通知表 | 拆分持久化边界、取消跨库外键耦合并约定引用验证方式、迁移顺序及独立通知读/写授权。P1 先验边界，P5 联调。 |
| G04 | §4.1–4.4 所有预约写入先受理，异步写资格 | 直约、候补加入与确认同步写 MySQL。AI 确认也使用原 `Booking.create`；按 request 查询只查参与资格 | 同一受理链、202 查询契约、授权/受理/资格状态映射和前端刷新待做。普通取消按 D03 保留即时确认。P2、P5。 |
| G05 | §4.2、§4.7 Redis Lua 预留、同槽、幂等和持久请求登记 | Lua 仅限流。Redis 短 TTL 用于展示/统计/签到和 AI 状态；现有限流键无 Cluster Hash Tag | Redis 库存和 Hold、幂等预留、XADD、请求登记、登记恢复、失败边界与库存初始化待实现。P2、R1。 |
| G06 | §4.3 Redis Consumer Group、PEL、RabbitMQ、Booking Worker | 通知 Outbox 已具备 Confirm/Return 和通知手动 ACK。当前无预约 Stream、Relay 或独立 Booking Worker | 新增预留事件到命令传输、ACK 条件、Pending 恢复、Worker Inbox、请求与资格原子裁决。保持通知发布与预约 Relay 各自的恢复边界。P2。 |
| G07 | §4.4、§4.7 MySQL 权威请求状态、Hold 处理及对账 | 现有六状态资格、条件容量更新、用户范围 request_id 去重及 AI 提案执行租约 | `booking_request`、`reservation_hold`、迟到登记和终态查询尚缺。固化锁序、回补幂等、未知状态处理与恢复审计。P2、R1/R2。 |
| G08 | §4.5 FIFO 异步候补、补位预留及超时 | 场次锁内分配持久序号；取消/过期直接在同一事务补位；固定 10 分钟，可能连续填多个空位 | 新的持久顺序和入队缺口恢复、Redis ZSET、SeatReleased、单场次一个有效处理位、5 分钟配置及异步确认。同步并发回归保留为原规则参照。P2、R3。 |
| G09 | D03、§4.6 普通取消即时确认，异步补库存/候补 | 已在事务中取消、扣减 MySQL 占用、记历史并对 RESERVED 发通知 Outbox；在同事务补位。OFFERED 取消不发同一取消通知 | 将普通取消的释放事实与通知事件语义分离。RESERVED/OFFERED 释放应可靠触发库存动作，WAITING 取消不回补。验证过期、场次取消及登记中的请求恢复。P2。 |
| G10 | §4.7、§9 过期、对账、重建与故障可观察 | XXL-JOB 已有候补维护、提醒、Outbox 调度；展示缓存允许失效后回源 | 预约请求恢复、Hold/Offer 对账、冻结接单、重建及修复审计缺失。Redis 故障时读取结果和拒绝新受理应分别验证。P2、P6。 |
| G11 | §5、§8 MinIO、上传、任务、文档版本、对象与向量回收 | 管理员 reindex 入口读取类路径 Markdown + Manifest，在 Java 内生成 Chunk/元数据、向 Chroma 写入，成功后切换 Redis 代际 | 无 MinIO、上传与格式解析、持久化任务、知识 Outbox/Inbox、按文档 active_version、场馆管理员管理端点。重用 Round 3 的来源/权限/证据检查。P3。 |
| G12 | §5、§7 独立授权检索 API | AI 进程内直接调用 Chroma 和知识索引，已有公开可见性、范围和代际过滤 | 由 Knowledge 服务执行 venue/user scope 和版本控制，AI 经服务 API 读取。拒绝越权、失效、删除与旧向量命中。P3、P5。 |
| G13 | D02、§6 同库整组成功或整组释放 | GuideGraphV2 能规划组合，但当前提案选择和执行按单场次 | 组合请求、每项预留关联、稳定锁序、单一 MySQL 事务确认、失败后整组释放以及逐项结果待做。P4。 |
| G14 | D02、D05、§6 跨参与方 Saga 与受控 Partner 演示 | 无 Orchestrator 或 Partner 服务。Round 3 测试语料中的场馆 A/B 只是隔离测试数据 | Saga 状态、步骤、命令/事件、本地事务、恢复、`COMPENSATION_REQUIRED` / `MANUAL_INTERVENTION`、显式部分保留以及专用演示入口待做。测试活动必须排除在普通搜索与 AI 推荐之外。P4。 |
| G15 | §7.2 GuideGraphV2 服务化与授权后接新入口 | 15 节点、只读工具、SSE、证据、会话与计划、提案版本已在当前应用运行路径中接入 | 保留 Graph 与前端交互。将工具和知识调用迁到接口；将提案授权与请求受理/业务终态分别呈现。旧的成功、重放和取消语义需逐项回归。P5。 |
| G16 | §8 RabbitMQ 多领域拓扑、幂等与统一消息信封 | 真实通知 Quorum 队列、通知 DTO、手动 ACK、幂等、有限重试、死信回放已具备 | booking ingress、领域事件、知识任务、Saga 拓扑均缺。增加版本、message_id、trace/correlation 和业务幂等键，保留通知旧消息与重放能力。P1 契约、P2–P4 实施。 |
| G17 | §9.1、§10 可观察性、Compose 与端到端压力验证 | 现有 Actuator 健康与部分 AI trace 日志，Compose 有现行两个 Java 进程。历史同步预约/查询压测和 EXPLAIN 已归档 | OpenTelemetry、Prometheus、Grafana 及跨 Gateway/Stream/Worker/Saga 的追踪未接入。新增 V3 受理与终态分开采样的压测、故障注入和运行说明。P6。 |

## V2.1 验收追踪

这里的「当前证据」只对应当前实现。新的链路验收采用 V2.1 的 CH 编号。

| 编号 | 当前证据或待验证项 | 执行阶段 |
| --- | --- | --- |
| CH01 | 已重跑现有 Java、Vue 与可启动的本地集成测试。V3 拆分后重跑冻结规则、AI 和原接口回归。 | P0、P6 |
| CH02 | 存在同步预约入口。实现后从 HTTP、AI、候补确认和组合调用逐一核对写入路径，普通取消单列。 | P2 |
| CH03 | 已有 MySQL 最后一席、并发与重复请求回归。补充 Redis Lua 库存为 1 和失败半写场景。 | P2 |
| CH04 | 当前 Confirm/Return 只覆盖通知发布。对新增 Relay 验证 ACK 无 Return 才 XACK。 | P2 |
| CH05 | 无预约 Stream 或 PEL。新增 Relay 宕机后认领、重复转发及同请求恢复测试。 | P2 |
| CH06 | 通知重投与已有预约重放有回归。新增 Booking Worker ACK 丢失、R1 迟到状态写入及副作用去重。 | P2 |
| CH07 | 当前 MySQL 约束、资格状态、超时有回归。新增 Redis Hold 到期与提交竞争、幂等回补和权威结果查询。 | P2 |
| CH08 | 当前同步 FIFO 和 OFFERED 超时有回归。新增迟到队首、锁住队首、重复释放和新直约竞争。 | P2 |
| CH09 | 当前可重建展示缓存。新增库存漂移/丢失恢复，验证暂停接单及 Pending/Hold 处理后恢复。 | P2 |
| CH10 | 当前 Markdown Manifest 路径有历史 Chroma 证据。验证 PDF、DOCX、Markdown、TXT 的实际上传与异步入库。 | P3 |
| CH11 | 当前有向量代际切换。新增数据库文档 active_version 失败回退、旧版本可读及恢复用例。 | P3 |
| CH12 | 当前有知识来源和场馆范围验证。新增 API 层跨场馆、未发布、删除和过期版本访问用例。 | P3 |
| CH13 | 当前多活动为规划后逐项确认。验证同库全成/全败、受控 Core + Partner A/B 三方确认。 | P4 |
| CH14 | 当前无跨服务补偿。验证整组全部释放后才报告失败、不可补偿逐项可见和用户显式部分保留。 | P4 |
| CH15 | 当前无 Saga 恢复。验证 Orchestrator 和参与方独立重启、重放及补偿收敛。 | P4 |
| CH16 | 现有 AI 明确确认、候补显式授权、用户隔离有回归。新增异步状态和 Partner 演示活动可见性测试。 | P5 |
| CH17 | 当前两 Java 进程共库。验证各服务独立启动、内部调用授权和数据库账号拒绝跨域访问。 | P1、P5 |
| CH18 | 当前只有局部日志。验证一个 correlation/trace 从 Gateway 穿过 Stream、RabbitMQ、Worker 及 Saga/Knowledge。 | P6 |
| CH19 | 有现行 Compose 定义与历史静态检查。执行 V3 干净环境启动、升级、预约、候补、知识入库与受控 Saga 演示。 | P6 |

## 实施前需要锁定的契约

1. **请求身份与状态**。对齐 V1 `(user_id, request_id)` 与 V3 全局唯一登记，明确旧客户端、状态 URL 和同 request_id 参数冲突。R1 的失败和迟到用例纳入验收。
2. **容量与公平性**。持久化的 Hold/请求终态裁决需覆盖回补、失联、乱序和已受理未落库的候补。P2 设计确定后按 R2/R3 验收。
3. **服务与数据归属**。服务拆分先验账号授权、来源和边界。通知已读、AI 计划引用、场馆规则与实时业务事实须按所有权访问。
4. **消息兼容与部署**。保留旧通知 event_id 和消费者幂等语义。版本化新事件，在隔离测试环境覆盖至少一次投递和恢复，再建立 V3 可复现部署证据。
