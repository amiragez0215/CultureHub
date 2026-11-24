"""Exercise the real Core, Partner A/B, MySQL, Redis, RabbitMQ and Seata AT."""
from contextlib import ExitStack
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time

spec = importlib.util.spec_from_file_location("p1", Path(__file__).resolve().parents[1] / "v3-p1/run-local.py")
p1 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p1)
sql = p1.root_sql
http = p1.SMOKE
schemas = {"CORE": "core_db", "PARTNER_A": "partner_db_a", "PARTNER_B": "partner_db_b"}
ports = {"CORE": 18110, "PARTNER_A": 18121, "PARTNER_B": 18122}
base = "http://127.0.0.1:18110"


def eventually(check, seconds=80):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.25)
    raise AssertionError("Condition did not become true")


def schema_setup():
    conf, _ = p1.setup()
    credentials = p1.WORK / "seata-partner-credentials.json"
    previous = p1.WORK / "saga-credentials.json"
    if credentials.exists():
        partners = json.loads(credentials.read_text(encoding="utf8"))
    elif previous.exists():
        partners = json.loads(previous.read_text(encoding="utf8"))
        credentials.write_text(json.dumps(partners), encoding="utf8")
    else:
        partners = {name: secrets.token_hex(24) for name in ("PARTNER_A", "PARTNER_B")}
        credentials.write_text(json.dumps(partners), encoding="utf8")
    schema_sql = (p1.ROOT / "deploy/mysql/01-schema.sql").read_text(encoding="utf8")
    for name, schema in schemas.items():
        if name != "CORE":
            account = "culturehub_" + name.lower()
            if not sql(f"select table_name from information_schema.tables where table_schema='{schema}' and table_name='app_user'"):
                sql(schema_sql.replace("EXISTS culturehub ", f"EXISTS {schema} ", 1)
                    .replace("USE culturehub;", f"USE {schema};", 1))
                grant_schema = schema.replace("_", r"\_")
                sql(f"create user '{account}'@'%' identified by '{partners[name]}'; "
                    f"grant select,insert,update,delete on `{grant_schema}`.* to '{account}'@'%';")
            checked = p1.mysql("select database();", account, partners[name], schema)
            assert checked.returncode == 0 and checked.stdout.strip() == schema
        migrations = ["20261111-v3-booking.sql", "20261114-v3-seata-at.sql"]
        if name == "CORE":
            migrations.append("20261114-v3-combination.sql")
        for migration in migrations:
            sql(f"USE {schema};\n" + (p1.ROOT / "deploy/migrations" / migration).read_text(encoding="utf8"))
    return conf, partners


def slot(schema, capacity=1, closed=False):
    state = "CANCELLED" if closed else "PUBLISHED"
    return int(sql(f"""
        use {schema};
        insert into venue(name) values('AT fixture');
        insert into activity(venue_id,title,status) values(last_insert_id(),'AT fixture','PUBLISHED');
        insert into activity_session(activity_id,registration_start_at,registration_end_at,start_at,end_at,
          checkin_open_at,checkin_close_at,capacity,status)
        values(last_insert_id(),date_sub(now(),interval 1 hour),date_add(now(),interval 1 hour),
          date_add(now(),interval 2 hour),date_add(now(),interval 3 hour),date_sub(now(),interval 1 hour),
          date_add(now(),interval 3 hour),{capacity},'{state}');
        select last_insert_id();
        """))


def item(name, session):
    return {"participant": name, "sessionId": session}


def redis(*args):
    # Minimal RESP reader for the isolated fixture. No additional Python dependency.
    encoded = [str(arg).encode() for arg in args]
    with socket.create_connection(("127.0.0.1", 16379), timeout=2) as connection:
        connection.sendall(("*%d\r\n" % len(args)).encode() + b"".join(
            ("$%d\r\n" % len(arg)).encode() + arg + b"\r\n" for arg in encoded))
        with connection.makefile("rb") as stream:
            line = stream.readline().rstrip(b"\r\n")
            if line.startswith(b"-"):
                raise AssertionError(line)
            if line.startswith(b"$"):
                size = int(line[1:])
                return None if size == -1 else stream.read(size).decode()
            return line[1:].decode()


def crash_and_recover(submit, final, status, assert_inventory, apps, options, stack, host, port):
    # Own the disposable TC process and its data so the test never stops a shared TC.
    home = Path(os.environ["SEATA_TEST_HOME"]).resolve()
    store = p1.WORK / ("tc-restart-" + secrets.token_hex(6))
    store.mkdir()
    jar = home / "target/seata-server.jar"
    assert jar.is_file()
    assert host == "127.0.0.1"
    def start():
        proc = subprocess.Popen([p1.JAVA, "-Xmx256m", "-XX:ActiveProcessorCount=2",
            "-Dloader.path=lib", "-Dspring.config.additional-location=./conf/",
            "-Dlogging.config=./conf/logback-spring.xml",
            "-Dseata.store.file.dir=" + str(store), "-Dseata.store.file.flush-disk-mode=sync",
            "-jar", str(jar), "-h", host, "-p", str(port), "-m", "file"],
            cwd=home, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=p1.NO_WINDOW)
        stack.callback(p1.stop, proc)
        eventually(lambda: listening(host, port), 20)
        assert proc.poll() is None
        return proc

    tc = start()
    for name, proc in apps.items():
        p1.stop(proc)
        apps[name] = p1.spawn(stack, "at-restart-" + name.lower(), "culturehub-api", ports[name], options[name])
        p1.ready("at-restart-" + name.lower(), apps[name], ports[name], "/actuator/health/readiness")

    items = [item(name, slot(schema)) for name, schema in schemas.items()]
    trigger = "at_crash_" + secrets.token_hex(5)
    last = items[-1]["sessionId"]
    sql(f"CREATE TRIGGER partner_db_b.{trigger} BEFORE INSERT ON partner_db_b.participation FOR EACH ROW "
        f"DO SLEEP(IF(NEW.session_id={last},7,0));")
    try:
        id = submit(items)
        def branch_ready():
            xid = sql(f"select coalesce(xid,'') from core_db.combination_request where id='{id}'")
            return xid and sql(f"select count(*) from partner_db_a.undo_log where xid='{xid}' and log_status=0") != "0"
        eventually(branch_ready, 12)
        p1.stop(apps["CORE"])
        p1.stop(tc)
        assert not listening(host, port)
        assert sql(f"select status from core_db.combination_request where id='{id}'") == "PROCESSING"
        start()
        apps["CORE"] = p1.spawn(stack, "at-core-recovered", "culturehub-api", ports["CORE"], options["CORE"])
        p1.ready("at-core-recovered", apps["CORE"], ports["CORE"], "/actuator/health/readiness")
        assert final(id, "FAILED")["db_result"] == "ROLLED_BACK"
        assert status(id)["allReleased"]
        assert_inventory(items, (0, 0, 0))
        print("PASS: worker and TC stopped with live undo; persisted TC session rolled back after restart", flush=True)
    finally:
        sql(f"drop trigger if exists partner_db_b.{trigger}")


def listening(host, port):
    try:
        with socket.create_connection((host, port), timeout=.5):
            return True
    except OSError:
        return False


def run():
    tc = os.environ.get("SEATA_SERVER_ADDR", "127.0.0.1:18091")
    host, port = tc.rsplit(":", 1)
    with socket.create_connection((host, int(port)), timeout=2):
        pass
    conf, partners = schema_setup()
    env = {**os.environ, **conf, "V3_DISCOVERY_ENABLED": "false", "RABBIT_HOST": "127.0.0.1",
           "RABBIT_PORT": "5673", "RABBIT_USER": "guest", "RABBIT_PASSWORD": "guest",
           "RABBIT_VHOST": "culturehub_v3p1_audit", "REDIS_HOST": "127.0.0.1", "REDIS_PORT": "16379",
           "JOBS_ENABLED": "false", "DEMO_PASSWORD": "", "V3_DEMO_ENABLED": "true",
           "SEATA_ENABLED": "true", "SEATA_SERVER_ADDR": tc,
           "V3_PARTNER_A_URL": "http://127.0.0.1:18121", "V3_PARTNER_B_URL": "http://127.0.0.1:18122"}
    with ExitStack() as stack:
        apps = {}
        options_by_name = {}
        for name, port in ports.items():
            options = {**env, "DB_URL": p1.jdbc(schemas[name]), "SPRING_PROFILES_ACTIVE": "v3-p4"}
            if name != "CORE":
                options.update(SPRING_PROFILES_ACTIVE="v3-partner", V3_PARTICIPANT=name,
                               V3_PARTNER_SERVICE_NAME="culturehub-" + name.lower().replace("_", "-"),
                               V3_PARTNER_DB_USER="culturehub_" + name.lower(),
                               V3_PARTNER_DB_PASSWORD=partners[name], V3_PARTNER_DB_SCHEMA=schemas[name])
            proc = p1.spawn(stack, "at-" + name.lower(), "culturehub-api", port, options)
            p1.ready("at-" + name.lower(), proc, port, "/actuator/health/readiness")
            apps[name] = proc
            options_by_name[name] = options

        auth = {"username": "at_" + secrets.token_hex(8), "password": secrets.token_hex(16)}
        registered, _ = http.expect(http.call(base, "/api/v1/auth/register", "POST", auth), 200, "register")
        login, _ = http.expect(http.call(base, "/api/v1/auth/login", "POST", auth), 200, "login")
        token = login["token"]

        def submit(items, partial=False):
            request = secrets.token_hex(16)
            path = "/api/v1/" + ("demo/" if any(i["participant"] != "CORE" for i in items) else "") + "combinations"
            payload = {"requestId": request, "confirmed": True, "retainPartial": partial, "items": items}
            first, _ = http.expect(http.call(base, path, "POST", payload, token), 200, "submit")
            replay, _ = http.expect(http.call(base, path, "POST", payload, token), 200, "replay")
            assert first["id"] == replay["id"]
            return first["id"]

        def status(id):
            result, _ = http.expect(http.call(base, f"/api/v1/combinations/{id}", token=token), 200, "status")
            return result

        def final(id, state):
            def matches():
                value = status(id)
                if value["status"] in ("SUCCEEDED", "FAILED", "PARTIALLY_SUCCEEDED", "MANUAL_INTERVENTION"):
                    assert value["status"] == state, value
                    return True
                return False
            eventually(matches)
            return status(id)

        def assert_inventory(items, expected):
            for i, count in zip(items, expected):
                schema = schemas[i["participant"]]
                session = i["sessionId"]
                assert sql(f"select occupied_count from {schema}.activity_session where id={session}") == str(count)
                assert sql(f"select count(*) from {schema}.participation where session_id={session} and active_guard=1") == str(count)

        local = [item("CORE", slot("core_db")), item("CORE", slot("core_db"))]
        id = submit(local)
        assert final(id, "SUCCEEDED")["db_result"] == "COMMITTED"
        assert sql(f"select xid is null from core_db.combination_request where id='{id}'") == "1"
        assert_inventory(local, (1, 1))
        reserved_id = "v3-at-" + secrets.token_hex(16)
        fresh = slot("core_db")
        refused = http.call(base, f"/api/v1/sessions/{fresh}/reservations", "POST",
                            {"request_id": reserved_id}, token)
        assert refused[0] in (400, 409) and refused[1]["code"] == "INVALID_REQUEST_ID", refused
        invalid_local = [item("CORE", slot("core_db")), item("CORE", slot("core_db", closed=True))]
        assert final(submit(invalid_local), "FAILED")["allReleased"]
        assert_inventory(invalid_local, (0, 0))
        print("PASS: same-schema local transaction, invalid local group, stable HTTP request replay", flush=True)

        # Delay the last branch to observe the first two durable undo images before the TC resolves.
        for should_fail in (False, True):
            items = [item(name, slot(schema)) for name, schema in schemas.items()]
            last = items[-1]["sessionId"]
            trigger = "at_fault_" + secrets.token_hex(5)
            failure = "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AT fixture failure';" if should_fail else ""
            sql(f"""USE partner_db_b;
DELIMITER //
CREATE TRIGGER {trigger} BEFORE INSERT ON participation FOR EACH ROW
BEGIN
  IF NEW.session_id={last} AND NEW.request_id LIKE 'v3-at-%' AND NEW.request_id NOT LIKE 'v3-at-partial-%' THEN
    DO SLEEP(2);
    {failure}
  END IF;
END//
DELIMITER ;
""")
            try:
                id = submit(items)
                def xid_seen():
                    xid = sql(f"select coalesce(xid,'') from core_db.combination_request where id='{id}'")
                    return xid and all(sql(f"select count(*) from {schema}.undo_log where xid='{xid}' and log_status=0") != "0"
                                       for schema in ("core_db", "partner_db_a"))
                eventually(xid_seen, 12)
                if should_fail:
                    result = final(id, "FAILED")
                    assert result["allReleased"] and result["db_result"] == "ROLLED_BACK"
                    assert_inventory(items, (0, 0, 0))
                    for i in items:
                        schema = schemas[i["participant"]]
                        assert sql(f"select count(*) from {schema}.booking_request where request_id like 'v3-at-{id}-%' and inventory_synced=true") == "1"
                else:
                    assert final(id, "SUCCEEDED")["db_result"] == "COMMITTED"
                    assert_inventory(items, (1, 1, 1))
                    for i in items:
                        schema = schemas[i["participant"]]
                        assert sql(f"select count(*) from {schema}.outbox o join {schema}.participation p "
                                   f"on o.business_key=concat('confirmed:',p.id) where p.session_id={i['sessionId']}") == "1"
                xid = sql(f"select xid from core_db.combination_request where id='{id}'")
                eventually(lambda: all(sql(f"select count(*) from {schema}.undo_log where xid='{xid}' and log_status=0") == "0"
                                       for schema in schemas.values()))
                for name in ("CORE", "PARTNER_A"):
                    assert f"xid={xid} group={id}" in (p1.WORK / f"at-{name.lower()}-stdout.log").read_text(encoding="utf8")
                if not should_fail:
                    assert f"xid={xid} group={id}" in (p1.WORK / "at-partner_b-stdout.log").read_text(encoding="utf8")
                print(f"PASS: AT {'late Partner B failure and rollback' if should_fail else 'three-service commit'}; matching XID and undo cleanup", flush=True)
            finally:
                sql(f"drop trigger if exists partner_db_b.{trigger}")

        partial = [item("CORE", slot("core_db")), item("PARTNER_A", slot("partner_db_a")),
                   item("PARTNER_B", slot("partner_db_b", closed=True))]
        id = submit(partial, True)
        assert final(id, "PARTIALLY_SUCCEEDED")["db_result"] == "ROLLED_BACK"
        assert_inventory(partial, (1, 1, 0))
        assert sql(f"select count(*) from core_db.participation where user_id={registered['id']} and request_id like 'v3-at-partial-{id}-%'") == "1"
        print("PASS: explicit partial authorization starts independent bookings after rollback and release", flush=True)

        for should_fail in (False, True):
            items = [item(name, slot(schema)) for name, schema in schemas.items()]
            last = items[-1]["sessionId"]
            trigger = "at_settle_" + secrets.token_hex(5)
            failure = "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AT settlement fixture';" if should_fail else ""
            sql(f"""USE partner_db_b;
DELIMITER //
CREATE TRIGGER {trigger} BEFORE INSERT ON participation FOR EACH ROW
BEGIN
  IF NEW.session_id={last} THEN
    DO SLEEP(2);
    {failure}
  END IF;
END//
DELIMITER ;
""")
            session = items[0]["sessionId"]
            key = f"ch:booking:{{{session}}}:stock"
            try:
                id = submit(items)
                eventually(lambda: sql(f"select count(*) from core_db.combination_request where id='{id}' and xid is not null") == "1", 10)
                assert redis("HGET", key, "held") == "1"
                redis("HSET", key, "held", "fixture-invalid")
                eventually(lambda: status(id)["status"] == "SETTLING")
                expected = "ROLLED_BACK" if should_fail else "COMMITTED"
                assert status(id)["db_result"] == expected
                assert sql(f"select count(*) from core_db.booking_request where request_id like 'v3-at-{id}-%' and inventory_synced=false") == "1"
                redis("HSET", key, "held", 1)
                final(id, "FAILED" if should_fail else "SUCCEEDED")
                assert redis("HGET", key, "held") == "0"
                assert redis("HGET", key, "request:v3-at-" + id + "-" + str(session)) == "CLOSED"
                assert_inventory(items, (0, 0, 0) if should_fail else (1, 1, 1))
                print(f"PASS: Redis settlement failure preserves {expected} and resumes from durable state", flush=True)
            finally:
                sql(f"drop trigger if exists partner_db_b.{trigger}")

        # Repeat the same transport command, bypassing HTTP request deduplication.
        redis("XADD", "ch:booking:combinations:stream", "*", "request", id, "trace", "fixture")
        eventually(lambda: redis("XLEN", "ch:booking:combinations:stream") == "0")
        assert status(id)["status"] == "FAILED"
        assert_inventory(items, (0, 0, 0))
        print("PASS: duplicate Redis Stream relay / RabbitMQ command does not execute a second transaction", flush=True)

        if os.environ.get("SEATA_TEST_RESTART") == "true":
            restart_port = int(os.environ.get("SEATA_TEST_RESTART_PORT", "18092"))
            assert not listening(host, restart_port), "Dedicated TC restart port is occupied"
            for options in options_by_name.values():
                options["SEATA_SERVER_ADDR"] = f"{host}:{restart_port}"
            crash_and_recover(submit, final, status, assert_inventory, apps, options_by_name, stack, host, restart_port)


if __name__ == "__main__":
    run()
