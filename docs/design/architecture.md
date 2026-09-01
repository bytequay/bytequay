# ByteQuay Architecture

ByteQuay is a local-first macOS desktop application. Its Electron and React
frontend talks to one Spring Boot sidecar on localhost. The sidecar integrates
with GitHub, stores durable local state in SQLite, and hosts the implemented
worktree-scoped agent service. A rich agent task UI over that service is
proposed in [Agent Task UI Design](agent-task-ui.md).

## 1. Architecture decision

The backend is a small six-module Maven reactor. Every listed module contains
implemented behavior; no empty extension or connector modules are reserved for
future work.

```text
backend/
├── pom.xml                         # Parent and reactor aggregator
├── core/
│   ├── pom.xml
│   └── src/                        # Shared domain values and storage ports
├── persistence-sqlite/
│   ├── pom.xml
│   └── src/                        # JPA entities, repositories, converters, stores
├── agent-spi/
│   ├── pom.xml
│   └── src/                        # Provider contract and shared provider values
├── agent-core/
│   ├── pom.xml
│   └── src/                        # Agent task and turn orchestration
├── agent-cli/
│   ├── pom.xml
│   └── src/                        # Codex, Claude, and process implementations
└── app/
    ├── pom.xml
    └── src/                        # Spring Boot composition root and executable
```

This is the smallest split that gives SQLite and agent providers real
boundaries without turning each package into a Maven module.

The module and provider-process boxes below exist today. The diagram as a
whole is the proposed rich-task flow from
[Agent Task UI Design](agent-task-ui.md): authenticated renderer transport,
child-thread multiplexing, rich normalized events, attachments, queueing, and
task controls are target behavior rather than shipped UI behavior.

```mermaid
flowchart LR
    subgraph DESKTOP["Electron desktop"]
        RENDERER["React renderer<br/>task projection"]
        MAIN["Electron main<br/>authenticated REST/SSE"]
        RENDERER <-->|"typed IPC"| MAIN
    end

    subgraph BACKEND["Spring sidecar"]
        APP["app<br/>HTTP/SSE and composition"]
        CORE["core<br/>shared domain and ports"]
        DB["persistence-sqlite<br/>JPA and SQLite adapter"]
        AGENT_CORE["agent-core<br/>tasks · turns · approvals · queue · events"]
        WORKTREES["app<br/>managed worktree operations"]
        AGENT_CLI["agent-cli<br/>provider adapters · owned-process supervisor"]
        AGENT_SPI["agent-spi<br/>capabilities and normalized events"]
        SQLITE[("SQLite")]

        APP --> CORE
        APP --> DB
        APP --> AGENT_CORE
        APP -->|"wires implementation"| WORKTREES
        APP --> AGENT_CLI
        AGENT_CORE -->|"WorktreeService port"| WORKTREES
        DB --> CORE
        DB --> AGENT_CORE
        DB --> SQLITE
        AGENT_CORE --> AGENT_SPI
        AGENT_CLI -->|"implements"| AGENT_SPI
    end

    subgraph CODEX["Codex runtime"]
        APP_SERVER["one long-lived<br/>Codex App Server"]
        THREADS["root and child threads<br/>agent loop · tool selection"]
        EXEC_SERVER["one task-owned exec-server per task<br/>stock process/filesystem tools · sandbox"]
        APP_SERVER --> THREADS
        THREADS -->|"selected environment · built-in tool calls"| EXEC_SERVER
    end

    CLAUDE["Claude CLI<br/>separate process per turn"]
    WORKTREE["ByteQuay-owned task worktree<br/>generated branch at exact commit"]
    GITHUB["GitHub API and gh CLI<br/>direct user actions only"]

    MAIN <-->|"localhost HTTP/SSE"| APP
    APP --> GITHUB
    AGENT_CLI <-->|"JSON-RPC over stdio"| APP_SERVER
    AGENT_CLI -->|"launch and own"| EXEC_SERVER
    AGENT_CLI <-->|"stream-json"| CLAUDE
    WORKTREES -->|"provision · inspect · cleanup"| WORKTREE
    EXEC_SERVER --> WORKTREE
    CLAUDE --> WORKTREE
```

## 2. Why SQLite is a dedicated module

The database adapter remains in the dedicated `persistence-sqlite` module.

The current SQLite package already contains a cohesive adapter:

- JPA entities and Spring Data repositories;
- JSON, collection, map, and time converters;
- pull-request, repository, watched-repository, view-state, detail, and
  credential stores;
- SQLite- and Hibernate-specific transactions; and
- credential-at-rest encryption used by the credential store.

The current agent service stores task, turn, and metric facts in the same
database; current pending approvals are process-local. The proposed rich task
surface makes approvals durable and adds input-delivery, queued-input, and
event-outbox facts defined by the service design. A task stores the immutable
ownership facts returned by `WorktreeService`; there is no separate worktree
allocation table or lifecycle. Keeping the durable implementations together
localizes Hibernate, Spring Data JPA, the SQLite driver, entity scanning,
schema ownership, and database integration tests.

The module is an outbound adapter, not a business-service layer. Controllers,
GitHub clients, task orchestration, provider protocols, and UI projections must
not move into it.

### 2.1 Dependency inversion

`persistence-sqlite` cannot depend on `app`, because `app` depends on the
persistence implementation at runtime. The extraction established this
direction by:

1. moving shared immutable domain values from `com.bytequay.app.domain` to
   `core`;
2. defining narrow storage ports in `core` for current GitHub/PR data;
3. keeping agent storage ports in `agent-core`;
4. making `persistence-sqlite` implement both sets of ports; and
5. making `app` depend on the ports and compose the SQLite implementations.

The result has no Maven cycle:

```text
app -> core
app -> persistence-sqlite
app -> agent-core
app -> agent-cli
persistence-sqlite -> core
persistence-sqlite -> agent-core
agent-core -> agent-spi
agent-cli -> agent-spi
```

Do not create a generic `Repository<T>`, a database-neutral entity model, or
a separate `database-spi` module. Storage ports belong beside the domain
behavior that uses them; SQLite implements those ports.

### 2.2 Persistence module contents

Suggested packages:

```text
com.bytequay.persistence.sqlite
├── config/                          # Entity and repository scanning
├── converter/                       # JPA column converters
├── credential/                      # Encrypted credential persistence
├── entity/                          # JPA entities
├── repository/                      # Spring Data repositories
└── store/                           # Domain-port implementations
```

`CredentialCipher` may move with the encrypted credential store because it
protects data at rest and uses no application behavior. It should remain a
concrete implementation, not gain a speculative cipher interface. GitHub CLI
token selection and credential policy remain in `app`.

The module should expose one explicit Spring configuration:

```java
@Configuration
@EntityScan(basePackageClasses = SqlitePersistenceMarker.class)
@EnableJpaRepositories(basePackageClasses = SqliteRepositoryMarker.class)
@ComponentScan(basePackageClasses = SqlitePersistenceMarker.class)
public class SqlitePersistenceConfiguration
{
}
```

`app` imports that configuration. It remains the only executable module and
the only place that chooses SQLite as ByteQuay's storage implementation.

### 2.3 Schema ownership

`persistence-sqlite` owns:

- JPA entity mappings;
- SQLite column representations;
- converters and repository queries;
- transactional store implementations; and
- database integration fixtures.

Hibernate continues to own schema creation and updates. The module does not add
Flyway or migration-history machinery until ByteQuay has a real released-data
migration contract.

The runtime database remains:

```text
~/Library/Application Support/ByteQuay/bytequay-1.0.db
```

Tests continue to use unique SQLite files under the system temporary
directory.

## 3. Module responsibilities

| Module | Owns | Must not own |
| --- | --- | --- |
| `core` | Shared immutable PR/repository values, credential storage values, and current-feature storage ports | Spring, JPA, SQLite, HTTP, provider protocols, or UI DTOs |
| `persistence-sqlite` | JPA entities, repositories, converters, encrypted credential storage, current-feature stores, and agent stores | Controllers, GitHub clients, task orchestration, or provider protocols |
| `agent-spi` | Driver/session/run contracts, lane IDs, capabilities, typed readiness, normalized provider events with opaque source identity, and usage samples | Spring, JPA, HTTP, SQLite, CLI process code, or provider SDKs |
| `agent-core` | Agent task/turn state, the shared worktree-service contract, mutation claims, event sequencing, approvals, input delivery, durable queued inputs, attachment references, metrics, completion, cleanup, and agent storage ports | Workspace path resolution, Git process implementation, Codex/Claude wire formats, JPA entities, or HTTP DTOs |
| `agent-cli` | Codex App Server client, experimental environment registration, task-owned Codex exec-server launch/handshake, Claude `stream-json` client, binary discovery, owned-process supervision, event mapping, and fixtures | Task tables, SSE, GitHub PR behavior, or database code |
| `app` | Spring composition, HTTP/SSE, GitHub clients, application services, managed workspace/worktree Git operations, schedulers, configuration, and executable packaging | Provider branching in controllers or direct JPA access from web code |

The frontend remains an npm project and does not become a Maven module.

## 4. Why there are no more modules

The current GitHub clients and pull-request services have one application
consumer and share the Spring Boot runtime. They stay in `app` until a second
consumer or independent release boundary exists.

Codex and Claude stay together in `agent-cli` because they share process
discovery, stream draining, cancellation, output limits, and fixtures. Split
them only if their dependencies, ownership, or release cadence diverge.

There is no parser module. Provider parsing is an implementation detail of
`agent-cli`. There is no connector framework: `AgentDriver` is the only
extension point currently justified.

## 5. Dependency and composition rules

1. Dependencies point toward framework-free contracts.
2. No module depends on `app`.
3. `app` is the only composition root and executable JAR.
4. `core`, `agent-core`, and `agent-spi` contain no Spring annotations.
5. `persistence-sqlite` never exposes JPA entities across its public ports.
6. `agent-cli` never writes SQLite or publishes SSE.
7. Phase one uses only provider-native tools under the selected task sandbox
   and approval policy. ByteQuay does not expose product actions as
   model-callable tools; GitHub writes remain behind direct product actions.
   The CodeGraph design may add only its two fixed,
   optional structural-exploration tools after their compatibility gate.
8. Java Platform Module System descriptors are unnecessary; Maven dependency
   direction is sufficient.

The current parent POM aggregates all six implemented children:

```xml
<packaging>pom</packaging>

<modules>
    <module>core</module>
    <module>agent-spi</module>
    <module>agent-core</module>
    <module>agent-cli</module>
    <module>persistence-sqlite</module>
    <module>app</module>
</modules>
```

Dependency and plugin versions belong in the parent. Each child declares only
the dependencies it uses.

## 6. System data flow

### 6.1 Pull-request flow

Display and refresh are separate paths:

1. A frontend display request for a stored PR ID reads detail, diff metadata, or stored file content
   through a `core` storage port and returns the local SQLite snapshot.
2. A direct repository-and-number detail request also reads SQLite first, but fetches that exact PR
   from GitHub on a local miss. This establishes a directly opened row or preserves GitHub's
   authoritative `404` when the PR does not exist.
3. `CredentialService` binds the resolved token to its GitHub user profile in one in-memory account
   record. Repeated profile, PR-sync, and CI-autofix reads reuse that record; changing the resolved
   token invalidates it and resolves `/user` once for the new credential.
4. Selecting a stored PR requests detail by its stable local row ID. The backend resolves the
   repository and number from SQLite, renders persisted detail first, then the frontend starts a
   non-overlapping six-second conditional refresh loop. An unchanged PR ETag refreshes only CI and
   mergeability and returns a status-only response; a changed PR ETag rebuilds and returns the full
   UI detail snapshot. Check runs and combined commit statuses carry their own stored ETags. An
   exhausted GitHub rate limit pauses the loop until GitHub's reported reset time.
5. The user-triggered detail refresh bypasses the PR ETag, rebuilds the complete local detail and
   default diff snapshots, and returns the full detail response.

The normalized repository name and PR number uniquely identify a pull request in SQLite. GitHub's
search and pull-request endpoints expose different numeric IDs for the same PR, so ingestion retains
the existing local row ID whenever either endpoint returns that repository-and-number pair.

Repository refresh discovers every pull request updated within 30 days without filtering by state,
plus older pull requests that remain open. Search results can add or update rows but their absence
never deletes a row. A known row omitted by the current search is reconciled through GitHub's exact
repository-and-number endpoint before retention runs. Closed rows expire only after both their close
time and last GitHub update are older than 30 days. The background job runs six minutes after its
previous refresh completes. New PRs, changed GitHub update timestamps, and missing local details
refresh the full aggregate immediately. An unchanged open PR updated within the previous six hours
refreshes only mergeability and CI when its stored status is at least six minutes old. Other unchanged
PRs are skipped, including every unchanged closed PR and older open PR.

Dashboard refresh requests for the same GitHub credential join an in-flight dashboard refresh and
reuse a successful dashboard snapshot for six minutes after it completes. A credential change waits
for older in-flight work and then starts a new scan. This keeps a manual refresh from queuing behind
scheduled repository work and then repeating the same GitHub discovery scan. The frontend keeps the
persisted list visible while a refresh is in progress, reloads the current search when it completes,
and reports sync failures in the list without replacing cached rows. The pull-request toolbar reuses
the existing dashboard and repository list scans, running the repository scan once for each currently
monitored repository. It persists only fields returned by those list APIs; detail-derived metrics and
diffs remain cached until the selected PR's focused polling or the scheduled full refresh updates
them. The global list combines personalized dashboard rows with current workspace projections.

Default PR diff files and commit metadata refresh with changed PR detail. Commit-range diffs and
full file bodies refresh only after the user requests that range or expands that file, and are
persisted before they are displayed.

GitHub writes remain behind direct comment, approve, failed-check rerun, or merge actions.

### 6.2 Issue flow

Issue display and refresh use the same local-first boundary:

1. Issue list, individual issue, activity, and type display requests read SQLite only.
2. `WorkspaceIssueRefreshService.refreshIssues` fetches and persists the open-issue projection;
   the scheduled job and the frontend refresh action call this same method.
3. `WorkspaceIssueRefreshService.refreshDetail` fetches one issue's activity and type data,
   persists one detail snapshot, and then rereads it for the explicit frontend refresh response.
4. Direct comment, close, type, assignee, and label writes update the affected local projection so
   later display requests still read SQLite. Closing removes the issue from the open-only projection.

### 6.3 Target rich-agent flow

The current Codex/Claude adapters and backend task/event services are the base.
The following is the proposed end-to-end flow after the documented contract
gaps are implemented:

1. The React renderer calls typed preload operations; Electron main adds the
   private desktop session credential and sends localhost HTTP requests.
2. `app` resolves an exact commit in the selected workspace and provisions one
   ByteQuay-owned generated branch/worktree through the shared synchronous
   `WorktreeService`. Only after the returned worktree is verified does it
   create or resume the worktree-scoped task through `agent-core`.
3. `agent-core` enforces lifecycle and queue rules and selects an
   `AgentDriver` through `agent-spi`.
4. The Codex adapter multiplexes the task's root and child thread IDs through
   one long-lived App Server, while each active Codex task has one separately
   supervised official `codex exec-server` selected through experimental
   `environment/add`. The Claude adapter remains a separate `stream-json`
   process per turn.
5. App Server owns the Codex model/tool loop, environment selection, built-in
   tool execution, and provider approval requests. The task exec-server applies
   the selected App Server sandbox and approval policy to the task's generated
   worktree.
   Each driver maps only user-visible provider facts to normalized ByteQuay
   events.
6. `agent-core` correlates task, agent thread, turn, and item identity. Each
   SQLite state change and matching event commit through one unit-of-work and
   outbox before JSONL/SSE publication. A task-local worktree-mutation claim serializes
   turns, queued starts, and completion; an explicitly queued input starts only
   after a successful terminal turn. The turn claim is released only after
   recursive child discovery, all observed execution-capable items are
   terminal, and App Server reports no background terminal for the root or
   children. Durable task lifecycle is only `OPEN -> COMPLETED | FAILED`;
   running, idle, closing, and recovery required are derived from turn state,
   mutation-claim ownership, and provider quiescence. A provider terminal,
   failed, cancelled, or interrupted status alone never proves tools stopped.
7. `persistence-sqlite` stores task, turn, input-delivery, queue, approval,
   owned-process, and metric facts, including the task's immutable worktree
   ownership values; detailed history and bounded attachment/output blobs
   remain in the task runtime directory.
8. `app` exposes replay and live events through one task SSE sequence.
   Electron main reconnects from the last sequence and forwards typed events
   to the renderer.
9. The renderer derives transcript grouping, folded activity prose, plans,
   icons, elapsed clocks, queue state, and root/child navigation without
   interpreting raw provider payloads.
10. Phase one stops at the stock App Server harness and task exec-server. It
    does not register ByteQuay actions as model-callable tools or add custom
    product-tool routing. The CodeGraph service is the only narrow
    exception: two fixed, fail-open structural tools after the base protocol,
    event streaming, approvals, and recovery paths work end to end.

The detailed backend contract is in
[Agent Service Design](agent-service.md); the proposed frontend behavior is in
[Agent Task UI Design](agent-task-ui.md).

## 7. Operational boundaries

- The backend listens only on `127.0.0.1:53123`.
- SQLite and runtime files stay under ByteQuay's application-support
  directory.
- There is one Spring application context and one SQLite database.
- Every agent task uses a separate worktree generated under its managed
  workspace by the shared service described in
  [Managed Worktree Service](worktree-service.md). The renderer never selects a
  repository or worktree path. Each provision, inspection, and cleanup call
  derives and revalidates the expected path, repository, branch, and commit.
  A half-finished or mismatched orphan is preserved and reported for manual
  inspection; startup does not run a worktree recovery state machine. Owned
  provider processes are still reconciled before agent endpoints become ready.
- Normal task completion requires a clean generated branch, removes the
  worktree without force, and always retains that branch as the local result.
  Worktree add/remove operations are serialized per managed repository; work
  inside different ready worktrees remains concurrent.
- Stock provider tool workloads run through the selected sandbox and approval
  policy. App Server and Claude retain the provider traffic their control loops
  require; endpoint-level OS filtering is not claimed by this first design.
  ByteQuay product-action tools are deferred. The optional two-tool CodeGraph
  boundary is specified separately and remains fail-open. Directly owned App
  Server, task exec-server, and Claude processes use durable launch
  intents/markers; normal per-turn quiescence uses typed item history and App
  Server's background-terminal APIs.
- The current executable proof starts the shared Codex App Server with an
  explicit no-child isolation baseline. Before creating a thread, ByteQuay must
  verify through
  `config/read` that the effective session flags disable every unapproved
  optional feature and web search, expose no MCP server, and apply the minimal
  shell environment without inherited setters, exclusions, or filters.
  `hooks/list` must be empty. `skills/list` may contain only bundled system
  skills canonically beneath the isolated `CODEX_HOME/skills/.system`, never a
  repository, user, admin, plugin-owned, or out-of-root skill. Because that
  baseline disables multi-agent, CP1 must separately prove the child-capable
  production profile and repeat these checks before CP1 completes or CP2
  begins. Skill discovery and the origin/error guard repeat immediately before
  every turn so an earlier workspace-write turn cannot introduce a later skill.
- Before a Codex turn, the driver reads App Server's effective config layers
  for every task cwd, requires the isolated user layer, rejects project config,
  and rejects every `.rules` file beside a returned system, user, or project
  policy-bearing source. `packagedDefaults` and
  `legacyManagedConfigTomlFromFile` layers are not exec-policy sources. It also
  requires `configRequirements/read` to report no applicable managed
  requirements. A
  missing `requirements` field, non-null value, request failure, or undecodable
  response fails closed. This security-specific response must contain exactly
  that one top-level key; an unknown sibling policy field also fails closed.
  The exact v0.151 executable gate proves the isolated keyless value is
  explicitly null; supporting a broader shape later requires new executable
  evidence.
  These are the first-release guards against inherited exec-policy allow rules;
  App Server v0.151 has no public per-thread rule-disable or effective-rule
  inventory.
- There is one Codex App Server process per ByteQuay backend runtime, not per
  task. Its loss interrupts live Codex turns; stored thread IDs remain
  resumable after restart and explicit retry. A replacement starts only after
  any prior owned process tree is verified stopped.
- Each active Codex task has one distinct, loopback-only official exec-server
  process and environment ID. It is re-launched and verified with
  `environment/info` before a stored thread resumes; no task silently falls
  back to App Server's default environment. The stock listener is
  unauthenticated and accepts multiple local clients, so its ephemeral port is
  an explicit same-host trust boundary rather than a credential. The v0.151.0
  reference API has no unregister request, so correctness does not depend on
  one: task close stops the executor while its unique registry entry remains
  disconnected until the shared App Server exits.
- The first release leaves the experimental, process-wide
  `--code-mode-host` option unset. It is not a per-task tool-routing API; any
  future custom Code Mode host needs a separately validated protocol design.
- App Server's command-level experimental maturity is an explicit product
  risk. The per-task environment and background-terminal APIs are also
  experimental. A fixed v0.151.0 reference fixture generates bindings with
  experimental definitions enabled, but it does not gate the production CLI.
  Production keeps Codex's startup self-update enabled and uses the
  user-managed installed version without treating any reported version text,
  including an unparseable string, as a readiness gate. A real protocol
  incompatibility blocks the affected operation and reports that detected version
  so the issue can be fixed.
- Maven module boundaries do not create processes or network boundaries.
- The packaged app starts only the `app` executable; library modules are
  ordinary JAR dependencies.
- Current Checkstyle, license, unit, SQLite integration, and Spring context
  checks run from the backend reactor.

## 8. Extraction sequence

The initial module extraction was completed in this order. Future structural
changes keep the same rule: mechanical moves remain separate from behavior.

1. Create `core`, move shared domain records, and introduce current-feature
   storage ports without changing behavior.
2. Create `persistence-sqlite`, move SQLite/JPA classes and
   `CredentialCipher`, and import its configuration from the existing app.
3. Move the remaining Spring Boot source into `app`; update `dev.sh`, CI,
   license paths, and packaging.
4. Add `agent-spi`, `agent-core`, and `agent-cli` with the first implemented
   provider behavior.

Every step must leave the backend reactor passing `mvn verify`. Do not mix
the repository moves with agent behavior.

## 9. Tradeoffs

The dedicated persistence module adds two costs: shared domain/storage ports
must move upstream first, and Spring scanning must be explicit. Those costs are
worth paying once agent data joins the existing PR data because the module
then owns one real technical boundary and one database.

Keeping all code in `app` would be simpler today, but agent storage would
increase coupling between JPA, provider execution, task lifecycle, and web
code. Splitting every feature into its own SPI and connector would move too far
in the opposite direction. The six-module design keeps the replaceable edges
isolated and leaves single-consumer application behavior together.
