#!/usr/bin/env bash
#
# Summarizes target/surefire-reports/*.xml into a Slack-ready plain-text
# report: total/passed/failed/errored counts, a per-scenario step-by-step
# trail (every step's PASSED/FAILED outcome, logged by
# automation.report.StepExecutor/ScenarioReportExtension and captured in each
# testcase's <system-out>), and for each failing testcase its class, method,
# and failure message.
#
# Usage: scripts/parse-surefire-results.sh [reports-dir] [output-file]
#   reports-dir defaults to target/surefire-reports
#   output-file defaults to stdout only if omitted

set -euo pipefail

REPORTS_DIR="${1:-target/surefire-reports}"
OUTPUT_FILE="${2:-}"

SUMMARY=$(python3 - "$REPORTS_DIR" <<'PYEOF'
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

reports_dir = sys.argv[1]
xml_files = sorted(glob.glob(os.path.join(reports_dir, "TEST-*.xml")))

if not xml_files:
    print(f"No surefire reports found in {reports_dir}")
    sys.exit(0)

# automation.report.StepExecutor/ScenarioReportExtension log lines with these
# markers (after Logback's default "<timestamp> [<thread>] <level> <logger>
# - " prefix), so a scenario's step trail is pulled out of its captured
# stdout by matching from the marker onward rather than the whole line.
STEP_LINE = re.compile(r"(STEP (?:PASSED|FAILED)|SCENARIO (?:PASSED|FAILED)) - .*")

# automation.clients.InvitationApiClient logs the Postman CLI's full captured
# output between these two marker lines. Only pulled into the summary for a
# failing testcase - see below - to keep green runs quiet.
POSTMAN_RESPONSE = re.compile(
    r"POSTMAN RESPONSE START.*?\n(.*?)\nPOSTMAN RESPONSE END", re.DOTALL)

total = passed = failed = errored = skipped = 0
failures = []
scenarios = []

for path in xml_files:
    root = ET.parse(path).getroot()
    for testcase in root.findall("testcase"):
        total += 1
        classname = testcase.get("classname", "?")
        name = testcase.get("name", "?")
        short_class = classname.rsplit(".", 1)[-1]

        failure = testcase.find("failure")
        error = testcase.find("error")
        skip = testcase.find("skipped")
        system_out = testcase.findtext("system-out") or ""

        step_lines = [m.group(0) for line in system_out.splitlines()
                      for m in [STEP_LINE.search(line)] if m]

        # StepExecutor/ScenarioReportExtension log the failing step into
        # <system-out>; the surefire <failure> message is often just a
        # @RetryingTest wrapper headline that doesn't name it.
        scenario_fail = next((l for l in step_lines if l.startswith("SCENARIO FAILED")), None)
        step_fail = next((l for l in reversed(step_lines) if l.startswith("STEP FAILED")), None)
        step_reason = scenario_fail or step_fail

        if failure is not None or error is not None:
            node = failure if failure is not None else error
            if failure is not None:
                failed += 1
                status = "FAILED"
            else:
                errored += 1
                status = "ERRORED"
            surefire_msg = node.get("message") or node.get("type") or "(no message)"
            # The Postman/invite log only matters when the scenario died before
            # any step ran; once a step has failed it's just noise.
            postman_match = None if step_reason else POSTMAN_RESPONSE.search(system_out)
            failures.append((short_class, name, step_reason or surefire_msg,
                              postman_match.group(1) if postman_match else None))
        elif skip is not None:
            skipped += 1
            status = "SKIPPED"
        else:
            passed += 1
            status = "PASSED"

        scenarios.append((short_class, name, status, step_lines))

print(f"Ran {total} tests: {passed} passed, {failed} failed, {errored} errored, {skipped} skipped")
print()

print("Scenarios:")
for short_class, name, status, step_lines in scenarios:
    print(f"- {short_class}.{name}: {status}")
    for line in step_lines:
        print(f"    {line}")
print()

if failures:
    print("Failures:")
    for short_class, name, message, postman_response in failures:
        first_line = message.strip().splitlines()[0] if message.strip() else "(no message)"
        print(f"- {short_class}.{name}: {first_line}")
        if postman_response:
            print("    Postman response:")
            for line in postman_response.strip().splitlines():
                print(f"      {line}")
PYEOF
)

echo "$SUMMARY"

if [[ -n "$OUTPUT_FILE" ]]; then
    printf '%s\n' "$SUMMARY" > "$OUTPUT_FILE"
fi
