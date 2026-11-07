"""Export Surefire outcomes without environment or host properties."""
import argparse
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULES = ("culturehub-notification-worker", "culturehub-business", "culturehub-api")
COUNTS = ("tests", "failures", "errors", "skipped")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-log", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    log = args.build_log.read_text(encoding="utf-8", errors="replace")
    aggregate = re.findall(r"(?m)^\[(?:INFO|WARNING)\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$", log)
    if "BUILD SUCCESS" not in log or len(aggregate) != len(MODULES):
        raise ValueError("Run the full Maven reactor before exporting evidence")
    suites = ET.Element("testsuites")
    summary = {}
    for module, expected in zip(MODULES, aggregate):
        selected = ET.SubElement(suites, "module", {"name": module})
        totals = dict.fromkeys(COUNTS, 0)
        for path in sorted((ROOT / module / "target/surefire-reports").glob("TEST-*.xml")):
            suite = ET.parse(path).getroot()
            for key in COUNTS:
                totals[key] += int(suite.attrib.get(key, "0"))
            for detail in suite.iter():
                for child in list(detail):
                    if child.tag in {"properties", "system-out", "system-err"}:
                        detail.remove(child)
            selected.append(suite)
        if tuple(totals.values()) != tuple(map(int, expected)):
            raise ValueError(f"{module}: XML totals disagree with Maven output: {totals}")
        totals["passed"] = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
        summary[module] = totals
    args.out.mkdir(exist_ok=True, parents=True)
    ET.ElementTree(suites).write(args.out / "ai-v2-round2-surefire.xml", encoding="utf-8", xml_declaration=True)
    total = {key: sum(item[key] for item in summary.values()) for key in (*COUNTS, "passed")}
    chroma = [line for line in log.splitlines() if line.startswith("Chroma ")]
    first = json.loads((ROOT / "docs/evidence/ai-v2-test-summary.json").read_text(encoding="utf-8"))["current"]
    report = {"firstV2": first, "secondV2": total, "modules": summary, "realChromaEvidence": chroma}
    (args.out / "ai-v2-round2-test-summary.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(total))


if __name__ == "__main__":
    main()
