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
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.tools.PermissionResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Shared machinery for wrapping a one-shot streaming CLI agent (Claude
 * Code's {@code claude -p}, OpenAI's {@code codex exec}) as a
 * {@link ThreadAgent}.
 *
 * <p>One <em>logical</em> session spans many subprocess invocations:
 * each {@link #send} spawns a fresh provider process, reads its
 * streaming-JSON stdout through a {@link CliStreamParser}, and the
 * process exits when the turn finishes. Between turns the session is
 * just a row in the database and an in-memory state machine.
 *
 * <p>Everything provider-agnostic lives here: the lifecycle state
 * machine, stdout draining, {@link StreamEvent} persistence + fan-out,
 * metrics, the permission/tool-budget gate, and the
 * thread/task-snapshot bookkeeping. Subclasses supply only the three
 * things that actually differ between CLIs:
 * <ul>
 *   <li>{@link #buildCommand(String)} — the argv for one turn,</li>
 *   <li>{@link #deliverPrompt(Process, String)} — how the user's input
 *       reaches the process (stdin vs. an argv arg),</li>
 *   <li>{@link #cleanupProviderResources()} — tearing down any
 *       per-session temp files on {@link #stop}.</li>
 * </ul>
 *
 * <p>Concurrency: the public lifecycle methods are thread-safe; a
 * single worker thread per session reads stdout so subscriber
 * delivery is serialized in source order. Send is rejected while a
 * turn is already in flight.
 */
public abstract class AbstractCliThreadAgent
        implements ThreadAgent
{
    private static final Logger log = LoggerFactory.getLogger(AbstractCliThreadAgent.class);

    /** Lifts the {@code callId} out of a permission_* row's content JSON.
     *  The value is a tool-use id (no quotes), so a simple capture is
     *  safe — and avoids a full parse on a hot constructor path. */
    private static final Pattern CALL_ID_PATTERN =
            Pattern.compile("\"callId\"\\s*:\\s*\"([^\"]*)\"");

    private static final int BUDGET_ALWAYS = -1;

    /** Thread id this agent serves. Protected so a subclass's
     *  {@link #buildCommand} can name per-thread temp files. */
    protected final String threadId;
    /** The registry stage key this agent connects to the MCP server under,
     *  embedded in its per-agent MCP URL ({@code .../agents/{agentKey}/mcp})
     *  so its tool calls resolve role / capability against its OWN running
     *  turn under concurrent stage agents. Set by {@link ThreadRegistry}
     *  right after construction; defaults to the reserved trunk key so the
     *  legacy single-agent path keeps working when it's never set. */
    private volatile String mcpAgentKey = PermissionResolver.TRUNK_AGENT_KEY;
    /** Working directory the subprocess runs in. */
    protected final String workingDir;
    /** Best-effort hook run at the start of every turn, before the CLI
     *  spawns — the trunk session wires this to fetch + reset its planning
     *  worktree to the latest base. No-op by default (task sessions don't
     *  set it). A failure here is logged, not fatal: the turn proceeds on
     *  the last-known checkout. */
    private volatile Runnable preTurnHook = () -> {};
    /** Resolved CLI binary name (overridable for tests). */
    protected final String binary;

    private final ThreadKind kind;
    private final String provider;
    private final String branchName;
    private final ThreadStore store;
    /** Retained from the ctor so resume() can flip the latest task's
     *  status alongside the thread's. */
    private final TaskStore taskStore;
    private final CliStreamParser parser;
    private final ObjectMapper mapper;
    private final ToolFileOps fileOps;
    private final McpPermissionGate gate;
    private final ExecutorService executor;
    private final CheckpointTrigger checkpointTrigger;
    private final CopyOnWriteArrayList<Consumer<StreamEvent>> listeners = new CopyOnWriteArrayList<>();

    private final AtomicReference<ThreadStatus> status = new AtomicReference<>();
    /** Detail of the most recent failure that drove the session to
     *  ERRORED (exit code + stderr tail, or an I/O message). Surfaced via
     *  {@link #lastErrorDetail} so the scheduler can attach the real cause
     *  to a turn that ended ERRORED without throwing. */
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicReference<String> agentSessionId = new AtomicReference<>();
    /** Id of the Task this agent is bound to (= the foreground task at
     *  spawn time). Every persisted ThreadMessage and every captured
     *  session-id flow back to this task, so jumping back to a parked
     *  sibling — which creates a fresh agent against the other task —
     *  never mixes histories. */
    private final String activeTaskId;
    /** The stage the in-flight turn belongs to, set by the scheduler before
     *  each {@link #send}; messages emitted during the turn inherit it. */
    private volatile String activeStageId;
    /** The stage this agent has bound to (set on its first stage turn). A work
     *  stage starts a BRAND-NEW session — it never {@code --resume}s the task's
     *  or a prior stage's session — so cross-stage context flows only through
     *  the seeded kickoff, not a shared provider session. */
    private volatile String boundStageId;
    private final AtomicReference<String> model = new AtomicReference<>("");
    private final AtomicReference<Process> currentProcess = new AtomicReference<>();
    /** Set true by {@link #interrupt} just before {@code destroy()} so
     *  {@link #runTurn} can tell a user-initiated cancellation from a
     *  real crash. */
    private final AtomicBoolean userInterrupted = new AtomicBoolean(false);
    private final AtomicLong nextSeq = new AtomicLong();
    /** Per-stage seq counters. A STAGE-scoped message uses its stage's own
     *  seq space (stage_messages), seeded lazily from the store, so concurrent
     *  per-stage agents never collide on the thread-global (thread_id, seq). */
    private final ConcurrentHashMap<String, AtomicLong> stageNextSeq = new ConcurrentHashMap<>();

    /** Accumulates {@code thinking_delta} chunks for the in-flight thinking
     *  block. In {@code --include-partial-messages} mode the final assistant
     *  message's thinking block is signature-only, so the persisted thought
     *  comes from these stitched deltas. Touched only on the single session
     *  event thread. */
    private final StringBuilder thinkingDeltaBuf = new StringBuilder();

    /** How many trailing stderr lines to keep so a non-zero exit can
     *  surface the CLI's actual error instead of a bare exit code. */
    private static final int STDERR_TAIL_LINES = 40;
    /** Rolling tail of the current turn's stderr, guarded by its own
     *  monitor (the drain runs on a separate thread). Cleared per turn. */
    private final ArrayDeque<String> stderrTail = new ArrayDeque<>();

    private final AtomicLong runningCostUsdMilli = new AtomicLong();
    private final AtomicLong runningTokensIn = new AtomicLong();
    private final AtomicLong runningTokensOut = new AtomicLong();
    // Task-scoped usage, seeded from the focused task (0 in trunk mode) and
    // grown per turn. The running* counters above are the THREAD's lifetime
    // cumulative spend; these are just this task's, so metrics() and the task
    // row don't inherit the whole chain's tokens. Untouched in trunk mode.
    private final AtomicLong taskCostUsdMilli = new AtomicLong();
    private final AtomicLong taskTokensIn = new AtomicLong();
    private final AtomicLong taskTokensOut = new AtomicLong();
    // Converts the parser's reported token usage to per-turn deltas. A
    // cumulative provider (Codex) reports the session running total each turn;
    // without this we summed those totals and quadratically over-counted.
    private final CumulativeUsageDelta tokensInDelta;
    private final CumulativeUsageDelta tokensOutDelta;
    private final AtomicLong runningToolCallCount = new AtomicLong();
    private final long sessionStartedMs;

    /** Per-tool auto-approval budget the user granted via "Allow next
     *  N". The value counts remaining auto-allows; {@link #BUDGET_ALWAYS}
     *  is the sentinel for "always for this tool". */
    private final Map<String, Integer> toolBudget = new ConcurrentHashMap<>();

    protected AbstractCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            CliStreamParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            String binary,
            String trunkCwd,
            Task boundTask,
            String modelOverride)
    {
        requireNonNull(thread, "thread is null");
        // CLI_AGENT is the dev/trunk case; BRAIN_AGENT is a brain thread whose
        // resolved work model is CLI, so it runs as a claude-code subprocess
        // too (read-only brain, MCP-scoped to the brain allowlist).
        if (thread.kind() != ThreadKind.CLI_AGENT && thread.kind() != ThreadKind.BRAIN_AGENT) {
            throw new IllegalArgumentException(getClass().getSimpleName()
                    + " only handles CLI_AGENT / BRAIN_AGENT threads");
        }
        this.threadId = thread.id();
        this.kind = thread.kind();
        this.provider = thread.provider();
        // The resolved work-model cascade (task/stage-aware) wins over the
        // thread's own stored model when the caller supplies one — buildStage
        // passes the stage-resolved model here so a stage override reaches
        // the actual --model/-m spawn arg, not just the thread's frozen value.
        this.model.set(modelOverride != null && !modelOverride.isBlank()
                ? modelOverride
                : (thread.model() == null ? "" : thread.model()));
        this.store = requireNonNull(store, "store is null");
        this.parser = requireNonNull(parser, "parser is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.fileOps = new ToolFileOps(mapper);
        this.gate = requireNonNull(gate, "gate is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.checkpointTrigger = requireNonNull(checkpointTrigger, "checkpointTrigger is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.binary = requireNonNull(binary, "binary is null");
        // Trunk-mode short-circuit: no focused Task, no worktree lease.
        // cwd is a watched-repo clone root supplied by the registry so
        // the CLI still has a sane place to read files from. The Thread
        // carries the trunk planning session id (threads.agent_session_id);
        // captured ids flow back to that column, not a task row.
        //
        // The trunk session is independent of the thread's task
        // lifecycle: a COMPLETED / ERRORED thread (its last task shipped
        // or errored) must still accept trunk planning turns. Normalise a
        // terminal inherited status to IDLE so send() doesn't refuse the
        // very first turn.
        long cumBaselineIn = 0L;
        long cumBaselineOut = 0L;
        if (trunkCwd != null) {
            this.workingDir = trunkCwd;
            this.branchName = null;
            this.activeTaskId = null;
            ThreadStatus inherited = thread.status();
            this.status.set(
                    inherited == ThreadStatus.COMPLETED
                            || inherited == ThreadStatus.ARCHIVED
                            || inherited == ThreadStatus.ERRORED
                            ? ThreadStatus.IDLE : inherited);
            this.agentSessionId.set(thread.agentSessionId());
            // A resumed trunk session already contributed thread.tokensIn, so
            // anchor cumulative-usage deltas there; a fresh session starts at 0.
            if (thread.agentSessionId() != null && !thread.agentSessionId().isBlank()) {
                cumBaselineIn = thread.tokensIn();
                cumBaselineOut = thread.tokensOut();
            }
        }
        else {
            // The Task this agent is bound to is resolved by the caller (the
            // ThreadRegistry) and handed in explicitly, rather than re-derived
            // here from the thread's active-task projection. Per-stage agents
            // each bind their own task; the registry resolves it from the
            // running turn's stamped task id (active-or-latest for the
            // resume-from-terminal path).
            if (boundTask == null) {
                throw new IllegalStateException(
                        "thread " + thread.id() + " has no task; cannot spawn CLI agent");
            }
            Task active = boundTask;
            this.workingDir = requireNonNull(active.agentCwd(),
                    "active task " + active.id() + " has no working dir; cannot spawn CLI agent");
            this.branchName = active.branchName();
            this.activeTaskId = active.id();
            this.status.set(thread.status());
            // Resume from the focused task's session, not the thread's.
            // Each Task owns its own forked session that --resume must hit
            // so we land back in this Task's worktree conversation.
            this.agentSessionId.set(active.agentSessionId());
            // Seed task-scoped usage from the task's own prior spend so a
            // resumed task keeps accumulating from where it left off.
            this.taskCostUsdMilli.set(active.costUsdMilli());
            this.taskTokensIn.set(active.tokensIn());
            this.taskTokensOut.set(active.tokensOut());
            // The task's session already contributed active.tokensIn on a
            // resume — anchor cumulative-usage deltas there.
            if (active.agentSessionId() != null && !active.agentSessionId().isBlank()) {
                cumBaselineIn = active.tokensIn();
                cumBaselineOut = active.tokensOut();
            }
        }
        this.runningCostUsdMilli.set(thread.costUsdMilli());
        this.runningTokensIn.set(thread.tokensIn());
        this.runningTokensOut.set(thread.tokensOut());
        boolean cumulativeUsage = parser.reportsCumulativeUsage();
        this.tokensInDelta = new CumulativeUsageDelta(cumulativeUsage, cumBaselineIn);
        this.tokensOutDelta = new CumulativeUsageDelta(cumulativeUsage, cumBaselineOut);
        this.sessionStartedMs = thread.createdAt().toEpochMilli();
        // Seed the seq counter from any existing rows so a restart
        // doesn't collide with prior persisted messages.
        List<ThreadMessage> existing = store.listMessages(threadId);
        long highest = existing.stream()
                .mapToLong(ThreadMessage::seq)
                .max()
                .orElse(-1L);
        this.nextSeq.set(highest + 1L);
        resolveStalePermissions(existing);
    }

    // ---- Provider-specific hooks -------------------------------------

    /**
     * Build the argv + environment for one turn. {@code userInput} is
     * the user's prompt for this turn — a stdin-fed CLI (Claude) ignores
     * it here and writes it in {@link #deliverPrompt}; an argv-fed CLI
     * (Codex) appends it to the command.
     */
    protected abstract ProcessBuilder buildCommand(String userInput);

    /**
     * Deliver the prompt to the freshly-spawned process. Default writes
     * {@code userInput} to stdin and closes it (Claude's {@code -p}
     * contract). A CLI that takes the prompt as an argv arg overrides
     * this to just close stdin without writing.
     */
    protected void deliverPrompt(Process process, String userInput)
            throws IOException
    {
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(userInput.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Tear down any per-session temp resources on {@link #stop}.
     *  Default no-op; a subclass with on-disk session state (MCP config,
     *  materialized skills) overrides. */
    protected void cleanupProviderResources()
    {
    }

    /** The current {@code --resume} session id (null/blank on the first
     *  turn). Subclasses read this when assembling their resume argv. */
    protected final String resumeSessionId()
    {
        return agentSessionId.get();
    }

    // ---- ThreadAgent --------------------------------------------------

    @Override
    public final String id()
    {
        return threadId;
    }

    @Override
    public final ThreadKind kind()
    {
        return kind;
    }

    @Override
    public final String provider()
    {
        return provider;
    }

    @Override
    public final String model()
    {
        return model.get();
    }

    @Override
    public final String workingDir()
    {
        return workingDir;
    }

    @Override
    public final String branchName()
    {
        return branchName;
    }

    @Override
    public final ThreadStatus status()
    {
        return status.get();
    }

    @Override
    public final String lastErrorDetail()
    {
        return lastError.get();
    }

    @Override
    public final AgentMetrics metrics()
    {
        long runtimeMs = Math.max(0L, System.currentTimeMillis() - sessionStartedMs);
        // A task-focused agent reports its TASK's usage; a trunk agent reports
        // the thread's. Without this split a focused task's metrics show the
        // whole thread's lifetime spend (the 26M-token "context" bug).
        boolean taskScoped = activeTaskId != null;
        return new AgentMetrics(
                runtimeMs,
                (taskScoped ? taskCostUsdMilli : runningCostUsdMilli).get(),
                (taskScoped ? taskTokensIn : runningTokensIn).get(),
                (taskScoped ? taskTokensOut : runningTokensOut).get(),
                (int) Math.min(Integer.MAX_VALUE, runningToolCallCount.get()),
                store.listFiles(threadId).size());
    }

    @Override
    public final List<ThreadMessage> history()
    {
        return store.listMessages(threadId);
    }

    @Override
    public final void setActiveStage(String stageId)
    {
        if (stageId != null && !stageId.isBlank() && !stageId.equals(boundStageId)) {
            // First turn of a new work stage: drop any inherited resume id so
            // this stage starts a fresh session instead of continuing the
            // task's (or a prior stage's). Within the stage, the id captured
            // from its own SessionStarted is reused across iterations.
            this.boundStageId = stageId;
            this.agentSessionId.set(null);
        }
        this.activeStageId = stageId;
    }

    @Override
    public final void setMcpAgentKey(String agentKey)
    {
        if (agentKey != null && !agentKey.isBlank()) {
            this.mcpAgentKey = agentKey;
        }
    }

    /** The registry stage key this agent embeds in its MCP server URL.
     *  Defaults to the reserved trunk key until {@link #setMcpAgentKey} runs,
     *  so the legacy single-agent URL keeps resolving. */
    protected final String mcpAgentKey()
    {
        return mcpAgentKey;
    }

    @Override
    public final CompletableFuture<Void> send(String userInput)
    {
        requireNonNull(userInput, "userInput is null");
        ThreadStatus current = status.get();
        if (current == ThreadStatus.COMPLETED
                || current == ThreadStatus.ARCHIVED
                || current == ThreadStatus.ERRORED) {
            throw new IllegalStateException(
                    "thread is in terminal status " + current + "; cannot send more input");
        }
        if (current == ThreadStatus.RUNNING) {
            throw new IllegalStateException("a turn is already in flight");
        }
        transition(ThreadStatus.RUNNING);
        // Echo the user input so subscribers see the full conversation
        // and the row lands in thread_messages — polling readers work off
        // the persisted log, not the listener fan-out.
        Instant now = Instant.now();
        handle(new StreamEvent.UserMessage(now, userInput));
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    runTurn(userInput);
                    completion.complete(null);
                }
                catch (RuntimeException | Error e) {
                    completion.completeExceptionally(e);
                    throw e;
                }
            });
        }
        catch (RejectedExecutionException e) {
            transition(ThreadStatus.ERRORED);
            completion.completeExceptionally(e);
        }
        return completion;
    }

    @Override
    public final void interrupt()
    {
        Process p = currentProcess.get();
        if (p != null && p.isAlive()) {
            log.info("Interrupting thread {} subprocess pid={}", threadId, p.pid());
            // Set the flag before destroy() so the runTurn() exit handler
            // observes it before the process's non-zero exit gets
            // classified — otherwise the documented Interrupt contract
            // ("back to IDLE, ready for the next turn") would route
            // through ERRORED and force the user to click Resume.
            userInterrupted.set(true);
            p.destroy();
        }
    }

    @Override
    public final void resume()
    {
        if (status.compareAndSet(ThreadStatus.AWAITING, ThreadStatus.IDLE)) {
            persistThreadSnapshot(null);
            return;
        }
        // ERRORED → IDLE: continue the conversation after a failed turn.
        // COMPLETED → IDLE: follow up on a thread the agent marked done.
        // Both keep agentSessionId on the row so the next send() resumes
        // exactly where the previous turn left off. Clear endedAt +
        // errorMessage so the runtime ticker restarts and the failure
        // banner doesn't hover over the new conversation.
        if (status.compareAndSet(ThreadStatus.ERRORED, ThreadStatus.IDLE)
                || status.compareAndSet(ThreadStatus.COMPLETED, ThreadStatus.IDLE)
                || status.compareAndSet(ThreadStatus.ARCHIVED, ThreadStatus.IDLE)) {
            Thread current = store.findThreadById(threadId).orElse(null);
            if (current == null) {
                return;
            }
            // Flip the bound task back to IDLE first so the thread-side
            // saveThread cascade sees a non-terminal active task and the two
            // stay in sync. Trunk agents have no bound task and must not
            // revive task work as a side effect.
            if (activeTaskId != null) {
                taskStore.findTaskById(activeTaskId).ifPresent(task -> {
                    if (task.status() != TaskStatus.COMPLETED
                            && task.status() != TaskStatus.REMOTE_CLOSED) {
                        taskStore.saveTask(new Task(
                                task.id(), task.threadId(), task.seq(), TaskStatus.IDLE,
                                task.branchName(), task.worktreePath(), task.baseBranch(),
                                task.workingDir(), task.processPid(), task.logPath(),
                                task.prNumber(), task.prState(), task.ciState(),
                                task.taskType(), task.linkedPrNumber(), task.linkedIssueNumber(),
                                task.costUsdMilli(), task.tokensIn(), task.tokensOut(),
                                agentSessionId.get(),
                                task.createdAt(), /* endedAt */ null,
                                /* errorMessage */ null,
                                task.name(), task.roleSkill(), task.workModel(),
                                task.pushedAt(), task.phase(), task.agendaJson(),
                                task.consecutiveAutoPushes(), task.linkedPrRef(), task.openingPrompt()));
                    }
                });
            }
            store.saveThread(new Thread(
                    current.id(), current.kind(), current.provider(), current.agentSessionId(),
                    current.title(), ThreadStatus.IDLE,
                    model.get(),
                    runningCostUsdMilli.get(), runningTokensIn.get(), runningTokensOut.get(),
                    current.createdAt(), Instant.now(),
                    /* endedAt */ null, /* errorMessage */ null,
                    current.flow(),
                    current.workspaceId(),
                    current.workModel()));
        }
    }

    @Override
    public final void stop()
    {
        interrupt();
        if (status.getAndSet(ThreadStatus.COMPLETED) != ThreadStatus.COMPLETED) {
            persistThreadSnapshot(Instant.now());
            handle(new StreamEvent.SessionEnded(Instant.now(), 0, null));
        }
        cleanupProviderResources();
    }

    @Override
    public final void notifyPermissionRequested(String callId, String toolName, String summary)
    {
        requireNonNull(callId, "callId is null");
        handle(new StreamEvent.PermissionRequested(
                Instant.now(),
                callId,
                toolName == null ? "tool" : toolName,
                summary == null ? "" : summary));
    }

    @Override
    public final boolean decide(String callId, PermissionDecision decision)
    {
        requireNonNull(callId, "callId is null");
        requireNonNull(decision, "decision is null");
        // Hand the decision to the MCP gate first — that unblocks the
        // subprocess waiting on its tools/call response. Only persist the
        // "decided" event when the gate actually resolved something — a
        // false here means the prompt already timed out (or was already
        // decided), so recording a fresh event would misrepresent a no-op
        // click as having done something.
        boolean resolved = gate.decide(callId, decision);
        if (resolved) {
            handle(new StreamEvent.PermissionDecided(Instant.now(), callId, decision));
        }
        return resolved;
    }

    @Override
    public final void grantToolBudget(String toolName, int count)
    {
        requireNonNull(toolName, "toolName is null");
        if (count == BUDGET_ALWAYS) {
            toolBudget.put(toolName, BUDGET_ALWAYS);
        }
        else if (count > 0) {
            // Finite grants accumulate, but "always" wins and stays sticky.
            toolBudget.merge(toolName, count, (prev, add) ->
                    prev == BUDGET_ALWAYS ? BUDGET_ALWAYS : prev + add);
        }
        else {
            return;
        }
        // Drain newly-granted slots against any callIds already sitting in
        // the MCP gate for this tool, so one "Allow next 5" click can
        // sweep up to five already-queued prompts.
        for (String pendingCallId : gate.pendingCallIdsFor(toolName)) {
            OptionalInt remaining = tryConsumeToolBudget(toolName);
            if (remaining.isEmpty()) {
                break;
            }
            decide(pendingCallId, PermissionDecision.ALLOW);
        }
    }

    @Override
    public final OptionalInt tryConsumeToolBudget(String toolName)
    {
        if (toolName == null) {
            return OptionalInt.empty();
        }
        // Atomic decrement with floor 0 — concurrent MCP requests for the
        // same tool can race, but the remapping hands out only remaining
        // slots. Capture the pre-decrement value so we can return the
        // post-decrement remainder (or -1 for an ALWAYS grant).
        int[] before = {0};
        toolBudget.computeIfPresent(toolName, (k, v) -> {
            before[0] = v;
            if (v == BUDGET_ALWAYS) {
                return BUDGET_ALWAYS;
            }
            return v > 1 ? v - 1 : null;
        });
        if (before[0] == 0) {
            return OptionalInt.empty();
        }
        if (before[0] == BUDGET_ALWAYS) {
            return OptionalInt.of(BUDGET_ALWAYS);
        }
        return OptionalInt.of(before[0] - 1);
    }

    @Override
    public final void notifyPermissionAutoAllowed(String callId, String toolName, int remaining)
    {
        requireNonNull(callId, "callId is null");
        handle(new StreamEvent.PermissionAutoAllowed(
                Instant.now(),
                callId,
                toolName == null ? "tool" : toolName,
                remaining));
    }

    @Override
    public final Runnable subscribeToEvents(Consumer<StreamEvent> listener)
    {
        requireNonNull(listener, "listener is null");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Wire a per-turn hook run before each turn's CLI spawn — the trunk
     *  session uses it to refresh its planning worktree to the latest
     *  base. Passing {@code null} clears it back to a no-op. */
    public final void setPreTurnHook(Runnable hook)
    {
        this.preTurnHook = hook == null ? () -> {} : hook;
    }

    // ---- Turn execution ----------------------------------------------

    private void runTurn(String userInput)
    {
        // Trunk sessions sync their planning worktree to the latest base
        // here (on this worker thread, not the scheduler lock). The user's
        // prompt is already the tail message, so the UI shows "Syncing…"
        // for this window until the model streams its first output.
        try {
            preTurnHook.run();
        }
        catch (RuntimeException e) {
            log.warn("Pre-turn hook for thread {} failed: {}", threadId, e.getMessage());
        }
        ProcessBuilder pb = buildCommand(userInput);
        // Clear any stale interrupt flag from a prior turn so a fresh
        // turn's non-zero exit isn't misread as a user cancellation.
        userInterrupted.set(false);
        // Drop a prior turn's failure detail so it can't leak onto a later
        // turn's outcome.
        lastError.set(null);
        Process process;
        try {
            process = pb.start();
        }
        catch (IOException e) {
            log.warn("Failed to spawn {} for thread {}: {}", binary, threadId, e.getMessage());
            lastError.set("failed to spawn " + binary + ": " + e.getMessage());
            transition(ThreadStatus.ERRORED);
            publish(new StreamEvent.ErrorOccurred(Instant.now(),
                    "failed to spawn " + binary + ": " + e.getMessage(), false));
            publish(new StreamEvent.SessionEnded(Instant.now(), -1, e.getMessage()));
            persistThreadSnapshot(Instant.now());
            return;
        }
        currentProcess.set(process);
        synchronized (stderrTail) {
            stderrTail.clear();
        }
        // The worktree lease is held by the registry for the lifetime of
        // this session, so the per-turn subprocess doesn't manage it.
        try {
            deliverPrompt(process, userInput);
            java.lang.Thread stderrThread = drainStderr(process);
            consumeStdout(process);
            int exit = process.waitFor();
            if (exit != 0) {
                // p.destroy() from interrupt() always lands here with a
                // non-zero exit. Treat the user-initiated path as a clean
                // cancel: back to IDLE, no error banner, no failure event.
                if (userInterrupted.compareAndSet(true, false)) {
                    transition(ThreadStatus.IDLE);
                    publish(new StreamEvent.SessionEnded(Instant.now(), exit, "interrupted by user"));
                }
                else {
                    // Let the stderr drain finish so the failure carries the
                    // CLI's actual error, not just a bare exit code.
                    try {
                        stderrThread.join(2_000);
                    }
                    catch (InterruptedException ignored) {
                        java.lang.Thread.currentThread().interrupt();
                    }
                    String tail = stderrTailText();
                    String detail = binary + " exited with code " + exit
                            + (tail.isBlank() ? "" : ":\n" + tail);
                    log.warn("CLI thread {} ({}) failed: {}", threadId, binary, detail);
                    lastError.set(detail);
                    publish(new StreamEvent.ErrorOccurred(Instant.now(), detail, true));
                    transition(ThreadStatus.ERRORED);
                    publish(new StreamEvent.SessionEnded(Instant.now(), exit, "non-zero exit"));
                }
            }
            else {
                transition(ThreadStatus.IDLE);
            }
        }
        catch (IOException e) {
            // A user interrupt closes the stdin pipe before the CLI
            // finishes reading it, surfacing here as "Broken pipe". Same
            // treatment as the non-zero-exit interrupt path.
            if (userInterrupted.compareAndSet(true, false)) {
                transition(ThreadStatus.IDLE);
                publish(new StreamEvent.SessionEnded(Instant.now(), -1, "interrupted by user"));
            }
            else {
                log.warn("I/O error talking to {} for thread {}: {}", binary, threadId, e.getMessage());
                lastError.set("I/O error talking to " + binary + ": " + e.getMessage());
                transition(ThreadStatus.ERRORED);
                publish(new StreamEvent.ErrorOccurred(Instant.now(), e.getMessage(), false));
            }
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            transition(ThreadStatus.IDLE);
        }
        finally {
            currentProcess.set(null);
            persistThreadSnapshot(null);
        }
    }

    private void consumeStdout(Process process)
            throws IOException
    {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                List<StreamEvent> events = parser.parse(line, Instant.now());
                for (StreamEvent event : events) {
                    handle(event);
                }
            }
        }
    }

    private java.lang.Thread drainStderr(Process process)
    {
        java.lang.Thread t = new java.lang.Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[{} stderr] {}", binary, line);
                    synchronized (stderrTail) {
                        stderrTail.addLast(line);
                        while (stderrTail.size() > STDERR_TAIL_LINES) {
                            stderrTail.removeFirst();
                        }
                    }
                }
            }
            catch (IOException ignored) {
                // Process exited; pipe is closed.
            }
        }, "thread-" + threadId + "-stderr");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** The captured stderr tail, joined — used to explain a non-zero exit. */
    private String stderrTailText()
    {
        synchronized (stderrTail) {
            return String.join("\n", stderrTail).strip();
        }
    }

    private void handle(StreamEvent event)
    {
        if (event instanceof StreamEvent.SessionStarted s) {
            if (agentSessionId.get() == null) {
                agentSessionId.set(s.sessionId());
            }
            captureReportedModel(s.model());
        }
        if (event instanceof StreamEvent.ToolCallStarted call) {
            runningToolCallCount.incrementAndGet();
            recordFileOps(call);
        }
        boolean turnDone = event instanceof StreamEvent.TurnDone;
        if (event instanceof StreamEvent.TurnDone t) {
            // Convert the parser's reported usage to a per-turn delta first —
            // Codex reports the session running total each turn, so adding the
            // raw value would quadratically over-count (the 3.8M-token bug).
            long inDelta = tokensInDelta.delta(t.tokensIn());
            long outDelta = tokensOutDelta.delta(t.tokensOut());
            runningCostUsdMilli.addAndGet(t.costUsdMilli());
            runningTokensIn.addAndGet(inDelta);
            runningTokensOut.addAndGet(outDelta);
            // Grow the focused task's OWN usage and persist it, so the task
            // row reflects what this task spent (not the thread's lifetime —
            // the saveThread cascade no longer mirrors thread totals onto it).
            if (activeTaskId != null) {
                long cost = taskCostUsdMilli.addAndGet(t.costUsdMilli());
                long in = taskTokensIn.addAndGet(inDelta);
                long out = taskTokensOut.addAndGet(outDelta);
                taskStore.findTaskById(activeTaskId).ifPresent(task ->
                        taskStore.saveTask(task.withUsage(cost, in, out)));
            }
        }
        // Stitch streamed thinking text: accumulate the deltas, and when the
        // (signature-only) thinking block lands with an empty summary, persist
        // the accumulated reasoning instead of an empty thought.
        StreamEvent toPersist = event;
        if (event instanceof StreamEvent.ThinkingTextDelta delta) {
            thinkingDeltaBuf.append(delta.textChunk());
        }
        else if (event instanceof StreamEvent.ThinkingStarted started
                && started.summary().isEmpty() && thinkingDeltaBuf.length() > 0) {
            toPersist = new StreamEvent.ThinkingStarted(started.timestamp(), thinkingDeltaBuf.toString());
            thinkingDeltaBuf.setLength(0);
        }
        else if (event instanceof StreamEvent.ThinkingDone) {
            thinkingDeltaBuf.setLength(0);
        }
        persistMessage(toPersist);
        publish(event);
        if (turnDone) {
            // Fire-and-forget — the trigger schedules background work and
            // returns immediately, so a slow Anthropic call can't back up
            // the session's event thread.
            try {
                checkpointTrigger.onTurnDone(threadId);
            }
            catch (RuntimeException e) {
                log.warn("checkpoint trigger failed for thread {}: {}", threadId, e.getMessage());
            }
        }
    }

    /** Pull any file ops out of a tool call's input and upsert them into
     *  {@code thread_files}. {@code count} accumulates so the Files tab
     *  can render "touched 3 times" without a join. */
    private void recordFileOps(StreamEvent.ToolCallStarted call)
    {
        List<ToolFileOps.FileOp> parsed = fileOps.parse(call.toolName(), call.inputJson());
        if (parsed.isEmpty()) {
            return;
        }
        Map<String, ThreadFile> existing = new HashMap<>();
        for (ThreadFile f : store.listFiles(threadId)) {
            existing.put(f.path(), f);
        }
        for (ToolFileOps.FileOp op : parsed) {
            ThreadFile prior = existing.get(op.path());
            int count = (prior == null ? 0 : prior.count()) + 1;
            int linesAdded = (prior == null ? 0 : prior.linesAdded()) + op.linesAdded();
            int linesRemoved = (prior == null ? 0 : prior.linesRemoved()) + op.linesRemoved();
            try {
                store.recordFile(new ThreadFile(
                        threadId, op.path(), op.operation(),
                        count, linesAdded, linesRemoved, call.timestamp()));
            }
            catch (RuntimeException e) {
                log.warn("Failed to record file op for thread {}: {}", threadId, e.getMessage());
            }
        }
    }

    private void captureReportedModel(String reportedModel)
    {
        String nextModel = reportedModel == null ? "" : reportedModel.trim();
        if (nextModel.isEmpty()) {
            return;
        }
        String currentModel = model.get();
        if (!currentModel.isBlank()) {
            return;
        }
        if (model.compareAndSet(currentModel, nextModel)) {
            persistThreadSnapshot(null);
        }
    }

    private void persistMessage(StreamEvent event)
    {
        ThreadMessage msg = toMessage(event);
        if (msg == null) {
            return;
        }
        try {
            if (msg.scope() == ThreadScope.STAGE) {
                store.appendStageMessage(msg);
            }
            else {
                store.appendMessage(msg);
            }
        }
        catch (RuntimeException e) {
            log.warn("Failed to persist message for thread {}: {}", threadId, e.getMessage());
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

    private ThreadMessage toMessage(StreamEvent event)
    {
        boolean stageScoped = activeStageId != null && !activeStageId.isBlank();
        long seq = stageScoped ? nextStageSeq(activeStageId) : nextSeq.getAndIncrement();
        String id = UUID.randomUUID().toString();
        Instant ts = event.timestamp();
        ThreadMessage built = switch (event) {
            case StreamEvent.SessionStarted e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "system", "session_started",
                    content(mapper.createObjectNode()
                            .put("sessionId", e.sessionId())
                            .put("cwd", e.cwd())
                            .put("model", e.model())),
                    null, null, null, null, ts);
            case StreamEvent.UserMessage e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "user", "text",
                    content(mapper.createObjectNode().put("text", e.text())),
                    null, null, null, null, ts);
            case StreamEvent.AssistantText e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "assistant", "text",
                    content(mapper.createObjectNode().put("text", e.text())),
                    null, null, null, null, ts);
            // Live-only — deltas reach SSE subscribers and feed the
            // in-flight assistant card; the assembled AssistantText is the
            // durable form. Persisting deltas would inflate the table by
            // ~1 row per token.
            case StreamEvent.AssistantTextDelta ignored -> null;
            case StreamEvent.ThinkingStarted e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "assistant", "thinking",
                    content(mapper.createObjectNode().put("summary", e.summary())),
                    null, null, null, null, ts);
            case StreamEvent.ThinkingTextDelta ignored -> null;
            case StreamEvent.ThinkingDone ignored -> null;
            case StreamEvent.ToolCallStarted e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "tool", "tool_call",
                    content(mapper.createObjectNode()
                            .put("callId", e.callId())
                            .put("toolName", e.toolName())
                            .set("input", rawJson(e.inputJson()))),
                    null, null, null, null, ts);
            case StreamEvent.ToolCallDone e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "tool", "tool_result",
                    content(mapper.createObjectNode()
                            .put("callId", e.callId())
                            .put("isError", e.isError())
                            .set("output", rawJson(e.outputJson()))),
                    null, null, null, null, ts);
            case StreamEvent.PermissionRequested e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "system", "permission_request",
                    content(mapper.createObjectNode()
                            .put("callId", e.callId())
                            .put("toolName", e.toolName())
                            .put("summary", e.summary())),
                    null, null, null, null, ts);
            case StreamEvent.PermissionDecided e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "system", "permission_decision",
                    content(mapper.createObjectNode()
                            .put("callId", e.callId())
                            .put("decision", e.decision().name())),
                    null, null, null, null, ts);
            case StreamEvent.PermissionAutoAllowed e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "system", "permission_auto_allowed",
                    content(mapper.createObjectNode()
                            .put("callId", e.callId())
                            .put("toolName", e.toolName())
                            .put("remaining", e.remaining())),
                    null, null, null, null, ts);
            // Live-only — in-flight token counters reach SSE subscribers
            // and overlay the metrics panel; TurnDone is the durable row.
            case StreamEvent.UsageUpdated ignored -> null;
            case StreamEvent.TurnDone e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "system", "turn_done", "{}",
                    e.durationMs(), e.tokensIn(), e.tokensOut(), e.costUsdMilli(), ts);
            case StreamEvent.ErrorOccurred e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "system", "error",
                    content(mapper.createObjectNode()
                            .put("message", e.message())
                            .put("recoverable", e.recoverable())),
                    null, null, null, null, ts);
            case StreamEvent.SessionEnded e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "system", "session_ended",
                    content(mapper.createObjectNode()
                            .put("exitCode", e.exitCode())
                            .put("errorMessage", e.errorMessage())),
                    null, null, null, null, ts);
        };
        // Stamp the explicit stage + scope for the turn this session is
        // running, so the row joins to its stage instead of relying on a time
        // window. activeStageId is set by the scheduler before each send.
        return built == null
                ? null
                : built.withStageScope(activeStageId, ThreadScope.of(activeTaskId, activeStageId));
    }

    private void publish(StreamEvent event)
    {
        for (Consumer<StreamEvent> listener : listeners) {
            try {
                listener.accept(event);
            }
            catch (RuntimeException e) {
                log.warn("Subscriber for thread {} threw: {}", threadId, e.getMessage());
            }
        }
    }

    private void transition(ThreadStatus next)
    {
        ThreadStatus prev = status.getAndSet(next);
        if (prev != next) {
            persistThreadSnapshot(next == ThreadStatus.COMPLETED || next == ThreadStatus.ERRORED
                    ? Instant.now()
                    : null);
        }
    }

    private void persistThreadSnapshot(Instant endedAt)
    {
        Thread current = store.findThreadById(threadId).orElse(null);
        if (current == null) {
            return;
        }
        String capturedSession = agentSessionId.get();
        // Task mode: push the captured session id down to the active task
        // and keep the Thread row's trunk session id untouched. Trunk
        // mode: the captured session IS the trunk session, so it lands on
        // threads.agent_session_id directly.
        if (activeStageId == null
                && activeTaskId != null
                && capturedSession != null
                && !capturedSession.isBlank()) {
            // Only a task-level (non-stage) turn writes its session onto the
            // task. A stage's session is per-stage and in-memory — never
            // pushed to the task, so the next stage can't --resume it.
            taskStore.findTaskById(activeTaskId).ifPresent(task -> {
                if (!capturedSession.equals(task.agentSessionId())) {
                    taskStore.saveTask(new Task(
                            task.id(), task.threadId(), task.seq(), task.status(),
                            task.branchName(), task.worktreePath(), task.baseBranch(),
                            task.workingDir(), task.processPid(), task.logPath(),
                            task.prNumber(), task.prState(), task.ciState(),
                            task.taskType(), task.linkedPrNumber(), task.linkedIssueNumber(),
                            task.costUsdMilli(), task.tokensIn(), task.tokensOut(),
                            capturedSession,
                            task.createdAt(), task.endedAt(), task.errorMessage(),
                            task.name(), task.roleSkill(), task.workModel()));
                }
            });
        }
        String threadSessionId = activeTaskId == null
                && capturedSession != null
                && !capturedSession.isBlank()
                ? capturedSession
                : current.agentSessionId();
        Thread next = new Thread(
                current.id(), current.kind(), current.provider(), threadSessionId,
                current.title(), status.get(),
                model.get(),
                runningCostUsdMilli.get(), runningTokensIn.get(), runningTokensOut.get(),
                current.createdAt(), Instant.now(),
                endedAt != null ? endedAt : current.endedAt(),
                current.errorMessage(),
                current.flow(),
                current.workspaceId(),
                current.workModel());
        store.saveThread(next);
    }

    // ---- Shared helpers ----------------------------------------------

    /**
     * Resolve approval prompts left unanswered by a prior session. The
     * permission gate is in-memory, so once that session is gone any
     * {@code permission_request} without a matching decision can never be
     * answered — it would show as a forever-pending card. Building a fresh
     * agent is exactly when the old gate is known dead, so we record a
     * denial for each, clearing the backlog.
     */
    private void resolveStalePermissions(List<ThreadMessage> messages)
    {
        List<String> stale = unresolvedPermissionCallIds(messages);
        for (String callId : stale) {
            persistMessage(new StreamEvent.PermissionDecided(
                    Instant.now(), callId, PermissionDecision.DENY));
        }
        if (!stale.isEmpty()) {
            log.info("Resolved {} stale permission prompt(s) for thread {}", stale.size(), threadId);
        }
    }

    /** The callIds of {@code permission_request} rows that never received
     *  a decision — the prompts a dead session left dangling. Each callId
     *  is returned at most once even if its request row appears twice. */
    static List<String> unresolvedPermissionCallIds(List<ThreadMessage> messages)
    {
        Set<String> resolved = new HashSet<>();
        for (ThreadMessage m : messages) {
            if ("permission_decision".equals(m.type()) || "permission_auto_allowed".equals(m.type())) {
                String callId = extractCallId(m.contentJson());
                if (callId != null) {
                    resolved.add(callId);
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (ThreadMessage m : messages) {
            if (!"permission_request".equals(m.type())) {
                continue;
            }
            String callId = extractCallId(m.contentJson());
            // resolved doubles as a dedupe guard: a callId is emitted once
            // even if its request row somehow appears twice.
            if (callId == null || !resolved.add(callId)) {
                continue;
            }
            out.add(callId);
        }
        return out;
    }

    private static String extractCallId(String contentJson)
    {
        if (contentJson == null) {
            return null;
        }
        Matcher matcher = CALL_ID_PATTERN.matcher(contentJson);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Serialize a built content node to its JSON string, falling back to
     *  an empty object if Jackson can't render it (never expected for the
     *  flat nodes built here). */
    private String content(ObjectNode node)
    {
        try {
            return mapper.writeValueAsString(node);
        }
        catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /** Parse a raw JSON string (a tool's input/output) into a node to embed
     *  verbatim. Empty/null or unparseable input becomes a JSON null, matching
     *  the prior "null" literal for absent tool payloads. */
    private JsonNode rawJson(String s)
    {
        if (s == null || s.isEmpty()) {
            return mapper.nullNode();
        }
        try {
            return mapper.readTree(s);
        }
        catch (JsonProcessingException e) {
            return mapper.nullNode();
        }
    }

    /** Default executor for production use — daemon threads named per
     *  thread so jstack output is readable. */
    public static ExecutorService defaultExecutor()
    {
        return Executors.newCachedThreadPool(r -> {
            java.lang.Thread t = new java.lang.Thread(r, "thread-runner");
            t.setDaemon(true);
            return t;
        });
    }
}
