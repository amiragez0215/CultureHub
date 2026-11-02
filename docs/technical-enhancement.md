# CultureHub 技术增强说明与学习导读

本轮在已有 V1.0 上增加公开场次缓存、批量统计、AI1 SSE 和场馆 CRUD 的 MyBatis-Plus 写入。五个 Java 模块、三个 AI 功能与部署拓扑保留原设计。预约状态机、场次行锁、MySQL 条件更新、唯一约束、Kafka Outbox 与独立确认接口继续使用原实现。

后续修复：已核验场次的流式说明连接中断/超时时，保留可靠卡片；增加 minimumRemaining 查询条件及单人预约明确说明。详见 [AI 连接中断修复](ai-connection-fix.md)，该补充优先于下文原始 error 终止描述。

## 公开场次缓存

入口：`GET /api/v1/activities/{id}/sessions`。代码：PublicSessions、Cache、Management。

| 键 | 内容 | TTL |
|---|---|---|
| `display:activity:{activityId}:sessions` | 按开始时间、ID 排序的公开场次 ID 数组 | 15 秒 |
| `display:session:{sessionId}` | 时间、容量、占用、状态、as_of 等展示字段 | 15 秒 |
| 原 `stats:{sessionId}` / `checkin:{participationId}` | 原统计及签到展示 | 保留原 15 秒 |

每次先从 MySQL 检查父活动仍为 PUBLISHED，父活动取消立即停止公开查询。索引键未命中时，一次 SQL 读取该活动非 DRAFT 场次，Pipeline 执行带 TTL 的 SETEX 回填。索引命中时，一次 GET 取得 ID 数组，再一次 MGET 读取全部场次；部分或全部场次键缺失时，一次 IN SQL 批量回源，返回顺序与索引数组一致。破损 JSON 按未命中处理。

热路径：一条可见性 SQL、一次索引 GET、一次 MGET。冷路径：两条 SQL、一次 Pipeline 回填。原接口直接查询 MySQL，没有虚构旧版本逐个 Redis GET 的基线；缓存主要减少重复读取公开场次行，不能承诺所有场景更快。真实 Redis 测试验证热路径只调用一次 MGET，部分/全失效仍只批量回源一次。

创建/编辑/发布场次、取消活动按需删索引及场次键；预约、取消、补位、确认、超时、签到复用原 Cache.invalidate，新增删除场次展示键。事务提交后删除，回滚不删。Redis 不可用时普通查询回源，删除失败或读写竞争产生的旧展示通过 15 秒 TTL 收敛。未增加双删任务、分布式锁或第二套库存。

展示名额不构成承诺。AI 候选核验、AI 确认、Booking 与 ParticipationService 仍直接读 MySQL；新报名仍使用原 Redis Lua 入口限流。展示缓存故障不会改变资格，限流 Redis 不可用时原有报名降级策略保留。

## SQL 优化与证据

代码：Operations.factsBatch、Operations.dashboard、AiService.activityReport。

| 场景（不含权限查询） | 原实现 | 当前实现 |
|---|---:|---:|
| 单场次统计 | 14 条 SQL | 4 条 SQL |
| 1—200 个场次指标 | 每场次 14 条 | 合计 4 条分组 SQL |
| 非空场馆日期报表，N 个场次 | 14N + 4 条 | 6 条 |

四条 SQL 分别取场次事实、参与状态分组、参与历史分组、评价分组，避免一对多表直接 JOIN 引发重复计数。报表另有场次选择及整体评价 AVG 查询；保留整体 AVG，避免各场次平均分再平均。活动数从已选场次的 activity_id 去重。AI 活动维度复盘复用批量指标，将逐场次读取当前时间改为一次读取。

统计入口使用只读事务，在默认 MySQL REPEATABLE READ 下保持四次查询的读视图；不锁业务行，不将事务延伸到模型调用。保留 RESERVED/CHECKED_IN 正式预约人数、OFFERED 占用、历史成功次数、用户主动取消、候补转换等原口径。最终签到率仅在结束且未取消时生成，无样本为 null。

测试保留旧实现 LegacyFacts，比较候补、确认、进行中、结束、取消的结果，并验证 4/6/14 条 SQL。证据：

- `docs/evidence/technical-sql-before.json`：改动前 SQL、EXPLAIN、规模与客户端耗时。
- `docs/evidence/technical-sql-after.json`：同 SQL 的改动后计划、新批量聚合计划、同一批 20 场次的 280 条/4 条 SQL 实测。
- `scripts/measure-technical-sql.py`：可重复执行的只读测量工具。

本机 MySQL 8.0.27，演示数据很少；耗时包括 mysql.exe 启动和连接成本。不能据此声称生产吞吐、P99 或提升百分比，实际数值以证据文件为准。

用户历史索引由 `ix_user(user_id,created_at,id)` 换为 `ix_user_history(user_id,id)`，对齐 WHERE user_id 和 ORDER BY id DESC。当前小样本优化器仍可能选择 uq_request 并 filesort，没有用 FORCE INDEX 固定计划，不声称已消除排序。索引实际收益需更大数据验证。唯一约束、候补队列索引和锁定 SQL 均保留。

活动列表保留原单条 SQL，没有 Java 循环查库；其相关子查询使用现有关联索引。本轮保留 EXPLAIN，不额外添加活动列表 Redis 缓存。

已有数据库执行可重复迁移，不清空数据；新库 DDL 已更新，init-demo.ps1 已追加迁移。本机演示库及专用测试库已执行：

```sql
SOURCE D:/agent/CultureHub/deploy/migrations/20261101-participation-history-index.sql;
```

不要通过重跑全量建库脚本迁移已有数据。

## AI1 SSE 协议

```http
POST /api/v1/ai/conversations/{id}/messages/stream
Authorization: Bearer <JWT>
Accept: text/event-stream
Content-Type: application/json

{"message":"周六下午有什么非遗体验？"}
```

沿用 Spring MVC SseEmitter，旧 /messages JSON 接口保留。每个流 SSE id 从 1 递增，done 含原 Graph traceId；不自动重连或恢复半个推荐，断线后用户重新提问。

| event | data JSON | 含义 |
|---|---|---|
| status | `{"node":"IntentExtract"}` | CONNECTED、IntentExtract、InformationRetrieve、ProposalGenerate、ProposalVerify、DecisionGate |
| delta | `{"text":"可以体验剪纸。"}` | 真实模型流按句聚合的文本片段 |
| proposal | `{"candidates":[...]}` | Java 核验后的场次卡片，可为空 |
| done | 原 message JSON 返回结构 | explanation、type/phase、candidates、sources、preferences、notice、serverTime、traceId |
| error | `{"code":"AI_TIMEOUT","message":"..."}` | 失败终止，不再发送 done，不提交预约 |

proposal 卡片含 candidateId/activityId/sessionId、真实时间和容量提示，它不是 PENDING 预约确认方案。用户仍经原 /proposals 选择 DIRECT 或 WAITLIST，再经独立 /booking-proposals/{id}/confirm 提交，服务端复核归属/期限/状态/容量并调用原 Booking。SSE 不创建参与资格，不把模型文本当业务成功，未经单独确认不转候补。

需求抽取与排序仍使用结构化输出，不直接流出 JSON 草稿。推荐分支在 ProposalVerify 后，额外执行一次有上限的 ChatClient.stream().content()，基于已核验事实生成自然语言说明；比旧 JSON 接口多一次模型调用。场馆咨询直接流式回答。澄清/无结果只发送完成的简短文本，不伪造打字效果。

先聚合完整句再执行安全校验，覆盖“预”“约成功”等跨 token 表述，因此 token 接收与句片段展示有短暂差异。页面始终提示尚未提交预约，准确时间/名额由 Java 卡片展示。

服务端最多 4 个专用线程、无排队缓冲，满载立即 503 AI_BUSY。通过 DelegatingSecurityContextRunnable 传递已认证身份；归属与参数校验在返回 SSE 头前完成。ASYNC 容器派发允许完成流响应，初始请求仍须 JWT。

模型流空闲超时 45 秒、说明总上限 60 秒、Graph 等待上限 90 秒、MVC 95 秒、前端 100 秒。超时为 AI_TIMEOUT，缺少配置为 AI_UNAVAILABLE。完成/异常/超时/网络写失败均关闭取消信号、清理观察者并尽力中断 Future。已经开始的同步需求抽取/工具调用仍受底层 HTTP 超时约束，无法保证断线立即停止供应商端生成；这些调用没有预约写入能力。

前端采用 POST Fetch + JWT + ReadableStream/TextDecoder，处理 UTF-8 跨字节、CRLF、跨块与多行 data。停止、离开、退出、切换账号、卸载均 abort。旧账号/旧会话响应不写入当前对话；error/提前断流清除本轮可操作卡片。nginx.conf 关闭 API 代理缓冲；本机已通过 Vite 代理收到真实分片。

DeepSeek SSE 与独立确认回归见 `docs/evidence/technical-sse-http.json`，临时用户/活动/场次已清理。它不代表完成 Embedding/Chroma 召回效果验证。

## MyBatis-Plus 迁移清单

| 操作 | 实现与理由 |
|---|---|
| 公开场馆列表 | 保留原 VenueMapper.selectList 和响应字段 |
| 场馆创建 | VenueMapper.insert，AUTO 主键回填 |
| 场馆修改 | selectById + updateById，保留平台权限、缺失 404、长度校验；introduction 显式 NULL 可清空 |
| 活动/场次修改、发布、取消 | 保留 JDBC 行锁、历史约束和状态条件，迁移收益有限 |
| 预约、候补、签到、评价 | 保留 JDBC 条件更新、唯一约束、锁顺序、资格检查和 upsert |
| 批量统计、Outbox、任务 | 手写 SQL 表达分组和并发行为更清楚 |

不添加逻辑删除、通用实体基类或分页插件。真实数据库测试在同一 Spring 事务中执行 MP insert 和 JDBC update，主动抛异常后均回滚；另外覆盖主键回填、NULL 清空及普通用户越权拒绝。

## 复测与学习

```powershell
mvn -s settings.xml clean package '-Dculturehub.localTest=true'
cd web
npm run test:ui
npm run build
```

localTest 会清空隔离实例 localhost:13306/culturehub 的测试表，只能使用专用测试库，不能改成 culturehub_rich_demo 或真实库。默认 Maven 测试不依赖本地端口，Docker 不可用时跳过容器测试。实际结果见 `docs/evidence/technical-validation.json`。

主动执行真实模型联调时才运行，会调用供应商并可能计费；日常测试不调用：

```powershell
python scripts/verify-ai-upgrade.py --run-real-model --stream --database culturehub_rich_demo --db-port 13306 --mysql D:/mysql/mysqlx/bin/mysql.exe --output docs/evidence/technical-sse-http.json
python scripts/measure-technical-sql.py --database culturehub_rich_demo --port 13306 --mysql D:/mysql/mysqlx/bin/mysql.exe
```

学习顺序：PublicSessions 的 MGET/批量回源 → Cache 的 afterCommit → LegacyFacts 与 factsBatch 的 SQL/口径对照 → streamGuide/AiStreamController/AiGuideGraph → 独立确认接口 → Management.venue 与 MP/JDBC 回滚测试。

未运行完整 Docker Compose、真实 Kafka 故障恢复、XXL-JOB Admin 全流程、Embedding/Chroma 检索质量、大规模压测或自动浏览器视觉截图。已执行 Vue 渲染器交互、前端构建和代理 HTTP 联调。
