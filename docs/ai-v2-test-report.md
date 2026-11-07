# AI V2 实施与验收报告

验收日期 2026-11-07。基线 `7d112761696fcaea8c2350aa0a4dd374cd0cf60d`；工作分支 `codex/ai-guide-v2`。原 V1、`main`、RabbitMQ 迁移分支均未覆盖。[架构与启动说明](ai-v2.md)包含15节点职责、状态、工具、RAG及预约边界。

## 实施顺序与回归

先运行实际 V1 基线，再按 State/Tool、RAG、规划/验证、MySQL/Redis/SSE 集成及验收推进。受保护业务源码逐文件 SHA256 一致，原测试文件与历史压测材料未删除或放宽。V1 默认20条查询入口保留，AI2/AI3 旧代码保留兼容开关，共用 ChatClient/Chroma/Embedding 和权限未删除。

| 口径 | 执行 | 通过 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|---:|
| 迁移前 V1/RabbitMQ 已验收源码复跑 | 298 | 202 | 0 | 0 | 96 |
| V2 各套件最新结果（完整回归及变动后的针对性复跑） | 353 | 257 | 0 | 0 | 96 |

Windows、Java17、MySQL 8.0.27、Redis 7.4.9、真实便携 RabbitMQ 4.1.4。96 次跳过是原 Docker 容器测试（本机缺 Docker），没有把容器套件记成通过。测试执行数含继承预约回归，不代表同等数量的不同场景。前端 `npm run test:ui` 和 `npm run build` 均通过；第一次 Vite 构建沙箱禁止 esbuild spawn，获准运行同一命令后通过。

```powershell
$env:TEMP='D:\agent\CultureHub\.tools\tmp'
$env:TMP=$env:TEMP
$env:RABBIT_VHOST='culturehub_acceptance'
mvn -s settings.xml package '-Djava.io.tmpdir=D:/agent/CultureHub/.tools/tmp' '-Dculturehub.localTest=true' '-Dculturehub.rabbitLocalTest=true' '-Drabbit.port=5673' '-DskipTests=false'
mvn -s settings.xml package '-Dculturehub.localTest=true' '-Dtest=GuidePlanningTest,GuideRoutingTest,GuideV2LocalTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -s settings.xml test '-Dtest=GuideRoutingTest' '-Dsurefire.failIfNoSpecifiedTests=false'
python scripts/ai-v2/evaluate-live.py --fixture
cd web
npm run test:ui
npm run build
```

完整回归后独立验证器增强，重跑规划、路由和真实 V2 套件；最后新增缺 Chroma 的路由用例并重跑。汇总使用最新 Surefire XML，不累加重复跑同一套件。[测试原始汇总](evidence/ai-v2-test-summary.json)。

## 验证了什么

- 真实 StateGraph 控制流：一个受控用例访问15种节点，故意提供不存在的场次后只回到 ProposalGenerate，IntentExtract 与 ToolExecute 各只执行一次；全局咨询跳过业务工具，缺 Chroma 时不生成规则结论。
- 有界组合与独立验证：时间/间隔/总窗口/已有资格冲突、硬数量不降级、软数量备选；验证器能发现受破坏的优化输出和虚构场次/事实承诺。
- 真实 MySQL/Redis：未确认无参与记录；两场分别确认，request_id 各自独立；重复点击只写一个资格和一条 Outbox；改计划与确认竞争；提案后新增冲突能阻止确认；Redis 记忆删除后读 MySQL 计划与提案；Graph 取消无预约写入。
- 原生游标：在 ToolCheck 后中断，从真实 Redis 帧恢复，不重复 IntentExtract。修改专用序列化器前暴露的 Record→Map 类型问题、两次恢复入口调试及修复均有本地 Maven 日志。最后一次路由补测曾因 Mockito 初始化占用测试夹具提前启动的10秒计时超时；计时挪到真实 Graph 调用前，生产90秒预算未变。
- 原预约、候补、提案结果恢复、Redis 限流不可用、FIFO、Outbox、真实 RabbitMQ ACK/Return/DLQ、控制器与 SSE 仍走原回归。

## Challenge Set 与真实模型对照

`ai-v2-challenges.json` 是可重复的确定性规划集。`GuideChallengeTest` 逐例检查组合数量与约束：9/9 通过，调用模型0次；这是 Java 算法验证，不代表真实模型质量。[明细](evidence/ai-v2-challenge.json)。

真实模型使用已有外部配置，三组各两题、V1/V2分别调用。独立双活动集用专用 `culturehub_ai_v2_eval` 库的真实 MySQL 行，两场同馆且不重叠。所有测试只生成规划，前后 participation 计数一致；脚本可复现，需已有本地 MySQL/Redis 和有效聊天模型配置。

| 样本 | V1 | V2 |
|---|---|---|
| 现有演示账号，明天搜索 | 推荐了场次47；它与已有场次13同在 10:00:29–12:00:29 | 读到本人预约，拒绝冲突方案 |
| 现有账号，必须两个 | 仍输出活动推荐，无组合规划结构 | 无可行组合，没有用单场补数 |
| 无历史预约的模拟账号，明天搜索 | 返回活动推荐 | 真实模型单场计划通过 Java 校验并保存 |
| 独立双活动样本，必须两个 | 活动推荐、没有组合时间表 | 真实模型输出同馆两项，顺序与时间经 Java 验证 |

实测响应耗时、候选 ID、V2 调用次数与服务商返回的 token 数见 [已有预约对照](evidence/ai-v2-live-existing-bookings.json)、[单场正向](evidence/ai-v2-live-model.json)、[双场正向](evidence/ai-v2-live-two-activity.json)。样本太少，不推断 P95、总体任务完成率或推荐相关性改善。V1 未采集 token usage，未配置价格表，不估算账单。RAG 证据准确率与重排收益仅做确定性/受控检验，不以缺真实检索的数据计算线上准确率。

真实联调先后发现：测试脚本误用 GET 创建会话（未触发模型，保留记录）；模型对未知时长返回0（通过缺省语义修复，显式0仍拒绝）；双活动夹具的 MySQL 客户端字符集缺失（改UTF-8后复跑）。失败记录：[脚本错误](evidence/ai-v2-live-harness-error.json)、[缺省语义修复前](evidence/ai-v2-live-before-fix.json)。

## V1 / V2 取舍与限制

- V1 是短链推荐与内存会话；V2 新增事实/知识拆分、确定性同馆组合、独立验证、定向修复、Redis 检查点及 MySQL 版本。收益证据是上述具体冲突、硬数量、重启与重复确认用例；不是泛化的语义质量承诺。
- 增加三张规划表、Redis 帧写入、组合计算与可能的一次生成修复；多节点使问题更易定位，同时引入 TTL、版本、历史约束合并、索引运营与存储开销。关键字段设置有界结果、消息/帧上限及超时。
- **未验收真实 Chroma/Embedding**：当前环境未运行适配的真实向量检索链路；已有聊天模型调用通过并不证明嵌入/索引通过。场馆级权限元数据、重排、冲突及缺证是受控测试。默认演示知识没有能认证所有活动年龄资格的来源。
- **未执行 Docker/Compose 及全渠道跨场次原子冲突实验**；保留预约核心事务。实际多入口同时写入不同场次仍以原业务约束为准，参见架构中的并发边界。未重新执行历史 HTTP 压测、真实 XXL Admin 或 RabbitMQ 多节点故障。
- 公开 API 可恢复保存的计划及提案，内部游标恢复须在原轮预算内；超时后开新轮按保存条件重新查询。未提供任意节点回放/任意图状态提交接口。
- 语义抽取、中文同义改写和词面重排仍有召回局限；仅样本及明确验收边界可以据此下结论。服务端没有用模型常识补齐官方规则，也不承诺余量锁定或代约。
