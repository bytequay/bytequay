#!/usr/bin/env python3
"""Retain only fixture review results, context receipts and provider usage."""

import argparse
from datetime import datetime
import json
from pathlib import Path


def elapsed_seconds(events):
    turns = [event for event in events if event["type"] == "lifecycle"
             and event["payload"]["phase"] in ("TURN_STARTED", "TURN_COMPLETED")]
    start = datetime.fromisoformat(turns[0]["occurredAt"].replace("Z", "+00:00"))
    end = datetime.fromisoformat(turns[-1]["occurredAt"].replace("Z", "+00:00"))
    return round((end - start).total_seconds(), 1)


def arm(root, enabled):
    review = json.loads((root / "final-review.json").read_text())
    events = json.loads((root / "events.json").read_text())
    usage = json.loads((root / "usage.json").read_text())
    run = review["runs"][-1]
    context = run["context"]
    provider_usage = [event["payload"] for event in events if event["type"] == "usage"
                      and event["payload"]["semantics"] == "FINAL_FOR_INVOCATION"]
    selected = [{"entryId": entry["entryId"], "version": entry["entryVersion"],
                 "inclusion": entry["inclusion"], "applicability": entry["applicability"],
                 "matchReasons": entry["matchReasons"]}
                for entry in context["selectedEntries"]]
    findings = [{"title": finding["title"], "severity": finding["severity"],
                 "path": finding["path"], "line": finding["startLine"],
                 "body": finding["body"],
                 "evidenceExplanations": [item["explanation"] for item in finding["evidence"]]}
                for finding in review["findings"]]
    return {
        "reviewId": review["id"], "runId": run["id"], "runState": run["state"],
        "contextEnabled": enabled, "coverage": run["coverage"],
        "summary": run["summary"], "findings": findings,
        "context": {
            "selectedEntries": selected,
            "bootstrapUtf8Bytes": context["bootstrap"]["utf8Bytes"] if context["bootstrap"] else 0,
            "deliveries": [{"turnId": item["turnId"], "utf8Bytes": item["utf8Bytes"],
                            "packetDigest": item["packetDigest"],
                            "outcome": run["contextDeliveryStates"][item["turnId"]]["outcome"]}
                           for item in context["deliveries"]],
            "dynamicNoteReads": run["contextNoteReads"],
        },
        "usageDashboard": {
            "summary": usage["summary"],
            "entries": [{"entryId": item["entryId"], "currentVersionCounts": item["currentVersionCounts"]}
                        for item in usage["entries"]],
        },
        "provider": {
            "completedTurns": len(provider_usage),
            "turnSpanSeconds": elapsed_seconds(events),
            "inputTokens": sum(item["inputTokens"] for item in provider_usage),
            "cachedInputTokens": sum(item["cachedInputTokens"] for item in provider_usage),
            "outputTokens": sum(item["outputTokens"] for item in provider_usage),
            "reportedCost": round(sum(item["cost"] for item in provider_usage), 6),
            "rateLimitWarnings": sum(event["type"] == "lifecycle" and
                                     event["payload"]["phase"] == "RATE_LIMITED" for event in events),
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("arms_root", type=Path)
    parser.add_argument("output", type=Path)
    arguments = parser.parse_args()
    result = {
        "fixture": {
            "repository": "contextfixture/review-case", "pullRequest": 1,
            "baseSha": "99dac517a7758debe9005eec0b910109c7ce8d32",
            "headSha": "a8cd427b4b6b04af139b329269fdc3aa231d0c2e",
            "expectedBug": "window.py accepts the excluded end boundary",
        },
        "provider": {"name": "Claude", "model": "Sonnet", "effort": "high", "cliVersion": "2.1.281"},
        "backendJarSha256": "1204933b67c0aa171ba4f10d224a6c2bd42e1b158f898e785feefd441a58ad2d",
        "arms": {"off": arm(arguments.arms_root / "off", False),
                 "warmOn": arm(arguments.arms_root / "on", True)},
    }
    arguments.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
