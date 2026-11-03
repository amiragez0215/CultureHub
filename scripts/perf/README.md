# 隔离环境 HTTP 性能测试

本工具不连接演示库，只接受名称以 `_perf` 结尾的数据库，HTTP 目标固定为本机 18090。需要 JDK 17、已构建 API、MySQL 8、Redis 和 Python 3.8+。不需要 Docker、AI Key 或 Kafka Worker；通知投递不在本负载测试范围。

## 执行

```powershell
mvn -s settings.xml package
python scripts/perf/run.py prepare --database culturehub_perf --port 13306 --mysql D:/mysql/mysqlx/bin/mysql.exe
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/perf/start.ps1
python scripts/perf/run.py run --runs 3
python scripts/perf/plans.py
```

默认测试用 MySQL root/空密码、Redis 16379。MySQL 非空密码可通过 MYSQL_PWD 提供，start.ps1 读取同一变量。工具参数可调整 MySQL、Redis 端口；plans.py 支持 --mysql、--port。先确保这些端口是隔离测试实例，不要用生产数据。

只复测回滚与初次响应超时后的幂等重试：`python scripts/perf/run.py run --fault-only --output docs/evidence/performance-faults.json`。延迟触发器只存在于专用库，强制首次响应超过50ms客户端期限；清理后同一个request_id重试，校验资格、occupied_count、Outbox各1。该步骤会临时持有测试行锁，不计入普通性能数字。

prepare 每次创建独立前缀的200个用户、1001个活动、约1001个场次和30000条取消状态历史记录，不删除旧数据。用户仅用于测试 JWT，不开放密码登录。JWT 固定测试密钥与 fixture 仅用于隔离 API，token 写在 Git 忽略的 .tools/perf-fixture.json，不能上传。start.ps1 禁用真实模型、演示初始化及任务调度，默认连接池上限保持10，不调低一致性约束。

run 为每个预约批次创建新场次。A：50/100/200客户端并发，各3轮、容量10；B：50人竞争容量1；C：20个相同账号/request_id请求，附极短读超时后重放。另在专用库短暂创建 Outbox INSERT 失败触发器，通过真实 HTTP 验证资格/名额回滚，finally 删除触发器，再以同一 request_id 重试。只有故障注入步骤预期500，其余系统异常单独计数。

每轮校验 occupied_count 等于消耗名额的资格数、不超过容量；查询有效资格重复数及确认 Outbox 数量。客户端超时不证明服务端一定执行到了哪一步，但后续幂等重试仍须返回同一业务结果。

D 包含列表、批量场次、场次统计；串行100次用于受控缓存对照，另对热查询以50个线程发200次请求。冷场次查询每次仅删除当前活动索引键，不 FLUSH Redis；场次键可复用但冷索引强制一次完整列表 SQL 回源。SQL 通过 performance_schema 摘要统计，Redis 通过 INFO 计数，MySQL 锁等待/慢查询通过 GLOBAL STATUS，连接池通过受认证 Actuator metrics 采样。

默认没有关闭 Redis Lua 限流。429 与 SESSION_FULL/其他4xx分别计数；HTTP5xx、连接失败单列。RPS 为整批请求数/批次时间；committedTPS 为确认 Outbox 数/时间，幂等成功响应数不等于新预约数。P95/P99 使用 nearest-rank，不拼接三轮百分位数冒充总体分位数。

结果保存在 docs/evidence/performance.json；原始响应仅含状态、错误码、分类和耗时，不保存令牌。全局状态计数会受到同实例其他客户端与观察者影响；运行时停止其他测试及负载。连接池离散采样会漏掉短时峰值。Python使用短连接，负载端与服务端同机，结果不是生产容量极限。CPU使用率、磁盘负载与完整服务器侧慢日志追踪未采集，不把已有计数器当作完整资源监控。

## k6

提供 burst.js 供已安装 k6 的机器运行，本机未安装时不把 Python 结果标为 k6 结果。每次准备新的空场次，容量按场景设置，然后运行：

```powershell
k6 run -e FIXTURE=../../.tools/perf-fixture.json -e SESSION_ID=新场次编号 -e VUS=50 scripts/perf/burst.js
# 将 VUS 改成100/200；最后名额场景使用容量1的空场次。
k6 run -e SESSION_ID=新场次编号 -e MODE=idempotent -e REQUEST_ID=k6_duplicate_001 -e VUS=20 scripts/perf/burst.js
```

k6 的 per-vu-iterations 不保证所有用户同一微秒到达；Python使用客户端 Barrier。两者均只能证明客户端并发，不能声称全部请求进入了 MySQL。运行 k6 后同样需要 SQL 校验人数、occupied_count、唯一资格与 Outbox，不能只看 HTTP 成功率。

结果比较工具：plans.py 保留原活动 SQL、新 XML 等价 SQL、普通及延迟关联历史分页的 EXPLAIN，分别测量三次，并断言分页结果一致。mysql.exe 启动/连接成本包含在计时内，不以此计算生产性能提升百分比。
