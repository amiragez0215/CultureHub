# AI 导览连接中断与剩余名额筛选修复

2026-11-01，用户反馈两轮对话：先询问青少年活动，再表示时间不限、至少剩余3个名额、希望与家人参加。

实际日志定位：需求抽取、查询、排序、Java 场次核验均完成；DecisionGate 额外生成说明时，调用 DeepSeek 的流连接发生 WebClientRequestException / SocketException: Connection reset。旧处理丢弃整轮结果，前端只显示 AI_FAILED。日志可证明连接被重置，不能据此断定是本地网络、代理或供应商哪一方造成，也不是因为用户提到3个人而触发业务拒绝。

本次增量修复：

- 仅在已经核验有有效卡片之后，说明生成发生网络错误或超时，才使用 Java 明确的说明保留真实卡片。尚未核验的结果不按成功返回；主动取消不转为降级成功；未配置密钥仍返回不可用。
- 其他阶段发生连接故障时返回明确 AI_NETWORK_ERROR，避免统一显示无原因的 AI_FAILED。
- Preferences 新增可选 minimumRemaining，查询 SQL 使用 capacity - occupied_count >= ?，核验及选择场次时也复核。只影响查询，不增加多人预约或预留名额。
- 需求抽取明确“都可以”为不限定日期，不重复追问；数据库查询仍限制默认日期范围。
- 最少剩余名额大于1时，Java 回答明确附加“当前账号一次只能预约1个名额，不支持代家人一次预约多人”，不让模型自行解释或改变平台规则。

测试包含受控 Connection reset 后保留卡片、不写预约，保留主动取消/早期超时/无效输出失败处理；真实 MySQL 验证至少3名额筛选，正式预约一人后剩余2则不再匹配。

真实 DeepSeek 两轮联调见 evidence/ai-capacity-http.json：澄清 → 推荐；抽取 minimumRemaining=3，最后一次实测返回卡片查询时剩余20、22、9，未创建参与记录。该次正常网络联调不等于实际注入供应商故障；Connection reset 容错通过受控模型响应测试验证。

本次执行结果见 evidence/ai-connection-fix-tests.json。Docker/Kafka/XXL-JOB/Embedding/Chroma 的既有未验证边界保持不变。
