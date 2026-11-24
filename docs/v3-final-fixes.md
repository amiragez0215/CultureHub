# V3 最后一轮定向修复与验收

> 本文记录 Seata AT 切换前的版本。当前跨服务实现及验证见 [Seata AT 迁移说明](v3-seata-at.md)。


## 修复范围

| 问题 | 修复文件与处理 |
|---|---|
| V1原库与旧通知 | README、v3-start明确仅支持全新安装，不声明culturehub到core_db无损升级。旧库、通知、旧Outbox不复制或删除；新notification_db从空表开始。局部AI复制脚本标为不受支持的开发辅助。 |
| 结果未知恢复 | AiRemoteBooking将连接错误/5xx视为BOOKING_RESULT_UNKNOWN；AiBookingProposalService保持PROCESSING，只用原request_id查询，临时查询故障不改FAILED。适配器与真实MySQL提案测试覆盖延迟完成。 |
| FIFO锁内网络 | BookingLedger.register在MySQL场次锁内分配并持久化序号。AsyncBooking在事务提交后执行Redis入队。先登记的JOIN仍阻止后来的补位或直约。 |
| Gateway路由与限流 | AI路由去重；core、notification、ai、knowledge、combinations均有Sentinel规则。真实Gateway HTTP测试验证各路由429和唯一ID。 |
| 初始化与打包权限 | Git记录00-init.sh为100755；脚本使用LF。自定义MySQL镜像COPY --chmod=0755并清除CRLF，不依赖Windows目录挂载权限。ZIP按Unix0755保存sh、0644保存其他源码，并校验脚本LF。尚未真实执行容器初始化。 |
| Gateway监控与CH18 | 独立9081管理监听器抓取Prometheus，公开业务端口拒绝抓取。Compose中的Gateway实际服务名为api，挂载配置与Prometheus目标修正为api:9081。Trace联调工具扩展为Gateway到Core/Stream/Rabbit/Worker。 |

## 本轮测试

结果以evidence/v3-final-fixes-tests.json及下方最终执行记录为准，不使用修复前数字代替。完整命令保留原测试开关，限制内存不修改业务断言：

```powershell
$env:MAVEN_OPTS='-Xmx256m'
$env:RABBIT_PORT='5673'
$env:RABBIT_VHOST='culturehub_v3p1_audit'
mvn -o -B -ntp -s settings.xml '-DargLine=-Xmx384m -XX:ActiveProcessorCount=2' '-Dculturehub.localTest=true' '-Dculturehub.p2LocalTest=true' '-Dculturehub.rabbitLocalTest=true' '-Drabbit.port=5673' package
npm.cmd --prefix web run test:ui
```

中间失败记录：Gateway测试最初未启用测试环境的指标导出，Prometheus返回404；加AutoConfigureObservability后原200断言通过。新增提案测试最初Mockito默认空Map被当成已有结果，已显式模拟“尚无结果”为null，保留PROCESSING断言重跑。Windows曾报页面文件不足，改为低内存单次执行。

## Docker干净部署与端到端

当前执行环境找不到Docker CLI，也没有远程Docker端点，因此没有实际执行Compose干净部署，不能提供伪造的容器健康状态。证据见evidence/v3-final-fixes-environment.json。

在具备Docker的机器，按v3-start的新安装步骤使用独立项目/空数据卷：

```powershell
python scripts/v3-p6/init-env.py --demo
# .env.v3设置模型、Embedding；要演示外部参与方则设置V3_DEMO_ENABLED=true。
docker compose --env-file .env.v3 -p culturehub-v3-clean-review -f compose.v3.yaml config --quiet
docker compose --env-file .env.v3 -p culturehub-v3-clean-review -f compose.v3.yaml up --build -d --wait
docker compose --env-file .env.v3 -p culturehub-v3-clean-review -f compose.v3.yaml ps -a
docker compose --env-file .env.v3 -p culturehub-v3-clean-review -f compose.v3.yaml logs --no-color mysql core api ai knowledge orchestrator notification partner-a partner-b
```

必须选择从未使用的新项目名；不要把旧V1数据卷挂入，不要为制造“干净环境”删除旧数据。没有healthcheck的服务，running只能说明进程存活，仍需检查业务接口与日志。

| 演示环节 | 本轮Docker验收 |
|---|---|
| 用户注册登录、单场次及最终查询 | 未验收；保留本地真实HTTP证据，不冒充Docker |
| FIFO候补/补位、取消异步回补 | 未验收；本地MySQL/Redis回归另列 |
| 知识上传/入库/检索/版本切换 | 未验收；需要真实MinIO、Chroma、Embedding |
| Core整组预约、三方Saga及失败补偿 | 未验收；既有独立进程及SQL测试不冒充容器 |
| AI真实对话、单场/整组确认 | 未验收；本轮没有真实模型生成质量结论 |
| 通知及全部监控容器 | 未验收；Gateway独立端口与本地OTLP结果另列 |

权限设置、ZIP检查、Compose静态合并均不能替代上述实际部署。仍未覆盖的专门故障矩阵继续沿用v3-test-report.md的明确缺口，不扩展新功能。

## 最终完整回归结果

Maven package BUILD SUCCESS：412次，313通过，0失败，0错误，99跳过。原真实MySQL/Redis/RabbitMQ测试已启用。99跳过包含97个容器条件执行及2个Chroma条件用例；新增提案用例同时存在本地与容器版本，因此跳过数比原基线增加1，不是关闭既有测试。

前端test:ui通过。GatewayRoutesTest两项通过：五路由实际限流、唯一ID、独立管理端口Prometheus。RemoteBoundaryTest六项通过，含远程结果未知恢复。ProposalLocalTest31项通过，含新增原请求延迟完成；BookingP2LocalTest在该轮执行时通过七项。详见逐用例JSON。

## CH18 本轮实际执行

使用最新构建真实启动Gateway与Core，请求经Gateway进入Redis/Stream/RabbitMQ/Worker；接收并解析实际OTLP protobuf，验证Worker Span保留请求提供的Trace ID。Gateway独立19081管理监听器实际抓取到HTTP及JVM指标，Core抓取到业务积压与Hikari指标。结果见evidence/v3-telemetry.json。

这证明经Gateway入口的上下文传递及指标可抓取；没有逐Span断言所有中间服务导出完整性，没有启动Collector/Grafana容器，也没有覆盖Knowledge Trace链路，不标为全平台CH18完全验收。

## 交付校验

完整compose.v3.yaml已重新合并；检查每个服务具有image或build、Gateway实际服务api装载监控配置、Prometheus抓取api:9081、MySQL镜像从Dockerfile.mysql构建。源码压缩包校验CRC、必要文件、00-init.sh的Unix0755执行位和LF行尾。Git索引00-init.sh为100755。Dockerfile还有CRLF清理兜底。

## 候补序号投影修复（2026-11-13）

删除 `BookingRedis` 的 `NEXT_QUEUE`、`syncQueueFloor` 及 `AsyncBooking.enqueue` 中的调用。FIFO 继续使用 MySQL 的 `last_waitlist_seq` 和 `queue_seq`，库存预占、Redis Stream 和候补有序索引继续走原链路。已有 TTL 的旧 Key 自然过期，无 TTL 的残留 Key 也不会影响入队。

使用 MySQL 8.0.27 和 Redis 7.4.9 执行回归。修复前，非数字字符串和 Hash 两种污染都使入队返回 503。修复后，两种情况均验证了 `RECEIVED → QUEUED`、Stream 回执、Worker 写入 `WAITING`、数据库序号与占用人数正确。原有 FIFO 登记、幂等、Hold 到期竞争、Stream 恢复和库存重建用例一并通过。

```powershell
$env:MAVEN_OPTS='-Xmx256m'
mvn -o -B -ntp -s settings.xml '-DargLine=-Xmx384m -XX:ActiveProcessorCount=2' '-Dculturehub.p2LocalTest=true' -pl culturehub-business -am '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=BookingP2LocalTest' test
```

本轮 Maven 构建成功，8 项测试通过，失败、错误和跳过均为 0。测试直接执行 Worker 消费逻辑，本轮未重跑 RabbitMQ 转发、全量测试或 Docker Compose。
