"""Export current Surefire counts and case results without JVM properties or logs."""

import json
import argparse
from pathlib import Path
from xml.etree import ElementTree

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--output", default="docs/evidence/v3-p1-p2-tests.json")
output = root / parser.parse_args().output
modules = {}
cases = []
for module in sorted(root.glob("culturehub-*")):
    counts = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    for report in sorted(module.glob("target/surefire-reports/TEST-*.xml")):
        suite = ElementTree.parse(report).getroot()
        for field in counts:
            counts[field] += int(suite.get(field, 0))
        for case in suite.findall("testcase"):
            status = next((field for field in ("skipped", "failure", "error") if case.find(field) is not None), "passed")
            cases.append({"module": module.name, "class": case.get("classname"),
                          "test": case.get("name"), "status": status, "seconds": case.get("time")})
    if counts["tests"]:
        counts["passed"] = counts["tests"] - counts["failures"] - counts["errors"] - counts["skipped"]
        modules[module.name] = counts
totals = {field: sum(module[field] for module in modules.values()) for field in
          ("tests", "passed", "failures", "errors", "skipped")}
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({"modules": modules, "total": totals, "cases": cases},
                             ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(totals))
