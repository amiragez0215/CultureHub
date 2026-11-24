# CultureHub V3 启动与迁移

## 干净环境

要求Java17、Maven、Node/npm；完整部署另需Docker Compose。当前机器未安装Docker，因此以下容器启动流程已做配置静态检查，但尚未实际验收。

```powershell
python scripts/v3-p6/init-env.py --demo
# 编辑.env.v3：聊天模型和Embedding分别配置；无密钥保持provider=none。
mvn -B -ntp -s settings.xml package
npm.cmd --prefix web ci
npm.cmd --prefix web run build
docker compose --env-file .env.v3 -f compose.v3.yaml config --quiet
docker compose --env-file .env.v3 -f compose.v3.yaml up --build -d
```

前端 http://localhost:8089；Gateway http://localhost:8090；Grafana http://127.0.0.1:3000。内部服务和Partner不发布主机端口。Grafana账号admin，密码在.env.v3；不能用业务测试密码替代中间件密钥。

--demo创建演示账号admin、venueadmin、user，密码123。不带--demo则不创建这些账号。生成脚本不会覆盖已有.env.v3。首次MySQL初始化创建独立库与最小权限账号，并应用P2–P5增量；保留数据卷时初始化不会重新执行。

如需Partner演示，在首次启动前设置V3_DEMO_ENABLED=true。Partner模拟活动只出现在组合预约演示目录，普通活动和AI搜索不会聚合外部场馆。

## AI配置

编辑.env.v3：AI_CHAT_PROVIDER=openai、AI_API_KEY、AI_BASE_URL、AI_CHAT_MODEL。DeepSeek兼容Chat配置默认Base URL为https://api.deepseek.com、model为deepseek-chat。Embedding单独配置AI_EMBEDDING_PROVIDER、AI_EMBEDDING_API_KEY、AI_EMBEDDING_BASE_URL、AI_EMBEDDING_MODEL并开启AI_VECTORSTORE=chroma；聊天密钥不自动代表有Embedding能力。

无聊天模型时普通业务正常启动，AI调用明确返回AI_UNAVAILABLE。未配Embedding/Chroma时知识任务不会伪造成功，RAG按不可用/证据不足处理。

## 支持范围：只支持 V3 全新安装

本轮不支持V1.0原始culturehub数据库到core_db的自动或无损升级，不能直接切换旧数据库连接启动V3。旧库、旧通知和旧Outbox保持原样，不自动复制、不删除。V3的notification_db从空表开始，不展示旧站内通知；不要将旧Broker/Redis/数据库卷混接到新安装。

必须另建V3项目的数据卷及数据库，保留旧环境和备份，避免对旧项目运行down -v。需要保留历史用户、资格、Outbox及通知的真实升级属于未支持项；不要把局部AI复制脚本当作完整迁移方案。scripts/v3-p5/migrate-existing-ai.sql仅是未验收的开发维护辅助，不在本轮支持范围内。

全新安装由初始化镜像创建 Core、Partner A/B 的 undo_log 和组合预约表。已有 V3 环境先按 [Seata AT 切换说明](v3-seata-at.md#部署和切换) 清空旧版在途事务，再执行增量 DDL。应用 DML 账号无需 DDL 权限。

初始化脚本由专用MySQL镜像COPY --chmod=0755放入entrypoint目录，避免Windows挂载权限使脚本被source而未执行。源码ZIP内.sh也保存0755权限。Docker干净初始化仍须真实Docker验收，权限设置与静态检查不等于容器运行通过。

本地无Docker的开发夹具约定MySQL13306、Redis16379、RabbitMQ5673/vhost culturehub_v3p1_audit。scripts/v3-p1/run-local.py及P2/P4/P5/P6脚本可运行隔离联调，但不负责在任意电脑安装数据库。设置V3_TEST_JAVA和V3_TEST_MYSQL指定工具路径。学习入口：BookingLedger、BookingRedis、BookingPipeline、BookingWorker、CombinationBooking、CombinationGlobalTransaction、AiRemoteConfiguration、AiGroupController、KnowledgeJobs。

## 监控与测试

完整Compose自动载入deploy/v3-p6/observability.properties。Prometheus仅在内部网络抓取；Gateway的Compose服务名为api，通过独立9081管理端口采集，公开8080业务监听器不暴露Prometheus。Collector使用有界内存和批量导出，当前Trace输出到Collector日志；Grafana提供HTTP延迟、积压、连接池及JVM面板。

测试命令和实际结果见v3-test-report.md，压测见v3-http-performance.md。Docker、模型和向量存储的未执行项目在那里单独登记。

重新生成完整Compose仅需安装scripts/v3-p6/requirements-tooling.txt并运行merge-compose.py；已提供的compose.v3.yaml可直接使用，无需Python运行时参与业务部署。
