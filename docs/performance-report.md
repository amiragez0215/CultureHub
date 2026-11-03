# CultureHub HTTP 性能测试报告

本报告由 scripts/perf/report.py 从真实 HTTP 原始记录生成；工具为 Python stdlib Barrier/urllib，不是 k6。k6 脚本已提供，本机未安装，未执行。

## 环境与方法

- 环境：Windows-10-10.0.19041-SP0；Python 3.8.0；逻辑CPU 16；MySQL 8.0.27。
- 数据库：culturehub_perf；活动/场次/参与历史记录数：2003 / 2068 / 30387。
- Java 17，Spring Boot 3；API、数据库、Redis、负载端位于同机。Hikari 默认上限10；每个预约场次容量10，最后名额场景容量1。
- 预约每规模3轮；客户端同步屏障发起一次请求，不是持续稳态流量。计时包含线程启动及短连接成本。原Lua限流、场次行锁、条件更新、唯一约束和Outbox事务均开启。
- 执行期间持续运行的其他系统程序未隔离CPU/磁盘；不把本机微基准解释为生产容量。最终记录重新运行，未与Maven/前端构建并行。

## HTTP 实测

|场景|并发|请求数|批次秒|RPS|平均ms|P95ms|P99ms|结果分类|
|---|---:|---:|---:|---:|---:|---:|---:|---|
|A-hot-run1|50|50|0.121|413.0|73.66|101.72|105.85|{"business": 40, "success": 10}|
|A-hot-run1|100|100|0.206|484.8|117.85|170.50|175.89|{"success": 10, "business": 90}|
|A-hot-run1|200|200|0.238|838.6|149.09|192.64|197.79|{"success": 10, "business": 90, "rate_limit": 100}|
|B-last-run1|50|50|0.089|562.0|48.16|72.33|76.09|{"business": 49, "success": 1}|
|C-idempotent-run1|20|20|0.023|878.4|10.91|15.17|16.56|{"rate_limit": 15, "success": 5}|
|A-hot-run2|50|50|0.161|311.3|97.51|140.02|144.74|{"success": 10, "business": 40}|
|A-hot-run2|100|100|0.173|578.3|99.34|141.05|146.82|{"success": 10, "business": 90}|
|A-hot-run2|200|200|0.250|800.3|154.50|202.72|208.93|{"success": 10, "business": 90, "rate_limit": 100}|
|B-last-run2|50|50|0.077|652.6|40.52|59.77|62.18|{"business": 49, "success": 1}|
|C-idempotent-run2|20|20|0.019|1067.8|9.31|12.95|12.97|{"success": 5, "rate_limit": 15}|
|A-hot-run3|50|50|0.105|477.3|65.85|89.03|92.58|{"business": 40, "success": 10}|
|A-hot-run3|100|100|0.208|480.5|119.29|170.67|177.87|{"success": 10, "business": 90}|
|A-hot-run3|200|200|0.228|878.9|141.60|183.22|189.38|{"success": 10, "business": 90, "rate_limit": 100}|
|B-last-run3|50|50|0.086|579.0|45.79|68.84|71.02|{"business": 49, "success": 1}|
|C-idempotent-run3|20|20|0.019|1036.3|9.05|13.41|13.83|{"rate_limit": 15, "success": 5}|
|D-list|1|100|0.393|254.2|3.92|4.26|27.77|{"success": 100}|
|D-list-parallel|50|200|0.126|1585.3|21.05|30.13|31.81|{"success": 200}|
|D-sessions-warm|1|100|0.343|291.1|3.42|3.94|21.92|{"success": 100}|
|D-sessions-warm-parallel|50|200|0.162|1237.9|27.69|41.11|43.12|{"success": 200}|
|D-sessions-cold|1|100|0.864|115.7|7.95|18.74|28.86|{"success": 100}|
|D-statistics|1|100|0.545|183.6|5.44|7.00|25.49|{"success": 100}|
|D-statistics-parallel|50|200|0.164|1218.6|26.54|38.66|44.05|{"success": 200}|
|D-history-first|1|100|0.395|252.9|3.94|5.17|24.80|{"success": 100}|
|D-history-first-parallel|50|200|0.151|1321.4|24.74|37.68|39.70|{"success": 200}|
|D-history-deep|1|100|5.218|19.2|52.17|79.15|94.90|{"success": 100}|
|D-history-deep-parallel|50|200|2.430|82.3|540.77|995.58|1184.30|{"success": 200}|

RPS包含成功与拒绝请求。`committedTPS`、每轮状态码、业务错误码和逐请求耗时保存在 evidence/performance.json；幂等重放的多个200不表示多个事务。不同轮百分位不做简单平均。

## 正确性与限流

- A-hot-run1 / 50并发：occupied=10，capacity=10，active=10，重复有效资格=0，确认Outbox=10；业务提交TPS=82.61。
- A-hot-run1 / 100并发：occupied=10，capacity=10，active=10，重复有效资格=0，确认Outbox=10；业务提交TPS=48.48。
- A-hot-run1 / 200并发：occupied=10，capacity=10，active=10，重复有效资格=0，确认Outbox=10；业务提交TPS=41.93。
- B-last-run1 / 50并发：occupied=1，capacity=1，active=1，重复有效资格=0，确认Outbox=1；业务提交TPS=11.24。
- C-idempotent-run1 / 20并发：occupied=1，capacity=1，active=1，重复有效资格=0，确认Outbox=1；业务提交TPS=43.92。
- A-hot-run2 / 50并发：occupied=10，capacity=10，active=10，重复有效资格=0，确认Outbox=10；业务提交TPS=62.26。
- A-hot-run2 / 100并发：occupied=10，capacity=10，active=10，重复有效资格=0，确认Outbox=10；业务提交TPS=57.83。
- A-hot-run2 / 200并发：occupied=10，capacity=10，active=10，重复有效资格=0，确认Outbox=10；业务提交TPS=40.02。
- B-last-run2 / 50并发：occupied=1，capacity=1，active=1，重复有效资格=0，确认Outbox=1；业务提交TPS=13.05。
- C-idempotent-run2 / 20并发：occupied=1，capacity=1，active=1，重复有效资格=0，确认Outbox=1；业务提交TPS=53.39。
- A-hot-run3 / 50并发：occupied=10，capacity=10，active=10，重复有效资格=0，确认Outbox=10；业务提交TPS=95.46。
- A-hot-run3 / 100并发：occupied=10，capacity=10，active=10，重复有效资格=0，确认Outbox=10；业务提交TPS=48.05。
- A-hot-run3 / 200并发：occupied=10，capacity=10，active=10，重复有效资格=0，确认Outbox=10；业务提交TPS=43.95。
- B-last-run3 / 50并发：occupied=1，capacity=1，active=1，重复有效资格=0，确认Outbox=1；业务提交TPS=11.58。
- C-idempotent-run3 / 20并发：occupied=1，capacity=1，active=1，重复有效资格=0，确认Outbox=1；业务提交TPS=51.81。

200并发批次受到入口100次/秒限流；429没有进入资格写事务，不能宣称200个请求同时压到MySQL。其他4xx与超时/连接错误/5xx分别记录，SESSION_FULL是正常业务结果。

受控故障：隔离库临时Outbox失败触发器导致预期500，资格与occupied_count回滚到0；finally删除触发器，同一request_id重试200，名额和确认事件各1。原始C-rollback-retry记录单列，不算成正常场景的意外系统异常。

## 缓存与数据库观测

|场景|SELECT摘要次数差|Redis命令计数差|Redis命中差|Redis未命中差|
|---|---:|---:|---:|---:|
|D-list|300|1|0|0|
|D-sessions-warm|300|201|5100|0|
|D-sessions-cold|400|5601|0|100|
|D-statistics|1100|1|0|0|
|D-history-first|400|1|0|0|
|D-history-deep|400|1|0|0|

SELECT计数来自performance_schema摘要，不是应用逐请求追踪；包含JWT账户/角色查询，Redis计数包含INFO采样与冷场景主动DEL。热场次路径：JWT校验 + 父活动可见性SQL，GET索引 + 一次MGET；冷索引额外一次批量SQL + Pipeline SETEX。不能把DEL的成本隐去后比较冷暖结果。

## 锁等待、慢查询与连接池

- A-hot-run1 / 50并发：InnoDB行锁等待次数增量49，累计等待毫秒增量726，Slow_queries增量0。
- A-hot-run1 / 100并发：InnoDB行锁等待次数增量99，累计等待毫秒增量1313，Slow_queries增量0。
- A-hot-run1 / 200并发：InnoDB行锁等待次数增量99，累计等待毫秒增量1263，Slow_queries增量0。
- A-hot-run2 / 50并发：InnoDB行锁等待次数增量49，累计等待毫秒增量1053，Slow_queries增量0。
- A-hot-run2 / 100并发：InnoDB行锁等待次数增量99，累计等待毫秒增量1082，Slow_queries增量0。
- A-hot-run2 / 200并发：InnoDB行锁等待次数增量99，累计等待毫秒增量1284，Slow_queries增量0。
- A-hot-run3 / 50并发：InnoDB行锁等待次数增量49，累计等待毫秒增量656，Slow_queries增量0。
- A-hot-run3 / 100并发：InnoDB行锁等待次数增量99，累计等待毫秒增量1333，Slow_queries增量0。
- A-hot-run3 / 200并发：InnoDB行锁等待次数增量99，累计等待毫秒增量1198，Slow_queries增量0。
- hikaricp.connections.active：采样15点，观察到的最大值0.0。
- hikaricp.connections.pending：采样15点，观察到的最大值0.0。

这些状态是共享实例的计数增量，观察者也有开销；连接池250ms离散采样可能漏掉毫秒级峰值，不能把采样最大值当真实峰值。Slow_queries受服务器阈值影响，0不证明没有慢SQL。CPU利用率、磁盘负载和完整慢日志未采集。

## SQL计划与优化取舍

|查询|3次客户端耗时ms|
|---|---|
|activity-before|70.83, 70.83, 72.43|
|activity-after|71.53, 72.36, 70.45|
|history-join-0|416.70, 440.02, 414.67|
|history-deferred-0|94.65, 84.09, 90.76|
|history-join-20000|473.69, 424.77, 411.52|
|history-deferred-20000|126.14, 107.61, 106.02|

SQL、EXPLAIN与规模保存在 evidence/orm-query-plans.json。客户端耗时包含mysql.exe启动/连接；新活动动态SQL没有稳定优势，不宣称耗时提升。三万条历史的延迟关联与原JOIN返回行相同，减少分页后关联行数，已在XML采用；保留原offset分页，深偏移仍有扫描成本。

现有 ix_activity_venue、ix_session_activity、ix_user_history 服务对应过滤及排序；不强行新增索引。20261103-query-indexes.sql 仅补齐旧库缺失的同名既有索引，已在性能库重复执行。

四条SQL的批量统计已在前次改造实现，本轮保留；不是本轮新创造的性能提升。参照 technical-enhancement.md 及旧SQL计数回归。

## 结论与局限

本次负载主要证明固定并发下的业务正确性和本机查询表现。保持默认池配置，没有证据支持删除行锁或调大连接池来追求数字。更多用户/独立负载机/持续稳态压测、Kafka通知投递故障、Docker全链路尚未执行。复现见 ../scripts/perf/README.md。

## 额外故障回归

另行执行 --fault-only：专用库延迟触发器使初次请求发生客户端超时，清理后以原request_id重试，资格/名额/确认Outbox各1；Outbox失败导致的事务回滚也重测成功。见 evidence/performance-faults.json。额外故障注入不合并到正常RPS统计。
