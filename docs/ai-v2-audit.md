# AI V2 源码审计与实施记录

基线：7d112761696fcaea8c2350aa0a4dd374cd0cf60d（RabbitMQ 已验收版本）。独立分支 codex/ai-guide-v2；V1 分支不覆盖。审计日期 2026-11-07。

实际依赖：Java17、Boot3.5.7、Spring AI1.1.2、Alibaba Graph Core1.1.2.0、Vue3.5.22/Vite6.1.0；无新增基础设施。

实际现状：AiGuideGraph 的五节点使用 Map 传递状态，内存 Conversation 与20条 ChatMemory；GuideQueryService 返回真实 JOIN 场次，固定20条；AiBookingProposalService 独立事务领取并调用 Booking，稳定 request_id 可查询恢复；SSE 是 POST+JWT，4个有界工作线程；知识目录为 Markdown，重建只有 source/visibility。AI2/3 共用 AiService 基础设施。

版本核查通过本地依赖 JAR 的 javap 完成：RedisSaver.Builder 需要 RedissonClient，项目只有 Lettuce。因此不添加第二客户端。V2 若采用 Redis 节点快照，会明确其恢复边界，不冒称原生 RedisSaver；MySQL 仍是授权和执行权威。

实施顺序：结构化 State/有界只读业务工具及测试 → 独立知识索引/检索/重排/证据分级及测试 → 确定性组合与验证/有限修复 → 持久化与前端/SSE/提案对接 → 原回归及 Challenge Set。

保护范围：Booking、ParticipationService、Cache、Outbox Publisher/Events、签到与统计、contracts 和通知 Worker、原性能材料。原 tests 不删除、不放宽；原 AI Graph 留作 V1 回归与对照。

主要风险：时间窗口语义从“开始时间”扩展为完整活动区间时必须区分版本；旧候选失效与 PROCESSING 提案竞争需持久化串行化，不能凭 Redis 状态授予预约；现有知识没有年龄数值规则，不能从亲子标签推断年龄资格；真实 Chroma/Embedding 是否可用需要单独实测。数据库迁移不得清空旧库。
