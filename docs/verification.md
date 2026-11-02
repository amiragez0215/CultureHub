# 实际验证记录

日期：2026-10-31，Asia/Shanghai。本记录区分源码实现、实际执行和环境限制。

补充（2026-11-01）：已完成 DeepSeek `deepseek-flash` 真实 API 与主业务 API 联调，覆盖导览、多轮记忆、策划结构及 60 分钟时长校验、Java 指标与真实评价复盘及报告持久化。证据见 `evidence/deepseek-direct.json`、`evidence/deepseek-business.json`。Embedding/Chroma 检索仍未执行；下述 10-31 的受控模型测试记录是此前的验证边界。

AI Graph 增量升级：69 项测试执行通过、失败和错误均为 0；包含 24 项单元测试及 45 项本地 MySQL/Redis 集成测试执行（其中复用了原预约回归用例）。51 项 Docker 容器测试跳过，不算通过。真实 DeepSeek/HTTP 验证了澄清、真实工具查询、选择不预约、过期拒绝、放弃、越权、并发确认产生一条资格与一条 Outbox、场馆咨询及 AI2/AI3 回归；临时数据已清理。独立无密钥实例启动和原报告保护也通过。完整证据分别为 `evidence/ai-upgrade-tests.json`、`ai-upgrade-http.json`、`ai-upgrade-no-key.json`、`ai-upgrade-ui.txt`。新设计说明见 `ai-graph-upgrade.md`。

## 已执行

* JDK 17.0.6、Maven 3.6.3，四模块 `mvn -s settings.xml '-Dculturehub.localTest=true' package` 构建成功，生成主 API 与 Worker 可执行 JAR。
* 机器没有 Docker 命令。使用现有 MySQL 8.0.27 程序，在工作区 `.tools/mysql-isolated` 初始化独立数据，监听 13306；未使用或更改机器原有 MySQL80 服务的数据。临时 Redis 监听 16379。
* 13 项真实 MySQL/Redis 集成测试通过：最后名额竞争、重复创建、取消补位与防插队、超时幂等、确认超时竞争、核销取消竞争、重复核销、越权、事务失败回滚重试、Lua 限流、旧请求重放、并发入队顺序、评价与统计。
* 6 项 AI/事件边界测试通过：结构化 DTO、时长约束、非法事件、虚构活动引用过滤、会话隔离、Java 指标来源、管理权限检查。它们使用可控模型或模拟响应，不代表真实模型通过。
* 容器版预约测试 13 项、Outbox/Kafka 测试 2 项、Worker/Kafka 测试 4 项因缺少 Docker 明确跳过。最新测试结果在 `evidence/tests.json`，不是所有测试都已执行。
* 主 API 在 18080 启动成功；XXL-JOB 三个 Handler 注册成功。
* 通过 Vite 的 `/api` 代理执行 HTTP 冒烟成功，覆盖注册登录、JWT、公开查询、管理发布、权限拒绝、请求结果找回、满额拒绝、候补、取消补位、确认、凭证、幂等核销、签到后取消拒绝、统计和缺少模型的错误响应。结果在 `evidence/http-smoke.json`。
* Vue 模板经过 `@vue/compiler-dom` 编译检查；Vite 生产构建成功，实际请求代理至 Java API。未把这项检查描述为全页面浏览器 E2E 测试。
* 最新业务 DDL 与模拟数据在新的独立数据库执行成功。Compose 与 application YAML 完成语法解析。

## 尚未在本机验证

1. **Docker Compose 整体启动**：缺少 Docker，镜像拉取、容器健康检查、容器间网络以及 XXL-JOB Admin 调度仍需在具备 Docker 的机器执行。
2. **Kafka / Worker 真实链路**：源码与容器测试已提供，但本机没有 Kafka Broker，不能声称重复发布、消费 Offset、死信和重启场景已经运行通过。
3. **Embedding 服务与 Chroma 检索**：尚未配置向量服务和运行 Chroma，未验证检索效果。DeepSeek 真实聊天已于 11-01 完成上述业务联调。
4. **全页面浏览器交互与摄像头扫码**：已经完成前端构建、模板检查和 HTTP 代理联调；未执行完整浏览器点击流程。现场核销提供粘贴随机凭证入口，用户凭证页生成二维码。
5. **性能指标**：没有进行正式压测，不提供 QPS、性能提升比例等未经测量的数据。

## 重跑

有 Docker 的环境执行 `mvn -s settings.xml test`，确认容器测试没有跳过，再执行 Compose 演示。AI 部分先配置环境变量和知识导入；自动化仍默认不访问付费模型。业务表结构或权限变更后应重跑对应真实集成测试与 HTTP 冒烟。
## 2026-11-01 技术增强补充

最新干净构建 `mvn -s settings.xml -q clean package '-Dculturehub.localTest=true'` 返回 0：174 次测试执行中 101 通过、73 跳过、0 失败/错误。34 次为单元及 MVC 受控响应测试，67 次为专用 MySQL/Redis 集成测试；后者含继承的预约回归重复执行，不等于 67 个互不重复场景。跳过的是 Docker 容器测试。

Vue 模板、中文映射、SSE 解析与实际 Vue 渲染器交互测试通过，生产构建通过；真实 HTTP 验证筛选/分页/授权/场次时间容量及参数 400；DeepSeek 真实 SSE、推荐、独立确认、AI2/AI3 回归通过，临时测试实体已清理。具体证据和未验证范围见 `evidence/technical-validation.json` 及 `technical-enhancement.md`。上述历史记录保留各自执行日期，本节为本轮新增验证。
