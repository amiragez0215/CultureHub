# CultureHub AI V2 第二轮整改

基线是第一轮 V2 的 `b5b45681e624bb642397d21e5677b5929ac29681`。第二轮位于独立分支 `codex/ai-guide-v2-round2`，阶段 A、B、C 的回退点分别为 `e5f0761`、`4894f37`、`be23c97`。本轮沿用现有规划表及预约提案表，无数据库迁移。

## Graph 路由与职责

活动请求先查业务事实，再在 Java 中计算最多五个可行组合。RAG 每次只服务当前组合。必需规则缺失时，现有 RepairRouter 可在一次检索修复预算内切换到另一个有限组合并清空旧证据。场馆咨询和指定场次详情可直接回答已核对的事实，无须运行组合优化。能从 MySQL 场馆资料确定开放时间时跳过相关向量查询。

| 节点 | 输入与输出 | 典型后继 |
| --- | --- | --- |
| IntentExtract | 最新消息、近期四组对话和既有结构化约束，输出 Java 合并后的本轮要求 | 信息需求、澄清或取消 |
| InformationNeedPlan | 按任务拆分工具需求、知识问题和规划计算 | ToolPlan、全局检索或澄清 |
| ToolPlan | 按需求编号、参数、有效期和已有结果生成缺口清单 | ToolExecute |
| ToolExecute | 执行白名单中的只读查询。明确的场次详情可经 Spring AI 原生 ToolCallback | ToolCheck |
| ToolCheck | 区分成功、空结果、缺字段和数据库异常 | CandidateOptimize、RAG、直接进入 InformationGate、补查或结束 |
| CandidateOptimize | 以 MySQL 快照检查同馆、间隔、日期、已有预约冲突及硬约束 | 当前组合的 RAG、InformationGate 或修复 |
| RetrievalPlan | 为具体问题指定馆与活动作用域、查询词及 Top K | Retrieve |
| Retrieve | Spring AI Chroma 查询，校验活动归属、有效期和可信内容摘要 | Rerank |
| Rerank | 按问题独立计算同义词命中、字符重合和可提取规则加分 | EvidenceGrade |
| EvidenceGrade | 提取直接可支持的规则结论，记录年龄区间、限定条件、来源与冲突 | InformationGate |
| InformationGate | 将有效知识问题绑定到当前方案的具体场次，区分必要条件与可降级信息 | ProposalGenerate、修复或说明无法确认 |
| ProposalGenerate | 聊天模型使用唯一已核查组合和结构化引用。咨询类回复由 Java 构造 | ProposalVerifier 或定向重试 |
| ProposalVerifier | 独立核对场次、时间、约束及场次—问题—结论—证据归属 | DecisionGate 或 RepairRouter |
| RepairRouter | 按失败原因局部补查，清除失效数据并消耗有界预算 | 对应节点或结束 |
| DecisionGate | 根据经核查的事实渲染理由，再交给持久化与 SSE | 返回用户 |

全轮预算为 90 秒、12 次业务查询和 4 次模型调用。业务补查最多两次，RAG 补检、组合重算和重新生成各最多一次。单次 Chroma 请求最多等 8 秒，且受整轮剩余时间限制。

## 11 项整改

| 问题 | 处理 |
| --- | --- |
| 1. 知识资源和重建 | Manifest 显式声明模拟来源、文档 ID、版本、作用域、实体、类型、可见性、有效性及可选有效期。先校验再暂存，使用 Redis 单键原子发布版本与可信片段摘要。 |
| 2. 第七个场馆错配 | 先优化再检索。按方案项绑定场次、活动、场馆、问题、证据和判定。找不到必需规则时可有界尝试下一组合。 |
| 3. 固定工具计划 | 查询、组合、场馆与场次详情各有独立需求。部分失败时只重做缺口。场次详情的原生回调接受空参数，Java 预先确定场次，模型无法指定账号或调用写工具。 |
| 4. 问题拆分 | 开放时间、年龄、监护人陪同、活动背景使用不同问题和检索条件。场馆营业资料来自 MySQL 时直接使用。 |
| 5. 重排 | 保留每条证据对应的问题编号。按问题做词面重排，提供同义表达和明确规则形式加分。 |
| 6. 证据判定 | 从可解析的条款提取结论与条件。相关描述不能证明规则。有效冲突保留冲突状态。 |
| 7. Gate 与 Verifier | 核查引用能否直接支持该问题和实体上的结论。推荐理由可引用 Java 事实 ID 或精确的场次、问题与证据三元组。 |
| 8. 无场馆 | 显式返回 `VENUE_NOT_FOUND` 空结果。查询异常及场馆数据不完整分别处理。 |
| 9. ChatMemory | Redis 保留最多 20 组、30 分钟 TTL，单次最多读取最近四组。账户与会话双重校验。只把有界历史传给意图节点，已保存方案从 MySQL 恢复。 |
| 10. SSE | `status` 传节点进度，`delta` 传安全的进度文字。已验证且已保存才发 `proposal`。保留 `error` 和 `done`。 |
| 11. 推荐理由 | 用真实类别、活动时间、兴趣匹配和衔接分钟数生成带 ID 的依据。模型选择与组织引用，Java 渲染最终文字并拒绝不合法的关联。 |

主要代码位于 `culturehub-business/src/main/java/cn/culturehub/ai/guide/`。Graph 和 State 在 `GuideGraphV2` 与 `GuideState`，工具在 `GuideTools` 与 `GuideLanguageModel`，知识在 `GuideKnowledgeQuestions`、`GuideKnowledgeIndex`、`GuideKnowledge`、`GuideRuleAssessment` 与 `GuideEvidenceScope`，规划验证在 `GuidePlanner` 与 `GuideNarration`，会话与展示在 `GuidePersistence`、`GuideAgentV2` 和 `web/src/`。从基线核对改动文件可用以下命令。

```powershell
git diff --name-status b5b4568..HEAD
```

## 知识数据与维护

知识清单位于 `culturehub-business/src/main/resources/knowledge/manifest.json`，Markdown 与它一起进入 JAR 及源码包。旧的 `source-no-data.zip` 导出规则排除了整个知识目录，第一轮 Git 仓库和全量源码包仍保有知识资源。第二轮的分享包保留这些明确标注的合成演示文档，排除真实环境变量文件、数据库卷、缓存、依赖目录和运行日志。

- `GLOBAL` 可提供一般文化和平台知识。只有 `VENUE` 可回答对应场馆规则，`ACTIVITY` 必须同时匹配活动及其场馆。
- 名称必须唯一匹配 MySQL 中开放场馆和已发布活动。只有显式声明 `optionalDemoCatalog` 的演示条目允许因种子目录未安装而跳过，管理接口返回清单。缺文件、元数据错误或必需场馆缺失会阻止发布。
- 新索引写入随机生成的独立版本。搜索按模块标记、版本、可见性和实体过滤，再校验摘要及元数据。Redis 丢失登记后需要由平台管理员重建。此前检索快照只在同一索引版本内使用。
- Chroma 写入或超时失败时，发布指针未推进。旧版本仍可查询，其他功能的向量不会被删除。暂存失败数据和历史版本不自动清理。需要清理时，先核实实例和活动版本，再仅处理本模块的非活动版本。
- 模拟年龄条款和开放时间仅供演示。真实项目需用经授权的有效文档替换。全文没有直接规则、条款冲突或向量库不可用时，分别保留可判断的结果状态。Java 只解释受支持的规则形式，遇到复杂例外时会返回不足。

Chroma 和 Embedding 就绪后，以平台管理员令牌调用 `POST /api/v1/admin/ai/knowledge/reindex`。已有版本可继续沿用原有模型配置。公开模型接口凭据仍通过环境变量提供。

## 预约边界

经验证并保存的计划只能用于展示和选择。每个场次单独生成待确认提案。用户点击确认后仍由原有 `AiBookingProposalService`、`Booking.create` 和 `ParticipationService` 决定结果。候补必须另行选择和确认。稳定 request_id、MySQL 锁与条件更新、FIFO、Outbox、RabbitMQ、幂等和结果恢复沿用现有实现。取消生成、关闭 SSE 或取消待确认方案都不提交预约。

## 测试入口

真实本机验收使用 JDK 17、MySQL 8.0.27、Redis、RabbitMQ 4.1.4、Chroma 1.0.0、ONNX MiniLM、DeepSeek 和 Chrome。Chroma 采用独立的本机数据目录及端口。

```powershell
$env:RABBIT_VHOST = "culturehub_acceptance"
mvn -s settings.xml package "-Dculturehub.localTest=true" "-Dculturehub.rabbitLocalTest=true" "-Drabbit.port=5673" "-Dculturehub.chromaLocalTest=true" "-Dchroma.test.embedding.url=http://127.0.0.1:18092"
Set-Location web
npm run test:ui
npm run build
```

本机测试默认 MySQL `13306`、Redis `16379`、Chroma `18090`。测试中的 RAG 集成从已安装的 `culturehub_rich_demo` 只读解析模拟场馆及活动归属，可通过 `chroma.test.jdbc` 指定等价测试库。测试写入独立随机 Chroma 集合和版本登记，完成后清除该集合及登记。

ONNX 验收桥接脚本仅监听本机。准备 Python 3.11、NumPy、ONNX Runtime 和 Tokenizers，将 Chroma 官方 MiniLM 模型压缩文件保存为模型目录下的 `onnx.tar.gz`。桥接器会校验文件 SHA-256，提取模型和 tokenizer 并运行实际推理。生产项目仍使用原有 Spring AI Embedding 配置。 [Chroma 文档](https://docs.trychroma.com/)。

```powershell
chroma run --host 127.0.0.1 --port 18090 --path .tools/chroma-test-data
python scripts/ai-v2/serve-test-embeddings.py --port 18092 --model-cache .tools/chroma-model
python scripts/ai-v2/compare-rounds.py --first <第一轮构建的jar> --second <第二轮构建的jar> --output docs/evidence/ai-v2-round2-live.json
python scripts/ai-v2/browser-smoke.py --chrome <Chrome路径> --output docs/evidence/ai-v2-round2-browser.json
python scripts/ai-v2/package-source.py CultureHub-AI-V2.0-round2-source.zip
```

浏览器脚本通过 Vue 实际注册并访问 SSE，验证生成、选择和取消待确认方案。运行前需启动隔离的测试 API 与 Vue，并为 Vite 指定 `CULTUREHUB_API`，默认浏览器目标地址为 `127.0.0.1:15190`。真实模型工具验收按 DeepSeek 提供的工具调用协议注册单一只读工具。 [DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/) · [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)。

测试汇总与原始记录见 [第二轮验收报告](ai-v2-round2-test-report.md)。
