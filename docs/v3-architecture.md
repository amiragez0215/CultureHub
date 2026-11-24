# CultureHub V3 实现架构

实现依据：V2.1 升级实施规格、P0 审计和差异清单。开发基线为 561d4382cc39cf45dd02fe79d3e3a4c05c0441a8，分支 codex/culturehub-v3。V1.0 业务冻结文档与 Git 提交分开登记，P0 原记录保留。

## 服务与数据边界

| 进程 | 职责 | 数据库 |
|---|---|---|
| Gateway | JWT、外部服务头清理、Sentinel、Nacos 路由 | 无业务库 |
| Core | 认证、场馆、活动、场次、参与资格、签到、评价、统计；Booking Worker 与 Stream Relay | core_db |
| Notification | 可靠消费、站内通知、已读状态；Core 校验账户有效性 | notification_db |
| Knowledge | MinIO 上传、文档版本、异步解析/Embedding/Chroma 入库、权限过滤检索 | knowledge_db |
| Seata TC | 跨服务组合 AT 提交、回滚与事务恢复 | 持久化事务会话卷 |
| AI | 现有15节点 Graph、Redis记忆和Checkpoint、规划及明确授权的预约提案 | ai_db |
| Partner A/B | 模拟独立场馆，各自的资格、Hold、Worker、事件 | partner_db_a / partner_db_b |

各服务启动时验证数据库与账号身份；DML账号禁止跨库。部署初始化由专用管理脚本执行DDL，不给应用账号DDL权限。culturehub-service-runtime 仅用于保留P1边界验收夹具，完整V3部署用真实AI、Knowledge替换探针进程。

## 单场次预约与取消

HTTP请求 → MySQL登记booking_request → Redis Lua原子预占/幂等/XADD → Stream Relay → RabbitMQ Confirm/Return成功 → XACK → Booking Worker本地事务 → 资格/历史/库存与Outbox → 异步库存投影与通知。

MySQL场次锁、条件更新及唯一约束保持最终权威。202表示请求受理，不表示预约成功。用户使用原request_id查询最终结果。迟到的QUEUED更新不能覆盖终态。

候补序号在MySQL场次锁内分配并持久化，入队时读取MySQL中的序号和候补优先信息。Redis保存预占状态、Stream和可重建的候补索引。较早但未落为资格的JOIN参与FIFO判定，锁住的队首不被跳过。普通取消在MySQL事务提交后立即确认，Outbox异步回补Redis并触发FIFO。

库存丢失时暂停接单；重建要求无未决请求，使用epoch/revision防止旧消息覆盖新投影。XXL-JOB提供恢复与对账handler，同时保留有界本地后台恢复。

## 组合预约

Core 组合 API 持久化授权与 request_id。Redis Stream → RabbitMQ → Booking Worker 触发确认。Worker 为各场次使用 Lua 预留并锁定处理权。同库走 Core 本地事务，跨服务调用单次 `@GlobalTransactional` 方法，通过受信任的同步 HTTP 将同一 XID 传播给 Partner A/B。提交或回滚确定后，持久化收尾任务幂等结算 Redis Hold 并写入 Outbox。

详见 [调用链、状态与部署](v3-seata-at.md)。

## AI迁移与授权

Graph、State和15节点职责沿用AI Round 3；Core在v3-p5不再实例化或暴露AI运行时。AI不直接读取Core表、不连接Chroma，使用封闭Feign工具目录和Knowledge API。用户身份从认证上下文获取，不由模型提供。

选中单场次只保存ai_booking_proposal；明确确认后以稳定request_id调用Core统一异步入口。AI方案选择先在本地事务外预取场次及已有预约，再持锁核对版本，避免跨服务调用占用本地锁。结果未知保持PROCESSING，用原请求恢复。

多活动方案通过AI组合确认API：验证计划归属、版本、时效、场次、衔接和现有预约；本地事务记录group_request_id及部分保留选项；事务外调用 Core 组合 API。重复确认使用原授权；GET只查询不提交。历史接口支持重启、刷新后的恢复。Graph本身没有预约写工具。

## 知识异步入库

上传先校验用户/场馆范围、文件类型和大小，再保存MinIO对象；MySQL事务登记文档、版本、任务和Outbox。Worker执行有界Tika解析、稳定分片、Embedding和向量写入，完整成功后才切active_version。旧版本在新版本失败时保持有效。删除即时从SQL检索范围排除，物理清理由异步任务执行。

检索的向量结果必须再通过MySQL有效版本、删除、过期、可见性和实体范围检查，返回SQL保存的正文与元数据。AI分别进行确定性重排和EvidenceGrade，不用向量正文替代实时库存。

## 可观测性

启用Micrometer Prometheus与OpenTelemetry OTLP。既有trace字段可携带W3C上下文，跨Redis/Stream/RabbitMQ恢复Worker Span；兼容旧UUID字段。指标包括HTTP、Hikari/JVM和只读业务积压Gauge。Collector当前将Trace输出到日志，不宣称具备持久化Trace检索。Grafana提供自动加载的Prometheus数据源和6个面板。

真实证据及未验证范围见v3-test-report.md。Docker完整部署、真实MinIO/Chroma全链路和所有新故障组合仍需对应环境验收，不因代码存在而标记通过。

## 最后定向修订

V3部署支持全新安装。FIFO由MySQL持久化序号决定。Redis库存与候补索引在MySQL事务提交后更新，并可按现有对账流程重建。
