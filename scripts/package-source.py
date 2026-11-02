"""Create a source-only share archive, without runtime or seed data.

Test source and schema definitions are preserved. No running database is read.
"""
from pathlib import Path
import hashlib
import os
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'CultureHub-V1.0-source-no-data.zip'
EXCLUDED_DIRS = {'.tools', '.git', '.idea', '.vscode', 'target', 'node_modules', 'dist', 'logs', '__pycache__', 'evidence', 'knowledge', 'demo'}
EXCLUDED_PATHS = {'deploy/mysql/02-demo.sql', 'deploy/mysql/04-zh-demo.sql', 'docs/verification.md', 'README.md'}
ALLOWED_SUFFIXES = {'.java', '.xml', '.yml', '.yaml', '.js', '.mjs', '.cjs', '.json', '.html', '.css', '.md', '.py', '.sql', '.conf', '.svg', '.ps1'}
README = '''# CultureHub V1.0｜源码分享版（无数据）

这是 Java 17 / Spring Boot 3 + Vue 3 项目。包含四个 Maven 模块、前端、数据库表结构、配置模板和测试源码。

AI1 已在现有项目中增量接入 Spring AI Alibaba Graph Core，多轮推荐后需用户明确确认，才调用原预约入口；AI2/AI3 继续 ChatClient。设计与测试说明见 docs/ai-graph-upgrade.md。已有库须执行 deploy/migrations/20261101-ai-booking-proposal.sql；新库 DDL 已包含方案表。

本轮还完成公开场次 MGET 缓存、批量 SQL 统计、AI1 POST SSE 与停止生成、场馆 CRUD 的 MP 写入。缓存语义、SSE 协议、ORM 清单与学习导读见 docs/technical-enhancement.md。已有库另执行 deploy/migrations/20261101-participation-history-index.sql；分享版不包含 evidence 中的本机测试运行记录。

## 包含与排除

保留代码、接口文档、设计说明、依赖版本锁定、Docker 构建文件、Compose 和纯建表 SQL。
不包含数据库文件或导出记录、模拟活动数据、用户与预约记录、通知与评价、AI 报告、知识文档正文、预置账号与密码哈希、日志、测试执行数据、密钥、依赖缓存和构建产物。
测试用例保留，其代码在执行时生成临时测试场景；这些测试常量不是真实用户数据。

## 开发工具

建议使用 VS Code，打开本文件所在的项目根目录；安装 Java、Spring Boot 和 Vue 扩展。后端也可使用 IntelliJ IDEA。PyCharm 不是本项目的主要开发工具。

## 启动

需要 Docker Desktop（Linux 容器）及 Docker Compose；本地开发另需 JDK 17、Maven、Node 22。

复制 .env.example 为 .env，填写自己的数据库密码、JWT 密钥和任务 Token。默认不生成演示账号，不启用模型。随后执行：

    docker compose up --build -d

Compose 前端为 http://localhost:8089，API 为 http://localhost:8080/api/v1。

这是空数据库版本：首次进入页面没有场馆和活动，不能直接使用原预览账号。可以注册自己的账号，再由可信初始化过程授予平台管理员角色。DEMO_PASSWORD 配置项为空时不会生成演示账号；如自行设置它，则程序会按 Seed.java 创建本地演示账号，这属于接收者主动初始化的数据。

XXL-JOB 脚本也只保留表结构：控制台管理员、schedule_lock 记录、执行器与三个任务需由接收者自行初始化；没有这些配置时定时任务不能自动工作。任务名称与配置依据 Jobs.java 和 compose.yaml。

本地开发构建：

    mvn -s settings.xml package

运行主 API 的 ApiApplication 和 Worker 的 WorkerApplication，前端在 web 目录运行 npm ci 与 npm run dev。先配置 MySQL、Redis、Kafka 等依赖，IDE 不代替基础服务。

## AI

三个入口为智能导览、智能活动策划、智能复盘。需要接收者自行配置聊天和 Embedding 模型，并提供有权使用的知识文档。详细说明见 docs/ai-and-startup.md。该文档中 15173 为原开发机器的临时预览端口，不是解压后的默认地址。

## 验证边界

原开发环境完成了后端构建、前端构建、真实 MySQL/Redis 核心测试及 HTTP 冒烟，并完成 DeepSeek 真实聊天的导览、策划和复盘联调。密钥不随源码分享，接收者需使用自己的凭据。完整 Compose、真实 Kafka 链路与 Embedding/Chroma 效果仍需在对应环境执行验证；不能把源码或单个模型测试等同于全环境验收通过。

普通单元测试不需要 Docker；无 Docker 时容器测试会跳过。配置专用本地 MySQL/Redis 时也能执行核心集成测试，见技术增强导读。不要在真实业务数据库上启用会清表的本地测试模式。
'''

def included(path):
    relative = path.relative_to(ROOT).as_posix()
    if relative in EXCLUDED_PATHS or path.is_symlink():
        return False
    if path.name.startswith('.env') and path.name != '.env.example':
        return False
    return (path.suffix in ALLOWED_SUFFIXES or path.name in {'.env.example', '.gitignore', '.dockerignore', 'Dockerfile', 'org.mockito.plugins.MockMaker'} or path.name.startswith('Dockerfile.'))

with zipfile.ZipFile(str(OUTPUT), 'w', zipfile.ZIP_DEFLATED) as archive:
    for folder, directories, files in os.walk(str(ROOT)):
        directories[:] = sorted(d for d in directories if d not in EXCLUDED_DIRS)
        for name in sorted(files):
            path = Path(folder) / name
            if not included(path):
                continue
            relative = path.relative_to(ROOT).as_posix()
            content = path.read_bytes()
            if relative == 'deploy/mysql/03-xxl-job.sql':
                sql = content.decode('utf-8')
                sql = re.sub(r'(?ims)^\s*(?:INSERT|REPLACE|UPDATE|DELETE|LOAD\s+DATA)\b.*?;\s*', '\n', sql)
                content = ('-- Source-only share: initialization rows intentionally excluded.\n' + sql).encode('utf-8')
            if relative == '.env.example':
                content = re.sub(r'(?m)^DEMO_PASSWORD=.*$', 'DEMO_PASSWORD=', content.decode('utf-8')).encode('utf-8')
            if relative == 'scripts/smoke.py':
                content = content.decode('utf-8').replace("os.environ.get('DEMO_PASSWORD','DemoPass123')", "os.environ['DEMO_PASSWORD']").encode('utf-8')
            archive.writestr('CultureHub/' + relative, content)
    archive.writestr('CultureHub/README.md', README.encode('utf-8'))
    archive.writestr('CultureHub/culturehub-business/src/main/resources/knowledge/.gitkeep', b'')

with zipfile.ZipFile(str(OUTPUT)) as archive:
    assert archive.testzip() is None
    for name in archive.namelist():
        parts = Path(name).parts
        assert not any(part in EXCLUDED_DIRS - {'knowledge', 'demo'} for part in parts), name
        assert not name.endswith(('.log', '.zip', '.db', '.rdb', '.aof', '.jar', '.class')), name
        assert not name.endswith('/.env'), name
        if name.endswith('.sql'):
            sql = archive.read(name).decode('utf-8')
            assert not re.search(r'(?im)^\s*(INSERT|REPLACE|UPDATE|DELETE|LOAD\s+DATA)\b', sql), name
    count = len(archive.namelist())
print('Archive: ' + str(OUTPUT))
print('Files: ' + str(count))
print('Bytes: ' + str(OUTPUT.stat().st_size))
print('SHA256: ' + hashlib.sha256(OUTPUT.read_bytes()).hexdigest())
print('Validated: source only; SQL contains schema but no data statements.')
