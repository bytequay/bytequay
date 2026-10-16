"""Read the approvals ByteQuay asked you for from the task event logs and test allow rules against them, for the
dashboard's Approvals tab: what was asked and how long you waited, which asks the rules would have answered, and what
they would have let through. Nothing is saved."""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
import json
import re

import activity

# What Codex writes below the command line of an ask, in this order; the command ends where the first of them starts.
CODEX_ADDED = ("\nReason: ", "\nAdditional permissions: ", "\nNetwork approval: ")
UNSANDBOXED = "Runs outside Claude's filesystem sandbox"
STOPPED = ("INTERRUPTED", "TASK_FAILED")
STOPPED_WITHIN = 5  # seconds; a turn stopped this soon after an ask ran out was ended by it
AUTOMATIC = "Automatic approval"  # Codex's own reviewer, which answers some requests before any card is shown
# Claude's own rule shape, the one ByteQuay already writes for the commands a review may not run.
RULE = re.compile(r"Bash\((?P<words>[^()]+?)(?P<prefix>:\*)?\)")
# Command or process substitution runs more than the words of its line say, so no rule covers a line that has one.
HIDDEN = re.compile(r"\$\(|`|<\(|>\(")
OPERATOR = re.compile(r"&&|\|\||\|&|[;|&\n]")
AMPERSAND_REDIRECT = re.compile(r"[<>]&|&>")  # 2>&1 and &> send output somewhere; they do not start a second command
# A suggested rule keeps the program, a subcommand right after it, and flags. Any other word may name a file, so it ends the
# rule, and so does a number: tail -5 and tail -50 are one rule.
SUBCOMMAND = re.compile(r"[a-z][a-z0-9-]*")
FLAG = re.compile(r"--?[A-Za-z][A-Za-z0-9-]*")
SUGGESTED_WORDS = 2  # words after the program that a suggested rule keeps
# What an ask's command line may do that a rule writer should know before covering it, by words in the line. The words
# are read here and never printed; only the label is.
# ponytail: a list of words and not a shell parser, so it says "look closer" and never "safe".
RISKS = tuple((label, re.compile(words)) for label, words in (
    ("writes to GitHub", r"\bgit\s+push\b|\bgh\s+(pr|issue|release|repo|label|workflow|secret|gist)\s+(create|edit|close|comment|merge|review|delete|reopen|ready|run|"
                         r"lock|unlock|fork|sync|set|enable|disable)\b|\bgh\s+api\b.*(-x|--method)\s*(post|put|patch|delete)\b"),
    ("network", r"\b(curl|wget|ssh|scp|rsync|nc|ftp)\b|\bgh\s+api\b|https?://"),
    ("deletes", r"\b(rm|rmdir|unlink)\b|\bgit\s+(clean|reset\s+--hard)\b"),
    ("runs another command", r"\b(eval|xargs)\b|\s-exec\b|\b(sh|bash|zsh)\s+-l?c\b|\bpython3?\s+-c\b|\bnode\s+-e\b|\|\s*(sh|bash|zsh)\b"),
    ("writes a file", r">>?\s*(?!/dev/null)[^\s&]|\btee\b")))
FLAGS = {"network": "network", "unsandboxed": "outside the sandbox"}  # what an ask itself says, beside what its words show


@dataclass(frozen=True)
class Ask:
    """One approval card and how it went. The command line is read in memory to match rules and is never printed."""

    task: str
    start: float
    provider: str  # codex or claude, from the card's title
    what: str  # command, file change, question, permissions or tool
    line: str | None  # the command of a command card; None when the card holds none that can be read
    outcome: str  # allowed, denied, expired or cancelled
    seconds: float
    stopped: bool  # the turn ended right after this ask ran out
    flags: frozenset = frozenset()


def card(item):
    """What a card asked and who asked it, as (provider, what, command line, flags)."""
    title, kind, detail = str(item.detail.get("title") or ""), str(item.detail.get("approvalKind") or ""), str(item.detail.get("detail") or "")
    words = title.split()
    provider = words[1].lower() if len(words) > 1 and words[0] == "Allow" else "other"
    line, flags = None, set()
    if kind == "COMMAND" and provider == "codex":
        line = activity.command_text({"argv": [detail[:min((detail.find(added) for added in CODEX_ADDED if added in detail), default=len(detail))]]})
        if CODEX_ADDED[2] in detail:
            flags.add("network")
    elif kind == "COMMAND" and provider == "claude":
        if detail.startswith(UNSANDBOXED):
            flags.add("unsandboxed")
            detail = detail.split("\n", 1)[-1]
        try:
            given = json.loads(detail)
        except ValueError:  # cut short at the card's size limit
            given = None
        if isinstance(given, dict) and isinstance(given.get("command"), str):
            line = given["command"]
    if kind == "COMMAND":
        what = "command"
    elif kind == "FILE_CHANGE":
        what = "file change"
    elif title.endswith("AskUserQuestion"):
        what = "question"
    else:
        what = "permissions" if title.endswith("requested permissions") else "tool"
    return provider, what, line, frozenset(flags)


def read_log(path, since=None, until=None):
    """The answered asks of one task log, Codex's own reviews as (seconds, verdict), every command line run, and how many
    asks have no answer in the log. Only turns that started from `since` and before `until` are read, when they are given."""
    events, _ = activity.read(path)
    windows, finished, unfinished = activity.items(events)
    kept = {turn for turn, (start, _) in windows.items() if (since is None or start >= since) and (until is None or start < until)}
    closed = activity.endings(events)
    made = []
    for item in finished:
        if item.kind == "approval" and item.turn in kept:
            provider, what, line, flags = card(item)
            outcome = str(item.detail.get("outcome") or "unknown").lower()
            ended = windows[item.turn][1]
            stopped = outcome == "expired" and closed.get(item.turn) in STOPPED and ended - item.end <= STOPPED_WITHIN
            made.append(Ask(path.parent.name, item.start, provider, what, line, outcome, item.end - item.start, stopped, flags))
    lines = [activity.command_text(item.detail) for item in finished if item.kind == "command" and item.turn in kept]
    return made, reviews(events, kept), lines, sum(item.kind == "approval" and item.turn in kept for item in unfinished)


def reviews(events, kept):
    """Codex's own reviews of requests, as (seconds, verdict). The verdict is the first word of what the reviewer said."""
    started, made = {}, []
    for event in events:
        payload = event["payload"]
        if event["type"] != "tool" or payload.get("name") != AUTOMATIC or event.get("turnId") not in kept:
            continue
        key = (event.get("source") or {}).get("providerItemId")
        if payload.get("state") == "STARTED":
            started[key] = activity.seconds(event["occurredAt"])
        elif payload.get("state") in activity.ENDED and key in started:
            said = re.match(r"[A-Za-z]+", str(payload.get("summary") or ""))
            verdict = "failed" if payload["state"] == "FAILED" else said.group().lower() if said else "said nothing"
            made.append((activity.seconds(event["occurredAt"]) - started.pop(key), verdict))
    return made


def parse_rule(text):
    """A rule in Claude's own shape as (words, prefix): Bash(./mvnw -o:*) covers what starts with those words, Bash(git status) only that command."""
    found = RULE.fullmatch(text.strip())
    # Bash(:*) would read as the words ":*" with no prefix mark, which is a prefix with no words.
    if not found or not found["words"].strip() or found["words"].endswith(":*"):
        raise ValueError(f"{text!r} is not a rule such as Bash(./mvnw -o:*) or Bash(git status)")
    return " ".join(found["words"].split()), bool(found["prefix"])


def pieces(line):
    """The simple commands of a command line, cut where one command ends and the next begins, with spaces made single.
    None for a line that can run more than its words say."""
    if HIDDEN.search(line):
        return None
    # Quoted text is covered so that an operator inside quotes does not cut the line.
    covered = AMPERSAND_REDIRECT.sub(lambda found: "x" * len(found.group()), activity.QUOTED.sub(lambda found: "x" * len(found.group()), line))
    made, start = [], 0
    for cut in [*OPERATOR.finditer(covered), None]:
        end = cut.start() if cut else len(line)
        piece = " ".join(line[start:end].split())
        if piece:
            made.append(piece)
        start = cut.end() if cut else end
    return made


def covered(line, rules):
    """Whether these rules answer a command line: every simple command in it must match one, and a prefix ends where a word ends."""
    made = pieces(line) if line is not None else None
    return bool(made) and all(any(piece == words or (prefix and piece.startswith(words + " ")) for words, prefix in rules) for piece in made)


def suggested(line):
    """The rules that would cover a command line, one for each of its commands: the program, a subcommand and flags. A path, a value,
    a number or quoted text ends the rule, so none is ever printed. None when no rule can cover the line."""
    made = pieces(line) if line is not None else None
    rules = []
    for piece in made or ():
        words = piece.split()
        # A program is a plain name or path. This also refuses a command that starts by setting a variable, which could hold a key.
        if not re.fullmatch(r"[\w./+-]+", words[0]):
            return None
        kept = words[:1]
        for place, word in enumerate(words[1:1 + SUGGESTED_WORDS]):
            if not (FLAG.fullmatch(word) or (place == 0 and SUBCOMMAND.fullmatch(word))):
                break
            kept.append(word)
        rule = f"Bash({' '.join(kept)}:*)"
        if rule not in rules:
            rules.append(rule)
    return tuple(rules) or None


def asked(asks):
    """What was asked, by provider and kind, as (provider, what, asks, asks that ran out, seconds waited): the most asks first."""
    kinds = Counter((ask.provider, ask.what) for ask in asks)
    rows = []
    for (provider, what), number in sorted(kinds.items(), key=lambda made: (-made[1], made[0])):
        own = [ask for ask in asks if (ask.provider, ask.what) == (provider, what)]
        rows.append((provider, what, number, sum(ask.outcome == "expired" for ask in own), sum(ask.seconds for ask in own)))
    return rows


def rule_rows(commands):
    """For every rule the asked commands need, the most needed first: (rule, asks that need it, asks it answers by itself, the
    seconds those waited, Codex asks, Claude asks)."""
    rows = []
    for rule, own in needed(commands):
        alone = [ask for ask in own if suggested(ask.line) == (rule,)]
        rows.append((rule, len(own), len(alone), sum(ask.seconds for ask in alone), sum(ask.provider == "codex" for ask in own), sum(ask.provider == "claude" for ask in own)))
    return rows


def uncovered(commands):
    """The command asks no rule can cover, and those whose command could not be read."""
    return [ask for ask in commands if ask.line is not None and suggested(ask.line) is None], [ask for ask in commands if ask.line is None]


def effect(commands, lines, rules):
    """What these rules would have changed: the command asks they answer and the waiting and turn endings that saves, the asks you
    denied that they allow, the risks among what they answer in a fixed order, and the commands run in the logs they cover, by program."""
    answered = [ask for ask in commands if covered(ask.line, rules)]
    marked = Counter(label for ask in answered for label in risks(ask))
    runs = Counter(activity.program(line) for line in lines if covered(line, rules))
    return {"answered": len(answered), "asks": len(commands), "saved": sum(ask.seconds for ask in answered), "waited": sum(ask.seconds for ask in commands),
            "kept": sum(ask.stopped for ask in answered), "stopped": sum(ask.stopped for ask in commands), "denied": sum(ask.outcome == "denied" for ask in answered),
            "risks": [(label, marked[label]) for label in (*FLAGS.values(), *(label for label, _ in RISKS)) if marked[label]],
            "covered": sorted(runs.items(), key=lambda one: (-one[1], one[0])), "runs": len(lines)}


def needed(commands):
    """Every rule the asked commands would need, each with the asks that need it: the most asks first, then the most waiting."""
    made = {}
    for ask in commands:
        for rule in suggested(ask.line) or ():
            made.setdefault(rule, []).append(ask)
    return sorted(made.items(), key=lambda one: (-len(one[1]), -sum(ask.seconds for ask in one[1]), one[0]))


def risks(ask):
    """What an ask's command line may do, as labels in a fixed order: what the card itself says, then what its words show."""
    found = [label for flag, label in FLAGS.items() if flag in ask.flags]
    if ask.line is not None:
        # Quoted words count too: bash -c "rm -r x" deletes as surely as rm -r x.
        found += [label for label, words in RISKS if words.search(ask.line.lower()) and label not in found]
    return found
