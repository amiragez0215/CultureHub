# -*- coding: utf-8 -*-
"""Real standalone API/Worker recovery against the isolated portable local broker.

Uses a dedicated disposable schema and the real XXL-JOB executor HTTP protocol;
the small trigger loop replaces only XXL Admin's clock, never the job handler.
No mock broker, delivery override, secret .env access, or business implementation changes.
"""
import argparse
import base64
import datetime
import json
import os
import re
from pathlib import Path
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = "culturehub_rabbit_acceptance"
API = "http://127.0.0.1:18091"
TOKEN = "isolated-rabbit-acceptance-job-token"
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def wait_for(check, timeout=90):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            result = check()
            if result:
                return result
        except (OSError, ValueError, urllib.error.URLError):
            pass
        time.sleep(0.25)
    raise AssertionError("Timed out waiting for " + str(check))


def port_open(port):
    try:
        with socket.create_connection(("127.0.0.1", port), 0.5):
            return True
    except OSError:
        return False


def http(path, body=None, token=None, base=API, headers=None):
    h = {"Content-Type": "application/json", **(headers or {})}
    if token:
        h["Authorization"] = "Bearer " + token
    request = urllib.request.Request(base + path, headers=h,
                                     data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql", default=os.environ.get("MYSQL_EXE", "mysql"))
    args = parser.parse_args()
    state = ROOT / ".tools/rabbit-acceptance"
    state.mkdir(exist_ok=True)
    evidence = {"startedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "schema": SCHEMA, "broker": "RabbitMQ 4.1.4 / OTP 27.3.4.1", "amqpPort": 5673,
                "vhost": "culturehub_acceptance", "scheduler": "Real XXL executor; Python periodic HTTP trigger (no Admin)",
                "checks": [], "passed": False}
    processes, logs = [], []
    stop = threading.Event()
    trigger_errors = []
    # Windows Oracle javapath/java.exe may fork a real JDK process. Track the actual
    # JVM executable so terminating Popen also terminates the tested service.
    java_settings = subprocess.run(["java", "-XshowSettings:properties", "-version"],
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True,
                                   creationflags=NO_WINDOW).stdout.decode(errors="replace")
    java_home = re.search(r"^\s*java.home\s*=\s*(.+)$", java_settings, re.MULTILINE)
    if not java_home:
        raise RuntimeError("Cannot resolve actual JDK java.home")
    java_executable = str(Path(java_home.group(1).strip()) / "bin" / ("java.exe" if os.name == "nt" else "java"))

    def sql(statement, database=SCHEMA):
        command = [args.mysql, "--protocol=TCP", "-h127.0.0.1", "-P13306", "-uroot",
                   "--default-character-set=utf8mb4", "--batch", "--skip-column-names"]
        if database:
            command.append(database)
        result = subprocess.run(command, input=statement.encode("utf-8"), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, check=True, creationflags=NO_WINDOW)
        return result.stdout.decode("utf-8").strip()

    def checked(name, condition, **data):
        assert condition, name + ": " + json.dumps(data)
        evidence["checks"].append({"name": name, "passed": True, **data})
        print("PASS " + name, flush=True)

    def broker(action):
        with (state / ("broker-" + action + ".log")).open("ab") as output:
            subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                            str(ROOT / "scripts/rabbitmq/local-broker.ps1"), "-Action", action],
                           cwd=ROOT, check=True, stdout=output, stderr=subprocess.STDOUT,
                           creationflags=NO_WINDOW, timeout=40)

    def launch(module, port, extra):
        output = (state / (module + ".log")).open("wb")
        logs.append(output)
        command = [java_executable, "-Duser.timezone=Asia/Shanghai", "-jar",
                   str(ROOT / module / "target" / (module + "-1.0.0.jar")),
                   "--server.address=127.0.0.1", "--server.port=" + str(port),
                   "--spring.datasource.url=jdbc:mysql://127.0.0.1:13306/" + SCHEMA + "?connectionTimeZone=Asia/Shanghai",
                   "--spring.datasource.username=root", "--spring.datasource.password=",
                   "--spring.rabbitmq.host=127.0.0.1", "--spring.rabbitmq.port=5673",
                   "--spring.rabbitmq.username=guest", "--spring.rabbitmq.password=guest",
                   "--spring.rabbitmq.virtual-host=culturehub_acceptance"] + extra
        process = subprocess.Popen(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT,
                                   creationflags=NO_WINDOW)
        processes.append(process)
        return process

    def periodic_trigger():
        while not stop.is_set():
            try:
                now = int(time.time() * 1000)
                code, reply = http("/run", {
                    "jobId": 91001, "executorHandler": "outboxPublisher", "executorParams": "",
                    "executorBlockStrategy": "SERIAL_EXECUTION", "executorTimeout": 0,
                    "logId": now, "logDateTime": now, "glueType": "BEAN", "glueSource": "",
                    "glueUpdatetime": now, "broadcastIndex": 0, "broadcastTotal": 1},
                    base="http://127.0.0.1:9999", headers={"XXL-JOB-ACCESS-TOKEN": TOKEN})
                if code != 200 or reply.get("code") != 200:
                    trigger_errors.append({"status": code, "reply": reply})
            except Exception as error:
                trigger_errors.append({"error": type(error).__name__})
            stop.wait(2)

    try:
        for port in [18091, 18092, 9999]:
            if port_open(port):
                raise RuntimeError("Test port is already in use: " + str(port))
        # Only this explicit test schema is recreated. Existing culturehub/rich_demo/perf are untouched.
        sql("DROP DATABASE IF EXISTS " + SCHEMA + "; CREATE DATABASE " + SCHEMA
            + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;", database=None)
        schema = (ROOT / "deploy/mysql/01-schema.sql").read_text(encoding="utf-8")
        sql("\n".join(schema.splitlines()[2:]))
        sql("INSERT INTO venue(id,name) VALUES(1,'Rabbit acceptance venue');"
            "INSERT INTO activity(id,venue_id,title,status) VALUES(1,1,'Real Rabbit acceptance','PUBLISHED');"
            "INSERT INTO activity_session(id,activity_id,registration_start_at,registration_end_at,"
            "start_at,end_at,checkin_open_at,checkin_close_at,capacity,status) VALUES"
            "(1,1,now()-interval 1 day,now()+interval 1 hour,now()+interval 2 hour,"
            "now()+interval 3 hour,now()-interval 1 hour,now()+interval 3 hour,5,'PUBLISHED');")
        evidence["mysqlVersion"] = sql("SELECT VERSION()")
        if port_open(5673):
            broker("Stop")
        wait_for(lambda: not port_open(5673), 30)
        checked("RabbitMQ completely stopped before API startup", not port_open(5673))
        api = launch("culturehub-api", 18091, [
            "--spring.config.import=optional:file:.tools/rabbit-acceptance-empty.properties",
            "--culture.jwt.secret=isolated-rabbit-acceptance-jwt-secret-at-least-32-chars",
            "--culture.demo.password=", "--culture.demo.rich=false", "--culture.jobs.enabled=true",
            "--xxl.job.admin.addresses=", "--xxl.job.accessToken=" + TOKEN,
            "--xxl.job.executor.address=http://127.0.0.1:9999/", "--spring.data.redis.port=16379",
            "--spring.ai.model.chat=none", "--spring.ai.model.embedding=none",
            "--spring.ai.vectorstore.type=none"])
        wait_for(lambda: http("/api/v1/activities")[0] == 200)
        checked("Real API started without Broker", api.poll() is None and not port_open(5673))
        code, user = http("/api/v1/auth/register", {"username": "rabbit_acceptance", "password": "123"})
        checked("HTTP registration", code == 200, status=code)
        code, login = http("/api/v1/auth/login", {"username": "rabbit_acceptance", "password": "123"})
        checked("HTTP login", code == 200, status=code)
        jwt = login["token"]
        code, reservation = http("/api/v1/sessions/1/reservations", {"request_id": str(uuid.uuid4())}, token=jwt)
        checked("Real HTTP booking commits while RabbitMQ is down", code == 200, status=code)
        checked("Booking and Outbox committed together",
                sql("SELECT count(*) FROM participation WHERE status='RESERVED'") == "1"
                and sql("SELECT occupied_count FROM activity_session WHERE id=1") == "1"
                and sql("SELECT count(*) FROM outbox WHERE status='PENDING'") == "1")
        event_id = sql("SELECT event_id FROM outbox")
        evidence["eventId"] = event_id
        code, health = http("/actuator/health")
        checked("Global health detects missing RabbitMQ", code == 503 and health["status"] == "DOWN")
        code, readiness = http("/actuator/health/readiness", token=jwt)
        checked("Core readiness remains UP", code == 200 and readiness["status"] == "UP")
        wait_for(lambda: port_open(9999), 20)
        trigger = threading.Thread(target=periodic_trigger, daemon=True)
        trigger.start()
        wait_for(lambda: int(sql("SELECT attempt_count FROM outbox")) >= 1, 30)
        wait_for(lambda: bool(sql("SELECT last_error FROM outbox")) and sql("SELECT last_error FROM outbox") != "NULL", 30)
        checked("Real failed Broker attempt retained with error and backoff",
                sql("SELECT status FROM outbox") == "PENDING"
                and sql("SELECT next_attempt_at>now(6) FROM outbox") == "1")
        broker("Start")
        wait_for(lambda: port_open(5673), 60)
        worker = launch("culturehub-notification-worker", 18092, [])
        wait_for(lambda: sql("SELECT count(*) FROM notification") == "1", 90)
        checked("Scheduled Publisher recovered and standalone Worker persisted notification",
                sql("SELECT status FROM outbox") == "PUBLISHED" and worker.poll() is None)
        checked("Recovery preserves event_id", sql("SELECT source_event_id FROM notification") == event_id)
        sql("UPDATE outbox SET status='PENDING',next_attempt_at=now(6)")
        wait_for(lambda: sql("SELECT status FROM outbox") == "PUBLISHED", 30)
        time.sleep(2)
        checked("Repeated published event consumed idempotently", sql("SELECT count(*) FROM notification") == "1")
        code, notices = http("/api/v1/me/notifications", token=jwt)
        checked("Notification visible through actual HTTP API", code == 200 and len(notices) == 1)
        # Verify actual broker-side queue types, not merely Java declaration arguments.
        auth = "Basic " + base64.b64encode(b"guest:guest").decode()
        path = "/api/queues/" + urllib.parse.quote("culturehub_acceptance", safe="")
        code, queues = http(path, base="http://127.0.0.1:15673", headers={"Authorization": auth})
        actual = {q["name"]: q["type"] for q in queues}
        checked("Actual durable production queues are Quorum", code == 200 and len(actual) == 3
                and all(v == "quorum" for v in actual.values()), queues=actual)
        checked("No scheduling protocol failures", not trigger_errors, errors=trigger_errors)
        evidence["passed"] = True
    except Exception as error:
        evidence["error"] = type(error).__name__ + ": " + str(error)
        raise
    finally:
        stop.set()
        if "trigger" in locals():
            trigger.join(20)
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(10)
        for output in logs:
            output.close()
        evidence["serviceProcessesStopped"] = all(p.poll() is not None for p in processes)
        evidence["servicePortsClosed"] = not any(port_open(p) for p in [18091, 18092, 9999])
        if not evidence["serviceProcessesStopped"] or not evidence["servicePortsClosed"]:
            evidence["passed"] = False
            evidence["cleanupError"] = "Acceptance service process or port still active"
        evidence["finishedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        output = ROOT / "docs/evidence/rabbitmq-standalone-acceptance.json"
        output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("Evidence: " + str(output), flush=True)
    assert evidence["passed"], "Acceptance or process cleanup incomplete"


if __name__ == "__main__":
    main()
