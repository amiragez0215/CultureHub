# REST 接口约定

根路径 `/api/v1`。JWT 通过 `Authorization: Bearer <token>` 发送。普通成功返回实体、数组或 `{ok:true}`；创建返回 `{id}` 或完整参与资格。错误返回 `{code,message}`，未预期错误增加 `traceId`。Spring Security 的未认证响应使用 HTTP 401。分页活动和参与记录每页 20；通知游标 `before` 每次 50。日期为 Asia/Shanghai ISO 本地时间。

| 分组 | 方法与路径 | 输入及用途 |
|---|---|---|
| Auth | POST `/auth/register`、`/auth/login` | username、password；仅注册普通角色 |
| Auth | GET `/auth/me` | 当前用户与数据库角色 |
| Venue | GET `/venues`、`/admin/me/venues` | 公开资料 / 当前授权场馆 |
| Venue | POST `/admin/venues` | id 可选，name、address、introduction、opening_hours、status |
| Authorization | GET `/admin/users` | 平台管理员查询用户 |
| Authorization | GET `/admin/users/{id}/venue-grants` | 仅平台管理员；读取该用户已有的 `{user_id,venue_ids}`，供场馆名称勾选回填 |
| Authorization | PUT `/admin/users/{id}/venue-grants` | 场馆 ID 数组，替换授权 |
| Activity | GET `/activities` | q、category、venueId、page；venueId=0 表示全部场馆 |
| Activity | GET `/activities/count` | q、category、venueId；返回同一筛选条件的 `{total}` |
| Activity | GET `/activities/{id}`、`/activities/{id}/sessions` | 公开详情与场次 |
| Activity | GET/POST `/admin/activities` | 管理查询 q、venueId、page、status / 保存草稿；编辑时携带 id；status 默认 ACTIVE（未取消），可选 DRAFT、PUBLISHED、CANCELLED、ALL |
| Activity | GET `/admin/activities/count` | q、venueId、status；总数与管理列表使用相同筛选和场馆授权条件 |
| Activity | POST `/admin/activities/{id}/publish`、`/cancel` | 发布草稿场次 / 原子取消整个活动 |
| Session | GET `/admin/activities/{id}/sessions` | 管理场次 |
| Session | POST `/admin/sessions` | activity_id、capacity、registration_start_at/end_at、start_at/end_at、checkin_open_at/close_at；编辑携带 id |
| Session | POST `/admin/sessions/{id}/cancel` | 取消场次及其资格 |
| Participation | POST `/sessions/{id}/reservations`、`/waitlist` | request_id，长度 8—80，重试复用 |
| Participation | GET `/me/participations?page=1` | 本人历史 |
| Participation | GET `/me/participations/count` | 本人历史总条数，用于每页 20 条的总页数计算 |
| Participation | GET `/me/participations/by-request/{requestId}` | 查找成功创建请求原结果 |
| Participation | GET `/participations/{id}` | 本人资格，含 server_time 与 confirm_deadline |
| Participation | POST `/participations/{id}/cancel`、`/confirm` | 针对具体资格操作 |
| CheckIn | GET `/participations/{id}/credential`、`/checkin-status` | 本人凭证 / 缓存展示状态 |
| CheckIn | POST `/admin/check-ins` | session_id、token，返回唯一核销记录 |
| Participation | GET `/admin/sessions/{id}/participations` | 场馆授权内记录，最多 100 |
| Notification | GET `/me/notifications?before=0` | 本人通知游标 |
| Notification | PATCH `/me/notifications/{id}/read` | 幂等设为已读 |
| Review | PUT `/sessions/{id}/my-review` | rating 1—5，content ≤2000；提交或修改 |
| Review | GET `/sessions/{id}/my-review` | 登录用户本人已有评价；无记录时返回默认评分及空内容，用于表单预填 |
| Review | GET `/sessions/{id}/reviews`、`/admin/sessions/{id}/reviews` | 公开简要内容 / 授权管理内容 |
| Statistics | GET `/admin/sessions/{id}/statistics` | 单场次真实指标，短 TTL |
| Statistics | GET `/admin/statistics?venueId=&from=&to=` | 按开始时间选择场次，最多 200，截止不含 |
| AI | POST `/ai/conversations` | 创建本人会话 |
| AI | POST `/ai/conversations/{id}/messages` | message；返回 type/phase、说明、来源和真实场次候选（candidateId） |
| AI | POST `/ai/conversations/{id}/messages/stream` | JWT + POST Fetch SSE；status/delta/proposal/done/error，详见 technical-enhancement.md；不提交预约 |
| AI | POST `/ai/conversations/{id}/proposals` | candidateId、mode=DIRECT/WAITLIST；选择后生成待确认方案，不预约 |
| AI | GET `/ai/booking-proposals/{id}` | 仅本人；读取权威状态，恢复已经提交的结果，不新建预约 |
| AI | POST `/ai/booking-proposals/{id}/confirm` | 独立确认；200 完成、202 处理中、409 终态失败；复用服务端原 request_id |
| AI | POST `/ai/booking-proposals/{id}/reject` | 放弃未提交方案，不能撤回处理中请求或取消已有预约 |
| AI | POST `/admin/ai/activity-plans` | venue_id、theme、minutes、audience、requirements |
| AI | POST `/admin/ai/reports` | session_id 或 activity_id；Java 指标与评价分析 |
| AI | GET `/admin/ai/reports?sessionId=` | 最近一次保存的报告 |
| AI | POST `/admin/ai/knowledge/reindex` | 平台管理员重新导入公开知识 |

预约关键错误包括 `SESSION_FULL`、`WAITLIST_PRIORITY`、`ALREADY_PARTICIPATING`、`REQUEST_ID_REUSED`、`REGISTRATION_CLOSED`、`CONFIRM_EXPIRED`。限流为 429，依赖不可用为 503，越权为 403。模型关闭或未配置返回 `AI_UNAVAILABLE`，不能把它显示成预约失败。

所有管理 Service 都根据实体实际场馆校验授权；公开接口只返回已发布活动。通知是事件发生时事实，确认页面必须重新读取实时资格。
