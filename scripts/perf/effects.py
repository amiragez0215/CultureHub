"""Summarize measured effects without changing or rerunning the original experiment."""
from pathlib import Path
import collections,json,statistics
root=Path(__file__).resolve().parents[2]
d=json.loads((root/'docs/evidence/performance.json').read_text())
p=json.loads((root/'docs/evidence/orm-query-plans.json').read_text())
t=json.loads((root/'docs/evidence/data-access-tests.json').read_text())['totals']
rows=d['results'];byname={r['name']:r for r in rows}
lines=['# CultureHub 数据访问与并发实验效果','',
'实验日期：2026-11-03。本文解释已完成实验的效果，不是新的压测记录。数值直接读取已提交的原始结果，未重新模拟或补造。',
'', '## 结论先看','',
'本次改造的主要收益是：数据访问结构更清楚，用户历史分页有可复现的查询改善，场次热缓存减少一次数据库回源；同时在保留原限流、行锁、唯一约束和Outbox事务的条件下，验证了并发预约及失败重试的正确性。**这不等于已经测出生产环境的最大QPS。**','',
'|项目|观察结果|能够得到的结论|', '|---|---|---|',
'|数据访问规范化|活动、场次、评价读取使用MP；活动列表及参与历史使用XML/DTO|工程结构更明确，权限、分页与空值语义经过回归；不能据此直接宣称速度提高|',
'|活动列表|新旧SQL的三次计时基本重叠|主要是规范化收益，未证实稳定的耗时改善|',
'|参与历史|三万条样本中，延迟关联比原JOIN分页耗时更低，返回内容一致|支持采用先分页再关联；深分页成本仍然存在|',
'|场次缓存|热路径每100次请求比冷索引路径少100次SELECT摘要计数|减少重复数据库回源；冷热耗时差异仅对本次场景成立|',
'|预约正确性|所有批次占用数与有效名额一致，重复有效资格为0|在已测试负载下未发现超额或重复占位|',
'|事务及幂等|Outbox故障完整回滚；客户端超时后原request_id重试只形成一条资格和一条事件|故障路径仍遵守原本地事务及幂等设计|','',
'## 一、实验条件','',
'- Windows 10，16个逻辑CPU；Java 17 / Spring Boot 3；MySQL 8.0.27、Redis 7.4.9；Hikari默认最大连接数10。',
'- Python 3.8标准库通过真实HTTP发请求；API、数据库、Redis与负载端同机。没有使用Mock响应代替压测。本机未安装k6，提供了脚本但未执行。',
'- 隔离数据库 `culturehub_perf`，最终记录中活动/场次/参与记录数为 **'+d['environment']['dataScale'].replace('\t',' / ')+'**。历史样本含三万条人工构造的已取消记录，不能把它们算成压测成功预约。',
'- 每个热门场次容量10，50/100/200客户端并发各3轮，每轮使用新场次；最后名额场景容量1、50并发、3轮。',
'- 每批为短时突发请求，轮间等待；不是几分钟或几小时持续保持指定并发。RPS分母包括线程启动与批次收尾，延迟统计为各HTTP请求时间。',
'- Redis Lua、MySQL行锁与条件更新、有效资格唯一约束、request_id和Outbox事务均保持开启。',
'- **Docker按本轮要求跳过，不作为本次交付前置条件。** 容器测试的跳过不算通过。','',
'## 二、预约并发实际产生了什么结果','',
'### 2.1 三轮全部结果汇总','',
'|客户端并发|轮数|总请求|预约成功响应|满额拒绝|限流拒绝|意外5xx/连接异常|实际新增资格|',
'|---:|---:|---:|---:|---:|---:|---:|---:|']
for n in (50,100,200):
 group=[r for r in rows if r['name'].startswith('A-') and r['concurrency']==n];codes=collections.Counter()
 for r in group:codes.update(r['codes'])
 errors=sum(r['outcomes'].get('system',0)+r['outcomes'].get('transport',0) for r in group)
 lines.append('|%d|%d|%d|%d|%d|%d|%d|%d|'%(n,len(group),sum(r['requests'] for r in group),codes['OK'],codes['SESSION_FULL'],codes['RATE_LIMITED'],errors,sum(r['occupied'] for r in group)))
lines+=['',
'例如200并发每轮是10个预约成功、90个满额拒绝、100个入口限流。它证明系统给出了可分类的正常结果，**不能说200个请求同时进入MySQL，更不能把拒绝请求的RPS当成预约提交TPS。** HTTP成功率低于100%是容量限制下的预期，不等于系统异常。','',
'### 2.2 吞吐与延迟的波动范围','',
'|客户端并发|三轮RPS范围|平均延迟范围ms|P95范围ms|P99范围ms|',
'|---:|---:|---:|---:|---:|']
for n in (50,100,200):
 group=[r for r in rows if r['name'].startswith('A-') and r['concurrency']==n]
 ranges=['%.2f—%.2f'%(min(r[k] for r in group),max(r[k] for r in group)) for k in ('rps','meanMs','p95Ms','p99Ms')]
 lines.append('|%d|%s|'%(n,'|'.join(ranges)))
lines+=['',
'这里展示三轮各自测量值的最小—最大范围，没有把三轮P95求平均。并发越高，更多请求被快速限流，RPS可能上升；这不能反推数据库写能力变强。完整每轮数值与业务提交TPS见[详细报告](performance-report.md)。','',
'### 2.3 正确性与故障结果','',
'- 热门场次每轮最终占用10个名额；占用等于消耗名额的资格数、不超过容量，重复有效资格为0。',
'- 最后一个名额：三轮共150次请求，只有3条新增资格，每轮资格和确认Outbox各1。',
'- 重复请求：每轮20个同账号、同request_id请求。多个200是对同一结果的重放；三轮最终仅3条资格和3条确认事件，入口429单独记录。',
'- 回滚重试：只在隔离库注入Outbox INSERT失败，真实HTTP返回预期500；检查资格和occupied_count均回滚到0。清理故障后，用同一request_id重试成功。',
'- 超时重试：在隔离库临时延迟Outbox写入，使首次请求超过50ms客户端期限；清理后原request_id重试，最终资格、占用、确认事件各1。',
'- 以上故障注入错误是预期结果，单列在[故障原始记录](evidence/performance-faults.json)，不混进正常场景的意外系统错误数。','',
'## 三、缓存与查询究竟改善了多少','',
'### 3.1 公开场次冷热对照','',
'两组均为100次串行HTTP请求，同一业务路径。热组先预热；冷组每次删除当前活动索引键，强制批量SQL回源，不清空Redis。','',
'|指标|热缓存|冷索引|', '|---|---:|---:|']
warm=byname['D-sessions-warm'];cold=byname['D-sessions-cold']
for label,key in [('平均响应ms','meanMs'),('P95 ms','p95Ms'),('P99 ms','p99Ms'),('RPS','rps')]:lines.append('|%s|%.2f|%.2f|'%(label,warm[key],cold[key]))
delta=lambda r,k:int(r['after'][k])-int(r['before'][k])
lines.append('|SELECT摘要计数增量|%d|%d|'%(delta(warm,'schemaSelectStatements'),delta(cold,'schemaSelectStatements')))
lines+=['',
'热路径并非完全不访问MySQL：JWT校验和父活动可见性检查仍执行。此次300与400的差值，对应100次冷查询多出的100次列表回源。多个场次键通过一次MGET读取，缺失时一次SQL批量查询，再Pipeline回填；这条调用路径还由真实Redis集成测试验证。',
'平均/P95/P99统计的是HTTP请求，不包括在客户端请求前执行的DEL；整批RPS计时包含DEL。因此不能把两组RPS差异全部归因于服务器缓存收益，也不能推广为所有列表都能提高相同比例。原始计数是共享实例摘要，包含认证与观察成本。','',
'### 3.2 原SQL与改造SQL对照','',
'下表为同批样本、每种SQL执行3次的客户端耗时均值，单位ms。计时包含mysql.exe启动与连接，不是纯SQL服务器执行时间。','',
'|场景|改造前|改造后|解释|', '|---|---:|---:|---|']
for label,before,after,explanation in [
 ('活动列表','activity-before','activity-after','差异很小，不认定稳定性能改善'),
 ('历史首页，offset=0','history-join-0','history-deferred-0','先取20条资格，再关联活动/场馆'),
 ('历史深页，offset=20000','history-join-20000','history-deferred-20000','保持原页码协议，减少关联成本但仍扫描偏移记录')]:
 lines.append('|%s|%.2f|%.2f|%s|'%(label,statistics.mean(p['queries'][before]['clientWallMs']),statistics.mean(p['queries'][after]['clientWallMs']),explanation))
lines+=['',
'已对照两种历史查询的返回行完全一致。EXPLAIN仍存在排序与部分关联扫描；不能把延迟关联表述为整个执行只扫描20行。活动列表使用既有联合索引，新XML主要减少重复条件和维护负担，没有新增索引来制造技术亮点。',
'本次不宣称“SQL条数从14N降到6”是新收益：批量统计在此前版本已经实现，本轮继续保留。','',
'### 3.3 仍然存在的瓶颈','',
'|当前HTTP历史查询|客户端并发|请求数|平均ms|P95 ms|P99 ms|', '|---|---:|---:|---:|---:|---:|']
for name,label in [('D-history-first','首页，串行'),('D-history-deep','深页，串行'),('D-history-deep-parallel','深页，并发')]:
 r=byname[name];lines.append('|%s|%d|%d|%.2f|%.2f|%.2f|'%(label,r['concurrency'],r['requests'],r['meanMs'],r['p95Ms'],r['p99Ms']))
lines+=['',
'深页在50客户端并发时P95接近1秒，说明优化后仍有深分页和资源竞争成本。它不是“所有查询都很快”的证据。本轮未改变游标协议，也未在缺乏证据时调大线程池或连接池。后续若真实需求出现大量深页读取，应继续评估查询计划和产品分页方式。','',
'## 四、锁等待和连接池怎样理解','',
'- 热门场次50并发时，三轮InnoDB行锁等待次数增量均为49；100并发均为99；200并发也均为99，与入口限流后进入事务的请求规模一致。',
'- 这说明单场次写入确实围绕同一行竞争。锁等待是守住名额约束的代价，不能通过删除行锁来追求表面RPS。全局计数也可能含同实例其他请求，不是逐请求因果追踪。',
'- 连接池上限为10。250ms离散采样观察到active/pending最大值均为0，但请求短于采样间隔，**这不能说明数据库没有负载，也不能证明连接池不存在短时等待。** 本次未可靠捕获峰值，不据此调整池大小。',
'- Slow_queries增量为0，只能解释为没有触发该服务器配置阈值；未采集完整慢日志、CPU利用率与磁盘负载，不宣称已经消除慢SQL。','',
'## 五、工程化验收结果','',
'|检查项|结果|', '|---|---|',
'|Java构建|四个Maven模块构建成功，压测JAR与最终构建哈希一致|',
'|自动化测试|%d次执行通过，0失败/错误；%d次容器测试按要求跳过，不算通过|'%(t['passed'],t['skipped']),
'|XML调整后复测|27次CatalogLocalTest通过，包含继承的核心回归；不要再与总数简单相加|',
'|前端|Vue交互测试、生产构建通过|',
'|核心事务|未改动预约、候补、签到、Outbox的原事务实现|',
'|数据迁移|仅补齐缺失的既有索引，已重复执行；不删除或重建业务数据|',
'|Docker|本次跳过，不要求安装或部署|','',
'本次结果可用于解释工程设计及这组样本中的并发正确性。它不覆盖持续稳态容量、独立负载机、不同硬件、Kafka通知投递故障或完整生产运行。','',
'## 六、证据与复现入口','',
'- [逐请求HTTP结果与环境](evidence/performance.json)',
'- [原SQL、EXPLAIN与三次耗时](evidence/orm-query-plans.json)',
'- [故障注入与重试结果](evidence/performance-faults.json)',
'- [自动化测试suite统计](evidence/data-access-tests.json)',
'- [详细性能报告](performance-report.md)',
'- [改造范围与实现说明](data-access-upgrade.md)',
'- [压测复现步骤](../scripts/perf/README.md)','',
'本文可通过 `python scripts/perf/effects.py` 从现有证据重新生成；该命令不发起新负载、不修改数据库。']
(root/'docs/experiment-results.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('Experiment effects generated from recorded measurements.')
