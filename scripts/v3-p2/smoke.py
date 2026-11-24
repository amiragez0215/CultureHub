"""Verify V3 async booking against dedicated MySQL, Redis and RabbitMQ fixtures."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import os
from pathlib import Path
import secrets
import subprocess
import time

spec = importlib.util.spec_from_file_location(
    "p1_smoke", Path(__file__).resolve().parents[1] / "v3-p1" / "smoke.py")
http = importlib.util.module_from_spec(spec)
spec.loader.exec_module(http)


def run(args):
    base = args.core
    api = "/api/v1"
    tag = secrets.token_hex(5)
    sql_args = [args.mysql, "--no-defaults", "--protocol=TCP", "--host=127.0.0.1",
                f"--port={args.mysql_port}", "--user=root", "--database=core_db",
                "--default-character-set=utf8mb4", "-NB"]

    def sql(statement):
        result = subprocess.run(sql_args, input=statement, encoding="utf-8",
                                capture_output=True, check=True, timeout=15)
        return result.stdout.strip()

    def account():
        body = {"username": "p2_" + secrets.token_hex(8), "password": secrets.token_hex(16)}
        registered, _ = http.expect(http.call(base, api + "/auth/register", "POST", body), 200, "Register")
        logged_in, _ = http.expect(http.call(base, api + "/auth/login", "POST", body), 200, "Login")
        return logged_in["token"], registered["id"]

    def session(capacity=1):
        return int(sql(f"""
          insert into venue(name) values('V3 P2 fixture {tag}');
          insert into activity(venue_id,title,status) values(last_insert_id(),'Async test','PUBLISHED');
          insert into activity_session(activity_id,registration_start_at,registration_end_at,start_at,
            end_at,checkin_open_at,checkin_close_at,capacity,status)
          values(last_insert_id(),date_sub(now(),interval 1 hour),date_add(now(),interval 1 hour),
            date_add(now(),interval 2 hour),date_add(now(),interval 3 hour),date_add(now(),interval 2 hour),
            date_add(now(),interval 3 hour),{capacity},'PUBLISHED');
          select last_insert_id();
        """))

    def submit(slot, token, request, wait=False):
        route = "waitlist" if wait else "reservations"
        status, body, _ = http.call(base, f"{api}/sessions/{slot}/{route}", "POST",
                                    {"request_id": request}, token)
        assert status in (200, 202), (route, status, body.get("code"))
        return status, body

    def get(request, token):
        body, _ = http.expect(http.call(base, f"{api}/booking-requests/{request}", token=token),
                              200, "Read owner booking status")
        return body

    def until(predicate, limit=14):
        deadline = time.monotonic() + limit
        while time.monotonic() < deadline:
            value = predicate()
            if value:
                return value
            time.sleep(0.2)
        raise AssertionError("Timed out waiting for the authoritative result")

    def final(request, token):
        def check():
            value = get(request, token)
            return value if value["requestStatus"] in ("CONFIRMED", "REJECTED", "EXPIRED") else None
        return until(check)

    def trigger(handler="outboxPublisher"):
        value = int(time.time() * 1000)
        body = {"jobId": 96001 if handler == "outboxPublisher" else 96002, "executorHandler": handler,
                "executorParams": "", "executorBlockStrategy": "SERIAL_EXECUTION",
                "executorTimeout": 20, "logId": value, "logDateTime": value,
                "glueType": "BEAN", "glueSource": "", "glueUpdatetime": 0, "broadcastIndex": 0, "broadcastTotal": 1}
        data, _ = http.expect(http.call(args.executor, "/run", "POST", body,
                                       headers={"XXL-JOB-ACCESS-TOKEN": os.environ["XXL_JOB_TOKEN"]}),
                              200, "Trigger existing XXL handler")
        assert data["code"] == 200

    people = [account() for _ in range(6)]
    slot = session(2)
    requests = [secrets.token_hex(16) for _ in people]
    with ThreadPoolExecutor(max_workers=len(people)) as pool:
        responses = list(pool.map(lambda pair: submit(slot, pair[0][0], pair[1]), zip(people, requests)))
    assert any(code == 202 for code, _ in responses)
    finals = [final(request, user[0]) for user, request in zip(people, requests)]
    winners = [(user, request) for user, request, result in zip(people, requests, finals)
               if result["requestStatus"] == "CONFIRMED"]
    assert len(winners) == 2
    assert sql(f"select occupied_count from activity_session where id={slot}") == "2"
    assert sql(f"select count(*) from participation where session_id={slot} and status='RESERVED'") == "2"
    assert sql(f"select count(*) from participation_history h join participation p on p.id=h.participation_id "
               f"where p.session_id={slot} and h.to_status='RESERVED'") == "2"
    token, _ = winners[0][0]
    request = winners[0][1]
    for _ in range(3):
        assert submit(slot, token, request)[0] == 200
    assert sql(f"select count(*) from participation where request_id='{request}'") == "1"
    intruder = next(user for user in people if user[0] != token)
    http.expect(http.call(base, f"{api}/booking-requests/{request}", token=intruder[0]), 404,
                "Other user cannot read a request")
    http.expect(http.call(base, f"{api}/sessions/{slot}/reservations", "POST",
                          {"request_id": request}, intruder[0]), 409, "Request ID cannot change owner")
    trigger()
    print("PASS: HTTP 202, capacity under concurrency, stable replay and request ownership", flush=True)

    if args.faults:
        # The fixture Core uses a long recovery interval. Register the earlier
        # JOIN in SQL to reproduce a crash before the Lua call deterministically.
        slot = session()
        owner, earlier, later, newcomer = people[:4]
        owner_request, early_request, late_request = [secrets.token_hex(16) for _ in range(3)]
        submit(slot, owner[0], owner_request)
        owner_part = final(owner_request, owner[0])["participation"]["id"]
        sql(f"""
          start transaction;
          update activity_session set last_waitlist_seq=1 where id={slot};
          insert into booking_request(request_id,user_id,session_id,operation,state,queue_seq,trace_id,expires_at)
          values('{early_request}',{earlier[1]},{slot},'JOIN','RECEIVED',1,'p2-fifo-test',date_add(now(),interval 50 second));
          update booking_session set revision=revision+1 where session_id={slot};
          commit;
        """)
        submit(slot, later[0], late_request, True)
        later_part = final(late_request, later[0])["participation"]["id"]
        assert sql(f"select status from participation where id={later_part}") == "WAITING"
        cancelled, _ = http.expect(http.call(base, f"{api}/participations/{owner_part}/cancel", "POST", {}, owner[0]),
                                   200, "Cancel confirms immediately")
        assert cancelled["status"] == "CANCELLED"
        trigger()
        time.sleep(1.3)
        assert sql(f"select status from participation where id={later_part}") == "WAITING"
        bypass = secrets.token_hex(16)
        submit(slot, newcomer[0], bypass)
        assert final(bypass, newcomer[0])["errorCode"] == "WAITLIST_PRIORITY"
        submit(slot, earlier[0], early_request, True)
        earlier_part = final(early_request, earlier[0])["participation"]["id"]
        trigger()
        until(lambda: sql(f"select status from participation where id={earlier_part}") == "OFFERED")
        assert sql(f"select status from participation where id={later_part}") == "WAITING"
        confirm = http.call(base, f"{api}/participations/{earlier_part}/confirm", "POST", {}, earlier[0])
        assert confirm[0] in (200, 202)
        assert final(f"v3-confirm-{earlier_part}", earlier[0])["participation"]["status"] == "RESERVED"
        print("PASS: late FIFO head, async cancellation/promotion, direct anti-bypass and async confirmation", flush=True)

        slot = session()
        delayed = secrets.token_hex(16)
        trigger_name = "p2_expire_" + tag
        sql(f"""
          create trigger {trigger_name} before update on booking_request for each row
          set NEW.expires_at=if(OLD.request_id='{delayed}' and NEW.state='QUEUED',
            date_sub(now(6),interval 1 second),NEW.expires_at);
        """)
        try:
            submit(slot, owner[0], delayed)
            assert final(delayed, owner[0])["requestStatus"] == "EXPIRED"
            assert sql(f"select occupied_count from activity_session where id={slot}") == "0"
            assert sql(f"select state from reservation_hold where request_id='{delayed}'") == "RELEASED"
        finally:
            sql(f"drop trigger {trigger_name};")
        print("PASS: Worker rejects expired Hold and leaves no formal qualification", flush=True)

    print("PASS: P2 real HTTP / MySQL / Redis / RabbitMQ smoke", flush=True)


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--core", default="http://127.0.0.1:18110")
    cli.add_argument("--executor", default="http://127.0.0.1:9999")
    cli.add_argument("--mysql", default="mysql")
    cli.add_argument("--mysql-port", type=int, default=13306)
    cli.add_argument("--faults", action="store_true", help="Dedicated fixture with recovery/reconciliation delay >=60s")
    args = cli.parse_args()
    if args.mysql_port == 3306:
        cli.error("Use a dedicated local test MySQL instance")
    run(args)
