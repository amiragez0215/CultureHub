# V3 Saga 实际联调记录（2026-11-12）

> 本文记录 Seata AT 切换前的版本。当前跨服务实现及验证见 [Seata AT 迁移说明](v3-seata-at.md)。


基线提交 `561d4382cc39cf45dd02fe79d3e3a4c05c0441a8`，工作分支 `codex/culturehub-v3`。当前为未最终验收的工作区变更。

## 实际执行并通过

脚本：`scripts/v3-p4/run-local.py`。Java 17、MySQL 8.0.27、Redis 7.4.9、RabbitMQ 4.1.4；四个独立 Java 进程。非 Mock 消息链路。

1. 两个 Core 场次在一个参与方 Confirm 事务中确认，最终两条 RESERVED。
2. Core、Partner A、Partner B 独立数据库与队列完成 Reserve/Confirm。
3. Partner B 场次已取消：全部补偿结束后返回 FAILED/allReleased，三个库占用均为零。
4. 同库第二项无效：最终没有有效资格，两个场次总占用为零。
5. 相同 request_id 重复提交：同一个 Saga，仅一条请求登记，最终成功。
6. Orchestrator 写入 Outbox 后、发布前终止进程；重启后原 Saga 自动恢复成功。

每次测试创建隔离演示记录，不删除已有数据。运行需要隔离测试库 core_db、partner_db_a、partner_db_b、saga_db；不允许指向生产环境。

```powershell
$env:V3_TEST_JAVA='你的 java.exe 路径'
$env:V3_TEST_MYSQL='你的 mysql.exe 路径'
python scripts/v3-p4/run-local.py
```

本地夹具约定：MySQL 13306 root 空密码、Redis 16379、RabbitMQ 5673/vhost culturehub_v3p1_audit。凭证保存在忽略的 .tools/v3-p1，不进入源码交付。该脚本是开发验收工具，不是生产部署脚本。

## 补充：真实 MySQL 状态机测试（参与方回执受控）

SagaRecoveryLocalTest 已验证：全部回执前不声明释放、CHECKED_IN进入MANUAL_INTERVENTION并保留逐项状态、明确部分保留及补偿代次隔离。数据库是真实MySQL，回执是受控测试输入，不属于真实Broker故障证明。

## 尚未覆盖的真实故障场景

- 已签到导致无法补偿的 MANUAL_INTERVENTION 和用户明确部分保留。
- 补偿期间参与方故障、多次补偿代次竞态、补偿消息重复及乱序。
- Worker 数据库提交后 ACK 前强制退出。
- Saga 与 Hold 到期、Redis 库存重建同时竞争。
- 完整 Gateway 前端浏览器端到端。

上述路径存在代码处理，不能据此写为已通过验收。P5独立AI和P6监控代码现已补充；完整验收范围以最新v3-test-report.md为准。
