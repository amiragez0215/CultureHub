# -*- coding: utf-8 -*-
"""Audit the messaging-only diff and record actual test evidence, never performance estimates."""
from pathlib import Path
import datetime
import hashlib
import json
import subprocess
import xml.etree.ElementTree as E

root=Path(__file__).resolve().parents[2]
def git(*args):
    return subprocess.check_output(['git',*args],cwd=str(root)).decode('utf-8').strip()
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
baseline=json.loads((root/'docs/evidence/rabbitmq-baseline.json').read_text(encoding='utf-8'))
stable=baseline['commit']
assert git('branch','--show-current')=='migration/rabbitmq'
assert subprocess.run(['git','merge-base','--is-ancestor',stable,'HEAD'],cwd=str(root)).returncode==0
for path, expected in baseline['protectedFiles'].items():
    assert digest(root/path)==expected, ('Performance evidence modified',path)
archive=json.loads((root/'docs/migration-baseline/kafka-tests.json').read_text(encoding='utf-8'))
for row in archive['tests']:
    assert digest(root/row['archive'])==row['sha256']
    assert subprocess.check_output(['git','show',stable+':'+row['source']],cwd=str(root))==(root/row['archive']).read_bytes()
old_evidence=git('ls-tree','-r','--name-only',stable,'--','docs/evidence').splitlines()
for path in old_evidence:
    # Respect the repository's text filters (Windows checkout may use CRLF).
    assert git('hash-object','--path='+path,path)==git('rev-parse',stable+':'+path),path
# Check every changed tracked/new source against an explicit migration scope.
paths=set(git('diff','--name-only',stable).splitlines()) | set(git('ls-files','--others','--exclude-standard').splitlines())
artifacts={p for p in paths if '/' not in p and p.startswith('CultureHub-RabbitMQ-') and p.endswith('.zip')}
paths-=artifacts  # Locally generated delivery ZIP is never staged or treated as source.
reasons={'.env.example': 'RabbitMQ external configuration template', 'README.md': 'Current messaging stack and pending acceptance notice', 'compose.yaml': 'Replace Kafka with durable RabbitMQ service', 'culturehub-api/src/main/resources/application.yml': 'RabbitMQ connection and confirms only', 'culturehub-business/pom.xml': 'AMQP/Rabbit test dependencies; test-scoped Worker support artifact', 'culturehub-contracts/pom.xml': 'Boot-managed common AMQP transport/topology dependency', 'culturehub-notification-worker/pom.xml': 'AMQP dependencies and attached test support jar; executable artifact unchanged', 'culturehub-business/src/main/java/cn/culturehub/Publisher.java': 'Outbox lease, Confirm/Return, recovery and microsecond due comparison', 'culturehub-contracts/src/main/java/cn/culturehub/RabbitTransport.java': 'Shared confirmed persistent send', 'culturehub-contracts/src/main/java/cn/culturehub/RabbitTopology.java': 'Shared exchanges, durable queues and bindings', 'culturehub-notification-worker/src/main/java/cn/culturehub/NotificationConsumer.java': 'Remove Kafka listener annotation only; DB transaction unchanged', 'culturehub-notification-worker/src/main/java/cn/culturehub/RabbitNotificationListener.java': 'Manual ACK, bounded recovery and isolation adapter', 'culturehub-notification-worker/src/main/java/cn/culturehub/WorkerConfiguration.java': 'Manual ACK/prefetch/recovery configuration', 'culturehub-notification-worker/src/main/java/cn/culturehub/DeadLetterReplay.java': 'Bounded RabbitMQ DLQ replay preserving IDs', 'culturehub-notification-worker/src/main/resources/application.yml': 'Broker connection and listener properties', 'culturehub-business/src/test/java/cn/culturehub/PublisherIntegrationTest.java': 'Original two publisher scenarios migrated with assertions plus Return case', 'culturehub-notification-worker/src/test/java/cn/culturehub/WorkerKafkaTest.java': 'Replaced only after user-approved exact baseline archival', 'culturehub-notification-worker/src/test/java/cn/culturehub/WorkerRabbitTest.java': 'Original four Worker scenarios plus exhaustion/lost-ack/replay', 'culturehub-notification-worker/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker': 'Subclass mocking for transport tests without Java agent', 'docs/design.md': 'Current message guarantees, no business state-machine changes', 'docs/ai-and-startup.md': 'Startup infrastructure name only; AI implementation unchanged', 'docs/learning-and-demo.md': 'Add historical-baseline notice and link current message startup guide', 'scripts/package-source.py': 'Messaging name in share README only', 'scripts/collect-verification.py': 'Collect new message test names; not executed against old evidence', 'culturehub-business/src/test/java/cn/culturehub/RabbitReliabilityLocalTest.java': '真实 Quorum Broker 下的批次隔离、Return、重试/DLQ、提交后 JVM 退出及重投验收', 'culturehub-business/src/test/java/cn/culturehub/LostAckWorkerProcess.java': '仅测试进程：真实通知提交后 halt，不发送 ACK'}

for path in paths:
    if path.startswith('docs/migration-baseline/'):
        reasons[path]='用户已授权原样归档的旧测试及校验记录'
    if path.startswith(('docs/rabbitmq-','docs/evidence/rabbitmq-','scripts/rabbitmq/')):
        reasons[path]='消息迁移专用文档、真实验收证据或复现工具'
    if path.startswith('culturehub-business/src/test/java/cn/culturehub/') and Path(path).name in [
        'OutboxRabbitLocalTest.java','RabbitConfirmTest.java','RabbitEndToEndTest.java',
        'RabbitEndToEndContainerTest.java','RabbitEndToEndLocalTest.java']:
        reasons[path]='消息可靠性、真实数据库回归或真实 Broker 测试'
    if path=='culturehub-notification-worker/src/test/java/cn/culturehub/ManualAckTest.java':
        reasons[path]='受控 ACK/重试协议单测；不冒充真实 Broker 验收'
unknown=sorted(paths-set(reasons))
assert not unknown, ('Unrelated changes',unknown)
core_changes=git('diff','--name-only',stable,'--','culturehub-business/src/main','culturehub-api/src/main/java','web').splitlines()
assert core_changes==['culturehub-business/src/main/java/cn/culturehub/Publisher.java'],core_changes
assert not git('diff','--name-only',stable,'--','culturehub-contracts/src/main/java/cn/culturehub/Event.java')
assert subprocess.run(['git','diff','--check',stable],cwd=str(root)).returncode==0
rows=[dict(E.parse(p).getroot().attrib) for p in root.glob('culturehub-*/target/surefire-reports/TEST-*.xml')]
current={r['name']:r for r in rows}
for row in baseline['tests']:
    replacement='cn.culturehub.WorkerRabbitTest' if row['name']=='cn.culturehub.WorkerKafkaTest' else row['name']
    assert int(current[replacement]['tests'])>=int(row['tests']),row['name']
    if int(row['skipped'])==0:
        assert int(current[replacement]['skipped'])==0,('Previously runnable suite skipped',replacement)
totals={k:sum(int(r[k]) for r in rows) for k in ('tests','failures','errors','skipped')}
totals['passed']=totals['tests']-totals['skipped']-totals['failures']-totals['errors']
real_names=['cn.culturehub.RabbitReliabilityLocalTest','cn.culturehub.RabbitEndToEndLocalTest']
real_junit=all(name in current and int(current[name]['tests'])>0 and
    all(int(current[name][k])==0 for k in ['failures','errors','skipped']) for name in real_names)
startup=json.loads((root/'docs/evidence/rabbitmq-standalone-acceptance.json').read_text(encoding='utf-8'))
accepted=real_junit and startup['passed'] and totals['failures']==totals['errors']==0
record={'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'baselineCommit':stable,'branch':git('branch','--show-current'),'verifiedSourceHead':git('rev-parse','HEAD'),
    'currentTotals':totals,'suites':[{k:r[k] for k in ('name','tests','failures','errors','skipped','time')} for r in rows],
    'protectedPerformanceHashesUnchanged':True,'allPriorStableEvidenceUnchanged':True,
    'archiveVerified':True,'outOfScopeChanges':unknown,'coreBusinessChanges':['Publisher.java (messaging only)'],
    'realRabbitIntegrationVerified':accepted,'standaloneAcceptanceChecks':len(startup['checks']),
    'deployment':'Workspace portable RabbitMQ 4.1.4 / Erlang 27.3.4.1; MySQL 8.0.27, Redis 7.4.9; no Docker',
    'finalCommitAllowed':accepted,'mergedToMain':False,'artifactsExcludedFromSource':sorted(artifacts)}
(root/'docs/evidence/rabbitmq-tests.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
lines=['# RabbitMQ 迁移逐文件范围核查','',
    '对照稳定提交 `'+stable+'`。原性能脚本、原始数据和报告逐文件 SHA256 一致；既有证据未改；旧 Kafka 测试归档与 Git 原始字节一致。','',
    '| 文件 | 原因 |','|---|---|']
for path in sorted(paths):
    lines.append('| `'+path+'` | '+reasons[path]+' |')
lines+=['','业务目录唯一实现差异为消息发布器 Publisher.java。预约、取消、FIFO、签到、评价、统计、Redis、MyBatis、AI Graph/HITL/SSE 和前端均无实现修改。',
    'Publisher 相关测试将“单条发送失败抛出异常”改为“批次继续且失败事件保留”，对应本轮明确要求；原数据库状态、重试次数、容量和幂等断言仍保留。',
    '根目录生成的 ZIP 作为本地交付物排除，不提交到 Git。未合并 main。']
(root/'docs/rabbitmq-diff.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
assert accepted,'Acceptance incomplete; do not commit final migration'
print(json.dumps({'totals':totals,'realBrokerVerified':accepted,'standaloneChecks':len(startup['checks']),'outsideScope':unknown},ensure_ascii=False))
