# AI 导览与确认预约增量升级

本次在原仓库内升级 AI1，保留五个 Java 业务模块。AI1 使用 Spring AI Alibaba Graph Core 1.1.2.0；Spring AI 统一升级为 1.1.2，Java 17 与 Spring Boot 3.5.7 保持不变。只新增 Graph Core 直接依赖，没有接入 DashScope Starter、Agent Framework、Studio、多 Agent 或新的常驻服务。

## 用户操作

登录后进入智能导览，描述日期、时段、兴趣或人群。条件不够时助手追问；条件足够时显示最多三个真实场次。选择一个场次后，出现包含场馆、时间、操作类型和截止时间的确认卡片。此时没有预约；点击“确认预约”才提交。亲子主题同样是当前账号一个名额，不支持代报家庭成员；年龄是推荐参考，不冒充精确年龄资格认证。

满额场次可单独选择候补并再次确认。直接预约失败不会自动改成候补。新问题或新选择会使旧的未确认方案失效。提交中的方案不能撤回；成功后通过原“我的参与”流程取消。刷新状态只查结果，不自动重新下单。

询问场馆地址、开放时间或规则时，Graph 走场馆咨询分支，不要求先给预约日期。知识库未配置或故障时，页面明确说明资料限制，仍可使用公开场馆资料和真实活动查询，不宣称完整 RAG 已运行。

## 六个阶段与实际代码

| 逻辑阶段 | 实际位置 | 作用 |
|---|---|---|
| IntentExtract | `AiGuideGraph.intent` | ChatClient 结构化抽取完整偏好，结合 20 条 ChatMemory、当前偏好和上轮真实推荐摘要；最新修改覆盖旧条件 |
| InformationRetrieve | `AiGuideGraph.retrieve`、`GuideQueryService.search` | 限定发布、报名窗口、日期时段、分类、人群和当前用户已有资格；最多 20 个场次；Chroma topK=4 |
| ProposalGenerate | `AiGuideGraph.generate`、`ReadTools.availableSessions` | 使用只读工具白名单，生成最多 3 个候选 ID；不执行写操作；工具调用最多 4 次 |
| ProposalVerify | `AiGuideGraph.verify` | ID 必须来自本轮 SQL 结果，再查库验证场次、状态和硬约束；失败最多重查一次 |
| DecisionGate | Graph 的 DecisionGate 节点、`AiGuideGraph.select` | 推荐阶段停在用户选择；选择经验证的候选后，生成一个待确认方案，等待独立确认请求 |
| BookingExecute | `AiBookingProposalService.confirm` | 在认证 HTTP 线程中取得执行权，调用原 `Booking.create`，记录真实结果 |

前五个阶段是真正编译并执行的 StateGraph 节点，具有条件边；`BookingExecute` 按规格允许的受控应用操作方式实现，没有放入异步 Graph 节点，也没有伪称通过内存 Checkpoint 恢复预约。图使用 OverAllState、每次最多 12 次节点执行、整个图最多等待 90 秒；没有无限修复循环。

`spring.ai.tools.throw-exception-on-error=true` 让工具预算错误直接中止，而不是作为普通文本交给模型继续尝试；工具管理器的预算传播有实际 Spring ToolCallingManager 测试。AI2 限制最多 20 个步骤、每步 1—480 分钟，并用 long 求和，防止异常模型输出借整数溢出绕过总时长校验。

Graph 的只读节点使用由 Java 验证后传入的用户标识，限定查询范围，不依赖异步线程中的 SecurityContext。执行写操作时重新从原 Access/SecurityContext 验证身份，不篡改 ThreadLocal，不接受模型生成的 userId。

## 状态与事务

`ai_booking_proposal` 保存随机 UUID、JWT 用户、会话、真实活动和场次、服务端 request_id、DIRECT/WAITLIST 类型、截止时间及结果。默认有效期 5 分钟，可通过 `culture.ai.proposal-ttl-minutes` 配置，限制为 1—30 分钟，且不能晚于报名截止或场次开始。方案不占用名额。

```text
PENDING → PROCESSING → SUCCEEDED
   │          ├────→ FAILED
   │          └────→ PROCESSING（结果待核对，继续使用同一 request_id）
   ├────→ REJECTED
   └────→ EXPIRED
```

创建/替换方案是一个短事务；数据库生成列及唯一约束确保每个用户会话最多一个 PENDING/PROCESSING 方案。确认采用行锁和短事务取得 PENDING→PROCESSING 执行权，保存 60 秒处理租约与执行标识，提交该事务后再调用 Booking。不得把长时间模型请求放在业务事务内。

Booking 先查当前用户同 request_id 的已提交结果，匹配场次与参与类型后直接返回；只有新的预约才进入 Redis Lua 限流，再调用 ParticipationService 的原事务。场次锁、条件更新、有效资格唯一约束、历史和 Outbox 都沿用原实现。

业务成功后单独收敛方案结果。若预约已提交而方案尚未更新，GET 或重试首先按原 request_id 查询已有参与记录，恢复 SUCCEEDED；即使方案过期、会话因重启消失，也可以恢复已提交结果。处理租约到期、原会话仍有效且方案未过期时，可显式使用同一请求恢复；不会换新键补单。结果仍不确定时保留 PROCESSING/BOOKING_RESULT_UNKNOWN，宁可继续核对，也不虚报失败或成功。

仅有内存对话和 ChatMemory，服务重启不会恢复完整聊天上下文。浏览器保存的历史用于展示，不能授权新的确认；会话失效后必须新建对话。已执行业务结果的可恢复性来自 MySQL 方案与原预约记录。若参与资格之后取消，重放展示当前 CANCELLED 状态，不重新报名。

## API

所有路径以 `/api/v1` 开头，AI1 需登录，AI2/AI3 仍按管理角色及场馆范围鉴权。

| 方法和路径 | 请求及结果 |
|---|---|
| POST `/ai/conversations` | 返回本人会话 `{id}`，闲置 30 分钟过期 |
| POST `/ai/conversations/{id}/messages` | `{message}`；返回 type/phase、explanation、candidates、sources、knowledgeNotice、preferences、serverTime、traceId |
| POST `/ai/conversations/{id}/proposals` | `{candidateId,mode:"DIRECT"}` 或 `WAITLIST`；candidateId 为本轮服务端随机标识，不能自报任意场次 |
| GET `/ai/booking-proposals/{id}` | 本人方案权威状态、具体场次、截止时间及参与结果；不提交新预约 |
| POST `/ai/booking-proposals/{id}/confirm` | 空请求体；只使用 URL 中方案 ID，不接受 userId/requestId/成功标记 |
| POST `/ai/booking-proposals/{id}/reject` | 放弃未提交方案；PROCESSING/SUCCEEDED 不会触发实际预约取消 |

消息 type 包括 MESSAGE、CLARIFICATION、RECOMMENDATION、NO_RESULT。方案 type 为 PENDING_CONFIRMATION 或 BOOKING_RESULT。确认完成返回 200，处理中返回 202；失败、拒绝或过期返回 409 及方案状态，可继续通过 GET 查询。归属错误为 403，不存在为 404，模型不可用为 503。

成功确认例：

```json
{"type":"BOOKING_RESULT","status":"SUCCEEDED","people":1,"entryType":"DIRECT","participation":{"id":123,"session_id":45,"status":"RESERVED"}}
```

上述编号仅为接口示例。只有后端实际返回参与记录，页面才显示预约成功；WAITING 只显示候补排队。方案响应不会暴露 request_id、执行令牌、JWT、模型密钥或签到凭证。

主要错误：AI_UNAVAILABLE、AI_FAILED、AI_OUTPUT_INVALID、CONVERSATION_EXPIRED、CONVERSATION_BUSY、PROPOSAL_NOT_FOUND、PROPOSAL_EXPIRED、PROPOSAL_PROCESSING、PROPOSAL_ALREADY_HANDLED、SESSION_FULL、WAITLIST_PRIORITY、ALREADY_PARTICIPATING、REGISTRATION_CLOSED、RATE_LIMITED、RATE_LIMIT_UNAVAILABLE、BOOKING_RESULT_UNKNOWN。

## 数据迁移与启动

新库：按原方式执行 `deploy/mysql/01-schema.sql`，已包含新表。旧库：在正确数据库上执行 `deploy/migrations/20261101-ai-booking-proposal.sql`，可重复执行，不清理业务历史。`scripts/init-demo.ps1` 已加入该迁移。本机演示库已应用迁移。

原四个 Maven 模块与 Vue 启动方式不变。DeepSeek 配置继续从本机 `.env` 读取，未覆盖用户密钥；API 当前 18080、前端 15173。模型关闭时普通业务仍可使用。没有新增 Docker 运行要求。

## 验证与复现

无外部模型的单元测试：

```powershell
mvn -s settings.xml '-Dtest=AiGuideGraphTest,AiBoundaryTest,AiValidationTest,InputValidationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

本机已有隔离 MySQL 13306 与 Redis 16379 时：

```powershell
mvn -s settings.xml '-Dculturehub.localTest=true' package
```

注意：本地集成测试使用并清理专用 `culturehub` 测试库，不是运行中的 `culturehub_rich_demo`。只在隔离开发数据库执行。Docker 版 ProposalContainerTest 等保留，缺少 Docker 时跳过，不算通过。

前端：`cd web` 后运行 `npm run test:ui`、`npm run build`。交互检查使用 Vue 自身渲染器和受控响应，不是实际浏览器 E2E。

真实模型/HTTP 回归必须显式开启，会产生少量模型用量：

```powershell
python scripts/verify-ai-upgrade.py --run-real-model --database culturehub_rich_demo --db-port 13306 --mysql 'D:\mysql\mysqlx\bin\mysql.exe'
```

该脚本只允许专用 `_demo` 库，创建并清理临时用户、活动、方案、预约和 Outbox，验证不确认不预约、过期、拒绝、越权、并发/重复确认、场馆咨询，以及 AI2/AI3 回归。不会读取模型密钥，不自动发布 AI 生成的策划。AI3 回归以当前演示场次 48 为例，其他演示库应选择有真实评价的已结束场次。

实际结果见 `docs/evidence/ai-upgrade-tests.json`、`ai-upgrade-http.json`、`ai-upgrade-ui.txt`。本机已真实调用 DeepSeek；Embedding/Chroma 检索、Docker 全部署、真实 Kafka 故障链路和 XXL-JOB Admin 调度尚未补测。

## 观测与学习路径

日志按 traceId 记录节点、路由、耗时；只读工具记录工具名与次数；确认记录 proposalId、业务码与耗时。不记录密钥、JWT、完整对话和签到令牌。日志、有限执行和测试用例分别承担 Observability、Execution 和 Evaluation Harness 职责，没有额外开发三个通用框架。

建议顺序：`AiController.message → AiGuideGraph.message/intent/retrieve/generate/verify → select → AiBookingProposalService.create/confirm → Booking.create → ParticipationService.createTx → Events.add`。再阅读 `AiGuideGraphTest` 与 `ProposalIntegrationTest`，对应澄清、虚构候选过滤、候补独立确认、并发、响应丢失和提交后恢复。
