"""Collect actual Surefire reports. Compatible with Python 3.8+. No test execution."""
from pathlib import Path
import json
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
names = {
    "LocalBookingTest", "AiValidationTest", "AiBoundaryTest", "InputValidationTest",
    "ContainerBookingTest", "PublisherIntegrationTest", "WorkerRabbitTest",
    "RabbitConfirmTest", "ManualAckTest", "OutboxRabbitLocalTest",
    "RabbitEndToEndContainerTest", "RabbitEndToEndLocalTest",
    "AiGuideGraphTest", "ProposalLocalTest", "ProposalContainerTest",
}
results = []
for report in root.glob("culturehub-*/target/surefire-reports/TEST-*.xml"):
    suite = ET.parse(str(report)).getroot()
    if suite.attrib.get("name", "").split(".")[-1] in names:
        results.append({key: suite.attrib[key] for key in
                        ("name", "tests", "errors", "failures", "skipped", "time")})
destination = root / "docs" / "evidence" / "tests.json"
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(results, indent=2), encoding="utf-8")
passed = sum(int(x["tests"]) - int(x["skipped"]) - int(x["errors"]) - int(x["failures"])
             for x in results)
skipped = sum(int(x["skipped"]) for x in results)
print(json.dumps({"passed": passed, "skipped": skipped, "report": str(destination)}))
