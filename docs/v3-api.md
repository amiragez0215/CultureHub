# V3 API 与状态约定

所有业务入口经Gateway，Authorization: Bearer JWT。服务内部RPC还需绑定调用者的X-CultureHub-Service和X-CultureHub-Service-Token；Gateway删除用户传入的这些头，用户不能伪装内部服务。身份只取服务端认证上下文。

## 单场次与候补

- POST /api/v1/sessions/{id}/reservations：{"request_id":"稳定且唯一的客户端请求编号"}。
- POST /api/v1/sessions/{id}/waitlist：同样的请求体，候补必须单独选择。
- GET /api/v1/booking-requests/{requestId}：当前用户查询原请求。
- POST /api/v1/participations/{id}/confirm：候补机会明确确认。
- POST /api/v1/participations/{id}/cancel：MySQL取消事务提交即确认，库存投影和FIFO异步。

202为已受理。requestStatus为RECEIVED/QUEUED/PROCESSING时继续查询，不能展示预约成功。CONFIRMED后查看participation的当前状态；REJECTED/EXPIRED等查看errorCode。网络超时后保留同一request_id，不能换新编号重试。request_id参数不一致返回冲突。

## AI与方案

- POST /api/v1/ai/conversations：新建会话。
- GET /api/v1/ai/conversations：当前用户会话。
- POST /api/v1/ai/conversations/{id}/messages：{"message":"周末安排两个文化活动"}。
- POST /api/v1/ai/conversations/{id}/messages/stream：同一请求体，SSE status/delta/done/error协议保留。
- GET /api/v1/ai/conversations/{id}/saved-plan：恢复当前已保存计划。
- POST /api/v1/ai/conversations/{id}/proposals：{"candidateId":"真实候选ID","mode":"DIRECT或WAITLIST"}。只创建待确认提案。
- POST /api/v1/ai/booking-proposals/{id}/confirm：明确确认；处理中可返回202。
- GET /api/v1/ai/booking-proposals/{id}：恢复原提交结果，不创建预约。
- POST /api/v1/ai/booking-proposals/{id}/reject：只能拒绝待确认提案，不能伪造撤回PROCESSING。

无模型时消息接口返回AI_UNAVAILABLE，普通业务不受影响。取消SSE只终止生成，不撤销已提交预约。

## 整组授权与 Seata AT

- POST /api/v1/ai/plans/{planId}/combination：{"version":1,"confirmed":true,"retainPartial":false}。场次来自该用户已验证的持久化计划，客户端不能替换项目。先保存授权和稳定request_id，再调用 Core。
- GET /api/v1/ai/plans/{planId}/combination：只查询原整组授权的结果。resultUnknown=true表示不能确定结果，不能当作失败或生成新请求。
- GET /api/v1/ai/group-requests：恢复已保存的整组授权，包括被后续规划替换的旧计划。
- POST /api/v1/combinations：{"requestId":"稳定编号","confirmed":true,"retainPartial":false,"items":[{"participant":"CORE","sessionId":1}]}，1–3项。
- GET /api/v1/combinations/{id}：当前用户整组结果及逐项状态。
- GET /api/v1/combinations?page=1：自己的历史请求，20条/页并返回total。
- GET /api/v1/combinations/by-request/{requestId}：原请求结果查询。

RECEIVED 表示已登记，PROCESSING 表示正在执行或等待确定数据库事务结果，SETTLING 表示 db_result 已确定并在同步名额。FAILED 且 allReleased=true 才表示整组没有保留名额。MANUAL_INTERVENTION 表示需要核对数据库或收尾状态。retainPartial 默认 false。用户明确授权后，整组回滚和 Hold 释放完成才进入 FALLBACK，按原项目逐项发起独立预约。PARTIALLY_SUCCEEDED 返回实际成功项。相同 requestId 必须保留原授权及项目。

Partner 内部接口位于 `/internal/v1/combination`。Core 使用服务凭据调用 `prepare`、`pin`、`confirm`、`proof`、`settle` 和 `fallback`。`confirm` 接收 `TX_XID` 并在本地分支事务完成前保持绑定。其他操作在全局事务外执行。Gateway 清除外部请求附带的 XID。

## 受控Partner演示

服务端V3_DEMO_ENABLED=true时开放GET /api/v1/demo/combinations/catalog，以及POST /api/v1/demo/combinations。仍要求有效用户JWT；普通活动与AI查询不聚合Partner。目录最多返回每方30个真实可报名场次；某方不可用返回该方UNAVAILABLE，不伪造活动。

## Knowledge

- POST /api/v1/knowledge/documents：multipart，metadata为application/json部分、file为PDF/DOCX/MD/TXT，最大20MB。
- GET /api/v1/knowledge/documents?venueId=...&page=1：经过场馆管理员范围核验。
- GET /api/v1/knowledge/documents/{id}：授权文档信息。
- POST /api/v1/knowledge/documents/{id}/reindex；DELETE同路径：均返回202及持久化jobId。
- GET /api/v1/knowledge/jobs/{id}：任务阶段，不返回执行租约token。
- POST /internal/v1/knowledge/retrieve：仅AI服务凭证+有效用户JWT，query/venueId/activityId/knowledgeType；默认只返回公开有效版本。

scope为GLOBAL/VENUE/ACTIVITY，visibility为PUBLIC/ADMIN，knowledgeType应与Graph的规则需求一致。场馆和活动ID必须匹配，平台全局文档写入要求平台管理员。向量检索命中仍需SQL权威校验，已删、过期、旧版本和错误实体一律排除。
