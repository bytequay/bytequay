#!/usr/bin/env python3
"""Export bounded, credential-free facts from the disposable live fixture."""

import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def turn_span(events):
    lifecycle = [event for event in events if event["type"] == "lifecycle"
                 and event["payload"].get("phase") in ("TURN_STARTED", "TURN_COMPLETED")]
    start = datetime.fromisoformat(lifecycle[0]["occurredAt"].replace("Z", "+00:00"))
    end = datetime.fromisoformat(lifecycle[-1]["occurredAt"].replace("Z", "+00:00"))
    return round((end - start).total_seconds(), 1)


def arm(root, expected_enabled, case):
    review = read(root / "final-review.json")
    events = read(root / "events.json")
    usage = read(root / "usage.json")
    run = review["runs"][-1]
    context = run["context"]
    assert run["state"] == "ACCEPTED"
    assert run["baseSha"] == case["revisions"]["base"]
    assert run["headSha"] == case["revisions"]["head"]
    assert context["enabled"] is expected_enabled
    provider_usage = [event["payload"] for event in events if event["type"] == "usage"
                      and event["payload"]["semantics"] == "FINAL_FOR_INVOCATION"]
    return {
        "reviewId": review["id"],
        "runId": run["id"],
        "runState": run["state"],
        "coverageComplete": run["coverage"]["complete"],
        "frozenBaseSha": run["baseSha"],
        "frozenHeadSha": run["headSha"],
        "taskModel": review["task"]["model"],
        "findings": [{
            "title": finding["title"], "body": finding["body"],
            "severity": finding["severity"], "path": finding["path"],
            "line": finding["startLine"], "headSha": finding["validatedHeadSha"],
            "evidence": [{"source": item["source"], "explanation": item["explanation"]}
                         for item in finding["evidence"]],
        } for finding in review["findings"]],
        "context": {
            "enabled": context["enabled"],
            "selectedNotes": [{"entryId": entry["entryId"], "version": entry["entryVersion"],
                               "inclusion": entry["inclusion"], "applicability": entry["applicability"],
                               "matchReasons": entry["matchReasons"]}
                              for entry in context["selectedEntries"]],
            "bootstrapUtf8Bytes": context["bootstrap"]["utf8Bytes"] if context["bootstrap"] else 0,
            "deliveries": [{"utf8Bytes": item["utf8Bytes"],
                            "outcome": run["contextDeliveryStates"][item["turnId"]]["outcome"]}
                           for item in context["deliveries"]],
            "dynamicNoteReads": len(run["contextNoteReads"]),
            "sourceReads": len(context["sourceReads"]),
        },
        "usageDashboard": {
            "trackingSince": usage["trackingSince"],
            "coverage": usage["coverage"], "summary": usage["summary"],
            "entries": [{"entryId": entry["entryId"], "currentVersionCounts": entry["currentVersionCounts"]}
                        for entry in usage["entries"]],
        },
        "provider": {
            "completedTurns": len(provider_usage),
            "turnSpanSeconds": turn_span(events),
            "inputTokens": sum(item["inputTokens"] for item in provider_usage),
            "cachedInputTokens": sum(item["cachedInputTokens"] for item in provider_usage),
            "outputTokens": sum(item["outputTokens"] for item in provider_usage),
            "reportedCost": round(sum(item["cost"] for item in provider_usage), 6),
            "rateLimitWarnings": sum(event["type"] == "lifecycle" and
                                     event["payload"].get("phase") == "RATE_LIMITED" for event in events),
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture_root", type=Path)
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()
    fixture = args.fixture_root
    output = args.output_dir
    case = read(output / "case.json")
    build = read(fixture / "learn-latest.json")["build"]
    notebook = read(fixture / "notebook-learned.json")
    attempt = build["results"][0]
    note = next(entry for entry in notebook["entries"] if entry["id"] == "knowledge:project")
    assert build["task"]["state"] == "COMPLETED" and attempt["outcome"] == "READY"
    assert note["version"] == 1 and note["kind"] == "KNOWLEDGE"
    assert any(claim["basis"] == "RECORDED_DECISION" for claim in note["claims"])
    assert any(item["kind"] == "HISTORY" and item["reference"].get("number") == 7
               for item in attempt["checkedReads"])
    write(output / "learn-build.json", {
        "taskState": build["task"]["state"], "baselineSha": build["baselineSha"],
        "selectedHistory": build["history"], "outcome": attempt["outcome"],
        "noteIds": attempt["noteIds"], "explanation": attempt["explanation"],
        "checkedReads": [{"kind": item["kind"], "reference": item["reference"],
                          "truncated": item["truncated"]} for item in attempt["checkedReads"]],
        "gaps": build["gaps"],
    })
    write(output / "learned-notebook.json", {
        "schemaVersion": notebook["schemaVersion"], "revision": notebook["revision"],
        "scope": {"id": notebook["scopes"][0]["id"],
                  "preparationOutcome": notebook["scopes"][0]["preparation"]["outcome"]},
        "note": note,
        "sourceEvidence": [{"id": item["id"], "reference": item["reference"],
                            "excerpt": item["excerpt"], "truncated": item["truncated"]}
                           for item in notebook["sourceEvidence"]],
    })
    write(output / "review-results.json", {
        "schemaVersion": 1,
        "qualityStatus": "UNSCORED_HUMAN_ORACLE_PENDING",
        "fixture": {"repository": case["repository"], "historicalPr": 7, "reviewPr": 8,
                    "baseSha": case["revisions"]["base"], "headSha": case["revisions"]["head"]},
        "provider": {"name": "Claude", "model": "Sonnet", "effort": "High", "cliVersion": "2.1.281"},
        "backendJarSha256": hashlib.sha256((fixture / "backend.jar").read_bytes()).hexdigest(),
        "arms": {"off": arm(fixture / "arms/off", False, case),
                 "warmOn": arm(fixture / "arms/on", True, case)},
    })


if __name__ == "__main__":
    main()
