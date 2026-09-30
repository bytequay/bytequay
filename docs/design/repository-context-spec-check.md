# Repository context: spec check

Status: implemented. It extends
[Repository context](repository-context-refactor.md) with one agent-service
addition, [a spec check task](#4-the-task-and-its-safety). The user approved the
task kind, at most eight reproduction turns per check, linking open issues that
already report a gap instead of reproducing them, and saving refuted candidates as
notes.

A spec check compares a topic's code with the specification it implements and
reports gaps. It reports only gaps a test run has reproduced. The user starts it
and reads the result; nothing in between asks for a decision.

## 1. What the user does

**Start.** A topic offers **Check against spec**, which opens its Spec check
drawer. The drawer lists the spec documents found for the topic, each already
selected, with how often the code links it: "Spec · 25 links from the topic's
code · 20 sections". Reference code is listed unselected. The user may add a spec
address or clear one, but never picks sections: the check plans them. A new
check of a topic starts from the specs its last check read. Usually the user just
starts. The run line states what the click starts: one task for the topic, a
check turn and up to eight reproduction turns, and the model.

**Finish.** The topic shows a report, and Needs you links to it. It says, for
example: "2 bugs and 3 feature requests confirmed · 4 not reproduced · 1 already
reported (#31252)", and what the check covered: "41 rules in 14 sections; the
next check continues with Column Mapping and Domain Metadata". Each confirmed
finding has its type, title, the spec rule with its link, the code lines, the
failing test, its output and a proposed change. **Open issue** opens GitHub's
new-issue form in the in-app browser, pre-filled with that finding; the user
submits it there. A body too long for the form's address is cut there and copied
whole to the clipboard, and **Copy body** copies it at any time. **Dismiss** hides
a finding and records why: filed on GitHub, not a real gap, or not worth filing.
Needs you lists each topic with findings to file until they are dismissed.
Nothing is filed, commented or pushed without the user's click.

## 2. Which specs

ByteQuay lists candidates without a model, strongest first:

1. Links in the topic's own code. A text scan of the topic's paths groups links by
   address without the `#section`, counts them and keeps their sections.
   Specification and protocol documents are kept. Source files of another engine
   are kept as reference implementations, used for comparison and never as rules.
   Issues and license links are dropped.
2. The topic's approved documentation sources and pages discovery suggested.
3. A spec the model proposes, shown unselected until the user selects it.

A topic whose code links no spec, like the Iceberg connector, starts with only
the second and third kinds. The check reads each selected spec's text with PAGE
and never relies on the model's memory of it.

## 3. The pipeline

**Check turn.** The task plans which sections to read; the user does not.

1. Sections the code links, most-linked first, and the sections next to them.
2. The sections of features the code declares, supported or refused, such as the
   feature names in Trino's `DeltaLakeTableFeatures`, and the spec's feature table.
3. Sections the spec's outline shows cover the same areas, although the code
   never links them. The first read of a page returns its outline, each heading
   with the page it starts on, so planning does not read the whole spec.

It checks features the code supports first, where gaps are bugs, then refused
ones. A later check continues with the sections this one did not reach. It lists
the features and the MUST, SHOULD and MAY rules those sections state. For each
rule it reads every code path that implements the behavior, not only the first,
since two paths that disagree are a strong signal: #31252's checkpoint writer
keyed actions by path while log replay keyed them by path and deletion vector.
It saves candidates with a spec quote and link, code lines and a predicted
outcome. Candidates may be wrong; the next step settles them.

Each rule gets one outcome:

| Outcome | Meaning | Candidate type |
|---|---|---|
| Follows | the code implements the rule | none; kept as a note |
| Diverges | implements the feature, but not as the rule says | Bug |
| Ignores | neither implements nor refuses what a MUST requires | Bug |
| Refuses | detects the feature and rejects it clearly | Feature request |
| Refuses unclearly | rejects it with a misleading result | Improvement |
| Skips a SHOULD | a recommendation is not followed | Improvement |
| Cannot tell | needs code outside the topic or runtime facts | Question in the plan |

Only rules for features the code claims to support can be Bug candidates. A
feature it does not support is a Feature request when it refuses the feature and
a Bug when it ignores it. For example, Trino's Delta connector supports 8 of the
spec's 19 writer features and refuses the rest with "Unsupported writer
features"; a table with the unsupported `catalogManaged` reader feature is
skipped with only a debug log, so it looks missing.

**Duplicate check.** Each candidate is searched in the repository's issues and
pull requests. An open issue that already reports it becomes "already reported"
with its link and is not reproduced. A closed one is reproduced, since the gap
may have come back.

**Reproduction turns.** One turn per candidate, most severe first, at most eight.
The task re-reads the code, since the candidate may be wrong, and writes the
smallest test in the module's own style that asserts what the spec requires. It
runs the test in its worktree as one command from the worktree root, without
`cd`, pipes, redirects or `&&`, so the command's exit status is the test's, and
reports the test file and that command. ByteQuay takes the exit code and output
of the command's latest completed run in that turn from the provider's events,
and the test's source from the worktree; it does not take the agent's word for
them. A command that does not name the test is refused, and so is a confirmed
result whose output shows a compile failure or no test run.

- **Confirmed**: the test fails, and its failure shows the gap the candidate
  states: a wrong result for a bug, the refusal for a feature request. The agent
  writes the issue's summary, impact and proposal, and picks repository labels;
  ByteQuay composes the draft from them and from the recorded rule, code lines,
  test and output.
- **Refuted**: the test passes. The code follows the rule, and the task saves
  that as a note citing the test.
- **Not reproduced**: the test cannot be built, fails for another reason, or
  needs an environment the task does not have. The report lists it separately and
  never as a finding.

A confirmed test keeps working after the report: it is the regression test for
whoever fixes the bug or builds the feature.

## 4. The task and its safety

A spec check is one workflow-owned task per topic, reusing it across turns the way
[learning batches](repository-context-refactor.md#questions-and-batches) do. It is
not interactive and appears in Tasks as a Spec check item.

- It may write tests in its own ByteQuay-owned worktree and run the project's
  build there, with network access for build dependencies, as
  [CI Autofix](ci-autofix-service.md) tasks do.
- It never receives a GitHub credential, pushes, comments or files anything. Its
  branch stays local; **Open issue** is the user's action.
- It never starts a ByteQuay local run; its test commands run in its own sandbox.
- Each turn has the configured turn timeout. Stop ends the check and keeps what it
  has confirmed.
- The planning turn gets the learning read tool, `repository_context_spec_search`
  and `repository_context_spec_candidates`; a reproduction turn gets the read tool
  and `repository_context_spec_result`.
- Before each reproduction turn the worktree returns to the check's baseline,
  keeping ignored build output for the next build. Before the task ends, the
  tests and build output are removed as well, so worktree cleanup succeeds. A
  worktree whose HEAD moved is kept for inspection and its task abandoned.

This is a new workflow task kind in the agent service, approved with this design.

## 5. The report and issue drafts

A topic keeps its latest check. A new check of the topic keeps the earlier
confirmed, refuted and already reported candidates, so it neither repeats nor
loses them, and starts from the sections the last check named next.

- **Bug draft:** summary, the spec rule quoted with its link, what the code does
  with its lines, impact, the test and its failing output, and the proposed fix.
- **Feature request draft:** the spec feature with its link, how the code
  refuses it now with its lines, who writes such tables or data, the test that
  shows the refusal, and a proposed scope.
- **Improvement draft:** the current behavior, the better one, and the test.

Drafts use the repository's labels when they match (`correctness`,
`delta-lake`). Each draft includes the test body and command, as #31252 did.

## 6. Limits

- One check at a time, at most eight reproduction turns per check. Candidates the
  check did not reach end as not reproduced, with the reason.
- A candidate needing containers, credentials or external services the task
  cannot start ends as not reproduced, with the reason.
- A spec newer than the code describes features the code never claimed; those are
  Feature requests only when the code refuses them, as above.
- The check reports gaps in the topic's paths. What it learns about other code
  goes to the plan's gaps.
