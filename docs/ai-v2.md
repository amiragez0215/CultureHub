# CultureHub AI V2：文化活动规划与智能预约

从 RabbitMQ 验收基线 `7d112761696fcaea8c2350aa0a4dd374cd0cf60d` 开始，独立 `codex/ai-guide-v2`。原 V1 仍可用。Java17、Spring AI1.1.2、Alibaba Graph Core1.1.2.0，未更换依赖或消息系统。源码审计见 [ai-v2-audit.md](ai-v2-audit.md)，实际结果见 [ai-v2-test-report.md](ai-v2-test-report.md)。

已有数据库先执行 `deploy/migrations/20261107-ai-guide-v2.sql`，可重复执行，仅增加三个规划表。新库由 `deploy/mysql/01-schema.sql` 创建同样的表。V2 分支默认 `AI_GUIDE_V2_ENABLED=true`；设 `false` 可运行原导览对照，不回滚表或数据。沿用现有 `.env` 模型配置。Vue 的“智能导览”进入规划；“恢复最近规划”按当前用户查询服务端保存结果。AI2/3 从管理员导航撤下，V2 下旧策划/报告 API 返回410；原实现与测试保留，知识重建仍可用。

## 节点与路由

`GuideGraphV2` 使用实际 `StateGraph` 条件边。Business Tool 与 RAG 交界处只传真实实体 ID 和信息需求，不在工具执行内调用向量库。

| 节点 | 主要输入 → 输出 | 调用 | 成功／失败去向 |
|---|---|---|---|
| IntentExtract | 认证请求、历史条件 → IntentPatch 合并后的需求 | ChatClient，1次；简单终止无需模型 | 需求规划；澄清／取消结束 |
| InformationNeedPlan | 意图与硬软条件 → 事实/知识/计算需求与依赖 | Java | 业务工具；全局知识跳过业务查询 |
| ToolPlan | 需求 → 白名单调用 | GuideTools | ToolExecute |
| ToolExecute | 调用与认证 owner → 事实快照/明确工具状态 | GuideQueryService | ToolCheck；结果失败或空集合分别记录 |
| ToolCheck | 场次、本人参与、字段 → 完整性/实体范围 | Java | 检索或 Gate；空结果终止；失败修复 |
| RetrievalPlan | 问题、真实场馆/活动 ID → 范围化查询 | Java 改写、拆分、补查扩展 | Retrieve |
| Retrieve | 检索计划 → 合法文档、知识链状态 | Chroma VectorStore | Rerank；故障保留 UNAVAILABLE 路径 |
| Rerank | 合法召回片段 → 二次排序及分数 | 字符双字集合重合率 | EvidenceGrade |
| EvidenceGrade | 范围、问题、排序 → 来源与四种证据状态 | Java，年龄区间和保守冲突判断 | InformationGate |
| InformationGate | ToolCheck、证据、必需规则 → 放行或阻断 | Java | 场馆问答直接生成；活动优化；缺证补查或停止 |
| CandidateOptimize | 真实场次与用户已有参与 → 最多5组可行组合 | Java 有界枚举 | 生成；无组合有限修复，不降硬数量 |
| ProposalGenerate | 可行组合/证据 → 结构化 Proposal | ChatClient structured response | 校验；格式失败回修复 |
| ProposalVerifier | Proposal 与事实 → 结构化错误定位 | Java | DecisionGate；失败定位至生成或优化 |
| RepairRouter | 首处错误与剩余预算 → 最早修复目标 | Java | ToolPlan/Execute、RetrievalPlan、Optimize、Generate；耗尽结束 |
| DecisionGate | 已验证方案 → 可展示可选择，不授权 | Java | 结束；由独立 HTTP API 开启提案确认 |

工具预算12、模型调用4、单轮等待90秒，业务补查最多2，检索/优化/生成各最多1次重试。真实查询成功或空结果可在同轮工具补查中复用；只改文案且快照30秒内时可复用事实。日期/场馆/活动变化重新查询。没有 RetrievalCheck。简单句“取消/结束”等停止本轮规划，不撤销已提交预约。

## 数据权威与检查点

- `GuideState` 的 Identity 由 `Access.user()`、服务端 UUID 和 MySQL 时钟产生；模型从未接收可执行的 owner 参数。`IntentPatch` 与结构化 `Proposal` 是模型候选，不能改写身份、库存、活动时间、来源或预约结果。
- `ToolResult`、场次/已有参与 DTO 由 Java 查询产生；`Evidence` 保留版本与来源；`Grade` 不是库存或年龄认证。`Verification` 提供字段、受影响场次和修复目标。Graph 框架只承载一个 `guide` 类型化领域值。
- Redis 短期聊天记录按账号与会话隔离，最近20轮，TTL30分钟。多轮提示以存下的结构化条件为主，不把全部聊天文本送回模型。节点快照单独保存，用于续谈。
- `LettuceGuideSaver` 实现实际版本的 `BaseCheckpointSaver` SPI，注册到 Graph CompileConfig；使用现有 Lettuce，非 Redisson RedisSaver。`GuideSerializer` 保留嵌套 Record 类型，修复默认克隆器把列表元素转成 Map 的兼容问题。检查点按认证用户、会话、轮次分键，最近32帧，TTL30分钟。内部游标恢复在真实 Redis 测试中验证，不重复 Intent；没有把原生恢复当授权，也没有对浏览器开放任意节点跳转。
- MySQL 的 `ai_guide_conversation` 提供归属、30分钟有效期、并发租约和递增版本；`ai_activity_plan`/`ai_activity_plan_item` 保存通过验证的方案与选择 ID（计划10分钟有效）；`ai_booking_proposal` 仍是预约授权和执行权威。Redis 丢失可恢复 MySQL 已保存规划和提案；Redis 检查点不可用时新规划失败关闭。

## 只读工具与知识

`SEARCH_SESSIONS` 调原 `GuideQueryService.search` 的有界重载（默认60，最大120）；V1 原20条入口保留。`SESSION_DETAILS` 调原 `current` 且过滤未公开记录。`VENUE_INFORMATION` 调原 `venues`。`MY_ACTIVE_BOOKINGS` 按服务端 owner JOIN 当前有效的 OFFERED、RESERVED、CHECKED_IN 时间窗；超过200条失败关闭，不截断后假装无冲突。V2 候选与已有预约批量查询有5秒语句超时。没有动态 SQL 语句入口、外部 URL 或写入工具。

`knowledge/manifest.json` 声明公开文件、版本及 GLOBAL/VENUE/ACTIVITY 作用域；场馆和活动通过 MySQL 公共对象解析，不假设固定 ID。重建维护稳定 documentId/chunkId、source、visibility、knowledgeType、active、可选 expiresOn。管理员 `POST /api/v1/admin/ai/knowledge/reindex` 仍需平台角色。召回过滤类型和实体，返回后核对已登记文件、具体切片正文和元数据，去重及控制长度。重建在索引写入前验证全部清单，Chroma 删除与写入仍不是跨调用事务。

重排使用 query/document 字符双字集合交集 / query 双字集合大小，过滤正确实体后排序，不用原向量分数冒充二次重排。EvidenceGrade 区分 SUPPORTED/INSUFFICIENT/CONFLICTING/UNAVAILABLE，年龄要求只有明确且覆盖用户自述年龄的范围才可支持；亲子标签不算证据。算法保守，可能把语义一致但词面不同的文档判为不足，也不能解析所有复杂规则。公开文本不具有指令权。

## 预约安全

SSE 保留 status/delta/proposal/done/error。只流式发送已核验的文字分块，模型原始 JSON 不直接上屏。选择已验证候选后，Java 检查归属、计划版本和有效期，重读场次与已有预约，调用原提案服务创建 PENDING；截止时间不超过计划期限。选择不是预约。

DIRECT 与 WAITLIST 分开，用户明确确认后才走 `AiBookingProposalService.confirm → Booking.create`。不同场次各有独立提案/请求 ID；不提供跨场次原子批量或代约。改计划只使 PENDING 失效，与原 MySQL 提案领取条件排序；PROCESSING 继续原请求 ID 查结果，不伪造取消。确认前再读现有预约以发现已提交的新冲突。原 Redis Lua、名额行锁/条件更新、唯一约束、FIFO、Outbox/RabbitMQ 均保持。

已知边界：确认前的跨场次时间检查是读取快照；普通预约业务未引入全渠道跨场次互斥锁，因此恰好同时从独立入口写入不同场次时，不能把这个规划校验当数据库级时间排他约束。未过期检查点能恢复内部游标，超时必须重新规划；浏览器恢复的是已保存规划和待确认提案。关闭 SSE 不撤回已提交业务结果。
