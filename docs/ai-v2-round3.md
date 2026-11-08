# CultureHub AI V2.0 第三轮整改

本轮以 `554d829f82cc8c07afac21e646b9e98861855548` 为基线，沿用 15 节点 Graph、候选组合算法、Redis 会话、MySQL 预约提案和原有 Java 预约状态机。变更集中在规则问题识别、证据判定、Graph 路由与前端展示。

## 证据与硬条件

| 维度 | 取值 | 用途 |
| --- | --- | --- |
| `GradeStatus` | `SUPPORTED`、`INSUFFICIENT`、`CONFLICTING`、`UNAVAILABLE` | 判断来源是否能给出明确答案 |
| `RequirementVerdict` | `MATCHED`、`NOT_MATCHED`、`UNKNOWN`、`NOT_APPLICABLE` | 判断已知规则是否满足本轮明确提出的硬条件 |

`GuideKnowledgeQuestions.plan` 单独记录证据需求和硬条件目标。询问“能不能”或者要求“必须确认”会形成待回答的问题。明确要求“必须找一个允许孩子独自参加的活动”才会形成独自入场约束。可识别的明确修改会更新旧的约束，并重新核验对应证据。

`GuideRuleAssessment.claims` 保留规则原文、结论方向、年龄范围和附带条件。年龄落在“仅限”范围之外时产生 `DENIED`。只给出“推荐／适合”年龄时保留建议范围，报名资格仍需证据。带陪同要求的允许条款保留陪同条件。允许符合年龄要求者独自入场、但没有可核验年龄资格的条款，可以作为有条件的咨询答案，硬条件判定仍是 `UNKNOWN`。

`GuideKnowledge.grade` 保留明确的否定和闭馆结论为 `SUPPORTED`，并清空该问题的 `unresolvedQuestions`。只有证据未解答的问题进入待确认列表。依据范围和实体一致性由 `GuideEvidenceScope.bind` 复核。

## 路由与界面

- **知识咨询**进入 `ProposalGenerate → ProposalVerifier → DecisionGate`，正面、否定和附条件答案共用验证流程。规则答复使用 `KNOWLEDGE_ANSWER`，仅在存在可选预约场次的方案上发出 SSE `proposal`。
- **活动规划**遇到 `SUPPORTED + NOT_MATCHED` 时，走 `InformationGate → RepairRouter`。在原有一次检索修复预算内，跳过仍依赖同一项已否定硬条件的候选，核验下一个可行组合。没有可继续核验的组合时返回 `NO_VALID_COMBINATION` 并保留已确认的规则。
- **证据不足**可以有界补查。当前没有额外的冲突消歧或 Chroma 故障恢复策略，来源冲突和服务不可用分别报告，跳过盲目改写。`RetrievalPlan` 不为已解答的问题补检索。
- **验证**保留 `PlanItem → Session → Venue / Activity → Question → Claim → Evidence` 的归属检查。硬条件失败使用 `HARD_CONSTRAINT_CONFLICT`，证据未查实使用独立的验证错误。
- **API 与 Vue**在 `GuideAgentV2.message` 的 `evidence` 中传递 `evidenceStatus`、`requirementVerdict`、`conclusion`、`conditions`、`evidenceIds`、`sources`、`explanation` 和 `unresolvedQuestions`。原 `status` 字段仍与 `evidenceStatus` 同值。Vue 直接展示否定、附条件允许、闭馆、来源冲突和暂时不可用。

## 可独立复现的虚构语料

`culturehub-business/src/test/resources/round3-rag/` 下有独立 Manifest、11 份完全虚构的 Markdown 和最小目录 SQL。测试文化馆 A 与 B 的营业时间和陪同规定不同。语料包含排他性和建议性年龄、带陪同要求的允许、冲突条款、过期条款和无关的历史介绍。测试使用专用 MySQL 库 `culturehub_round3_rag`、随机 Chroma 集合及独立 Redis 登记，结束后清理测试集合与登记。

此前分享版的导出规则排除了演示语料与验收记录。Round 2 正式仓库仍保留 `culturehub-business/src/main/resources/knowledge/`。第三轮虚构验收语料置于测试资源目录，常规生产 JAR 使用主资源下的原有 Manifest。要手工重建生产知识索引，应在主资源目录准备自己的有效 Manifest 与文档，使用平台管理员权限调用 `POST /api/v1/admin/ai/knowledge/reindex`。

在已有 MySQL、Redis、Chroma 和 Embedding 端点时，可单独运行第三轮独立语料测试。测试需在本机测试 MySQL 上有建库权限。MySQL 用户和密码可通过 `CHROMA_TEST_MYSQL_USER` 和 `CHROMA_TEST_MYSQL_PASSWORD` 提供，其他端口按命令参数修改。

```powershell
mvn -s settings.xml -pl culturehub-business -am test "-Dtest=GuideChromaLocalTest#standaloneFictitiousCorpusResolvesBothDirectionsWithoutDemoDatabase" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dculturehub.chromaLocalTest=true" "-Dchroma.test.fixture.server=jdbc:mysql://127.0.0.1:13306" "-Dchroma.test.url=http://127.0.0.1:18090" "-Dchroma.test.embedding.url=http://127.0.0.1:18092"
```

本轮复用了 [本机 ONNX 测试桥接器和启动说明](ai-v2-round2.md#测试入口)。真实 Embedding 测试走同一套 Manifest 校验、分片、Chroma 写入、索引发布、检索和规则判定代码。

## 测试边界

`GuideRoundThreeTest` 运行真实 Java Graph，控制模型、目录与向量召回输入，覆盖候选切换、无备选、已否定不补查、条件修改、来源表达和无预约提案事件。`GuideChromaLocalTest` 使用真实 Chroma、ONNX Embedding、MySQL 和 Redis 核查虚构语料及故障恢复。预约、候补、提案和通知继续由原有本机集成套件验证。前端用合成 SSE 事件验证渲染并执行 Vite 构建。

规则解释限于可辨认的条款形式。开放时间的复杂节假日例外、自然语言多重否定和跨条款资格推导仍可能返回证据不足。已有陪同或年龄规则不代表实际身份已核验，提交预约时继续走原 Java 业务。备选核验遵守现有有限预算，无法据此断言目录中所有场次都不符合要求。

## 实测结果

| 验证 | 结果 |
| --- | --- |
| Maven 完整 reactor | 383 项，287 通过，0 失败，0 错误，96 跳过。Round 2 为 372 项，276 通过，跳过数相同。 |
| 新增 Java Graph、证据和 API 输出 | 10 项通过，模型和业务工具输入受控。包含已否定不补检、换候选、改硬条件和咨询不产生预约提案。 |
| 真实 Chroma 与 ONNX MiniLM | 两个集成用例通过。独立语料覆盖 10 个查询、过期文档过滤与跨馆隔离。故障注入单独验证 `UNAVAILABLE` 和恢复。 |
| 真实本机业务 | 15 项预约、27 项规划与预约、30 项预约提案、63 项 Outbox／RabbitMQ 本机回归通过。 |
| Vue | `npm run test:ui` 和 `npm run build` 均通过。受控 SSE 交互包含“需要陪同”和来源展示。 |

完整 Java 运行使用 JDK 17、MySQL 8.0.27、Redis、RabbitMQ 4.1.4、Chroma 1.0.0 和真实 ONNX MiniLM。96 个跳过项均为无 Docker 环境的 Testcontainers 测试。

```powershell
$env:RABBIT_VHOST = "culturehub_acceptance"
mvn -s settings.xml package "-Dculturehub.localTest=true" "-Dculturehub.rabbitLocalTest=true" "-Drabbit.port=5673" "-Dculturehub.chromaLocalTest=true" "-Dchroma.test.embedding.url=http://127.0.0.1:18092" "-DskipTests=false"
Set-Location web
npm run test:ui
npm run build
```

保留 [Surefire 用例和跳过原因](evidence/ai-v2-round3-surefire.xml) 与 [测试计数和真实 Chroma 判定记录](evidence/ai-v2-round3-test-summary.json)。Surefire 导出移除了 JVM 属性和常规进程日志。

### 真实 HTTP 和 SSE

用真实 DeepSeek、Java API、Chroma 和 ONNX MiniLM，在隔离库 `culturehub_ai_round3_eval` 运行了规则咨询和规划。正式联调两轮的参与记录均保持为 0。

| 复跑场景 | 实际 API 结果 |
| --- | --- |
| 8 岁能否独自入场 | `KNOWLEDGE_ANSWER`，`SUPPORTED + ACCOMPANIMENT_REQUIRED + NOT_APPLICABLE`，带模拟来源 |
| SSE 查询指定剪纸场次的报名年龄 | `KNOWLEDGE_BLOCKED`，返回 `INSUFFICIENT`。原模拟说明只有“适合 6～12 岁”，未给出排他或允许报名条款 |
| 8 岁是否符合场馆年龄规则 | `KNOWLEDGE_ANSWER`，`SUPPORTED + ALLOWED`，保留监护人陪同条件 |
| 类别、主题和受众不限，但必须独自入场 | `NO_VALID_COMBINATION`，`SUPPORTED + ACCOMPANIMENT_REQUIRED + NOT_MATCHED`，候选为空 |

首次启动联调时，脚本复用的 SQL 辅助函数仍指向旧测试库，导致新库缺表。已固定初始化目标后重新执行。第一轮有效请求中的硬条件问题在目录筛选阶段返回 `NO_RESULTS`。复跑明确了目录筛选条件不限，核验了真实规则不匹配路径。实际模型对目录条件的提取仍会影响召回。

```powershell
python scripts/ai-v2/check-round3-live.py --java D:/Test_Soft/jdk17/bin/java.exe --mysql D:/mysql/mysqlx/bin/mysql.exe --jar culturehub-api/target/culturehub-api-1.0.0.jar --output docs/evidence/ai-v2-round3-live-rerun.json
```

[首轮有效联调](evidence/ai-v2-round3-live.json) · [复跑的实际 HTTP/SSE 返回](evidence/ai-v2-round3-live-rerun.json)
