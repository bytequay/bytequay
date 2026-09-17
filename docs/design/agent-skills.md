# Managed Agent Skills

ByteQuay owns system guidance and user-added prompt skills independently of
Codex, Claude, and any future API provider. Applicable system guidance is always
included and is absent from user-facing settings and skill APIs. Users manage
only their own added skills, choosing Global, Development, or Review scope.
Provider plugin commands, repository instruction
files, and global CLI configuration are not involved.

## Internal system guidance

`agent-skills/bytequay-system.md` contains concise communication rules,
a response check, and ByteQuay's review and output requirements. The rules
apply on every turn, including progress updates, within the requested format.
Required review coverage is preserved when grouping long lists.
Wording rules favor readable sentences and preserve exact technical details,
conditions, and normal prose in shared artifacts.
PR descriptions explain the problem, resulting behavior and design choices.
They never report local commands, test runs or counts, pass/fail results, build
or validation outcomes, logs, environment limitations or agent-work recaps.
This applies to creation and updates, including Testing, Validation and Test
plan template sections. Local execution details stay in the task conversation;
descriptions of test changes may explain the behavior covered.
PR and development Review & Learning cards default to short, complete sentences:
purpose usually takes 1–2 sentences, and each commit usually takes 1–3. Commit
cards explain the change, its key design or implementation choice, and why it
solves the problem using the code or author context; inferred rationale is
identified. A simple mechanical change can use one sentence. There is no minimum
word count or required Problem/Approach/Result template. Findings and private
replies usually use up to 60 words, with
replacement code excluded from that target. The report contains only intention
and commit explanations; it generates no combined-result, evidence/test,
coverage, limitations, or critical-issues cards. Evidence accompanies findings,
and machine coverage remains required. Concrete optional improvements use
inline findings with GitHub fenced `suggestion` replacements on the exact
current-side lines. Required coverage, essential qualifications, and
explicit requests for detail take precedence over these targets. User writing
preferences apply inside structured fields, including generated refinement
and private replies in the selected task scope. These are generation rules;
stored text is neither truncated nor retroactively rewritten.

`AgentSkillService` always prepends this packaged resource when resolving an
instruction snapshot. It is not an `AgentSkill` row, is not stored in user
settings, and has no list/edit/toggle/remove API. The model is instructed to
apply the defaults silently, without announcing a mode or quoting policy.
The policies are packaged resources with no external content dependency.
This concise default is ByteQuay-owned guidance, not an installed provider skill
or plugin; it remains active when the user skill collection is empty.

## Development guidance

`agent-skills/bytequay-development.md` defines ByteQuay's implementation rules.
Deliberate laziness means eliminating unnecessary implementation and maintenance
work: question the mechanism, choose the first sufficient solution, and stop
after required verification while delivering the complete requested outcome.
`AgentSkillService.instructions(AgentTask)` adds it only for the existing
development task identities:

| Agent session | Development guidance |
| --- | --- |
| Interactive worktree task | Included |
| CI Autofix, including its borrowed rebase task | Included |
| Cherry-pick conflict resolution | Included |
| PR review, discussion, or report repair | Excluded |
| Title generation and Better Words, including API requests | Excluded |

After excluding open review-owned tasks, the inclusion rule is positive: an
interactive task, `CI_AUTOFIX`, or `CHERRY_PICK` permission policy. Other
noninteractive tasks receive common guidance and Global user skills. The
no-argument resolver used by helpers never adds
development guidance, even when generating a title for a development task.
This uses existing task identities without adding a task-kind field or task mode.

Interactive tasks can also receive review or research requests. The policy
expressly applies only to development assignments and excludes review,
research, and helper assignments, including delegated ones. Native child
instruction inheritance is provider-owned; ByteQuay does not classify those
children or replace their prompts independently. The scope rule governs
inherited text rather than guaranteeing its absence from child context.

These policies remain internal and cannot be listed, toggled, edited, or removed
through user skill management. Development guidance is composed before enabled
user skills and grants no additional tools or permissions.

## Review guidance

`agent-skills/bytequay-review.md` guides understanding, inspection, validation,
and reporting through two turns in the existing review task. The first saves a
change map, assessed changed files, relevant context references, and candidate
hypotheses. The second challenges candidates against tests, source contracts,
callers, configuration, and counterevidence before saving findings.

Focused reproductions are required whenever feasible and useful. For a fix or
feature, passing the intended behavioral assertions at the head and failing
against the relevant old or narrowly removed behavior can establish that the
test detects the change. Compile/setup failures, unrelated mutations, and
formatter exit codes are not valid behavioral controls. Remaining defects must
be established in the frozen PR code. Direct source arguments, lifecycle and
concurrency analysis, compatibility checks, static analysis, measured behavior,
and version-matched documentation complement tests. An unrelated passing suite
or a test merely written is not proof. Restore only the agent's experiment edits
after capturing reproducible evidence.

Findings keep a short standalone body. Evidence retains the triggering input,
command, actual exit/result, decisive output, test/setup, control changes, and
validation explanation. Unsupported candidates are rejected with reasons;
zero findings remains valid. The same conversation's second pass is explicit
self-review, not independent judgment.

Defects require Critical, High, or Medium severity by default; Low requires a
user request. Optional concrete improvements are suggestions without severity.
At most ten distinct findings may be created per round, including retries and
discussions. The tool enforces the count and recording boundary, while semantic
truth and severity remain model judgments. Complete commit explanations and
truthful coverage remain required. The shared bounded reporting-repair budget
never turns known scope omissions into successful coverage.

`ReviewPrompts` loads this private resource through the existing `ReviewBridge`
for both providers, including discussion and resumed/report-repair turns.
Development tasks, unrelated tasks, and title/wording helpers do not receive
it. It has no settings row or user-management API. Experiments use the existing
review worktree permissions; publication remains a direct user action.

## User skill scopes

Ownership and scope are separate. All built-in system resources stay private;
Settings lists only the user's own records. Each user skill has one required
scope, independent of the provider and workspace:

| Scope | Included in |
| --- | --- |
| Global (`GLOBAL`) | Every task and helper request |
| Development (`DEVELOPMENT`) | Interactive development worktree tasks, CI Autofix, and cherry-pick conflict resolution |
| Review (`REVIEW`) | Open PR review tasks, including discussions and report repair |

`AgentSkillService` identifies Review through
`AgentReviewStore.findReviewByTask`, requiring an open review. This takes
precedence over the development predicate. A noninteractive AUTO task alone
does not identify a review. The no-argument resolver used by title generation
and Better Words selects Global, even when the helper belongs to a development
or review task. Native child inheritance retains the scope boundary described
above; ByteQuay does not classify arbitrary prompts or native children into
new task types.

Each instruction snapshot contains enabled Global user skills followed by
enabled skills for the selected task scope, preserving saved order within
each group. Development and Review user skills do not cross into one another.
Each prompt heading names its scope, and delegated assignments are instructed
to use only Global and matching scoped guidance.
An explicit current-scope and replacement notice instructs the agent to
discard historical skill settings after a scope edit, disable, or removal.
System resources remain active independently of the user
collection.

## Storage and management

`AgentSkillService` owns the ordered user skill collection in the existing
SQLite-backed `AppSettingsStore`, under `agent.skills`. Each skill contains a
stable ID, name, instruction text, scope, and enabled flag.
Settings exposes add, edit, enable/disable, and remove. Add/Edit includes a
Scope selector with Global, Development, and Review; new forms default to
Global. Rows display the selected scope, and toggling a skill preserves it.
Custom skills are
plain pasted instruction text. Adding a skill does not install packages,
execute hooks, grant tools, or fetch remote content.

The user collection starts empty. Reads return saved skills without modifying
the collection. Deleting its last entry saves an empty collection; internal
guidance remains active.

Names are limited to 100 characters, each instruction to 20,000 characters,
and the collection to 50 skills and 100,000 UTF-8 instruction bytes (Claude
receives the prompt in process arguments). Mutations
are serialized within the one local backend; invalid changes do not replace
saved settings. Corrupt saved data fails visibly instead of silently resetting
the user's collection.

The existing authenticated Electron bridge exposes these local endpoints:

| Operation | Endpoint | Result |
| --- | --- | --- |
| List | `GET /api/settings/agent-skills` | Ordered user skills only |
| Add | `POST /api/settings/agent-skills` | Updated collection |
| Edit or toggle | `PUT /api/settings/agent-skills/{id}` | Updated collection |
| Remove | `DELETE /api/settings/agent-skills/{id}` | Updated collection |

Add and edit accept name, instructions, scope, and enabled. Scope is required
and must be `GLOBAL`, `DEVELOPMENT`, or `REVIEW`; the Electron boundary and
backend validate it. No endpoint accepts system guidance as an editable
resource. No new database schema, plugin marketplace, workflow
lifecycle, or provider registry is needed.

## Instruction delivery

Agent core resolves a fresh immutable string at provider-session opening for
each ordinary, workflow-owned, queued, or resumed turn and for title helpers.
`AgentSessionContext.managedInstructions` is the provider-neutral contract.
Real turns receive a `Function<AgentTask, String>` resolver from application
wiring; title helpers retain the no-argument `Supplier<String>` resolver.
This is independent of the optional CodeGraph/review tool registration.

The existing Better Words helper resolves current Global skills for every
request, including its CLI session and OpenAI/DeepSeek API instructions. Its required
rewrite-only format remains part of the composed task instruction.

Codex composes system guidance and applicable enabled user skills with review/CodeGraph guidance in
`developerInstructions` on both `thread/start` and `thread/resume`, explicitly
replacing the previous value. Claude supplies the composition through
`--append-system-prompt` on each invocation. An explicitly empty adapter
input also clears adapter guidance; application resolution always supplies
the internal system guidance and a current user-skill list or explicit empty
notice to counter stale user preferences in conversation history.

Changing settings does not interrupt or steer an active turn. The next opened
session receives current settings, including scope edits and removals. Provider-owned child
agents remain subject to their harness's instruction inheritance; ByteQuay
does not add a separate child skill registry. A future API-backed agent driver
must deliver the same context through its system/developer instruction channel;
this change does not implement an API-backed driver.

The composition states that task-specific output formats, complete review
coverage, evidence, tool contracts, and permission/publication boundaries take
precedence. Writing preferences apply within structured prose fields. ByteQuay's
system guidance preserves complete commit coverage and supporting evidence for
the selected findings. Provider approval,
isolation, allowed tools, and native skill-discovery rules are unchanged.

## Verification

Regression checks cover always-applied system guidance with an empty user
collection, development-policy inclusion and review/helper exclusion across
provider lanes, complete review-policy delivery only through the review bridge,
captured command output cited alongside frozen source in a saved finding,
system text absent from skill responses, read-only retrieval of
saved user skills, scope selection and edits/toggles/removal across service
restart, invalid/corrupt settings, required-scope HTTP validation, frontend
scope editing and transport validation, task identity and fresh core resolution
after restart and before queued turns,
title helpers, and both provider adapters with enabled, replaced, and cleared
instructions on initial and resumed sessions. Provider launch fixtures verify
composition alongside existing workflow tools. These checks establish prompt
delivery, not a guarantee of model wording or provider child inheritance.

Full `mvn verify` passed with Checkstyle, license checks, and
Spring integration tests. Frontend lint,
TypeScript, the complete test suite, and macOS arm64 packaging passed. The final package
was rebuilt after backend verification and contains the same verified backend
JAR, with only the internal ByteQuay policy resources. Process-supervisor and Chromium tests
required normal process visibility outside the development tool sandbox.
