"""Exercise an isolated P1 deployment. Creates two users and one real booking."""

import argparse
import json
import os
import secrets
import time
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

HTTP = build_opener(ProxyHandler({}))


def call(base, path, method="GET", body=None, token=None, headers=None):
    request_headers = dict(headers or {})
    if body is not None:
        request_headers["Content-Type"] = "application/json"
    if token:
        request_headers["Authorization"] = f"Bearer {token}"
    data = None if body is None else json.dumps(body).encode()
    req = Request(base + path, data, request_headers, method=method)
    try:
        response = HTTP.open(req, timeout=8)
    except HTTPError as error:
        response = error
    with response:
        raw = response.read()
        try:
            result = json.loads(raw) if raw else None
        except ValueError:
            result = None
        return response.status, result, response.headers


def expect(result, expected, label):
    status, body, headers = result
    if status != expected:
        code = body.get("code", "") if isinstance(body, dict) else ""
        raise AssertionError(f"{label}: HTTP {status} {code}; expected {expected}")
    return body, headers


def wait_for_catalog(gateway, trace):
    deadline = time.monotonic() + 20
    while True:
        result = call(gateway, "/api/v1/venues", headers={"X-Trace-Id": trace})
        if result[0] != 503 or time.monotonic() >= deadline:
            return result
        time.sleep(0.5)


def run(args):
    base = args.gateway
    api = "/api/v1"
    test_trace = "v3-p1-smoke-trace"
    _, headers = expect(wait_for_catalog(base, test_trace),
                        200, "Public catalog via Gateway")
    assert headers.get("X-Trace-Id") == test_trace
    expect(call(base, f"{api}/me/notifications"), 401, "Notification JWT required")
    expect(call(base, f"{api}/me/notifications", token="invalid.token.example"), 401,
           "Invalid JWT rejected")

    def new_user():
        account = {"username": "p1_" + secrets.token_hex(7), "password": secrets.token_hex(16)}
        _, _ = expect(call(base, f"{api}/auth/register", "POST", account), 200, "Register via Gateway")
        user, _ = expect(call(base, f"{api}/auth/login", "POST", account), 200, "Login via Gateway")
        return user["token"]

    owner, other = new_user(), new_user()
    expect(call(base, f"{api}/auth/me", token=owner), 200, "Existing authenticated Core endpoint")
    expect(call(base, "/internal/v1/core/readiness", token=owner,
                headers={"X-CultureHub-Service": "culturehub-ai", "X-CultureHub-Service-Token": "forged"}),
           403, "Internal API not routed through Gateway")
    if args.core:
        expect(call(args.core, f"{api}/me/notifications", token=owner), 403,
               "Core does not expose notification reads in the P1 profile")
        expect(call(args.core, "/internal/v1/core/readiness"), 401, "Internal credential required")
    for service, url in args.probe:
        token = os.environ["V3_PROBE_TOKEN"]
        headers = {"X-CultureHub-Service-Token": token, "X-Trace-Id": test_trace}
        expect(call(url, "/internal/v1/service/readiness"), 401, f"{service} probe protected")
        info, _ = expect(call(url, "/internal/v1/service/readiness", headers=headers),
                         200, f"{service} database identity")
        assert info["service"] == f"culturehub-{service}"
        assert info["schema"] == {"ai": "ai_db", "knowledge": "knowledge_db", "orchestrator": "saga_db"}[service]
        info, _ = expect(call(url, "/internal/v1/service/core-readiness", headers=headers),
                         200, f"{service} authenticated Feign call to Core")
        assert info == {"service": "culturehub-core", "schema": "core_db", "traceId": test_trace}

    booking, _ = expect(call(base, f"{api}/sessions/{args.session}/reservations", "POST",
                             {"request_id": secrets.token_hex(16)}, owner), 200, "Existing booking API")
    participation = booking["id"]
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        if args.executor:
            payload = {"jobId": 95001, "executorHandler": "outboxPublisher", "executorParams": "",
                       "executorBlockStrategy": "SERIAL_EXECUTION", "executorTimeout": 20,
                       "logId": int(time.time() * 1000), "logDateTime": int(time.time() * 1000),
                       "glueType": "BEAN", "glueSource": "", "glueUpdatetime": 0,
                       "broadcastIndex": 0, "broadcastTotal": 1}
            handler, _ = expect(call(args.executor, "/run", "POST", payload,
                                     headers={"XXL-JOB-ACCESS-TOKEN": os.environ["XXL_JOB_TOKEN"]}),
                                200, "Actual Outbox Publisher handler")
            assert handler["code"] == 200
        inbox, _ = expect(call(base, f"{api}/me/notifications", token=owner), 200,
                          "Notification API through Gateway")
        matching = [row for row in inbox if row["participation_id"] == participation]
        if matching:
            break
        time.sleep(1)
    else:
        raise AssertionError("Committed reservation did not produce a real notification")
    assert len(matching) == 1 and matching[0]["read_at"] is None
    notice = matching[0]["id"]
    hidden, _ = expect(call(base, f"{api}/me/notifications", token=other), 200, "Other user's inbox")
    assert hidden == []
    path = f"{api}/me/notifications/{notice}/read"
    expect(call(base, path, "PATCH", token=other), 200, "Other user's read attempt")
    still_unread, _ = expect(call(base, f"{api}/me/notifications", token=owner), 200, "Owner's inbox")
    assert still_unread[0]["read_at"] is None
    expect(call(base, path, "PATCH", token=owner), 200, "Owner marks notification read")
    after, _ = expect(call(base, f"{api}/me/notifications", token=owner), 200, "Read persisted")
    assert after[0]["read_at"] is not None
    print("PASS: public routes, JWT, internal route isolation, trace, booking, RabbitMQ notification and ownership")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", default="http://127.0.0.1:8090")
    parser.add_argument("--session", type=int, required=True, help="An open session in the test database")
    parser.add_argument("--executor", help="Test-only XXL executor URL if Admin is not running")
    parser.add_argument("--core", help="Optional direct Core URL in isolated environments")
    parser.add_argument("--probe", action="append", nargs=2, default=[], metavar=("SERVICE", "URL"))
    run(parser.parse_args())
