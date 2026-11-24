# V3 改造说明与索引

基线561d4382cc39cf45dd02fe79d3e3a4c05c0441a8，独立分支codex/culturehub-v3。V1.0业务文档与Git提交分开登记。P0原始审计与差异清单保留不覆盖。

| 阶段 | 当前源码 |
|---|---|
| P1 | Gateway、Nacos/OpenFeign/Sentinel、独立数据库账号、服务身份与通知独立查询。 |
| P2 | BookingLedger、BookingRedis、BookingPipeline、BookingWorker及Lua；统一异步预约、FIFO、取消释放、对账与XXL-JOB。 |
| P3 | culturehub-knowledge：上传、MinIO、Tika、任务/版本/Outbox、Chroma、SQL权威过滤和管理页面。 |
| P4 | Core 本地组合事务、Seata AT、Partner A/B、持久化 Hold 收尾及受控演示目录。 |
| P5 | culturehub-ai：复用Round3 Graph，远程只读工具/Knowledge；独立会话、计划、提案和整组授权；稳定请求恢复与SSE。 |
| P6 | Micrometer/OTLP、Prometheus/Grafana配置、完整Compose、真实HTTP突发与故障工具、测试报告。 |

[架构、事务和权限边界](v3-architecture.md) · [API协议](v3-api.md) · [启动与迁移](v3-start.md) · [实际测试及缺口](v3-test-report.md) · [真实性能数据](v3-http-performance.md)

核心规则保持：MySQL最终权威；普通取消事务即时确认；Redis投影和FIFO由Outbox异步触发；同库整组统一确认；跨服务补偿未完成不宣称释放；部分保留须明确选择。Kafka旧测试归档、旧业务回归及原HTTP压测数据保留。

最初P1边界夹具和历史报告仍可查阅；完整V3部署已经使用真实AI/Knowledge模块，不能再用探针进程代替业务服务。当前未通过全部CH验收，未执行的项目在测试报告逐项列明。

## 最后定向修订

V3部署支持全新安装。候补序号在MySQL场次事务内分配并持久化。Redis入队在数据库登记提交后执行。旧版 `queue-sequence` Key 不再被业务读取，已有TTL的Key会自然过期。
