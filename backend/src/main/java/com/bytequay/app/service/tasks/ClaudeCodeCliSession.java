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
package com.bytequay.app.service.tasks;

import com.bytequay.app.domain.AgentMetrics;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskKind;
import com.bytequay.app.domain.TaskMessage;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Wraps the {@code claude} CLI as an {@link AgentSession}.
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
public class ClaudeCodeCliSession
        implements AgentSession
{
    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeCliSession.class);

    /** {@code claude}'s default binary name on PATH. Overridable in
     *  case the user installed it under a different name. */
    private static final String DEFAULT_BINARY = "claude";

    private final String taskId;
    private final TaskKind kind;
    private final String provider;
    private final String model;
    private final String workingDir;
    private final String branchName;
    private final String binary;
    private final TaskStore store;
    private final StreamJsonParser parser;
    private final ToolFileOps fileOps;
    private final ExecutorService executor;
    private final CopyOnWriteArrayList<Consumer<StreamEvent>> listeners = new CopyOnWriteArrayList<>();

    /** Pending permission decisions, keyed by the source tool's
     *  {@code call_id}. Populated by {@link #decide}; consumed by the
     *  permission-prompt MCP shim once we wire that up in a later
     *  slice. Kept here so callers can already exercise the API. */
    private final ConcurrentHashMap<String, PermissionDecision> pendingDecisions =
            new ConcurrentHashMap<>();

    private final AtomicReference<TaskStatus> status = new AtomicReference<>();
    private final AtomicReference<String> agentSessionId = new AtomicReference<>();
    private final AtomicReference<Process> currentProcess = new AtomicReference<>();
    private final AtomicLong nextSeq = new AtomicLong();

    private final AtomicLong runningCostUsdMilli = new AtomicLong();
    private final AtomicLong runningTokensIn = new AtomicLong();
    private final AtomicLong runningTokensOut = new AtomicLong();
    private final AtomicLong runningToolCallCount = new AtomicLong();
    private final long sessionStartedMs;

    public ClaudeCodeCliSession(
            Task task,
            TaskStore store,
            StreamJsonParser parser,
            ObjectMapper mapper,
            ExecutorService executor)
    {
        this(task, store, parser, mapper, executor, DEFAULT_BINARY);
    }

    ClaudeCodeCliSession(
            Task task,
            TaskStore store,
            StreamJsonParser parser,
            ObjectMapper mapper,
            ExecutorService executor,
            String binary)
    {
        requireNonNull(task, "task is null");
        if (task.kind() != TaskKind.CLI_AGENT) {
            throw new IllegalArgumentException("ClaudeCodeCliSession only handles CLI_AGENT tasks");
        }
        this.taskId = task.id();
        this.kind = task.kind();
        this.provider = task.provider();
        this.model = task.model();
        this.workingDir = task.workingDir();
        this.branchName = task.branchName();
        this.store = requireNonNull(store, "store is null");
        this.parser = requireNonNull(parser, "parser is null");
        this.fileOps = new ToolFileOps(requireNonNull(mapper, "mapper is null"));
        this.executor = requireNonNull(executor, "executor is null");
        this.binary = requireNonNull(binary, "binary is null");
        this.status.set(task.status());
        this.agentSessionId.set(task.agentSessionId());
        this.runningCostUsdMilli.set(task.costUsdMilli());
        this.runningTokensIn.set(task.tokensIn());
        this.runningTokensOut.set(task.tokensOut());
        this.sessionStartedMs = task.createdAt().toEpochMilli();
        // Seed the seq counter from any existing rows so a restart
        // doesn't collide with prior persisted messages.
        long highest = store.listMessages(taskId).stream()
                .mapToLong(TaskMessage::seq)
                .max()
                .orElse(-1L);
        this.nextSeq.set(highest + 1L);
    }

    @Override
    public String id()
    {
        return taskId;
    }

    @Override
    public TaskKind kind()
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
        return model;
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
    public TaskStatus status()
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
                store.listFiles(taskId).size());
    }

    @Override
    public List<TaskMessage> history()
    {
        return store.listMessages(taskId);
    }

    @Override
    public void send(String userInput)
    {
        requireNonNull(userInput, "userInput is null");
        TaskStatus current = status.get();
        if (current == TaskStatus.COMPLETED || current == TaskStatus.ERRORED) {
            throw new IllegalStateException(
                    "task is in terminal status " + current + "; cannot send more input");
        }
        if (current == TaskStatus.RUNNING) {
            throw new IllegalStateException("a turn is already in flight");
        }
        transition(TaskStatus.RUNNING);
        // Echo the user input so subscribers see the full conversation
        // even though the source CLI doesn't replay it for us.
        Instant now = Instant.now();
        publish(new StreamEvent.UserMessage(now, userInput));
        executor.submit(() -> runTurn(userInput));
    }

    @Override
    public void interrupt()
    {
        Process p = currentProcess.get();
        if (p != null && p.isAlive()) {
            log.info("Interrupting task {} subprocess pid={}", taskId, p.pid());
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
        if (status.compareAndSet(TaskStatus.IDLE, TaskStatus.AWAITING)) {
            persistTaskSnapshot(null);
        }
    }

    @Override
    public void resume()
    {
        if (status.compareAndSet(TaskStatus.AWAITING, TaskStatus.IDLE)) {
            persistTaskSnapshot(null);
        }
    }

    @Override
    public void stop()
    {
        interrupt();
        if (status.getAndSet(TaskStatus.COMPLETED) != TaskStatus.COMPLETED) {
            persistTaskSnapshot(Instant.now());
            publish(new StreamEvent.SessionEnded(Instant.now(), 0, null));
        }
    }

    @Override
    public void decide(String callId, PermissionDecision decision)
    {
        requireNonNull(callId, "callId is null");
        requireNonNull(decision, "decision is null");
        // Idempotent: only the first decision for a callId wins.
        pendingDecisions.putIfAbsent(callId, decision);
        publish(new StreamEvent.PermissionDecided(Instant.now(), callId, decision));
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
        Process process;
        try {
            process = pb.start();
        }
        catch (IOException e) {
            log.warn("Failed to spawn {} for task {}: {}", binary, taskId, e.getMessage());
            transition(TaskStatus.ERRORED);
            publish(new StreamEvent.ErrorOccurred(Instant.now(),
                    "failed to spawn " + binary + ": " + e.getMessage(), false));
            publish(new StreamEvent.SessionEnded(Instant.now(), -1, e.getMessage()));
            persistTaskSnapshot(Instant.now());
            return;
        }
        currentProcess.set(process);
        try {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(userInput.getBytes(StandardCharsets.UTF_8));
            }
            drainStderr(process);
            consumeStdout(process);
            int exit = process.waitFor();
            if (exit != 0) {
                publish(new StreamEvent.ErrorOccurred(Instant.now(),
                        binary + " exited with code " + exit, true));
                transition(TaskStatus.ERRORED);
                publish(new StreamEvent.SessionEnded(Instant.now(), exit, "non-zero exit"));
            }
            else {
                transition(TaskStatus.IDLE);
            }
        }
        catch (IOException e) {
            log.warn("I/O error talking to {} for task {}: {}", binary, taskId, e.getMessage());
            transition(TaskStatus.ERRORED);
            publish(new StreamEvent.ErrorOccurred(Instant.now(), e.getMessage(), false));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            transition(TaskStatus.IDLE);
        }
        finally {
            currentProcess.set(null);
            persistTaskSnapshot(null);
        }
    }

    private ProcessBuilder buildCommand()
    {
        // --dangerously-skip-permissions is needed because headless
        // {@code -p} mode otherwise hangs the moment the agent wants
        // to call a tool that requires a permission prompt. We accept
        // the trade-off for v1: the user delegated the task on
        // purpose. MCP-based gating wired through our Allow / Deny
        // banner is the follow-up that earns this flag back.
        ImmutableList.Builder<String> argv = ImmutableList.<String>builder()
                .add(binary)
                .add("-p")
                .add("--output-format", "stream-json")
                .add("--verbose")
                .add("--dangerously-skip-permissions");
        String resume = agentSessionId.get();
        if (resume != null && !resume.isBlank()) {
            argv.add("--resume", resume);
        }
        ProcessBuilder pb = new ProcessBuilder(argv.build());
        pb.directory(Path.of(workingDir).toFile());
        pb.redirectErrorStream(false);
        return pb;
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
        Thread t = new Thread(() -> {
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
        }, "task-" + taskId + "-stderr");
        t.setDaemon(true);
        t.start();
    }

    private void handle(StreamEvent event)
    {
        if (event instanceof StreamEvent.SessionStarted s && agentSessionId.get() == null) {
            agentSessionId.set(s.sessionId());
        }
        if (event instanceof StreamEvent.ToolCallStarted call) {
            runningToolCallCount.incrementAndGet();
            recordFileOps(call);
        }
        if (event instanceof StreamEvent.TurnDone t) {
            runningCostUsdMilli.addAndGet(t.costUsdMilli());
            runningTokensIn.addAndGet(t.tokensIn());
            runningTokensOut.addAndGet(t.tokensOut());
        }
        persistMessage(event);
        publish(event);
    }

    /** Pull any file ops out of a tool call's input and upsert them
     *  into {@code task_files}. {@code count} accumulates so the
     *  Files tab can render "touched 3 times" without a join. */
    private void recordFileOps(StreamEvent.ToolCallStarted call)
    {
        List<ToolFileOps.FileOp> parsed = fileOps.parse(call.toolName(), call.inputJson());
        if (parsed.isEmpty()) {
            return;
        }
        Map<String, TaskFile> existing = new HashMap<>();
        for (TaskFile f : store.listFiles(taskId)) {
            existing.put(f.path(), f);
        }
        for (ToolFileOps.FileOp op : parsed) {
            TaskFile prior = existing.get(op.path());
            int count = (prior == null ? 0 : prior.count()) + 1;
            int linesAdded = (prior == null ? 0 : prior.linesAdded()) + op.linesAdded();
            int linesRemoved = (prior == null ? 0 : prior.linesRemoved()) + op.linesRemoved();
            try {
                store.recordFile(new TaskFile(
                        taskId, op.path(), op.operation(),
                        count, linesAdded, linesRemoved, call.timestamp()));
            }
            catch (RuntimeException e) {
                log.warn("Failed to record file op for task {}: {}", taskId, e.getMessage());
            }
        }
    }

    private void persistMessage(StreamEvent event)
    {
        TaskMessage msg = toMessage(event);
        if (msg == null) {
            return;
        }
        try {
            store.appendMessage(msg);
        }
        catch (RuntimeException e) {
            log.warn("Failed to persist message for task {}: {}", taskId, e.getMessage());
        }
    }

    private TaskMessage toMessage(StreamEvent event)
    {
        long seq = nextSeq.getAndIncrement();
        String id = UUID.randomUUID().toString();
        Instant ts = event.timestamp();
        return switch (event) {
            case StreamEvent.SessionStarted e -> new TaskMessage(
                    id, taskId, seq, "system", "session_started",
                    String.format("{\"sessionId\":\"%s\",\"cwd\":\"%s\",\"model\":\"%s\"}",
                            jsonEscape(e.sessionId()), jsonEscape(e.cwd()), jsonEscape(e.model())),
                    null, null, null, null, ts);
            case StreamEvent.UserMessage e -> new TaskMessage(
                    id, taskId, seq, "user", "text",
                    "{\"text\":\"" + jsonEscape(e.text()) + "\"}",
                    null, null, null, null, ts);
            case StreamEvent.AssistantText e -> new TaskMessage(
                    id, taskId, seq, "assistant", "text",
                    "{\"text\":\"" + jsonEscape(e.text()) + "\"}",
                    null, null, null, null, ts);
            case StreamEvent.ThinkingStarted e -> new TaskMessage(
                    id, taskId, seq, "assistant", "thinking",
                    "{\"summary\":\"" + jsonEscape(e.summary()) + "\"}",
                    null, null, null, null, ts);
            case StreamEvent.ThinkingDone ignored -> null;
            case StreamEvent.ToolCallStarted e -> new TaskMessage(
                    id, taskId, seq, "tool", "tool_call",
                    String.format("{\"callId\":\"%s\",\"toolName\":\"%s\",\"input\":%s}",
                            jsonEscape(e.callId()),
                            jsonEscape(e.toolName()),
                            e.inputJson().isEmpty() ? "null" : e.inputJson()),
                    null, null, null, null, ts);
            case StreamEvent.ToolCallDone e -> new TaskMessage(
                    id, taskId, seq, "tool", "tool_result",
                    String.format("{\"callId\":\"%s\",\"isError\":%s,\"output\":%s}",
                            jsonEscape(e.callId()),
                            e.isError(),
                            e.outputJson().isEmpty() ? "null" : e.outputJson()),
                    null, null, null, null, ts);
            case StreamEvent.PermissionRequested e -> new TaskMessage(
                    id, taskId, seq, "system", "permission_request",
                    String.format("{\"callId\":\"%s\",\"toolName\":\"%s\",\"summary\":\"%s\"}",
                            jsonEscape(e.callId()),
                            jsonEscape(e.toolName()),
                            jsonEscape(e.summary())),
                    null, null, null, null, ts);
            case StreamEvent.PermissionDecided e -> new TaskMessage(
                    id, taskId, seq, "system", "permission_decision",
                    String.format("{\"callId\":\"%s\",\"decision\":\"%s\"}",
                            jsonEscape(e.callId()), e.decision().name()),
                    null, null, null, null, ts);
            case StreamEvent.TurnDone e -> new TaskMessage(
                    id, taskId, seq, "system", "turn_done", "{}",
                    e.durationMs(), e.tokensIn(), e.tokensOut(), e.costUsdMilli(), ts);
            case StreamEvent.ErrorOccurred e -> new TaskMessage(
                    id, taskId, seq, "system", "error",
                    String.format("{\"message\":\"%s\",\"recoverable\":%s}",
                            jsonEscape(e.message()), e.recoverable()),
                    null, null, null, null, ts);
            case StreamEvent.SessionEnded e -> new TaskMessage(
                    id, taskId, seq, "system", "session_ended",
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
                log.warn("Subscriber for task {} threw: {}", taskId, e.getMessage());
            }
        }
    }

    private void transition(TaskStatus next)
    {
        TaskStatus prev = status.getAndSet(next);
        if (prev != next) {
            persistTaskSnapshot(next == TaskStatus.COMPLETED || next == TaskStatus.ERRORED
                    ? Instant.now()
                    : null);
        }
    }

    private void persistTaskSnapshot(Instant endedAt)
    {
        Task current = store.findTaskById(taskId).orElse(null);
        if (current == null) {
            return;
        }
        Task next = new Task(
                current.id(), current.kind(), current.provider(), agentSessionId.get(),
                current.title(), status.get(), current.workingDir(), current.branchName(),
                current.model(),
                runningCostUsdMilli.get(), runningTokensIn.get(), runningTokensOut.get(),
                current.processPid(), current.logPath(),
                current.createdAt(), Instant.now(),
                endedAt != null ? endedAt : current.endedAt(),
                current.errorMessage(), current.metadataJson());
        store.saveTask(next);
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
     *  task so jstack output is readable. */
    public static ExecutorService defaultExecutor()
    {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "task-runner");
            t.setDaemon(true);
            return t;
        });
    }
}
