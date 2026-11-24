> 最后一轮定向修复及最新结果见 [修订验收报告](v3-final-fixes.md)。本页此前的406次统计属于修复前基线，不代表最后版本。

> 本文记录 Seata AT 切换前的版本。当前跨服务实现及验证见 [Seata AT 迁移说明](v3-seata-at.md)。


# CultureHub V3 实际验收记录

日期2026-11-12；分支codex/culturehub-v3；开发基线561d4382cc39cf45dd02fe79d3e3a4c05c0441a8。P1–P6主要实现已落在源码中；**代码实现与完整验收分开登记，CH01–CH19不是全部通过状态**。

## 最新执行结果

完整Maven package：406次测试，308通过，0失败，0错误，98跳过。启用了真实MySQL、Redis和RabbitMQ本地测试开关；跳过来自Docker或Chroma条件，不能计作通过。详见evidence/v3-current-tests.json。

本次开发过程中曾出现配置覆盖、DATETIME类型、残留跨库访问、Feign依赖和前端调用参数错误，均在之后修复并重跑。上面是最后一次完整执行结果，不表示中间每轮都成功。

| 单独执行 | 实际结果与边界 |
|---|---|
| P4四进程联调 | 同库两个项目确认；Core+Partner A/B确认；拒绝后全组补偿；局部无效整组释放；幂等；Orchestrator发布前崩溃重启恢复，共六项通过。使用真实MySQL/Redis/RabbitMQ。 |
| P5三进程联调 | 独立AI库、跨用户拒绝、Core关闭AI入口、未配置模型明确503、单场次选择不预约、明确确认经过真实异步链路、重复确认、整组原授权重放及历史恢复通过。计划由测试注入，不是模型生成质量证明。 |
| 知识版本测试 | 真实MySQL验证新版本失败保留旧版、重复消费不重复入库、删除不被重新激活；MinIO与向量写入使用受控响应。不是MinIO/Chroma端到端证明。 |
| Saga状态机测试 | 真实MySQL验证全补偿完成条件、已签到人工干预、明确部分保留及补偿代次乱序。参与方回执使用受控输入，不冒充Broker故障。 |
| 真实Broker停机 | 停止专用5673 Broker后启动Core，HTTP202保留待处理请求；Broker恢复后原请求完成且仅一条资格。见evidence/v3-broker-outage.json。 |
| 真实OTLP与指标 | HTTP Trace上下文经Redis/Stream/RabbitMQ保留到Worker，实际导出至测试OTLP HTTP接收器；抓取到Prometheus业务积压与Hikari指标。没有启动Collector/Grafana容器。见evidence/v3-telemetry.json。 |
| 前端 | Vue模板、中文、SSE和交互回归通过；包含组合明确确认、AI整组选择及账号隔离；Vite生产构建通过。未执行完整浏览器端到端。 |
| HTTP负载 | 50/100/200突发请求，每场容量25，最终占用与有效预约均25，无重复有效资格；200档100个被限流。详见v3-http-performance.md及原始JSON。 |
| Compose | 已合并compose.v3.yaml并检查profile、真实模块镜像、Partner无公开端口、挂载路径和Core无Broker健康依赖；未实际执行Docker Compose。 |

## CH01–CH19 对照

| 编号 | 已有证据 | 尚缺证据 |
|---|---|---|
| CH01 | 全量Java回归与前端构建 | 完整浏览器多服务业务走查 |
| CH02 | 单场次、候补确认和AI确认使用统一异步入口，HTTP受理与终态分离 | 全部外部环境部署复核 |
| CH03 | Redis Lua预占与真实HTTP容量/重复资格校验 | 大规模持续压测 |
| CH04 | 原有真实Broker Confirm/Return回归，Relay确认后XACK实现 | 新Relay专项不可路由故障矩阵 |
| CH05 | 真实Redis PEL接管，真实Broker停机恢复 | 多Relay同时崩溃恢复 |
| CH06 | Worker提交重放不重复资格/事件；旧真实ACK故障回归 | 新Booking Worker进程提交后ACK前强杀 |
| CH07 | 请求过期、持锁提交竞争、幂等回补测试 | 长时间运行故障组合 |
| CH08 | 持久化FIFO、迟到队首、异步释放/确认测试 | 跨多个Relay乱序持续负载 |
| CH09 | 暂停、epoch/revision、库存丢失及重建测试 | Redis全量丢失与多实例同时重建 |
| CH10 | 文件类型/大小和异步解析代码，受控对象真实SQL测试 | 四种格式真实MinIO到Chroma入库 |
| CH11 | 真实MySQL版本失败不切换、删除不重新激活 | 真实向量服务中断与恢复 |
| CH12 | SQL权威过滤、Core场馆权限、实体范围与恶意文档测试 | 新Knowledge真实Chroma权限端到端 |
| CH13 | 同库和三方真实确认 | 全栈部署复核 |
| CH14 | 真全组补偿；MySQL人工干预、部分保留与代次测试 | 对应真实参与方故障注入 |
| CH15 | Orchestrator真实进程重启恢复 | 全部参与方进程恢复组合 |
| CH16 | 独立AI明确授权、原请求恢复及整组链路 | 真实模型生成到确认全流程 |
| CH17 | 独立账号跨库拒绝、AI隔离、用户归属及服务凭证 | 全栈网络隔离复核 |
| CH18 | 真实OTLP和Prometheus抓取 | Gateway/Knowledge整条Trace及Collector/Grafana运行 |
| CH19 | 完整部署代码与静态检查 | Docker干净环境启动验收 |

## 命令与环境

Java17.0.6、MySQL8.0.27、Redis7.4.9、RabbitMQ4.1.4，Windows本机专用夹具13306/16379/5673；P1另有真实Nacos3.0.3证据。服务仅用各自DML账号；测试初始化使用夹具管理账号。

```powershell
$env:RABBIT_PORT='5673'
$env:RABBIT_VHOST='culturehub_v3p1_audit'
mvn -o -B -ntp -s settings.xml '-Dculturehub.localTest=true' '-Dculturehub.p2LocalTest=true' '-Dculturehub.rabbitLocalTest=true' '-Drabbit.port=5673' package
npm.cmd --prefix web run test:ui
npm.cmd --prefix web run build
python scripts/v3-p4/run-local.py
python scripts/v3-p5/run-local.py
python scripts/v3-p6/telemetry-smoke.py
```

-o仅用于已准备依赖缓存的环境，干净环境去掉-o。脚本要求明确的隔离夹具，不自动把开发者任意MySQL改成测试库。Broker故障脚本额外要求V3_FAULT_TEST_CONFIRM=isolated-v3p1-audit。

## 环境限制与结论

Docker未安装；官方MinIO Windows当前下载地址及所试归档地址返回410。没有在本轮使用真实聊天模型，也没有把DeepSeek聊天密钥当成Embedding服务。未执行项目如上登记。

P5独立AI和P6监控已补充，不能继续沿用“尚无AI业务服务/尚无监控代码”的旧阶段结论；也不能反过来把这些实现写成Docker、RAG或真实模型已全部验收。当前仍有明确的专项验收缺口，不能宣称CH01–CH19全部通过。
