/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.AgentMetrics;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.agents.AgentContextCompiler;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnHooks;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.ai.ModelPricing;
import com.bytequay.app.service.local.ds4.Ds4Instrumentation;
import com.bytequay.app.service.local.ds4.Ds4LifecycleService;
import com.bytequay.app.service.local.ds4.Ds4State;
import com.bytequay.app.service.skills.ManagedSkill;
import com.bytequay.app.service.skills.ManagedSkillBundle;
import com.bytequay.app.service.threads.tools.AgentTool;
import com.bytequay.app.service.threads.tools.AgentToolContext;
import com.bytequay.app.service.threads.tools.LogicLoopToolRegistry;
import com.bytequay.app.service.threads.tools.ToolPermissionMediator;
import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.Gating;
import com.bytequay.app.service.workspaces.WorkspaceDocumentLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * In-JVM API-lane {@link ThreadAgent}. Drives the loop by calling the
 * resolved provider's HTTP API directly and translating streaming deltas
 * into the same {@link StreamEvent} shapes the CLI lane emits.
 *
 * <p>B5 multi-provider transport: Anthropic (SSE Messages API), OpenAI
 * (chat-completions SSE), and DeepSeek (OpenAI-compatible surface, cloud
 * and local ds4 variant) are all wired. Tools route through
 * {@link LogicLoopToolRegistry} on all three providers; the Anthropic path
 * uses {@code tool_use} content blocks while OpenAI/DeepSeek use the
 * {@code tool_calls} message role.
 *
 * <p>History replay limits to {@value REPLAY_HISTORY_LIMIT} recent text
 * messages per turn. Tool-call / tool-result rows from prior turns are
 * excluded — only the final assistant text survives across turn boundaries.
 *
 * <p>Mirrors {@link ClaudeCodeCliThreadAgent} on the contract that matters
 * for callers: same {@link ThreadStore#appendMessage} writes, same event
 * ordering (SessionStarted → AssistantTextDelta… → AssistantText →
 * UsageUpdated → TurnDone → SessionEnded), same lifecycle state machine
 * (IDLE/RUNNING/AWAITING/STOPPED), so the frontend renders trunk and task
 * panes identically across lanes.
 */
public class LogicLoopThreadAgent
        implements ThreadAgent
{
    private static final Logger log = LoggerFactory.getLogger(LogicLoopThreadAgent.class);

    // ── Anthropic ─────────────────────────────────────────────────────────
    private static final String ANTHROPIC_PROVIDER_ID = "anthropic";
    private static final String ANTHROPIC_MESSAGES_URL = "https://api.anthropic.com/v1/messages";

    // ── OpenAI ────────────────────────────────────────────────────────────
    private static final String OPENAI_PROVIDER_ID = "openai";
    private static final String OPENAI_COMPLETIONS_URL = "https://api.openai.com/v1/chat/completions";

    // ── DeepSeek ──────────────────────────────────────────────────────────
    private static final String DEEPSEEK_PROVIDER_ID = "deepseek";
    private static final String DEEPSEEK_COMPLETIONS_URL = "https://api.deepseek.com/chat/completions";
    /** Local ds4 model served by the Ds4LifecycleService subprocess. */
    /** Fallback local URL when no Ds4LifecycleService is wired (test
     *  paths). Production resolves the URL dynamically from the
     *  supervisor's status endpoint so a user-configured ds4.port
     *  flows through end-to-end.
     *  <p>Note the {@code /v1/} prefix — ds4-server's README pins
     *  the chat-completions endpoint at {@code POST /v1/chat/completions};
     *  hitting {@code /chat/completions} (without {@code /v1/}) was
     *  the bug that surfaced as the trunk thread flipping to ERRORED. */
    private static final String DEEPSEEK_LOCAL_COMPLETIONS_URL = "http://127.0.0.1:8000/v1/chat/completions";
    private static final String DEEPSEEK_LOCAL_MODEL_ID = "deepseek-v4-flash";
    /** Placeholder token the local ds4 server accepts; the real gate is
     *  "server running", not "key present". */
    private static final String DEEPSEEK_LOCAL_AUTH_TOKEN = "dsv4-local";

    private static final int MAX_OUTPUT_TOKENS = 4_096;
    /** History tail replayed to the provider on each turn. Bound by row
     *  count rather than token budget for simplicity in v1. */
    private static final int REPLAY_HISTORY_LIMIT = 80;
    /** Safety cap on the per-turn tool-use ↔ result loop. Reset on
     *  every {@code send()}. */
    private static final int MAX_TOOL_ITERATIONS = 12;

    // ── Fields ────────────────────────────────────────────────────────────

    private final String threadId;
    /** Task attribution for the current scheduler turn. A BRAIN_AGENT starts
     *  with its 1:1 parent task; stage agents receive the explicit turn task
     *  immediately before dispatch. */
    private volatile String activeTaskId;
    private final ThreadKind kind;
    private final ThreadStore store;
    /** Stage of the in-flight turn, set by the scheduler before each send;
     *  emitted messages inherit it as their explicit stage_id. */
    private volatile String activeStageId;
    /** AgentRun episode of the in-flight turn, when this turn belongs to one. */
    private volatile String activeAgentRunId;
    private volatile ManagedSkillBundle managedSkillBundle = ManagedSkillBundle.empty();
    private volatile List<ManagedSkill> activeManagedSkills = List.of();
    /** Null keeps the legacy role/kind filter; non-null is the scheduler's
     * bounded tool set, including an intentionally empty set. */
    private volatile Set<String> activeToolNames;
    private volatile Supplier<String> preTurnHook = () -> null;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final CredentialService credentialService;
    private final WorkModel resolvedModel;
    private final String workingDir;
    private final String branchName;
    private final String roleSkillText;
    private final String sessionId;
    private final long sessionStartedMs;
    private final HttpClient httpClient;
    /** Shared provider tool-round loop. The agent supplies wire-level
     *  config per turn; the runner owns the transport + round loop and
     *  calls back into this class for tool execution, events, and
     *  persistence. */
    private final TurnRunner turnRunner;
    /** Tools the model is told about and can call. Null on the
     *  legacy text-only constructor used by older test paths. */
    private final LogicLoopToolRegistry toolRegistry;
    /** Local ds4 supervisor — null when the agent is built without
     *  the local lane wired (e.g. unit tests). Used to (a) build the
     *  current local endpoint URL so a user-configured ds4.port is
     *  honoured, and (b) refuse to dispatch when the server isn't
     *  RUNNING instead of silently 404'ing. */
    private final Ds4LifecycleService ds4;
    /** Metrics ring the local-ds4 path records each round into so
     *  the Metrics tab reflects thread turns alongside review calls.
     *  Null on the legacy paths. */
    private final Ds4Instrumentation ds4Instrumentation;
    /** Coordinates Allow / Deny prompts with {@link ThreadAgent#decide}.
     *  Null when the agent is built outside the production wiring
     *  (legacy tests); the BridgedTool falls back to refusing gated
     *  calls when this is missing. */
    private final McpPermissionGate permissionGate;
    private final CopyOnWriteArrayList<Consumer<StreamEvent>> listeners = new CopyOnWriteArrayList<>();

    private final AtomicReference<ThreadStatus> status = new AtomicReference<>();
    /** Detail of the most recent fatal failure, surfaced via
     *  {@link #lastErrorDetail} so the scheduler can record the real cause
     *  on a turn that ended ERRORED without throwing. */
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> currentTurn = new AtomicReference<>();
    private final AtomicBoolean userInterrupted = new AtomicBoolean(false);
    private final AtomicLong nextSeq = new AtomicLong();
    /** Per-stage seq counters — a STAGE-scoped message uses its stage's own
     *  seq space (stage_messages), so concurrent per-stage agents don't
     *  collide on the thread-global (thread_id, seq). */
    private final ConcurrentHashMap<String, AtomicLong> stageNextSeq = new ConcurrentHashMap<>();
    private final AtomicLong runningTokensIn = new AtomicLong();
    private final AtomicLong runningTokensOut = new AtomicLong();
    private final AtomicLong runningCostUsdMilli = new AtomicLong();
    // Per-turn token overlay: rounds accumulate here as they complete so
    // the polled thread vitals climb mid-turn. Folded into the running
    // totals (and reset) when the turn finalises or errors, leaving the
    // committed total identical to summing the turn once at the end.
    private final AtomicLong liveTurnTokensIn = new AtomicLong();
    private final AtomicLong liveTurnTokensOut = new AtomicLong();
    private final AtomicReference<String> activeModel = new AtomicReference<>("");
    /** Per-tool auto-approval budget the user granted via "Allow next
     *  N". {@code -1} is the sentinel for "always for this tool" until
     *  the session ends. Lives only for the session — a stop /
     *  failed thread drops the map. */
    private final ConcurrentHashMap<String, Integer> toolBudget = new ConcurrentHashMap<>();
    /** Pending Allow / Deny prompts so {@link #stop} / {@link #interrupt}
     *  can cancel them rather than leak a waiting handler thread. */
    private final Set<String> pendingPermissions = ConcurrentHashMap.newKeySet();
    private static final int BUDGET_ALWAYS = -1;
    /** Default cap on how long we wait for a user Allow / Deny
     *  before treating the prompt as denied. Five minutes matches
     *  the CLI lane's MCP gate. */
    private static final Duration PERMISSION_WAIT_TIMEOUT = Duration.ofMinutes(5);

    /** Trunk turns are planning conversations — they read memory,
     *  inspect existing PRs / tasks, and spawn new tasks, but they
     *  don't execute the work themselves. Restricting the tool
     *  catalog to this allowlist on trunk turns cuts ~2-3 K input
     *  tokens per round (mostly schema bloat from publish + shell
     *  tools the trunk would never call). Task turns get the full
     *  catalog because they're the surface that actually executes.
     *
     *  <p>Tools that intentionally stay <em>off</em> the trunk:
     *  every {@code PublishToolHandlers} mutator (push, merge_pr,
     *  approve_pr, open_pr, post_comment, …), {@code run_shell},
     *  {@code run_checks}, and provider-driven skill discovery. Add a name here only
     *  after confirming the trunk can complete its planning loop without
     *  it. */
    // Package-private so the characterization test can pin the contract:
    // the trunk must be able to plan and queue, not just cut one task.
    static final Set<String> TRUNK_TOOL_ALLOWLIST = Set.of(
            "recall_memory",
            "lookup_memory",
            "read_workspace_memory",
            "recall_thread",
            "list_prs",
            "read_pr",
            "read_issue",
            "read_task",
            "read_current_repository",
            // The trunk reads files so it can plan against real source — and
            // so a pasted image (saved to a path it's handed) can be opened.
            "read_file",
            // Explicitly replace the stable planning snapshot mid-cycle.
            "sync_repo",
            "create_task",
            // Planning the queue is core trunk work — without these the
            // trunk can cut a task but never line one up behind the active
            // one, reorder the plan, or drop a stale entry.
            "queue_task",
            "reorder_queue",
            "drop_queued_task",
            // trunk-role.md already instructs the trunk extensively to ask
            // rather than assume — this was the missing mechanical half.
            "ask_user_question");

    /** The read-only tool surface the brain agent is allowed to call.
     *  Enforced the same way as {@link #TRUNK_TOOL_ALLOWLIST}: the rendered
     *  tool list is filtered to these names, so the brain agent never even
     *  sees a write tool. The handlers register in {@code LogicLoopToolRegistry}
     *  via {@code @AgentTool}; until they land the list renders empty. */
    public static final Set<String> BRAIN_TOOL_ALLOWLIST = Set.of(
            "count_operations",
            "read_commit_summary",
            "read_diff_summary",
            "check_test_coverage",
            "read_stage_metrics",
            "read_phase_history",
            "read_review_panel_findings",
            "read_remote_pr_status",
            "list_unresolved_comments",
            // The brain's one write tool — records the structured plan during
            // a PlanStage. The handler no-ops (errors) when no PlanStage is
            // open, so it's inert outside planning.
            "record_plan",
            // Lets the brain re-read the finalized plan it (or the trunk) recorded.
            "read_plan_summary",
            // Brain adversarial review (plan-rail-runs.md R20-R24): the plan
            // self-review turn and the two code lock-point review turns all
            // run on this same brain thread, so it needs the dev-context read
            // tools plus its own comment + verdict writers.
            "read_dev_report",
            "read_dev_conversation",
            "record_pr_comment",
            "record_review_verdict");

    /** System prompt for the read-only brain agent. A later change prepends
     *  a digest of the task's recent iteration summaries; this is the static
     *  role portion. */
    static final String BRAIN_SYSTEM_PROMPT = """
            You are the read-only brain agent for a developer task. You can \
            introspect the task's stages, iterations, phases, commits, and PR \
            state via the provided tools. You cannot edit files, run commands, \
            push, or post to GitHub. During a stage-scoped planning or \
            adversarial-review turn, you may use only the local planning/review \
            write tools that the turn explicitly asks for, such as record_plan, \
            record_pr_comment, or record_review_verdict.

            Answer the user's question concisely (target 6 sentences or fewer). \
            When you reference a stage or iteration, mention it by full name \
            (e.g. "CiFixingStage iteration #1"); the frontend renders these as \
            clickable chips.

            If you need precise data, call a tool. Don't speculate when you can \
            introspect.""";

    /** Mediator passed into every {@link AgentToolContext} so the
     *  bridged-CLI catalog can route through the permission gate.
     *  Lazy-stateless; one instance per agent. */
    private final ToolPermissionMediator permissionMediator = this::admitToolCall;

    public LogicLoopThreadAgent(
            Thread thread,
            ThreadStore store,
            ObjectMapper mapper,
            ExecutorService executor,
            CredentialService credentialService,
            WorkModel resolvedModel,
            String workingDir,
            String roleSkillText)
    {
        this(thread, store, mapper, executor, credentialService,
                resolvedModel, workingDir, roleSkillText, /* toolRegistry */ null,
                /* ds4 */ null, /* ds4Instrumentation */ null,
                /* permissionGate */ null);
    }

    public LogicLoopThreadAgent(
            Thread thread,
            ThreadStore store,
            ObjectMapper mapper,
            ExecutorService executor,
            CredentialService credentialService,
            WorkModel resolvedModel,
            String workingDir,
            String roleSkillText,
            LogicLoopToolRegistry toolRegistry)
    {
        this(thread, store, mapper, executor, credentialService,
                resolvedModel, workingDir, roleSkillText, toolRegistry,
                /* ds4 */ null, /* ds4Instrumentation */ null,
                /* permissionGate */ null);
    }

    public LogicLoopThreadAgent(
            Thread thread,
            ThreadStore store,
            ObjectMapper mapper,
            ExecutorService executor,
            CredentialService credentialService,
            WorkModel resolvedModel,
            String workingDir,
            String roleSkillText,
            LogicLoopToolRegistry toolRegistry,
            Ds4LifecycleService ds4,
            Ds4Instrumentation ds4Instrumentation)
    {
        this(thread, store, mapper, executor, credentialService,
                resolvedModel, workingDir, roleSkillText, toolRegistry,
                ds4, ds4Instrumentation, /* permissionGate */ null);
    }

    public LogicLoopThreadAgent(
            Thread thread,
            ThreadStore store,
            ObjectMapper mapper,
            ExecutorService executor,
            CredentialService credentialService,
            WorkModel resolvedModel,
            String workingDir,
            String roleSkillText,
            LogicLoopToolRegistry toolRegistry,
            Ds4LifecycleService ds4,
            Ds4Instrumentation ds4Instrumentation,
            McpPermissionGate permissionGate)
    {
        this.threadId = thread.id();
        this.activeTaskId = thread.parentTaskId();
        this.kind = thread.kind();
        this.store = requireNonNull(store, "store is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.resolvedModel = requireNonNull(resolvedModel, "resolvedModel is null");
        this.workingDir = workingDir;
        // A LOGIC_LOOP / brain thread is read-only — it owns no dev branch.
        this.branchName = null;
        this.roleSkillText = roleSkillText;
        this.sessionId = thread.agentSessionId() == null
                ? "logic-loop-" + UUID.randomUUID()
                : thread.agentSessionId();
        this.status.set(thread.status() == null ? ThreadStatus.IDLE : thread.status());
        this.sessionStartedMs = System.currentTimeMillis();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.turnRunner = new TurnRunner(httpClient, mapper);
        this.runningTokensIn.set(thread.tokensIn());
        this.runningTokensOut.set(thread.tokensOut());
        this.runningCostUsdMilli.set(thread.costUsdMilli());
        this.activeModel.set(thread.model() == null ? "" : thread.model());
        this.toolRegistry = toolRegistry;
        this.ds4 = ds4;
        this.ds4Instrumentation = ds4Instrumentation;
        this.permissionGate = permissionGate;
        store.maxMessageSeq(threadId).ifPresent(max -> nextSeq.set(max + 1));
    }

    // ── ThreadAgent interface ─────────────────────────────────────────────

    @Override
    public String id()
    {
        return sessionId;
    }

    @Override
    public ThreadKind kind()
    {
        return kind;
    }

    @Override
    public String provider()
    {
        return resolvedModel.agentOrProvider();
    }

    @Override
    public String model()
    {
        String live = activeModel.get();
        if (live != null && !live.isEmpty()) {
            return live;
        }
        return resolvedModel.model();
    }

    @Override
    public String workingDir()
    {
        return workingDir;
    }

    @Override
    public String branchName()
    {
        return branchName;
    }

    @Override
    public ThreadStatus status()
    {
        return status.get();
    }

    @Override
    public String lastErrorDetail()
    {
        return lastError.get();
    }

    @Override
    public AgentMetrics metrics()
    {
        long runtime = Math.max(0L, System.currentTimeMillis() - sessionStartedMs);
        return new AgentMetrics(
                runtime,
                runningCostUsdMilli.get(),
                runningTokensIn.get(),
                runningTokensOut.get(),
                /* toolCallCount */ 0,
                /* filesTouched */ 0);
    }

    @Override
    public List<ThreadMessage> history()
    {
        return store.listMessages(threadId);
    }

    @Override
    public CompletionStage<Void> send(String userInput)
    {
        ThreadStatus current = status.get();
        if (current == ThreadStatus.RUNNING) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            done.completeExceptionally(new IllegalStateException(
                    "a turn is already in flight"));
            return done;
        }
        if (current == ThreadStatus.COMPLETED
                || current == ThreadStatus.ARCHIVED
                || current == ThreadStatus.ERRORED) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            done.complete(null);
            return done;
        }
        userInterrupted.set(false);
        lastError.set(null);
        status.set(ThreadStatus.RUNNING);
        // Persist RUNNING synchronously — a turn can run many autonomous
        // steps over several minutes, and the thread row is the only signal
        // GET /api/threads/{id} (and so the sidebar dot + the trunk's
        // Working banner) has. Without this, the row sits at its pre-turn
        // status for the whole turn and only gets rewritten once, back to
        // IDLE, when the turn finishes — the UI shows nothing is happening
        // until some unrelated write (like the user's own next message)
        // happens to persist a fresher snapshot.
        persistThreadProgress();
        CompletableFuture<Void> turn = CompletableFuture.runAsync(
                () -> runTurn(userInput), executor);
        currentTurn.set(turn);
        return turn;
    }

    // ── Turn dispatch ─────────────────────────────────────────────────────

    private void runTurn(String userInput)
    {
        String note = null;
        try {
            note = preTurnHook.get();
        }
        catch (RuntimeException e) {
            log.warn("Pre-turn hook for thread {} failed: {}", threadId, e.getMessage());
        }
        Instant now = Instant.now();
        liveTurnTokensIn.set(0);
        liveTurnTokensOut.set(0);
        // Pasted images ride inside userInput as a MessageAttachments
        // envelope (see its doc) — decode once here. Unlike the CLI agents,
        // this transport builds the outgoing request JSON directly, so it
        // inlines real multimodal image content blocks instead of a
        // file-path pointer (see buildMessageHistory/buildOpenAiMessages).
        MessageAttachments.Decoded decoded = MessageAttachments.decode(mapper, userInput);
        persistUserMessage(decoded.text(), now, decoded.images());
        publish(new StreamEvent.UserMessage(now, decoded.text(), decoded.images()));
        publish(new StreamEvent.SessionStarted(now, sessionId, workingDir, model()));
        // A pre-turn note (e.g. "planning base moved") reaches the model
        // input for this turn only; the persisted user row stays clean.
        String modelInput = note == null || note.isBlank()
                ? decoded.text()
                : note + "\n\n" + decoded.text();

        try {
            String provider = resolvedModel.agentOrProvider();
            if (ANTHROPIC_PROVIDER_ID.equalsIgnoreCase(provider)) {
                runAnthropicTurn(modelInput, decoded.images(), now);
            }
            else if (OPENAI_PROVIDER_ID.equalsIgnoreCase(provider)
                    || DEEPSEEK_PROVIDER_ID.equalsIgnoreCase(provider)) {
                runOpenAiCompatibleTurn(modelInput, decoded.images(), now);
            }
            else {
                emitFatal("Provider '" + provider + "' is not supported by the API lane. "
                        + "Supported providers: anthropic, openai, deepseek.", now);
            }
        }
        catch (UnsupportedProviderException e) {
            emitFatal(e.getMessage(), now);
        }
        catch (RuntimeException e) {
            if (userInterrupted.get()) {
                publish(new StreamEvent.SessionEnded(Instant.now(), 0, null));
                status.set(ThreadStatus.IDLE);
                return;
            }
            log.warn("LogicLoopThreadAgent turn failed for thread {}: {}", threadId, e.getMessage());
            emitFatal(e.getMessage() == null ? "Provider call failed" : e.getMessage(), now);
        }
        finally {
            currentTurn.set(null);
        }
    }

    /** Run a lightweight lifecycle check before each API-lane turn. Trunk
     * sessions use this to sync the planning worktree to the latest fetched
     * base; a non-blank return value is prepended to this turn's model
     * input (the persisted transcript stays clean). */
    public void setPreTurnHook(Supplier<String> hook)
    {
        this.preTurnHook = hook == null ? () -> null : hook;
    }

    // ── Anthropic transport ───────────────────────────────────────────────

    private void runAnthropicTurn(String userInput, List<String> images, Instant turnStart)
    {
        if (resolvedModel.kind() != WorkModelKind.API) {
            throw new UnsupportedProviderException(
                    "LogicLoopThreadAgent expected an API work model but got "
                            + resolvedModel.kind());
        }
        String account = resolvedModel.account();
        Optional<String> apiKey = account == null || account.isBlank()
                ? credentialService.getSecret(CredentialType.AI, ANTHROPIC_PROVIDER_ID)
                : credentialService.getSecret(CredentialType.AI, ANTHROPIC_PROVIDER_ID, account);
        String key = apiKey.orElseThrow(() -> new IllegalStateException(
                "No Anthropic API key on file" + (account == null ? "" : " for account " + account)
                        + ". Add one in Settings → Credentials."));
        String modelId = resolvedModel.model() == null || resolvedModel.model().isBlank()
                ? "claude-sonnet-4-6"
                : resolvedModel.model();
        activeModel.set(modelId);

        String system = composeSystemPrompt();
        ArrayNode messages = buildMessageHistory(userInput, images);
        // Trunk/brain turns get narrow allowlists; ordinary task turns get the
        // task-role catalog, still filtered by thread kind.
        Set<String> toolFilter = toolNameFilter();
        ArrayNode toolsArray = toolRegistry == null
                ? null
                : toolRegistry.renderAsAnthropicTools(mapper, toolFilter, activeAgentRole(), kind);

        TurnResult result = turnRunner.runTurn(
                new TurnSpec(
                        TurnSpec.Transport.ANTHROPIC, ANTHROPIC_MESSAGES_URL, key, modelId,
                        system, messages, toolsArray,
                        MAX_OUTPUT_TOKENS, MAX_TOOL_ITERATIONS),
                this::executeLoopTool,
                loopHooks(/* isLocalDs4 */ false));
        if (result.end() == TurnResult.End.INTERRUPTED) {
            return;
        }
        finalizeTurn(modelId, turnStart, renderFinalText(result),
                result.tokensIn(), result.tokensOut());
    }

    /** Never persist a silent blank: when the turn produced no final text
     *  (the wrap-up round still came back empty, or the model returned
     *  nothing), surface a clear message instead of an empty bubble. */
    private static String renderFinalText(TurnResult result)
    {
        String text = result.finalText();
        if (text != null && !text.isBlank()) {
            return text;
        }
        if (result.end() == TurnResult.End.MAX_STEPS) {
            return "I hit the tool-step limit before I could finish gathering everything for "
                    + "this. Try narrowing it (e.g. one repo) and I'll answer directly.";
        }
        return "The model returned an empty response. Try rephrasing the request.";
    }

    // ── OpenAI-compatible transport (OpenAI + DeepSeek) ───────────────────

    private void runOpenAiCompatibleTurn(String userInput, List<String> images, Instant turnStart)
    {
        if (resolvedModel.kind() != WorkModelKind.API) {
            throw new UnsupportedProviderException(
                    "LogicLoopThreadAgent expected an API work model but got "
                            + resolvedModel.kind());
        }
        String provider = resolvedModel.agentOrProvider();
        String modelId = resolvedModel.model() == null || resolvedModel.model().isBlank()
                ? defaultModelForProvider(provider)
                : resolvedModel.model();
        activeModel.set(modelId);

        String url;
        String token;

        if (DEEPSEEK_PROVIDER_ID.equalsIgnoreCase(provider)) {
            if (DEEPSEEK_LOCAL_MODEL_ID.equals(modelId)) {
                url = resolveLocalDs4Url();
                token = DEEPSEEK_LOCAL_AUTH_TOKEN;
            }
            else {
                url = DEEPSEEK_COMPLETIONS_URL;
                token = credentialService.getSecret(CredentialType.AI, DEEPSEEK_PROVIDER_ID)
                        .orElseThrow(() -> new IllegalStateException(
                                "No DeepSeek API key on file. Add one in Settings → Credentials."));
            }
        }
        else {
            // openai
            url = OPENAI_COMPLETIONS_URL;
            token = credentialService.getSecret(CredentialType.AI, OPENAI_PROVIDER_ID)
                    .orElseThrow(() -> new IllegalStateException(
                            "No OpenAI API key on file. Add one in Settings → Credentials."));
        }

        String system = composeSystemPrompt();
        ArrayNode messages = buildOpenAiMessages(system, userInput, images);
        Set<String> toolFilter = toolNameFilter();
        ArrayNode toolsArray = toolRegistry == null
                ? null
                : toolRegistry.renderAsOpenAiTools(mapper, toolFilter, activeAgentRole(), kind);

        boolean isLocalDs4 = DEEPSEEK_PROVIDER_ID.equalsIgnoreCase(provider)
                && DEEPSEEK_LOCAL_MODEL_ID.equals(modelId);

        TurnResult result = turnRunner.runTurn(
                new TurnSpec(
                        TurnSpec.Transport.OPENAI_COMPAT, url, token, modelId,
                        /* system */ null, messages, toolsArray,
                        MAX_OUTPUT_TOKENS, MAX_TOOL_ITERATIONS),
                this::executeLoopTool,
                loopHooks(isLocalDs4));
        if (result.end() == TurnResult.End.INTERRUPTED) {
            return;
        }
        finalizeTurn(modelId, turnStart, renderFinalText(result),
                result.tokensIn(), result.tokensOut());
    }

    /** Stream-event + persistence wiring for one runner-driven turn.
     *  The runner owns the wire round loop; everything this agent
     *  must observe (deltas, usage, tool rows, ds4 metrics,
     *  interruption) rides these hooks in the same order the loop
     *  produced them before the extraction. */
    private TurnHooks loopHooks(boolean isLocalDs4)
    {
        return new TurnHooks()
        {
            @Override
            public void onTextDelta(int blockIndex, String chunk)
            {
                publish(new StreamEvent.AssistantTextDelta(Instant.now(), blockIndex, chunk));
            }

            @Override
            public void onUsage(long tokensIn, long tokensOut)
            {
                publish(new StreamEvent.UsageUpdated(Instant.now(), tokensIn, tokensOut));
            }

            @Override
            public void onToolCallStarted(String callId, String toolName, String inputJson)
            {
                publish(new StreamEvent.ToolCallStarted(Instant.now(), callId, toolName, inputJson));
                persistToolCall(callId, toolName, inputJson);
            }

            @Override
            public void onToolCallDone(String callId, String resultText, boolean isError)
            {
                String outputJson;
                try {
                    outputJson = mapper.writeValueAsString(
                            mapper.createObjectNode().put("text", resultText));
                }
                catch (Exception e) {
                    outputJson = "{\"text\":\"\"}";
                }
                publish(new StreamEvent.ToolCallDone(Instant.now(), callId, outputJson, isError));
                persistToolResult(callId, resultText, isError);
            }

            @Override
            public void onRoundCompleted(long tokensIn, long tokensOut, long elapsedNanos)
            {
                recordLocalDs4Sample(isLocalDs4, tokensIn, tokensOut, elapsedNanos);
                // Fold the round's tokens into the live overlay and flush so
                // the thread vitals grow during the turn rather than jumping
                // only at turn end.
                liveTurnTokensIn.addAndGet(tokensIn);
                liveTurnTokensOut.addAndGet(tokensOut);
                persistThreadProgress();
            }

            @Override
            public boolean interrupted()
            {
                return userInterrupted.get();
            }
        };
    }

    /** {@link ToolExecutor} body for runner-driven turns — registry
     *  dispatch with the same context + permission mediation the loop
     *  always had. */
    private ToolExecutor.ToolCallResult executeLoopTool(ToolCall call)
    {
        AgentTool.Result result = invokeTool(call.name(), call.input());
        return new ToolExecutor.ToolCallResult(result.text(), result.isError());
    }

    /** Build the local ds4 chat-completions URL from the supervisor's
     *  live status so a user-configured ds4.port is honoured. Pre-
     *  validates the server is RUNNING; turning a 404 from a stopped
     *  server into a clear "open Settings → Local AI (ds4) to Start
     *  it" message saves the user from chasing an opaque ERRORED. */
    private String resolveLocalDs4Url()
    {
        if (ds4 == null) {
            return DEEPSEEK_LOCAL_COMPLETIONS_URL;
        }
        Ds4State state = ds4.status().state();
        if (state != Ds4State.RUNNING) {
            throw new IllegalStateException(
                    "Local ds4 server is " + state + "; open Settings → Local AI (ds4) "
                            + "to Start it (or pick a different work model).");
        }
        String endpoint = ds4.status().endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return DEEPSEEK_LOCAL_COMPLETIONS_URL;
        }
        return endpoint + "/v1/chat/completions";
    }

    /** Record one completed local-ds4 round into the metrics ring so
     *  the Metrics tab reflects thread turns alongside review calls.
     *  No-op when the round wasn't local-ds4 or the instrumentation
     *  isn't wired (test paths). */
    private void recordLocalDs4Sample(boolean isLocalDs4, long tokensIn, long tokensOut, long elapsedNanos)
    {
        if (!isLocalDs4 || ds4Instrumentation == null) {
            return;
        }
        long elapsedMs = Math.max(1L, elapsedNanos / 1_000_000L);
        double tps = tokensOut == 0 ? 0.0 : (tokensOut * 1000.0) / elapsedMs;
        String caller = activeTaskId() == null ? "trunk" : "task";
        ds4Instrumentation.record(Ds4Instrumentation.Sample.of(
                caller, "/v1/chat/completions",
                tokensIn, tokensOut, tps, elapsedMs, "200"));
    }

    private JsonNode parseToolInput(String raw)
    {
        if (raw == null || raw.isEmpty()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(raw);
        }
        catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private AgentTool.Result invokeTool(String name, JsonNode input)
    {
        if (toolRegistry == null) {
            return AgentTool.Result.error(
                    "No tool registry wired for this session — text-only mode.");
        }
        Set<String> allowedNames = toolNameFilter();
        if (allowedNames != null && !allowedNames.contains(name)) {
            return AgentTool.Result.error(
                    "Tool '" + name + "' is not available to this turn.");
        }
        Optional<AgentTool> tool = toolRegistry.find(name);
        if (tool.isEmpty()) {
            return AgentTool.Result.error(
                    "Unknown tool: " + name + ". Available: " + toolRegistry.list().stream()
                            .map(AgentTool::name).toList());
        }
        Path cwd = workingDir == null ? null : Path.of(workingDir);
        String taskId = activeTaskId();
        try {
            return tool.get().invoke(input, new AgentToolContext(
                    threadId, taskId, cwd, permissionMediator,
                    activeStageId, activeAgentRunId, kind));
        }
        catch (RuntimeException e) {
            log.warn("Tool {} threw on thread {}: {}", name, threadId, e.getMessage());
            return AgentTool.Result.error("Tool '" + name + "' failed: " + e.getMessage());
        }
    }

    // ── Persistence helpers ───────────────────────────────────────────────

    private void persistToolCall(String callId, String toolName, String inputJson)
    {
        long seq = nextSeq.getAndIncrement();
        ObjectNode body = mapper.createObjectNode();
        body.put("callId", callId);
        body.put("toolName", toolName);
        body.set("input", parseToolInput(inputJson));
        String contentJson;
        try {
            contentJson = mapper.writeValueAsString(body);
        }
        catch (Exception e) {
            contentJson = "{}";
        }
        appendStamped(new ThreadMessage(
                UUID.randomUUID().toString(), threadId, activeTaskId(), seq,
                "tool", "tool_call", contentJson,
                null, null, null, null, Instant.now()));
    }

    private void persistToolResult(String callId, String text, boolean isError)
    {
        long seq = nextSeq.getAndIncrement();
        ObjectNode body = mapper.createObjectNode();
        body.put("callId", callId);
        body.put("text", text == null ? "" : text);
        body.put("isError", isError);
        String contentJson;
        try {
            contentJson = mapper.writeValueAsString(body);
        }
        catch (Exception e) {
            contentJson = "{}";
        }
        appendStamped(new ThreadMessage(
                UUID.randomUUID().toString(), threadId, activeTaskId(), seq,
                "tool", "tool_result", contentJson,
                null, null, null, null, Instant.now()));
    }

    // ── Inner types ───────────────────────────────────────────────────────

    // ── Prompt / history builders ─────────────────────────────────────────

    private String composeSystemPrompt()
    {
        String role;
        if (kind == ThreadKind.BRAIN_AGENT) {
            // The registry composes the brain prompt (role template + context
            // digest) and passes it as roleSkillText at session creation; fall
            // back to the bare template if it wasn't supplied.
            role = roleSkillText == null || roleSkillText.isBlank()
                    ? BRAIN_SYSTEM_PROMPT
                    : roleSkillText;
        }
        else {
            role = roleSkillText;
        }
        RoleAndKnowledge separated = splitRoleAndKnowledge(role);
        String compiled = AgentContextCompiler.compilePrompt(
                separated.role(),
                WorkspaceDocumentLoader.load(workingDir),
                separated.knowledge(),
                activeManagedSkills).systemPrompt();
        return compiled.isBlank() ? null : compiled;
    }

    private static RoleAndKnowledge splitRoleAndKnowledge(String prompt)
    {
        String marker = "\n\n# Workspace memory and knowledge\n\n";
        if (prompt == null) {
            return new RoleAndKnowledge(null, null);
        }
        int split = prompt.indexOf(marker);
        if (split < 0) {
            return new RoleAndKnowledge(prompt, null);
        }
        return new RoleAndKnowledge(
                prompt.substring(0, split),
                prompt.substring(split + marker.length()));
    }

    private record RoleAndKnowledge(String role, String knowledge) {}

    /** The tool-name allowlist for this turn: brain agents get the
     *  brain review surface, trunk turns get the narrow trunk allowlist,
     *  task turns rely on role/kind filtering only (null = no name filter). */
    private Set<String> toolNameFilter()
    {
        if (activeToolNames != null) {
            return activeToolNames;
        }
        if (kind == ThreadKind.BRAIN_AGENT) {
            return BRAIN_TOOL_ALLOWLIST;
        }
        return isTrunkTurn() ? TRUNK_TOOL_ALLOWLIST : null;
    }

    private AgentRole activeAgentRole()
    {
        return activeTaskId() == null ? AgentRole.TRUNK : AgentRole.TASK;
    }

    /** Build the Anthropic message history array (no system message —
     *  Anthropic takes the system prompt as a top-level field). */
    private ArrayNode buildMessageHistory(String userInput, List<String> images)
    {
        ArrayNode messages = mapper.createArrayNode();
        List<ThreadMessage> tail = store.listRecentMessages(threadId, REPLAY_HISTORY_LIMIT);
        for (ThreadMessage row : tail) {
            String role = row.role();
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            if (!"text".equals(row.type())) {
                continue;
            }
            String text = extractText(row.contentJson());
            if (text == null || text.isEmpty()) {
                continue;
            }
            messages.add(userOrAssistantContentNode(role, text, extractImages(row.contentJson()), true));
        }
        messages.add(userOrAssistantContentNode("user", userInput, images, true));
        return messages;
    }

    /** Build the OpenAI-compatible message list. The system prompt goes as
     *  the first role:system message; OpenAI does not accept a top-level
     *  {@code system} field. */
    private ArrayNode buildOpenAiMessages(String system, String userInput, List<String> images)
    {
        ArrayNode messages = mapper.createArrayNode();
        if (system != null && !system.isEmpty()) {
            ObjectNode sysMsg = mapper.createObjectNode();
            sysMsg.put("role", "system");
            sysMsg.put("content", system.trim());
            messages.add(sysMsg);
        }
        List<ThreadMessage> tail = store.listRecentMessages(threadId, REPLAY_HISTORY_LIMIT);
        for (ThreadMessage row : tail) {
            String role = row.role();
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            if (!"text".equals(row.type())) {
                continue;
            }
            String text = extractText(row.contentJson());
            if (text == null || text.isEmpty()) {
                continue;
            }
            messages.add(userOrAssistantContentNode(role, text, extractImages(row.contentJson()), false));
        }
        messages.add(userOrAssistantContentNode("user", userInput, images, false));
        return messages;
    }

    /** One message entry: a plain {@code content} string when there are no
     *  images (identical to the pre-image-support shape), or a multimodal
     *  content-block array — image block(s) then a text block — when there
     *  are. {@code anthropicStyle} picks the block shape: Anthropic's
     *  {@code {"type":"image","source":{...}}} vs OpenAI's {@code
     *  {"type":"image_url","image_url":{"url":...}}}; DeepSeek rides the
     *  OpenAI shape too, though its local ds4 model likely has no vision
     *  support — untested, narrow enough to leave for whoever hits it. */
    private ObjectNode userOrAssistantContentNode(
            String role, String text, List<String> images, boolean anthropicStyle)
    {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", role);
        if (images == null || images.isEmpty()) {
            msg.put("content", text == null ? "" : text);
            return msg;
        }
        ArrayNode parts = msg.putArray("content");
        for (String path : images) {
            ObjectNode block = imageContentBlock(path, anthropicStyle);
            if (block != null) {
                parts.add(block);
            }
        }
        ObjectNode textBlock = mapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", text == null ? "" : text);
        parts.add(textBlock);
        return msg;
    }

    /** Reads an attached image fresh off disk and base64-encodes it into a
     *  content block. Returns null (dropping the block, not the whole turn)
     *  if the file is gone — e.g. an old message referencing a since-deleted
     *  attachments dir — logging instead of failing the turn over one image. */
    private ObjectNode imageContentBlock(String path, boolean anthropicStyle)
    {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Path.of(path));
        }
        catch (IOException e) {
            log.warn("Could not read attached image {} for thread {}: {}", path, threadId, e.getMessage());
            return null;
        }
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String mimeType = MessageAttachments.mimeTypeFor(path);
        ObjectNode node = mapper.createObjectNode();
        if (anthropicStyle) {
            node.put("type", "image");
            ObjectNode source = node.putObject("source");
            source.put("type", "base64");
            source.put("media_type", mimeType);
            source.put("data", base64);
        }
        else {
            node.put("type", "image_url");
            node.putObject("image_url").put("url", "data:" + mimeType + ";base64," + base64);
        }
        return node;
    }

    private String extractText(String contentJson)
    {
        if (contentJson == null || contentJson.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(contentJson);
            return node.path("text").asText(null);
        }
        catch (Exception e) {
            return null;
        }
    }

    /** The image file paths a persisted {@code text} message carries, if
     *  any — see {@link MessageAttachments#encodeMessage}. */
    private List<String> extractImages(String contentJson)
    {
        if (contentJson == null || contentJson.isEmpty()) {
            return List.of();
        }
        try {
            JsonNode node = mapper.readTree(contentJson);
            List<String> images = new ArrayList<>();
            node.path("images").forEach(n -> images.add(n.asText()));
            return images;
        }
        catch (Exception e) {
            return List.of();
        }
    }

    // ── Cost / model helpers ──────────────────────────────────────────────

    /** Look up per-model pricing and compute the turn cost in milli-USD.
     *  Falls back to Sonnet-class pricing for unrecognised model ids. */
    private static long estimateCostMilli(String modelId, long tokensIn, long tokensOut)
    {
        return ModelPricing.estimateCostMilli(modelId, tokensIn, tokensOut);
    }

    private static String defaultModelForProvider(String provider)
    {
        if (DEEPSEEK_PROVIDER_ID.equalsIgnoreCase(provider)) {
            return "deepseek-chat";
        }
        // openai
        return "gpt-4o-mini";
    }

    // ── Turn finalisation ─────────────────────────────────────────────────

    /** Shared post-loop bookkeeping: persist the assistant message, emit
     *  the summary events, update running totals, and mark the session idle. */
    private void finalizeTurn(
            String modelId, Instant turnStart, String finalText,
            long totalTokensIn, long totalTokensOut)
    {
        Instant finishedAt = Instant.now();
        long durationMs = Math.max(0L, finishedAt.toEpochMilli() - turnStart.toEpochMilli());
        // The overlay already reflected these tokens live during the turn;
        // clear it before committing the authoritative total so the persist
        // below lands baseline + total exactly once.
        liveTurnTokensIn.set(0);
        liveTurnTokensOut.set(0);
        runningTokensIn.addAndGet(totalTokensIn);
        runningTokensOut.addAndGet(totalTokensOut);
        long turnCostMilli = estimateCostMilli(modelId, totalTokensIn, totalTokensOut);
        runningCostUsdMilli.addAndGet(turnCostMilli);

        persistAssistantMessage(finalText, finishedAt, durationMs,
                totalTokensIn, totalTokensOut, turnCostMilli);
        publish(new StreamEvent.AssistantText(finishedAt, finalText));
        publish(new StreamEvent.TurnDone(finishedAt, durationMs, turnCostMilli,
                totalTokensIn, totalTokensOut));
        status.set(ThreadStatus.IDLE);
        persistThreadProgress();
    }

    // ── Message persistence ───────────────────────────────────────────────

    private void persistUserMessage(String text, Instant ts, List<String> images)
    {
        long seq = nextSeq.getAndIncrement();
        String contentJson = MessageAttachments.encodeMessage(mapper, text, images, activeManagedSkillNames());
        appendStamped(new ThreadMessage(
                UUID.randomUUID().toString(), threadId, activeTaskId(), seq,
                "user", "text", contentJson,
                /* durationMs */ null, /* tokensIn */ null, /* tokensOut */ null,
                /* costUsdMilli */ null, ts));
    }

    private void persistAssistantMessage(
            String text, Instant ts, long durationMs,
            long tokensIn, long tokensOut, long costMilli)
    {
        long seq = nextSeq.getAndIncrement();
        appendStamped(new ThreadMessage(
                UUID.randomUUID().toString(), threadId, activeTaskId(), seq,
                "assistant", "text", encodeText(text),
                durationMs, tokensIn, tokensOut, costMilli, ts));
    }

    /** The task this turn's rows and tools attribute to. */
    @Override
    public String activeTaskId()
    {
        return activeTaskId;
    }

    @Override
    public void setActiveTask(String taskId)
    {
        this.activeTaskId = taskId;
    }

    @Override
    public void setActiveStage(String stageId)
    {
        this.activeStageId = stageId;
    }

    @Override
    public String activeStageId()
    {
        return activeStageId;
    }

    @Override
    public void setActiveAgentRun(String agentRunId)
    {
        this.activeAgentRunId = agentRunId;
    }

    @Override
    public void setManagedSkillBundle(ManagedSkillBundle bundle)
    {
        this.managedSkillBundle = bundle == null ? ManagedSkillBundle.empty() : bundle;
        this.activeManagedSkills = List.of();
    }

    @Override
    public void setActiveManagedSkillNames(List<String> names)
    {
        this.activeManagedSkills = managedSkillBundle.select(names);
    }

    @Override
    public void setActiveManagedSkills(List<ManagedSkill> skills)
    {
        this.activeManagedSkills = skills == null ? List.of() : List.copyOf(skills);
    }

    @Override
    public void setActiveToolNames(Set<String> names)
    {
        this.activeToolNames = names == null ? null : Set.copyOf(names);
    }

    private List<String> activeManagedSkillNames()
    {
        return activeManagedSkills.stream()
                .map(ManagedSkill::name)
                .toList();
    }

    /** Persist a message stamped with the turn's explicit stage + scope. A
     *  STAGE-scoped row is re-keyed into its stage's own seq space and the
     *  decoupled stage_messages store; the thread seq the caller pre-allocated
     *  is left unused (a harmless gap in the thread's seq). */
    private void appendStamped(ThreadMessage message)
    {
        if (activeStageId != null && !activeStageId.isBlank()) {
            long seq = nextStageSeq(activeStageId);
            store.appendStageMessage(new ThreadMessage(
                    message.id(), message.threadId(), message.taskId(), seq,
                    message.role(), message.type(), message.contentJson(),
                    message.durationMs(), message.tokensIn(), message.tokensOut(),
                    message.costUsdMilli(), message.ts(), activeStageId, ThreadScope.STAGE));
        }
        else {
            store.appendMessage(message.withStageScope(
                    activeStageId, ThreadScope.of(message.taskId(), activeStageId)));
        }
    }

    /** Next seq in a stage's own space, seeded lazily from the stage store. */
    private long nextStageSeq(String stageId)
    {
        return stageNextSeq
                .computeIfAbsent(stageId, id ->
                        new AtomicLong(store.maxStageMessageSeq(id).map(m -> m + 1).orElse(0L)))
                .getAndIncrement();
    }

    /** True when this turn is happening at the trunk (planning) level
     *  rather than inside a task's execution window. Reused by the
     *  tool-catalog filter — see {@link #TRUNK_TOOL_ALLOWLIST}.
     *
     *  <p>"No active task on the thread" is a good-enough proxy for
     *  "this is a trunk turn" because the trunk agent only runs when
     *  the user typed into the trunk composer (which doesn't open a
     *  task), and the task agent only runs while a task is active.
     *  If we later allow trunk turns while a task is also active
     *  (e.g. background planning while a task executes), we'll plumb
     *  an explicit {@code isTrunk} flag through the constructor and
     *  switch this to read it. */
    private boolean isTrunkTurn()
    {
        return activeTaskId() == null;
    }

    private String encodeText(String text)
    {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("text", text == null ? "" : text);
            return mapper.writeValueAsString(node);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to encode message text", e);
        }
    }

    // ── Event publishing ──────────────────────────────────────────────────

    private void publish(StreamEvent event)
    {
        for (Consumer<StreamEvent> listener : listeners) {
            try {
                listener.accept(event);
            }
            catch (RuntimeException e) {
                log.warn("LogicLoopThreadAgent subscriber failed on {}: {}",
                        event.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    private void emitFatal(String message, Instant turnStart)
    {
        Instant now = Instant.now();
        lastError.set(message);
        publish(new StreamEvent.ErrorOccurred(now, message, /* recoverable */ false));
        publish(new StreamEvent.SessionEnded(now, /* exitCode */ 1, message));
        status.set(ThreadStatus.ERRORED);
        long durationMs = Math.max(0L, now.toEpochMilli() - turnStart.toEpochMilli());
        publish(new StreamEvent.TurnDone(now, durationMs, 0L, 0L, 0L));
        // Commit whatever tokens the failed turn did consume so the count
        // doesn't snap back to the pre-turn baseline on the next poll.
        runningTokensIn.addAndGet(liveTurnTokensIn.getAndSet(0));
        runningTokensOut.addAndGet(liveTurnTokensOut.getAndSet(0));
        persistThreadProgress();
    }

    /** Mirror the agent's running totals back to the Thread row so the
     *  metrics strip and the dashboard's per-thread spend stay in sync. */
    private void persistThreadProgress()
    {
        Optional<Thread> current = store.findThreadById(threadId);
        if (current.isEmpty()) {
            return;
        }
        Thread t = current.get();
        Thread next = new Thread(
                t.id(), t.kind(), t.provider(), t.agentSessionId(),
                t.title(), status.get(),
                model(),
                runningCostUsdMilli.get(),
                runningTokensIn.get() + liveTurnTokensIn.get(),
                runningTokensOut.get() + liveTurnTokensOut.get(),
                t.createdAt(), Instant.now(),
                t.endedAt(), t.errorMessage(),
                t.flow(), t.workspaceId(), t.workModel());
        store.saveThread(next);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void interrupt()
    {
        userInterrupted.set(true);
        cancelPendingPermissions();
        CompletableFuture<Void> turn = currentTurn.get();
        if (turn != null) {
            turn.cancel(true);
        }
    }

    @Override
    public void resume()
    {
        // ARCHIVED (auto-archived for inactivity) revives to IDLE so the
        // user can pick the panel back up.
        if (status.get() == ThreadStatus.ARCHIVED) {
            status.set(ThreadStatus.IDLE);
        }
    }

    @Override
    public void stop()
    {
        userInterrupted.set(true);
        cancelPendingPermissions();
        CompletableFuture<Void> turn = currentTurn.get();
        if (turn != null) {
            turn.cancel(true);
        }
        status.set(ThreadStatus.COMPLETED);
        publish(new StreamEvent.SessionEnded(Instant.now(), 0, null));
    }

    /** Cancel every outstanding Allow / Deny prompt so a stop /
     *  interrupt doesn't leak handler threads blocked on the gate. */
    private void cancelPendingPermissions()
    {
        if (permissionGate == null) {
            return;
        }
        for (String callId : pendingPermissions) {
            permissionGate.cancel(callId);
        }
        pendingPermissions.clear();
    }

    @Override
    public void notifyPermissionRequested(String callId, String toolName, String summary)
    {
        // The mediator publishes its own PermissionRequested event,
        // so this hook is only useful as a structural log for
        // surprise registrations.
        log.debug("notifyPermissionRequested: callId={}, tool={}", callId, toolName);
    }

    @Override
    public boolean decide(String callId, PermissionDecision decision)
    {
        return permissionGate != null && permissionGate.decide(callId, decision);
    }

    @Override
    public void grantToolBudget(String toolName, int count)
    {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        toolBudget.merge(toolName, count, (existing, add) -> {
            if (existing == BUDGET_ALWAYS || add == BUDGET_ALWAYS) {
                return BUDGET_ALWAYS;
            }
            return existing + add;
        });
        // Drain any pending Allow / Deny prompts for the same tool —
        // a user who just clicked "Allow next 5" shouldn't have to
        // click Approve N more times for the calls that piled up.
        if (permissionGate != null) {
            for (String pendingCallId : permissionGate.pendingCallIdsFor(toolName)) {
                OptionalInt slot = tryConsumeToolBudget(toolName);
                if (slot.isEmpty()) {
                    break;
                }
                permissionGate.decide(pendingCallId, PermissionDecision.ALLOW);
                publish(new StreamEvent.PermissionAutoAllowed(
                        Instant.now(), pendingCallId, toolName, slot.getAsInt()));
            }
        }
    }

    @Override
    public OptionalInt tryConsumeToolBudget(String toolName)
    {
        if (toolName == null) {
            return OptionalInt.empty();
        }
        // Atomic decrement with the sentinel kept intact for ALWAYS
        // grants. computeIfPresent returns the new value (or null if
        // the entry was removed) so the caller knows what's left.
        Integer remaining = toolBudget.computeIfPresent(toolName, (k, v) -> {
            if (v == BUDGET_ALWAYS) {
                return BUDGET_ALWAYS;
            }
            int next = v - 1;
            return next <= 0 ? null : next;
        });
        if (remaining == null) {
            // Either no budget existed, or the last slot was just
            // consumed and the entry removed.
            return toolBudget.containsKey(toolName) ? OptionalInt.empty() : OptionalInt.of(0);
        }
        if (remaining == BUDGET_ALWAYS) {
            return OptionalInt.of(BUDGET_ALWAYS);
        }
        return OptionalInt.of(remaining);
    }

    @Override
    public void notifyPermissionAutoAllowed(String callId, String toolName, int remaining)
    {
        publish(new StreamEvent.PermissionAutoAllowed(Instant.now(), callId, toolName, remaining));
    }

    /** {@link ToolPermissionMediator} body. Lives on the agent so it
     *  can drive the gate, the budget, and the event stream the UI
     *  listens to — none of those are accessible from inside the
     *  registry singleton. */
    private PermissionDecision admitToolCall(String callId, String toolName, Gating gating, String summary)
    {
        if (gating == Gating.AUTO) {
            return PermissionDecision.ALLOW;
        }
        if (gating == Gating.PARKED) {
            // Parked tools (request_review, push, merge_pr, …) ride
            // the CLI lane's notification + publish-service flow.
            // Refusing here is safer than running on the model's
            // word; the bridge surfaces a clear pointer to the CLI
            // lane in its error envelope.
            return PermissionDecision.DENY;
        }
        // GATED — consult the per-tool budget first, then prompt.
        OptionalInt slot = tryConsumeToolBudget(toolName);
        if (slot.isPresent()) {
            int left = slot.getAsInt();
            publish(new StreamEvent.PermissionAutoAllowed(Instant.now(), callId, toolName, left));
            return PermissionDecision.ALLOW;
        }
        if (permissionGate == null) {
            // No gate wired — refuse rather than silently allow.
            return PermissionDecision.DENY;
        }
        CompletableFuture<PermissionDecision> future = permissionGate.register(callId, toolName);
        pendingPermissions.add(callId);
        publish(new StreamEvent.PermissionRequested(Instant.now(), callId, toolName, summary));
        try {
            PermissionDecision decision = future.get(
                    PERMISSION_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            publish(new StreamEvent.PermissionDecided(Instant.now(), callId, decision));
            return decision;
        }
        catch (TimeoutException e) {
            permissionGate.cancel(callId);
            publish(new StreamEvent.PermissionDecided(Instant.now(), callId, PermissionDecision.DENY));
            return PermissionDecision.DENY;
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            permissionGate.cancel(callId);
            return PermissionDecision.DENY;
        }
        catch (ExecutionException e) {
            log.warn("Permission gate future failed for {}/{}: {}", callId, toolName, e.getMessage());
            return PermissionDecision.DENY;
        }
        finally {
            pendingPermissions.remove(callId);
        }
    }

    @Override
    public Runnable subscribeToEvents(Consumer<StreamEvent> listener)
    {
        requireNonNull(listener, "listener is null");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Distinct exception so the runTurn catch can branch on it and show
     *  a clear "wrong provider" message instead of a generic failure. */
    private static final class UnsupportedProviderException
            extends RuntimeException
    {
        UnsupportedProviderException(String message)
        {
            super(message);
        }
    }
}
