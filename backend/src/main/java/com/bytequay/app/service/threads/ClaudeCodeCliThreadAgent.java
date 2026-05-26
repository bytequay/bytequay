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
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.skills.SkillMaterializer;
import com.bytequay.app.service.tools.ToolContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
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
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Wraps the {@code claude} CLI as an {@link ThreadAgent}.
 *
 * <p>One <em>logical</em> session spans many subprocess invocations:
 * each {@link #send} spawns a fresh {@code claude -p ... --resume
 * <session-id>} (or no {@code --resume} on the first turn), reads its
 * {@code stream-json} stdout, and the process exits when the turn
 * finishes. Between turns the session is just a row in the database
 * and an in-memory state machine.
 *
 * <p>Concurrency: the public lifecycle methods are thread-safe; a
 * single worker thread per session reads stdout so subscriber
 * delivery is serialized in source order. Send is rejected while a
 * turn is already in flight.
 */
public class ClaudeCodeCliThreadAgent
        implements ThreadAgent
{
    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeCliThreadAgent.class);

    /** {@code claude}'s default binary name on PATH. Overridable in
     *  case the user installed it under a different name. */
    private static final String DEFAULT_BINARY = "claude";

    private final String threadId;
    private final ThreadKind kind;
    private final String provider;
    private final String workingDir;
    private final String branchName;
    private final String binary;
    private final ThreadStore store;
    /** Retained from the ctor so resume() can flip the latest task's
     *  status alongside the thread's. The thread-side cascade in
     *  SqliteThreadStore.saveThread only fires for non-terminal active
     *  tasks; when we're reviving a fully-COMPLETED thread the active
     *  lookup is empty, so the agent has to explicitly flip the
     *  underlying task row through TaskStore. */
    private final TaskStore taskStore;
    private final StreamJsonParser parser;
    private final ToolFileOps fileOps;
    private final McpPermissionGate gate;
    private final ExecutorService executor;
    private final CheckpointTrigger checkpointTrigger;
    /** Resolves the thread's workspace memory_md at spawn time. Empty
     *  string when nothing's there yet (the freshly-installed default
     *  workspace). The CLI sees the result via --append-system-prompt
     *  on each session bootstrap. */
    private final Supplier<String> workspaceMemoryProvider;
    /** Role skill text resolved at session construction. Trunk uses
     *  the fixed template; task mode uses the task's frozen
     *  {@code role_skill} column. Null when no role block applies —
     *  the buildCommand step then skips the --append-system-prompt
     *  entry. Resolved once so the system prefix stays byte-stable
     *  for the lifetime of the session. */
    private final String roleSkillText;
    private final CopyOnWriteArrayList<Consumer<StreamEvent>> listeners = new CopyOnWriteArrayList<>();

    /** Lazily-written MCP config file Claude reads via
     *  {@code --mcp-config}. Same path for the lifetime of one
     *  session; cleaned up on {@link #stop}. */
    private final AtomicReference<Path> mcpConfigPath = new AtomicReference<>();

    /** Materializes the resolved skills for this session into a
     *  session-scoped temp dir on first turn; cleaned up on
     *  {@link #stop}. The DB stays the source of truth — these
     *  files are ephemeral and re-derived on every fresh session. */
    private final SkillMaterializer skillMaterializer;
    private final AtomicReference<Path> skillsDir = new AtomicReference<>();

    private final AtomicReference<ThreadStatus> status = new AtomicReference<>();
    private final AtomicReference<String> agentSessionId = new AtomicReference<>();
    /** Id of the Task this agent is bound to (= the foreground task at
     *  spawn time). Every persisted ThreadMessage and every captured
     *  session-id flow back to this task, so jumping back to a parked
     *  sibling — which creates a fresh agent against the other task —
     *  never mixes histories. */
    private final String activeTaskId;
    private final AtomicReference<String> model = new AtomicReference<>("");
    private final AtomicReference<Process> currentProcess = new AtomicReference<>();
    /** Set true by {@link #interrupt} just before {@code destroy()} so
     *  {@link #runTurn} can tell a user-initiated cancellation from a
     *  real crash. p.destroy() makes the subprocess exit non-zero,
     *  which would otherwise route through the ERRORED branch and
     *  force the user to click Resume just to keep typing. Reset at
     *  the start of every turn and consumed when the runTurn cleanup
     *  observes the non-zero exit. */
    private final AtomicBoolean userInterrupted = new AtomicBoolean(false);
    private final AtomicLong nextSeq = new AtomicLong();

    private final AtomicLong runningCostUsdMilli = new AtomicLong();
    private final AtomicLong runningTokensIn = new AtomicLong();
    private final AtomicLong runningTokensOut = new AtomicLong();
    private final AtomicLong runningToolCallCount = new AtomicLong();
    private final long sessionStartedMs;

    /** Per-tool auto-approval budget the user granted via "Allow next
     *  N". The value counts remaining auto-allows; {@link #BUDGET_ALWAYS}
     *  is the sentinel for "always for this tool". Lives only for the
     *  session; a stopped or failed thread drops the map. */
    private final Map<String, Integer> toolBudget = new ConcurrentHashMap<>();

    private static final int BUDGET_ALWAYS = -1;

    public ClaudeCodeCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            SkillMaterializer skillMaterializer,
            String roleSkillText)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, skillMaterializer, roleSkillText, DEFAULT_BINARY, (String) null);
    }

    /**
     * Trunk-mode constructor: the agent runs without a focused Task,
     * cwd defaulting to {@code trunkCwd} (a watched-repo clone root),
     * with {@code thread.agentSessionId} as the {@code --resume} id.
     * Persisted messages and the captured session id flow back to the
     * Thread row instead of any Task, so cross-task planning history
     * stays in the trunk slice ({@code task_id IS NULL}).
     */
    public ClaudeCodeCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            SkillMaterializer skillMaterializer,
            String roleSkillText,
            String trunkCwd,
            @SuppressWarnings("unused") TrunkMode trunkMode)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, skillMaterializer, roleSkillText, DEFAULT_BINARY, trunkCwd);
    }

    /** Marker enum disambiguating the two-argument trailing-string
     *  constructor overloads. {@link #ENABLED} = trunk mode. */
    public enum TrunkMode { ENABLED }

    ClaudeCodeCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            SkillMaterializer skillMaterializer,
            String roleSkillText,
            String binary)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, skillMaterializer, roleSkillText, binary, (String) null);
    }

    private ClaudeCodeCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            SkillMaterializer skillMaterializer,
            String roleSkillText,
            String binary,
            String trunkCwd)
    {
        requireNonNull(thread, "thread is null");
        if (thread.kind() != ThreadKind.CLI_AGENT) {
            throw new IllegalArgumentException("ClaudeCodeCliThreadAgent only handles CLI_AGENT threads");
        }
        this.threadId = thread.id();
        this.kind = thread.kind();
        this.provider = thread.provider();
        this.model.set(thread.model() == null ? "" : thread.model());
        this.store = requireNonNull(store, "store is null");
        this.parser = requireNonNull(parser, "parser is null");
        this.fileOps = new ToolFileOps(requireNonNull(mapper, "mapper is null"));
        this.gate = requireNonNull(gate, "gate is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.checkpointTrigger = requireNonNull(checkpointTrigger, "checkpointTrigger is null");
        this.workspaceMemoryProvider = requireNonNull(workspaceMemoryProvider, "workspaceMemoryProvider is null");
        // skillMaterializer is allowed to be null on legacy / test
        // paths that don't care about skill materialization. The
        // buildCommand hook gates I/O behind a null check.
        this.skillMaterializer = skillMaterializer;
        this.roleSkillText = roleSkillText;
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.binary = requireNonNull(binary, "binary is null");
        // Look up the active task directly from TaskStore so the
        // ctor works whether the caller built the Thread record via
        // SqliteThreadStore (where activeTask is projected) or hand-
        // assembled it (where it may be null). The agent can't run
        // without a working directory.
        //
        // The fallback to findLatestTaskForThread covers the resume-
        // from-terminal path: a COMPLETED thread's most-recent task
        // is also terminal, so the active lookup returns empty, but
        // we still need that task's worktree + branch when the user
        // picks the conversation back up. resume() will flip the
        // thread (and via persistThreadSnapshot, the task) back to
        // IDLE before the first send() actually spawns the CLI.
        // Trunk-mode short-circuit: no focused Task, no worktree lease.
        // cwd is a watched-repo clone root supplied by the registry so
        // the CLI still has a sane place to read files from. The Thread
        // carries the trunk planning session id (threads.agent_session_id);
        // captured ids flow back to that column, not a task row.
        //
        // The trunk session is independent of the thread's task
        // lifecycle: a COMPLETED / ERRORED thread (its last task shipped
        // or errored) must still accept trunk planning turns, since
        // planning at the trunk is what the user does to figure out
        // the next slice. Normalise a terminal inherited status to
        // IDLE so send() doesn't refuse the very first turn.
        if (trunkCwd != null) {
            this.workingDir = trunkCwd;
            this.branchName = null;
            this.activeTaskId = null;
            ThreadStatus inherited = thread.status();
            this.status.set(
                    inherited == ThreadStatus.COMPLETED
                            || inherited == ThreadStatus.ERRORED
                            ? ThreadStatus.IDLE : inherited);
            this.agentSessionId.set(thread.agentSessionId());
        }
        else {
            Task active = taskStore.findActiveTaskForThread(thread.id())
                    .or(() -> taskStore.findLatestTaskForThread(thread.id()))
                    .orElseThrow(() -> new IllegalStateException(
                            "thread " + thread.id()
                                    + " has no task; cannot spawn CLI agent"));
            this.workingDir = requireNonNull(active.agentCwd(),
                    "active task " + active.id() + " has no working dir; cannot spawn CLI agent");
            this.branchName = active.branchName();
            this.activeTaskId = active.id();
            this.status.set(thread.status());
            // Resume from the focused task's session, not the thread's.
            // The Thread carries the trunk/planning session; each Task owns
            // its own forked session that --resume must hit so we land back
            // in this Task's worktree conversation.
            this.agentSessionId.set(active.agentSessionId());
        }
        this.runningCostUsdMilli.set(thread.costUsdMilli());
        this.runningTokensIn.set(thread.tokensIn());
        this.runningTokensOut.set(thread.tokensOut());
        this.sessionStartedMs = thread.createdAt().toEpochMilli();
        // Seed the seq counter from any existing rows so a restart
        // doesn't collide with prior persisted messages.
        long highest = store.listMessages(threadId).stream()
                .mapToLong(ThreadMessage::seq)
                .max()
                .orElse(-1L);
        this.nextSeq.set(highest + 1L);
    }

    @Override
    public String id()
    {
        return threadId;
    }

    @Override
    public ThreadKind kind()
    {
        return kind;
    }

    @Override
    public String provider()
    {
        return provider;
    }

    @Override
    public String model()
    {
        return model.get();
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
    public AgentMetrics metrics()
    {
        long runtimeMs = Math.max(0L, System.currentTimeMillis() - sessionStartedMs);
        return new AgentMetrics(
                runtimeMs,
                runningCostUsdMilli.get(),
                runningTokensIn.get(),
                runningTokensOut.get(),
                (int) Math.min(Integer.MAX_VALUE, runningToolCallCount.get()),
                store.listFiles(threadId).size());
    }

    @Override
    public List<ThreadMessage> history()
    {
        return store.listMessages(threadId);
    }

    @Override
    public CompletableFuture<Void> send(String userInput)
    {
        requireNonNull(userInput, "userInput is null");
        ThreadStatus current = status.get();
        if (current == ThreadStatus.COMPLETED || current == ThreadStatus.ERRORED) {
            throw new IllegalStateException(
                    "thread is in terminal status " + current + "; cannot send more input");
        }
        if (current == ThreadStatus.RUNNING) {
            throw new IllegalStateException("a turn is already in flight");
        }
        transition(ThreadStatus.RUNNING);
        // Echo the user input so subscribers see the full conversation
        // and the row lands in thread_messages — polling readers (i.e.
        // the detail page) work off the persisted log, not the
        // listener fan-out, so publish() alone wouldn't show it.
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
    public void interrupt()
    {
        Process p = currentProcess.get();
        if (p != null && p.isAlive()) {
            log.info("Interrupting thread {} subprocess pid={}", threadId, p.pid());
            // Set the flag before destroy() so the runTurn() exit
            // handler observes it before the process's non-zero exit
            // gets classified. Without this, p.destroy() routes
            // through the ERRORED branch and the user has to click
            // Resume just to keep typing — the documented Interrupt
            // contract is "back to IDLE, ready for the next turn".
            userInterrupted.set(true);
            p.destroy();
        }
    }

    @Override
    public void pause()
    {
        // The CLI runs one shot per turn; there is no persistent loop
        // to suspend. "Pause" semantics for the CLI kind are: stop
        // accepting new turns until resume. We model that by flipping
        // to AWAITING; resume() flips back to IDLE.
        if (status.compareAndSet(ThreadStatus.IDLE, ThreadStatus.AWAITING)) {
            persistThreadSnapshot(null);
        }
    }

    @Override
    public void resume()
    {
        if (status.compareAndSet(ThreadStatus.AWAITING, ThreadStatus.IDLE)) {
            persistThreadSnapshot(null);
            return;
        }
        // ERRORED → IDLE: the user wants to continue the conversation
        // after the CLI turn failed (token-quota reset, network blip,
        // agent error). COMPLETED → IDLE: the user wants to follow up
        // on a thread the agent had marked done; the conversation isn't
        // really over, the user just has more to say. Both transitions
        // keep agentSessionId on the row so the next send() spawns
        // `claude -p ... --resume <session-id>` and the model picks up
        // exactly where the previous turn left off. We clear endedAt
        // and errorMessage so the Lifetime · Runtime ticker restarts
        // and the failure banner (if any) doesn't hover over the new
        // conversation.
        if (status.compareAndSet(ThreadStatus.ERRORED, ThreadStatus.IDLE)
                || status.compareAndSet(ThreadStatus.COMPLETED, ThreadStatus.IDLE)) {
            Thread current = store.findThreadById(threadId).orElse(null);
            if (current == null) {
                return;
            }
            // Flip the latest task back to IDLE first so the
            // thread-side saveThread cascade sees a non-terminal
            // active task and the two stay in sync. Without this, a
            // resumed-from-terminal thread would carry a COMPLETED /
            // ERRORED task forever — findActiveTaskForThread filters
            // those out, so the UI would render activeTask=null even
            // though the agent is running again.
            taskStore.findLatestTaskForThread(threadId).ifPresent(t -> {
                if (t.status() == TaskStatus.COMPLETED
                        || t.status() == TaskStatus.ERRORED) {
                    taskStore.saveTask(new Task(
                            t.id(), t.threadId(), t.seq(), TaskStatus.IDLE,
                            t.branchName(), t.worktreePath(), t.baseBranch(),
                            t.workingDir(), t.processPid(), t.logPath(),
                            t.prNumber(), t.prState(), t.ciState(),
                            t.taskType(), t.linkedPrNumber(), t.linkedIssueNumber(),
                            t.costUsdMilli(), t.tokensIn(), t.tokensOut(),
                            agentSessionId.get(),
                            t.createdAt(), /* endedAt */ null,
                            /* errorMessage */ null,
                            t.name(), t.roleSkill()));
                }
            });
            // Preserve the trunk planning session id on the Thread row;
            // the captured agent session id belongs on the Task and was
            // persisted via taskStore above.
            store.saveThread(new Thread(
                    current.id(), current.kind(), current.provider(), current.agentSessionId(),
                    current.title(), ThreadStatus.IDLE,
                    model.get(),
                    runningCostUsdMilli.get(), runningTokensIn.get(), runningTokensOut.get(),
                    current.createdAt(), Instant.now(),
                    /* endedAt */ null, /* errorMessage */ null,
                    current.flow(),
                    current.workspaceId(),
                    current.activeTask()));
        }
    }

    @Override
    public void stop()
    {
        interrupt();
        if (status.getAndSet(ThreadStatus.COMPLETED) != ThreadStatus.COMPLETED) {
            persistThreadSnapshot(Instant.now());
            handle(new StreamEvent.SessionEnded(Instant.now(), 0, null));
        }
        cleanupMcpConfig();
        cleanupSkillsDir();
    }

    @Override
    public void notifyPermissionRequested(String callId, String toolName, String summary)
    {
        requireNonNull(callId, "callId is null");
        handle(new StreamEvent.PermissionRequested(
                Instant.now(),
                callId,
                toolName == null ? "tool" : toolName,
                summary == null ? "" : summary));
    }

    @Override
    public void decide(String callId, PermissionDecision decision)
    {
        requireNonNull(callId, "callId is null");
        requireNonNull(decision, "decision is null");
        // Hand the decision to the MCP gate first — that unblocks the
        // subprocess waiting on its tools/call response. Then route
        // the event through handle() so it persists for replay.
        gate.decide(callId, decision);
        handle(new StreamEvent.PermissionDecided(Instant.now(), callId, decision));
    }

    @Override
    public void grantToolBudget(String toolName, int count)
    {
        requireNonNull(toolName, "toolName is null");
        if (count == BUDGET_ALWAYS) {
            toolBudget.put(toolName, BUDGET_ALWAYS);
        }
        else if (count > 0) {
            // Finite grants accumulate, but "always" wins and stays
            // sticky.
            toolBudget.merge(toolName, count, (prev, add) ->
                    prev == BUDGET_ALWAYS ? BUDGET_ALWAYS : prev + add);
        }
        else {
            return;
        }
        // Drain newly-granted slots against any callIds already
        // sitting in the MCP gate for this tool. The "approve N times
        // in a row" bug (docs/mockups/issue/tasks/approval-display.png)
        // happened because granting a budget only helped FUTURE
        // requests; existing pending ones still needed individual
        // user clicks. Now one "Allow next 5" click can sweep up to
        // five already-queued prompts.
        for (String pendingCallId : gate.pendingCallIdsFor(toolName)) {
            OptionalInt remaining = tryConsumeToolBudget(toolName);
            if (remaining.isEmpty()) {
                break;
            }
            decide(pendingCallId, PermissionDecision.ALLOW);
        }
    }

    @Override
    public OptionalInt tryConsumeToolBudget(String toolName)
    {
        if (toolName == null) {
            return OptionalInt.empty();
        }
        // Atomic decrement with floor 0 — concurrent MCP requests for
        // the same tool can race, but the remapping function makes sure
        // only the remaining slots are handed out. We capture the
        // pre-decrement value so we can return the post-decrement
        // remainder (or -1 for an ALWAYS grant); computeIfPresent only
        // hands us the new value, which can't distinguish "removed
        // because we consumed the last slot" from "absent".
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
    public void notifyPermissionAutoAllowed(String callId, String toolName, int remaining)
    {
        requireNonNull(callId, "callId is null");
        handle(new StreamEvent.PermissionAutoAllowed(
                Instant.now(),
                callId,
                toolName == null ? "tool" : toolName,
                remaining));
    }

    @Override
    public Runnable subscribeToEvents(Consumer<StreamEvent> listener)
    {
        requireNonNull(listener, "listener is null");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void runTurn(String userInput)
    {
        ProcessBuilder pb = buildCommand();
        // Clear any stale interrupt flag from a prior turn so a fresh
        // turn's non-zero exit isn't misread as a user cancellation.
        userInterrupted.set(false);
        Process process;
        try {
            process = pb.start();
        }
        catch (IOException e) {
            log.warn("Failed to spawn {} for thread {}: {}", binary, threadId, e.getMessage());
            transition(ThreadStatus.ERRORED);
            publish(new StreamEvent.ErrorOccurred(Instant.now(),
                    "failed to spawn " + binary + ": " + e.getMessage(), false));
            publish(new StreamEvent.SessionEnded(Instant.now(), -1, e.getMessage()));
            persistThreadSnapshot(Instant.now());
            return;
        }
        currentProcess.set(process);
        // The worktree lease is held by the registry for the lifetime
        // of this session — see ThreadRegistry.getOrCreate / evict —
        // so the per-turn subprocess doesn't manage it. That keeps
        // the lock in place while the human is reading the diff or
        // walking away between prompts, which is what the design
        // doc's "lease is the lock" wording assumes.
        try {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(userInput.getBytes(StandardCharsets.UTF_8));
            }
            drainStderr(process);
            consumeStdout(process);
            int exit = process.waitFor();
            if (exit != 0) {
                // p.destroy() from interrupt() always lands here with a
                // non-zero exit. Treat the user-initiated path as a
                // clean cancel: back to IDLE, no error banner, no
                // failure event — the user pressed the button on
                // purpose and wants to type the next instruction.
                if (userInterrupted.compareAndSet(true, false)) {
                    transition(ThreadStatus.IDLE);
                    publish(new StreamEvent.SessionEnded(Instant.now(), exit, "interrupted by user"));
                }
                else {
                    publish(new StreamEvent.ErrorOccurred(Instant.now(),
                            binary + " exited with code " + exit, true));
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
            // finishes reading it, surfacing here as "Broken pipe".
            // Same treatment as the non-zero-exit interrupt path —
            // clean IDLE, no error banner.
            if (userInterrupted.compareAndSet(true, false)) {
                transition(ThreadStatus.IDLE);
                publish(new StreamEvent.SessionEnded(Instant.now(), -1, "interrupted by user"));
            }
            else {
                log.warn("I/O error talking to {} for thread {}: {}", binary, threadId, e.getMessage());
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

    private ProcessBuilder buildCommand()
    {
        ImmutableList.Builder<String> argv = ImmutableList.<String>builder()
                .add(binary)
                .add("-p")
                .add("--output-format", "stream-json")
                .add("--verbose")
                // Surface the upstream Anthropic stream events (text
                // deltas, content_block_start/stop, message_delta) so
                // the parser can emit AssistantTextDelta events for
                // the in-flight assistant card. The fully assembled
                // assistant message still lands at message_stop and
                // takes precedence for persistence.
                .add("--include-partial-messages")
                .add("--mcp-config", ensureMcpConfig().toString())
                .add("--permission-prompt-tool", "mcp__bytequay__approval_prompt");
        // Inject the role skill body as the system role block. Frozen
        // at session construction so the prefix stays byte-stable for
        // the lifetime of the session — that's what keeps the cache
        // warm across turns. Skipped when null (legacy rows).
        if (roleSkillText != null && !roleSkillText.isBlank()) {
            argv.add("--append-system-prompt", roleSkillText.strip());
        }
        // Inject the workspace memory as an appended system prompt so
        // every turn sees the distilled project brain (architecture
        // decisions, conventions, blockers). Skip the flag when memory
        // is blank to avoid noise.
        String workspaceMemory = workspaceMemoryProvider.get();
        if (workspaceMemory != null && !workspaceMemory.isBlank()) {
            argv.add("--append-system-prompt",
                    "# Workspace memory\n\n" + workspaceMemory.strip());
        }
        String resume = agentSessionId.get();
        if (resume != null && !resume.isBlank()) {
            argv.add("--resume", resume);
        }
        ProcessBuilder pb = new ProcessBuilder(argv.build());
        pb.directory(Path.of(workingDir).toFile());
        pb.redirectErrorStream(false);
        // Cap the Node.js heap inside the Claude CLI subprocess so a
        // single thread can't blow through the app-wide ~8 GB budget on
        // its own. 512 MB is enough for the streaming JSON pipeline +
        // tool-use buffering we observe in practice; multiplied by the
        // scheduler's 4-way CLI concurrency lane (see
        // bytequay.threads.scheduler.max-cli-running) this keeps the
        // combined CLI heap around ~2 GB even with the lane full.
        // NODE_OPTIONS rides through env so it applies whether the user
        // installed claude as a global npm bin or via npx/yarn.
        pb.environment().merge(
                "NODE_OPTIONS",
                "--max-old-space-size=512",
                (existing, ours) -> existing.contains("--max-old-space-size") ? existing : existing + " " + ours);
        // Resolve + materialize the skill manifest into a session-
        // scoped temp dir. The CLI lane reads SKILL.md folders from
        // disk through its own discovery loop; the path lives in an
        // env var the integration picks up (no flag wiring yet — the
        // CLI's skill-discovery contract is still being firmed up).
        Path skills = ensureSkillsDir();
        if (skills != null) {
            pb.environment().put("BYTEQUAY_SKILLS_DIR", skills.toString());
        }
        return pb;
    }

    /** Lazily writes the per-thread MCP config to a temp file Claude
     *  reads. Same path for the lifetime of the session — we only
     *  rewrite if the temp file got nuked between turns. */
    private Path ensureMcpConfig()
    {
        Path existing = mcpConfigPath.get();
        if (existing != null && Files.isRegularFile(existing)) {
            return existing;
        }
        try {
            Path tmp = Files.createTempFile("bytequay-mcp-" + threadId + "-", ".json");
            String json = "{\"mcpServers\":{\"bytequay\":{"
                    + "\"type\":\"http\","
                    + "\"url\":\"http://127.0.0.1:53123/api/threads/" + threadId + "/mcp\""
                    + "}}}";
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            tmp.toFile().deleteOnExit();
            mcpConfigPath.set(tmp);
            return tmp;
        }
        catch (IOException e) {
            throw new IllegalStateException("Failed to write MCP config for thread " + threadId, e);
        }
    }

    private void cleanupMcpConfig()
    {
        Path p = mcpConfigPath.getAndSet(null);
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            }
            catch (IOException ignored) {
                // Best-effort — temp file gets cleaned on JVM exit anyway.
            }
        }
    }

    /** Re-materialize the resolved skills into a session-scoped temp
     *  dir on every buildCommand. Idempotent: the materializer
     *  overwrites SKILL.md files in place so a re-spawn picks up edits
     *  the user made between turns. Silently no-ops when no
     *  materializer was wired in (legacy / test paths). */
    private Path ensureSkillsDir()
    {
        if (skillMaterializer == null) {
            return null;
        }
        Path existing = skillsDir.get();
        if (existing == null) {
            try {
                existing = Files.createTempDirectory("bytequay-skills-" + threadId + "-");
                existing.toFile().deleteOnExit();
                skillsDir.set(existing);
            }
            catch (IOException e) {
                log.warn("Failed to create skills temp dir for thread {}: {}", threadId, e.getMessage());
                return null;
            }
        }
        try {
            skillMaterializer.materialize(existing, ToolContext.forRepo(repoFromWorkingDir(), null));
        }
        catch (RuntimeException e) {
            log.warn("Skill materialization failed for thread {}: {}", threadId, e.getMessage());
        }
        return existing;
    }

    private void cleanupSkillsDir()
    {
        Path p = skillsDir.getAndSet(null);
        if (p != null && skillMaterializer != null) {
            skillMaterializer.cleanup(p);
        }
    }

    /** Best-effort owner/repo extraction from the working dir. Returns
     *  null when the cwd doesn't follow the watched-repo convention —
     *  the manifest query then falls back to global-only rows. */
    private String repoFromWorkingDir()
    {
        if (workingDir == null) {
            return null;
        }
        Path path = Path.of(workingDir);
        Path name = path.getFileName();
        if (name == null) {
            return null;
        }
        Path parent = path.getParent();
        Path owner = parent == null ? null : parent.getFileName();
        if (owner == null) {
            return null;
        }
        return owner + "/" + name;
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

    private void drainStderr(Process process)
    {
        java.lang.Thread t = new java.lang.Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[{} stderr] {}", binary, line);
                }
            }
            catch (IOException ignored) {
                // Process exited; pipe is closed.
            }
        }, "thread-" + threadId + "-stderr");
        t.setDaemon(true);
        t.start();
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
            runningCostUsdMilli.addAndGet(t.costUsdMilli());
            runningTokensIn.addAndGet(t.tokensIn());
            runningTokensOut.addAndGet(t.tokensOut());
        }
        persistMessage(event);
        publish(event);
        if (turnDone) {
            // Fire-and-forget — the trigger schedules background work
            // and returns immediately, so a slow Anthropic call can't
            // back up the session's event thread.
            try {
                checkpointTrigger.onTurnDone(threadId);
            }
            catch (RuntimeException e) {
                log.warn("checkpoint trigger failed for thread {}: {}", threadId, e.getMessage());
            }
        }
    }

    /** Pull any file ops out of a tool call's input and upsert them
     *  into {@code thread_files}. {@code count} accumulates so the
     *  Files tab can render "touched 3 times" without a join. */
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
            store.appendMessage(msg);
        }
        catch (RuntimeException e) {
            log.warn("Failed to persist message for thread {}: {}", threadId, e.getMessage());
        }
    }

    private ThreadMessage toMessage(StreamEvent event)
    {
        long seq = nextSeq.getAndIncrement();
        String id = UUID.randomUUID().toString();
        Instant ts = event.timestamp();
        return switch (event) {
            case StreamEvent.SessionStarted e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "system", "session_started",
                    String.format("{\"sessionId\":\"%s\",\"cwd\":\"%s\",\"model\":\"%s\"}",
                            jsonEscape(e.sessionId()), jsonEscape(e.cwd()), jsonEscape(e.model())),
                    null, null, null, null, ts);
            case StreamEvent.UserMessage e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "user", "text",
                    "{\"text\":\"" + jsonEscape(e.text()) + "\"}",
                    null, null, null, null, ts);
            case StreamEvent.AssistantText e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "assistant", "text",
                    "{\"text\":\"" + jsonEscape(e.text()) + "\"}",
                    null, null, null, null, ts);
            // Live-only — deltas reach SSE subscribers and feed the
            // in-flight assistant card; the assembled AssistantText
            // is the durable form. Persisting deltas would inflate
            // the conversation table by ~1 row per token.
            case StreamEvent.AssistantTextDelta ignored -> null;
            case StreamEvent.ThinkingStarted e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "assistant", "thinking",
                    "{\"summary\":\"" + jsonEscape(e.summary()) + "\"}",
                    null, null, null, null, ts);
            case StreamEvent.ThinkingDone ignored -> null;
            case StreamEvent.ToolCallStarted e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "tool", "tool_call",
                    String.format("{\"callId\":\"%s\",\"toolName\":\"%s\",\"input\":%s}",
                            jsonEscape(e.callId()),
                            jsonEscape(e.toolName()),
                            e.inputJson().isEmpty() ? "null" : e.inputJson()),
                    null, null, null, null, ts);
            case StreamEvent.ToolCallDone e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "tool", "tool_result",
                    String.format("{\"callId\":\"%s\",\"isError\":%s,\"output\":%s}",
                            jsonEscape(e.callId()),
                            e.isError(),
                            e.outputJson().isEmpty() ? "null" : e.outputJson()),
                    null, null, null, null, ts);
            case StreamEvent.PermissionRequested e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "system", "permission_request",
                    String.format("{\"callId\":\"%s\",\"toolName\":\"%s\",\"summary\":\"%s\"}",
                            jsonEscape(e.callId()),
                            jsonEscape(e.toolName()),
                            jsonEscape(e.summary())),
                    null, null, null, null, ts);
            case StreamEvent.PermissionDecided e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "system", "permission_decision",
                    String.format("{\"callId\":\"%s\",\"decision\":\"%s\"}",
                            jsonEscape(e.callId()), e.decision().name()),
                    null, null, null, null, ts);
            case StreamEvent.PermissionAutoAllowed e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "system", "permission_auto_allowed",
                    String.format("{\"callId\":\"%s\",\"toolName\":\"%s\",\"remaining\":%d}",
                            jsonEscape(e.callId()),
                            jsonEscape(e.toolName()),
                            e.remaining()),
                    null, null, null, null, ts);
            // Live-only — in-flight token counters reach SSE subscribers
            // and overlay the metrics panel; TurnDone is still the
            // durable accounting row.
            case StreamEvent.UsageUpdated ignored -> null;
            case StreamEvent.TurnDone e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "system", "turn_done", "{}",
                    e.durationMs(), e.tokensIn(), e.tokensOut(), e.costUsdMilli(), ts);
            case StreamEvent.ErrorOccurred e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "system", "error",
                    String.format("{\"message\":\"%s\",\"recoverable\":%s}",
                            jsonEscape(e.message()), e.recoverable()),
                    null, null, null, null, ts);
            case StreamEvent.SessionEnded e -> new ThreadMessage(
                    id, threadId, activeTaskId, seq, "system", "session_ended",
                    String.format("{\"exitCode\":%d,\"errorMessage\":%s}",
                            e.exitCode(),
                            e.errorMessage() == null
                                    ? "null"
                                    : "\"" + jsonEscape(e.errorMessage()) + "\""),
                    null, null, null, null, ts);
        };
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
        // Task mode: push the captured session id down to the active
        // task and keep the Thread row's trunk session id untouched.
        // Trunk mode: the captured session IS the trunk session, so
        // it lands on threads.agent_session_id directly.
        if (activeTaskId != null
                && capturedSession != null
                && !capturedSession.isBlank()) {
            taskStore.findTaskById(activeTaskId).ifPresent(t -> {
                if (!capturedSession.equals(t.agentSessionId())) {
                    taskStore.saveTask(new Task(
                            t.id(), t.threadId(), t.seq(), t.status(),
                            t.branchName(), t.worktreePath(), t.baseBranch(),
                            t.workingDir(), t.processPid(), t.logPath(),
                            t.prNumber(), t.prState(), t.ciState(),
                            t.taskType(), t.linkedPrNumber(), t.linkedIssueNumber(),
                            t.costUsdMilli(), t.tokensIn(), t.tokensOut(),
                            capturedSession,
                            t.createdAt(), t.endedAt(), t.errorMessage(),
                            t.name(), t.roleSkill()));
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
                current.activeTask());
        store.saveThread(next);
    }

    private static String jsonEscape(String s)
    {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
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
