"""Render only previously executed measurements, never synthesize performance numbers."""
from pathlib import Path
import json,xml.etree.ElementTree as E
root=Path(__file__).resolve().parents[2];d=json.loads((root/'docs/evidence/performance.json').read_text());plans=json.loads((root/'docs/evidence/orm-query-plans.json').read_text())
lines=['# CultureHub HTTP 性能测试报告','', '本报告由 scripts/perf/report.py 从真实 HTTP 原始记录生成；工具为 Python stdlib Barrier/urllib，不是 k6。k6 脚本已提供，本机未安装，未执行。','', '## 环境与方法','', '- 环境：'+d['environment']['os']+'；Python '+d['environment']['python']+'；逻辑CPU '+str(d['environment']['cpuCount'])+'；MySQL '+d['environment']['mysql']+'。','- 数据库：'+d['environment']['database']+'；活动/场次/参与历史记录数：'+d['environment']['dataScale'].replace('\t',' / ')+'。', '- Java 17，Spring Boot 3；API、数据库、Redis、负载端位于同机。Hikari 默认上限10；每个预约场次容量10，最后名额场景容量1。', '- 预约每规模3轮；客户端同步屏障发起一次请求，不是持续稳态流量。计时包含线程启动及短连接成本。原Lua限流、场次行锁、条件更新、唯一约束和Outbox事务均开启。','- 执行期间持续运行的其他系统程序未隔离CPU/磁盘；不把本机微基准解释为生产容量。最终记录重新运行，未与Maven/前端构建并行。','', '## HTTP 实测','', '|场景|并发|请求数|批次秒|RPS|平均ms|P95ms|P99ms|结果分类|','|---|---:|---:|---:|---:|---:|---:|---:|---|']
for r in d['results']:
 if 'rps' not in r:continue
 lines.append('|%s|%d|%d|%.3f|%.1f|%.2f|%.2f|%.2f|%s|'%(r['name'],r['concurrency'],r['requests'],r['seconds'],r['rps'],r['meanMs'],r['p95Ms'],r['p99Ms'],json.dumps(r['outcomes'],ensure_ascii=False)))
lines+=['','RPS包含成功与拒绝请求。`committedTPS`、每轮状态码、业务错误码和逐请求耗时保存在 evidence/performance.json；幂等重放的多个200不表示多个事务。不同轮百分位不做简单平均。','', '## 正确性与限流','']
for r in d['results']:
 if r['name'].startswith(('A-','B-','C-idempotent')):
  lines.append('- %s / %d并发：occupied=%d，capacity=%d，active=%d，重复有效资格=%d，确认Outbox=%d；业务提交TPS=%.2f。'%(r['name'],r['concurrency'],r['occupied'],r['capacity'],r['active'],r['duplicateActive'],r['outboxConfirmed'],r['committedTPS']))
lines+=['','200并发批次受到入口100次/秒限流；429没有进入资格写事务，不能宣称200个请求同时压到MySQL。其他4xx与超时/连接错误/5xx分别记录，SESSION_FULL是正常业务结果。','', '受控故障：隔离库临时Outbox失败触发器导致预期500，资格与occupied_count回滚到0；finally删除触发器，同一request_id重试200，名额和确认事件各1。原始C-rollback-retry记录单列，不算成正常场景的意外系统异常。','', '## 缓存与数据库观测','', '|场景|SELECT摘要次数差|Redis命令计数差|Redis命中差|Redis未命中差|','|---|---:|---:|---:|---:|']
for r in d['results']:
 if not r['name'].startswith('D-') or 'before' not in r:continue
 before=r['before'];after=r['after'];b=before['redisInfo'];a=after['redisInfo']
 delta=lambda k:int(a.get(k,0))-int(b.get(k,0))
 lines.append('|%s|%d|%d|%d|%d|'%(r['name'],int(after['schemaSelectStatements'])-int(before['schemaSelectStatements']),delta('total_commands_processed'),delta('keyspace_hits'),delta('keyspace_misses')))
lines+=['','SELECT计数来自performance_schema摘要，不是应用逐请求追踪；包含JWT账户/角色查询，Redis计数包含INFO采样与冷场景主动DEL。热场次路径：JWT校验 + 父活动可见性SQL，GET索引 + 一次MGET；冷索引额外一次批量SQL + Pipeline SETEX。不能把DEL的成本隐去后比较冷暖结果。','', '## 锁等待、慢查询与连接池','']
for r in d['results']:
 if not r['name'].startswith('A-'):continue
 diff=lambda k:int(r['after'][k])-int(r['before'][k])
 lines.append('- %s / %d并发：InnoDB行锁等待次数增量%d，累计等待毫秒增量%d，Slow_queries增量%d。'%(r['name'],r['concurrency'],diff('Innodb_row_lock_waits'),diff('Innodb_row_lock_time'),diff('Slow_queries')))
samples=[s for r in d['results'] for s in r.get('poolSamples',[])]
for metric in ('hikaricp.connections.active','hikaricp.connections.pending'):
 values=[float(m['value']) for s in samples for m in (s.get(metric,[]) if isinstance(s.get(metric),list) else [])]
 lines.append('- %s：采样%d点，观察到的最大值%s。'%(metric,len(values),max(values) if values else '不可用'))
lines+=['','这些状态是共享实例的计数增量，观察者也有开销；连接池250ms离散采样可能漏掉毫秒级峰值，不能把采样最大值当真实峰值。Slow_queries受服务器阈值影响，0不证明没有慢SQL。CPU利用率、磁盘负载和完整慢日志未采集。','', '## SQL计划与优化取舍','', '|查询|3次客户端耗时ms|','|---|---|']
for name,v in plans['queries'].items():lines.append('|'+name+'|'+', '.join('%.2f'%t for t in v['clientWallMs'])+'|')
lines+=['','SQL、EXPLAIN与规模保存在 evidence/orm-query-plans.json。客户端耗时包含mysql.exe启动/连接；新活动动态SQL没有稳定优势，不宣称耗时提升。三万条历史的延迟关联与原JOIN返回行相同，减少分页后关联行数，已在XML采用；保留原offset分页，深偏移仍有扫描成本。','', '现有 ix_activity_venue、ix_session_activity、ix_user_history 服务对应过滤及排序；不强行新增索引。20261103-query-indexes.sql 仅补齐旧库缺失的同名既有索引，已在性能库重复执行。','', '四条SQL的批量统计已在前次改造实现，本轮保留；不是本轮新创造的性能提升。参照 technical-enhancement.md 及旧SQL计数回归。','', '## 结论与局限','', '本次负载主要证明固定并发下的业务正确性和本机查询表现。保持默认池配置，没有证据支持删除行锁或调大连接池来追求数字。更多用户/独立负载机/持续稳态压测、Kafka通知投递故障、Docker全链路尚未执行。复现见 ../scripts/perf/README.md。']
if (root/'docs/evidence/performance-faults.json').exists():
 fault=json.loads((root/'docs/evidence/performance-faults.json').read_text())
 assert all(r.get('rollbackVerified') or r.get('singleQualificationAndEvent') for r in fault['results'])
 lines+=['','## 额外故障回归','', '另行执行 --fault-only：专用库延迟触发器使初次请求发生客户端超时，清理后以原request_id重试，资格/名额/确认Outbox各1；Outbox失败导致的事务回滚也重测成功。见 evidence/performance-faults.json。额外故障注入不合并到正常RPS统计。']
(root/'docs/performance-report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
rows=[dict(E.parse(p).getroot().attrib) for p in root.glob('culturehub-*/target/surefire-reports/TEST-*.xml')]
totals={k:sum(int(r.get(k,0)) for r in rows) for k in ('tests','failures','errors','skipped')};totals['passed']=totals['tests']-totals['skipped']-totals['errors']-totals['failures']
(root/'docs/evidence/data-access-tests.json').write_text(json.dumps({'totals':totals,'suites':[{k:r[k] for k in ('name','tests','failures','errors','skipped')} for r in rows],'notes':'Inherited booking tests execute in multiple suites; not all executions are unique scenarios. Full Maven test plus targeted CatalogLocalTest after deferred-join XML change; package built with skipTests afterward.'},indent=2),encoding='utf-8')
print('Report generated from executed results:',totals)
