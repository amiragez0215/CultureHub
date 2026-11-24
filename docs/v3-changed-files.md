# V3 工作区逐文件变更清单

> 本文记录 Seata AT 切换前的版本。当前跨服务实现及验证见 [Seata AT 迁移说明](v3-seata-at.md)。


基线561d4382cc39cf45dd02fe79d3e3a4c05c0441a8；不包含用户历史压缩包和规格附件。生成时尚未建立最终发布提交。

| 文件 | 用途 |
|---|---|
| `"CultureHub_V3_\345\215\207\347\272\247\345\256\236\346\226\275\350\247\204\346\240\274\350\257\264\346\230\216\344\271\246_\344\277\256\350\256\242\347\211\210.docx"` | 工程依赖、运行配置模板或交付说明 |
| `.dockerignore` | 工程依赖、运行配置模板或交付说明 |
| `.env.v3.example` | 工程依赖、运行配置模板或交付说明 |
| `.gitattributes` | 工程依赖、运行配置模板或交付说明 |
| `.gitignore` | 工程依赖、运行配置模板或交付说明 |
| `README.md` | 工程依赖、运行配置模板或交付说明 |
| `compose.v3-p1.yaml` | 增量迁移、最小权限部署及可观测性配置 |
| `compose.v3-p2.yaml` | 增量迁移、最小权限部署及可观测性配置 |
| `compose.v3-p3.yaml` | 增量迁移、最小权限部署及可观测性配置 |
| `compose.v3-p4.yaml` | 增量迁移、最小权限部署及可观测性配置 |
| `compose.v3-p5.yaml` | 增量迁移、最小权限部署及可观测性配置 |
| `compose.v3-p6.yaml` | 增量迁移、最小权限部署及可观测性配置 |
| `compose.v3.yaml` | 增量迁移、最小权限部署及可观测性配置 |
| `culturehub-ai/pom.xml` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/AiController.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/AiCoreClient.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/AiGroupController.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/AiKnowledgeClient.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/AiOrchestratorClient.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/AiRemoteAccess.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/AiRemoteApplication.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/AiRemoteBooking.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/AiRemoteConfiguration.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/AiRemoteKnowledge.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/AiRemoteProposals.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/AiRemoteQueries.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/AiStreamController.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/ErrorAdvice.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/GuidePlanController.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/java/cn/culturehub/ai/guide/AiServicePersistence.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/main/resources/application.yml` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/test/java/cn/culturehub/RemoteBoundaryTest.java` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-ai/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` | 独立AI服务、远程工具/知识边界、计划授权及验证 |
| `culturehub-api/pom.xml` | Core业务入口异步化及受鉴权服务边界 |
| `culturehub-api/src/main/java/cn/culturehub/AiBusinessQueryController.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-api/src/main/java/cn/culturehub/AiController.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-api/src/main/java/cn/culturehub/AiStreamController.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-api/src/main/java/cn/culturehub/ApiController.java` | Core业务入口异步化及受鉴权服务边界 |
| `culturehub-api/src/main/java/cn/culturehub/CoreAiIsolation.java` | Core业务入口异步化及受鉴权服务边界 |
| `culturehub-api/src/main/java/cn/culturehub/CoreServiceBoundary.java` | Core业务入口异步化及受鉴权服务边界 |
| `culturehub-api/src/main/java/cn/culturehub/GuidePlanController.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-api/src/main/java/cn/culturehub/KnowledgeScopeController.java` | Core业务入口异步化及受鉴权服务边界 |
| `culturehub-api/src/main/java/cn/culturehub/SecurityConfiguration.java` | Core业务入口异步化及受鉴权服务边界 |
| `culturehub-api/src/main/resources/application-v3-p1.yml` | Core业务入口异步化及受鉴权服务边界 |
| `culturehub-api/src/main/resources/application-v3-p2.yml` | Core业务入口异步化及受鉴权服务边界 |
| `culturehub-api/src/main/resources/application-v3-partner.yml` | Core业务入口异步化及受鉴权服务边界 |
| `culturehub-api/src/main/resources/application.yml` | Core业务入口异步化及受鉴权服务边界 |
| `culturehub-api/src/test/java/cn/culturehub/AiBusinessQueryTest.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-api/src/test/java/cn/culturehub/CoreBoundaryAuthorizationTest.java` | Core业务入口异步化及受鉴权服务边界 |
| `culturehub-business/src/main/java/cn/culturehub/AiBookingProposalService.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-business/src/main/java/cn/culturehub/AiGuideGraph.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-business/src/main/java/cn/culturehub/AiService.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-business/src/main/java/cn/culturehub/AsyncBooking.java` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-business/src/main/java/cn/culturehub/Booking.java` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-business/src/main/java/cn/culturehub/BookingLedger.java` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-business/src/main/java/cn/culturehub/BookingPipeline.java` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-business/src/main/java/cn/culturehub/BookingRedis.java` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-business/src/main/java/cn/culturehub/BookingWorker.java` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-business/src/main/java/cn/culturehub/GuideLanguageModel.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-business/src/main/java/cn/culturehub/ParticipationService.java` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-business/src/main/java/cn/culturehub/Publisher.java` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-business/src/main/java/cn/culturehub/SagaParticipant.java` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-business/src/main/java/cn/culturehub/SagaParticipantTransactions.java` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-business/src/main/java/cn/culturehub/ai/guide/GuideAgentV2.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-business/src/main/java/cn/culturehub/ai/guide/GuideGraphV2.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-business/src/main/java/cn/culturehub/ai/guide/GuideIntent.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-business/src/main/java/cn/culturehub/ai/guide/GuideKnowledge.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-business/src/main/java/cn/culturehub/ai/guide/GuidePersistence.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-business/src/main/java/cn/culturehub/ai/guide/GuidePlanner.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-business/src/main/java/cn/culturehub/ai/guide/GuideTools.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-business/src/main/java/cn/culturehub/ai/guide/LettuceGuideSaver.java` | 保留Graph业务并限制Core V3运行配置；增加跨服务持久化适配点 |
| `culturehub-business/src/main/resources/booking/inventory.lua` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-business/src/main/resources/booking/relay.lua` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-business/src/main/resources/booking/reserve.lua` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-business/src/main/resources/booking/state.lua` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-business/src/main/resources/booking/waitlist-index.lua` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-business/src/test/java/cn/culturehub/BookingP2LocalTest.java` | 统一异步预约、持久化FIFO、库存恢复、Saga参与方及测试 |
| `culturehub-contracts/pom.xml` | 消息契约、服务身份、可靠发送、Trace与监控 |
| `culturehub-contracts/src/main/java/cn/culturehub/RabbitTransport.java` | 消息契约、服务身份、可靠发送、Trace与监控 |
| `culturehub-contracts/src/main/java/cn/culturehub/v3/BookingMessage.java` | 消息契约、服务身份、可靠发送、Trace与监控 |
| `culturehub-contracts/src/main/java/cn/culturehub/v3/SagaMessage.java` | 消息契约、服务身份、可靠发送、Trace与监控 |
| `culturehub-contracts/src/main/java/cn/culturehub/v3/ServiceBoundary.java` | 消息契约、服务身份、可靠发送、Trace与监控 |
| `culturehub-contracts/src/main/java/cn/culturehub/v3/TraceCarrier.java` | 消息契约、服务身份、可靠发送、Trace与监控 |
| `culturehub-contracts/src/main/java/cn/culturehub/v3/V3Telemetry.java` | 消息契约、服务身份、可靠发送、Trace与监控 |
| `culturehub-contracts/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 消息契约、服务身份、可靠发送、Trace与监控 |
| `culturehub-gateway/pom.xml` | Gateway鉴权、服务头清理、路由与Sentinel |
| `culturehub-gateway/src/main/java/cn/culturehub/v3/gateway/GatewayApplication.java` | Gateway鉴权、服务头清理、路由与Sentinel |
| `culturehub-gateway/src/main/java/cn/culturehub/v3/gateway/GatewayConfiguration.java` | Gateway鉴权、服务头清理、路由与Sentinel |
| `culturehub-gateway/src/main/resources/application.yml` | Gateway鉴权、服务头清理、路由与Sentinel |
| `culturehub-knowledge/pom.xml` | 知识文档、异步入库、版本权威检索及测试 |
| `culturehub-knowledge/src/main/java/cn/culturehub/knowledge/CoreKnowledgeAccess.java` | 知识文档、异步入库、版本权威检索及测试 |
| `culturehub-knowledge/src/main/java/cn/culturehub/knowledge/KnowledgeApplication.java` | 知识文档、异步入库、版本权威检索及测试 |
| `culturehub-knowledge/src/main/java/cn/culturehub/knowledge/KnowledgeConfiguration.java` | 知识文档、异步入库、版本权威检索及测试 |
| `culturehub-knowledge/src/main/java/cn/culturehub/knowledge/KnowledgeController.java` | 知识文档、异步入库、版本权威检索及测试 |
| `culturehub-knowledge/src/main/java/cn/culturehub/knowledge/KnowledgeDocuments.java` | 知识文档、异步入库、版本权威检索及测试 |
| `culturehub-knowledge/src/main/java/cn/culturehub/knowledge/KnowledgeJobs.java` | 知识文档、异步入库、版本权威检索及测试 |
| `culturehub-knowledge/src/main/resources/application.yml` | 知识文档、异步入库、版本权威检索及测试 |
| `culturehub-knowledge/src/test/java/cn/culturehub/knowledge/KnowledgeIntegrityTest.java` | 知识文档、异步入库、版本权威检索及测试 |
| `culturehub-knowledge/src/test/java/cn/culturehub/knowledge/KnowledgeJobLocalTest.java` | 知识文档、异步入库、版本权威检索及测试 |
| `culturehub-knowledge/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` | 知识文档、异步入库、版本权威检索及测试 |
| `culturehub-notification-worker/pom.xml` | 通知独立数据库与查询权限 |
| `culturehub-notification-worker/src/main/java/cn/culturehub/NotificationQueryController.java` | 通知独立数据库与查询权限 |
| `culturehub-notification-worker/src/main/java/cn/culturehub/NotificationServiceBoundary.java` | 通知独立数据库与查询权限 |
| `culturehub-notification-worker/src/main/resources/application-v3-p1.yml` | 通知独立数据库与查询权限 |
| `culturehub-notification-worker/src/main/resources/application.yml` | 通知独立数据库与查询权限 |
| `culturehub-orchestrator/pom.xml` | Saga协调、受控Partner目录、恢复与状态机测试 |
| `culturehub-orchestrator/src/main/java/cn/culturehub/saga/SagaApplication.java` | Saga协调、受控Partner目录、恢复与状态机测试 |
| `culturehub-orchestrator/src/main/java/cn/culturehub/saga/SagaConfiguration.java` | Saga协调、受控Partner目录、恢复与状态机测试 |
| `culturehub-orchestrator/src/main/java/cn/culturehub/saga/SagaCoordinator.java` | Saga协调、受控Partner目录、恢复与状态机测试 |
| `culturehub-orchestrator/src/main/java/cn/culturehub/saga/SagaDemoController.java` | Saga协调、受控Partner目录、恢复与状态机测试 |
| `culturehub-orchestrator/src/main/java/cn/culturehub/saga/SagaOwnerValidation.java` | Saga协调、受控Partner目录、恢复与状态机测试 |
| `culturehub-orchestrator/src/main/resources/application.yml` | Saga协调、受控Partner目录、恢复与状态机测试 |
| `culturehub-orchestrator/src/test/java/cn/culturehub/saga/SagaRecoveryLocalTest.java` | Saga协调、受控Partner目录、恢复与状态机测试 |
| `culturehub-orchestrator/src/test/java/cn/culturehub/saga/SagaTimestampTest.java` | Saga协调、受控Partner目录、恢复与状态机测试 |
| `culturehub-service-runtime/pom.xml` | 保留P1独立边界验证夹具，完整部署不使用其探针替代业务 |
| `culturehub-service-runtime/src/main/java/cn/culturehub/v3/runtime/BoundaryApplication.java` | 保留P1独立边界验证夹具，完整部署不使用其探针替代业务 |
| `culturehub-service-runtime/src/main/java/cn/culturehub/v3/runtime/CoreReadinessClient.java` | 保留P1独立边界验证夹具，完整部署不使用其探针替代业务 |
| `culturehub-service-runtime/src/main/java/cn/culturehub/v3/runtime/ServiceReadiness.java` | 保留P1独立边界验证夹具，完整部署不使用其探针替代业务 |
| `culturehub-service-runtime/src/main/resources/application.yml` | 保留P1独立边界验证夹具，完整部署不使用其探针替代业务 |
| `deploy/Dockerfile.java` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/migrations/20261111-v3-booking.sql` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/migrations/20261111-v3-knowledge.sql` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/migrations/20261111-v3-saga-participant.sql` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/migrations/20261111-v3-saga.sql` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/migrations/20261112-v3-ai-group.sql` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/migrations/20261112-v3-ai.sql` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/migrations/20261112-v3-knowledge-tracing.sql` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/migrations/20261112-v3-saga-compensation-retry.sql` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/migrations/demo-partner-sessions.sql` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/v3-p1/00-init.sh` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/v3-p6/culturehub-dashboard.json` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/v3-p6/dashboards.yml` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/v3-p6/datasources.yml` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/v3-p6/observability.properties` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/v3-p6/otel-collector.yml` | 增量迁移、最小权限部署及可观测性配置 |
| `deploy/v3-p6/prometheus.yml` | 增量迁移、最小权限部署及可观测性配置 |
| `docs/evidence/v3-broker-outage.json` | 架构、实际证据、审计和未验证范围记录 |
| `docs/evidence/v3-current-tests.json` | 架构、实际证据、审计和未验证范围记录 |
| `docs/evidence/v3-http-bursts.json` | 架构、实际证据、审计和未验证范围记录 |
| `docs/evidence/v3-http-integration.json` | 配套交付文档、证据或可重复执行工具 |
| `docs/evidence/v3-p1-p2-tests.json` | 架构、实际证据、审计和未验证范围记录 |
| `docs/evidence/v3-telemetry.json` | 架构、实际证据、审计和未验证范围记录 |
| `docs/v3-api.md` | 架构、实际证据、审计和未验证范围记录 |
| `docs/v3-architecture.md` | 架构、实际证据、审计和未验证范围记录 |
| `docs/v3-changed-files.md` | 配套交付文档、证据或可重复执行工具 |
| `docs/v3-http-performance.md` | 架构、实际证据、审计和未验证范围记录 |
| `docs/v3-implementation.md` | 架构、实际证据、审计和未验证范围记录 |
| `docs/v3-p0-audit.md` | 架构、实际证据、审计和未验证范围记录 |
| `docs/v3-p0-gap-matrix.md` | 架构、实际证据、审计和未验证范围记录 |
| `docs/v3-saga-acceptance.md` | 架构、实际证据、审计和未验证范围记录 |
| `docs/v3-start.md` | 架构、实际证据、审计和未验证范围记录 |
| `docs/v3-test-report.md` | 架构、实际证据、审计和未验证范围记录 |
| `pom.xml` | 工程依赖、运行配置模板或交付说明 |
| `scripts/v3-p1/run-local.py` | 可重复的隔离联调、故障、压测与构建工具 |
| `scripts/v3-p1/smoke.py` | 可重复的隔离联调、故障、压测与构建工具 |
| `scripts/v3-p2/export-tests.py` | 可重复的隔离联调、故障、压测与构建工具 |
| `scripts/v3-p2/run-local.py` | 可重复的隔离联调、故障、压测与构建工具 |
| `scripts/v3-p2/smoke.py` | 可重复的隔离联调、故障、压测与构建工具 |
| `scripts/v3-p4/run-local.py` | 可重复的隔离联调、故障、压测与构建工具 |
| `scripts/v3-p5/migrate-existing-ai.sql` | 可重复的隔离联调、故障、压测与构建工具 |
| `scripts/v3-p5/run-local.py` | 可重复的隔离联调、故障、压测与构建工具 |
| `scripts/v3-p6/booking-load.py` | 可重复的隔离联调、故障、压测与构建工具 |
| `scripts/v3-p6/broker-outage.py` | 可重复的隔离联调、故障、压测与构建工具 |
| `scripts/v3-p6/init-env.py` | 可重复的隔离联调、故障、压测与构建工具 |
| `scripts/v3-p6/merge-compose.py` | 可重复的隔离联调、故障、压测与构建工具 |
| `scripts/v3-p6/package-source.py` | 配套交付文档、证据或可重复执行工具 |
| `scripts/v3-p6/requirements-tooling.txt` | 配套交付文档、证据或可重复执行工具 |
| `scripts/v3-p6/telemetry-smoke.py` | 可重复的隔离联调、故障、压测与构建工具 |
| `web/check-interactions.mjs` | 异步结果、组合授权、知识管理及交互回归 |
| `web/src/app-template.html` | 异步结果、组合授权、知识管理及交互回归 |
| `web/src/locale.js` | 异步结果、组合授权、知识管理及交互回归 |
| `web/src/main.js` | 异步结果、组合授权、知识管理及交互回归 |
