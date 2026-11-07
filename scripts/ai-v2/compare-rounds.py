"""Compare two V2 commits using the same isolated catalog and the configured real chat model."""
import argparse
import datetime as dt
import hashlib
import json
import os
import socket
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DATABASE = "culturehub_ai_round2_eval"
API = "/api/v1"
CASES = (
    ("simple-search", "请在市文化博物馆推荐一个文化活动，时间不限。", [1, 2]),
    ("two-activities", "请在市文化博物馆安排明天的两个活动，必须两个，同一场馆，时间不冲突。", [1, 2]),
    ("session-details", "请查询场次编号 1 的详情，只查询。", [1]),
    ("missing-venue", "不存在的文化馆几点开门？", []),
    ("verified-age", "请在市文化博物馆推荐非遗剪纸体验，必须确认8岁儿童能不能参加。", [1]),
)


def sql(mysql, script, database=DATABASE):
    command = [mysql, "--protocol=TCP", "-h", "127.0.0.1", "-P", "13306", "-u", "root",
               "--default-character-set=utf8mb4", "--batch", "--skip-column-names"]
    if database:
        command.append(database)
    return subprocess.run(command, input=script, encoding="utf-8", check=True,
                          stdout=subprocess.PIPE).stdout.strip()


def seed(mysql):
    sql(mysql, "CREATE DATABASE IF NOT EXISTS " + DATABASE
        + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci", None)
    if sql(mysql, "select count(*) from information_schema.tables where table_schema=database()") == "0":
        schema = (ROOT / "deploy/mysql/01-schema.sql").read_text(encoding="utf-8").splitlines()
        sql(mysql, "\n".join(schema[2:]))
    manifest = json.loads((ROOT / "culturehub-business/src/main/resources/knowledge/manifest.json")
                          .read_text(encoding="utf-8"))
    names = ["市文化博物馆", "社区文化艺术中心"]
    names += list(dict.fromkeys(entry["venueName"] for entry in manifest
                                if entry.get("venueName") and entry["venueName"] not in names))
    for venue_id, name in enumerate(names, 1):
        escaped = name.replace("'", "''")
        sql(mysql, f"""INSERT INTO venue(id,name,opening_hours)
          VALUES({venue_id},'{escaped}','每日 09:00—17:00')
          ON DUPLICATE KEY UPDATE name=VALUES(name),opening_hours=VALUES(opening_hours)""")
    sql(mysql, """
    INSERT INTO activity(id,venue_id,title,category,description,status) VALUES
      (1,1,'非遗剪纸体验','HERITAGE','体验传统剪纸纹样与纸艺','PUBLISHED'),
      (2,1,'博物馆里的城市记忆','LECTURE','城市历史与博物馆馆藏讲解','PUBLISHED')
      ON DUPLICATE KEY UPDATE title=VALUES(title),status=VALUES(status);
    INSERT INTO activity_session(id,activity_id,registration_start_at,registration_end_at,start_at,
        end_at,checkin_open_at,checkin_close_at,capacity,status) VALUES
      (1,1,now()-interval 1 day,current_date+interval 32 hour,current_date+interval 34 hour,
        current_date+interval 36 hour,current_date+interval 33 hour,current_date+interval 36 hour,10,'PUBLISHED'),
      (2,2,now()-interval 1 day,current_date+interval 32 hour,current_date+interval 38 hour,
        current_date+interval 40 hour,current_date+interval 37 hour,current_date+interval 40 hour,10,'PUBLISHED')
      ON DUPLICATE KEY UPDATE registration_start_at=VALUES(registration_start_at),
        registration_end_at=VALUES(registration_end_at),start_at=VALUES(start_at),end_at=VALUES(end_at),
        checkin_open_at=VALUES(checkin_open_at),checkin_close_at=VALUES(checkin_close_at);
    """)


def request(port, path, body=None, token=None, stream=False):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    url = f"http://127.0.0.1:{port}{API}{path}"
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers, data=payload),
                                timeout=100) as result:
        if not stream:
            return json.load(result)
        events = []
        for record in result.read().decode("utf-8").replace("\r\n", "\n").split("\n\n"):
            kind = next((line[6:].strip() for line in record.splitlines() if line.startswith("event:")), "")
            data = "\n".join(line[5:].strip() for line in record.splitlines() if line.startswith("data:"))
            if kind and data:
                events.append((kind, json.loads(data)))
        return events


def run(args, name, jar, port, username, suffix):
    with socket.socket() as probe:
        if probe.connect_ex(("127.0.0.1", port)) == 0:
            raise RuntimeError(f"Test port {port} already in use")
    collection = "guide-round2-eval-" + name + "-" + suffix
    work = ROOT / ".tools"
    work.mkdir(exist_ok=True)
    command = [args.java, "-jar", str(jar.resolve()),
               "--server.address=127.0.0.1", f"--server.port={port}",
               f"--spring.datasource.url=jdbc:mysql://127.0.0.1:13306/{DATABASE}?connectionTimeZone=Asia/Shanghai",
               "--spring.datasource.username=root", "--spring.datasource.password=",
               "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=16379",
               "--culture.jwt.secret=isolated-read-only-v2-comparison-signing-key",
               "--culture.demo.password=", "--culture.demo.rich=false", "--culture.jobs.enabled=false",
               "--culture.ai.guide-v2.enabled=true", "--spring.ai.vectorstore.type=chroma",
               "--spring.ai.vectorstore.chroma.enabled=true", "--spring.ai.model.embedding=openai",
               "--spring.ai.vectorstore.chroma.client.host=http://127.0.0.1",
               f"--spring.ai.vectorstore.chroma.client.port={args.chroma_port}",
               f"--spring.ai.vectorstore.chroma.collection-name={collection}",
               f"--spring.ai.openai.embedding.base-url=http://127.0.0.1:{args.embedding_port}",
               "--spring.ai.openai.embedding.api-key=loopback-test",
               "--spring.ai.openai.embedding.options.model=all-MiniLM-L6-v2"]
    results = []
    with (work / f"guide-round2-live-{name}.log").open("wb") as output:
        process = subprocess.Popen(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        try:
            for _ in range(150):
                try:
                    request(port, "/activities")
                    break
                except (OSError, urllib.error.URLError):
                    if process.poll() is not None:
                        raise RuntimeError(f"{name} API exited during startup")
                    time.sleep(.2)
            else:
                raise RuntimeError(f"{name} API did not start")
            username = username + "_" + name
            request(port, "/auth/register", {"username": username, "password": "123"})
            sql(args.mysql, "INSERT IGNORE INTO user_role(user_id,role) SELECT id,'PLATFORM_ADMIN'"
                f" FROM app_user WHERE username='{username}'")
            token = request(port, "/auth/login", {"username": username, "password": "123"})["token"]
            reindex = request(port, "/admin/ai/knowledge/reindex", {}, token)
            for case, text, expected_ids in CASES:
                started = time.monotonic()
                result = {"case": case, "question": text, "expectedCatalogSessionIds": expected_ids}
                try:
                    conversation = request(port, "/ai/conversations", {}, token)["id"]
                    url = f"/ai/conversations/{conversation}/messages"
                    if case == "session-details":
                        events = request(port, url + "/stream", {"message": text}, token, stream=True)
                        result["sseOrder"] = [kind for kind, _ in events]
                        result["sseNodes"] = [body.get("node") for kind, body in events if kind == "status"]
                        reply = next((body for kind, body in events if kind == "done"), {})
                        result["streamErrors"] = [body.get("code") for kind, body in events if kind == "error"]
                    else:
                        reply = request(port, url, {"message": text}, token)
                    result.update(phase=reply.get("phase"), answer=reply.get("explanation"),
                                  ids=[c["sessionId"] for c in reply.get("candidates", [])],
                                  detailIds=[c["sessionId"] for c in reply.get("sessionDetails", [])],
                                  evidence=[{"status": e.get("status"), "ids": e.get("evidenceIds")}
                                            for e in reply.get("evidence", [])],
                                  reasons=(reply.get("plan") or {}).get("recommendationReasons", []),
                                  nodes=reply.get("visitedNodes"), usage=reply.get("usage"))
                except urllib.error.HTTPError as error:
                    result.update(httpStatus=error.code, errorCode=json.load(error).get("code"))
                except (OSError, ValueError) as error:
                    result["error"] = type(error).__name__
                result["seconds"] = round(time.monotonic() - started, 3)
                results.append(result)
        finally:
            process.terminate()
            try:
                process.wait(12)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(10)
    return {"round": name, "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "indexedChunks": reindex.get("chunks"), "cases": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--first", required=True, type=Path)
    parser.add_argument("--second", required=True, type=Path)
    parser.add_argument("--java", default=os.environ.get("JAVA", "java"))
    parser.add_argument("--mysql", default=os.environ.get("MYSQL", "mysql"))
    parser.add_argument("--chroma-port", default=18090, type=int)
    parser.add_argument("--embedding-port", default=18092, type=int)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    seed(args.mysql)
    before = sql(args.mysql, "select count(*) from participation")
    suffix = str(int(time.time()))
    username = "r2_eval_" + suffix
    runs = [run(args, name, jar, port, username, suffix)
            for name, jar, port in (("first", args.first, 18096), ("second", args.second, 18097))]
    after = sql(args.mysql, "select count(*) from participation")
    assert before == after, "Read-only comparison changed participation"
    result = {"recordedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "database": DATABASE,
              "realChatModel": True, "realChromaAndEmbedding": True,
              "participationBefore": int(before), "participationAfter": int(after), "runs": runs}
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Recorded {sum(len(r['cases']) for r in runs)} cases in {args.output}")


if __name__ == "__main__":
    main()
