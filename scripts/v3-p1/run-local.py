import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / ".tools" / "v3-p1"
WORK.mkdir(parents=True, exist_ok=True)
JAVA = os.environ.get("V3_TEST_JAVA", shutil.which("java"))
MYSQL = os.environ.get("V3_TEST_MYSQL", shutil.which("mysql"))
MYSQL_CLI = [MYSQL, "--no-defaults", "--protocol=TCP", "--host=127.0.0.1",
             "--port=13306", "--default-character-set=utf8mb4", "-NB"]
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
SPEC = importlib.util.spec_from_file_location("p1_smoke", ROOT / "scripts/v3-p1/smoke.py")
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)
roles = {
    "core": ("core_db", "CORE_DB_PASSWORD"),
    "notification": ("notification_db", "NOTIFICATION_DB_PASSWORD"),
    "ai": ("ai_db", "AI_DB_PASSWORD"),
    "knowledge": ("knowledge_db", "KNOWLEDGE_DB_PASSWORD"),
    "jobs": ("xxl_job", "JOBS_DB_PASSWORD"),
}


def mysql(sql, user="root", password="", schema=None):
    flags = [*MYSQL_CLI, "--user=" + user]
    if schema:
        flags.append("--database=" + schema)
    env = {**os.environ, "MYSQL_PWD": password}
    return subprocess.run(flags, input=sql, encoding="utf-8", capture_output=True, env=env,
                          creationflags=NO_WINDOW, timeout=10)


def root_sql(sql):
    result = mysql(sql)
    if result.returncode:
        raise AssertionError(result.stderr)
    return result.stdout.strip()


def setup():
    state = WORK / "p1-fixture-credentials.json"
    if state.exists():
        conf = json.loads(state.read_text(encoding="utf-8"))
    else:
        conf = {key: secrets.token_hex(24) for _, key in roles.values()}
        for key in ("JWT_SECRET", "V3_PROBE_TOKEN", "XXL_JOB_TOKEN", "V3_AI_CORE_TOKEN",
                    "V3_KNOWLEDGE_CORE_TOKEN", "V3_COMBINATION_TOKEN", "V3_NOTIFICATION_CORE_TOKEN"):
            conf[key] = secrets.token_hex(24)
        state.write_text(json.dumps(conf), encoding="utf-8")
    if not root_sql("select table_name from information_schema.tables "
                    "where table_schema='notification_db' and table_name='notification';"):
        raise RuntimeError("Initialize the dedicated P1 fixture first using deploy/v3-p1/00-init.sh")
    for role, (schema, key) in roles.items():
        account = f"culturehub_{role}"
        result = mysql("select database();", account, conf[key], schema)
        assert result.returncode == 0 and result.stdout.strip() == schema, (role, result.stderr)
        for other_schema, _ in roles.values():
            if other_schema != schema:
                denial = mysql("select database();", account, conf[key], other_schema)
                assert denial.returncode != 0 and "1044" in denial.stderr, (role, other_schema)
    root_sql("create database if not exists coreXdb;")
    wildcard = mysql("select database();", "culturehub_core", conf["CORE_DB_PASSWORD"], "coreXdb")
    assert wildcard.returncode != 0 and "1044" in wildcard.stderr
    print("PASS: 5 SQL accounts, 20 cross-schema denials and literal underscore", flush=True)
    session = root_sql("""
        use core_db;
        insert into venue(name) values('V3 P1 Test');
        insert into activity(venue_id,title,status) values(last_insert_id(),'V3 P1 activity','PUBLISHED');
        insert into activity_session(activity_id,registration_start_at,registration_end_at,start_at,end_at,
          checkin_open_at,checkin_close_at,capacity,status)
        values(last_insert_id(),date_sub(now(),interval 1 hour),date_add(now(),interval 1 hour),
          date_add(now(),interval 2 hour),date_add(now(),interval 3 hour),date_add(now(),interval 2 hour),
          date_add(now(),interval 3 hour),30,'PUBLISHED');
        select last_insert_id();
    """)
    return conf, int(session)


def jdbc(schema):
    return (f"jdbc:mysql://127.0.0.1:13306/{schema}?characterEncoding=UTF-8"
            "&connectionTimeZone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false")


def spawn(stack, name, module, port, env):
    out = stack.enter_context((WORK / f"{name}-stdout.log").open("w", encoding="utf-8"))
    err = stack.enter_context((WORK / f"{name}-stderr.log").open("w", encoding="utf-8"))
    jar = ROOT / module / "target" / f"{module}-1.0.0.jar"
    proc = subprocess.Popen(
        [JAVA, "-XX:ActiveProcessorCount=4", "-Xmx384m", "-Duser.timezone=Asia/Shanghai",
         f"-Djava.io.tmpdir={WORK / 'tmp'}", "-jar", str(jar), f"--server.port={port}",
         "--server.address=127.0.0.1"],
        cwd=WORK, env=env, stdout=out, stderr=err, creationflags=NO_WINDOW)
    stack.callback(stop, proc)
    return proc


def stop(proc):
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)


def ready(name, proc, port, path="/actuator/health"):
    until = time.monotonic() + 75
    while time.monotonic() < until and proc.poll() is None:
        try:
            if SMOKE.call(f"http://127.0.0.1:{port}", path)[0] == 200:
                print(f"PASS: {name} independently started on {port}", flush=True)
                return
        except OSError:
            pass
        time.sleep(0.5)
    raise RuntimeError(f"{name} unavailable. See {name}-stdout.log and stderr log.")


def run(nacos):
    conf, session = setup()
    auth = {"JWT_SECRET": conf["JWT_SECRET"]}
    cloud = {"V3_DISCOVERY_ENABLED": "true" if nacos else "false",
             "SPRING_CLOUD_NACOS_DISCOVERY_IP": "127.0.0.1",
             "NACOS_SERVER_ADDR": nacos or "127.0.0.1:8848"}
    if not nacos:
        cloud["V3_CORE_URL"] = "http://127.0.0.1:18110"
    env = {**os.environ, **cloud}
    broker = {"RABBIT_HOST": "127.0.0.1", "RABBIT_PORT": "5673", "RABBIT_USER": "guest",
              "RABBIT_PASSWORD": "guest", "RABBIT_VHOST": "culturehub_v3p1_audit"}
    with ExitStack() as stack:
        worker = spawn(stack, "p1-notification", "culturehub-notification-worker", 18111,
                       {**env, **auth, **broker, "SPRING_PROFILES_ACTIVE": "v3-p1",
                        "DB_URL": jdbc("notification_db"),
                        "NOTIFICATION_DB_PASSWORD": conf["NOTIFICATION_DB_PASSWORD"],
                        "V3_NOTIFICATION_CORE_TOKEN": conf["V3_NOTIFICATION_CORE_TOKEN"]})
        boundaries = {}
        for offset, role in enumerate(("ai", "knowledge"), 12):
            schema, key = roles[role]
            boundaries[role] = (18100 + offset, spawn(
                stack, f"p1-{role}", "culturehub-service-runtime", 18100 + offset,
                {**env, "V3_ROLE": role, "V3_DB_URL": jdbc(schema),
                 "V3_DB_USER": f"culturehub_{role}", "V3_DB_PASSWORD": conf[key],
                 "V3_PROBE_TOKEN": conf["V3_PROBE_TOKEN"], "V3_CORE_TOKEN": conf[f"V3_{role.upper()}_CORE_TOKEN"]}))
        ready("p1-notification", worker, 18111)
        for role, (port, proc) in boundaries.items():
            ready(f"p1-{role}", proc, port)
        core = spawn(stack, "p1-core", "culturehub-api", 18110,
                     {**env, **conf, **broker, "SPRING_PROFILES_ACTIVE": "v3-p1",
                      "DB_URL": jdbc("core_db"), "REDIS_HOST": "127.0.0.1", "REDIS_PORT": "16379",
                      "JOBS_ENABLED": "true", "XXL_JOB_ADMIN": "", "DEMO_PASSWORD": "",
                      "AI_CHAT_PROVIDER": "none", "AI_EMBEDDING_PROVIDER": "none", "AI_VECTORSTORE": "none"})
        ready("p1-core", core, 18110, "/actuator/health/readiness")
        gateway_routes = {} if nacos else {
            "V3_CORE_ROUTE": "http://127.0.0.1:18110",
            "V3_NOTIFICATION_ROUTE": "http://127.0.0.1:18111",
        }
        gateway = spawn(stack, "p1-gateway", "culturehub-gateway", 18109,
                        {**env, **auth, **gateway_routes, "V3_GATEWAY_RPS": "20"})
        ready("p1-gateway", gateway, 18109)
        os.environ["V3_PROBE_TOKEN"] = conf["V3_PROBE_TOKEN"]
        os.environ["XXL_JOB_TOKEN"] = conf["XXL_JOB_TOKEN"]
        SMOKE.run(argparse.Namespace(gateway="http://127.0.0.1:18109", session=session,
                                     executor="http://127.0.0.1:9999", core="http://127.0.0.1:18110",
                                     probe=[(role, f"http://127.0.0.1:{port}")
                                            for role, (port, _) in boundaries.items()]))
        assert root_sql(f"select count(*) from notification_db.notification where participation_id="
                        f"(select id from core_db.participation where session_id={session});") == "1"
        assert root_sql("select count(*) from core_db.notification;") == "0"
        with ThreadPoolExecutor(max_workers=32) as pool:
            statuses = list(pool.map(lambda _: SMOKE.call("http://127.0.0.1:18109", "/api/v1/venues")[0],
                                     range(40)))
        assert set(statuses) == {200, 429}, sorted(set(statuses))
        print(f"PASS: Sentinel route protection returned {statuses.count(429)} HTTP 429 responses", flush=True)
        print("PASS: notification persisted only in notification_db", flush=True)


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else None)
