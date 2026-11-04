# RabbitMQ 迁移说明与验收状态

## 当前状态

迁移前稳定提交：`5e5c509f590776caf25e0751543990b979fba9e8`，迁移前工作区干净。迁移分支：`migration/rabbitmq`。2026-11-04 已在本机真实 RabbitMQ 4.1.4、Erlang 27.3.4.1、MySQL 8.0.27、Redis 7.4.9 环境完成链路与故障恢复测试；Docker 未安装，未合并 main。

完整回归：298 次执行、202 次通过、96 次跳过、0 失败、0 错误；真实独立 API/Worker 进程验收 15 项检查通过。测试次数包含继承业务回归，不能解读为 202 个独立消息故障场景。详情见 [验收报告](rabbitmq-test-report.md)、[逐文件差异](rabbitmq-diff.md) 和 evidence/rabbitmq-standalone-acceptance.json。

## 保留的边界

预约/取消/候补/签到/评价/统计/Redis/MyBatis/AI Graph与SSE的核心实现未修改。Event record字段与JSON语义、event_id、有效资格与通知唯一约束、Outbox表结构和外部HTTP接口均保留。NotificationConsumer的解析/校验/事务/唯一冲突处理原样保留，仅移除Kafka注解，交给新的Rabbit监听适配器调用。

Event.TOPIC与Event.DLT公开常量名称为源码兼容保留，值用作Exchange/DLQ逻辑名称，不再代表Kafka资源。没有变更Outbox字段或事件DTO，不需要数据迁移SQL。

## 消息拓扑

|资源|名称|职责|
|---|---|---|
|Topic Exchange|culturehub.notification.events.v1|接收 notification.事件类型|
|主队列|culturehub.notifications.v1|处理通知事件|
|Direct Exchange|culturehub.notifications.recovery.v1|承接retry/dead隔离路由|
|重试队列|culturehub.notifications.v1.retry|持久化有限重试副本|
|隔离队列|culturehub.notification.events.v1.dlt|不可恢复/重试耗尽消息|

Exchange持久化；三个队列均为quorum持久队列，消息为PERSISTENT。RabbitAdmin按连接建立时声明，配置相同时可重复。若同名旧资源类型不兼容，Broker会拒绝声明；应在隔离环境排空并重建对应资源，不通过忽略异常掩盖拓扑冲突。单节点quorum用于开发环境，不声称Broker磁盘丢失或集群容灾保证。

## 发布与Outbox恢复

1. 原业务事务写资格及Outbox，完全不等待Broker。
2. 发布器查询到期PENDING，条件更新现有next_attempt_at与attempt_count抢占30秒租约，更新影响行数必须为1。
3. 网络发送位于数据库事务外。RabbitTemplate启用mandatory、correlated confirm和returns；独立CorrelationData等待最多10秒。ACK且未Return才允许标记PUBLISHED。Spring AMQP保证Returned在Confirm future完成前填充，见[官方说明](https://docs.spring.io/spring-amqp/reference/amqp/template.html)。
4. 成功标记带attempt_count条件，阻止旧租约实例覆盖新一次抢占。单条异常保留PENDING并继续本批其他事件，按10/20/40/80/160/300秒封顶退避重试，记录异常类型与截断后的原因；Broker恢复后同一event_id继续发布。
5. 进程中断后租约到期重新抢占；已入Broker、未标记数据库时仍可能重发，最终靠通知唯一约束幂等。不是端到端Exactly Once。

attempt_count现在表示实际抢占投递次数，包括已送达但标记失败的尝试，不仅是失败次数；它是内部投递元数据，无外部接口依赖。网络单次等待10秒小于30秒租约；长时间进程暂停仍可能使租约过期并出现副本，不能用租约代替消费幂等。未增加分布式锁或Outbox库存表。

修复了Outbox到期比较的精度不一致：候选查询统一current_timestamp(6)，与DATETIME(6)字段一致，使刚提交的到期事件可立即参与发布。该修复仅位于消息发布器。

## 消费、重试和ACK

RabbitNotificationListener接收原始Message，调用原NotificationConsumer。只有TransactionTemplate返回（数据库提交完成）后才basicAck。进程在提交后ACK前中断时会重投，数据库唯一约束再次兜底。

可恢复异常发送到retry，x-retry-attempt递增，最多3次。重试队列与主队列使用同一监听器；重试消息在未ACK状态等待1秒再访问数据库。此处刻意采用小规模Worker延迟，不用TTL/DLX自动中转，避免把默认DLX转发误称为有确认的可靠转移；没有引入延迟插件。延迟期间中断仍由Broker恢复未确认消息。它会占用一个Worker线程，暂时影响其他通知处理吞吐，当前演示规模接受此取舍。

JSON格式/事件校验失败直接隔离，重试耗尽进入DLQ。副本保留原消息正文/messageId，添加重试次数、原队列和异常类型，不写入SQL堆栈或密钥。重试或DLQ副本确认并可路由后才ACK原消息；若转移失败，原消息保持未ACK、关闭通道，容器按5秒恢复间隔重建，不无限即时requeue。重投/确认窗口可能产生副本，每个副本的重试头持久化，但不声称全局只会执行固定次数。

进入DLQ仅表示隔离，不代表通知成功。人工修复原因后可显式启用DeadLetterReplay，每次最多100条；原event_id不变，重试头重置为0，转发确认后才ACK隔离消息。不要对未修复的毒消息反复重放。

## 模块调整理由

- contracts保留事件DTO，新增共用RabbitTransport与RabbitTopology，避免API/Worker两套确认和拓扑定义；依赖由Boot管理的starter-amqp。
- business仅更换Publisher及消息相关依赖/测试，其他业务Service不动。
- Worker新增监听适配器，保留通知数据库幂等实现，迁移重试/DLQ/重放。
- 端到端测试需要同时调用原Booking与真实Worker类，Worker额外挂载test-support分类JAR，仅由business以test作用域依赖。原可执行Worker JAR、启动命令和生产依赖方向保持不变；未新增Maven模块。

原Kafka两份测试按基线提交逐字节归档在migration-baseline，附SHA256与用户授权记录。Publisher原2个用例、Worker原4个用例均有RabbitMQ对应实现并保留原断言，新增路由失败、重试耗尽、丢ACK、人工重放和真实预约全链路测试。旧文档和原压测材料中Kafka字样属于历史基线，不是当前依赖或当前运行配置。


## 本轮收尾修复

- Publisher 单条发送、Confirm/Return 或标记失败不再无条件抛出并终止整批。记录 attempt_count、last_error、next_attempt_at；失败标记写入也失败时保留原30秒领取租约，过期后可再领取。中断信号仍会结束批次。last_error 最长300字符，数据库和公开接口不变。
- 删除 Compose API 对 RabbitMQ service_healthy 的依赖，保留 Worker 依赖及 Broker 自身健康检查。Boot RabbitAdmin 沿用连接监听器延迟声明，RabbitTransport 构造时仅设置 mandatory，无连接/发消息；没有启动 Runner 同步发布 Outbox。API 保留 Rabbit 健康监测，核心 readiness 分组只包含 readinessState/db/redis，避免消息故障错误表示为预约不可用。真实启动测试验证上述 Bean 初始化行为。
- PublisherIntegrationTest 也逐项使用 RabbitTopology 的生产 Declarables，删除单独 Classic 声明。生产、端到端及本地可靠性测试均是相同 Exchange/Quorum Queue/Binding/路由：没有隐式 TTL/DLX，重试与死信由确认后转发的适配器负责。真实管理 API 已核实三队列均为 quorum。
- 原 Kafka 归档保持逐字节不变。原 Publisher 测试把“失败应抛出异常”改为“失败应保留事件且批次继续”，这是本轮明确修复的调度行为；原状态、计数、重试和幂等断言保留并增强。

核心业务事务未迁移或重写，Outbox 仍以 `PUBLISHED` 为现有成功状态，没有擅自改名为 `SENT`。只有 Confirm ACK 且没有 Return 时才尝试写入成功状态。
