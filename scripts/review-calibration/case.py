#!/usr/bin/env python3
"""Freeze one public pull request and its reviewer's inline comments as a scoring case."""

from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import shlex
import subprocess
import sys

REPOSITORY = Path(__file__).resolve().parents[2]
EDITS = """query($owner: String!, $name: String!, $number: Int!) { repository(owner: $owner, name: $name) { pullRequest(number: $number) {
  body userContentEdits(first: 100) { totalCount nodes { editedAt deletedAt diff } } } } }"""


def gh(*args):
    return json.loads(subprocess.check_output(["gh", "api", *args]))


def threads(pull, comments):
    """Inline comments that open a thread and were written by a person other than the author."""
    return [comment for comment in comments if comment.get("in_reply_to_id") is None
            and comment["user"]["type"] != "Bot" and comment["user"]["login"] != pull["user"]["login"]]


def pick(pull, comments, reviewer=None, drop=()):
    """Choose the reviewer, the commit their first comments were written on, the cut-off and the comments that count."""
    opened = sorted(threads(pull, comments), key=lambda comment: (comment["created_at"], comment["id"]))
    if reviewer is None:
        counts = Counter(comment["user"]["login"] for comment in opened)
        # A tie goes to whoever commented first: opened is in time order and max keeps the first of equals.
        reviewer = max(counts, key=counts.get, default=None)
    mine = [comment for comment in opened if comment["user"]["login"] == reviewer]
    if not mine:
        raise ValueError("No inline comment opens a thread" + (f" by {reviewer}" if reviewer else ""))
    head = mine[0]["original_commit_id"]
    on_head = [comment for comment in mine if comment["original_commit_id"] == head]
    kept = [{"id": comment["id"], "path": comment["path"], "line": comment.get("original_line"), "startLine": comment.get("original_start_line"),
             "side": comment.get("side"), "hunk": comment.get("diff_hunk"), "body": comment["body"], "url": comment["html_url"]}
            for comment in on_head if comment["id"] not in drop]
    if not kept:
        raise ValueError("Every comment on the reviewed commit was dropped")
    return {"reviewer": reviewer, "head": head, "cutoff": mine[0]["created_at"], "comments": kept,
            "dropped": sorted(comment["id"] for comment in on_head if comment["id"] in drop)}


def body_at(body, edits, cutoff):
    """The description as it stood at the cut-off, or None when GitHub no longer holds that version."""
    if edits["totalCount"] == 0:
        return body
    if edits["totalCount"] > len(edits["nodes"]):
        return None
    latest = max((edit for edit in edits["nodes"] if edit["editedAt"] <= cutoff), key=lambda edit: edit["editedAt"], default=None)
    return None if latest is None or latest["deletedAt"] else latest["diff"]


def comments_of(repo, number, fetch):
    return [comment for page in fetch("--paginate", "--slurp", f"repos/{repo}/pulls/{number}/comments?per_page=100") for comment in page]


def build(repo, number, reviewer=None, drop=(), fetch=gh):
    owner, name = repo.split("/")
    pull = fetch(f"repos/{repo}/pulls/{number}")
    if pull["base"]["repo"]["private"]:
        raise ValueError("Only public repositories: the judge sends this text to a model provider")
    picked = pick(pull, comments_of(repo, number, fetch), reviewer, drop)
    described = fetch("graphql", "-f", "query=" + EDITS, "-f", "owner=" + owner, "-f", "name=" + name, "-F", f"number={number}")["data"]["repository"]["pullRequest"]
    base = fetch(f"repos/{repo}/compare/{pull['base']['ref']}...{picked['head']}")["merge_base_commit"]["sha"]
    return {"repo": repo, "number": number, "url": pull["html_url"], "title": pull["title"], "base": base, **picked,
            "body": body_at(described["body"], described["userContentEdits"], picked["cutoff"])}


def candidates(repo, since, pages, minimum, fetch=gh):
    """Recently merged pull requests where one reviewer opened enough threads on one commit."""
    found = []
    for page in range(1, pages + 1):
        for pull in fetch(f"repos/{repo}/pulls?state=closed&sort=updated&direction=desc&per_page=100&page={page}"):
            if not pull["merged_at"] or pull["merged_at"] < since:
                continue
            try:
                picked = pick(pull, comments_of(repo, pull["number"], fetch))
            except ValueError:
                continue
            if len(picked["comments"]) >= minimum:
                found.append({"number": pull["number"], "merged": pull["merged_at"][:10], "files": fetch(f"repos/{repo}/pulls/{pull['number']}")["changed_files"],
                              "reviewer": picked["reviewer"], "head": picked["head"], "comments": len(picked["comments"]), "title": pull["title"]})
    return found


def context(case):
    described = case["body"] if case["body"] is not None else "(The description as it stood then is no longer available, so it is left out.)"
    return (f"Source: {case['url']}. The description is the version from before the first review comment ({case['cutoff']}), "
            f"taken from GitHub's edit history. The title is today's.\n\n# {case['title']}\n\n{described}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    listing = commands.add_parser("list", help="Print recently merged pull requests that fit the rule")
    listing.add_argument("--repo", required=True, help="owner/name of a public repository")
    listing.add_argument("--since", required=True, help="Earliest merge date, YYYY-MM-DD")
    listing.add_argument("--pages", type=int, default=1, help="Hundreds of recently updated closed pull requests to scan")
    listing.add_argument("--min-comments", type=int, default=3)
    building = commands.add_parser("build", help="Write case.json and context.md for one pull request")
    building.add_argument("--repo", required=True, help="owner/name of a public repository")
    building.add_argument("--pr", type=int, required=True)
    building.add_argument("--clone", type=Path, required=True, help="Local clone run.py will read; it must hold both commits")
    building.add_argument("--output", type=Path, required=True, help="New directory outside this repository")
    building.add_argument("--reviewer", help="Login; default is the person who opened the most threads")
    building.add_argument("--drop", type=int, nargs="*", default=[], help="Comment ids that are chatter, not requests")
    args = parser.parse_args()
    if args.command == "list":
        for found in candidates(args.repo, args.since, args.pages, args.min_comments):
            print(f"#{found['number']}  merged {found['merged']}  {found['files']} files  {found['reviewer']} @{found['head'][:9]}  "
                  f"{found['comments']} comments  {found['title']}")
        return 0
    output = args.output.resolve()
    if output == REPOSITORY or REPOSITORY in output.parents:
        parser.error("--output must be outside this repository: a case holds other people's comment text")
    try:
        case = build(args.repo, args.pr, args.reviewer, set(args.drop))
    except ValueError as refused:
        parser.error(str(refused))
    missing = [commit for commit in (case["base"], case["head"])
               if subprocess.run(["git", "-C", str(args.clone), "cat-file", "-e", commit + "^{commit}"], stderr=subprocess.DEVNULL).returncode]
    if missing:
        print(f"The clone lacks {' and '.join(missing)}. Fetch, then build again:\n"
              f"  git -C {shlex.quote(str(args.clone))} fetch https://github.com/{args.repo} {' '.join(missing)}", file=sys.stderr)  # the lab's own folder has a space
        return 1
    output.mkdir(parents=True, exist_ok=False)
    (output / "case.json").write_text(json.dumps(case, indent=2) + "\n")
    (output / "context.md").write_text(context(case))
    print(json.dumps({"output": str(output), "reviewer": case["reviewer"], "base": case["base"], "head": case["head"], "comments": len(case["comments"]),
                      "description": "as of the cut-off" if case["body"] is not None else "unavailable"}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
