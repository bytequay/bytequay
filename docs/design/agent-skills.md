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
PR and development Review & Learning cards use flexible default writing
targets: purpose usually 60–100 words, and commit explanations usually 50–90
words in short **Problem**, **Approach**, and **Result** paragraphs. Commit
cards explain the rationale, implementation, and resulting behavior using the
code or author context; inferred rationale is identified. Simple mechanical
commits can use shorter explanations. These ranges are neither minimums nor
hard caps. Combined result remains up to 30 words, and finding bodies or
private replies usually up to 60 words. Evidence uses a few short result bullets; limitations state
each distinct gap once. Required coverage, essential qualifications, and
explicit requests for detail take precedence over these targets. User writing
preferences apply inside structured fields, including generated refinement
and private replies in the selected task scope. These are generation rules;
stored text is neither truncated nor retroactively rewritten.

`AgentSkillService` always prepends this packaged resource when resolving an
instruction snapshot. It is not an `AgentSkill` row, is not stored in user
settings, and has no list/edit/toggle/remove API. The model is instructed to
apply the defaults silently, without announcing a mode or quoting policy.
The policies are packaged resources with no external content dependency.

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

`agent-skills/bytequay-review.md` makes executable proof the highest review
priority. For every suspected issue, opinion, or finding, the agent must run
an existing test that exercises the trigger or write and actually run a focused
reproduction whenever feasible, before reporting the claim. Findings keep a
short standalone body; their evidence references and explanations carry the
command, expected/actual result, decisive output, test path or reproduction
setup. A concrete execution blocker is
reported as a limitation; only independently established code defects may be
reported without a reproduced result. Speculation is omitted.

Review findings require an explicit Critical, High, or Medium severity by
default; Low requires a direct user request. Each full or incremental round
selects at most ten distinct findings, ranked by severity and impact before
saving. Discussions, retries, and report repair share that limit. Complete
commit coverage and testing limitations remain required. Report repair reuses
the investigation and collected evidence, with focused reproduction when
feasible proof is still missing before reporting a claim.

`ReviewPrompts` loads this private resource into the existing `ReviewBridge`
instruction. The bridge is available only to open review-owned tasks, so
review, discussion, and resumed/report-repair turns receive it through both
providers' system/developer channels. Development tasks, unrelated tasks, and
title/wording helpers do not receive this resource. It has no settings row or
user-management API. Tests and experiments use existing review worktree tools
and permissions. The severity and selection rules guide the model; the existing
reporting tools validate anchors and evidence identity, not semantic truth or
a deterministic severity/count filter.

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
