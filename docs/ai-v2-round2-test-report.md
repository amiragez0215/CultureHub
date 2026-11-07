# CultureHub AI V2 第二轮验收报告

## 执行结果

| 验证 | 实际结果 |
| --- | --- |
| Maven Java 17 完整 reactor | `BUILD SUCCESS`，372 项中 276 通过、0 失败、0 错误、96 跳过 |
| 第一轮 V2 已记录基线 | 353 项中 257 通过、0 失败、0 错误、96 跳过 |
| 真实 MySQL / Redis / RabbitMQ | 本机测试通过，包含 15 项预约、27 项 V2 规划与预约、30 项预约提案及 63 项 Outbox/RabbitMQ 业务回归 |
| 真实 Chroma / Embedding | Chroma 1.0.0 与 ONNX MiniLM 实际通过清单、Markdown、分片、向量写入、检索、排序、证据判定 |
| Vue | `npm run test:ui` 及 `npm run build` 均退出 0 |
| 真实 Chrome | 注册登录、节点进度、规划卡片、选中待确认提案及取消通过。隔离库核对到一条 `REJECTED` 浏览器提案和零条参与记录 |

完整命令如下。测试 MySQL/Redis 使用本机隔离实例，RabbitMQ 使用 `culturehub_acceptance` vhost。

```powershell
$env:JAVA_HOME = "D:\Test_Soft\jdk17"
$env:RABBIT_VHOST = "culturehub_acceptance"
mvn -s settings.xml package "-Djava.io.tmpdir=D:/agent/CultureHub/.tools/tmp" "-Dculturehub.localTest=true" "-Dculturehub.rabbitLocalTest=true" "-Drabbit.port=5673" "-Dculturehub.chromaLocalTest=true" "-Dchroma.test.embedding.url=http://127.0.0.1:18092" "-DskipTests=false"
Set-Location web
npm run test:ui
npm run build
```

Java 定向测试覆盖七场馆候选、正确归属、硬条件不放宽、定向补查与修复上限、假来源、Prompt 注入、年龄和陪同条件、多轮约束继承、账号隔离与 SSE 取消。MySQL 回归覆盖无确认不预约、直接预约和候补分别授权、重复确认、旧 PENDING 失效、PROCESSING 和原 request_id 恢复。

Chroma 集成在真实向量库测试中注入客户端传输失败。失败重建保留旧版本指针，规则检索返回 `UNAVAILABLE`，解除故障后同一知识库可继续查询。其他模块的公开向量保留，过期及错作用域文档被拒绝。浏览器演示使用另一个已关闭的 Chroma 端口，API 可启动并完成核心读接口和活动规划。

## 同一夹具对照

固定 Java 夹具通过将同一个 `GuideRoundComparisonTest` 放入第一轮源码副本和第二轮进行实跑。既有 9 个确定性规划挑战在两轮均为 9/9。以下分母来自有限样本。

| 项目 | 第一轮 | 第二轮 |
| --- | --- | --- |
| 工具计划精确匹配四类任务 | 2/4 | 4/4 |
| ToolCheck 区分无场馆及 MySQL 超时 | 1/2 | 2/2 |
| 明确开放规则与相关但无规则片段 | 1/2 | 2/2 |
| 错实体证据反例被错误放行 | 1/1 | 0/1 |
| 固定两片段词面重排的正确规则名次 | 1 | 1 |
| 原有确定性规划挑战 | 9/9 | 9/9 |

真实 Chroma 小样本中，两个预期的场馆规则文档都进入各自的 Top 6。博物馆年龄问题原始第一名是相关但不足的活动介绍，规则重排后第一名是年龄条款。这个结果只对应本次演示语料与两条查询。复测不使用固定向量或假 Embedding。

真实 DeepSeek 运行使用同一 MySQL 活动目录、独立 Chroma 集合和账号。第二轮多八个合成知识条目，年龄案例同时检验知识补齐和代码。以下为首次真实对照实测，从创建会话到收到结果计时。

| 案例 | 第一轮 V2 | 第二轮 V2 |
| --- | --- | --- |
| 普通搜索 | 验证后失败，2 次工具、3 次模型，5.344 秒 | 场次 1，1 次工具、2 次模型，2.641 秒 |
| 两活动硬要求 | 场次 1、2，2 次工具、2 次模型，2.781 秒 | 场次 1、2，2 次工具、2 次模型，2.875 秒 |
| 指定场次详情 SSE | 返回 `AI_FAILED`，0.078 秒 | 返回场次 1 的只读详情，1 次原生 Java 回调、2 次模型，1.844 秒 |
| 不存在的场馆 | 返回澄清，1.188 秒 | 返回无场馆，1 次工具、1 次模型，1.046 秒 |
| 必须确认剪纸年龄规则 | 证据不足，未推荐，1.140 秒 | 引用该剪纸活动的年龄条款后推荐场次 1，2.891 秒 |

真实模型回调只注册本轮的场次详情查询。原生工具返回由 Java 调用并捕获，SSE 的 `done` 记录 `nativeToolCalls=1`，规划没有访问预约写工具。对照前后参与记录均为零。调用和 Token 数见原始记录，费用及推荐相关性的总体提升没有测量。

最终构建产物复跑后，第二轮五项都再次达到预期。第一轮普通搜索这次通过，场次详情 SSE 仍返回错误，无场馆时给出了空推荐，年龄确认仍缺证据。重复运行体现了模型输出波动，单次响应时间及成功样本不能代表总体水平。 [最终构建复跑记录](evidence/ai-v2-round2-live-rerun.json)。

## 结果记录与限制

- [Surefire 用例结果](evidence/ai-v2-round2-surefire.xml) 和 [模块汇总](evidence/ai-v2-round2-test-summary.json) 保留测试名、断言结果、耗时与跳过原因。导出移除了 JVM 属性及常规进程日志。
- [同夹具确定性对照](evidence/ai-v2-round2-deterministic.json)、[真实模型首次对照](evidence/ai-v2-round2-live.json) 和 [Chrome 结果](evidence/ai-v2-round2-browser.json) 分开记录。
- 96 个 Testcontainers 用例因无 Docker 跳过，保留在测试报告和仓库中。等价的本机测试用了真实 MySQL、Redis、RabbitMQ，未把它们计作 Docker 测试。
- ONNX MiniLM 验收检验真实向量链路和小样本排序，缺少大规模中文语料、盲评和成本统计。重排是规则加词面相关性，复杂自然语言条款仍可能返回证据不足。
- SSE 的增量文字用于显示阶段进度。最终计划和理由在独立验证与 MySQL 保存之后发送。
- 旧 RabbitMQ、MyBatis、缓存和 HTTP 压测报告原样保留。本轮未重新进行 HTTP 吞吐压测。
