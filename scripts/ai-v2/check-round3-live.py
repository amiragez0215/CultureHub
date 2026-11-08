"""Exercise Round 3 HTTP/SSE rule paths with a configured chat model and real Chroma."""

import argparse
import datetime as dt
import importlib.util
import json
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--mysql", default="mysql")
    parser.add_argument("--port", default=18105, type=int)
    parser.add_argument("--chroma-port", default=18090, type=int)
    parser.add_argument("--embedding-port", default=18092, type=int)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location(
        "compare_rounds", Path(__file__).with_name("compare-rounds.py")
    )
    harness = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(harness)
    database = "culturehub_ai_round3_eval"
    harness.DATABASE = database
    sql = harness.sql

    def isolated_sql(mysql, script, selected_database=database):
        return sql(mysql, script, selected_database)

    harness.sql = isolated_sql
    harness.CASES = (
        ("venue-guardian", "只想咨询场馆规则。市文化博物馆，8 岁孩子能独自入场吗？", []),
        ("session-details", "只核实场次编号 1 的资格，8 岁能不能参加非遗剪纸体验？", [1]),
        ("venue-age", "只咨询市文化博物馆的年龄规则：8 岁孩子符合参加年龄吗？", []),
        ("hard-independent", "请在市文化博物馆找一个活动，活动类别、主题和受众标签不限，但必须允许孩子独自入场。", []),
    )
    request = harness.request
    answers = []

    def capture(*params, **kwargs):
        reply = request(*params, **kwargs)
        path = params[1]
        if path.endswith("/messages/stream"):
            payload = next((value for event, value in reply if event == "done"), {})
        elif path.endswith("/messages"):
            payload = reply
        else:
            return reply
        answers.append({
            "phase": payload.get("phase"),
            "candidateCount": len(payload.get("candidates", [])),
            "preferences": payload.get("preferences"),
            "evidence": payload.get("evidence", []),
        })
        return reply

    harness.request = capture
    harness.seed(args.mysql)
    before = isolated_sql(args.mysql, "select count(*) from participation")
    suffix = str(int(time.time()))
    result = harness.run(args, "round3", args.jar, args.port, "r3_eval_" + suffix, suffix)
    after = isolated_sql(args.mysql, "select count(*) from participation")
    evidence = {
        "run": result,
        "recordedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "provider": "Configured chat model and real Chroma + Embedding",
        "participationBefore": int(before),
        "participationAfter": int(after),
        "answers": answers,
    }
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    assert before == after, "The rules check changed participation"
    print(f"Recorded {len(result['cases'])} live cases with unchanged participation")


if __name__ == "__main__":
    main()
