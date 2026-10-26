# ByteQuay Lab

The Lab is ByteQuay's internal Insights feature. It measures how the app and
its agents work: where turn time goes, which tools and commands they call and
how often those fail, what each model and effort costs, how well reviews hold
up, how much a person had to step in, and what the app takes from this Mac.
A change to the product is chosen from these numbers and then checked against
them. The Lab reads ByteQuay's task logs and database without writing, keeps
its results in its own folder, and serves its dashboard to this machine only.
The desktop app bundles its Python runtime and dependencies. **Settings →
Integrations → Lab → Open Lab** refreshes the statistics and starts or reuses
the dashboard automatically; **Show the Lab** adds the same action to the
sidebar. Installed users need no terminal setup: where the dashboard asks for
something to be run, it has a button that runs the same code with the lab's own
Python, and only storing the DeepSeek key stays a step for a terminal, as the
Mac asks for the key there. The commands below remain available for
development and controlled experiments.

## App health

The landing view combines the last day's existing backend logs with recent
resource samples. **Agent activity** opens the existing turn and review tools.
**Analyze now** at the top of Lab and **Open Lab** analyze
`~/Library/Logs/ByteQuay/backend.log` and retained `backend.log*` rotations
read-only. Development can select another
log folder with `desktop.py --logs PATH` or `dashboard.py --logs PATH`.

Reads are bounded to eight files and four MiB per file, including decompressed
archives. The page discloses unreadable, truncated and omitted sources. Saved
`app-health.json` contains fixed failure categories, component labels, counts,
times and aggregated timing pairs; raw messages, stack traces, paths, task IDs
and credentials are not copied into it or task briefs. Ordinary warnings and
errors need no extra logging. Send latency uses matched `send-timing` phase
pairs only when those optional debug logs already exist. Provider wait includes
provider processing and network time; overlapping phases must not be summed.

Health suggestions use local rules, require no model key, and prepare a task
brief with evidence, a source location and a verification measurement. They do
not execute fixes. Missing records preserve the last agent snapshots and mark
them stale while still opening the health page. **Start resource monitoring**
is an explicit opt-in to the existing persistent monitor; **Processes** stops
it. Memory and open-file growth comparisons use one app/backend process session
with a stable observed workload. Detached descendants remain counted after
their parent exits, provided the monitor observed them beforehand. Neither
growth nor detachment alone proves a leak. The health view does not directly
measure UI responsiveness or external-service availability.

## Review discovery calibration

`run.py` runs one bounded, read-only discovery pass against exact Git objects,
with the Codex command line or, with `--provider claude`, the Claude one, or
with `--provider agent` the lab's own agent (see below). Use
it to inspect whether a policy elicits useful hypotheses before running a
production review comparison. It does not exercise ByteQuay's reporting
tools, child agents, validation turns, final selection, or persistence. It cannot
establish that the complete review workflow has improved.

The script exports the merge-base and head as ordinary files, without Git history,
working-tree edits, untracked files or later commits. It also captures the diff,
policy and supplied context, hashes every input, and records the CLI version,
model, reasoning setting, exact command, elapsed time, JSONL events and response.
The source checkout is read-only. Output must be a new directory outside it.
Symlinks and submodules are rejected rather than silently changing source meaning.
Each tree is copied in full through one `git cat-file --batch` process, so the cost
follows the size of the repository, not of the change: a tree of 15,000 files takes
about two seconds.

The CLI pins the built-in OpenAI provider and uses the existing authenticated account. It ignores user configuration
and execution rules, disables memory, plugins, apps, native delegation and web
search, uses an ephemeral read-only session, and redirects its SQLite state and
logs to the output directory. The Claude command line gets the same prompt and
answer shape. It starts inside the frozen input with no customization, no
saved session and no connected servers, and with only the tools that read and
search files, kept to that directory. It also uses the existing login, and
nothing a parent Claude session leaves in the environment reaches it. The
invocation does not change ByteQuay settings,
reset a database, or write to GitHub. A hard wall-clock timeout kills the provider
process group. An interrupted or failed arm has no discovery score.

## Freeze and run

Before changing policy, save the old `ReviewPrompts.java` and `bytequay-review.md`
outside the checkout. Record the exact source commit and hashes with them. Keep
that manifest beside the results; do not reconstruct a baseline from memory.
`run.py` compares the policy resource only. Its identical discovery-only wrapper
replaces the unavailable report tools; it does not reproduce the Java request or
the production workflow. Keep that limitation attached to any result.

Supply a context file containing only intent, requirements and PR prose available
at the review cutoff. Exclude later review comments, fixes and reference answers.
Record the prose source and cutoff in that file; Git timestamps alone do not
prove when a comment was available. A real historical fixture requires an
independently frozen context snapshot. Do not substitute today's PR description.

Run each policy against the same refs, context, model, reasoning and test timeout.
Replace `CONFIGURED_REVIEW_MODEL` and `CONFIGURED_REVIEW_EFFORT` with the actual
Review configuration; both arguments are required. The harness ignores user
configuration, so it cannot infer those settings. Replace `TEST_TIMEOUT_SECONDS`
with the explicit wall-clock budget for this experiment. This timeout belongs
only to the harness. Production turns have no time limit unless
`bytequay.agent.maximum-turn-runtime` is set, and these runs do not change it.

```sh
python3 scripts/review-calibration/run.py \
  --repo /path/to/source --base BASE_SHA --head HEAD_SHA \
  --policy /path/to/frozen-baseline/bytequay-review.md \
  --context /path/to/frozen-context.md --output /tmp/review-baseline-run1 \
  --model CONFIGURED_REVIEW_MODEL --reasoning CONFIGURED_REVIEW_EFFORT \
  --timeout TEST_TIMEOUT_SECONDS

python3 scripts/review-calibration/run.py \
  --repo /path/to/source --base BASE_SHA --head HEAD_SHA \
  --policy backend/app-github/src/main/resources/agent-skills/bytequay-review.md \
  --context /path/to/frozen-context.md --output /tmp/review-proposed-run1 \
  --model CONFIGURED_REVIEW_MODEL --reasoning CONFIGURED_REVIEW_EFFORT \
  --timeout TEST_TIMEOUT_SECONDS
```

`runtime.json` names the provider, the model and the effort of the arm, and what
it used: tokens in with the cached ones among them, tokens out, and for Claude
its own cost at API prices. A run that is stopped from outside takes its
command line down with it and keeps the status `started`.

Inspect `runtime.json` first. Keep failures and partial transcripts; do not count
a timeout or `incomplete_output` as zero findings. Completion requires a successful
turn event and a schema-valid response, even when the CLI exits zero. Then inspect
`response.json` and the source cited by
each hypothesis. Check both manifests agree on all source and context hashes.
Do not publish model hypotheses as validated findings.

## The lab's own agent

```sh
DEEPSEEK_API_KEY=... uv run run.py --repo /path/to/source --base BASE_SHA --head HEAD_SHA \
  --policy POLICY --context CONTEXT --output OUTPUT --provider agent --model deepseek-v4-pro --reasoning default
```

`agent.py` is an agent written on the model API itself, with no agent
framework, so every part of it can be read and changed here. Each use gives its
loop the tools it offers and the one that hands the answer in. A review is
described here; the Resources page's risk look (see Monitor) and the rules
writer (below) are the others.
`--provider agent`
runs it on the same frozen input, with the same prompt and the same answer shape
as the two command lines, so the judge scores all three alike. It calls
DeepSeek through the OpenAI SDK, as the judge does, and records the effort
without sending it.

The agent is one loop. It sends the conversation and four tools to the model.
When the model calls tools, the agent runs them and adds each result to the
conversation under the call's id; when the model hands the review in, the run
ends. Three tools only read: `list_files`, `read_file` (300 numbered lines at a
time) and `search` (a regular expression, 50 matches at most). Each is kept to
the input folder: a path that leads out of it, with `..` or as an absolute path,
is refused. The fourth, `submit_review`, takes the answer, which is checked
against the answer shape like any tool call. A mistake, the model's or a
file's, comes back to the model as an error it can act on: a path that is not
there, arguments that are not JSON, a review that misses a field.

A run ends after 40 model calls or at `--timeout`. The last call, or the one
after a conversation passes 100,000 prompt tokens, comes with a request to hand
the review in now and offers the tool that hands it in alone, so the call
cannot go to reading on. A review handed in then and sent back for what is
wrong with it gets two more calls to put it right. The model's own messages go back to it as they came,
thinking included, which a thinking model needs between the steps of one task.
`events.jsonl` keeps one line per model call (its time, tokens, text and the
calls it made) and per tool call (its time, the size of its result and any
error), and `runtime.json` the steps, the tokens and `agent.py` followed by a
hash of its source, so a changed agent is told apart in the results.

`test_agent.py` drives the loop with a stand-in model that answers from a
script, and runs the whole arm against a stand-in of DeepSeek's chat API on
this machine (`DEEPSEEK_BASE_URL`), so no test needs a key or the network.

## Rules writer

```sh
uv run writer.py NAME --setting SETTING --kind work
uv run writer.py NAME --setting SETTING --kind expert
uv run writer.py NAME --setting SETTING --kind merge --versions SETTING SETTING
```

`writer.py` has the lab's agent write a new version of the review rules from
what a setting's runs in experiment `NAME` did. `work` changes how the
reviewer works (what it reads first, how it checks a lead, when it stops) and
may not name the repository, as the rules serve every repository. `expert`
adds what a reviewer of the experiment's repository knows: its conventions and
what its maintainers ask for. Each `expert` change must be backed by runs of
at least two pull requests, when the evidence holds two: what only one pull
request needed is knowledge of that change, and would only teach the rules
the tuning cases.

`merge` puts together versions that settings of the same experiment tried on
the same cases. Besides the runs of the rules as they stand, the writer reads
each version under `versions/<name>/`: the version, what its writer changed
and why, and its runs, whose description and change are those of the run of
the same name under `runs/`. It compares what the reviewer did under each version
with what it did before, pull request by pull request, keeps the changes whose
runs show the reviewer working better and merges them into one file. Nothing
it reads says which version scored better. A change may name a version's run
as `<name>/<run>`; the rule against naming the repository and the one of two
pull requests do not apply, as the merge carries both kinds on.

The writer reads an evidence folder built from the setting's completed runs,
all of which must have used the same rules file: that rules file; the task
every run was given and the shape of its answer, which are fixed, so a rule
must fit them (the task asks for leads to check later, not checked findings);
and for each run its change, the pull request's prose from before the review,
the leads it handed in, its time and tokens, and what it did. It sees the
change as a diff, not the whole code, and is told to claim only what it read. What a run did is its Codex
commands or Claude tool calls in order, each with the start of what came back,
and what the model said. All runs read together share 120,000 characters of
steps, at least 6,000 each, so every run fits the writer's conversation; a
run's steps lose their middle first. The run's input folder appears as `.`, so no path names this Mac's user.
Only these files are copied. The writer never reads a judgement, a score, a
label or a case, and the folder is searched for every reviewer comment of the
cases all the same: one found there stops the writing and the folder is
deleted. A writer that saw the answer key would learn the key, not how to
review. What it reads goes to DeepSeek's API, with the judge's key.

It hands in edits to the rules, every change with the runs that show the need
for it, and one lesson. An edit quotes a piece of the rules exactly and gives
what goes in its place, so the rest stays word for word and a long rules file
is never written out again; the lab makes the edits. A quoted piece that is not
in the rules exactly once, or edits that overlap, are sent back, saying how
much of a quote that went astray does match, as are rules
that come out unchanged or longer than one and a half times the current rules
plus 2,000 characters (they go with every review), more than eight changes or
twenty edits, and changes that name no run it read. A writing may read until
its conversation holds 200,000 tokens, as it reads every run, and ends after 40
model calls or 15 minutes; its last call can only hand the edits in, with two
more calls when they are sent back.

Each version stays in the experiment's `rules` folder: `work-1.md` and so on,
counted per kind; `work-1.json` with the changes, the reasons, the lesson, a
diff against the current rules, the runs read, the hashes of the rules, the
writer and the agent, and the tokens; `work-1.events.jsonl` with every model
and tool call; and `work-1.evidence/`, exactly what the writer could read.
The command prints a setting that names the new rules, such as
`"quick-work-1": {..., "policy": "rules/work-1.md"}`. Add it to the plan's
settings and run `batch.py NAME` to try the version on the same cases, then
judge and compare it like any setting. Cases the writer learned from are
tuning cases: a better score on them says the version runs, not that it
reviews better.

## Improving the rules

```sh
uv run improve.py --tuning TUNING --unseen UNSEEN --setting SETTING --parallel 2
uv run improve.py --continue
```

`improve.py` makes one improvement from start to end, with an experiment of
tuning cases and one of unseen cases (marked `"unseen": true`) that both name
`SETTING` with the same rules, model and effort. It goes through these stages
and keeps how far it got in `improvements/N/improvement.json` in the lab's
folder:

1. `base`: the setting's missing runs in both experiments, and the judge on the
   unseen ones.
2. `work` and `expert`: the writer's two versions, from the setting's runs on
   the tuning cases.
3. `tried`: a setting for each version in the tuning plan, such as
   `SETTING-work-1`, and its runs. They are not judged: nothing reads their
   scores.
4. `merge`: the writer merges the two from what their runs did.
5. `checked`: the merged version is copied into the unseen experiment, gets a
   setting there, runs and is judged.
6. `compared`: how many comments each of the two settings found or partly
   found on the unseen cases, each with its range, and the change between them
   with its range. Both ran the same pull requests, so the change is drawn with
   the same cases on both sides, which a case's own difficulty cannot move.
   `better` or `worse` when the change's whole range lies on one side of zero,
   `not clear` otherwise.

Nothing of one unseen case is shown, only these totals. The improvement holds
the tuning experiment for as long as it runs, and the unseen one while it runs
there, so no `batch.py` and no second improvement can change them meanwhile.
Ctrl+C stops it; `--continue` carries on with the last improvement that did not
end, and runs nothing twice. A writing that hands nothing in ends the
improvement; carried on, that writing starts over under a new number. One
improvement of a small setting takes about 36 Codex runs, some three hours two
at a time, and about $1.50 of DeepSeek for three writings and the judge. Making
a version the rules ByteQuay ships is a person's step.

## Scoring

`case.py`, `judge.py` and `report.py` score runs against a human reviewer's
inline comments. They need Python 3.12 or later and the one dependency in
`pyproject.toml`; run them with [uv](https://docs.astral.sh/uv/) from this
directory. The judge calls DeepSeek and reads its key only from
`DEEPSEEK_API_KEY`.

A case is one public pull request: the reviewer who opened the most threads, or
the one named; the commit their first comment was written on; that comment's
time as the cut-off; and their thread-opening inline comments on that commit as
the answer key. The description is the version from before the cut-off, read
from GitHub's edit history, and is omitted when that version is gone. A case
never falls back to a later commit, because the comments would point at other
code. `case.py list` prints recently merged pull requests that fit, so cases
are chosen by a rule. Private repositories are refused: the judge sends comment
text and leads to a model provider. A case holds other people's comment text,
so `--output` must be outside this repository; record chosen cases only as
pointers (repository, number, commit, comment ids) in `cases.json` in the lab's
folder, which is never committed. A case whose comments or scores anyone has
read is a `tuning` case and can no longer serve as an unseen holdout.

```sh
uv run case.py list --repo OWNER/NAME --since YYYY-MM-DD
uv run case.py build --repo OWNER/NAME --pr NUMBER --clone /path/to/source \
  --output /tmp/lab/cases/NAME-NUMBER
```

`build` prints the fork point and the reviewed commit. Pass them to `run.py` as
`--base` and `--head`, with the case's `context.md` as `--context`. Write each
run to `ROOT/SETTING/RUN`, with one `SETTING` directory per rules file or other
arm, then judge it and print the table:

```sh
uv run judge.py run --case /tmp/lab/cases/NAME-NUMBER/case.json --run ROOT/SETTING/RUN
uv run report.py ROOT
```

The judge makes one call per reviewer comment and answers FOUND, PARTLY or
MISSED with the leads that match. Leads are shuffled by `--seed` and numbered,
so the judge sees neither their order nor their setting. An answer that is
empty, cut off or the wrong shape is asked again once and then recorded as a
judge error, never as MISSED. Only completed runs are judged. `report.py` lists
failed, timed-out and unjudged runs apart and leaves them out of every total,
and it names a setting whose runs used different rules files, models or
efforts. Leads
that match no comment are counted as extra leads and are not judged: the
reviewer may have missed a real problem.

Check the judge before trusting a table. The answer key as leads must be all
FOUND, another case's leads must be all MISSED, and two judgements of one run
show how many verdicts flip, since the judge has no seed:

```sh
uv run judge.py run --case CASE --oracle --output /tmp/lab/checks/known-good
uv run judge.py run --case CASE --leads OTHER_RUN/response.json --output /tmp/lab/checks/known-bad
uv run judge.py run --case CASE --run RUN --output /tmp/lab/checks/again
uv run judge.py agree --judgement RUN/judgement.json --labels /tmp/lab/checks/again/judgement.json
uv run judge.py agree --judgement RUN/judgement.json --labels LABELS.json
```

`LABELS.json` maps a comment id to the verdict a person gave it. Set the
agreement to reach before reading the judge's answers, and after changing the
judge prompt measure it again on calls nobody has read.

The dashboard's Judge page takes the labels by hand. For an experiment, every
comment the judge answered in one of its runs is a pair, shown word for word as
the judge was asked (the run's leads shuffled and numbered, then the comment
and the code under it), without the judge's answer or the setting that ran,
in one fixed order that mixes settings and pull requests. Found, Partly and
Missed keep a label in the experiment's `labels.json` in the order given, and
the last can be taken back. Once a pair is labelled, the page shows how many
the judge answered the same and, for each of your answers, how the judge
answered; 30 labels give a first number. The scores on the Reviews tab say how
far the two agree.

## Experiments

An experiment is a comparison that is kept: a folder with a plan and every run
made for it. It can be stopped, started again, and added to later, so the same
cases can be run again when a model, an effort or the rules change.

```text
FOLDER/cases/NAME/case.json, context.md        a frozen case, built with case.py
FOLDER/experiments/NAME/plan.json              what to compare
FOLDER/experiments/NAME/SETTING/CASE_repN/     one run, as run.py leaves it
```

`FOLDER` is the lab's own folder, `~/Library/Application Support/ByteQuay Lab`,
unless `--folder` names another. A plan names the cases, where the clone of
each repository is, and the settings. A setting is a provider (`codex`,
`claude`, or `agent`: the lab's own agent on DeepSeek, which records the effort
without sending it), a model, an effort, and a rules file. Only the agent's
runs are handed the DeepSeek key, from `DEEPSEEK_API_KEY` or the Keychain;
without one they wait and the experiment says so. The rules file of the plan
counts for every setting that names none of its own. Keep a copy of it inside
the experiment and name it by that path, so the experiment still means the
same after the rules change.

```json
{
  "version": 1,
  "repeats": 2,
  "timeout": 900,
  "policy": "rules.md",
  "clones": {"OWNER/NAME": "/path/to/source"},
  "cases": ["NAME-NUMBER"],
  "settings": {
    "codex-high": {"provider": "codex", "model": "MODEL", "effort": "high"},
    "claude-max": {"provider": "claude", "model": "MODEL", "effort": "max"}
  }
}
```

```sh
uv run batch.py NAME --list
uv run batch.py NAME --parallel 2
uv run batch.py NAME --judge
```

`batch.py` runs every run of the plan that has not ended, a case at a time, so
a stopped experiment has compared whole cases. A run that ended is kept as it
is, also when it failed or ran out of time. A run that was interrupted is run
again. Ctrl+C or a stop from outside ends the command lines that are running.
An experiment runs once at a time: while it runs it names its process in
`running.pid` and holds a lock on that file, a second start is refused, and
`--list` shows its unfinished runs as running. The system lets go of the lock
however the process ends, so a file left behind by a crash is not believed.
The two source trees of a run are removed when it ends; its manifest keeps
their hashes. `--list` only says how every run stands. `--judge` then scores
the completed runs that have no verdicts yet: the key is read from
`DEEPSEEK_API_KEY`, then from the Keychain, or asked for without being shown or
kept when a person is at the terminal. `"executables"` in the plan can name the
full path of `codex` and `claude` for a scheduler that does not know where they
are.

`"unseen": true` in a plan marks an experiment of unseen cases, kept apart
from the tuning cases a change was made from. Its scores are shown only as each
setting's totals and their range, never a run or a comment: `report.py` prints
the totals alone, and the dashboard's Reviews tab leaves out the comparison
comment by comment and the list of runs. The Judge page never offers its pairs,
as a pair shows the reviewer's comment, and `writer.py` refuses to learn from
it. What the runs and the judge leave on disk is complete; nobody opens it.
After three changes chosen on the same unseen cases, they have been leaned on
enough: replace them with pull requests merged since, and make the old ones
tuning cases.

The dashboard can do the same with a button: its Reviews tab shows how the
runs of the experiment you picked stand, and runs and judges what is missing,
one run at a time, or stops the runs. It starts `batch.py NAME --judge` apart
from the page, so closing the page does not stop the runs, and what it says
goes to `batch.log` beside the plan. It judges only with the key in the
Keychain or in `DEEPSEEK_API_KEY`, as nobody is there to be asked for it. The
same tab sets how often the experiment gets a new judged round, as `--every`
does, and saves the scores again from the runs when the saved ones cannot be
read.

### Every week by itself

```sh
uv run batch.py --store-key
uv run batch.py NAME --every week
uv run batch.py NAME --every never
```

`--store-key` keeps the judge's DeepSeek key in the Mac's Keychain. The Mac's
own `security` tool asks for it, so the key never passes through the lab.
`--every week` (or `day`) then adds a round to the experiment by itself, from
now on: every case under every setting once more, judged with the key from
the Keychain, and the scores saved again. `--round` adds one round by hand.
Every round counts in the comparison, so it grows firmer week by week, and
the runs of each round stay apart in the list of scored runs.

The lab keeps the schedule itself: `schedule.json` in its folder, and one
LaunchAgent, `~/Library/LaunchAgents/com.bytequay.lab.plist`, which it writes,
loads and removes. Every night at three the agent runs `batch.py --due`, which
starts the rounds that are due, one after another; a night the Mac slept
through counts when it wakes. What they say goes to `schedule.log` in the
lab's folder. `--every never` for the last scheduled experiment removes the
agent.

## Monitor

```sh
uv run monitor.py
uv run monitor.py --start
uv run monitor.py --stop
```

`monitor.py` watches what ByteQuay takes from this Mac. With no option it
prints how every part stands now. `--start` keeps a sampler running from then
on, at every login and whenever it stops, as a second LaunchAgent the lab
writes and removes (`com.bytequay.lab.monitor`); `--stop` ends it.

Every fifteen seconds the sampler notes the processes, memory, CPU, threads and
open files of each part of the app: the Electron app's main process, its
windows, its GPU process and its other helpers; the Java backend; the agent
command lines the backend starts; and the commands they run. It knows the app
by its program, the packaged ByteQuay or Electron started from a ByteQuay
checkout with `.` (a browser test's Electron runs a script of its own), and the
backend by `bytequay-backend.jar`, and it counts everything they started. A
process's program and arguments only tell the parts apart and are not kept.
They are read again whenever a process turns into another program: `dev.sh`
starts the backend as a copy of itself that copies the built JAR and then
becomes Java, with the same process id. It also counts, read-only from
ByteQuay's database, the turns running, the open tasks and the inputs waiting
in their queues, with the states the backend itself counts as running and
waiting (a test reads them from its source).

Every hour it saves the turn, review, Tuning, context and memory tables again
(`activity.py`, `tuning.py`, `context.py` and `memory.py` with `--save`, into the lab's folder), one after
the other as processes of their own at the lowest priority, so no page shows
numbers more than an hour old. A save takes about 15 seconds. Then it starts
the lab's agent to look for risks (`advisor.py --if-due`), which looks only
when the last look began a day ago or more and says nothing without a key, so
a look happens about once a day.

Every five minutes, while the backend runs, it asks the backend's JVM how full its
heap is, with the `jcmd` beside the backend's `java` (`jcmd PID GC.heap_info`,
at the lowest priority): what the heap uses, what the JVM has set aside for it,
its limit, and what lived through collections (the old and humongous regions).
The backend's memory is more than its heap, so only these numbers tell how
near the heap is to its limit. A runtime without `jcmd` leaves no row.

A few times a day it counts who holds the app's data on disk
(`--count-disk` counts once). The count walks every file, so the sampler
starts it as a process of its own at the lowest priority. Each top-level
folder of the app's data is a place, and each file has an owner: an open task
(apart: open tasks nobody changed for a week), a finished task, a removed task
(its worktree or run data is still there, but the database no longer has the
task), a retired feature (the code graph's index, the old development flow's
worktrees, the repository clones kept in `repos/` before workspaces), an old
database copy, the repositories worktrees are made from, or
the app itself. Whose task data is whose comes read-only from the database: a
task's id, its state and when it last changed, and the worktree each task,
rebase or cherry-pick row names (a test checks that no other table names a
worktree). A file of 64 KB or more is mapped onto the disk without being read,
so a block that copy-on-write copies share is counted once; one that copies of
different owners share counts as shared. `du` counts every copy in full and
said 72 GB for the code graph's index, where the blocks came to 29 GB. A file
with two names counts once.

The numbers go to `monitor.db` in the lab's folder. Every sample is kept for a
week; older ones are folded into one row an hour with the hour's average and
peak, and kept for 90 days, as are the disk counts. The sampler counts its own
cost apart, with the disk count it starts, as the part `monitor`: on the Mac
it was built on, about 37 MB and under 1% of one core. A disk count of 4.7
million files took two minutes, and up to about 470 MB while it lined up 2.4
million stretches of the disk.

The dashboard's Resources page shows them: tiles and the last 15 minutes,
looked at again every two seconds; memory, CPU, threads and turns running over
the last hour, day, week, 30 or 90 days, on one clock so a spike can be set
against the work; every part now, typically and at its peak; the typical
memory of each group on each day from the samples when no turn ran, so memory
held with no work to do shows as a column that rises; and the disk: what could
be freed (removed tasks and retired features, which nothing uses), what open
tasks idle for a week hold, the free space on this disk, every owner with what
it is, and every place with how much it grew since the first count kept.
Memory is what a process holds in RAM. The app's helpers share a little of
it, so the sum runs slightly high.

At the top of the page, "Look for risks" has the lab's own agent look at these
numbers (`uv run advisor.py` looks once from a terminal). `advisor.py` gives
the agent loop four read-only tools: an overview of every part's memory, CPU,
threads and open files typically, at the peak and now, with the Mac's totals,
the agent turns and the limits (the backend's heap limit and whether it stops
when that runs out, read from its command line, and the disk's free space); a
series of one amount of one part, or of the Mac, per hour, optionally from the
samples when no turn ran; the Mac's busiest moments with what ByteQuay took of
them; and the disk by owner with its growth, what Clean up would delete and
the old database copies it deletes when its box is ticked.
The agent hands in a summary and at most eight suggestions, each with a risk
(high, medium or low), the numbers behind it, what to do, where, the number to
watch, and how it is carried out: by Clean up, or as a fix at one of the places
in ByteQuay the agent is told about (`PLACES`: the backend's JVM flags in
`dev.sh` and `backendProcess.ts`, what a finished task removes in
`DefaultAgentCompletionService`). A risk neither can act on, or one that only
says to watch a number, is left out, and a fix names a place only when changing
it would move the number it gives to watch. An answer whose numbers are missing, that
offers a clean-up when nothing is left to clean, or whose fix names none of
those places, goes back to it as an error. The page shows them the gravest first, each that Clean up
carries out with a button that asks for the same confirmation, and what the
agent saw first. A look runs apart from the page and keeps
`advice/advice.json` and every model and tool call in `advice/events.jsonl` in
the lab's folder; every look that finishes is also kept in `advice/looks/`.
The agent is told the titles the last look found, each with its action, and
gives a risk it finds again with the same action the same title, so each risk
says how many earlier looks found it too; a risk whose action changes, such as
one that only said to watch before, gets a new title that says what to do,
and the page lists the risks gone since the look before. A risk takes the
marks of the lab's own suggestions, kept in the same `record.json` and
`changes.json`: being worked on, not wanted (left out until brought back on
the Suggestions tab), or made. What became of them, on the Suggestions tab,
lists both kinds; a risk that was made is checked by whether the newest look
on a later day than the change still finds it. The next look is told how each
of the last look's risks stands: being worked on since a day, not wanted (so
it leaves it out), or made on a day (so it hands it in again only if the
numbers since still show it). A look takes the DeepSeek key from the Keychain
(`uv run batch.py --store-key`) and sends counters and the last look's titles
with those states and days, never a path, a command, a task's title or a
reason typed for a mark; it ends after 20 model calls or ten minutes.

A risk with a fix has the same brief and **Fix it** as a suggestion (see
Suggestions): it opens a ByteQuay task for the change, a task on it says so on
the card, and completing that task notes the change, which the looks after it
then check. Both pages note completed tasks, whichever is opened first.

Below the disk, "Clean up" lists what nothing in the app uses any more, by the
same rules as the disk count: what removed tasks left in the runtime and among
the worktrees, and what retired features left. `uv run cleanup.py` prints the
same list and `uv run cleanup.py --apply` deletes it. The old database copies
go only when asked for: with `--with-copies`, or on the page when its box is
ticked, as they are the only way back to an older database. On the page, Clean up
asks you to confirm, then starts `cleanup.py --apply` apart from the page and
shows how far it got. This is the only place the lab writes to ByteQuay's data
folder, and only once you confirm. A worktree goes through git
(`git worktree remove`, never with `--force`), which keeps one with changes not
committed or files it does not track; its branch stays in the repository, so
no commit is lost. A repository's own folder, such as an old clone in `repos/`,
goes after the worktrees made from it, and only once all its work is on a
remote: nothing left uncommitted, no commit that no remote branch has, no stash,
and no worktree made from it still there. A removed task's event log stays, as
the lab reads it.
Nothing that changed in the last day goes, and the database is read again just
before anything is deleted. When it is done, the page says what was deleted,
how much the disk gained and what was kept and why, and the disk is counted
again. How the last clean-up went is kept in `cleanup/cleanup.json` in the
lab's folder; only one runs at a time.

The dashboard's Processes page lists every program the lab's own Python runs
now (its pages, the monitor, experiments and their review runs, tests) with
when it started and its memory, looked at again every three seconds. Stop ends
one as Ctrl+C would; for the monitor it also removes the LaunchAgent, which
would otherwise start it again. Below, the monitor is started or stopped as
`--start` and `--stop` do. A stopped page cannot start itself: start it again
with `uv run dashboard.py`.

## Tuning

```sh
uv run tuning.py --save
```

`tuning.py` counts how much a person had to step in to get the agent's work
done, and how its turns ended, so a change to the prompt, the tools or the
flow can be checked against them. From the task logs it counts, for every
turn, the messages that started a turn, waited in the queue or went into a
running turn (steering), the approvals a person answered and the ones that ran
out or were cancelled, and the rate limits, retries and interruptions. From
ByteQuay's database, read without writing, it adds what started each turn
(a person or an automation such as a draft pull request), whether someone
asked it to stop, how it ended, and each task's state and repository. A turn's
terminal reason is free text and is not read; the database leaves its failure
kind empty. A task whose row is gone from the database keeps its turns, with
no state. `--save` writes `tuning.json` into the lab's folder.

The dashboard's Tuning page shows the last 7 days, 30 days or all of it. "A
person in the loop": the tasks, how many needed one message and nothing else
(one-ask tasks), the typical messages per task, the approvals answered and the
turns stopped; how a person stepped in after asking; how many messages each
task needed; and the tasks that needed the most. A task's first message is its
ask; every later one counts as another message, which can be a correction or
new work, as the log keeps no reason. "How turns ended": the share completed
of the turns whose ending the database still holds, the rate limits, retries
and interruptions for every 100 turns, and the turns of each week by how they
ended, with those whose ending is not known hatched.

"Tool paths" follows the calls of every turn in the order they started, by
the name a call is counted under in the Tools tab: the calls in all and per
turn, the lookups of ByteQuay's own tools (`bytequay_list_tools`,
`bytequay_get_tool` and `ToolSearch` find a tool and do no work), and the most
times one call followed itself within a turn. A grid of the most called tools
counts which call came straight before which, so a loop shows on its
diagonal; its shades go by the tens, as a few pairs run to thousands. A table
lists every tool and program with the turns that used it, its calls, the calls
of a typical and of the busiest turn, and the share of its calls that came
straight after a call to itself. A table saved before paths were counted asks
to be saved again.

"Setups" lists every setup the turns ran with. ByteQuay names it for every
turn it starts: two halves, such as `3fa9c1d2-91be07aa`, the first from
ByteQuay's own instructions for the provider session (its command scope, plan
guidance and tool guidance, without the task's own context such as its pull
request) and the second from the tools it was given. Each kind of session,
such as a development task or a read-only discussion, has a setup of its own.
A change to either half gives a new name, and Changed says which half no
earlier turn had. Each setup shows when it first ran, its turns, the share completed, the
typical minutes and calls of a turn, and the steering or stops and the rate
limits for every 100 turns, so a change can be judged by the turns before and
after it. Turns from before ByteQuay named setups have none.

## Context

```sh
uv run context.py
uv run context.py --save
```

`context.py` measures how the context of an agent turn grows from one model
call to the next, what fills it, and what a call's time goes with, from the
task logs. ByteQuay logs the tokens a Codex turn has spent after every model
call as a running total, and the step from one total to the next is what that
call was sent (the whole context) and what it wrote. Codex logs a call's
tokens after the commands and tools the call started, so what started before
them is what that call ran, and its results come into the next call. A call is
sent what the call before it was sent, what that call wrote, and what came in:
the results of what that call ran (and any message you sent meanwhile). So
what came in is the step less those two, shared evenly among what the call
before it ran; on 2,544 pairs of calls it was never less than zero, except
where the context shrank because it was compacted. Some older turns logged the
session's running total under the turn's own name (a Codex count fixed since);
their first call is told by the turn's total in the database. A call's time
runs from when all its results were back, or the turn began, to its first
command or tool, or to its end when it ran none. Claude reports a turn's
tokens once, at its end, so Claude turns have no calls here.

It prints the calls, what fills the context and what a call's time goes with.
`--save` keeps `context.json` in the lab's folder: one row per call with its
task, turn, start, number in its turn, the tokens sent and written, what came
in, the names of the command programs and tools whose results came in, and its
seconds, with the model and effort of its turn read-only from the database;
never a command's arguments, a path or what a tool returned. The dashboard's
Context page shows the typical context at each call of a turn by model; for
each program or tool the tokens it added and the input tokens it caused (what
comes in before a call is sent again at every later call of its turn, so the
biggest are the outputs worth trimming); and for each model with 30 timed
calls or more, a straight fit of a call's seconds into a fixed part, a part for
every 100,000 tokens sent and one for every 1,000 written, with the share of
the spread it explains and the share of the time each part takes.

## Memory

```sh
uv run memory.py
uv run memory.py --save
```

`memory.py` asks whether repository memory helps. ByteQuay gives a turn the
memories of its repository, and counts for each memory the turns it was given
to and the review findings that cite it. `memory.py` reads those records
read-only from the database: how often each memory was given and cited, the
characters a turn is given (sent again at every model call of the turn), and
how the review findings that cite a memory ended against those that cite
none. `--save` keeps `memory.json` with ids, kinds, counts, sizes and times,
never a memory's title or text; the dashboard's Memory page reads the titles
from the database only to show them. A memory given many times and never cited
costs every turn and helps none, so it is the one to rewrite or remove; the
page says when too few findings are decided to tell whether citing a memory
helps a finding hold up.

## Delivery

The dashboard's Delivery page shows what development tasks delivered for the
agent time, tokens and dollars they took, from the turn table
`uv run activity.py --save` keeps. A result is the agent's own report when a
development ends: the change is ready to deliver, or it is not. Why a result
was not ready is named by the words of the report, which `activity.py` reads in
memory and never keeps: no change asked (a question or research turn), waiting
for you (a choice or a review was yours), not committed, could not publish,
checks failed, or other; the turn table keeps only that name. A result that
asked for no change does not count. Every task with a result that counts is
here, under the model and effort most of its turns ran with;
a task whose turns the database no longer holds counts under "not known".
`delivery.py` adds them up for the last 7 days, 30 days or all of it.

Tiles: the development tasks, the ready results of all results, the agent
minutes per ready result, and the tasks never ready. For each model and
effort: its tasks, the share of results that said ready, and the agent
minutes, tokens and dollars per ready result. Minutes per ready count the
minutes of every task, those never ready too, so a setting that often misses
pays for it. A share and minutes per ready come with the range they could lie
in, drawn by task (see Ranges); with fewer than three tasks behind them there
is none. Tokens count every input token, cached ones too, and the output; a
task with a turn that records no tokens is left out of them, and dollars come
only from tasks whose every turn records a cost. "Never ready" lists the tasks
with results and none ready, the most agent time first, and "Why results were
not ready" counts the names. The tasks of one
setting are not those of another, so this shows what happened, not a fair
race; the Experiments compare settings on the same cases. A table saved
before results were kept asks to be saved again.

## Agent activity

`activity.py` shows where the wall-clock time of ByteQuay agent turns goes. It
reads the task event logs under
`~/Library/Application Support/ByteQuay/agent-runtime`, or `--runtime DIR`,
and changes nothing. Its tables come from the logs alone; only `--save` also
reads ByteQuay's database, as described below. It needs only the standard
library.

```sh
uv run activity.py
uv run activity.py --since YYYY-MM-DD
uv run activity.py --task START_OF_TASK_ID
```

Every second of a turn is in one of three states: the model is working and
nothing else runs, a tool or command is running, or the turn waits for the
user's approval or answer. The first table gives each state and its parts in
minutes and as a share of turn time, so the three states add up to 100%. When
several things run at once, a second is counted once, under the first running
group in a fixed order: the user, tests and builds, other shell commands,
reading and searching files, ByteQuay tools, subagents, context compaction,
other tools, then the model. A line under the tables says for how long two or
more tools ran at once.

"Shell time by program" names each command line after one program: a build or
test tool if the line runs one, else a wait loop if it sleeps, else the first
program that does real work. Only the first word of each command counts, after
`cd`, variable settings and a wrapping `zsh -lc` are removed, so
`src/main/java/` in an argument is not the `java` program. These times are
summed over runs; commands that overlap can add up to more than their share in
the first table. A script is "shell script" whatever it runs inside.

"Model time by what just finished" counts each stretch of model time under the
activity that ended right before it. The logs do not record when a model call
starts or when its first output arrives, so model time cannot be split into
reasoning and request time. Only visible text is told apart from silence.

"Calls and how they ended" counts every command and every tool call that
finished, with how many failed, and lists under each the three names that
failed most often. A command is named after its program as above, and a tool
by its name without its server. A search with `rg` or `grep` that ends with
exit code 1 found nothing: it is counted on a line of its own and not as
failed. A command line is named after one program, so a search behind another
program's pipe is not seen as one. A test run that fails counts as failed,
also when the test was meant to fail. ByteQuay's permission hooks and
automatic approvals and the provider's context compactions are not calls the
agent chose and are left out.

Start and end times are used, because many finished items carry no stored
duration. An item that never reports an end is left out and counted on its own
line, as are log lines in an old format. `--task` adds a timeline of that
task's stretches of 30 seconds or more. Only group names, program names,
counts and times are printed, never a command's arguments, a path or any
message text.

## Saving results

`report.py` and `activity.py` take `--save`. Besides printing, each then writes
its numbers as JSON. `report.py` writes `scores.json` into the experiment it
read. `activity.py` writes `turns.json` and `reviews.json` into
`~/Library/Application Support/ByteQuay Lab`, or into the folder named after
`--save`. The folder is the lab's own. A new result replaces the earlier one in
one step. While the monitor runs, it saves `turns.json`, `reviews.json` and
`tuning.json` every hour by itself. Nothing is saved when no run or turn was found, so an empty filter
cannot wipe a result.

```sh
uv run report.py ROOT --save
uv run activity.py --save
```

`scores.json` holds what the score tables print, with the id and the verdict
of every comment. `turns.json` is the
turn table: one row for every turn, with its task id, its start, how it ended,
the seconds of each part of its time, the programs it ran and what the model
reacted to. A row also lists every single command of 30 seconds or more by its
program, every wait for the user with its kind, how it ended and how long it
took, and how often the provider gave a rate limit notice or tried a call
again. For every program and every tool the turn called, the row has the
runs, how many failed, how many searches found nothing, and the seconds. For
a program it also has the characters its runs printed and why its failed runs
failed. The reason is read from the words a failed command printed: the first
that fits of sandbox or permission, no test matched, compile error, test
failure, build failed, missing file or path, network, and timeout or killed.
A failure that printed nothing the log kept is "no output kept", and one whose
words name none of these is "other". Only the name of the reason is saved, and
of the output only its length. For every call that had something before it in
its turn, the row also keeps the group of what the model last saw end or
start, the group of the call and the seconds between the two, added up by
kind of gap: in one answer (under half a second), a short hop (under ten
seconds) or a long step. And for every program, it keeps the runs of a command
line already run in the same folder of the same task, and how many of them had
nothing written in between: no file changed, and nothing ran that could
write, which is any command that does more than read, an editing tool or a
subagent. A wait loop run again is not counted. A turn that ended a
development keeps the agent's result: ready to deliver, or not ready. Its
message and what it learned are text and stay unread.
Both files hold names, counts and times and nothing else: no command
arguments, no paths and no message text.

For the turn table `activity.py` also reads ByteQuay's own database,
`bytequay-1.0.db` beside the runtime folder or `--database PATH`. `records.py`
opens it read-only and takes, for each turn, its model, effort, provider,
repository name, tokens, cost, tool calls, how it ended and the name of the
setup it ran with, and no title, prompt or other text. A database from before
ByteQuay named setups has none. A turn in both the log and the database gets these facts
in its row. A turn only in the log has none of them. A turn only in the
database gets a row without a time split, because its log is gone. When the
database is missing or has another shape, the table is saved from the logs
alone and says why.

`reviews.json` holds the reviews ByteQuay itself ran, from the same database
and under the same `--since` and `--task`: one row for every review run with
its repository, its start, its length, how it ended, whether ByteQuay had to
ask again for a complete report, and the model, effort, tokens and cost of
its own turns; and one row for every finding with the run that raised it,
how it stands (open, resolved or withdrawn) and its severity. A finding's
title, its text and the reason given for its state are never read.
`fixture/saved-reviews.json` is a small example.

ByteQuay stores on a Codex turn and on a Claude turn the tokens and the cost
of that turn alone, and `records.py` saves them as they are. For any other
provider the lab cannot tell what the stored amounts count and saves neither
tokens nor cost. A test reads the
application's source and fails when the columns the lab reads, or the way the
application counts tokens and cost, change. `fixture/saved-scores.json` and
`fixture/saved-turns.json` are small examples of the two shapes. The tests
write them, so an example is always exactly what the lab saves. Run the tests
once with `UPDATE_SAVED_EXAMPLES=1` after changing a shape on purpose.

## Dashboard

```sh
uv run dashboard.py
uv run dashboard.py FOLDER --port 8600 --runtime RUNTIME
```

`dashboard.py` shows the saved turn table as one page at
`http://127.0.0.1:8517`. Most tabs read the folder and nothing else. The
Suggestions and Approvals tabs also read the task logs, in this process
only, to match approval rules against command lines that are never shown or
saved. **Analyze now** at the top of Lab refreshes activity, reviews, tuning,
context and memory through `desktop.py`, and the page then shows them. Pick the days
in the side bar; days are in the reader's own time zone, while `--since`
counts in UTC. A model and effort and a repository can be picked there too,
and every tab follows except Suggestions, which picks its own days, and
Approvals, which follows the days only.

The Suggestions tab comes first. It lists what `suggest.py` suggests changing
in ByteQuay for the last 7, 14 or 28 days or every day, the most time each
could save first, each with the numbers seen, what to do, where in ByteQuay,
and the number to watch. "I made this change" notes the change in
`changes.json` under the suggestion's title, as `digest.py --note` does; from
then on the suggestion shows its number in the seven days before that day and
in the days since, within the same models and efforts, and says whether the
change helped, made it worse or is not clear yet. "Working on it" and "Not
wanted" (with a reason) mark a suggestion in `record.json` in the folder; one
not wanted leaves the list until "Bring back". "What became of them" lists
every suggestion marked or made: where it stands, the minutes a week it could
save when it was made and now, and for a change that was made, its number
before and after and what the change did. That check takes the suggestion as
it stood in the 14 days before the change, so a suggestion that no longer
shows because the change worked is still checked. The Approvals tab, built on `approvals.py`, shows what
was asked, the rules the asked commands need, and a test of rules picked from
that table or written by hand, with the asks they answer, the minutes and turn
endings they save, the risks among them, and how many of all commands run
they cover.

The Overview tab has the totals, a ring of the
three states, a ring of how the turns ended and the turn time of each day. The
Time tab has every part of the time as rings, bars for what the agent waited
for, shell time by program, model time by what just finished, the tasks
that took the most time, and the turn time of each repository. The Slow spots
tab has the long single commands by program with the longest ones listed,
how the waits for the user ended and how long an answer took, and the rate
limit notices and retries of each day. The logs say how often a notice or a
retry came, not how long it held a turn up. These three tabs are about turns
that have a log, and the numbers of the first two are those of the terminal
tables on the same turns.

The Tools tab is about the commands and tool calls the agent made, also on
turns that have a log. Its tiles give the calls, the share of commands and of
tool calls that failed, the searches that found nothing, and the time in
calls, where two calls that ran at once both count in full. "Worth a tool?"
draws one bubble for every program and tool with five calls or more: further
right it was called more often, higher it failed more often, and a bigger
bubble took more time. A big bubble high on the right is where a tool of its
own, or a rule, would pay first. The six that failed most often are named,
and the pointer names any other. A table lists every command and tool. "Why
commands failed" gives the failed commands by reason, with the programs each
reason hit most; the two reasons that say nothing, "no output kept" and
"other", are drawn in gray. "What commands hand back" gives the characters
commands printed by program, a rough stand-in for the tokens the model then
reads; what a tool call hands back is not in the logs. "Would merging or
caching pay?" gives the best case for each. Merging calls could save at most
the short hops, and only where the second call did not need the first one's
result. A cache could save at most the runs of a line already run with
nothing written in between, and only where the line gives the same answer
twice. The section also says how many calls the model already asked for in
one answer, draws the time before calls by kind of gap, lists the short hops
by what came before and the call made after, and draws the repeated runs by
program. "By
provider" gives each provider its commands and tool calls with how many
failed, and a grid of how often the most used commands failed under each
provider, where a cell with fewer than five runs says how few and has no
color. Different tasks ran under each provider, so this too shows what
happened and is not a fair test. A table saved before the lab counted what
this tab shows makes this tab alone say so, and every other tab is drawn as
before.

The Models tab is about turns the database knows. It lists every model and
effort with its turns, typical turn, typical tokens and tool calls, and how
often a turn completed, then draws the typical turn as a grid of model by
effort, the spread of turn length, the time split and the endings side by
side. "Typical" is the middle turn, so a few very long turns do not move it.
The typical turn and the completed share each come with a range (see Ranges).
A model and effort with fewer than five turns stays in the table and out of
the charts. Different tasks ran under each one, so the tab shows what
happened and is not a fair test of which is better.

The Cost tab is about tokens and dollars. Tokens come in three kinds: fresh
input, input read from the cache, and output. The tab shows them as a ring,
by model and effort, by day and for the tasks that used the most. Dollars are
shown only where they are known. A recorded cost is the Claude command line's
own figure at API prices, which on a subscription is not what was paid. For
turns without one, the tab estimates from the reader's own prices when the
folder holds a `prices.json`:

```json
{"version": 1, "dollarsPerMillionTokens": {"MODEL": {"input": 0, "cachedInput": 0, "output": 0}}}
```

The tab prints this file with every model that still has no cost, ready to
fill in. A model whose prices are all zero has no price yet. The lab ships no
prices of its own. The table gives each model and effort its tokens, its
dollars, how they are known, the typical dollars of a turn, and the dollars
of a completed turn, which also carries the turns that did not complete.

The Reviews tab has two halves. The first is about the reviews ByteQuay ran:
what became of their findings, how the runs ended, how many findings a run
raised, the findings of each severity, and a table by model and effort. A
finding is resolved when the user or a development agent marked it done, and
withdrawn when the reviewer took it back; "held up" is the resolved share of
those two, and an open finding is not decided yet. "Asked again" counts the
runs whose report was not complete, so ByteQuay asked the reviewer once more.
Different pull requests were reviewed under each model, so this too shows
what happened and is not a fair test. The tab says so when the reviews were
saved at another time than the turns. The second half is about experiments:
pick one, the one worked on last first, see how its runs stand with a button
to run or stop them, and see one bar per setting for what it found of a human
reviewer's comments, with the settings below. Then pick two
settings to set them side by side, comment by comment. Over several runs a
comment counts by how much of it was found, a part-find as half. The tab says
how often each did better, and how often chance alone gives a split as uneven
(the sign test), so a gap of two or three comments is not read as a winner.

The page is served to this machine only. Started any other way, for example
with `streamlit run dashboard.py`, Streamlit would serve it to the whole
network, so the page then shows one line saying how to start it and nothing
else. A saved file that is missing, cut off or in another shape is named as
such and never drawn as zeros.

ByteQuay's **Open Lab** first runs `desktop.py --folder FOLDER --runtime
RUNTIME --database DATABASE` to refresh all five snapshots, then starts
`dashboard.py` on an available loopback port. Concurrent opens share the work;
later opens refresh the data and reload the existing dashboard page. Refresh
failure leaves the previous snapshots and reports an error with Retry. Empty
datasets replace old snapshots. The managed dashboard exits on app quit or
when its parent disappears. Opening it does not install a LaunchAgent, start
resource sampling, or call a model. The standalone command still defaults to
port 8517 and supports a chosen `--port` for developers.

`charts.py` turns the table into pandas frames and Plotly figures and can be
tested without a page; `test_dashboard.py` runs the whole page without a
browser. The dashboard needs Streamlit, Plotly and pandas. Streamlit depends on
pyarrow, which has no build for Python 3.15 yet, so the project asks for a
Python below 3.15.

## Report

```sh
uv run digest.py
uv run digest.py --day
uv run digest.py --week --ending 2026-10-12
uv run digest.py --note "Narrower Maven commands in the review rules" --on 2026-10-14
```

`digest.py` writes a report from the saved tables: the week that ended on the
last whole day before the save, or one day with `--day`. It reads the folder
and nothing else, so save first with `activity.py --save`. The report is
printed and kept as a file of its own under `reports/` in the folder, so
earlier reports stay. The dashboard's Report page shows the same report for
the week or the day picked there.

The report has eight parts. "Worth a look" comes first: every number that got
clearly worse within the same models and efforts, against the days before or
since a change you noted. "What changed" sets each number of these days
against the same number of days before. How much was done counts every turn.
How the time was spent is known only for turns that still have a log, and the
report says how many those are. "Why it moved" first names the models and
efforts whose share of the turns moved by ten points or more. Then it takes
every total that moved by a quarter or more (agent time, turns, long
commands, questions, rate limit notices, tokens and cost) and names the two
models and efforts that moved it most, with their turns before and since. A
count that stays below ten on both sides gets no reason. For tokens and cost
it says how many turns on each side record them and which provider never
does, so a cost that grew because more turns record one is not read as work
that got dearer.

"Better or worse" sets the numbers that do not grow with the amount of work
against the days before: the typical turn, the share of turns completed, how
the time was spent, model time at the start of a turn and after a ByteQuay
tool, long commands and questions in an agent hour, tokens in a turn, and
review runs asked again. Its "Clear?" column says whether each moved beyond
chance (see Ranges), in the unit of the Change column. It compares like with
like: each model and effort with three tasks or more on both sides, weighed
by its turns, so a week that ran more turns on a slower model is not read as
ByteQuay getting slower. It says better or worse where one way is plainly
better (more turns completed; less waiting for you, model time, long
commands, questions, tokens and runs asked again), yes for a clear move of a
number such as the typical turn, and too few tasks when no model and effort
has three on both sides. With ten numbers, about one report in five flags one
as worse by chance alone, so read a single flag as a lead and the same flag in
the next report as a finding.

"What changed in ByteQuay" lists the setups that first ran these days, each
with the half of its name that no earlier setup had, and the commits that
reached the agent code, by where they landed (instructions and context, tools
and approvals, each lane, turns and tasks, reviews) with the latest of each.
Commits land nearly every day, so this says where to look when a number got
worse; it does not measure a cause. "Where the time went" ranks the model's time
by what had just finished, each kind of waiting for a tool, and waiting for
the reader, each with its share before, and for the model how often it
happened and how long each time took. "Models and effort" lists the settings
with the most turns. "Changes you noted" is for checking a change made to
ByteQuay: note what you changed with `--note`, and for four weeks each report
sets the days since that day against the seven before it, by the numbers of
"Better or worse", with the same Clear? column. The notes are kept in
`changes.json` in the folder and can be edited by hand.

A share moves in points and any other number by how much it grew. A number
that is not known is a dash and never a zero. The days before are measured
again from today's tables, so a log removed since then is missing from them.

## Approval rules

`approvals.py` reads the task logs and tests allow rules against the approval
cards ByteQuay showed you, for the dashboard's Approvals tab. Without rules it lists what was asked by provider
and kind, how long you waited, how many asks ran out, and how many of those
ended their turn: a Codex command that runs out stops its turn, while Claude
carries on. Then come the rules the asked commands would need, each with how
many asks need it and how many it answers by itself, and what Codex's own
reviewer answered without a card.

A rule has Claude's own shape, the one ByteQuay already writes for what a
review may not run: `Bash(words:*)` covers a command that starts with those
words, `Bash(words)` only that command. It matches the way Claude's rules do:
every command of a line, cut at `&&`, `||`, `;`, `|`, `&` and new lines, must
match a rule, and a prefix ends where a word ends. A line with `$(...)`,
backticks or `<(...)`, or one that starts by setting a variable, is never
covered, because it can run more than its words say.

With rules to test, the tab says how many command asks the rules answer, the
minutes of waiting and the turn endings they save, any ask you denied that
they would allow, the risks among the asks they answer, and how many of all
commands run in the logs they cover, which shows how wide they are. A risk is
a word in the line worth a closer look (writes to GitHub, network, deletes,
runs another command, writes a file, outside the sandbox), never a judgment
that a line is safe. Nothing is saved, and only providers, kinds, rule words,
counts and times are shown: a suggested rule keeps the program, a
subcommand and flags, and stops at a path, a value, a number or quoted text.

## Suggestions

`suggest.py` turns what the lab measured into a list of changes to make in
ByteQuay, shown in the dashboard's Suggestions tab. Each suggestion says what was seen, what to do, where in ByteQuay to
do it (a file and a name in it, or a place in the app), how many minutes a
week it could save at most, and the number to watch once the change is made.
The list is ranked by those minutes, and one that could save less than two
minutes a week is left out. It reads the saved tables and the approvals in the
task logs, and by default only the last 14 days up to the last saved one,
because an older leak may already be fixed: Codex's escalations, for example,
go to its own reviewer since 26 Sep 2026 and no longer show a card.

Each suggestion comes from a rule with its limits as named constants:

- a rule that would answer several approval asks of one provider by itself,
  with the risks of what it lets through;
- asks that ran out with no answer and ended their turn;
- a program that failed often for a reason the agent can avoid (no test
  matched, no output kept, the sandbox, a missing file or path, the network,
  a timeout), with how much more often under one provider than another; a
  failing test or a compile error is the work itself and suggests nothing;
- a first model step of a turn that takes long, turn after turn;
- short hops from one ByteQuay tool result to the next ByteQuay call;
- long single runs of one program;
- review runs ByteQuay had to ask again for a complete report.

The minutes are an upper bound, and what they count is said in each
suggestion. The list shows what happened, not a measured cause: check a change
by noting it, in the dashboard or with `digest.py --note`, and watching its
number. A test checks that every file a suggestion names is still in ByteQuay
and still holds the name it points to.

The number to watch comes with its range, and once a change is noted the
dashboard says whether the change helped, made it worse or is not clear yet,
comparing like with like (see Ranges). Every number a suggestion watches is
better lower.

Each suggestion also has a brief for a ByteQuay task: the same text, the places
to change, the number to watch, and a last line `Lab suggestion: <title>`. On
the page ByteQuay starts (Open Lab), **Fix it** opens that brief in the task
box of the watched repository `bytequay/bytequay`, where you read and send it.
While that task is open the suggestion says a task is on it. When the task is
completed, the dashboard notes the change on that day by itself, so its number
is checked as if you had pressed "I made this change". The lab finds the task
by the last line of its first message in the task logs, and keeps nothing else
of the message. A task that failed, or whose last line was edited away, notes
nothing. Started from the terminal, the page shows the brief to copy, and
"I made this change" stays for work done anywhere else.

## Ranges

`ranges.py` gives a number a 95% range: where it could lie had other tasks
come. It draws the tasks again, with replacement, 2,000 times with a fixed
seed, so the same table always gives the same range. Whole tasks are drawn and
not single turns, because the turns of one task share its code, its prompt and
its mistakes; drawn turn by turn, a task with forty alike turns would make the
range look far narrower than it is. Review runs are drawn by review for the
same reason. A share or a rate is a sum of parts over a sum of wholes, and a
typical value is the middle one. With fewer than three tasks there is no range.

A change is clear when its whole range lies on one side of zero. The tasks
before and since are drawn apart, never paired, because they are other tasks.
A comparison of like with like puts a number together from each model and
effort with three tasks or more on both sides, each weighed by its turns on
both sides together, and draws each one's tasks on their own. A count with no
event on a side gives no range, nor does a change in percent whose side before
is zero in more draws than the range leaves out: drawn again, such a side
would look known for sure.
Ranges appear in the Models and Reviews tables, beside each number to watch,
on a noted change in the Suggestions tab, and in the report's "Clear?" column.
The comparison of two scorer settings keeps its own sign test, and each scored
setting's share of comments found or partly found has a range drawn by case.

## Synthetic smoke fixture

`fixture/` is a deliberately small example for checking the harness and reading
its output. The contract permits using CSV rows after leaving the context, while
the changed implementation defers reading an owned file until iteration. It is
an exposed answer, not an unseen quality benchmark. Its result must never be
reported as holdout recall or precision.

Create a disposable Git repository from the two trees, commit `fixture/base/`
then `fixture/head/`, record both SHAs, and supply `fixture/context.md` to the
commands above. Keep the fixture README, `fixture/case.json` and this
explanation outside the exported input. No test or runtime mutation is needed
for discovery. `fixture/case.json` is a hand-written answer key with one
comment about the closed file; judging the fixture run against it and printing
the table should give one FOUND row. `fixture/unrelated-leads.json` holds two
leads about other code, to pass as `--leads` for the all-MISSED check. Verify
the tools without a provider call using:

```sh
uv run python -m unittest discover -p 'test_*.py'
```

## Production comparison and adjudication

For an actual quality claim, freeze a small calibration set and a separate unseen
holdout set before adjusting the policy. Use the existing frozen review/replay
path with the same repository rules, source, PR context, provider, model, budget
and tool access for both arms. Start fresh review tasks; do not reuse a conversation
that has seen the other arm or reference answers. Preserve each run's raw discovery,
validation, full supported pool, final selection and transcript. Do not reset a
user's live review database to replay a fixture.

Use the design's proposed pilot of three calibration and six holdout PRs with two
runs per arm. Freeze the proposed prompt before opening holdout outputs. Pool
candidates from both arms with an independent manual review, hide arm attribution,
and adjudicate issue families against the frozen source. Record for each family:
independent contract, reachable trigger, consequence or concrete removable cost,
source/control evidence, duplicate root cause, discovery phase, validation result,
and selection membership. Later maintainer comments are reference leads requiring
verification, not automatically correct ground truth.

Report initial discovery and cumulative discovery separately; include baseline
late nit checks and validation discoveries in cumulative recall. Separate discovery
recall, validation precision and selected-finding precision so a filter cannot hide
missed discovery. Give per-PR and pooled results, denominators, duplicate/unsupported
counts, timeouts, cost and elapsed time. Precision with zero selected findings is
undefined. Describe unjudged hypotheses and missing historical context explicitly.
Without an adjudicated holdout corpus, report execution feasibility and observed
hypotheses only; leave improvement claims unmeasured.
